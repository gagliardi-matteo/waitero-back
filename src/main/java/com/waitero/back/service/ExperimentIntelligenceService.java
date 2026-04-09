package com.waitero.back.service;

import com.waitero.back.dto.ExperimentDecision;
import com.waitero.back.dto.ExperimentMetricsDTO;
import com.waitero.back.dto.ExperimentVariantMetricsDTO;
import com.waitero.back.entity.ExperimentConfig;
import com.waitero.back.entity.ExperimentDecisionLog;
import com.waitero.back.repository.ExperimentConfigRepository;
import com.waitero.back.repository.ExperimentDecisionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ExperimentIntelligenceService {

    private static final String KEEP_A = "KEEP_A";
    private static final String KEEP_B = "KEEP_B";
    private static final String WAIT = "WAIT";
    private static final String WINNER_A = "A";
    private static final String WINNER_B = "B";
    private static final String WINNER_NONE = "NONE";

    private final AnalyticsService analyticsService;
    private final ExperimentConfigRepository experimentConfigRepository;
    private final ExperimentDecisionLogRepository experimentDecisionLogRepository;
    private final ExperimentService experimentService;

    @Transactional
    public ExperimentDecision evaluateExperiment(Long restaurantId) {
        ExperimentConfig config = loadConfig(restaurantId);
        ExperimentMetricsDTO metrics = analyticsService.getExperimentMetrics(restaurantId);
        ExperimentVariantMetricsDTO a = metrics.variantA();
        ExperimentVariantMetricsDTO b = metrics.variantB();

        double revenueA = toDouble(a.revenue());
        double revenueB = toDouble(b.revenue());
        double aovA = toDouble(a.avgOrderValue());
        double aovB = toDouble(b.avgOrderValue());
        double itemsA = toDouble(a.itemsPerOrder());
        double itemsB = toDouble(b.itemsPerOrder());
        int sampleSize = Math.toIntExact(Math.min((long) Integer.MAX_VALUE, a.orders() + b.orders()));

        double upliftRevenue = revenueA - revenueB;
        double upliftAOV = aovA - aovB;
        double upliftItems = itemsA - itemsB;
        double upliftPercent = safeDivide(upliftRevenue, revenueB) * 100.0d;
        double confidence = confidence(aovA, aovB, sampleSize);

        String recommendation = recommendation(sampleSize, confidence, upliftPercent, config);
        String winningVariant = switch (recommendation) {
            case KEEP_A -> WINNER_A;
            case KEEP_B -> WINNER_B;
            default -> WINNER_NONE;
        };
        boolean significant = !WAIT.equals(recommendation);

        ExperimentDecision decision = ExperimentDecision.builder()
                .restaurantId(restaurantId)
                .winningVariant(winningVariant)
                .upliftRevenue(upliftRevenue)
                .upliftAOV(upliftAOV)
                .upliftItems(upliftItems)
                .upliftPercent(upliftPercent)
                .confidence(confidence)
                .sampleSize(sampleSize)
                .isSignificant(significant)
                .recommendation(recommendation)
                .build();
        logDecision(decision);
        return decision;
    }

    @Transactional
    public void applyAutopilotIfEnabled(Long restaurantId) {
        ExperimentConfig config = loadConfig(restaurantId);
        if (!config.isAutopilotEnabled()) {
            return;
        }

        ExperimentDecision decision = evaluateExperiment(restaurantId);
        if (KEEP_A.equals(decision.recommendation())) {
            experimentService.setExperimentMode(restaurantId, ExperimentService.MODE_FORCE_A);
        } else if (KEEP_B.equals(decision.recommendation())) {
            experimentService.setExperimentMode(restaurantId, ExperimentService.MODE_FORCE_B);
        }
    }

    private ExperimentConfig loadConfig(Long restaurantId) {
        return experimentConfigRepository.findByRestaurantId(restaurantId)
                .orElseGet(() -> ExperimentConfig.builder()
                        .restaurantId(restaurantId)
                        .autopilotEnabled(false)
                        .minSampleSize(50)
                        .minUpliftPercent(5.0d)
                        .minConfidence(0.95d)
                        .holdoutPercent(10)
                        .updatedAt(Instant.now())
                        .build());
    }

    private String recommendation(int sampleSize, double confidence, double upliftPercent, ExperimentConfig config) {
        if (sampleSize < config.getMinSampleSize()) {
            return WAIT;
        }
        if (confidence < config.getMinConfidence()) {
            return WAIT;
        }
        if (upliftPercent > config.getMinUpliftPercent()) {
            return KEEP_A;
        }
        if (upliftPercent < -config.getMinUpliftPercent()) {
            return KEEP_B;
        }
        return WAIT;
    }

    private double confidence(double aovA, double aovB, int sampleSize) {
        if (sampleSize <= 0) {
            return 0.0d;
        }
        double diff = aovA - aovB;
        double variance = (aovA + aovB) / 2.0d;
        if (!Double.isFinite(variance) || variance <= 0.0d) {
            return 0.0d;
        }
        double z = diff / Math.sqrt(variance / sampleSize);
        if (!Double.isFinite(z)) {
            return 0.0d;
        }
        return 1.0d / (1.0d + Math.exp(-z));
    }

    private double safeDivide(double numerator, double denominator) {
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator) || denominator == 0.0d) {
            return 0.0d;
        }
        return numerator / denominator;
    }

    private double toDouble(BigDecimal value) {
        if (value == null) {
            return 0.0d;
        }
        double result = value.doubleValue();
        return Double.isFinite(result) ? result : 0.0d;
    }

    private void logDecision(ExperimentDecision decision) {
        experimentDecisionLogRepository.save(ExperimentDecisionLog.builder()
                .restaurantId(decision.restaurantId())
                .decision(decision.recommendation())
                .uplift(decision.upliftPercent())
                .confidence(decision.confidence())
                .createdAt(Instant.now())
                .build());
    }
}