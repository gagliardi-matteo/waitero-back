package com.waitero.back.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.back.dto.ExperimentAnalysisDTO;
import com.waitero.back.dto.ExperimentVariantPerformanceDTO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ExperimentAutopilotLogger {

    private static final Logger log = LoggerFactory.getLogger("experiment.autopilot");

    private final ObjectMapper objectMapper;

    public void logDecision(ExperimentAnalysisDTO analysis) {
        if (analysis == null) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("restaurantId", analysis.restaurantId());
        payload.put("currentMode", analysis.currentMode());
        payload.put("targetMode", analysis.targetMode());
        payload.put("winner", analysis.suggestedWinner());
        payload.put("uplift", analysis.upliftVsBaseline());
        payload.put("action", analysis.action());
        payload.put("stable", analysis.stable());
        payload.put("sufficientData", analysis.sufficientData());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put(ExperimentService.VARIANT_A, metricPayload(analysis.variantA()));
        metrics.put(ExperimentService.VARIANT_B, metricPayload(analysis.variantB()));
        metrics.put(ExperimentService.VARIANT_C, metricPayload(analysis.variantC()));
        payload.put("metrics", metrics);

        info(payload);
    }

    private Map<String, Object> metricPayload(ExperimentVariantPerformanceDTO metric) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalRevenue", metric == null ? null : metric.totalRevenue());
        payload.put("totalOrders", metric == null ? null : metric.totalOrders());
        payload.put("totalSessions", metric == null ? null : metric.totalSessions());
        payload.put("rps", metric == null ? null : metric.rps());
        payload.put("aov", metric == null ? null : metric.aov());
        payload.put("cr", metric == null ? null : metric.cr());
        payload.put("activeDays", metric == null ? null : metric.activeDays());
        return payload;
    }

    private void info(Map<String, Object> payload) {
        try {
            log.info(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize experiment autopilot decision log", ex);
        }
    }
}
