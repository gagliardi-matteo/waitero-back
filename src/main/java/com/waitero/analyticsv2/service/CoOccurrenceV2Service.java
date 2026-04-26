package com.waitero.analyticsv2.service;

import com.waitero.analyticsv2.dto.RelatedDishV2DTO;
import com.waitero.analyticsv2.repository.CoOccurrenceV2Repository;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CoOccurrenceV2Service {

    private final CoOccurrenceV2Repository coOccurrenceRepository;

    public List<RelatedDishV2DTO> getTopRelated(
            Long restaurantId,
            Long dishId,
            int limit,
            boolean onlyAvailable,
            AnalyticsV2TimeRange timeRange
    ) {
        if (restaurantId == null || dishId == null) {
            return List.of();
        }

        int normalizedLimit = Math.max(1, Math.min(limit, 20));
        return coOccurrenceRepository.fetchTopRelatedDishes(restaurantId, dishId, onlyAvailable, normalizedLimit, timeRange)
                .stream()
                .map(row -> new RelatedDishV2DTO(
                        row.dishId(),
                        row.dishName(),
                        row.description(),
                        row.category(),
                        row.price(),
                        row.available(),
                        row.imageUrl(),
                        row.pairOrderCount(),
                        row.relatedDishOrderCount(),
                        row.affinity(),
                        row.lift()
                ))
                .toList();
    }

    List<CoOccurrenceV2Repository.RelatedDishRow> getTopRelatedRows(
            Long restaurantId,
            Long dishId,
            int limit,
            boolean onlyAvailable,
            AnalyticsV2TimeRange timeRange
    ) {
        if (restaurantId == null || dishId == null) {
            return List.of();
        }
        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        return coOccurrenceRepository.fetchTopRelatedDishes(restaurantId, dishId, onlyAvailable, normalizedLimit, timeRange);
    }

    List<CoOccurrenceV2Repository.BaseRelatedDishRow> getTopRelatedRowsForBaseDishes(
            Long restaurantId,
            Collection<Long> dishIds,
            int limit,
            boolean onlyAvailable,
            AnalyticsV2TimeRange timeRange
    ) {
        if (restaurantId == null || dishIds == null || dishIds.isEmpty()) {
            return List.of();
        }

        List<Long> normalizedDishIds = dishIds.stream()
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        if (normalizedDishIds.isEmpty()) {
            return List.of();
        }

        int normalizedLimit = Math.max(1, Math.min(limit, 50));
        return coOccurrenceRepository.fetchTopRelatedDishesForBaseDishes(restaurantId, normalizedDishIds, onlyAvailable, normalizedLimit, timeRange);
    }
}