package com.waitero.back.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.waitero.analyticsv2.dto.MenuRankedDishV2DTO;
import com.waitero.analyticsv2.dto.UpsellSuggestionV2DTO;
import com.waitero.analyticsv2.service.AnalyticsV2Service;
import com.waitero.analyticsv2.service.MenuIntelligenceV2Service;
import com.waitero.analyticsv2.service.UpsellV2Service;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.entity.Piatto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Deprecated(since = "2026-04", forRemoval = true)
public class AnalyticsV2ShadowModeService {

    private static final Logger log = LoggerFactory.getLogger("analyticsv2.shadow.compare");

    private final AnalyticsV2Service analyticsV2Service;
    private final MenuIntelligenceV2Service menuIntelligenceV2Service;
    private final UpsellV2Service upsellV2Service;
    private final AnalyticsV2TimeRangeResolver timeRangeResolver;
    private final ObjectWriter compactJsonWriter;

    public AnalyticsV2ShadowModeService(
            AnalyticsV2Service analyticsV2Service,
            MenuIntelligenceV2Service menuIntelligenceV2Service,
            UpsellV2Service upsellV2Service,
            AnalyticsV2TimeRangeResolver timeRangeResolver,
            ObjectMapper objectMapper
    ) {
        this.analyticsV2Service = analyticsV2Service;
        this.menuIntelligenceV2Service = menuIntelligenceV2Service;
        this.upsellV2Service = upsellV2Service;
        this.timeRangeResolver = timeRangeResolver;
        this.compactJsonWriter = objectMapper.copy()
                .disable(SerializationFeature.INDENT_OUTPUT)
                .writer();
    }

    @Async
    public void shadowAnalyticsDashboard(Long restaurantId) {
        runSafely("analytics_dashboard", restaurantId, () -> analyticsV2Service.getDashboard(restaurantId, defaultTimeRange()));
    }

    @Async
    public void shadowAnalyticsOverview(Long restaurantId) {
        runSafely("analytics_overview", restaurantId, () -> analyticsV2Service.getOverview(restaurantId, defaultTimeRange()));
    }

    @Async
    public void shadowAnalyticsDishMetrics(Long restaurantId) {
        runSafely("analytics_dishes", restaurantId, () -> analyticsV2Service.getDishMetrics(restaurantId, defaultTimeRange()));
    }

    @Async
    public void shadowRanking(Long restaurantId, List<Piatto> v1Ranking) {
        runSafely("ranking", restaurantId, () -> {
            AnalyticsV2TimeRange timeRange = defaultTimeRange();
            List<ShadowDishSnapshot> v1 = toSnapshotsFromPiatto(v1Ranking, 10);
            List<ShadowDishSnapshot> v2 = toSnapshotsFromV2Ranking(menuIntelligenceV2Service.getRankedMenu(restaurantId, 10, timeRange));
            logComparison(restaurantId, "ranking", v1, v2, Map.of(
                    "dateFrom", timeRange.dateFrom(),
                    "dateTo", timeRange.dateTo()
            ));
        });
    }

    @Async
    public void shadowDishUpsell(Long restaurantId, Long dishId, List<Piatto> v1Upsell) {
        runSafely("upsell_dish", restaurantId, () -> {
            AnalyticsV2TimeRange timeRange = defaultTimeRange();
            int limit = Math.max(2, v1Upsell == null ? 0 : v1Upsell.size());
            List<ShadowDishSnapshot> v1 = toSnapshotsFromPiatto(v1Upsell, limit);
            List<ShadowDishSnapshot> v2 = toSnapshotsFromV2Upsell(upsellV2Service.getDishSuggestions(restaurantId, dishId, limit, timeRange));
            logComparison(restaurantId, "upsell", v1, v2, Map.of(
                    "mode", "dish",
                    "dishId", dishId,
                    "dateFrom", timeRange.dateFrom(),
                    "dateTo", timeRange.dateTo()
            ));
        });
    }

    @Async
    public void shadowCartUpsell(Long restaurantId, Collection<Long> dishIds, List<Piatto> v1Upsell) {
        runSafely("upsell_cart", restaurantId, () -> {
            AnalyticsV2TimeRange timeRange = defaultTimeRange();
            List<Long> normalizedDishIds = dishIds == null ? List.of() : dishIds.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            int limit = Math.max(2, v1Upsell == null ? 0 : v1Upsell.size());
            List<ShadowDishSnapshot> v1 = toSnapshotsFromPiatto(v1Upsell, limit);
            List<ShadowDishSnapshot> v2 = toSnapshotsFromV2Upsell(upsellV2Service.getCartSuggestions(restaurantId, normalizedDishIds, limit, timeRange));
            logComparison(restaurantId, "upsell", v1, v2, Map.of(
                    "mode", "cart",
                    "dishIds", normalizedDishIds,
                    "dateFrom", timeRange.dateFrom(),
                    "dateTo", timeRange.dateTo()
            ));
        });
    }

    private AnalyticsV2TimeRange defaultTimeRange() {
        return timeRangeResolver.resolve(null, null);
    }

    private void logComparison(
            Long restaurantId,
            String type,
            List<ShadowDishSnapshot> v1,
            List<ShadowDishSnapshot> v2,
            Map<String, Object> extraFields
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("restaurantId", restaurantId);
        payload.put("type", type);
        if (extraFields != null && !extraFields.isEmpty()) {
            payload.putAll(extraFields);
        }
        payload.put("v1", v1);
        payload.put("v2", v2);
        payload.put("differences", buildDifferences(v1, v2));

        try {
            log.info(compactJsonWriter.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize analyticsv2 shadow comparison for restaurant {} type {}", restaurantId, type, ex);
        }
    }

    private List<ShadowDifference> buildDifferences(List<ShadowDishSnapshot> v1Rows, List<ShadowDishSnapshot> v2Rows) {
        Map<Long, ShadowDishSnapshot> v1ByDishId = indexByDishId(v1Rows);
        Map<Long, ShadowDishSnapshot> v2ByDishId = indexByDishId(v2Rows);

        LinkedHashSet<Long> orderedDishIds = new LinkedHashSet<>();
        orderedDishIds.addAll(v1ByDishId.keySet());
        orderedDishIds.addAll(v2ByDishId.keySet());

        return orderedDishIds.stream()
                .map(dishId -> {
                    ShadowDishSnapshot v1Row = v1ByDishId.get(dishId);
                    ShadowDishSnapshot v2Row = v2ByDishId.get(dishId);
                    return new ShadowDifference(
                            dishId,
                            v2Row != null ? v2Row.dishName() : v1Row.dishName(),
                            v1Row != null ? v1Row.position() : null,
                            v2Row != null ? v2Row.position() : null,
                            differenceType(v1Row, v2Row)
                    );
                })
                .sorted(Comparator
                        .comparing((ShadowDifference row) -> sortOrder(row.v2Position(), row.v1Position()))
                        .thenComparing(ShadowDifference::dishId))
                .toList();
    }

    private Map<Long, ShadowDishSnapshot> indexByDishId(List<ShadowDishSnapshot> rows) {
        Map<Long, ShadowDishSnapshot> result = new LinkedHashMap<>();
        for (ShadowDishSnapshot row : rows) {
            result.putIfAbsent(row.dishId(), row);
        }
        return result;
    }

    private String differenceType(ShadowDishSnapshot v1Row, ShadowDishSnapshot v2Row) {
        if (v1Row == null) {
            return "ONLY_IN_V2";
        }
        if (v2Row == null) {
            return "ONLY_IN_V1";
        }
        if (Objects.equals(v1Row.position(), v2Row.position())) {
            return "UNCHANGED";
        }
        return v2Row.position() < v1Row.position() ? "MOVED_UP_IN_V2" : "MOVED_DOWN_IN_V2";
    }

    private int sortOrder(Integer preferredPosition, Integer fallbackPosition) {
        if (preferredPosition != null) {
            return preferredPosition;
        }
        if (fallbackPosition != null) {
            return fallbackPosition + 100;
        }
        return Integer.MAX_VALUE;
    }

    private List<ShadowDishSnapshot> toSnapshotsFromPiatto(List<Piatto> dishes, int limit) {
        if (dishes == null || dishes.isEmpty()) {
            return List.of();
        }
        List<ShadowDishSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < dishes.size() && i < limit; i++) {
            Piatto dish = dishes.get(i);
            if (dish == null || dish.getId() == null) {
                continue;
            }
            snapshots.add(new ShadowDishSnapshot(
                    i + 1,
                    dish.getId(),
                    dish.getNome(),
                    dish.getCategoriaLabel()
            ));
        }
        return snapshots;
    }

    private List<ShadowDishSnapshot> toSnapshotsFromV2Ranking(List<MenuRankedDishV2DTO> ranking) {
        if (ranking == null || ranking.isEmpty()) {
            return List.of();
        }
        List<ShadowDishSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < ranking.size(); i++) {
            MenuRankedDishV2DTO dish = ranking.get(i);
            snapshots.add(new ShadowDishSnapshot(i + 1, dish.dishId(), dish.dishName(), dish.category()));
        }
        return snapshots;
    }

    private List<ShadowDishSnapshot> toSnapshotsFromV2Upsell(List<UpsellSuggestionV2DTO> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }
        List<ShadowDishSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < suggestions.size(); i++) {
            UpsellSuggestionV2DTO suggestion = suggestions.get(i);
            snapshots.add(new ShadowDishSnapshot(i + 1, suggestion.dishId(), suggestion.dishName(), suggestion.category()));
        }
        return snapshots;
    }

    private void runSafely(String operation, Long restaurantId, Runnable runnable) {
        if (restaurantId == null) {
            return;
        }
        try {
            runnable.run();
        } catch (Exception ex) {
            log.warn("AnalyticsV2 shadow mode failed for operation {} restaurant {}", operation, restaurantId, ex);
        }
    }

    private record ShadowDishSnapshot(
            Integer position,
            Long dishId,
            String dishName,
            String category
    ) {
    }

    private record ShadowDifference(
            Long dishId,
            String dishName,
            Integer v1Position,
            Integer v2Position,
            String differenceType
    ) {
    }
}
