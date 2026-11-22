package dev.feederrestock.runtime;

import dev.feederrestock.logic.HighlightKind;
import net.minecraft.util.math.Box;

/**
 * One registered container to draw in the world. The box already covers both
 * halves of a double chest and is inset slightly so it does not fight with the
 * block's own edges.
 *
 * @param distance whole blocks between the player's eyes and the container
 */
public record HighlightTarget(Box box, HighlightKind kind, String label, int distance) {
}
