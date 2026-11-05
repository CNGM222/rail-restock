package dev.railrestock.runtime;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Only advanced after a full server inventory packet was applied client-side. */
public final class InventorySyncTracker {
    private static long sequence;
    private static final Map<Integer, Snapshot> fullUpdates = new HashMap<>();

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
        sequence = 0L;
    }
}
