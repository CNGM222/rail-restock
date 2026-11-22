package dev.feederrestock.runtime;

import dev.feederrestock.FeederRestockClient;
import dev.feederrestock.config.RestockConfig;
import dev.feederrestock.integration.FeederMarks;
import dev.feederrestock.integration.LitematicaBridge;
import dev.feederrestock.integration.PrinterBridge;
import dev.feederrestock.logic.ContainerRanking;
import dev.feederrestock.logic.ContainerVisits;
import dev.feederrestock.logic.CursorTransfer;
import dev.feederrestock.logic.HighlightKind;
import dev.feederrestock.logic.PrintPause;
import dev.feederrestock.logic.ScreenHider;
import dev.feederrestock.logic.TransferPlanner;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-thread state machine. Every interaction goes through vanilla APIs, at
 * vanilla reach: no movement, no background inventory reading, no invented
 * success. Unobtrusive mode only suppresses the local container screen, so the
 * mouse stays captured, the view never turns and the held slot never changes.
 *
 * <p>Only containers registered with mapart-feeder are used; the mod never looks
 * for containers on its own. Printing is paused a few ticks before a container is
 * opened so the printer is never mid-placement when the inventory session starts.</p>
 *
 * <p>One visit empties everything the container can give for the current
 * deficit in a bounded batch, then requests one full server snapshot in the same
 * session to confirm the totals. Completed visits stay remembered until departure.</p>
 */
public final class RestockController {
    private enum Phase {
        IDLE, PREPARE, OPENING, READY, VERIFYING, MANUAL_PAUSE
    }

    private static final int MAX_STACKS_PER_VISIT = 64;
    private static final int MAX_CLICKS_PER_TICK = 128;
    /** Grace for a snapshot that does not match the plan to be superseded by a newer one. */
    private static final long VERIFICATION_GRACE_TICKS = 10;
    /** Cached container markers are refreshed at this rate; they are only visuals. */
    private static final long HIGHLIGHT_REFRESH_TICKS = 10;
    private static final int MAX_HIGHLIGHTS = 32;

    private final MinecraftClient client;
    private final RestockConfig config;
    private final LitematicaBridge bridge = new LitematicaBridge();
    private final ContainerScanner scanner = new ContainerScanner();
    private final Map<Long, Long> cooldowns = new HashMap<>();
    private final ContainerVisits visits = new ContainerVisits();
    private final FeederMarks marks = new FeederMarks();
    private final ContainerKnowledge knowledge = new ContainerKnowledge();

    private Phase phase = Phase.IDLE;
    private boolean enabled;
    private boolean stopRequested;
    private Object worldIdentity;
    private long tick;
    private long nextScan;
    private long nextAction;
    private long deadline;
    /** Tick at which the container may be opened; -1 while no pre-open pause is armed. */
    private long preOpenUntil = -1;
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
    private Screen hiddenScreen;
    private boolean closingInternally;
    /** The container is already closed while its authoritative snapshot is awaited. */
    private boolean sessionClosed;

    private ItemStack transferItem = ItemStack.EMPTY;
    private int sourceSlot = -1;
    private boolean checkingTransfer;
    private int clicksThisTick;
    private final PrintPause printPause = new PrintPause();
    private final PrinterBridge printer = new PrinterBridge();
    private long visitPauseUntil;
    private long verificationBaseline;
    private long verificationSawAt = -1;
    private long nextHighlightRefresh;
    /** Registered containers to draw this frame; client thread only, never null. */
    private final List<HighlightTarget> highlights = new ArrayList<>();
    private String hudSummary = "";
    private long hudSummaryUntil;

    /** Everything planned for this container visit, merged per item. */
    private final List<BatchEntry> batch = new ArrayList<>();
    private int stacksThisVisit;
    private boolean capacityLimited;
    private record BlockedItem(ItemStack sample, int minimumCapacity) { }
    private final List<BlockedItem> blockedItems = new ArrayList<>();

    /** Reason collected while the container is reopened for authoritative totals. */
    private String pendingReason = "";

    private String status = "已关闭";
    private String lastChat = "";
    private long lastChatTick = -1000;

    /** One planned amount for an item, with the inventory baseline it started from. */
    private static final class BatchEntry {
        final ItemStack sample;
        final long before;
        final long containerBefore;
        long planned;

        BatchEntry(ItemStack sample, long before, long containerBefore, long planned) {
            this.sample = sample;
            this.before = before;
            this.containerBefore = containerBefore;
            this.planned = planned;
        }
    }

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
            releasePrintPause();
        }
        enabled = !enabled;
        if (enabled) {
            stopRequested = false;
            visits.clear();
            cooldowns.clear();
            knowledge.clear();
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
        if (hiddenSession) {
            // No screen was ever shown, so this cannot come from a click: it is an
            // internal recovery. Close the invisible container instead of leaving it
            // open behind printing; only a cursor that cannot be emptied is handed
            // back as a visible screen for the player to fix.
            boolean current = ownedHandler != null && client.player != null
                    && client.player.currentScreenHandler == ownedHandler;
            if (current && clearCursorQuietly(ownedHandler)) {
                closeSessionNow();
            } else if (hiddenScreen != null && client.currentScreen == null) {
                closingInternally = true;
                try {
                    client.setScreen(hiddenScreen);
                } finally {
                    closingInternally = false;
                }
            }
        }
        hiddenSession = false;
        hiddenScreen = null;
        ownOpenRequested = false;
        ownedHandler = null;
        sessionClosed = false;
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
            hiddenScreen = null;
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
            hiddenScreen = requested;
            ownOpenRequested = false;
            return true;
        }

        // A screen we did not ask for: the user opened something. Close our hidden
        // container so their screen works, but keep restocking armed: it resumes as
        // soon as the screen is closed again.
        if (hiddenSession) safelyCloseHiddenSession();
        if (phase != Phase.IDLE && phase != Phase.MANUAL_PAUSE && !sessionClosed) pauseForScreen();
        return false;
    }

    /**
     * Suspends the current visit without disabling the feature. The container is
     * closed so the player's own screen behaves normally; the next idle pass
     * reopens the same container and keeps restocking.
     */
    private void pauseForScreen() {
        closeOwned();
        batch.clear();
        stacksThisVisit = 0;
        capacityLimited = false;
        blockedItems.clear();
        transferItem = ItemStack.EMPTY;
        sourceSlot = -1;
        checkingTransfer = false;
        candidate = null;
        stopRequested = false;
        preOpenUntil = -1;
        phase = Phase.IDLE;
        nextScan = tick;
        releasePrintPause();
        status = "你打开了界面，补料已暂停；关闭后自动继续";
    }

    private void safelyCloseHiddenSession() {
        hiddenSession = false;
        if (client.player == null) return;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == client.player.playerScreenHandler) {
            ownedHandler = null;
            ownOpenRequested = false;
            return;
        }
        if (!handler.getCursorStack().isEmpty() && !clearCursor(handler)) return;
        closingInternally = true;
        try {
            client.player.closeHandledScreen();
        } finally {
            closingInternally = false;
        }
        ownedHandler = null;
        ownOpenRequested = false;
    }

    /**
     * Puts the cursor stack back before any close, so no click can be lost and no
     * item stays attached to the mouse once the container is gone.
     */
    private boolean clearCursor(ScreenHandler handler) {
        if (handler.getCursorStack().isEmpty()) return true;
        if (returnCursorToSource(handler)) return true;
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) return true;
        Slot destination = InventorySlots.destination(handler, client.player, cursor);
        if (destination == null || InventorySlots.space(destination, cursor) < cursor.getCount()) {
            return false;
        }
        holdPrintPause();
        client.interactionManager.clickSlot(handler.syncId, destination.id, 0, SlotActionType.PICKUP, client.player);
        return handler.getCursorStack().isEmpty();
    }

    /** Best effort: never throws while unwinding another failure. */
    private boolean clearCursorQuietly(ScreenHandler handler) {
        try {
            return clearCursor(handler);
        } catch (RuntimeException | LinkageError error) {
            FeederRestockClient.LOGGER.warn("归位鼠标物品失败：{}", LitematicaBridge.describeFailure(error));
            return false;
        }
    }

    private boolean returnCursorToSource(ScreenHandler handler) {
        if (sourceSlot < 0 || sourceSlot >= handler.slots.size()) return false;
        ItemStack cursor = handler.getCursorStack();
        Slot source = handler.getSlot(sourceSlot);
        if (!InventorySlots.matches(cursor, transferItem) || InventorySlots.space(source, cursor) < cursor.getCount()) {
            return false;
        }
        holdPrintPause();
        client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, client.player);
        return handler.getCursorStack().isEmpty();
    }

    public void disconnect() {
        if (client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler) {
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
        preOpenUntil = -1;
        ownOpenRequested = false;
        hiddenSession = false;
        hiddenScreen = null;
        sessionClosed = false;
        batch.clear();
        stacksThisVisit = 0;
        capacityLimited = false;
        blockedItems.clear();
        phase = Phase.IDLE;
        cooldowns.clear();
        visits.clear();
        knowledge.clear();
        marks.clear();
        highlights.clear();
        nextHighlightRefresh = 0;
        hudSummary = "";
        hudSummaryUntil = 0;
        visitPauseUntil = 0;
        releasePrintPause();
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
            visits.forgetDeparted(key -> scanner.isStillNearby(client, key));
            syncPrintGate();
            refreshHighlights();
            if (phase == Phase.MANUAL_PAUSE) {
                if (client.currentScreen == null && client.player.currentScreenHandler == client.player.playerScreenHandler) {
                    phase = Phase.IDLE;
                    releasePrintPause();
                    nextScan = tick + config.manualPauseTicks;
                }
                showStatus();
                return;
            }
            if (!enabled && phase == Phase.IDLE) {
                releasePrintPause();
                return;
            }
            if (!client.player.isAlive() || client.player.isSpectator()) {
                enabled = false;
                abort("当前玩家状态不能补料");
                return;
            }
            if (phase != Phase.IDLE && phase != Phase.MANUAL_PAUSE && !sessionClosed
                    && client.currentScreen != null && !ownsCurrentScreen()) {
                // Fallback for a screen that appeared without going through our hook.
                pauseForScreen();
                return;
            }
            if (ownedHandler != null && !sessionClosed && client.player.currentScreenHandler != ownedHandler) {
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
                if (!sessionClosed && candidate != null && scanner.hit(client, candidate.pos()) == null) {
                    abort("已离开容器可交互范围，稍后重新靠近");
                    return;
                }
            }
            switch (phase) {
                case IDLE -> idle();
                case PREPARE -> prepare();
                case OPENING -> waitForOpen();
                case READY -> ready();
                case VERIFYING -> waitForVerification();
                default -> { }
            }
            showStatus();
        } catch (RuntimeException | LinkageError error) {
            FeederRestockClient.LOGGER.error("Feeder Restock 已安全停用", error);
            String message = "补料已停用：" + LitematicaBridge.describeFailure(error);
            try {
                emergencyStop();
            } finally {
                notice(message);
            }
        }
    }

    private void idle() {
        if (!enabled || tick < nextScan) return;
        nextScan = tick + config.scanIntervalTicks;
        if (client.currentScreen != null) {
            status = "等待你关闭界面后继续补料";
            nextScan = tick + 5;
            return;
        }
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) {
            status = "等待容器关闭";
            nextScan = tick + 5;
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
            visits.clear();
            cooldowns.clear();
            knowledge.clear();
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
        marks.refresh(tick);
        knowledge.expire(tick, config.knowledgeExpireTicks);
        if (!marks.isPresent()) {
            status = "未找到 mapart-feeder 的登记（config/mapartfeeder.json）";
            nextScan = tick + Math.max(config.scanIntervalTicks, 20);
            return;
        }
        if (marks.size() == 0) {
            status = "mapart-feeder 里还没有登记容器：对准箱子按 K 登记";
            nextScan = tick + Math.max(config.scanIntervalTicks, 20);
            return;
        }
        List<ContainerScanner.Candidate> found = scanner.findRegistered(client, marks,
                client.world.getRegistryKey().getValue().toString(), cooldowns, visits, tick);
        ContainerScanner.Candidate picked = choose(found, needs);
        if (picked == null) {
            // Nothing reachable right now: do not re-scan every single tick.
            nextScan = tick + Math.max(config.scanIntervalTicks, 20);
            return;
        }
        candidate = picked;
        // Printing is suspended for the whole visit, but never past this cap: a
        // slow sync must not freeze printing for seconds.
        visitPauseUntil = tick + config.visitPauseCapTicks;
        preOpenUntil = -1;
        batch.clear();
        stacksThisVisit = 0;
        capacityLimited = false;
        blockedItems.clear();
        checkingTransfer = false;
        requestCount();
        phase = Phase.PREPARE;
        deadline = tick + config.syncTimeoutTicks;
        status = "核对本次真实缺口";
    }

    /**
     * Picks the container the way mapart-feeder surveys its registered chests:
     * known sources of the still-missing items first, then never-opened chests
     * nearest-first, and never a chest already known to hold nothing useful.
     */
    private ContainerScanner.Candidate choose(List<ContainerScanner.Candidate> found,
                                              List<LitematicaBridge.Need> needs) {
        if (found.isEmpty()) {
            status = "附近没有可交互的已登记容器（走到箱子旁再取）";
            return null;
        }
        Vec3d eye = client.player.getEyePos();
        Map<Long, ContainerScanner.Candidate> byKey = new LinkedHashMap<>();
        List<ContainerRanking.Candidate> ranked = new ArrayList<>(found.size());
        for (ContainerScanner.Candidate option : found) {
            boolean known = knowledge.knows(option.key());
            long useful = known ? usefulFor(option.key(), needs) : 0L;
            // Surveyed and holding nothing we need: do not open it again.
            if (known && useful <= 0) continue;
            byKey.putIfAbsent(option.key(), option);
            ranked.add(new ContainerRanking.Candidate(option.key(),
                    Math.sqrt(eye.squaredDistanceTo(Vec3d.ofCenter(option.pos()))), known, useful));
        }
        List<ContainerRanking.Candidate> ordered = ContainerRanking.order(ranked);
        if (ordered.isEmpty()) {
            status = "已登记的容器里没有所需原料，等待重新登记或缓存过期";
            return null;
        }
        return byKey.get(ordered.getFirst().key());
    }

    /** How many of the still-missing items this container is known to hold. */
    private long usefulFor(long key, List<LitematicaBridge.Need> needs) {
        long total = 0;
        for (LitematicaBridge.Need need : needs) {
            if (need.deficit() <= 0 || !(need.stack() instanceof ItemStack stack)) continue;
            total += Math.min(need.deficit(), knowledge.count(key, stack));
        }
        return total;
    }

    /** Registered containers to draw this frame. Client thread only. */
    public List<HighlightTarget> highlights() {
        return highlights;
    }

    /**
     * Marks the registered containers with what the cache knows about them: green
     * when the cache says they still hold part of the current deficit, yellow when
     * they were never opened (or their cache expired), grey when they are known to
     * hold nothing useful. Refreshed a few times per second; it is only a visual and
     * the renderer draws the boxes itself.
     */
    private void refreshHighlights() {
        if (!config.highlightContainers || !enabled || context == null
                || client.player == null || client.world == null) {
            highlights.clear();
            return;
        }
        if (tick < nextHighlightRefresh) return;
        nextHighlightRefresh = tick + HIGHLIGHT_REFRESH_TICKS;
        List<LitematicaBridge.Need> needs = bridge.needs(context, client.player);
        marks.refresh(tick);
        String dimension = client.world.getRegistryKey().getValue().toString();
        Vec3d eye = client.player.getEyePos();
        double rangeSq = (double) config.highlightRange * config.highlightRange;
        Set<Long> seen = new HashSet<>();
        List<HighlightTarget> fresh = new ArrayList<>();
        for (BlockPos marked : marks.positions(dimension)) {
            if (!client.world.isChunkLoaded(marked)) continue;
            double distanceSq = eye.squaredDistanceTo(Vec3d.ofCenter(marked));
            if (distanceSq > rangeSq) continue;
            Bounds bounds = containerBounds(marked);
            if (bounds == null) continue;
            long key = scanner.keyOf(client, marked);
            if (!seen.add(key)) continue;
            boolean known = knowledge.knows(key);
            long useful = known ? usefulFor(key, needs) : 0L;
            HighlightKind kind = HighlightKind.of(known, useful > 0);
            fresh.add(new HighlightTarget(bounds.min(), bounds.max(), kind,
                    (int) Math.round(Math.sqrt(distanceSq))));
        }
        fresh.sort(Comparator.comparingInt(HighlightTarget::distance));
        highlights.clear();
        highlights.addAll(fresh.size() > MAX_HIGHLIGHTS ? fresh.subList(0, MAX_HIGHLIGHTS) : fresh);
    }

    /** Inclusive block range covering the whole container, both halves of a double chest included. */
    private record Bounds(BlockPos min, BlockPos max) { }

    private Bounds containerBounds(BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        if (!ContainerScanner.isContainerBlock(state)) return null;
        BlockPos other = pos;
        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            other = pos.offset(ChestBlock.getFacing(state));
        }
        return new Bounds(
                new BlockPos(Math.min(pos.getX(), other.getX()),
                        Math.min(pos.getY(), other.getY()),
                        Math.min(pos.getZ(), other.getZ())),
                new BlockPos(Math.max(pos.getX(), other.getX()),
                        Math.max(pos.getY(), other.getY()),
                        Math.max(pos.getZ(), other.getZ())));
    }

    /**
     * Easy Place is held only for a few ticks around the actual slot operations.
     * Unlike a visit-wide lock, a slow open or synchronization wait expires this
     * deadline and lets printing continue by itself.
     */
    private void holdPrintPause() {
        printPause.hold(tick, config.printPauseTicks);
        InteractionGate.setBusy(true);
    }

    private void releasePrintPause() {
        printPause.release();
        InteractionGate.setBusy(false);
        printer.resume();
    }

    /**
     * Re-evaluated every tick so the pause always ends, even if a phase stalls.
     * Both Litematica's Easy Place and the printer mod are held by the same flag.
     */
    private void syncPrintGate() {
        if (phase == Phase.MANUAL_PAUSE) {
            releasePrintPause();
            return;
        }
        boolean visiting = phase == Phase.PREPARE || phase == Phase.OPENING || phase == Phase.READY
                || (phase == Phase.VERIFYING && !sessionClosed);
        boolean paused = printPause.active(tick) || (visiting && tick <= visitPauseUntil)
                || InventoryGuard.blocksPrinting();
        InteractionGate.setBusy(paused);
        // The mixin cancels the printer's own tick when the gate is closed, so the
        // config switch is only touched on builds without that hook (no flicker).
        if (printer.hasTickHook()) printer.resume();
        else printer.setSuspended(paused);
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
            finish("来不及完成材料统计，稍后重试", false);
            return;
        }
        if (!countReady()) {
            // A count taken while blocks are being placed would inflate the
            // deficit, so only this opt-in counting mode holds printing.
            if (config.autoRecountMaterialList) holdPrintPause();
            return;
        }
        if (bridge.needs(context, client.player).stream().noneMatch(n -> n.deficit() > 0)) {
            finish("当前材料已够用，继续轻松放置", false);
            return;
        }
        if (preOpenUntil < 0) {
            // Stop printing a few ticks BEFORE the container opens, so the printer is
            // never half-way through a placement when the inventory session starts.
            preOpenUntil = tick + config.preOpenPauseTicks;
            holdPrintPause();
            printPause.holdUntil(preOpenUntil + 1);
        }
        if (tick < preOpenUntil) {
            status = "即将开箱：已提前暂停打印";
            return;
        }
        startOpen();
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

    private void startOpen() {
        if (client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler) {
            manualTakeover();
            return;
        }
        BlockHitResult hit = scanner.hit(client, candidate.pos());
        Hand hand = openingHand();
        if (hit == null || hand == null) {
            finish("容器目前不可正常打开", false);
            return;
        }
        openBaseline = InventorySyncTracker.sequence();
        ownedHandler = null;
        ownOpenRequested = true;
        hiddenSession = false;
        sessionClosed = false;
        phase = Phase.OPENING;
        deadline = tick + config.openTimeoutTicks;
        status = "正在打开已登记容器";
        // Only the use packet itself must not race the player's own use key.
        holdPrintPause();
        client.interactionManager.interactBlock(client.player, hand, hit);
    }

    private boolean supportedHandler(ScreenHandler handler) {
        return handler.getClass() == GenericContainerScreenHandler.class
                || handler.getClass() == ShulkerBoxScreenHandler.class;
    }

    private void waitForOpen() {
        ScreenHandler current = client.player.currentScreenHandler;
        if (current == client.player.playerScreenHandler) {
            if (tick > deadline) finish("开箱超时，稍后重试", false);
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
            if (tick > deadline) finish("开箱或完整库存同步超时，稍后重试", false);
            return;
        }
        if (!ownedHandler.getCursorStack().isEmpty()) {
            enabled = false;
            manualTakeover();
            notice("服务端同步后鼠标仍有物品，已停止自动操作，请检查");
            return;
        }
        // Survey the container exactly like mapart-feeder does: remember what it
        // currently holds so later passes can skip chests with nothing useful.
        knowledge.record(candidate.key(), containerStacks(ownedHandler), tick);
        phase = Phase.READY;
        nextAction = tick;
        ready();
    }

    /** Container slots only; the player inventory is not part of the survey. */
    private List<ItemStack> containerStacks(ScreenHandler handler) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (InventorySlots.isPlayerSlot(slot, client.player)) continue;
            if (!slot.getStack().isEmpty()) stacks.add(slot.getStack().copy());
        }
        return stacks;
    }

    /** Sends all eligible source-slot operations in the same tick, within a packet budget. */
    private void ready() {
        if (tick < nextAction) return;
        if (bridge.isRecountPending()) {
            if (batch.isEmpty()) finish("材料清单正在重新统计，先结束本轮补料", false);
            else beginVerification("材料清单正在重新统计，结算已取材料");
            return;
        }
        if (!ownedHandler.getCursorStack().isEmpty()) {
            manualTakeover();
            return;
        }
        clicksThisTick = 0;
        for (int index = 0; index < ownedHandler.slots.size(); index++) {
            Slot slot = ownedHandler.getSlot(index);
            if (InventorySlots.isPlayerSlot(slot, client.player) || !slot.canTakeItems(client.player)) continue;
            ItemStack source = slot.getStack();
            if (source.isEmpty()) continue;
            // Refresh against each preceding local prediction so repeated stacks of
            // the same item never reuse the original deficit or inventory capacity.
            long deficit = deficit(bridge.needs(context, client.player), source);
            if (deficit <= 0) continue;
            int capacity = InventorySlots.capacity(ownedHandler, client.player, source);
            TransferPlanner.Plan plan = TransferPlanner.plan(source.getCount(), deficit, capacity);
            if (plan.kind() == TransferPlanner.Kind.NONE) {
                capacityLimited = true;
                int minimum = capacity > deficit ? source.getCount() / 2 + source.getCount() % 2 : 1;
                blockedItems.add(new BlockedItem(source.copy(), minimum));
                continue;
            }
            if (stacksThisVisit >= MAX_STACKS_PER_VISIT) {
                beginVerification("单次补料数量已达上限");
                return;
            }
            // Always finish a cursor remainder before yielding to another tick.
            int cost = plan.kind() == TransferPlanner.Kind.QUICK_MOVE ? 1
                    : 38 + Math.min(plan.transferCount(), plan.pickupCount() - plan.transferCount());
            // Reserve cursor cleanup and the final synchronization packet as well.
            if (clicksThisTick + cost + 1 > MAX_CLICKS_PER_TICK) {
                nextAction = tick + config.clickIntervalTicks;
                return;
            }
            sourceSlot = slot.id;
            transferItem = source.copy();
            long before = InventorySlots.countDirect(client.player, transferItem);
            long containerBefore = InventorySlots.countContainer(ownedHandler, client.player, transferItem);
            checkingTransfer = true;
            // Clicks and the player's own placement must never interleave. Only this
            // batch takes the gate; the deadline releases it if anything stalls.
            holdPrintPause();
            if (plan.kind() == TransferPlanner.Kind.QUICK_MOVE) {
                click(sourceSlot, 0, SlotActionType.QUICK_MOVE);
            } else {
                exactTransfer(plan);
            }
            long moved = InventorySlots.countDirect(client.player, transferItem) - before;
            long removed = containerBefore - InventorySlots.countContainer(ownedHandler, client.player, transferItem);
            if (moved != plan.transferCount() || removed != moved || !ownedHandler.getCursorStack().isEmpty()) {
                abort("容器转移预测发生变化，停止本次操作并等待重新同步");
                return;
            }
            recordTaken(transferItem, before, containerBefore, moved);
            knowledge.subtract(candidate.key(), transferItem, (int) moved, tick);
            stacksThisVisit++;
            transferItem = ItemStack.EMPTY;
            sourceSlot = -1;
            if (!slot.getStack().isEmpty()) index--; // A half pickup can leave another usable portion.
        }
        if (batch.isEmpty()) {
            finish(capacityLimited ? "背包容量不足，暂不取料" : "该容器没有待补材料",
                    false, capacityLimited ? config.successCooldownTicks : config.emptyContainerCooldownTicks);
            return;
        }
        beginVerification(capacityLimited ? "可用背包容量有限，结算本次已取材料" : "容器内待补材料已取完");
    }

    private void exactTransfer(TransferPlanner.Plan plan) {
        CursorTransfer.execute(plan, new CursorTransfer.Access() {
            @Override public void pickup(boolean half) {
                click(sourceSlot, half ? 1 : 0, SlotActionType.PICKUP);
            }
            @Override public int cursorCount() {
                ItemStack cursor = ownedHandler.getCursorStack();
                return cursor.isEmpty() ? 0 : InventorySlots.matches(cursor, transferItem) ? cursor.getCount() : -1;
            }
            @Override public boolean canReturn(int count) {
                return InventorySlots.space(ownedHandler.getSlot(sourceSlot), transferItem) >= count;
            }
            @Override public void returnToSource(boolean single) {
                click(sourceSlot, single ? 1 : 0, SlotActionType.PICKUP);
            }
            @Override public int destination() {
                Slot slot = InventorySlots.destination(ownedHandler, client.player, transferItem);
                return slot == null ? -1 : slot.id;
            }
            @Override public int space(int slot) {
                return InventorySlots.space(ownedHandler.getSlot(slot), transferItem);
            }
            @Override public void deposit(int slot, boolean single) {
                click(slot, single ? 1 : 0, SlotActionType.PICKUP);
            }
        });
    }

    private void recordTaken(ItemStack sample, long before, long containerBefore, long planned) {
        for (BatchEntry entry : batch) {
            if (InventorySlots.matches(entry.sample, sample)) {
                entry.planned += planned;
                return;
            }
        }
        batch.add(new BatchEntry(sample, before, containerBefore, planned));
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

    /** Request a full vanilla snapshot, then close: never hold the chest open for it. */
    private void beginVerification(String reason) {
        pendingReason = reason;
        if (!ownedHandler.getCursorStack().isEmpty()) {
            abort("鼠标物品尚未归位，取消本次结算");
            return;
        }
        verificationBaseline = InventorySyncTracker.sequence();
        verificationSawAt = -1;
        phase = Phase.VERIFYING;
        deadline = tick + config.syncTimeoutTicks;
        status = "结算：" + reason;
        // Hold the gate only for this tick and the short settle window behind it: the
        // request and the close leave together, so the wait for a round trip no longer
        // keeps the container open or freezes printing.
        holdPrintPause();
        InventorySyncTracker.track(ownedHandler.syncId);
        // Vanilla 1.21.11 accepts PICKUP/-1 as a no-op even if its cursor differs.
        // A stale revision requests updateToClient -> syncState. Normal successful
        // clicks have no guaranteed reply because their prediction hashes may match.
        // Never substitute -999 here: that is the drop-outside-inventory operation.
        client.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(
                ownedHandler.syncId, -1, (short) -1, (byte) 0, SlotActionType.PICKUP,
                Int2ObjectMaps.emptyMap(), ItemStackHash.EMPTY));
        if (closeSessionNow()) {
            // The container is gone, so the visit-wide window has nothing left to guard.
            visitPauseUntil = 0;
        }
    }

    /**
     * Closes the container session in the tick the batch finished. The snapshot
     * request is already queued, so the server answers it before it processes the
     * close and the reply still arrives on the remembered sync id.
     *
     * @return true when no container is open any more
     */
    private boolean closeSessionNow() {
        ScreenHandler handler = ownedHandler;
        if (handler != null && client.player != null && client.player.currentScreenHandler == handler) {
            if (!clearCursorQuietly(handler)) return false;
            closingInternally = true;
            try {
                client.player.closeHandledScreen();
            } finally {
                closingInternally = false;
            }
        }
        hiddenScreen = null;
        hiddenSession = false;
        ownOpenRequested = false;
        sessionClosed = true;
        return client.player == null || client.player.currentScreenHandler != handler;
    }

    private void waitForVerification() {
        if (ownedHandler == null) {
            finish("容器会话已结束", false);
            return;
        }
        if (!sessionClosed && !closeSessionNow()) {
            // A cursor stack that cannot be placed anywhere has to be handed to the
            // player, so the container stays open and the gate keeps printing paused.
            status = "等待归位鼠标物品后关闭容器";
        }
        InventorySyncTracker.Snapshot snapshot = InventorySyncTracker.latest(ownedHandler.syncId);
        if (snapshot != null && snapshot.sequence() > verificationBaseline) {
            boolean matches = batch.stream().allMatch(entry -> snapshotMatches(snapshot, entry));
            if (matches) {
                verifyVisit(snapshot, true);
                return;
            }
            // An earlier click can already have forced an intermediate resync. Give the
            // batch's final totals a short grace, then settle with the newest snapshot:
            // the container is closed, so waiting the whole timeout would only delay the
            // next chest for no gain.
            if (verificationSawAt < 0) verificationSawAt = tick;
            if (tick - verificationSawAt >= VERIFICATION_GRACE_TICKS || tick > deadline) {
                verifyVisit(snapshot, false);
            }
        } else if (tick > deadline) {
            finish(pendingReason + "；服务端完整库存确认超时，本次转移未标记成功", false);
        }
    }

    /** Container slots of an authoritative snapshot; player slots are excluded. */
    private List<ItemStack> snapshotContainerStacks(InventorySyncTracker.Snapshot snapshot) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Slot slot : ownedHandler.slots) {
            if (InventorySlots.isPlayerSlot(slot, client.player)) continue;
            if (slot.id < 0 || slot.id >= snapshot.contents().size()) continue;
            ItemStack stack = snapshot.contents().get(slot.id);
            if (!stack.isEmpty()) stacks.add(stack.copy());
        }
        return stacks;
    }

    /** The container is the authority: Easy Place may consume items after we resume. */
    private boolean snapshotMatches(InventorySyncTracker.Snapshot snapshot, BatchEntry entry) {
        return entry.containerBefore - snapshot.count(ownedHandler, client.player, entry.sample, false) == entry.planned;
    }

    private void verifyVisit(InventorySyncTracker.Snapshot snapshot, boolean allMatch) {
        long total = 0;
        List<String> parts = new ArrayList<>();
        // The authoritative survey: what the container still holds afterwards.
        knowledge.record(candidate.key(), snapshotContainerStacks(snapshot), tick);
        for (BatchEntry entry : batch) {
            long removed = entry.containerBefore - snapshot.count(ownedHandler, client.player, entry.sample, false);
            long actual = Math.max(0, Math.min(entry.planned, removed));
            if (actual > 0) {
                total += actual;
                if (parts.size() < 6) parts.add(entry.sample.getName().getString() + "×" + actual);
            }
        }
        bridge.refresh(context, client.player);
        checkingTransfer = false;
        String summary = parts.isEmpty() ? "没有确认取到材料"
                : "已取 " + String.join("、", parts) + "（共 " + total + " 个）";
        if (batch.size() > 6) summary += " 等 " + batch.size() + " 种";
        String reason = pendingReason.isEmpty() ? summary : pendingReason + "；" + summary;
        if (!allMatch) reason += "；容器减少量与计划不符，已按同步库存刷新清单";
        hudSummary = summary;
        hudSummaryUntil = tick + 100;
        if (config.actionbarStatus) client.player.sendMessage(Text.literal("[自动补料] " + summary), true);
        finish(reason, allMatch && total > 0);
    }

    private void click(int slot, int button, SlotActionType type) {
        if (ownedHandler == null || client.player.currentScreenHandler != ownedHandler
                || scanner.hit(client, candidate.pos()) == null) {
            throw new IllegalStateException("容器会话或交互距离已经失效");
        }
        client.interactionManager.clickSlot(ownedHandler.syncId, slot, button, type, client.player);
        clicksThisTick++;
    }

    private void abort(String reason) {
        if (ownedHandler != null && client.player != null && client.player.currentScreenHandler == ownedHandler) {
            ItemStack cursor = ownedHandler.getCursorStack();
            if (!cursor.isEmpty()) {
                if (client.player.isAlive() && !client.player.isSpectator()
                        && candidate != null && scanner.hit(client, candidate.pos()) != null
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
        // Keep the owner until terminal cleanup closes the real hidden session.
        finalizeVisit(reason, false, config.emptyContainerCooldownTicks);
    }

    private void closeOwned() {
        hiddenScreen = null;
        hiddenSession = false;
        ownOpenRequested = false;
        if (ownedHandler != null && client.player != null && client.player.currentScreenHandler == ownedHandler) {
            // A stack still on the cursor must go back first: closing with it would
            // drop or merge it on the server side and leave nothing to point at.
            clearCursorQuietly(ownedHandler);
            closingInternally = true;
            try {
                client.player.closeHandledScreen();
            } finally {
                closingInternally = false;
            }
        }
        ownedHandler = null;
        sessionClosed = false;
    }

    /**
     * Last-resort stop after an unexpected failure. Everything is best effort,
     * because the failure itself may already have broken the state we read; the
     * interaction gate stays closed while any container or cursor item remains.
     */
    private void emergencyStop() {
        enabled = false;
        stopRequested = false;
        try {
            if (ownedHandler != null && client.player != null
                    && client.player.currentScreenHandler == ownedHandler) {
                clearCursorQuietly(ownedHandler);
                closeSessionNow();
            }
        } catch (RuntimeException | LinkageError error) {
            FeederRestockClient.LOGGER.warn("结束容器会话时出错：{}", LitematicaBridge.describeFailure(error));
        } finally {
            ownedHandler = null;
            hiddenSession = false;
            hiddenScreen = null;
            ownOpenRequested = false;
            sessionClosed = false;
            context = null;
            scope = null;
            candidate = null;
            preOpenUntil = -1;
            awaitingCount = false;
            countRequestQueued = false;
            batch.clear();
            stacksThisVisit = 0;
            capacityLimited = false;
            blockedItems.clear();
            checkingTransfer = false;
            transferItem = ItemStack.EMPTY;
            sourceSlot = -1;
            visitPauseUntil = 0;
            printPause.release();
            highlights.clear();
            nextHighlightRefresh = 0;
            phase = client.currentScreen == null ? Phase.IDLE : Phase.MANUAL_PAUSE;
            InteractionGate.setBusy(phase == Phase.MANUAL_PAUSE);
            printer.resume();
        }
    }

    private void finish(String reason, boolean success) {
        long cooldown = success ? config.containerRevisitTicks : config.emptyContainerCooldownTicks;
        if (success && capacityLimited) cooldown = config.successCooldownTicks;
        finish(reason, success, cooldown);
    }

    private void finish(String reason, boolean success, long cooldownTicks) {
        if (ownedHandler != null && !ownedHandler.getCursorStack().isEmpty()) {
            abort(reason);
            return;
        }
        finalizeVisit(reason, success, cooldownTicks);
    }

    /** Terminal cleanup only: never calls abort or finish. */
    private void finalizeVisit(String reason, boolean success, long cooldownTicks) {
        closeOwned();
        if (candidate != null) {
            visits.mark(candidate.key());
            for (BlockedItem blocked : blockedItems) {
                ItemStack sample = blocked.sample();
                int baseline = InventorySlots.capacity(client.player.playerScreenHandler, client.player, sample);
                visits.capacityBlocked(candidate.key(), baseline, blocked.minimumCapacity(),
                        () -> InventorySlots.capacity(client.player.playerScreenHandler, client.player, sample));
            }
            cooldowns.put(candidate.key(), tick + cooldownTicks);
        }
        if (checkingTransfer) reason += "；未把客户端预测计为补料成功";
        notice(reason);
        candidate = null;
        transferItem = ItemStack.EMPTY;
        sourceSlot = -1;
        checkingTransfer = false;
        pendingReason = "";
        batch.clear();
        stacksThisVisit = 0;
        capacityLimited = false;
        blockedItems.clear();
        stopRequested = false;
        ownOpenRequested = false;
        hiddenSession = false;
        sessionClosed = false;
        preOpenUntil = -1;
        phase = Phase.IDLE;
        nextScan = tick + config.scanIntervalTicks;
        visitPauseUntil = 0;
        releasePrintPause();
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
            client.player.sendMessage(Text.literal("[自动补料] " + (tick < hudSummaryUntil ? hudSummary : status)), true);
        }
    }
}
