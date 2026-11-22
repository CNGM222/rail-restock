package dev.feederrestock.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerRankingTest {
    @Test
    void knownSourceOfTheMissingItemWins() {
        List<ContainerRanking.Candidate> ordered = ContainerRanking.order(List.of(
                new ContainerRanking.Candidate(1, 2.0, true, 0),
                new ContainerRanking.Candidate(2, 6.0, true, 64),
                new ContainerRanking.Candidate(3, 1.0, false, 0)));
        assertEquals(2, ordered.getFirst().key());
    }

    @Test
    void closerContainerWinsBetweenEqualAmounts() {
        List<ContainerRanking.Candidate> ordered = ContainerRanking.order(List.of(
                new ContainerRanking.Candidate(1, 8.0, true, 32),
                new ContainerRanking.Candidate(2, 2.0, true, 32)));
        assertEquals(List.of(2L, 1L), ordered.stream().map(ContainerRanking.Candidate::key).toList());
    }

    @Test
    void aBiggerSourceOutranksASlightlyCloserOne() {
        List<ContainerRanking.Candidate> ordered = ContainerRanking.order(List.of(
                new ContainerRanking.Candidate(1, 4.0, true, 8),
                new ContainerRanking.Candidate(2, 5.0, true, 64)));
        assertEquals(2, ordered.getFirst().key());
    }

    @Test
    void neverOpenedContainersAreSurveyedBeforeUselessOnes() {
        List<ContainerRanking.Candidate> ordered = ContainerRanking.order(List.of(
                new ContainerRanking.Candidate(1, 1.0, true, 0),
                new ContainerRanking.Candidate(2, 9.0, false, 0),
                new ContainerRanking.Candidate(3, 2.0, false, 0)));
        assertEquals(List.of(3L, 2L), ordered.stream().map(ContainerRanking.Candidate::key).toList());
    }

    @Test
    void surveyedContainersWithoutTheItemAreNeverOpenedAgain() {
        List<ContainerRanking.Candidate> ordered = ContainerRanking.order(List.of(
                new ContainerRanking.Candidate(1, 1.0, true, 0),
                new ContainerRanking.Candidate(2, 1.0, true, 0)));
        assertTrue(ordered.isEmpty());
    }

    @Test
    void emptyInputStaysEmpty() {
        assertTrue(ContainerRanking.order(List.of()).isEmpty());
    }

    @Test
    void orderingIsDeterministicForEqualScores() {
        ContainerRanking.Candidate first = new ContainerRanking.Candidate(7, 3.0, true, 16);
        ContainerRanking.Candidate second = new ContainerRanking.Candidate(9, 3.0, true, 16);
        assertEquals(List.of(7L, 9L),
                ContainerRanking.order(List.of(second, first)).stream()
                        .map(ContainerRanking.Candidate::key).toList());
    }
}
