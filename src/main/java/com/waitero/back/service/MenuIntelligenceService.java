package com.waitero.back.service;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class MenuIntelligenceService {

    private static final Duration RANKING_CACHE_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final PerformanceLabelResolver performanceLabelResolver;
    private final AnalyticsService analyticsService;
    private final ExperimentService experimentService;
    private final PiattoRepository piattoRepository;
    private final Map<Long, CachedRanking> rankingCache = new ConcurrentHashMap<>();


    public List<Piatto> rankDishesByRevenue(Long restaurantId) {
        Instant now = Instant.now();
        CachedRanking cached = rankingCache.get(restaurantId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.ranking();
        }

        List<Piatto> dishes = piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId);
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
        rankingCache.put(restaurantId, new CachedRanking(diversified, scoreByDishId, now.plus(RANKING_CACHE_TTL)));
        return diversified;
    }
    public List<Piatto> rankDishesByRevenue(Long restaurantId, String sessionId) {
        String variant = experimentService.getVariant(sessionId, restaurantId);
        if ("B".equals(variant) || ExperimentService.VARIANT_HOLDOUT.equals(variant)) {
            return rankDishesByBaseline(restaurantId);
        }
        return rankDishesByRevenue(restaurantId);
    }

    private List<Piatto> rankDishesByBaseline(Long restaurantId) {
        List<Piatto> dishes = piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId);
        if (dishes.isEmpty()) {
            return List.of();
        }

        Map<Long, AnalyticsService.DishFeatures> featuresByDishId = new LinkedHashMap<>();
        for (AnalyticsService.DishFeatures features : analyticsService.getDishFeatures(restaurantId)) {
            featuresByDishId.put(features.dishId, features);
        }

        return dishes.stream()
                .sorted(Comparator
                        .comparing((Piatto dish) -> {
                            AnalyticsService.DishFeatures features = featuresByDishId.get(dish.getId());
                            double popularity = features != null ? features.popularity : 0.0d;
                            double price = dish.getPrezzo() != null ? dish.getPrezzo().doubleValue() : 0.0d;
                            return popularity + price;
                        }).reversed()
                        .thenComparing(Piatto::getId))
                .toList();
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

    private record CachedRanking(List<Piatto> ranking, Map<Long, Double> scoreByDishId, Instant expiresAt) {
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
