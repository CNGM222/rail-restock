package dev.feederrestock.runtime;

import dev.feederrestock.logic.HighlightKind;
import net.minecraft.util.math.BlockPos;

/**
 * One registered container to draw in the world, as the inclusive block range that
 * covers it: a single block for a chest half or a barrel, two blocks for a double
 * chest when both halves are known.
 *
 * @param min lower corner of the inclusive block range
 * @param max upper corner of the inclusive block range
 * @param distance whole blocks between the player's eyes and the container
 */
public record HighlightTarget(BlockPos min, BlockPos max, HighlightKind kind, int distance) {
}
