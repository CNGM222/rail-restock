package dev.railrestock.runtime;

import dev.railrestock.RailRestockClient;
import dev.railrestock.config.RestockConfig;
import dev.railrestock.integration.LitematicaBridge;
import dev.railrestock.logic.ScreenHider;
import dev.railrestock.logic.TransferPlanner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-thread state machine. Every interaction goes through vanilla APIs, at
 * vanilla reach: no movement, no background inventory reading, no invented
 * success. Unobtrusive mode only suppresses the local container screen, so the
 * mouse stays captured, the view never turns and the held slot never changes.
 */
public final class RestockController {
    private enum Phase {
        IDLE, PREPARE, OPENING, VERIFY_OPEN, READY, PICKUP_CHECK, DEPOSIT,
        RETURN_REMAINDER, SETTLE, REOPEN_DELAY, MANUAL_PAUSE
    }

    private final MinecraftClient client;
    private final RestockConfig config;
    private final LitematicaBridge bridge = new LitematicaBridge();
    private final ContainerScanner scanner = new ContainerScanner();
    private final Map<Long, Long> cooldowns = new HashMap<>();

    private Phase phase = Phase.IDLE;
    private boolean enabled;
    private boolean stopRequested;
    private Object worldIdentity;
    private long tick;
    private long nextScan;
    private long nextAction;
    private long deadline;
    private long lastRecount;
    private long countBaseline;
    private boolean awaitingCount;
    private boolean countRequestQueued;

    private LitematicaBridge.Context context;
    private String scope;
    private ContainerScanner.Candidate candidate;
    private ScreenHandler ownedHandler;
    private long openBaseline;
    private boolean ownOpenRequested;
    private boolean hiddenSession;
    private boolean closingInternally;

    private ItemStack transferItem = ItemStack.EMPTY;
    private TransferPlanner.Plan plan;
    private int sourceSlot = -1;
    private int remainingToDeposit;
    private long beforeDirect;
    private long confirmedThisVisit;
    private boolean checkingTransfer;

    private String status = "已关闭";
    private String lastChat = "";
    private long lastChatTick = -1000;

    public RestockController(MinecraftClient client, RestockConfig config) {
        this.client = client;
        this.config = config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        if (phase == Phase.MANUAL_PAUSE) {
            phase = Phase.IDLE;
            InteractionGate.setBusy(false);
        }
        enabled = !enabled;
        if (enabled) {
            stopRequested = false;
            nextScan = tick;
            notice("自动补料已开启：按所选投影的全部缺口取料");
        } else {
            stopRequested = phase != Phase.IDLE;
            notice(stopRequested ? "正在安全结束本次补料" : "自动补料已关闭");
        }
    }

    /** Called by real user interaction with a screen we own, never by our own clicks. */
    public void manualTakeover() {
        if (phase == Phase.IDLE || phase == Phase.MANUAL_PAUSE) return;
        enabled = false;
        stopRequested = false;
        ownedHandler = null;
        candidate = null;
        phase = Phase.MANUAL_PAUSE;
        InteractionGate.setBusy(true);
        notice("你已接管容器，自动补料已关闭；不会移动你手上的物品");
    }

    public boolean ownsCurrentScreen() {
        if (client.player == null) return false;
        return ownedHandler != null && client.currentScreen instanceof HandledScreen<?>
                && client.player.currentScreenHandler == ownedHandler;
    }

    /**
     * Called from the head of {@code MinecraftClient.setScreen}. Returning true
     * cancels the local screen change only; the container handler and the server
     * session stay exactly as vanilla established them.
     */
    public boolean interceptScreenChange(Screen requested) {
        if (closingInternally) return false;
        if (requested == null) {
            hiddenSession = false;
            return false;
        }
        if (client.player == null || client.world == null) return false;

        boolean hide = ScreenHider.hide(new ScreenHider.Facts(
                config.unobtrusiveInteraction,
                ownOpenRequested,
                requested instanceof HandledScreen<?>,
                supportedHandler(client.player.currentScreenHandler),
                client.currentScreen == null));
        if (hide) {
            hiddenSession = true;
            ownOpenRequested = false;
            return true;
        }

        // A screen we did not ask for: the user or another mod took over. Close our
        // hidden container first so their screen is not bound to our handler.
        if (hiddenSession) {
            if (!safelyCloseHiddenSession()) {
                manualTakeover();
                notice("无法安全归位鼠标物品，已让出控制");
            }
            return false;
        }

        if (phase != Phase.IDLE && phase != Phase.MANUAL_PAUSE && !ownsCurrentScreen()) {
            // Chat, inventory, pause menu...: stop before clicking a stale handler.
            enabled = false;
            stopRequested = true;
        }
        return false;
    }

    private boolean safelyCloseHiddenSession() {
        hiddenSession = false;
        if (client.player == null) return true;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == client.player.playerScreenHandler) {
            ownedHandler = null;
            ownOpenRequested = false;
            return true;
        }
        if (!handler.getCursorStack().isEmpty() && !returnCursorToSource(handler)) return false;
        closingInternally = true;
        try {
            client.player.closeHandledScreen();
        } finally {
            closingInternally = false;
        }
        ownedHandler = null;
        ownOpenRequested = false;
        return true;
    }

    private boolean returnCursorToSource(ScreenHandler handler) {
        if (sourceSlot < 0 || sourceSlot >= handler.slots.size()) return false;
        ItemStack cursor = handler.getCursorStack();
        Slot source = handler.getSlot(sourceSlot);
        if (!InventorySlots.matches(cursor, transferItem) || InventorySlots.space(source, cursor) < cursor.getCount()) {
            return false;
        }
        client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, client.player);
        return handler.getCursorStack().isEmpty();
    }

    public void disconnect() {
        if (hiddenSession && client.player != null) {
            hiddenSession = false;
            closingInternally = true;
            try {
                client.player.closeHandledScreen();
            } catch (RuntimeException ignored) {
                // The connection is already gone; nothing else to clean up.
            } finally {
                closingInternally = false;
            }
        }
        enabled = false;
        stopRequested = false;
        worldIdentity = null;
        context = null;
        scope = null;
        awaitingCount = false;
        countRequestQueued = false;
        ownedHandler = null;
        candidate = null;
        ownOpenRequested = false;
        hiddenSession = false;
        phase = Phase.IDLE;
        cooldowns.clear();
        InteractionGate.setBusy(false);
        InventorySyncTracker.reset();
        MaterialGenerationTracker.reset();
    }

    public void tick() {
        tick++;
        if (client.world == null || client.player == null) {
            if (worldIdentity != null) disconnect();
            return;
        }
        if (worldIdentity != client.world) {
            if (worldIdentity != null) disconnect();
            worldIdentity = client.world;
        }
        try {
            if (phase == Phase.MANUAL_PAUSE) {
                if (client.currentScreen == null && client.player.currentScreenHandler == client.player.playerScreenHandler) {
                    phase = Phase.IDLE;
                    InteractionGate.setBusy(false);
                    nextScan = tick + config.manualPauseTicks;
                }
                showStatus();
                return;
            }
            if (!enabled && phase == Phase.IDLE) {
                InteractionGate.setBusy(false);
                return;
            }
            if (!client.player.isAlive() || client.player.isSpectator()) {
                enabled = false;
                abort("当前玩家状态不能补料");
                return;
            }
            if (ownedHandler != null && client.player.currentScreenHandler != ownedHandler) {
                ownedHandler = null;
                finish("容器已关闭或被替换，稍后重新扫描", false);
                return;
            }
            if (phase != Phase.IDLE && context != null) {
                if (stopRequested || !bridge.isStillSelected(context)
                        || !bridge.isPlacementEnabled(context)
                        || !scope.equals(bridge.scopeFingerprint(context))) {
                    abort(stopRequested ? "自动补料已关闭" : "投影或统计范围已变化，已结束旧任务");
                    return;
                }
                if (phase != Phase.REOPEN_DELAY && candidate != null
                        && scanner.hit(client, candidate.pos(), config) == null) {
                    abort("已离开容器可交互范围，等待下次经过");
                    return;
                }
            }
            switch (phase) {
                case IDLE -> idle();
                case PREPARE -> prepare();
                case OPENING, VERIFY_OPEN -> waitForOpen();
                case READY -> ready();
                case PICKUP_CHECK -> checkPickup();
                case DEPOSIT -> deposit();
                case RETURN_REMAINDER -> returnRemainder();
                case SETTLE -> settle();
                case REOPEN_DELAY -> reopen();
                default -> { }
            }
            showStatus();
        } catch (RuntimeException | LinkageError error) {
            RailRestockClient.LOGGER.error("Rail Restock 已安全停用", error);
            enabled = false;
            notice("补料已停用：" + LitematicaBridge.describeFailure(error));
            try {
                abort("集成或交互失败；未继续发送操作");
            } catch (RuntimeException ignored) {
                ownedHandler = null;
                ownOpenRequested = false;
                hiddenSession = false;
                phase = client.currentScreen == null ? Phase.IDLE : Phase.MANUAL_PAUSE;
                InteractionGate.setBusy(phase == Phase.MANUAL_PAUSE);
            }
        }
    }

    private void idle() {
        InteractionGate.setBusy(false);
        if (!enabled || tick < nextScan) return;
        nextScan = tick + config.scanIntervalTicks;
        if (client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler) {
            status = "等待你关闭当前界面";
            return;
        }
        if (!client.player.playerScreenHandler.getCursorStack().isEmpty()) {
            status = "等待归位鼠标上的物品";
            return;
        }
        LitematicaBridge.Context selected = bridge.resolveContext();
        if (selected == null || !bridge.isPlacementEnabled(selected)) {
            context = null;
            status = "请先选中并启用一个 Litematica 投影";
            return;
        }
        String currentScope = bridge.scopeFingerprint(selected);
        if (context == null || context.placement() != selected.placement()
                || context.materialList() != selected.materialList() || !currentScope.equals(scope)) {
            context = selected;
            scope = currentScope;
            requestCount();
            status = config.autoRecountMaterialList
                    ? "正在统计投影材料：" + context.name()
                    : "使用已有材料清单：" + context.name();
            return;
        }
        if (!countReady()) return;
        if (config.autoRecountMaterialList && tick - lastRecount >= config.recountIntervalTicks) {
            requestCount();
            status = "正在更新建筑缺失数量";
            return;
        }
        List<LitematicaBridge.Need> needs = bridge.needs(context, client.player);
        if (needs.stream().noneMatch(n -> n.deficit() > 0 && n.stack() instanceof ItemStack s && !s.isEmpty())) {
            status = "材料已满足清单需求，持续待命";
            return;
        }
        if (openingHand() == null) {
            status = "请释放潜行/物品使用后再开始补料";
            return;
        }
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= tick);
        List<ContainerScanner.Candidate> found = scanner.find(client, config, cooldowns, tick);
        if (found.isEmpty()) {
            status = "扫描中：附近没有可正常交互的补料容器";
            return;
        }
        candidate = found.getFirst();
        confirmedThisVisit = 0;
        checkingTransfer = false;
        InteractionGate.setBusy(true);
        // Freeze printing before the final count, otherwise consumed blocks would
        // turn a stale "missing" count into an artificial restocking deficit.
        requestCount();
        phase = Phase.PREPARE;
        deadline = tick + config.syncTimeoutTicks;
        status = "暂停轻松放置，核对本次真实缺口";
    }

    private void requestCount() {
        if (!config.autoRecountMaterialList) {
            // Automatic recount is off. Never schedule Litematica's count task: that
            // is what prints the "scheduled task added" chat line and HUD progress.
            awaitingCount = false;
            countRequestQueued = false;
            return;
        }
        awaitingCount = true;
        countRequestQueued = true;
        lastRecount = tick;
        scheduleFreshCountIfDrained();
    }

    private void scheduleFreshCountIfDrained() {
        if (countRequestQueued && !bridge.isRecountPending()) {
            // Take the baseline only after pre-existing tasks drained: a task created
            // for an old range must never certify a new or frozen snapshot.
            countBaseline = MaterialGenerationTracker.generation(context.materialList());
            bridge.requestRecount(context);
            countRequestQueued = false;
            lastRecount = tick;
        }
    }

    private boolean countReady() {
        if (!config.autoRecountMaterialList) {
            // Use whatever the last completed count produced; never trigger a new one.
            if (MaterialGenerationTracker.generation(context.materialList()) <= 0L) {
                status = "材料清单尚未统计：请先在 Litematica 刷新一次（自动重算已关闭）";
                return false;
            }
            return true;
        }
        if (countRequestQueued) {
            scheduleFreshCountIfDrained();
            status = countRequestQueued ? "等待旧统计任务结束，再统计当前范围" : "已开始新的材料统计";
            return false;
        }
        if (bridge.isRecountPending()) {
            status = "等待材料统计完成（可能需要投影区块加载）";
            return false;
        }
        if (awaitingCount) {
            if (MaterialGenerationTracker.generation(context.materialList()) <= countBaseline) {
                status = "材料统计尚未成功提交，稍后重试";
                if (tick - lastRecount >= config.recountIntervalTicks) requestCount();
                return false;
            }
            awaitingCount = false;
        }
        return true;
    }

    private void prepare() {
        if (tick > deadline) {
            finish("本次经过来不及完成材料统计，等待下一圈", false);
            return;
        }
        if (!countReady()) return;
        if (bridge.needs(context, client.player).stream().noneMatch(n -> n.deficit() > 0)) {
            finish("当前材料已够用，继续轻松放置", false);
            return;
        }
        startOpen(false);
    }

    /**
     * Container blocks consume the interaction and return SUCCESS, so a non-empty
     * main hand still opens them without using the held item. That is why the
     * selected hotbar slot never has to change.
     */
    private Hand openingHand() {
        if (client.player.isSneaking() || client.player.isUsingItem()) return null;
        return Hand.MAIN_HAND;
    }

    private void startOpen(boolean verification) {
        if (client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler) {
            manualTakeover();
            return;
        }
        BlockHitResult hit = scanner.hit(client, candidate.pos(), config);
        Hand hand = openingHand();
        if (hit == null || hand == null) {
            finish(verification ? "已移出补料点，转移尚未取得完整同步确认" : "容器目前不可正常打开", false);
            return;
        }
        openBaseline = InventorySyncTracker.sequence();
        ownedHandler = null;
        ownOpenRequested = true;
        hiddenSession = false;
        phase = verification ? Phase.VERIFY_OPEN : Phase.OPENING;
        deadline = tick + config.openTimeoutTicks;
        status = verification ? "重新开箱，等待服务端权威库存" : "正在打开附近容器";
        client.interactionManager.interactBlock(client.player, hand, hit);
    }

    private boolean supportedHandler(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler || handler instanceof ShulkerBoxScreenHandler;
    }

    private void waitForOpen() {
        ScreenHandler current = client.player.currentScreenHandler;
        if (current == client.player.playerScreenHandler) {
            if (tick > deadline) finish("开箱超时，等待下次经过", false);
            return;
        }
        if (ownedHandler == null) {
            if (!supportedHandler(current) || !(ownOpenRequested || hiddenSession)) {
                manualTakeover();
                notice("打开的不是补料容器，已让出控制");
                return;
            }
            ownedHandler = current;
            ownOpenRequested = false;
        }
        if (!InventorySyncTracker.hasFullUpdateAfter(ownedHandler.syncId, openBaseline)) {
            if (tick > deadline) finish("开箱或完整库存同步超时，等待下次经过", false);
            return;
        }
        if (!ownedHandler.getCursorStack().isEmpty()) {
            enabled = false;
            manualTakeover();
            notice("服务端同步后鼠标仍有物品，已停止自动操作，请检查");
            return;
        }
        if (phase == Phase.VERIFY_OPEN) {
            long actual = InventorySlots.countDirect(client.player, transferItem) - beforeDirect;
            bridge.refresh(context, client.player);
            checkingTransfer = false;
            if (actual != plan.transferCount()) {
                finish("服务端库存变化与计划不同（实际变化 " + actual + "），已重新核对清单", false);
                return;
            }
            confirmedThisVisit += actual;
            status = "服务端已确认补入 " + actual + " 个 " + transferItem.getName().getString();
        }
        phase = Phase.READY;
        nextAction = tick + config.clickIntervalTicks;
    }

    private void ready() {
        if (tick < nextAction) return;
        if (bridge.isRecountPending()) {
            finish("材料清单正在重新统计，先结束本轮补料", confirmedThisVisit > 0);
            return;
        }
        if (!ownedHandler.getCursorStack().isEmpty()) {
            enabled = false;
            manualTakeover();
            return;
        }
        List<LitematicaBridge.Need> needs = bridge.needs(context, client.player);
        boolean matchingButNoSpace = false;
        for (Slot slot : ownedHandler.slots) {
            if (InventorySlots.isPlayerSlot(slot, client.player) || !slot.canTakeItems(client.player)) continue;
            ItemStack source = slot.getStack();
            if (source.isEmpty()) continue;
            long deficit = deficit(needs, source);
            if (deficit <= 0) continue;
            int capacity = InventorySlots.capacity(ownedHandler, client.player, source);
            TransferPlanner.Plan nextPlan = TransferPlanner.planWithoutQuickMove(source.getCount(), deficit, capacity);
            if (nextPlan.kind() == TransferPlanner.Kind.NONE) {
                matchingButNoSpace = true;
                continue;
            }
            sourceSlot = slot.id;
            transferItem = source.copy();
            beforeDirect = InventorySlots.countDirect(client.player, transferItem);
            plan = nextPlan;
            remainingToDeposit = plan.transferCount();
            checkingTransfer = true;
            click(sourceSlot, plan.kind() == TransferPlanner.Kind.PICKUP_HALF ? 1 : 0, SlotActionType.PICKUP);
            phase = Phase.PICKUP_CHECK;
            nextAction = tick + config.clickIntervalTicks;
            return;
        }
        finish(matchingButNoSpace ? "背包容量或安全暂存空间不足" : "该容器没有更多待补材料", confirmedThisVisit > 0);
    }

    private static long deficit(List<LitematicaBridge.Need> needs, ItemStack source) {
        long result = 0;
        for (LitematicaBridge.Need need : needs) {
            if (need.stack() instanceof ItemStack stack && InventorySlots.matches(stack, source)) {
                result = Math.max(result, need.deficit());
            }
        }
        return result;
    }

    private void checkPickup() {
        if (tick < nextAction) return;
        ItemStack cursor = ownedHandler.getCursorStack();
        if (!InventorySlots.matches(cursor, transferItem) || cursor.getCount() != plan.pickupCount()) {
            abort("取料预测被服务端改变，已停止本次操作");
            return;
        }
        phase = Phase.DEPOSIT;
        deposit();
    }

    private void deposit() {
        if (tick < nextAction) return;
        ItemStack cursor = ownedHandler.getCursorStack();
        if (cursor.isEmpty()) {
            phase = Phase.SETTLE;
            nextAction = tick + config.clickIntervalTicks;
            return;
        }
        if (!InventorySlots.matches(cursor, transferItem)) {
            manualTakeover();
            return;
        }
        if (bridge.isRecountPending()) remainingToDeposit = 0;
        else remainingToDeposit = (int) Math.min(remainingToDeposit, deficit(bridge.needs(context, client.player), transferItem));
        if (remainingToDeposit <= 0) {
            phase = Phase.RETURN_REMAINDER;
            returnRemainder();
            return;
        }
        Slot destination = InventorySlots.destination(ownedHandler, client.player, cursor);
        if (destination == null) {
            abort("可用背包空间发生变化，停止本次取料");
            return;
        }
        int move = cursor.getCount() <= remainingToDeposit
                && InventorySlots.space(destination, cursor) >= cursor.getCount() ? cursor.getCount() : 1;
        click(destination.id, move == cursor.getCount() ? 0 : 1, SlotActionType.PICKUP);
        remainingToDeposit -= move;
        nextAction = tick + config.clickIntervalTicks;
        if (remainingToDeposit == 0) phase = Phase.RETURN_REMAINDER;
    }

    private void returnRemainder() {
        if (tick < nextAction) return;
        ItemStack cursor = ownedHandler.getCursorStack();
        if (cursor.isEmpty()) {
            phase = Phase.SETTLE;
            nextAction = tick + config.clickIntervalTicks;
            return;
        }
        if (!returnCursorToSource(ownedHandler)) {
            abort("源槽位已变化，结束当前取料并安全归位物品");
            return;
        }
        phase = Phase.SETTLE;
        nextAction = tick + config.clickIntervalTicks;
    }

    private void settle() {
        if (tick < nextAction) return;
        if (!ownedHandler.getCursorStack().isEmpty()) {
            abort("鼠标物品尚未归位，取消后续取料");
            return;
        }
        // Accepted vanilla clicks need not be acknowledged individually. Closing and
        // normally reopening supplies a fresh authoritative snapshot instead.
        closeOwned();
        phase = Phase.REOPEN_DELAY;
        nextAction = tick + 1;
        deadline = tick + config.syncTimeoutTicks;
        status = "等待重新开箱核实实际转移数量";
    }

    private void reopen() {
        if (tick < nextAction) return;
        if (tick > deadline) {
            finish("库存确认超时，本次转移未标记成功", false);
            return;
        }
        startOpen(true);
    }

    private void click(int slot, int button, SlotActionType type) {
        if (ownedHandler == null || client.player.currentScreenHandler != ownedHandler
                || scanner.hit(client, candidate.pos(), config) == null) {
            throw new IllegalStateException("容器会话或交互距离已经失效");
        }
        client.interactionManager.clickSlot(ownedHandler.syncId, slot, button, type, client.player);
    }

    private void abort(String reason) {
        if (ownedHandler != null && client.player != null && client.player.currentScreenHandler == ownedHandler) {
            ItemStack cursor = ownedHandler.getCursorStack();
            if (!cursor.isEmpty()) {
                if (client.player.isAlive() && !client.player.isSpectator()
                        && candidate != null && scanner.hit(client, candidate.pos(), config) != null
                        && returnCursorToSource(ownedHandler)) {
                    cursor = ownedHandler.getCursorStack();
                }
                if (!cursor.isEmpty() && InventorySlots.capacity(ownedHandler, client.player, cursor) < cursor.getCount()) {
                    enabled = false;
                    manualTakeover();
                    notice(reason + "；请手动归位鼠标物品，未自动关闭界面");
                    return;
                }
            }
        }
        ownedHandler = null;
        ownOpenRequested = false;
        finalizeVisit(reason, false);
    }

    private void closeOwned() {
        hiddenSession = false;
        ownOpenRequested = false;
        if (ownedHandler != null && client.player != null && client.player.currentScreenHandler == ownedHandler) {
            closingInternally = true;
            try {
                client.player.closeHandledScreen();
            } finally {
                closingInternally = false;
            }
        }
        ownedHandler = null;
    }

    private void finish(String reason, boolean success) {
        if (ownedHandler != null && !ownedHandler.getCursorStack().isEmpty()) {
            abort(reason);
            return;
        }
        finalizeVisit(reason, success);
    }

    /** Terminal cleanup only: never calls abort or finish. */
    private void finalizeVisit(String reason, boolean success) {
        closeOwned();
        if (candidate != null) {
            cooldowns.put(candidate.key(), tick
                    + (success ? config.successCooldownTicks : config.emptyContainerCooldownTicks));
        }
        if (checkingTransfer) reason += "；未把客户端预测计为补料成功";
        if (confirmedThisVisit > 0) notice("本轮已确认补入 " + confirmedThisVisit + " 个材料；" + reason);
        else status = reason;
        candidate = null;
        transferItem = ItemStack.EMPTY;
        plan = null;
        sourceSlot = -1;
        checkingTransfer = false;
        confirmedThisVisit = 0;
        stopRequested = false;
        ownOpenRequested = false;
        hiddenSession = false;
        phase = Phase.IDLE;
        nextScan = tick + config.scanIntervalTicks;
        InteractionGate.setBusy(false);
    }

    private void notice(String text) {
        status = text;
        if (config.chatNotifications && client.player != null
                && (!text.equals(lastChat) || tick - lastChatTick > 100)) {
            client.player.sendMessage(Text.literal("[自动补料] " + text), false);
            lastChat = text;
            lastChatTick = tick;
        }
    }

    private void showStatus() {
        // Keep the HUD quiet while a container session is active so restocking
        // stays invisible; the action bar is only used while idling.
        if (config.actionbarStatus && phase == Phase.IDLE && enabled
                && client.player != null && tick % 40 == 0) {
            client.player.sendMessage(Text.literal("[自动补料] " + status), true);
        }
    }
}
