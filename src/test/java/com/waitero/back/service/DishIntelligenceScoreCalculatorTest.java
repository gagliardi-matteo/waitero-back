package com.waitero.back.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DishIntelligenceScoreCalculatorTest {

    @Test
    void shouldComputeUnifiedDishScoreDeterministically() {
        DishIntelligenceScoreCalculator calculator = new DishIntelligenceScoreCalculator(0.35d, 0.5d);

        double score = calculator.computeScore(
                new BigDecimal("45.00"),
                90L,
                1.0d,
                0.60d,
                200L
        );

        double expectedRpi = (45.0d + 5.0d) / (90.0d + 10.0d);
        double expectedExploration = 0.5d * Math.sqrt(Math.log(200.0d) / 91.0d);
        double expectedScore = expectedRpi + (0.35d * 0.60d) + expectedExploration;

        assertEquals(expectedScore, score, 0.0000001d);
    }

    @Test
    void shouldBlendLiftAndAffinityIntoSingleAffinityScore() {
        DishIntelligenceScoreCalculator calculator = new DishIntelligenceScoreCalculator(0.35d, 0.5d);

        double combined = calculator.combineAffinity(new BigDecimal("2.0000"), new BigDecimal("0.4000"));
        double expected = (0.7d * (2.0d / 3.0d)) + (0.3d * 0.4d);

        assertEquals(expected, combined, 0.0000001d);
    }
}
