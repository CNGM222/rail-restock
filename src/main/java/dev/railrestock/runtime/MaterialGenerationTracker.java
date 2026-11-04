package dev.railrestock.runtime;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Counts successful material-list submissions. A completion listener would be
 * single-slot and would steal Litematica's GUI listener, so an observer mixin
 * records a generation instead. Client thread only.
 */
public final class MaterialGenerationTracker {
    private static final Map<Object, Long> generations = new WeakHashMap<>();
    private static long lastGeneration;

    private MaterialGenerationTracker() { }

    /** 0 when the list has never submitted a complete count in this session. */
    public static long generation(Object materialList) {
        if (materialList == null) throw new NullPointerException("materialList");
        Long value = generations.get(materialList);
        return value == null ? 0L : value;
    }

    public static void completed(Object materialList) {
        if (materialList == null) throw new NullPointerException("materialList");
        generations.put(materialList, ++lastGeneration);
    }

    public static void reset() {
        generations.clear();
        lastGeneration = 0L;
    }
}
