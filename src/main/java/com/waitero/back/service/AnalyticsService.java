package com.waitero.back.service;

import com.waitero.back.dto.AnalyticsDashboardDTO;
import com.waitero.back.dto.AnalyticsOverviewDTO;
import com.waitero.back.dto.BenchmarkInsightDTO;
import com.waitero.back.dto.DishPerformanceDTO;
import com.waitero.back.dto.RevenueOpportunityDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsDashboardDTO getDashboard(Long restaurantId) {
        AnalyticsOverviewDTO overview = getOverview(restaurantId);
        List<DishPerformanceDTO> dishPerformance = getDishPerformance(restaurantId);

        return AnalyticsDashboardDTO.builder()
                .overview(overview)
                .dishPerformance(dishPerformance)
                .revenueOpportunities(buildRevenueOpportunities(dishPerformance))
                .benchmarkInsights(buildBenchmarkInsights(dishPerformance))
                .build();
    }

    public AnalyticsOverviewDTO getOverview(Long restaurantId) {
        long views = readCount(
                "select count(*) from event_log where restaurant_id = ? and event_type = 'view_dish'",
                restaurantId
        );
        long orders = readCount(
                "select count(*) from customer_orders where ristoratore_id = ?",
                restaurantId
        );
        long sessions = readCount(
                "select count(distinct session_id) from event_log where restaurant_id = ? and session_id is not null and btrim(session_id) <> ''",
                restaurantId
        );
        BigDecimal averageOrderValue = jdbcTemplate.queryForObject(
                "select coalesce(avg(totale), 0) from customer_orders where ristoratore_id = ?",
                BigDecimal.class,
                restaurantId
        );

        BigDecimal conversionRate = ratio(orders, views);
        BigDecimal dropoffRate = BigDecimal.ONE.subtract(ratio(orders, sessions)).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);

        return AnalyticsOverviewDTO.builder()
                .views(views)
                .orders(orders)
                .sessions(sessions)
                .conversionRate(conversionRate)
                .dropoffRate(dropoffRate)
                .averageOrderValue(averageOrderValue == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : averageOrderValue.setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    public List<DishPerformanceDTO> getDishPerformance(Long restaurantId) {
        return jdbcTemplate.query(
                """
                select
                    p.id as dish_id,
                    p.nome as dish_name,
                    p.categoria as category,
                    p.prezzo as price,
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
                order by coalesce(ev.views, 0) desc, coalesce(ord.order_count, 0) desc, p.nome asc
                """,
                (rs, rowNum) -> {
                    long views = rs.getLong("views");
                    long addToCart = rs.getLong("add_to_cart");
                    long orderCount = rs.getLong("order_count");
                    BigDecimal viewToCartRate = ratio(addToCart, views);
                    BigDecimal viewToOrderRate = ratio(orderCount, views);

                    return DishPerformanceDTO.builder()
                            .dishId(rs.getLong("dish_id"))
                            .dishName(rs.getString("dish_name"))
                            .category(rs.getString("category"))
                            .price(normalizeMoney(rs.getBigDecimal("price")))
                            .views(views)
                            .clicks(rs.getLong("clicks"))
                            .addToCart(addToCart)
                            .orderCount(orderCount)
                            .viewToCartRate(viewToCartRate)
                            .viewToOrderRate(viewToOrderRate)
                            .performanceLabel(resolvePerformanceLabel(views, addToCart, orderCount))
                            .build();
                },
                restaurantId,
                restaurantId,
                restaurantId
        );
    }

    public List<RevenueOpportunityDTO> getRevenueOpportunities(Long restaurantId) {
        return buildRevenueOpportunities(getDishPerformance(restaurantId));
    }

    public List<BenchmarkInsightDTO> getBenchmarkInsights(Long restaurantId) {
        return buildBenchmarkInsights(getDishPerformance(restaurantId));
    }

    private List<RevenueOpportunityDTO> buildRevenueOpportunities(List<DishPerformanceDTO> dishes) {
        BigDecimal totalPrice = dishes.stream()
                .map(DishPerformanceDTO::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averagePrice = dishes.isEmpty()
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : totalPrice.divide(BigDecimal.valueOf(dishes.size()), 2, RoundingMode.HALF_UP);

        return dishes.stream()
                .map(dish -> toRevenueOpportunity(dish, averagePrice))
                .filter(opportunity -> opportunity != null)
                .sorted(Comparator.comparing(RevenueOpportunityDTO::revenueScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(6)
                .toList();
    }

    private List<BenchmarkInsightDTO> buildBenchmarkInsights(List<DishPerformanceDTO> dishes) {
        if (dishes.isEmpty()) {
            return List.of();
        }

        BigDecimal restaurantOrderRate = averageOrderRate(dishes);
        Map<String, CategoryBenchmark> categoryBenchmarks = dishes.stream()
                .collect(Collectors.groupingBy(this::normalizeCategory))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new CategoryBenchmark(
                                averageOrderRate(entry.getValue()),
                                averageCartRate(entry.getValue()),
                                entry.getValue().size()
                        )
                ));

        return dishes.stream()
                .map(dish -> toBenchmarkInsight(dish, categoryBenchmarks.get(normalizeCategory(dish)), restaurantOrderRate))
                .filter(insight -> insight != null)
                .sorted(Comparator.comparing(BenchmarkInsightDTO::benchmarkScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(8)
                .toList();
    }

    private RevenueOpportunityDTO toRevenueOpportunity(DishPerformanceDTO dish, BigDecimal averagePrice) {
        BigDecimal price = normalizeMoney(dish.price());
        BigDecimal orderRate = dish.viewToOrderRate() == null ? BigDecimal.ZERO : dish.viewToOrderRate();
        BigDecimal cartRate = dish.viewToCartRate() == null ? BigDecimal.ZERO : dish.viewToCartRate();

        if (dish.orderCount() >= 5 && orderRate.compareTo(new BigDecimal("0.1800")) >= 0 && price.compareTo(averagePrice) <= 0) {
            BigDecimal suggestedPrice = normalizeMoney(price.multiply(new BigDecimal("1.08")));
            return RevenueOpportunityDTO.builder()
                    .dishId(dish.dishId())
                    .dishName(dish.dishName())
                    .category(dish.category())
                    .currentPrice(price)
                    .suggestedPrice(suggestedPrice)
                    .opportunityType("price_increase_test")
                    .title("Test prezzo verso l'alto")
                    .rationale("Converte bene e ha gia domanda: puoi testare un aumento leggero senza togliere visibilita.")
                    .actionLabel("Prova +8% o posizionamento premium")
                    .revenueScore(score(dish, new BigDecimal("1.10")))
                    .build();
        }

        if (dish.views() >= 12 && dish.orderCount() <= 1 && cartRate.compareTo(new BigDecimal("0.1200")) >= 0) {
            return RevenueOpportunityDTO.builder()
                    .dishId(dish.dishId())
                    .dishName(dish.dishName())
                    .category(dish.category())
                    .currentPrice(price)
                    .suggestedPrice(price)
                    .opportunityType("bundle_or_reposition")
                    .title("Meglio in bundle o upsell")
                    .rationale("Attira attenzione ma chiude poco: meglio usarlo come complemento o abbinarlo a un piatto forte.")
                    .actionLabel("Spostalo vicino ai top performer o usalo in bundle")
                    .revenueScore(score(dish, new BigDecimal("0.92")))
                    .build();
        }

        if (dish.orderCount() >= 3 && price.compareTo(averagePrice) < 0 && cartRate.compareTo(new BigDecimal("0.1500")) >= 0) {
            BigDecimal suggestedPrice = normalizeMoney(price.multiply(new BigDecimal("1.05")));
            return RevenueOpportunityDTO.builder()
                    .dishId(dish.dishId())
                    .dishName(dish.dishName())
                    .category(dish.category())
                    .currentPrice(price)
                    .suggestedPrice(suggestedPrice)
                    .opportunityType("margin_upgrade")
                    .title("Margine migliorabile")
                    .rationale("Il piatto entra bene nel percorso cliente ma il prezzo e sotto media: c'e spazio per alzare valore percepito o prezzo.")
                    .actionLabel("Testa un aumento lieve o una scheda piu premium")
                    .revenueScore(score(dish, new BigDecimal("1.04")))
                    .build();
        }

        if (dish.performanceLabel().equals("top_performer") && dish.price().compareTo(averagePrice) >= 0) {
            return RevenueOpportunityDTO.builder()
                    .dishId(dish.dishId())
                    .dishName(dish.dishName())
                    .category(dish.category())
                    .currentPrice(price)
                    .suggestedPrice(price)
                    .opportunityType("visibility_anchor")
                    .title("Usalo come ancora revenue")
                    .rationale("E gia un top performer con prezzo solido: tienilo visibile e usalo per trainare contorni, bevande o dolci.")
                    .actionLabel("Mantienilo in alto e costruisci upsell intorno")
                    .revenueScore(score(dish, BigDecimal.ONE))
                    .build();
        }

        return null;
    }

    private BenchmarkInsightDTO toBenchmarkInsight(
            DishPerformanceDTO dish,
            CategoryBenchmark categoryBenchmark,
            BigDecimal restaurantOrderRate
    ) {
        if (categoryBenchmark == null || dish.views() <= 0) {
            return null;
        }

        BigDecimal orderRate = safeRate(dish.viewToOrderRate());
        BigDecimal cartRate = safeRate(dish.viewToCartRate());
        BigDecimal categoryOrderRate = safeRate(categoryBenchmark.orderRate());
        BigDecimal categoryCartRate = safeRate(categoryBenchmark.cartRate());

        if (dish.orderCount() >= 3
                && dish.views() >= 6
                && categoryBenchmark.dishCount() >= 2
                && orderRate.compareTo(categoryOrderRate.multiply(new BigDecimal("1.25"))) >= 0) {
            return BenchmarkInsightDTO.builder()
                    .dishId(dish.dishId())
                    .dishName(dish.dishName())
                    .category(dish.category())
                    .views(dish.views())
                    .orderCount(dish.orderCount())
                    .viewToCartRate(cartRate)
                    .viewToOrderRate(orderRate)
                    .categoryViewToCartRate(categoryCartRate)
                    .categoryViewToOrderRate(categoryOrderRate)
                    .restaurantViewToOrderRate(restaurantOrderRate)
                    .benchmarkLabel("outperforming_category")
                    .title("Sopra benchmark di categoria")
                    .rationale("Il piatto converte meglio della media della sua categoria e puo diventare il riferimento del blocco menu.")
                    .actionLabel("Tienilo alto nel ranking e usalo come piatto guida")
                    .benchmarkScore(benchmarkScore(dish, orderRate.subtract(categoryOrderRate).add(new BigDecimal("0.0100"))))
                    .build();
        }

        if (dish.views() >= 10
                && categoryBenchmark.dishCount() >= 2
                && orderRate.compareTo(categoryOrderRate.multiply(new BigDecimal("0.60"))) <= 0) {
            return BenchmarkInsightDTO.builder()
                    .dishId(dish.dishId())
                    .dishName(dish.dishName())
                    .category(dish.category())
                    .views(dish.views())
                    .orderCount(dish.orderCount())
                    .viewToCartRate(cartRate)
                    .viewToOrderRate(orderRate)
                    .categoryViewToCartRate(categoryCartRate)
                    .categoryViewToOrderRate(categoryOrderRate)
                    .restaurantViewToOrderRate(restaurantOrderRate)
                    .benchmarkLabel("under_category_benchmark")
                    .title("Sotto benchmark di categoria")
                    .rationale("Riceve attenzione ma chiude peggio dei pari categoria: il problema e scheda, prezzo o posizionamento nel menu.")
                    .actionLabel("Rivedi foto, copy, prezzo o spostalo accanto ai piatti forti")
                    .benchmarkScore(benchmarkScore(dish, categoryOrderRate.subtract(orderRate).add(new BigDecimal("0.0120"))))
                    .build();
        }

        if (dish.addToCart() >= 3
                && categoryBenchmark.dishCount() >= 2
                && cartRate.compareTo(categoryCartRate.multiply(new BigDecimal("1.15"))) >= 0
                && orderRate.compareTo(categoryOrderRate) < 0) {
            return BenchmarkInsightDTO.builder()
                    .dishId(dish.dishId())
                    .dishName(dish.dishName())
                    .category(dish.category())
                    .views(dish.views())
                    .orderCount(dish.orderCount())
                    .viewToCartRate(cartRate)
                    .viewToOrderRate(orderRate)
                    .categoryViewToCartRate(categoryCartRate)
                    .categoryViewToOrderRate(categoryOrderRate)
                    .restaurantViewToOrderRate(restaurantOrderRate)
                    .benchmarkLabel("post_cart_friction")
                    .title("Attrito dopo add to cart")
                    .rationale("Entra in carrello piu della media categoria ma viene ordinato meno: qui c'e frizione percepita prima del checkout.")
                    .actionLabel("Usalo in bundle, semplifica opzioni o rafforza il valore percepito")
                    .benchmarkScore(benchmarkScore(dish, cartRate.subtract(orderRate).add(new BigDecimal("0.0080"))))
                    .build();
        }

        if (dish.orderCount() >= 2
                && orderRate.compareTo(restaurantOrderRate.multiply(new BigDecimal("1.20"))) >= 0) {
            return BenchmarkInsightDTO.builder()
                    .dishId(dish.dishId())
                    .dishName(dish.dishName())
                    .category(dish.category())
                    .views(dish.views())
                    .orderCount(dish.orderCount())
                    .viewToCartRate(cartRate)
                    .viewToOrderRate(orderRate)
                    .categoryViewToCartRate(categoryCartRate)
                    .categoryViewToOrderRate(categoryOrderRate)
                    .restaurantViewToOrderRate(restaurantOrderRate)
                    .benchmarkLabel("above_restaurant_average")
                    .title("Sopra media del locale")
                    .rationale("Converte meglio della media generale del menu: merita visibilita e puo trainare cross-sell.")
                    .actionLabel("Usalo come leva trasversale tra categorie e percorsi upsell")
                    .benchmarkScore(benchmarkScore(dish, orderRate.subtract(restaurantOrderRate).add(new BigDecimal("0.0060"))))
                    .build();
        }

        return null;
    }

    private BigDecimal benchmarkScore(DishPerformanceDTO dish, BigDecimal delta) {
        BigDecimal normalizedDelta = safeRate(delta).multiply(new BigDecimal("100"));
        BigDecimal volume = BigDecimal.valueOf(Math.max(dish.views(), 1L)).multiply(new BigDecimal("0.40"));
        BigDecimal orders = BigDecimal.valueOf(dish.orderCount()).multiply(new BigDecimal("2.50"));
        return normalizeMoney(normalizedDelta.add(volume).add(orders));
    }

    private BigDecimal averageOrderRate(List<DishPerformanceDTO> dishes) {
        List<DishPerformanceDTO> withViews = dishes.stream()
                .filter(dish -> dish.views() > 0)
                .toList();
        if (withViews.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal total = withViews.stream()
                .map(dish -> safeRate(dish.viewToOrderRate()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return total.divide(BigDecimal.valueOf(withViews.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal averageCartRate(List<DishPerformanceDTO> dishes) {
        List<DishPerformanceDTO> withViews = dishes.stream()
                .filter(dish -> dish.views() > 0)
                .toList();
        if (withViews.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal total = withViews.stream()
                .map(dish -> safeRate(dish.viewToCartRate()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return total.divide(BigDecimal.valueOf(withViews.size()), 4, RoundingMode.HALF_UP);
    }

    private String normalizeCategory(DishPerformanceDTO dish) {
        if (dish.category() == null || dish.category().isBlank()) {
            return "Senza categoria";
        }
        return dish.category().trim();
    }

    private BigDecimal safeRate(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal score(DishPerformanceDTO dish, BigDecimal priceMultiplier) {
        BigDecimal base = BigDecimal.valueOf(dish.orderCount())
                .multiply(normalizeMoney(dish.price()))
                .multiply(priceMultiplier);
        BigDecimal attentionBonus = BigDecimal.valueOf(Math.max(dish.views(), 1L)).multiply(new BigDecimal("0.05"));
        return normalizeMoney(base.add(attentionBonus));
    }

    private long readCount(String sql, Long restaurantId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, restaurantId);
        return value == null ? 0L : value;
    }

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeMoney(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String resolvePerformanceLabel(long views, long addToCart, long orderCount) {
        if (views >= 10 && orderCount == 0) {
            return "high_interest_low_conversion";
        }
        if (orderCount >= 5 || (views >= 10 && ratio(orderCount, views).compareTo(new BigDecimal("0.1500")) >= 0)) {
            return "top_performer";
        }
        if (addToCart > 0 && orderCount == 0) {
            return "cart_abandonment";
        }
        return "stable";
    }

    private record CategoryBenchmark(BigDecimal orderRate, BigDecimal cartRate, int dishCount) {
    }
}
