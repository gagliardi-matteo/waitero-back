package com.waitero.back.service;

import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.dto.ExperimentVariantPerformanceDTO;
import com.waitero.back.entity.ExperimentConfig;
import com.waitero.back.entity.ExperimentDecisionLog;
import com.waitero.back.repository.ExperimentConfigRepository;
import com.waitero.back.repository.ExperimentDecisionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentIntelligenceServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-04-24T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ExperimentAnalyticsService experimentAnalyticsService;

    @Mock
    private ExperimentDecisionService experimentDecisionService;

    @Mock
    private ExperimentConfigRepository experimentConfigRepository;

    @Mock
    private ExperimentDecisionLogRepository experimentDecisionLogRepository;

    @Mock
    private ExperimentService experimentService;

    @Mock
    private AnalyticsV2TimeRangeResolver analyticsV2TimeRangeResolver;

    @Mock
    private ExperimentAutopilotLogger experimentAutopilotLogger;

    private ExperimentIntelligenceService experimentIntelligenceService;

    @BeforeEach
    void setUp() {
        experimentIntelligenceService = new ExperimentIntelligenceService(
                experimentAnalyticsService,
                experimentDecisionService,
                experimentConfigRepository,
                experimentDecisionLogRepository,
                experimentService,
                analyticsV2TimeRangeResolver,
                experimentAutopilotLogger,
                FIXED_CLOCK
        );
        ReflectionTestUtils.setField(experimentIntelligenceService, "minSessionsPerVariant", 100L);
        ReflectionTestUtils.setField(experimentIntelligenceService, "minOrdersPerVariant", 30L);
        ReflectionTestUtils.setField(experimentIntelligenceService, "minActiveDaysPerVariant", 7L);
        ReflectionTestUtils.setField(experimentIntelligenceService, "maxUpliftDrift", new BigDecimal("0.1000"));
        ReflectionTestUtils.setField(experimentIntelligenceService, "autopilotCooldownMinutes", 30L);
    }

    @Test
    void shouldSwitchToWinningVariantCWhenAutopilotIsEnabled() {
        Long restaurantId = 42L;
        AnalyticsV2TimeRange timeRange = new AnalyticsV2TimeRange(LocalDate.of(2026, 3, 26), LocalDate.of(2026, 4, 24));

        when(experimentConfigRepository.findByRestaurantId(restaurantId)).thenReturn(Optional.of(config(restaurantId, true)));
        when(analyticsV2TimeRangeResolver.resolve(null, null)).thenReturn(timeRange);
        when(experimentService.getExperimentMode(restaurantId)).thenReturn(ExperimentService.MODE_ABC);
        when(experimentAnalyticsService.computeMetrics(eq(restaurantId), any(AnalyticsV2TimeRange.class)))
                .thenReturn(metrics());
        when(experimentDecisionService.determineWinner(any(), any(), any()))
                .thenReturn(new ExperimentDecisionService.ProposedDecision(
                        ExperimentService.VARIANT_C,
                        ExperimentService.MODE_FORCE_C,
                        new BigDecimal("0.0800"),
                        true,
                        true,
                        "C_OUTPERFORMS_B"
                ));
        when(experimentDecisionLogRepository.findFirstByRestaurantIdAndDecisionInOrderByCreatedAtDesc(eq(restaurantId), any()))
                .thenReturn(Optional.empty());

        experimentIntelligenceService.applyAutopilotIfEnabled(restaurantId);

        verify(experimentService).setExperimentMode(restaurantId, ExperimentService.MODE_FORCE_C);
        ArgumentCaptor<ExperimentDecisionLog> captor = ArgumentCaptor.forClass(ExperimentDecisionLog.class);
        verify(experimentDecisionLogRepository).save(captor.capture());
        assertEquals("SWITCH_TO_C", captor.getValue().getDecision());
    }

    @Test
    void shouldRespectCooldownBeforeSwitchingAgain() {
        Long restaurantId = 99L;
        AnalyticsV2TimeRange timeRange = new AnalyticsV2TimeRange(LocalDate.of(2026, 3, 26), LocalDate.of(2026, 4, 24));

        when(experimentConfigRepository.findByRestaurantId(restaurantId)).thenReturn(Optional.of(config(restaurantId, true)));
        when(analyticsV2TimeRangeResolver.resolve(null, null)).thenReturn(timeRange);
        when(experimentService.getExperimentMode(restaurantId)).thenReturn(ExperimentService.MODE_ABC);
        when(experimentAnalyticsService.computeMetrics(eq(restaurantId), any(AnalyticsV2TimeRange.class)))
                .thenReturn(metrics());
        when(experimentDecisionService.determineWinner(any(), any(), any()))
                .thenReturn(new ExperimentDecisionService.ProposedDecision(
                        ExperimentService.VARIANT_C,
                        ExperimentService.MODE_FORCE_C,
                        new BigDecimal("0.0800"),
                        true,
                        true,
                        "C_OUTPERFORMS_B"
                ));
        when(experimentDecisionLogRepository.findFirstByRestaurantIdAndDecisionInOrderByCreatedAtDesc(eq(restaurantId), any()))
                .thenReturn(Optional.of(ExperimentDecisionLog.builder()
                        .restaurantId(restaurantId)
                        .decision("SWITCH_TO_B")
                        .uplift(0.04d)
                        .confidence(1.0d)
                        .createdAt(Instant.parse("2026-04-24T09:45:00Z"))
                        .build()));

        experimentIntelligenceService.applyAutopilotIfEnabled(restaurantId);

        verify(experimentService, never()).setExperimentMode(any(), any());
        ArgumentCaptor<ExperimentDecisionLog> captor = ArgumentCaptor.forClass(ExperimentDecisionLog.class);
        verify(experimentDecisionLogRepository).save(captor.capture());
        assertEquals("COOLDOWN_HOLD", captor.getValue().getDecision());
    }

    private ExperimentConfig config(Long restaurantId, boolean autopilotEnabled) {
        return ExperimentConfig.builder()
                .restaurantId(restaurantId)
                .autopilotEnabled(autopilotEnabled)
                .minSampleSize(50)
                .minUpliftPercent(5.0d)
                .minConfidence(0.95d)
                .holdoutPercent(5)
                .updatedAt(Instant.parse("2026-04-24T09:00:00Z"))
                .build();
    }

    private Map<String, ExperimentVariantPerformanceDTO> metrics() {
        return Map.of(
                ExperimentService.VARIANT_A, metric(ExperimentService.VARIANT_A, "900.00", 40, 120, "7.50", "22.50", "0.3333", 12),
                ExperimentService.VARIANT_B, metric(ExperimentService.VARIANT_B, "1000.00", 50, 125, "8.00", "20.00", "0.4000", 12),
                ExperimentService.VARIANT_C, metric(ExperimentService.VARIANT_C, "1184.00", 52, 140, "8.46", "22.77", "0.3714", 12)
        );
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
