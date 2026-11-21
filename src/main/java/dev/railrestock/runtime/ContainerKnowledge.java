package dev.railrestock.runtime;

import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What each container is known to hold, learned only from a server snapshot of
 * a container this mod actually opened — never guessed from the world.
 *
 * <p>Knowledge is deliberately short-lived: entries expire after
 * {@code knowledgeExpireTicks}, and F8 off/on clears everything, because the
 * player may refill a chest from elsewhere while we stand still.</p>
 */
public final class ContainerKnowledge {
    private static final class Count {
        final ItemStack sample;
        int count;

        Count(ItemStack sample, int count) {
            this.sample = sample.copyWithCount(1);
            this.count = count;
        }
    }

    private static final class Entry {
        final List<Count> counts = new ArrayList<>();
        long updated;

        Count find(ItemStack stack) {
            for (Count count : counts) {
                if (InventorySlots.matches(count.sample, stack)) return count;
            }
            return null;
        }
    }

    private final Map<Long, Entry> entries = new HashMap<>();

    /** Replaces the entry with an authoritative container snapshot (player slots excluded). */
    public void record(long key, List<ItemStack> containerStacks, long tick) {
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        entry.counts.clear();
        for (ItemStack stack : containerStacks) {
            if (stack == null || stack.isEmpty()) continue;
            Count existing = entry.find(stack);
            if (existing != null) existing.count += stack.getCount();
            else entry.counts.add(new Count(stack, stack.getCount()));
        }
        entry.counts.removeIf(count -> count.count <= 0);
        entry.updated = tick;
    }

    /** Keeps the entry useful between clicks, before the next snapshot lands. */
    public void subtract(long key, ItemStack stack, int amount, long tick) {
        Entry entry = entries.get(key);
        if (entry == null || amount <= 0) return;
        Count count = entry.find(stack);
        if (count == null) return;
        count.count = Math.max(0, count.count - amount);
        entry.counts.removeIf(candidate -> candidate.count <= 0);
        entry.updated = tick;
    }

    public boolean knows(long key) {
        return entries.containsKey(key);
    }

    public long count(long key, ItemStack stack) {
        Entry entry = entries.get(key);
        if (entry == null) return 0;
        Count count = entry.find(stack);
        return count == null ? 0 : count.count;
    }

    public void expire(long now, int ttlTicks) {
        entries.entrySet().removeIf(entry -> now - entry.getValue().updated > ttlTicks);
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
