package com.waitero.back.service;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UpsellService {

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final OrdineItemRepository ordineItemRepository;
    private final DishCooccurrenceRepository dishCooccurrenceRepository;
    private final PiattoRepository piattoRepository;

    private final Map<Long, Instant> refreshByRestaurant = new ConcurrentHashMap<>();
    private final Map<Long, Object> locksByRestaurant = new ConcurrentHashMap<>();

    @Transactional
    public List<Piatto> getUpsellSuggestions(Long dishId, Long restaurantId) {
        if (dishId == null || restaurantId == null) {
            return List.of();
        }
        if (!piattoRepository.existsByIdAndRistoratoreId(dishId, restaurantId)) {
            return List.of();
        }

        ensureAggregatesUpToDate(restaurantId);

        return dishCooccurrenceRepository.findAvailableSuggestions(dishId, restaurantId)
                .stream()
                .filter(cooccurrence -> !Objects.equals(cooccurrence.getSuggestedDish().getId(), dishId))
                .sorted(Comparator
                        .comparingDouble(this::score).reversed()
                        .thenComparing(DishCooccurrence::getConfidence, Comparator.reverseOrder())
                        .thenComparing(cooccurrence -> safePrice(cooccurrence.getSuggestedDish().getPrezzo())))
                .map(DishCooccurrence::getSuggestedDish)
                .limit(2)
                .collect(Collectors.toList());
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

    private double score(DishCooccurrence cooccurrence) {
        double confidence = cooccurrence.getConfidence() != null ? cooccurrence.getConfidence() : 0.0d;
        double inversePrice = inversePrice(cooccurrence.getSuggestedDish().getPrezzo());
        return (0.7d * confidence) + (0.3d * inversePrice);
    }

    private double inversePrice(BigDecimal price) {
        if (price == null) {
            return 0.0d;
        }
        double numericPrice = price.doubleValue();
        if (numericPrice <= 0.0d) {
            return 0.0d;
        }
        return 1.0d / numericPrice;
    }

    private double safePrice(BigDecimal price) {
        return price != null ? price.doubleValue() : Double.MAX_VALUE;
    }
}

