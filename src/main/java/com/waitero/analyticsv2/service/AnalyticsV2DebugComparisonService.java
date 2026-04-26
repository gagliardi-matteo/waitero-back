package com.waitero.analyticsv2.service;

import com.waitero.analyticsv2.dto.AnalyticsV2DebugCompareDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2DebugDifferenceDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2DebugDishPositionDTO;
import com.waitero.analyticsv2.dto.MenuRankedDishV2DTO;
import com.waitero.analyticsv2.dto.UpsellSuggestionV2DTO;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.back.entity.Piatto;
import com.waitero.back.service.MenuIntelligenceService;
import com.waitero.back.service.UpsellService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AnalyticsV2DebugComparisonService {

    private final MenuIntelligenceService legacyMenuIntelligenceService;
    private final UpsellService legacyUpsellService;
    private final MenuIntelligenceV2Service menuIntelligenceV2Service;
    private final UpsellV2Service upsellV2Service;

    public AnalyticsV2DebugCompareDTO compare(Long restaurantId, Long dishId, AnalyticsV2TimeRange timeRange) {
        List<AnalyticsV2DebugDishPositionDTO> rankingV1 = toDebugRowsFromLegacyDishes(
                legacyMenuIntelligenceService.rankDishesByRevenue(restaurantId),
                10
        );
        List<AnalyticsV2DebugDishPositionDTO> rankingV2 = toDebugRowsFromV2Ranking(
                menuIntelligenceV2Service.getRankedMenu(restaurantId, 10, timeRange)
        );

        List<AnalyticsV2DebugDishPositionDTO> upsellV1 = List.of();
        List<AnalyticsV2DebugDishPositionDTO> upsellV2 = List.of();
        List<String> notes = new ArrayList<>();
        notes.add("V1 outputs reflect the current legacy services and may include event-driven bias or legacy smoothing.");
        notes.add("V2 outputs are computed only from paid/completed orders within the requested date window.");

        if (dishId != null) {
            upsellV1 = toDebugRowsFromLegacyDishes(legacyUpsellService.getUpsellSuggestions(dishId, restaurantId), 10);
            upsellV2 = toDebugRowsFromV2Upsell(upsellV2Service.getDishSuggestions(restaurantId, dishId, 10, timeRange));
        } else {
            notes.add("Upsell comparison was skipped because no dishId was provided.");
        }

        return new AnalyticsV2DebugCompareDTO(
                restaurantId,
                dishId,
                timeRange.dateFrom(),
                timeRange.dateTo(),
                rankingV1,
                rankingV2,
                buildDifferences(rankingV1, rankingV2),
                upsellV1,
                upsellV2,
                buildDifferences(upsellV1, upsellV2),
                notes
        );
    }

    private List<AnalyticsV2DebugDishPositionDTO> toDebugRowsFromLegacyDishes(List<Piatto> dishes, int limit) {
        return java.util.stream.IntStream.range(0, Math.min(dishes.size(), limit))
                .mapToObj(index -> {
                    Piatto dish = dishes.get(index);
                    return new AnalyticsV2DebugDishPositionDTO(
                            index + 1,
                            dish.getId(),
                            dish.getNome(),
                            dish.getCategoria() == null ? null : dish.getCategoria().name()
                    );
                })
                .toList();
    }

    private List<AnalyticsV2DebugDishPositionDTO> toDebugRowsFromV2Ranking(List<MenuRankedDishV2DTO> dishes) {
        return java.util.stream.IntStream.range(0, dishes.size())
                .mapToObj(index -> {
                    MenuRankedDishV2DTO dish = dishes.get(index);
                    return new AnalyticsV2DebugDishPositionDTO(index + 1, dish.dishId(), dish.dishName(), dish.category());
                })
                .toList();
    }

    private List<AnalyticsV2DebugDishPositionDTO> toDebugRowsFromV2Upsell(List<UpsellSuggestionV2DTO> suggestions) {
        return java.util.stream.IntStream.range(0, suggestions.size())
                .mapToObj(index -> {
                    UpsellSuggestionV2DTO suggestion = suggestions.get(index);
                    return new AnalyticsV2DebugDishPositionDTO(index + 1, suggestion.dishId(), suggestion.dishName(), suggestion.category());
                })
                .toList();
    }

    private List<AnalyticsV2DebugDifferenceDTO> buildDifferences(
            List<AnalyticsV2DebugDishPositionDTO> v1Rows,
            List<AnalyticsV2DebugDishPositionDTO> v2Rows
    ) {
        Map<Long, AnalyticsV2DebugDishPositionDTO> v1ByDishId = indexByDishId(v1Rows);
        Map<Long, AnalyticsV2DebugDishPositionDTO> v2ByDishId = indexByDishId(v2Rows);

        LinkedHashSet<Long> orderedDishIds = new LinkedHashSet<>();
        orderedDishIds.addAll(v1ByDishId.keySet());
        orderedDishIds.addAll(v2ByDishId.keySet());

        return orderedDishIds.stream()
                .map(dishId -> {
                    AnalyticsV2DebugDishPositionDTO v1Row = v1ByDishId.get(dishId);
                    AnalyticsV2DebugDishPositionDTO v2Row = v2ByDishId.get(dishId);
                    return new AnalyticsV2DebugDifferenceDTO(
                            dishId,
                            v2Row != null ? v2Row.dishName() : v1Row.dishName(),
                            v1Row != null ? v1Row.position() : null,
                            v2Row != null ? v2Row.position() : null,
                            differenceType(v1Row, v2Row)
                    );
                })
                .sorted(Comparator
                        .comparing((AnalyticsV2DebugDifferenceDTO row) -> rankOrder(row.v2Position(), row.v1Position()))
                        .thenComparing(AnalyticsV2DebugDifferenceDTO::dishId))
                .toList();
    }

    private Map<Long, AnalyticsV2DebugDishPositionDTO> indexByDishId(List<AnalyticsV2DebugDishPositionDTO> rows) {
        Map<Long, AnalyticsV2DebugDishPositionDTO> result = new LinkedHashMap<>();
        for (AnalyticsV2DebugDishPositionDTO row : rows) {
            result.putIfAbsent(row.dishId(), row);
        }
        return result;
    }

    private String differenceType(AnalyticsV2DebugDishPositionDTO v1Row, AnalyticsV2DebugDishPositionDTO v2Row) {
        if (v1Row == null) {
            return "ONLY_IN_V2";
        }
        if (v2Row == null) {
            return "ONLY_IN_V1";
        }
        if (v1Row.position() == v2Row.position()) {
            return "UNCHANGED";
        }
        return v2Row.position() < v1Row.position() ? "MOVED_UP_IN_V2" : "MOVED_DOWN_IN_V2";
    }

    private int rankOrder(Integer preferredPosition, Integer fallbackPosition) {
        if (preferredPosition != null) {
            return preferredPosition;
        }
        if (fallbackPosition != null) {
            return fallbackPosition + 100;
        }
        return Integer.MAX_VALUE;
    }
}