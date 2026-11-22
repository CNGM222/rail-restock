package dev.railrestock.runtime;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only advanced after a full server inventory packet was applied client-side. */
public final class InventorySyncTracker {
    private static long sequence;
    private static final Map<Integer, Snapshot> fullUpdates = new HashMap<>();
    /**
     * Session ids whose snapshot is still awaited after the container was closed.
     * The close happens before the reply, so the current handler alone cannot tell
     * which incoming packets are ours.
     */
    private static final Set<Integer> tracked = new HashSet<>();

    public record Snapshot(long sequence, List<ItemStack> contents) {
        public long count(ScreenHandler handler, PlayerEntity player, ItemStack stack, boolean playerSlots) {
            long total = 0;
            for (Slot slot : handler.slots) {
                if (slot.id < contents.size() && InventorySlots.isPlayerSlot(slot, player) == playerSlots) {
                    ItemStack item = contents.get(slot.id);
                    if (InventorySlots.matches(item, stack)) total += item.getCount();
                }
            }
            return total;
        }
    }

    private InventorySyncTracker() { }

    public static void fullUpdate(int syncId, List<ItemStack> contents) {
        if (fullUpdates.size() > 64) fullUpdates.clear();
        fullUpdates.put(syncId, new Snapshot(++sequence, contents.stream().map(ItemStack::copy).toList()));
    }

    /** Keeps recording one closed session's sync id until its snapshot arrives. */
    public static void track(int syncId) {
        if (tracked.size() > 8) tracked.clear();
        tracked.add(syncId);
    }

    public static boolean isTracked(int syncId) {
        return tracked.contains(syncId);
    }

    public static long sequence() {
        return sequence;
    }

    public static Snapshot latest(int syncId) {
        return fullUpdates.get(syncId);
    }

    public static boolean hasFullUpdateAfter(int syncId, long baseline) {
        Snapshot snapshot = latest(syncId);
        return snapshot != null && snapshot.sequence() > baseline;
    }

    public static void reset() {
        fullUpdates.clear();
        tracked.clear();
        sequence = 0L;
    }
}
