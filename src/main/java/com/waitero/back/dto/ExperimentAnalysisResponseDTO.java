package com.waitero.back.dto;

import com.waitero.back.service.ExperimentService;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Builder
public record ExperimentAnalysisResponseDTO(
        Long restaurantId,
        LocalDate dateFrom,
        LocalDate dateTo,
        Map<String, ExperimentVariantPerformanceDTO> metrics,
        String winner,
        String currentMode,
        String targetMode,
        BigDecimal upliftVsBaseline,
        boolean sufficientData,
        boolean stable,
        String reason,
        String action
) {

    public static ExperimentAnalysisResponseDTO from(ExperimentAnalysisDTO analysis) {
        Map<String, ExperimentVariantPerformanceDTO> metrics = new LinkedHashMap<>();
        metrics.put(ExperimentService.VARIANT_A, analysis.variantA());
        metrics.put(ExperimentService.VARIANT_B, analysis.variantB());
        metrics.put(ExperimentService.VARIANT_C, analysis.variantC());

        return ExperimentAnalysisResponseDTO.builder()
                .restaurantId(analysis.restaurantId())
                .dateFrom(analysis.dateFrom())
                .dateTo(analysis.dateTo())
                .metrics(metrics)
                .winner(analysis.suggestedWinner())
                .currentMode(toExternalMode(analysis.currentMode()))
                .targetMode(toExternalMode(analysis.targetMode()))
                .upliftVsBaseline(analysis.upliftVsBaseline())
                .sufficientData(analysis.sufficientData())
                .stable(analysis.stable())
                .reason(analysis.reason())
                .action(analysis.action())
                .build();
    }

    private static String toExternalMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return mode;
        }
        return mode.startsWith("MODE_") ? mode : "MODE_" + mode;
    }
}
