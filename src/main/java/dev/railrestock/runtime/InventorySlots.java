package dev.railrestock.runtime;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/** Inventory helpers using the same component-aware equality as vanilla merging. */
public final class InventorySlots {
    private InventorySlots() { }

    public static boolean matches(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.areItemsAndComponentsEqual(a, b);
    }

    /** Player main inventory and hotbar only: never armour, crafting or the offhand. */
    public static boolean isPlayerSlot(Slot slot, PlayerEntity player) {
        return slot.inventory == player.getInventory() && slot.getIndex() >= 0 && slot.getIndex() < 36;
    }

    public static int space(Slot slot, ItemStack stack) {
        if (!slot.isEnabled() || !slot.canInsert(stack)) return 0;
        ItemStack current = slot.getStack();
        if (!current.isEmpty() && !matches(current, stack)) return 0;
        return Math.max(0, Math.min(stack.getMaxCount(), slot.getMaxItemCount(stack)) - current.getCount());
    }

    /** Total insertable capacity for this exact item. */
    public static int capacity(ScreenHandler handler, PlayerEntity player, ItemStack stack) {
        long result = 0;
        for (Slot slot : handler.slots) {
            if (isPlayerSlot(slot, player)) result += space(slot, stack);
        }
        return (int) Math.min(Integer.MAX_VALUE, result);
    }

    /** Merge into a partial stack first, then use an empty slot. */
    public static Slot destination(ScreenHandler handler, PlayerEntity player, ItemStack stack) {
        for (Slot slot : handler.slots) {
            if (isPlayerSlot(slot, player) && !slot.getStack().isEmpty() && space(slot, stack) > 0) return slot;
        }
        for (Slot slot : handler.slots) {
            if (isPlayerSlot(slot, player) && space(slot, stack) > 0) return slot;
        }
        return null;
    }

    /** Direct count in the 36 main slots; excludes container contents. */
    public static long countDirect(PlayerEntity player, ItemStack stack) {
        long total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack owned = player.getInventory().getStack(i);
            if (matches(owned, stack)) total += owned.getCount();
        }
        return total;
    }
}
