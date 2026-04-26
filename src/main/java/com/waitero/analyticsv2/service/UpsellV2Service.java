package com.waitero.analyticsv2.service;

import com.waitero.analyticsv2.dto.UpsellSuggestionV2DTO;
import com.waitero.analyticsv2.repository.CoOccurrenceV2Repository;
import com.waitero.analyticsv2.repository.DishCatalogV2Repository;
import com.waitero.analyticsv2.support.AnalyticsV2JsonLogger;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UpsellV2Service {

    private static final Set<String> MAIN_CATEGORIES = Set.of("ANTIPASTO", "PRIMO", "SECONDO");

    private final DishCatalogV2Repository dishCatalogRepository;
    private final CoOccurrenceV2Service coOccurrenceService;
    private final AnalyticsV2JsonLogger shadowLogger;

    public List<UpsellSuggestionV2DTO> getDishSuggestions(Long restaurantId, Long dishId, int limit, AnalyticsV2TimeRange timeRange) {
        if (restaurantId == null || dishId == null) {
            return List.of();
        }

        DishCatalogV2Repository.DishCatalogRow baseDish = dishCatalogRepository.findDish(restaurantId, dishId)
                .filter(row -> Boolean.TRUE.equals(row.available()))
                .orElse(null);
        if (baseDish == null) {
            shadowLogger.logUpsell("dish", restaurantId, timeRange, dishId, List.of(dishId), List.of());
            return List.of();
        }

        Set<String> cartCategories = Set.of(normalizeCategory(baseDish.category()));
        List<CoOccurrenceV2Repository.RelatedDishRow> related = coOccurrenceService.getTopRelatedRows(
                restaurantId,
                dishId,
                Math.max(limit * 4, 12),
                true,
                timeRange
        );
        List<UpsellSuggestionV2DTO> suggestions = toSuggestions(related, Set.of(dishId), cartCategories, limit);
        shadowLogger.logUpsell("dish", restaurantId, timeRange, dishId, List.of(dishId), suggestions);
        return suggestions;
    }

    public List<UpsellSuggestionV2DTO> getCartSuggestions(Long restaurantId, Collection<Long> dishIds, int limit, AnalyticsV2TimeRange timeRange) {
        if (restaurantId == null || dishIds == null || dishIds.isEmpty()) {
            return List.of();
        }

        Set<Long> normalizedDishIds = dishIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedDishIds.isEmpty()) {
            return List.of();
        }

        Map<Long, DishCatalogV2Repository.DishCatalogRow> cartDishMap = dishCatalogRepository.findDishMap(restaurantId, normalizedDishIds, true);
        if (cartDishMap.isEmpty()) {
            shadowLogger.logUpsell("cart", restaurantId, timeRange, null, normalizedDishIds, List.of());
            return List.of();
        }

        Set<String> cartCategories = cartDishMap.values().stream()
                .map(DishCatalogV2Repository.DishCatalogRow::category)
                .map(this::normalizeCategory)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, AggregatedCandidate> candidates = new LinkedHashMap<>();
        for (CoOccurrenceV2Repository.BaseRelatedDishRow row : coOccurrenceService.getTopRelatedRowsForBaseDishes(
                restaurantId,
                cartDishMap.keySet(),
                20,
                true,
                timeRange
        )) {
            if (normalizedDishIds.contains(row.dishId())) {
                continue;
            }

            AggregatedCandidate candidate = candidates.computeIfAbsent(row.dishId(), ignored -> new AggregatedCandidate(row));
            candidate.supportingDishCount += 1;
            candidate.pairOrderCount += row.pairOrderCount();
            candidate.affinity = candidate.affinity.max(row.affinity());
            candidate.lift = candidate.lift.max(row.lift());
        }

        List<AggregatedCandidate> sorted = new ArrayList<>(candidates.values());
        sorted.sort(Comparator
                .comparingInt((AggregatedCandidate candidate) -> categoryPriority(candidate.category, cartCategories)).reversed()
                .thenComparingInt(candidate -> candidate.supportingDishCount).reversed()
                .thenComparingLong(candidate -> candidate.pairOrderCount).reversed()
                .thenComparing(AggregatedCandidate::affinity).reversed()
                .thenComparing(AggregatedCandidate::lift).reversed()
                .thenComparing(AggregatedCandidate::dishId));

        List<UpsellSuggestionV2DTO> suggestions = sorted.stream()
                .limit(normalizeLimit(limit))
                .map(candidate -> new UpsellSuggestionV2DTO(
                        candidate.dishId(),
                        candidate.dishName,
                        candidate.description,
                        candidate.category,
                        candidate.price,
                        candidate.imageUrl,
                        candidate.supportingDishCount,
                        candidate.pairOrderCount,
                        candidate.affinity,
                        candidate.lift,
                        buildRationale(candidate.category, cartCategories)
                ))
                .toList();

        shadowLogger.logUpsell("cart", restaurantId, timeRange, null, normalizedDishIds, suggestions);
        return suggestions;
    }

    private List<UpsellSuggestionV2DTO> toSuggestions(
            List<CoOccurrenceV2Repository.RelatedDishRow> relatedRows,
            Set<Long> excludedDishIds,
            Set<String> cartCategories,
            int limit
    ) {
        return relatedRows.stream()
                .filter(row -> !excludedDishIds.contains(row.dishId()))
                .sorted(Comparator
                        .comparingInt((CoOccurrenceV2Repository.RelatedDishRow row) -> categoryPriority(row.category(), cartCategories)).reversed()
                        .thenComparing(CoOccurrenceV2Repository.RelatedDishRow::pairOrderCount).reversed()
                        .thenComparing(CoOccurrenceV2Repository.RelatedDishRow::affinity).reversed()
                        .thenComparing(CoOccurrenceV2Repository.RelatedDishRow::lift).reversed()
                        .thenComparing(CoOccurrenceV2Repository.RelatedDishRow::dishId))
                .limit(normalizeLimit(limit))
                .map(row -> new UpsellSuggestionV2DTO(
                        row.dishId(),
                        row.dishName(),
                        row.description(),
                        row.category(),
                        row.price(),
                        row.imageUrl(),
                        1,
                        row.pairOrderCount(),
                        row.affinity(),
                        row.lift(),
                        buildRationale(row.category(), cartCategories)
                ))
                .toList();
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 10));
    }

    private int categoryPriority(String candidateCategory, Set<String> cartCategories) {
        String normalized = normalizeCategory(candidateCategory);
        boolean hasMainDish = hasMainDish(cartCategories);
        if ("BEVANDA".equals(normalized) && !cartCategories.contains("BEVANDA")) {
            return 4;
        }
        if ("CONTORNO".equals(normalized) && hasMainDish && !cartCategories.contains("CONTORNO")) {
            return 3;
        }
        if ("DOLCE".equals(normalized) && hasMainDish && !cartCategories.contains("DOLCE")) {
            return 2;
        }
        return 1;
    }

    private String buildRationale(String candidateCategory, Set<String> cartCategories) {
        String normalized = normalizeCategory(candidateCategory);
        boolean hasMainDish = hasMainDish(cartCategories);
        if ("BEVANDA".equals(normalized) && !cartCategories.contains("BEVANDA")) {
            return "Frequently ordered with paid orders and useful to close the ticket with a beverage.";
        }
        if ("CONTORNO".equals(normalized) && hasMainDish && !cartCategories.contains("CONTORNO")) {
            return "Frequently paired with main dishes and adds a complementary side item.";
        }
        if ("DOLCE".equals(normalized) && hasMainDish && !cartCategories.contains("DOLCE")) {
            return "Frequently paired with main dishes and extends the order with a dessert.";
        }
        return "Frequently purchased together in paid orders.";
    }

    private boolean hasMainDish(Set<String> categories) {
        return categories.stream().anyMatch(MAIN_CATEGORIES::contains);
    }

    private String normalizeCategory(String category) {
        if (category == null) {
            return "";
        }
        return category.trim().toUpperCase(Locale.ROOT);
    }

    private static class AggregatedCandidate {
        private final Long dishId;
        private final String dishName;
        private final String description;
        private final String category;
        private final BigDecimal price;
        private final String imageUrl;
        private int supportingDishCount;
        private long pairOrderCount;
        private BigDecimal affinity = BigDecimal.ZERO.setScale(4);
        private BigDecimal lift = BigDecimal.ZERO.setScale(4);

        private AggregatedCandidate(CoOccurrenceV2Repository.BaseRelatedDishRow row) {
            this.dishId = row.dishId();
            this.dishName = row.dishName();
            this.description = row.description();
            this.category = row.category();
            this.price = row.price();
            this.imageUrl = row.imageUrl();
        }

        private Long dishId() {
            return dishId;
        }

        private BigDecimal affinity() {
            return affinity;
        }

        private BigDecimal lift() {
            return lift;
        }
    }
}