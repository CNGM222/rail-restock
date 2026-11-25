package dev.feederrestock.integration;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * The container registry this mod takes material from. There are two
 * implementations:
 *
 * <ul>
 *   <li>{@link FeederMarks} — mapart-feeder's own {@code config/mapartfeeder.json},
 *       read-only, used whenever that mod is installed so both mods share one list;</li>
 *   <li>{@link LocalMarks} — our own {@code config/feederrestock-containers.json},
 *       used when mapart-feeder is absent so the player can still register
 *       containers (default key: K).</li>
 * </ul>
 */
public interface MarkSource {
    /** Re-reads the backing file at most once per second. */
    void refresh(long tick);

    /** Registered positions in one dimension; the only sources restocking accepts. */
    List<BlockPos> positions(String dimension);

    int size();

    /** Drops the in-memory list; the next refresh reloads it from disk. */
    void clear();
}
