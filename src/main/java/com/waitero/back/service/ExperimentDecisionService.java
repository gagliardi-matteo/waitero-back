package com.waitero.back.service;

import com.waitero.back.dto.ExperimentVariantPerformanceDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Service
public class ExperimentDecisionService {

    public ProposedDecision determineWinner(
            Map<String, ExperimentVariantPerformanceDTO> fullMetrics,
            Map<String, ExperimentVariantPerformanceDTO> recentMetrics,
            DecisionThresholds thresholds
    ) {
        ExperimentVariantPerformanceDTO fullA = metric(fullMetrics, ExperimentService.VARIANT_A);
        ExperimentVariantPerformanceDTO fullB = metric(fullMetrics, ExperimentService.VARIANT_B);
        ExperimentVariantPerformanceDTO fullC = metric(fullMetrics, ExperimentService.VARIANT_C);
        ExperimentVariantPerformanceDTO recentA = metric(recentMetrics, ExperimentService.VARIANT_A);
        ExperimentVariantPerformanceDTO recentB = metric(recentMetrics, ExperimentService.VARIANT_B);
        ExperimentVariantPerformanceDTO recentC = metric(recentMetrics, ExperimentService.VARIANT_C);

        boolean cEnough = hasEnoughData(fullB, thresholds) && hasEnoughData(fullC, thresholds);
        BigDecimal cUplift = uplift(fullC, fullB);
        boolean cStable = cEnough && isStablePair(fullB, fullC, recentB, recentC, thresholds);
        if (cStable && cUplift.compareTo(thresholds.minCUpliftVsBaseline()) > 0) {
            return new ProposedDecision(
                    ExperimentService.VARIANT_C,
                    ExperimentService.MODE_FORCE_C,
                    cUplift,
                    true,
                    true,
                    "C_OUTPERFORMS_B"
            );
        }

        boolean abEnough = hasEnoughData(fullA, thresholds) && hasEnoughData(fullB, thresholds);
        boolean abStable = abEnough && isStablePair(fullB, fullA, recentB, recentA, thresholds);
        if (abStable) {
            if (isBetter(fullB, fullA)) {
                return new ProposedDecision(
                        ExperimentService.VARIANT_B,
                        ExperimentService.MODE_FORCE_B,
                        BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                        true,
                        true,
                        "B_OUTPERFORMS_A"
                );
            }
            return new ProposedDecision(
                    ExperimentService.VARIANT_A,
                    ExperimentService.MODE_FORCE_A,
                    uplift(fullA, fullB),
                    true,
                    true,
                    "A_OUTPERFORMS_B"
            );
        }

        return new ProposedDecision(
                "NONE",
                null,
                BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                cEnough || abEnough,
                false,
                cEnough || abEnough ? "UNSTABLE_METRICS" : "INSUFFICIENT_DATA"
        );
    }

    private boolean hasEnoughData(ExperimentVariantPerformanceDTO metrics, DecisionThresholds thresholds) {
        if (metrics == null) {
            return false;
        }
        return metrics.totalSessions() >= thresholds.minSessionsPerVariant()
                && metrics.totalOrders() >= thresholds.minOrdersPerVariant()
                && metrics.activeDays() >= thresholds.minActiveDaysPerVariant();
    }

    private boolean hasEnoughRecentData(ExperimentVariantPerformanceDTO metrics, DecisionThresholds thresholds) {
        if (metrics == null) {
            return false;
        }
        return metrics.totalSessions() >= thresholds.recentMinSessionsPerVariant()
                && metrics.totalOrders() >= thresholds.recentMinOrdersPerVariant()
                && metrics.activeDays() >= thresholds.recentMinActiveDaysPerVariant();
    }

    private boolean isStablePair(
            ExperimentVariantPerformanceDTO baselineFull,
            ExperimentVariantPerformanceDTO candidateFull,
            ExperimentVariantPerformanceDTO baselineRecent,
            ExperimentVariantPerformanceDTO candidateRecent,
            DecisionThresholds thresholds
    ) {
        if (!hasEnoughRecentData(baselineRecent, thresholds) || !hasEnoughRecentData(candidateRecent, thresholds)) {
            return false;
        }

        BigDecimal fullUplift = uplift(candidateFull, baselineFull);
        BigDecimal recentUplift = uplift(candidateRecent, baselineRecent);
        if (fullUplift.signum() != 0 && recentUplift.signum() != 0 && fullUplift.signum() != recentUplift.signum()) {
            return false;
        }
        return fullUplift.subtract(recentUplift).abs().compareTo(thresholds.maxUpliftDrift()) <= 0;
    }

    private boolean isBetter(ExperimentVariantPerformanceDTO left, ExperimentVariantPerformanceDTO right) {
        int rpsComparison = safeMetric(left.rps()).compareTo(safeMetric(right.rps()));
        if (rpsComparison != 0) {
            return rpsComparison > 0;
        }

        int aovComparison = safeMetric(left.aov()).compareTo(safeMetric(right.aov()));
        if (aovComparison != 0) {
            return aovComparison > 0;
        }

        int crComparison = safeRate(left.cr()).compareTo(safeRate(right.cr()));
        if (crComparison != 0) {
            return crComparison > 0;
        }

        return false;
    }

    private BigDecimal uplift(ExperimentVariantPerformanceDTO candidate, ExperimentVariantPerformanceDTO baseline) {
        BigDecimal candidateRps = safeMetric(candidate == null ? null : candidate.rps());
        BigDecimal baselineRps = safeMetric(baseline == null ? null : baseline.rps());
        if (baselineRps.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return candidateRps.subtract(baselineRps)
                .divide(baselineRps, 4, RoundingMode.HALF_UP);
    }

    private ExperimentVariantPerformanceDTO metric(Map<String, ExperimentVariantPerformanceDTO> metrics, String variant) {
        return metrics == null ? null : metrics.get(variant);
    }

    private BigDecimal safeMetric(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal safeRate(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    public record DecisionThresholds(
            long minSessionsPerVariant,
            long minOrdersPerVariant,
            long minActiveDaysPerVariant,
            long recentMinSessionsPerVariant,
            long recentMinOrdersPerVariant,
            long recentMinActiveDaysPerVariant,
            BigDecimal minCUpliftVsBaseline,
            BigDecimal maxUpliftDrift
    ) {
    }

    public record ProposedDecision(
            String suggestedWinner,
            String targetMode,
            BigDecimal upliftVsBaseline,
            boolean sufficientData,
            boolean stable,
            String reason
    ) {
    }
}
