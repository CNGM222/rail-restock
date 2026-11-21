package dev.railrestock.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Container choice adapted from mapart-feeder's survey order, without any
 * Minecraft dependency:
 *
 * <ol>
 *   <li>containers already known to hold what is still missing, best
 *       {@code useful × 64 / (distance + 1)} score first;</li>
 *   <li>containers that were never opened, nearest first, so they can be
 *       surveyed and remembered;</li>
 *   <li>containers known to hold nothing useful are dropped entirely — the same
 *       rule that stops mapart-feeder from reopening a surveyed chest.</li>
 * </ol>
 */
public final class ContainerRanking {
    private ContainerRanking() { }

    /** @param useful how many of the currently missing items this container is known to hold */
    public record Candidate(long key, double distance, boolean known, long useful) {
        double score() {
            return useful * 64.0 / (distance + 1.0);
        }

        int tier() {
            if (known && useful > 0) return 0;
            return known ? 2 : 1;
        }
    }

    public static List<Candidate> order(List<Candidate> candidates) {
        List<Candidate> result = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (candidate.tier() < 2) result.add(candidate);
        }
        result.sort(Comparator
                .comparingInt(Candidate::tier)
                .thenComparing((first, second) -> first.tier() == 0
                        ? Double.compare(second.score(), first.score())
                        : Double.compare(first.distance(), second.distance()))
                .thenComparingLong(Candidate::key));
        return result;
    }
}
