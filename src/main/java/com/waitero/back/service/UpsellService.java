package com.waitero.back.service;

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

    private final Map<Long, Instant> refreshByRestaurant = new ConcurrentHashMap<>();
    private final Map<Long, Object> locksByRestaurant = new ConcurrentHashMap<>();

    @Transactional
    public List<Piatto> getUpsellSuggestions(Long dishId, Long restaurantId) {
        if (dishId == null || restaurantId == null) {
            return List.of();
        }

        return piattoRepository.findByIdAndRistoratoreId(dishId, restaurantId)
                .filter(this::isAvailable)
                .map(dish -> rankSuggestions(List.of(dish), restaurantId, 2))
                .orElseGet(List::of);
    }

    @Transactional
    public List<Piatto> getCartUpsellSuggestions(List<Long> dishIds, Long restaurantId) {
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

        return rankSuggestions(cartDishes, restaurantId, 2);
    }

    private List<Piatto> rankSuggestions(List<Piatto> baseDishes, Long restaurantId, int limit) {
        if (baseDishes == null || baseDishes.isEmpty()) {
            return List.of();
        }

        ensureAggregatesUpToDate(restaurantId);

        Map<Long, Piatto> availableDishById = loadAvailableDishMap(restaurantId);
        Map<Long, MenuIntelligenceService.DishSignal> signals = menuIntelligenceService.getDishSignals(restaurantId);
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
                if (suggestedDish == null || excludedDishIds.contains(suggestedDish.getId())) {
                    continue;
                }

                CandidateScore candidate = candidateByDishId.computeIfAbsent(suggestedDish.getId(), id -> new CandidateScore(suggestedDish));
                candidate.addCooccurrence(scoreCooccurrence(cooccurrence, suggestedDish, signals.get(suggestedDish.getId()), cartCategories, hasMainDish),
                        cooccurrence.getConfidence() != null ? cooccurrence.getConfidence() : 0.0d);
            }
        }

        for (Piatto candidateDish : availableDishById.values()) {
            if (excludedDishIds.contains(candidateDish.getId())) {
                continue;
            }

            CandidateScore candidate = candidateByDishId.computeIfAbsent(candidateDish.getId(), id -> new CandidateScore(candidateDish));
            candidate.addFallback(scoreFallback(candidateDish, signals.get(candidateDish.getId()), cartCategories, hasMainDish));
        }

        return candidateByDishId.values().stream()
                .sorted(Comparator
                        .comparingDouble(CandidateScore::totalScore).reversed()
                        .thenComparingInt(CandidateScore::pairHits).reversed()
                        .thenComparingDouble(CandidateScore::bestConfidence).reversed()
                        .thenComparing(candidate -> safePrice(candidate.dish().getPrezzo())))
                .map(CandidateScore::dish)
                .limit(limit)
                .toList();
    }

    private double scoreCooccurrence(DishCooccurrence cooccurrence,
                                     Piatto suggestedDish,
                                     MenuIntelligenceService.DishSignal signal,
                                     Set<Categoria> cartCategories,
                                     boolean hasMainDish) {
        double confidence = cooccurrence.getConfidence() != null ? cooccurrence.getConfidence() : 0.0d;
        return (0.62d * confidence)
                + (0.23d * performanceScore(signal))
                + categoryGapScore(suggestedDish.getCategoria(), cartCategories, hasMainDish)
                + recommendationBoost(suggestedDish)
                + affordabilityBoost(suggestedDish.getPrezzo());
    }

    private double scoreFallback(Piatto candidateDish,
                                 MenuIntelligenceService.DishSignal signal,
                                 Set<Categoria> cartCategories,
                                 boolean hasMainDish) {
        return (0.58d * performanceScore(signal))
                + categoryGapScore(candidateDish.getCategoria(), cartCategories, hasMainDish)
                + recommendationBoost(candidateDish)
                + affordabilityBoost(candidateDish.getPrezzo());
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

    private record CandidateScore(
            Piatto dish,
            double cooccurrenceScore,
            double fallbackScore,
            double bestConfidence,
            int pairHits
    ) {
        private CandidateScore(Piatto dish) {
            this(dish, 0.0d, 0.0d, 0.0d, 0);
        }

        private CandidateScore addCooccurrence(double score, double confidence) {
            return new CandidateScore(
                    dish,
                    cooccurrenceScore + score,
                    fallbackScore,
                    Math.max(bestConfidence, confidence),
                    pairHits + 1
            );
        }

        private CandidateScore addFallback(double score) {
            return new CandidateScore(dish, cooccurrenceScore, Math.max(fallbackScore, score), bestConfidence, pairHits);
        }

        private double totalScore() {
            return cooccurrenceScore + fallbackScore;
        }
    }
}
