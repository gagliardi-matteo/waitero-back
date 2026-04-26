package com.waitero.back.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.back.dto.DishIntelligenceDTO;
import com.waitero.back.entity.Piatto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MenuRankingExperimentLogger {

    private static final Logger log = LoggerFactory.getLogger("menu.ranking.experiment");
    private static final int TOP_DISH_LOG_LIMIT = 3;

    private final ObjectMapper objectMapper;

    public void logDishScoreRanking(
            Long restaurantId,
            String sessionId,
            List<Piatto> rankedDishes,
            Map<Long, BigDecimal> scoreByDishId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("variant", ExperimentService.VARIANT_C);
        payload.put("restaurantId", restaurantId);
        payload.put("sessionId", sessionId);
        payload.put("topDishes", rankedDishes.stream()
                .limit(TOP_DISH_LOG_LIMIT)
                .map(Piatto::getId)
                .toList());

        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        rankedDishes.stream()
                .limit(TOP_DISH_LOG_LIMIT)
                .forEach(dish -> scores.put(String.valueOf(dish.getId()), scoreByDishId.getOrDefault(dish.getId(), BigDecimal.ZERO)));
        payload.put("scores", scores);
        info(payload);
    }

    public void logDishScoreFallback(Long restaurantId, String sessionId, Exception exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("variant", ExperimentService.VARIANT_C);
        payload.put("restaurantId", restaurantId);
        payload.put("sessionId", sessionId);
        payload.put("fallbackVariant", ExperimentService.VARIANT_A);
        payload.put("errorType", exception == null ? null : exception.getClass().getSimpleName());
        payload.put("errorMessage", safeMessage(exception == null ? null : exception.getMessage()));
        warn(payload, exception);
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String normalized = message.trim();
        return normalized.length() > 300 ? normalized.substring(0, 300) : normalized;
    }

    private void info(Map<String, Object> payload) {
        try {
            log.info(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize menu ranking experiment log", ex);
        }
    }

    private void warn(Map<String, Object> payload, Exception exception) {
        try {
            log.warn(objectMapper.writeValueAsString(payload), exception);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize menu ranking experiment fallback log", ex);
        }
    }
}
