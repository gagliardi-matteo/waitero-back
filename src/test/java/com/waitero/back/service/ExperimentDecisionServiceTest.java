package com.waitero.back.service;

import com.waitero.back.dto.ExperimentVariantPerformanceDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentDecisionServiceTest {

    private final ExperimentDecisionService experimentDecisionService = new ExperimentDecisionService();

    @Test
    void shouldChooseVariantCWhenItBeatsBaselineBWithStableUplift() {
        ExperimentDecisionService.DecisionThresholds thresholds = new ExperimentDecisionService.DecisionThresholds(
                100,
                30,
                7,
                50,
                15,
                3,
                new BigDecimal("0.0500"),
                new BigDecimal("0.1000")
        );

        Map<String, ExperimentVariantPerformanceDTO> fullMetrics = Map.of(
                ExperimentService.VARIANT_A, metric(ExperimentService.VARIANT_A, "900.00", 40, 120, "7.50", "22.50", "0.3333", 12),
                ExperimentService.VARIANT_B, metric(ExperimentService.VARIANT_B, "1000.00", 50, 125, "8.00", "20.00", "0.4000", 12),
                ExperimentService.VARIANT_C, metric(ExperimentService.VARIANT_C, "1184.00", 52, 140, "8.46", "22.77", "0.3714", 12)
        );
        Map<String, ExperimentVariantPerformanceDTO> recentMetrics = Map.of(
                ExperimentService.VARIANT_A, metric(ExperimentService.VARIANT_A, "420.00", 18, 60, "7.00", "23.33", "0.3000", 6),
                ExperimentService.VARIANT_B, metric(ExperimentService.VARIANT_B, "480.00", 24, 60, "8.00", "20.00", "0.4000", 6),
                ExperimentService.VARIANT_C, metric(ExperimentService.VARIANT_C, "552.00", 24, 65, "8.49", "23.00", "0.3692", 6)
        );

        ExperimentDecisionService.ProposedDecision decision = experimentDecisionService.determineWinner(fullMetrics, recentMetrics, thresholds);

        assertEquals(ExperimentService.VARIANT_C, decision.suggestedWinner());
        assertEquals(ExperimentService.MODE_FORCE_C, decision.targetMode());
        assertTrue(decision.sufficientData());
        assertTrue(decision.stable());
        assertEquals("C_OUTPERFORMS_B", decision.reason());
    }

    @Test
    void shouldStayUndecidedWhenMetricsAreInsufficient() {
        ExperimentDecisionService.DecisionThresholds thresholds = new ExperimentDecisionService.DecisionThresholds(
                100,
                30,
                7,
                50,
                15,
                3,
                new BigDecimal("0.0500"),
                new BigDecimal("0.1000")
        );

        Map<String, ExperimentVariantPerformanceDTO> fullMetrics = Map.of(
                ExperimentService.VARIANT_A, metric(ExperimentService.VARIANT_A, "120.00", 8, 20, "6.00", "15.00", "0.4000", 2),
                ExperimentService.VARIANT_B, metric(ExperimentService.VARIANT_B, "150.00", 9, 22, "6.82", "16.67", "0.4091", 2),
                ExperimentService.VARIANT_C, metric(ExperimentService.VARIANT_C, "170.00", 10, 24, "7.08", "17.00", "0.4167", 2)
        );
        Map<String, ExperimentVariantPerformanceDTO> recentMetrics = fullMetrics;

        ExperimentDecisionService.ProposedDecision decision = experimentDecisionService.determineWinner(fullMetrics, recentMetrics, thresholds);

        assertEquals("NONE", decision.suggestedWinner());
        assertFalse(decision.sufficientData());
        assertFalse(decision.stable());
        assertEquals("INSUFFICIENT_DATA", decision.reason());
    }

    private ExperimentVariantPerformanceDTO metric(
            String variant,
            String totalRevenue,
            long totalOrders,
            long totalSessions,
            String rps,
            String aov,
            String cr,
            long activeDays
    ) {
        return ExperimentVariantPerformanceDTO.builder()
                .variant(variant)
                .totalRevenue(new BigDecimal(totalRevenue))
                .totalOrders(totalOrders)
                .totalSessions(totalSessions)
                .rps(new BigDecimal(rps))
                .aov(new BigDecimal(aov))
                .cr(new BigDecimal(cr))
                .activeDays(activeDays)
                .build();
    }
}
