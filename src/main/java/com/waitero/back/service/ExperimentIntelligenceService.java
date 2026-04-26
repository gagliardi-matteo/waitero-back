package com.waitero.back.service;

import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.dto.ExperimentAnalysisDTO;
import com.waitero.back.dto.ExperimentDecision;
import com.waitero.back.dto.ExperimentVariantPerformanceDTO;
import com.waitero.back.entity.ExperimentConfig;
import com.waitero.back.entity.ExperimentDecisionLog;
import com.waitero.back.repository.ExperimentConfigRepository;
import com.waitero.back.repository.ExperimentDecisionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExperimentIntelligenceService {

    private static final String WINNER_NONE = "NONE";
    private static final String ACTION_KEEP_CURRENT = "KEEP_CURRENT_MODE";
    private static final String ACTION_KEEP_EXPLORING = "KEEP_EXPLORING";
    private static final String ACTION_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    private static final String ACTION_COOLDOWN_HOLD = "COOLDOWN_HOLD";
    private static final List<String> SWITCH_ACTIONS = List.of("SWITCH_TO_A", "SWITCH_TO_B", "SWITCH_TO_C");

    private final ExperimentAnalyticsService experimentAnalyticsService;
    private final ExperimentDecisionService experimentDecisionService;
    private final ExperimentConfigRepository experimentConfigRepository;
    private final ExperimentDecisionLogRepository experimentDecisionLogRepository;
    private final ExperimentService experimentService;
    private final AnalyticsV2TimeRangeResolver analyticsV2TimeRangeResolver;
    private final ExperimentAutopilotLogger experimentAutopilotLogger;
    private final Clock analyticsV2Clock;

    @Value("${waitero.experiment.min-sessions-per-variant:100}")
    private long minSessionsPerVariant;

    @Value("${waitero.experiment.min-orders-per-variant:30}")
    private long minOrdersPerVariant;

    @Value("${waitero.experiment.min-active-days-per-variant:7}")
    private long minActiveDaysPerVariant;

    @Value("${waitero.experiment.max-uplift-drift:0.10}")
    private BigDecimal maxUpliftDrift;

    @Value("${waitero.experiment.autopilot.cooldown-minutes:30}")
    private long autopilotCooldownMinutes;

    @Transactional(readOnly = true)
    public ExperimentAnalysisDTO getExperimentAnalysis(Long restaurantId, LocalDate dateFrom, LocalDate dateTo) {
        return analyze(restaurantId, analyticsV2TimeRangeResolver.resolve(dateFrom, dateTo));
    }

    @Transactional(readOnly = true)
    public ExperimentDecision evaluateExperiment(Long restaurantId) {
        ExperimentAnalysisDTO analysis = getExperimentAnalysis(restaurantId, null, null);
        ExperimentVariantPerformanceDTO baseline = analysis.variantB();
        ExperimentVariantPerformanceDTO winner = winnerMetrics(analysis);

        return ExperimentDecision.builder()
                .restaurantId(analysis.restaurantId())
                .winningVariant(analysis.suggestedWinner())
                .upliftRevenue(moneyDiff(winner == null ? null : winner.totalRevenue(), baseline == null ? null : baseline.totalRevenue()))
                .upliftAOV(moneyDiff(winner == null ? null : winner.aov(), baseline == null ? null : baseline.aov()))
                .upliftItems(0.0d)
                .upliftPercent(percentValue(analysis.upliftVsBaseline()))
                .confidence(analysis.stable() ? 1.0d : 0.0d)
                .sampleSize(Math.toIntExact(Math.min(Integer.MAX_VALUE, totalOrders(analysis))))
                .isSignificant(analysis.sufficientData() && analysis.stable())
                .recommendation(analysis.action())
                .build();
    }

    @Transactional
    public void applyAutopilotIfEnabled(Long restaurantId) {
        ExperimentConfig config = loadConfig(restaurantId);
        if (!config.isAutopilotEnabled()) {
            return;
        }

        ExperimentAnalysisDTO analysis = analyze(restaurantId, analyticsV2TimeRangeResolver.resolve(null, null));
        if (analysis.action().startsWith("SWITCH_TO_") && analysis.targetMode() != null) {
            experimentService.setExperimentMode(restaurantId, analysis.targetMode());
        }

        experimentAutopilotLogger.logDecision(analysis);
        experimentDecisionLogRepository.save(ExperimentDecisionLog.builder()
                .restaurantId(restaurantId)
                .decision(analysis.action())
                .uplift(toDouble(analysis.upliftVsBaseline()))
                .confidence(analysis.stable() ? 1.0d : 0.0d)
                .createdAt(Instant.now(analyticsV2Clock))
                .build());
    }

    private ExperimentAnalysisDTO analyze(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        ExperimentConfig config = loadConfig(restaurantId);
        String currentMode = experimentService.getExperimentMode(restaurantId);
        Map<String, ExperimentVariantPerformanceDTO> fullMetrics = experimentAnalyticsService.computeMetrics(restaurantId, timeRange);
        Map<String, ExperimentVariantPerformanceDTO> recentMetrics = experimentAnalyticsService.computeMetrics(restaurantId, recentHalf(timeRange));

        ExperimentDecisionService.ProposedDecision proposedDecision = experimentDecisionService.determineWinner(
                fullMetrics,
                recentMetrics,
                thresholds(config)
        );

        String action = resolveAction(restaurantId, currentMode, proposedDecision);
        String targetMode = proposedDecision.targetMode() != null ? proposedDecision.targetMode() : currentMode;
        if (ACTION_COOLDOWN_HOLD.equals(action)) {
            targetMode = currentMode;
        }

        return ExperimentAnalysisDTO.builder()
                .restaurantId(restaurantId)
                .dateFrom(timeRange.dateFrom())
                .dateTo(timeRange.dateTo())
                .variantA(fullMetrics.get(ExperimentService.VARIANT_A))
                .variantB(fullMetrics.get(ExperimentService.VARIANT_B))
                .variantC(fullMetrics.get(ExperimentService.VARIANT_C))
                .currentMode(currentMode)
                .targetMode(targetMode)
                .suggestedWinner(proposedDecision.suggestedWinner())
                .upliftVsBaseline(safeRate(proposedDecision.upliftVsBaseline()))
                .sufficientData(proposedDecision.sufficientData())
                .stable(proposedDecision.stable())
                .reason(proposedDecision.reason())
                .action(action)
                .build();
    }

    private String resolveAction(
            Long restaurantId,
            String currentMode,
            ExperimentDecisionService.ProposedDecision proposedDecision
    ) {
        if (proposedDecision == null) {
            return ACTION_INSUFFICIENT_DATA;
        }
        if (proposedDecision.targetMode() == null || WINNER_NONE.equals(proposedDecision.suggestedWinner())) {
            if (ExperimentService.MODE_ABC.equals(currentMode)) {
                return ACTION_KEEP_EXPLORING;
            }
            return ACTION_INSUFFICIENT_DATA;
        }
        if (proposedDecision.targetMode().equals(currentMode)) {
            return ExperimentService.MODE_ABC.equals(currentMode) ? ACTION_KEEP_EXPLORING : ACTION_KEEP_CURRENT;
        }
        if (cooldownActive(restaurantId)) {
            return ACTION_COOLDOWN_HOLD;
        }
        return "SWITCH_TO_" + proposedDecision.suggestedWinner();
    }

    private boolean cooldownActive(Long restaurantId) {
        Instant now = Instant.now(analyticsV2Clock);
        return experimentDecisionLogRepository
                .findFirstByRestaurantIdAndDecisionInOrderByCreatedAtDesc(restaurantId, SWITCH_ACTIONS)
                .map(ExperimentDecisionLog::getCreatedAt)
                .filter(createdAt -> createdAt != null)
                .map(createdAt -> now.isBefore(createdAt.plus(Duration.ofMinutes(Math.max(1L, autopilotCooldownMinutes)))))
                .orElse(false);
    }

    private ExperimentConfig loadConfig(Long restaurantId) {
        return experimentConfigRepository.findByRestaurantId(restaurantId)
                .orElseGet(() -> ExperimentConfig.builder()
                        .restaurantId(restaurantId)
                        .autopilotEnabled(false)
                        .minSampleSize(50)
                        .minUpliftPercent(5.0d)
                        .minConfidence(0.95d)
                        .holdoutPercent(5)
                        .updatedAt(Instant.now(analyticsV2Clock))
                        .build());
    }

    private ExperimentDecisionService.DecisionThresholds thresholds(ExperimentConfig config) {
        long minSessions = Math.max(minSessionsPerVariant, config == null ? 0L : config.getMinSampleSize());
        long minOrders = Math.max(1L, minOrdersPerVariant);
        long minDays = Math.max(1L, minActiveDaysPerVariant);

        return new ExperimentDecisionService.DecisionThresholds(
                minSessions,
                minOrders,
                minDays,
                Math.max(1L, minSessions / 2L),
                Math.max(1L, minOrders / 2L),
                Math.max(3L, minDays / 2L),
                BigDecimal.valueOf(config == null ? 5.0d : config.getMinUpliftPercent())
                        .movePointLeft(2)
                        .setScale(4, RoundingMode.HALF_UP),
                safeRate(maxUpliftDrift)
        );
    }

    private AnalyticsV2TimeRange recentHalf(AnalyticsV2TimeRange timeRange) {
        long spanDays = Math.max(1L, ChronoUnit.DAYS.between(timeRange.dateFrom(), timeRange.dateTo()) + 1L);
        long recentSpan = Math.max(1L, spanDays / 2L);
        LocalDate recentFrom = timeRange.dateTo().minusDays(recentSpan - 1L);
        return new AnalyticsV2TimeRange(recentFrom, timeRange.dateTo());
    }

    private ExperimentVariantPerformanceDTO winnerMetrics(ExperimentAnalysisDTO analysis) {
        if (analysis == null) {
            return null;
        }
        return switch (analysis.suggestedWinner()) {
            case ExperimentService.VARIANT_A -> analysis.variantA();
            case ExperimentService.VARIANT_B -> analysis.variantB();
            case ExperimentService.VARIANT_C -> analysis.variantC();
            default -> null;
        };
    }

    private long totalOrders(ExperimentAnalysisDTO analysis) {
        if (analysis == null) {
            return 0L;
        }
        return analysis.variantA().totalOrders() + analysis.variantB().totalOrders() + analysis.variantC().totalOrders();
    }

    private BigDecimal safeRate(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private double moneyDiff(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return 0.0d;
        }
        BigDecimal safeLeft = left;
        BigDecimal safeRight = right;
        return safeLeft.subtract(safeRight).doubleValue();
    }

    private double percentValue(BigDecimal uplift) {
        return safeRate(uplift).movePointRight(2).doubleValue();
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0.0d : value.doubleValue();
    }
}
