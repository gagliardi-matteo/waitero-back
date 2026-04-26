package com.waitero.back.service;

import com.waitero.analyticsv2.dto.UpsellSuggestionV2DTO;
import com.waitero.analyticsv2.service.UpsellV2Service;
import com.waitero.analyticsv2.support.AnalyticsV2JsonLogger;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.entity.Categoria;
import com.waitero.back.entity.DishCooccurrence;
import com.waitero.back.entity.DishCooccurrenceId;
import com.waitero.back.entity.Piatto;
import com.waitero.back.repository.DishCooccurrenceCountProjection;
import com.waitero.back.repository.DishCooccurrenceRepository;
import com.waitero.back.repository.DishOrderCountProjection;
import com.waitero.back.repository.OrdineItemRepository;
import com.waitero.back.repository.PiattoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UpsellService {

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final OrdineItemRepository ordineItemRepository;
    private final DishCooccurrenceRepository dishCooccurrenceRepository;
    private final PiattoRepository piattoRepository;
    private final MenuIntelligenceService menuIntelligenceService;
    private final AnalyticsService analyticsService;
    private final ExperimentService experimentService;
    private final AnalyticsV2JsonLogger analyticsV2JsonLogger;
    private final UpsellV2Service upsellV2Service;
    private final AnalyticsV2TimeRangeResolver analyticsV2TimeRangeResolver;
    private final DishIntelligenceService dishIntelligenceService;
    private final Map<Long, Instant> refreshByRestaurant = new ConcurrentHashMap<>();
    private final Map<Long, Object> locksByRestaurant = new ConcurrentHashMap<>();

    @Deprecated(since = "2026-04", forRemoval = false)
    @Transactional
    public List<Piatto> getUpsellSuggestions(Long dishId, Long restaurantId) {
        return getUpsellSuggestionsInternal(dishId, restaurantId);
    }

    @Transactional
    public List<Piatto> getUpsellSuggestions(Long dishId, Long restaurantId, String sessionId) {
        return getUpsellSuggestions(dishId, restaurantId, sessionId, null);
    }

    @Transactional
    public List<Piatto> getUpsellSuggestions(Long dishId, Long restaurantId, String sessionId, Integer tableId) {
        String variant = experimentService.getVariant(sessionId, restaurantId, tableId);
        if (ExperimentService.VARIANT_B.equals(variant)) {
            return getDishUpsellSuggestionsV2First(dishId, restaurantId, sessionId, tableId, variant);
        }
        if (ExperimentService.VARIANT_C.equals(variant)) {
            return getDishUpsellSuggestionsWithDishIntelligenceBoost(dishId, restaurantId);
        }
        return getUpsellSuggestionsInternal(dishId, restaurantId);
    }

    private List<Piatto> getDishUpsellSuggestionsV2First(Long dishId, Long restaurantId, String sessionId, Integer tableId, String assignedVariant) {
        try {
            return getV2DishUpsellSuggestions(dishId, restaurantId, 2);
        } catch (Exception ex) {
            experimentService.pinVariant(sessionId, restaurantId, tableId, ExperimentService.VARIANT_A);
            analyticsV2JsonLogger.logFallback("upsell_dish", restaurantId, assignedVariant, dishId, List.of(dishId), ex);
            return getUpsellSuggestionsInternal(dishId, restaurantId);
        }
    }

    private List<Piatto> getUpsellSuggestionsInternal(Long dishId, Long restaurantId) {
        return getUpsellSuggestionsInternal(dishId, restaurantId, 2);
    }

    private List<Piatto> getUpsellSuggestionsInternal(Long dishId, Long restaurantId, int limit) {
        if (dishId == null || restaurantId == null) {
            return List.of();
        }

        return piattoRepository.findByIdAndRistoratoreId(dishId, restaurantId)
                .filter(this::isAvailable)
                .map(dish -> rankSuggestions(List.of(dish), restaurantId, limit))
                .orElseGet(List::of);
    }

    @Deprecated(since = "2026-04", forRemoval = false)
    @Transactional
    public List<Piatto> getCartUpsellSuggestions(List<Long> dishIds, Long restaurantId) {
        return getCartUpsellSuggestionsInternal(dishIds, restaurantId);
    }

    @Transactional
    public List<Piatto> getCartUpsellSuggestions(List<Long> dishIds, Long restaurantId, String sessionId) {
        return getCartUpsellSuggestions(dishIds, restaurantId, sessionId, null);
    }

    @Transactional
    public List<Piatto> getCartUpsellSuggestions(List<Long> dishIds, Long restaurantId, String sessionId, Integer tableId) {
        String variant = experimentService.getVariant(sessionId, restaurantId, tableId);
        if (ExperimentService.VARIANT_B.equals(variant)) {
            return getCartUpsellSuggestionsV2First(dishIds, restaurantId, sessionId, tableId, variant);
        }
        if (ExperimentService.VARIANT_C.equals(variant)) {
            return getCartUpsellSuggestionsWithDishIntelligenceBoost(dishIds, restaurantId);
        }
        return getCartUpsellSuggestionsInternal(dishIds, restaurantId);
    }

    private List<Piatto> getCartUpsellSuggestionsV2First(List<Long> dishIds, Long restaurantId, String sessionId, Integer tableId, String assignedVariant) {
        try {
            return getV2CartUpsellSuggestions(dishIds, restaurantId, 2);
        } catch (Exception ex) {
            List<Long> normalizedDishIds = dishIds == null ? List.of() : dishIds.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            experimentService.pinVariant(sessionId, restaurantId, tableId, ExperimentService.VARIANT_A);
            analyticsV2JsonLogger.logFallback("upsell_cart", restaurantId, assignedVariant, null, normalizedDishIds, ex);
            return getCartUpsellSuggestionsInternal(dishIds, restaurantId);
        }
    }

    private List<Piatto> getCartUpsellSuggestionsInternal(List<Long> dishIds, Long restaurantId) {
        return getCartUpsellSuggestionsInternal(dishIds, restaurantId, 2);
    }

    private List<Piatto> getCartUpsellSuggestionsInternal(List<Long> dishIds, Long restaurantId, int limit) {
        if (restaurantId == null || dishIds == null || dishIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Piatto> availableDishById = loadAvailableDishMap(restaurantId);
        List<Piatto> cartDishes = dishIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(availableDishById::get)
                .filter(Objects::nonNull)
                .toList();

        if (cartDishes.isEmpty()) {
            return List.of();
        }

        return rankSuggestions(cartDishes, restaurantId, limit);
    }

    private List<Piatto> getV2DishUpsellSuggestions(Long dishId, Long restaurantId, int limit) {
        if (dishId == null || restaurantId == null) {
            return List.of();
        }

        Map<Long, Piatto> dishById = piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId)
                .stream()
                .collect(Collectors.toMap(Piatto::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        AnalyticsV2TimeRange timeRange = analyticsV2TimeRangeResolver.resolve(null, null);

        return upsellV2Service.getDishSuggestions(restaurantId, dishId, limit, timeRange).stream()
                .map(UpsellSuggestionV2DTO::dishId)
                .map(dishById::get)
                .filter(Objects::nonNull)
                .filter(this::isAvailable)
                .toList();
    }

    private List<Piatto> getV2CartUpsellSuggestions(List<Long> dishIds, Long restaurantId, int limit) {
        if (restaurantId == null || dishIds == null || dishIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Piatto> dishById = piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId)
                .stream()
                .collect(Collectors.toMap(Piatto::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        AnalyticsV2TimeRange timeRange = analyticsV2TimeRangeResolver.resolve(null, null);

        return upsellV2Service.getCartSuggestions(restaurantId, dishIds, limit, timeRange).stream()
                .map(UpsellSuggestionV2DTO::dishId)
                .map(dishById::get)
                .filter(Objects::nonNull)
                .filter(this::isAvailable)
                .toList();
    }

    private List<Piatto> getDishUpsellSuggestionsWithDishIntelligenceBoost(Long dishId, Long restaurantId) {
        try {
            return rerankWithDishIntelligenceBoost(
                    getUpsellSuggestionsInternal(dishId, restaurantId, 6),
                    restaurantId,
                    2
            );
        } catch (Exception ex) {
            return getUpsellSuggestionsInternal(dishId, restaurantId);
        }
    }

    private List<Piatto> getCartUpsellSuggestionsWithDishIntelligenceBoost(List<Long> dishIds, Long restaurantId) {
        try {
            return rerankWithDishIntelligenceBoost(
                    getCartUpsellSuggestionsInternal(dishIds, restaurantId, 6),
                    restaurantId,
                    2
            );
        } catch (Exception ex) {
            return getCartUpsellSuggestionsInternal(dishIds, restaurantId);
        }
    }

    private List<Piatto> rerankWithDishIntelligenceBoost(List<Piatto> suggestions, Long restaurantId, int limit) {
        if (restaurantId == null || suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }

        Map<Long, Double> affinityScoreByDishId = dishIntelligenceService.getDishIntelligence(restaurantId).stream()
                .filter(dto -> dto.dishId() != null)
                .collect(Collectors.toMap(
                        dto -> dto.dishId(),
                        dto -> dto.affinityScore() == null ? 0.0d : dto.affinityScore().doubleValue(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        Map<Long, Integer> baseRankByDishId = new LinkedHashMap<>();
        for (int index = 0; index < suggestions.size(); index++) {
            Piatto suggestion = suggestions.get(index);
            if (suggestion != null && suggestion.getId() != null) {
                baseRankByDishId.putIfAbsent(suggestion.getId(), index);
            }
        }

        return suggestions.stream()
                .filter(this::isAvailable)
                .filter(dish -> dish.getId() != null)
                .collect(Collectors.toMap(Piatto::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new))
                .values()
                .stream()
                .sorted(Comparator
                        .comparingDouble((Piatto dish) -> boostedDishIntelligenceScore(dish, baseRankByDishId, affinityScoreByDishId))
                        .reversed()
                        .thenComparing(dish -> baseRankByDishId.getOrDefault(dish.getId(), Integer.MAX_VALUE))
                        .thenComparing(Piatto::getId))
                .limit(limit)
                .toList();
    }

    private double boostedDishIntelligenceScore(
            Piatto dish,
            Map<Long, Integer> baseRankByDishId,
            Map<Long, Double> affinityScoreByDishId
    ) {
        int baseRank = baseRankByDishId.getOrDefault(dish.getId(), Integer.MAX_VALUE);
        double legacyWeight = baseRank == Integer.MAX_VALUE ? 0.0d : Math.max(0.0d, 1.0d - (baseRank * 0.08d));
        double affinityBoost = affinityScoreByDishId.getOrDefault(dish.getId(), 0.0d) * 0.18d;
        return legacyWeight + affinityBoost;
    }

    private List<Piatto> rankSuggestions(List<Piatto> baseDishes, Long restaurantId, int limit) {
        if (baseDishes == null || baseDishes.isEmpty()) {
            return List.of();
        }

        Map<Long, Piatto> availableDishById = loadAvailableDishMap(restaurantId);
        Map<Long, AnalyticsService.DishFeatures> featuresByDishId = analyticsService.getDishFeatures(restaurantId)
                .stream()
                .collect(Collectors.toMap(features -> features.dishId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        double avgPrice = availableDishById.values().stream()
                .map(Piatto::getPrezzo)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0d);
        Set<Long> excludedDishIds = baseDishes.stream()
                .map(Piatto::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Categoria> cartCategories = baseDishes.stream()
                .map(Piatto::getCategoria)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean hasMainDish = hasMainDish(cartCategories);

        Map<Long, CandidateScore> candidateByDishId = new LinkedHashMap<>();

        for (Piatto baseDish : baseDishes) {
            for (DishCooccurrence cooccurrence : dishCooccurrenceRepository.findAvailableSuggestions(baseDish.getId(), restaurantId)) {
                Piatto suggestedDish = availableDishById.get(cooccurrence.getSuggestedDish().getId());
                if (suggestedDish == null) {
                    continue;
                }

                CandidateScore candidate = candidateByDishId.computeIfAbsent(suggestedDish.getId(), id -> new CandidateScore(suggestedDish));
                candidate.addCooccurrence(scoreCooccurrence(cooccurrence, suggestedDish, featuresByDishId.get(suggestedDish.getId()), cartCategories, hasMainDish)
                        + alreadyInCartPenalty(suggestedDish, excludedDishIds),
                        cooccurrence.getConfidence() != null ? cooccurrence.getConfidence() : 0.0d);
            }
        }

        for (Piatto candidateDish : availableDishById.values()) {
            CandidateScore candidate = candidateByDishId.computeIfAbsent(candidateDish.getId(), id -> new CandidateScore(candidateDish));
            candidate.addFallback(scoreFallback(candidateDish, featuresByDishId.get(candidateDish.getId()), cartCategories, hasMainDish, avgPrice)
                    + alreadyInCartPenalty(candidateDish, excludedDishIds));
        }

        List<Piatto> ranked = candidateByDishId.values().stream()
                .sorted(Comparator
                        .comparingDouble(CandidateScore::totalScore).reversed()
                        .thenComparing(Comparator.comparingInt(CandidateScore::pairHits).reversed())
                        .thenComparing(Comparator.comparingDouble(CandidateScore::bestConfidence).reversed())
                        .thenComparing(candidate -> safePrice(candidate.dish().getPrezzo()))
                        .thenComparing(candidate -> candidate.dish().getId()))
                .map(CandidateScore::dish)
                .toList();

        return enforceUpsellCategoryMix(ranked, availableDishById, excludedDishIds).stream()
                .limit(limit)
                .toList();
    }
    private List<Piatto> getSimpleUpsellSuggestions(List<Long> dishIds, Long restaurantId, int limit) {
        if (dishIds == null || dishIds.isEmpty() || restaurantId == null) {
            return List.of();
        }

        Set<Long> baseDishIds = dishIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (baseDishIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Piatto> suggestionsById = new LinkedHashMap<>();
        List<DishCooccurrence> cooccurrences = new ArrayList<>();
        for (Long baseDishId : baseDishIds) {
            cooccurrences.addAll(dishCooccurrenceRepository.findAvailableSuggestions(baseDishId, restaurantId));
        }

        cooccurrences.stream()
                .sorted(Comparator
                        .comparing((DishCooccurrence cooccurrence) -> cooccurrence.getConfidence() == null ? 0.0d : cooccurrence.getConfidence()).reversed()
                        .thenComparing(Comparator.comparing((DishCooccurrence cooccurrence) -> cooccurrence.getCount() == null ? 0L : cooccurrence.getCount()).reversed())
                        .thenComparing(cooccurrence -> cooccurrence.getSuggestedDish().getId()))
                .map(DishCooccurrence::getSuggestedDish)
                .filter(this::isAvailable)
                .filter(dish -> !baseDishIds.contains(dish.getId()))
                .forEach(dish -> suggestionsById.putIfAbsent(dish.getId(), dish));

        return suggestionsById.values().stream()
                .limit(limit)
                .toList();
    }
    private double scoreCooccurrence(DishCooccurrence cooccurrence,
                                     Piatto suggestedDish,
                                     AnalyticsService.DishFeatures features,
                                     Set<Categoria> cartCategories,
                                     boolean hasMainDish) {
        double confidence = cooccurrence.getConfidence() != null ? cooccurrence.getConfidence() : 0.0d;
        double baseScore = (0.5d * confidence)
                + (0.3d * featureValue(features, "rpi"))
                + (0.2d * featureValue(features, "orderRate"));
        double conversionEstimate = (0.6d * confidence) + (0.4d * featureValue(features, "orderRate"));
        double expectedLift = safeNumericPrice(suggestedDish.getPrezzo()) * conversionEstimate;
        return expectedLift + categoryBoost(suggestedDish.getCategoria()) + categoryGapScore(suggestedDish.getCategoria(), cartCategories, hasMainDish);
    }

    private double scoreFallback(Piatto candidateDish,
                                 AnalyticsService.DishFeatures features,
                                 Set<Categoria> cartCategories,
                                 boolean hasMainDish,
                                 double avgPrice) {
        double baseScore = (0.3d * featureValue(features, "rpi"))
                + (0.2d * featureValue(features, "orderRate"));
        double conversionEstimate = 0.4d * featureValue(features, "orderRate");
        double price = safeNumericPrice(candidateDish.getPrezzo());
        double expectedLift = price * conversionEstimate;
        return expectedLift
                + categoryGapScore(candidateDish.getCategoria(), cartCategories, hasMainDish)
                + lowPriceBoost(price, avgPrice);
    }




    private double alreadyInCartPenalty(Piatto dish, Set<Long> excludedDishIds) {
        if (dish == null || dish.getId() == null) {
            return 0.0d;
        }
        return excludedDishIds.contains(dish.getId()) ? -0.5d : 0.0d;
    }
    private List<Piatto> enforceUpsellCategoryMix(List<Piatto> ranked, Map<Long, Piatto> availableDishById, Set<Long> excludedDishIds) {
        List<Piatto> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Piatto dish : ranked) {
            if (dish == null || dish.getId() == null || !seen.add(dish.getId())) {
                continue;
            }
            result.add(dish);
        }

        ensureCategory(result, availableDishById, excludedDishIds, Categoria.BEVANDA);
        if (result.stream().noneMatch(this::isNonMainDish)) {
            availableDishById.values().stream()
                    .filter(this::isAvailable)
                    .filter(this::isNonMainDish)
                    .filter(dish -> result.stream().noneMatch(existing -> existing.getId().equals(dish.getId())))
                    .findFirst()
                    .ifPresent(dish -> result.add(Math.min(1, result.size()), dish));
        }
        return result;
    }

    private void ensureCategory(List<Piatto> result, Map<Long, Piatto> availableDishById, Set<Long> excludedDishIds, Categoria category) {
        if (result.stream().anyMatch(dish -> dish.getCategoria() == category)) {
            return;
        }
        availableDishById.values().stream()
                .filter(this::isAvailable)
                .filter(dish -> dish.getCategoria() == category)
                .filter(dish -> result.stream().noneMatch(existing -> existing.getId().equals(dish.getId())))
                .findFirst()
                .ifPresent(dish -> result.add(0, dish));
    }

    private boolean isNonMainDish(Piatto dish) {
        return dish != null && (dish.getCategoria() == Categoria.CONTORNO || dish.getCategoria() == Categoria.DOLCE);
    }
    private double featureValue(AnalyticsService.DishFeatures features, String field) {
        if (features == null) {
            return 0.0d;
        }
        return switch (field) {
            case "rpi" -> features.rpi;
            case "orderRate" -> features.orderRate;
            default -> 0.0d;
        };
    }

    private double categoryBoost(Categoria candidateCategory) {
        if (candidateCategory == Categoria.BEVANDA) {
            return 0.25d;
        }
        if (candidateCategory == Categoria.CONTORNO) {
            return 0.20d;
        }
        if (candidateCategory == Categoria.DOLCE) {
            return 0.15d;
        }
        return 0.0d;
    }

    private double lowPriceBoost(double price, double avgPrice) {
        if (avgPrice <= 0.0d || price <= 0.0d) {
            return 0.0d;
        }
        return price < avgPrice * 0.5d ? 0.1d : 0.0d;
    }

    private double safeNumericPrice(BigDecimal price) {
        if (price == null) {
            return 0.0d;
        }
        double value = price.doubleValue();
        return Double.isFinite(value) ? value : 0.0d;
    }
    private double performanceScore(MenuIntelligenceService.DishSignal signal) {
        if (signal == null) {
            return 0.0d;
        }

        double orderRate = signal.viewToOrderRate() != null ? signal.viewToOrderRate().doubleValue() : 0.0d;
        double cartRate = signal.viewToCartRate() != null ? signal.viewToCartRate().doubleValue() : 0.0d;
        double orderVolume = Math.min(signal.orderCount() / 12.0d, 1.0d);
        return (0.42d * orderRate)
                + (0.24d * cartRate)
                + (0.20d * orderVolume)
                + performanceLabelBoost(signal.performanceLabel());
    }

    private double performanceLabelBoost(String performanceLabel) {
        if ("top_performer".equals(performanceLabel)) {
            return 0.18d;
        }
        if ("cart_abandonment".equals(performanceLabel)) {
            return 0.04d;
        }
        if ("high_interest_low_conversion".equals(performanceLabel)) {
            return -0.08d;
        }
        return 0.0d;
    }

    private double categoryGapScore(Categoria candidateCategory, Set<Categoria> cartCategories, boolean hasMainDish) {
        if (candidateCategory == null) {
            return 0.0d;
        }
        if (candidateCategory == Categoria.BEVANDA && !cartCategories.contains(Categoria.BEVANDA)) {
            return 0.24d;
        }
        if (candidateCategory == Categoria.CONTORNO && hasMainDish && !cartCategories.contains(Categoria.CONTORNO)) {
            return 0.20d;
        }
        if (candidateCategory == Categoria.DOLCE && hasMainDish && !cartCategories.contains(Categoria.DOLCE)) {
            return 0.14d;
        }
        return 0.0d;
    }

    private boolean hasMainDish(Set<Categoria> categories) {
        return categories.contains(Categoria.PRIMO)
                || categories.contains(Categoria.SECONDO)
                || categories.contains(Categoria.ANTIPASTO);
    }

    private double recommendationBoost(Piatto dish) {
        return Boolean.TRUE.equals(dish.getConsigliato()) ? 0.06d : 0.0d;
    }

    private double affordabilityBoost(BigDecimal price) {
        double numericPrice = safePrice(price);
        if (Double.isInfinite(numericPrice) || numericPrice <= 0.0d) {
            return 0.0d;
        }
        return Math.min(6.0d / numericPrice, 0.08d);
    }

    private Map<Long, Piatto> loadAvailableDishMap(Long restaurantId) {
        return piattoRepository.findAllByRistoratoreId(restaurantId)
                .stream()
                .filter(this::isAvailable)
                .collect(Collectors.toMap(Piatto::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private boolean isAvailable(Piatto dish) {
        return dish != null && Boolean.TRUE.equals(dish.getDisponibile());
    }

    private void ensureAggregatesUpToDate(Long restaurantId) {
        Instant lastRefresh = refreshByRestaurant.get(restaurantId);
        Instant now = Instant.now();
        if (lastRefresh != null && now.isBefore(lastRefresh.plus(CACHE_TTL))) {
            return;
        }

        Object lock = locksByRestaurant.computeIfAbsent(restaurantId, key -> new Object());
        synchronized (lock) {
            Instant secondCheck = refreshByRestaurant.get(restaurantId);
            Instant secondNow = Instant.now();
            if (secondCheck != null && secondNow.isBefore(secondCheck.plus(CACHE_TTL))) {
                return;
            }
            refreshAggregates(restaurantId);
            refreshByRestaurant.put(restaurantId, secondNow);
        }
    }

    @Transactional
    protected void refreshAggregates(Long restaurantId) {
        List<Long> restaurantDishIds = piattoRepository.findIdsByRistoratoreId(restaurantId);
        if (restaurantDishIds.isEmpty()) {
            return;
        }

        dishCooccurrenceRepository.deleteByBaseDishIds(restaurantDishIds);

        Map<Long, Long> orderCountByDish = ordineItemRepository.countDishOccurrencesByRestaurant(restaurantId)
                .stream()
                .collect(Collectors.toMap(DishOrderCountProjection::getDishId, DishOrderCountProjection::getOrderCount));

        if (orderCountByDish.isEmpty()) {
            return;
        }

        Map<Long, Piatto> dishById = piattoRepository.findAllById(restaurantDishIds)
                .stream()
                .collect(Collectors.toMap(Piatto::getId, dish -> dish));

        List<DishCooccurrence> rows = new ArrayList<>();
        for (DishCooccurrenceCountProjection pair : ordineItemRepository.countDishPairsByRestaurant(restaurantId)) {
            Long baseDishId = pair.getBaseDishId();
            Long suggestedDishId = pair.getSuggestedDishId();
            Long baseOrderCount = orderCountByDish.get(baseDishId);
            Piatto baseDish = dishById.get(baseDishId);
            Piatto suggestedDish = dishById.get(suggestedDishId);
            if (baseOrderCount == null || baseOrderCount <= 0 || baseDish == null || suggestedDish == null) {
                continue;
            }

            rows.add(DishCooccurrence.builder()
                    .id(DishCooccurrenceId.builder()
                            .baseDishId(baseDishId)
                            .suggestedDishId(suggestedDishId)
                            .build())
                    .baseDish(baseDish)
                    .suggestedDish(suggestedDish)
                    .count(pair.getPairCount())
                    .confidence(pair.getPairCount().doubleValue() / baseOrderCount.doubleValue())
                    .build());
        }

        if (!rows.isEmpty()) {
            dishCooccurrenceRepository.saveAll(rows);
        }
    }

    @Transactional
    @Async
    public void refreshAggregatesForRestaurant(Long restaurantId) {
        if (restaurantId == null) {
            return;
        }

        refreshAggregates(restaurantId);
        refreshByRestaurant.put(restaurantId, Instant.now());
    }

    private double safePrice(BigDecimal price) {
        return price != null ? price.doubleValue() : Double.MAX_VALUE;
    }

    private static class CandidateScore {
        private final Piatto dish;
        private double cooccurrenceScore;
        private double fallbackScore;
        private double bestConfidence;
        private int pairHits;

        private CandidateScore(Piatto dish) {
            this.dish = dish;
        }

        private Piatto dish() {
            return dish;
        }

        private void addCooccurrence(double score, double confidence) {
            double adjustedScore = this.pairHits > 0 ? score * 0.7d : score;
            this.cooccurrenceScore += adjustedScore;
            this.pairHits += 1;
            this.bestConfidence = Math.max(this.bestConfidence, confidence);
        }

        private void addFallback(double score) {
            this.fallbackScore = Math.max(this.fallbackScore, score);
        }

        private double totalScore() {
            return cooccurrenceScore + fallbackScore;
        }

        private int pairHits() {
            return pairHits;
        }

        private double bestConfidence() {
            return bestConfidence;
        }
    }
}







