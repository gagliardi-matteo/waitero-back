package com.waitero.analyticsv2.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.analyticsv2.dto.MenuRankedDishV2DTO;
import com.waitero.analyticsv2.dto.UpsellSuggestionV2DTO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AnalyticsV2JsonLogger {

    private static final Logger log = LoggerFactory.getLogger("analyticsv2.rollout");
    private static final int LOG_LIMIT = 5;

    private final ObjectMapper objectMapper;

    public void logRanking(Long restaurantId, AnalyticsV2TimeRange timeRange, List<MenuRankedDishV2DTO> ranking) {
        List<Map<String, Object>> rankingPayload = ranking.stream()
                .limit(LOG_LIMIT)
                .map(dish -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("dishId", dish.dishId());
                    row.put("dishName", dish.dishName());
                    row.put("category", dish.category());
                    row.put("orderCount", dish.orderCount());
                    row.put("revenuePerDish", dish.revenuePerDish());
                    row.put("rankingScore", dish.rankingScore());
                    return row;
                })
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "analyticsv2_ranking");
        payload.put("restaurantId", restaurantId);
        payload.put("dateFrom", timeRange.dateFrom());
        payload.put("dateTo", timeRange.dateTo());
        payload.put("topDishes", rankingPayload);
        info(payload);
    }

    public void logUpsell(
            String computation,
            Long restaurantId,
            AnalyticsV2TimeRange timeRange,
            Long baseDishId,
            Collection<Long> cartDishIds,
            List<UpsellSuggestionV2DTO> suggestions
    ) {
        List<Map<String, Object>> suggestionPayload = suggestions.stream()
                .limit(LOG_LIMIT)
                .map(suggestion -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("dishId", suggestion.dishId());
                    row.put("dishName", suggestion.dishName());
                    row.put("category", suggestion.category());
                    row.put("supportingDishCount", suggestion.supportingDishCount());
                    row.put("pairOrderCount", suggestion.pairOrderCount());
                    row.put("affinity", suggestion.affinity());
                    row.put("lift", suggestion.lift());
                    return row;
                })
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "analyticsv2_upsell");
        payload.put("computation", computation);
        payload.put("restaurantId", restaurantId);
        payload.put("dateFrom", timeRange.dateFrom());
        payload.put("dateTo", timeRange.dateTo());
        payload.put("baseDishId", baseDishId);
        payload.put("cartDishIds", cartDishIds == null ? List.of() : cartDishIds);
        payload.put("suggestions", suggestionPayload);
        info(payload);
    }

    public void logFallback(
            String computation,
            Long restaurantId,
            String assignedVariant,
            Long baseDishId,
            Collection<Long> cartDishIds,
            Exception exception
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "analyticsv2_fallback");
        payload.put("computation", computation);
        payload.put("restaurantId", restaurantId);
        payload.put("assignedVariant", assignedVariant);
        payload.put("fallbackVariant", "A");
        payload.put("baseDishId", baseDishId);
        payload.put("cartDishIds", cartDishIds == null ? List.of() : cartDishIds);
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
            log.warn("Failed to serialize analyticsv2 rollout log", ex);
        }
    }

    private void warn(Map<String, Object> payload, Exception exception) {
        try {
            log.warn(objectMapper.writeValueAsString(payload), exception);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize analyticsv2 fallback log", ex);
        }
    }
}
