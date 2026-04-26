package com.waitero.back.service;

import com.waitero.back.dto.DishIntelligenceDTO;
import com.waitero.analyticsv2.dto.MenuRankedDishV2DTO;
import com.waitero.analyticsv2.service.MenuIntelligenceV2Service;
import com.waitero.analyticsv2.support.AnalyticsV2JsonLogger;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.entity.Piatto;
import com.waitero.back.repository.PiattoRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class MenuIntelligenceService {

    private static final Duration RANKING_CACHE_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final PerformanceLabelResolver performanceLabelResolver;
    private final AnalyticsService analyticsService;
    private final ExperimentService experimentService;
    private final AnalyticsV2JsonLogger analyticsV2JsonLogger;
    private final PiattoRepository piattoRepository;
    private final MenuIntelligenceV2Service menuIntelligenceV2Service;
    private final AnalyticsV2TimeRangeResolver analyticsV2TimeRangeResolver;
    private final DishIntelligenceService dishIntelligenceService;
    private final MenuRankingExperimentLogger menuRankingExperimentLogger;
    private final Map<Long, CachedRanking> rankingCache = new ConcurrentHashMap<>();

    @Deprecated(since = "2026-04", forRemoval = false)
    public List<Piatto> rankDishesByRevenue(Long restaurantId) {
        return rankDishesByRevenueInternal(restaurantId);
    }

    public List<Piatto> rankDishesByRevenue(Long restaurantId, String sessionId) {
        return rankDishesByRevenue(restaurantId, sessionId, null);
    }

    public List<Piatto> rankDishesByRevenue(Long restaurantId, String sessionId, Integer tableId) {
        String variant = experimentService.getVariant(sessionId, restaurantId, tableId);
        if (ExperimentService.VARIANT_B.equals(variant)) {
            return rankDishesByRevenueWithV2Fallback(restaurantId, sessionId, tableId, variant);
        }
        if (ExperimentService.VARIANT_C.equals(variant)) {
            return rankDishesByDishScoreWithFallback(restaurantId, sessionId, tableId);
        }
        return rankDishesByRevenueInternal(restaurantId);
    }

    private List<Piatto> rankDishesByRevenueWithV2Fallback(Long restaurantId, String sessionId, Integer tableId, String assignedVariant) {
        try {
            return rankDishesByAnalyticsV2(restaurantId);
        } catch (Exception ex) {
            experimentService.pinVariant(sessionId, restaurantId, tableId, ExperimentService.VARIANT_A);
            analyticsV2JsonLogger.logFallback("ranking", restaurantId, assignedVariant, null, List.of(), ex);
            return rankDishesByRevenueInternal(restaurantId);
        }
    }

    private List<Piatto> rankDishesByRevenueInternal(Long restaurantId) {
        Instant now = Instant.now();
        CachedRanking cached = rankingCache.get(restaurantId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return materializeCachedRanking(restaurantId, cached);
        }

        List<Piatto> dishes = loadAvailableDishes(restaurantId);
        if (dishes.isEmpty()) {
            return List.of();
        }

        Map<Long, AnalyticsService.DishFeatures> featuresByDishId = new LinkedHashMap<>();
        for (AnalyticsService.DishFeatures features : analyticsService.getDishFeatures(restaurantId)) {
            featuresByDishId.put(features.dishId, features);
        }

        double avgPrice = dishes.stream()
                .map(Piatto::getPrezzo)
                .filter(price -> price != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0d);

        List<AnalyticsService.DishFeatures> orderedFeatures = dishes.stream()
                .map(dish -> featuresByDishId.getOrDefault(dish.getId(), new AnalyticsService.DishFeatures(
                        dish.getId(),
                        0L,
                        0.0d,
                        0.0d,
                        0.0d,
                        0.0d,
                        0.0d,
                        dish.getPrezzo() != null ? dish.getPrezzo().doubleValue() : 0.0d
                )))
                .toList();

        List<Double> rpiNorm = analyticsService.normalize(orderedFeatures.stream().map(features -> features.rpi).toList());
        List<Double> orderRateNorm = analyticsService.normalize(orderedFeatures.stream().map(features -> features.orderRate).toList());
        List<Double> cartRateNorm = analyticsService.normalize(orderedFeatures.stream().map(features -> features.cartRate).toList());
        List<Double> ctrNorm = analyticsService.normalize(orderedFeatures.stream().map(features -> features.ctr).toList());
        List<Double> popularityNorm = analyticsService.normalize(orderedFeatures.stream().map(features -> features.popularity).toList());
        List<Double> priceBoostNorm = analyticsService.normalize(orderedFeatures.stream()
                .map(features -> avgPrice <= 0.0d ? 0.0d : Math.min(features.price / avgPrice, 1.0d))
                .toList());

        long totalOrders = readTotalOrders(restaurantId);
        Map<Long, Double> scoreByDishId = new LinkedHashMap<>();
        for (int i = 0; i < orderedFeatures.size(); i++) {
            AnalyticsService.DishFeatures features = orderedFeatures.get(i);
            double score;
            if (totalOrders < 20 || features.impressions < 10) {
                score = features.popularity + features.price;
            } else {
                score = (0.40d * rpiNorm.get(i))
                        + (0.20d * orderRateNorm.get(i))
                        + (0.15d * cartRateNorm.get(i))
                        + (0.10d * ctrNorm.get(i))
                        + (0.10d * popularityNorm.get(i))
                        + (0.05d * priceBoostNorm.get(i));
            }
            double previousScore = cached != null ? cached.scoreByDishId().getOrDefault(features.dishId, score) : score;
            score = (0.8d * score) + (0.2d * previousScore);
            scoreByDishId.put(features.dishId, score);
        }

        List<Piatto> ranked = dishes.stream()
                .sorted(Comparator
                        .comparing((Piatto dish) -> scoreByDishId.getOrDefault(dish.getId(), 0.0d)).reversed()
                        .thenComparing(Piatto::getId))
                .toList();
        List<Piatto> diversified = applyDiversity(ranked);
        rankingCache.put(restaurantId, new CachedRanking(
                diversified.stream().map(Piatto::getId).toList(),
                scoreByDishId,
                now.plus(RANKING_CACHE_TTL)
        ));
        return diversified;
    }

    private List<Piatto> rankDishesByAnalyticsV2(Long restaurantId) {
        List<Piatto> dishes = loadAvailableDishes(restaurantId);
        if (dishes.isEmpty()) {
            return List.of();
        }

        Map<Long, Piatto> dishById = new LinkedHashMap<>();
        for (Piatto dish : dishes) {
            dishById.put(dish.getId(), dish);
        }

        AnalyticsV2TimeRange timeRange = analyticsV2TimeRangeResolver.resolve(null, null);
        List<MenuRankedDishV2DTO> rankedMenu = menuIntelligenceV2Service.getRankedMenu(restaurantId, dishes.size(), timeRange);

        List<Piatto> ordered = new ArrayList<>(dishes.size());
        Set<Long> seenDishIds = new LinkedHashSet<>();
        for (MenuRankedDishV2DTO row : rankedMenu) {
            Piatto dish = dishById.get(row.dishId());
            if (dish != null && seenDishIds.add(dish.getId())) {
                ordered.add(dish);
            }
        }

        dishes.stream()
                .sorted(Comparator.comparing(Piatto::getId))
                .filter(dish -> seenDishIds.add(dish.getId()))
                .forEach(ordered::add);

        return ordered;
    }

    private List<Piatto> rankDishesByDishScoreWithFallback(Long restaurantId, String sessionId, Integer tableId) {
        try {
            return rankDishesByDishScore(restaurantId, sessionId);
        } catch (Exception ex) {
            experimentService.pinVariant(sessionId, restaurantId, tableId, ExperimentService.VARIANT_A);
            menuRankingExperimentLogger.logDishScoreFallback(restaurantId, sessionId, ex);
            return rankDishesByRevenueInternal(restaurantId);
        }
    }

    private List<Piatto> rankDishesByDishScore(Long restaurantId, String sessionId) {
        List<Piatto> dishes = loadAvailableDishes(restaurantId);
        if (dishes.isEmpty()) {
            return List.of();
        }

        List<DishIntelligenceDTO> intelligence = dishIntelligenceService.getDishIntelligence(restaurantId);
        if (intelligence.isEmpty()) {
            throw new IllegalStateException("Dish intelligence returned an empty ranking");
        }

        Map<Long, Piatto> dishById = new LinkedHashMap<>();
        for (Piatto dish : dishes) {
            dishById.put(dish.getId(), dish);
        }

        List<DishIntelligenceDTO> orderedIntelligence = intelligence.stream()
                .sorted(Comparator
                        .comparing(DishIntelligenceDTO::score, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(DishIntelligenceDTO::dishId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<Piatto> ordered = new ArrayList<>(dishes.size());
        Set<Long> seenDishIds = new LinkedHashSet<>();
        Map<Long, BigDecimal> scoreByDishId = new LinkedHashMap<>();
        for (DishIntelligenceDTO row : orderedIntelligence) {
            scoreByDishId.put(row.dishId(), row.score());
            Piatto dish = dishById.get(row.dishId());
            if (dish != null && seenDishIds.add(dish.getId())) {
                ordered.add(dish);
            }
        }

        dishes.stream()
                .sorted(Comparator.comparing(Piatto::getId))
                .filter(dish -> seenDishIds.add(dish.getId()))
                .forEach(ordered::add);

        List<Piatto> withCategoryCoverage = ensureCategoryCoverage(ordered);
        List<Piatto> diversified = applyDiversity(withCategoryCoverage);
        menuRankingExperimentLogger.logDishScoreRanking(restaurantId, sessionId, diversified, scoreByDishId);
        return diversified;
    }

    private List<Piatto> ensureCategoryCoverage(List<Piatto> ranked) {
        if (ranked == null || ranked.size() <= 1) {
            return ranked == null ? List.of() : ranked;
        }

        List<Piatto> categoryLeads = new ArrayList<>();
        Set<Object> seenCategories = new LinkedHashSet<>();
        Set<Long> categoryLeadIds = new LinkedHashSet<>();
        for (Piatto dish : ranked) {
            if (dish == null || dish.getId() == null || dish.getCategoria() == null) {
                continue;
            }
            if (seenCategories.add(dish.getCategoria())) {
                categoryLeads.add(dish);
                categoryLeadIds.add(dish.getId());
            }
        }

        if (categoryLeads.size() <= 1) {
            return ranked;
        }

        List<Piatto> reordered = new ArrayList<>(ranked.size());
        reordered.addAll(categoryLeads);
        for (Piatto dish : ranked) {
            if (dish == null || dish.getId() == null || categoryLeadIds.contains(dish.getId())) {
                continue;
            }
            reordered.add(dish);
        }
        return reordered;
    }

    private List<Piatto> applyDiversity(List<Piatto> ranked) {
        List<Piatto> result = new ArrayList<>(ranked);
        for (int i = 2; i < result.size(); i++) {
            if (!sameCategory(result.get(i - 2), result.get(i - 1)) || !sameCategory(result.get(i - 1), result.get(i))) {
                continue;
            }

            int swapIndex = -1;
            for (int j = i + 1; j < result.size(); j++) {
                if (!sameCategory(result.get(i - 1), result.get(j))) {
                    swapIndex = j;
                    break;
                }
            }

            if (swapIndex > i) {
                Piatto replacement = result.remove(swapIndex);
                result.add(i, replacement);
            }
        }
        return result;
    }

    private boolean sameCategory(Piatto left, Piatto right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getCategoria(), right.getCategoria());
    }

    private long readTotalOrders(Long restaurantId) {
        Long value = jdbcTemplate.queryForObject(
                "select count(*) from customer_orders where ristoratore_id = ?",
                Long.class,
                restaurantId
        );
        return value == null ? 0L : value;
    }

    public Map<Long, DishSignal> getDishSignals(Long restaurantId) {
        List<DishSignal> signals = jdbcTemplate.query(
                """
                select
                    p.id as dish_id,
                    coalesce(ev.views, 0) as views,
                    coalesce(ev.clicks, 0) as clicks,
                    coalesce(ev.add_to_cart, 0) as add_to_cart,
                    coalesce(ord.order_count, 0) as order_count
                from piatto p
                left join (
                    select
                        dish_id,
                        count(*) filter (where event_type = 'view_dish') as views,
                        count(*) filter (where event_type = 'click_dish') as clicks,
                        count(*) filter (where event_type = 'add_to_cart') as add_to_cart
                    from event_log
                    where restaurant_id = ?
                      and dish_id is not null
                    group by dish_id
                ) ev on ev.dish_id = p.id
                left join (
                    select
                        coi.piatto_id as dish_id,
                        count(distinct co.id) as order_count
                    from customer_order_items coi
                    join customer_orders co on co.id = coi.ordine_id
                    where co.ristoratore_id = ?
                    group by coi.piatto_id
                ) ord on ord.dish_id = p.id
                where p.ristoratore_id = ?
                  and coalesce(p.disponibile, false) = true
                """,
                (rs, rowNum) -> {
                    long views = rs.getLong("views");
                    long addToCart = rs.getLong("add_to_cart");
                    long orderCount = rs.getLong("order_count");
                    return DishSignal.builder()
                            .dishId(rs.getLong("dish_id"))
                            .views(views)
                            .clicks(rs.getLong("clicks"))
                            .addToCart(addToCart)
                            .orderCount(orderCount)
                            .viewToCartRate(ratio(addToCart, views))
                            .viewToOrderRate(ratio(orderCount, views))
                            .performanceLabel(performanceLabelResolver.resolve(views, addToCart, orderCount))
                            .build();
                },
                restaurantId,
                restaurantId,
                restaurantId
        );

        Map<Long, DishSignal> result = new LinkedHashMap<>();
        for (DishSignal signal : signals) {
            result.put(signal.dishId(), signal);
        }
        return result;
    }

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private List<Piatto> materializeCachedRanking(Long restaurantId, CachedRanking cachedRanking) {
        if (cachedRanking == null) {
            return List.of();
        }

        Map<Long, Piatto> availableDishById = loadAvailableDishes(restaurantId).stream()
                .collect(java.util.stream.Collectors.toMap(Piatto::getId, dish -> dish, (left, right) -> left, LinkedHashMap::new));
        if (availableDishById.isEmpty()) {
            return List.of();
        }

        List<Piatto> ordered = new ArrayList<>(availableDishById.size());
        Set<Long> seenDishIds = new LinkedHashSet<>();
        for (Long dishId : cachedRanking.dishIds()) {
            Piatto dish = availableDishById.get(dishId);
            if (dish != null && seenDishIds.add(dishId)) {
                ordered.add(dish);
            }
        }
        availableDishById.values().stream()
                .filter(dish -> dish.getId() != null && seenDishIds.add(dish.getId()))
                .sorted(Comparator.comparing(Piatto::getId))
                .forEach(ordered::add);
        return ordered;
    }

    private List<Piatto> loadAvailableDishes(Long restaurantId) {
        return piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId).stream()
                .filter(this::isAvailable)
                .toList();
    }

    private boolean isAvailable(Piatto dish) {
        return dish != null && Boolean.TRUE.equals(dish.getDisponibile());
    }

    private record CachedRanking(List<Long> dishIds, Map<Long, Double> scoreByDishId, Instant expiresAt) {
    }

    @Builder
    public record DishSignal(
            Long dishId,
            long views,
            long clicks,
            long addToCart,
            long orderCount,
            BigDecimal viewToCartRate,
            BigDecimal viewToOrderRate,
            String performanceLabel
    ) {
    }
}

