package com.waitero.back.service;

import com.waitero.back.dto.AnalyticsDashboardDTO;
import com.waitero.back.dto.AnalyticsOverviewDTO;
import com.waitero.back.dto.BenchmarkInsightDTO;
import com.waitero.back.dto.DishPerformanceDTO;
import com.waitero.back.dto.ExperimentMetricsDTO;
import com.waitero.back.dto.ExperimentUpliftDTO;
import com.waitero.back.dto.ExperimentVariantMetricsDTO;
import com.waitero.back.dto.RevenueOpportunityDTO;
import com.waitero.back.dto.RevenueKpiDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final JdbcTemplate jdbcTemplate;
    private final PerformanceLabelResolver performanceLabelResolver;

    public AnalyticsDashboardDTO getDashboard(Long restaurantId) {
        AnalyticsOverviewDTO overview = getOverview(restaurantId);
        List<DishPerformanceDTO> dishPerformance = getDishPerformance(restaurantId);

        return AnalyticsDashboardDTO.builder()
                .overview(overview)
                .dishPerformance(dishPerformance)
                .revenueOpportunities(buildRevenueOpportunities(dishPerformance))
                .benchmarkInsights(buildBenchmarkInsights(dishPerformance))
                .revenueKpis(getRevenueBreakdown(restaurantId))
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
        long impressions = readCount(
                "select count(*) from event_log where restaurant_id = ? and event_type = 'view_menu_item'",
                restaurantId
        );
        long clicks = readCount(
                "select count(*) from event_log where restaurant_id = ? and event_type = 'click_dish'",
                restaurantId
        );
        BigDecimal totalRevenue = jdbcTemplate.queryForObject(
                "select coalesce(sum(totale), 0) from customer_orders where ristoratore_id = ?",
                BigDecimal.class,
                restaurantId
        );
        BigDecimal averageOrderValue = jdbcTemplate.queryForObject(
                "select coalesce(avg(totale), 0) from customer_orders where ristoratore_id = ?",
                BigDecimal.class,
                restaurantId
        );

        BigDecimal conversionRate = ratio(orders, views);
        BigDecimal dropoffRate = BigDecimal.ONE.subtract(ratio(orders, sessions)).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        BigDecimal ctr = ratio(clicks, impressions);
        BigDecimal revenuePerImpression = moneyRatio(totalRevenue, impressions);

        return AnalyticsOverviewDTO.builder()
                .views(views)
                .orders(orders)
                .sessions(sessions)
                .conversionRate(conversionRate)
                .dropoffRate(dropoffRate)
                .averageOrderValue(averageOrderValue == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : averageOrderValue.setScale(2, RoundingMode.HALF_UP))
                .impressions(impressions)
                .ctr(ctr)
                .revenuePerImpression(revenuePerImpression)
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
                    coalesce(ev.impressions, 0) as impressions,
                    coalesce(ev.clicks, 0) as clicks,
                    coalesce(ev.add_to_cart, 0) as add_to_cart,
                    coalesce(ord.order_count, 0) as order_count,
                    coalesce(ord.revenue, 0) as revenue
                from piatto p
                left join (
                    select
                        dish_id,
                        count(*) filter (where event_type = 'view_dish') as views,
                        count(*) filter (where event_type = 'view_menu_item') as impressions,
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
                        count(distinct co.id) as order_count,
                        coalesce(sum(coi.prezzo_unitario * coi.quantity), 0) as revenue
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
                    long impressions = rs.getLong("impressions");
                    long clicks = rs.getLong("clicks");
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
                            .impressions(impressions)
                            .clicks(clicks)
                            .addToCart(addToCart)
                            .orderCount(orderCount)
                            .viewToCartRate(viewToCartRate)
                            .ctr(ratio(clicks, impressions))
                            .revenuePerImpression(moneyRatio(rs.getBigDecimal("revenue"), impressions))
                            .viewToOrderRate(viewToOrderRate)
                            .performanceLabel(performanceLabelResolver.resolve(views, addToCart, orderCount))
                            .build();
                },
                restaurantId,
                restaurantId,
                restaurantId
        );
    }

    public List<DishFeatures> getDishFeatures(Long restaurantId) {
        return jdbcTemplate.query(
                """
                select
                    p.id as dish_id,
                    coalesce(p.prezzo, 0) as price,
                    coalesce(ev.impressions, 0) as impressions,
                    coalesce(ev.clicks, 0) as clicks,
                    coalesce(ev.views, 0) as views,
                    coalesce(ev.add_to_cart, 0) as add_to_cart,
                    coalesce(ord.orders, 0) as orders,
                    coalesce(ord.quantity, 0) as quantity,
                    coalesce(ord.revenue, 0) as revenue
                from piatto p
                left join (
                    select
                        dish_id,
                        count(*) filter (where event_type = 'view_menu_item') as impressions,
                        count(*) filter (where event_type = 'click_dish') as clicks,
                        count(*) filter (where event_type = 'view_dish') as views,
                        count(*) filter (where event_type = 'add_to_cart') as add_to_cart
                    from event_log
                    where restaurant_id = ?
                      and dish_id is not null
                    group by dish_id
                ) ev on ev.dish_id = p.id
                left join (
                    select
                        coi.piatto_id as dish_id,
                        count(distinct co.id) as orders,
                        coalesce(sum(coi.quantity), 0) as quantity,
                        coalesce(sum(coi.quantity * coi.prezzo_unitario), 0) as revenue
                    from customer_order_items coi
                    join customer_orders co on co.id = coi.ordine_id
                    where co.ristoratore_id = ?
                    group by coi.piatto_id
                ) ord on ord.dish_id = p.id
                where p.ristoratore_id = ?
                """,
                (rs, rowNum) -> {
                    long impressions = rs.getLong("impressions");
                    long clicks = rs.getLong("clicks");
                    long views = rs.getLong("views");
                    long addToCart = rs.getLong("add_to_cart");
                    long orders = rs.getLong("orders");
                    long quantity = rs.getLong("quantity");
                    double revenue = safeDouble(rs.getBigDecimal("revenue"));
                    double price = safeDouble(rs.getBigDecimal("price"));

                    double ctr = impressions < 5 ? 0.0d : safeDivide(clicks, impressions);
                    double orderRate = views < 5 ? 0.0d : safeDivide(orders, views);
                    double cartRate = views < 5 ? 0.0d : safeDivide(addToCart, views);
                    double alpha = 5.0d;
                    double beta = 10.0d;
                    double rpi = safeDivide(revenue + alpha, impressions + beta);
                    double popularity = Math.min(quantity / 50.0d, 1.0d);

                    return new DishFeatures(
                            rs.getLong("dish_id"),
                            impressions,
                            rpi,
                            ctr,
                            orderRate,
                            cartRate,
                            popularity,
                            price
                    );
                },
                restaurantId,
                restaurantId,
                restaurantId
        );
    }

    public List<Double> normalize(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<Double> safeValues = values.stream()
                .map(value -> value == null || !Double.isFinite(value) ? 0.0d : value)
                .toList();
        double min = safeValues.stream().min(Double::compareTo).orElse(0.0d);
        double max = safeValues.stream().max(Double::compareTo).orElse(0.0d);

        if (Double.compare(max, min) == 0 || max - min < 0.0001d) {
            return safeValues.stream().map(ignored -> 0.5d).toList();
        }

        return safeValues.stream()
                .map(value -> Math.max(0.0d, Math.min(value, max)))
                .map(value -> (value - min) / (max - min))
                .toList();
    }

    public RevenueKpiDTO getRevenueBreakdown(Long restaurantId) {
        return jdbcTemplate.queryForObject(
                """
                with order_totals as (
                    select
                        co.id as order_id,
                        coalesce(sum(coi.quantity * coi.prezzo_unitario), 0) as total_revenue,
                        coalesce(sum(coi.quantity), 0) as item_quantity,
                        bool_or(coi.source = 'upsell') as has_upsell
                    from customer_orders co
                    left join customer_order_items coi on coi.ordine_id = co.id
                    where co.ristoratore_id = ?
                    group by co.id
                ), totals as (
                    select
                        coalesce(sum(total_revenue), 0) as total_revenue,
                        coalesce(sum(case when has_upsell then total_revenue else 0 end), 0) as upsell_revenue,
                        count(*) as orders,
                        coalesce(sum(item_quantity), 0) as item_quantity,
                        count(*) filter (where has_upsell) as orders_with_upsell,
                        count(*) filter (where not has_upsell or has_upsell is null) as orders_without_upsell,
                        coalesce(avg(total_revenue) filter (where has_upsell), 0) as avg_with_upsell,
                        coalesce(avg(total_revenue) filter (where not has_upsell or has_upsell is null), 0) as avg_without_upsell
                    from order_totals
                ), sessions as (
                    select count(distinct nullif(session_id, '')) as users
                    from event_log
                    where restaurant_id = ?
                )
                select
                    totals.total_revenue,
                    totals.upsell_revenue,
                    totals.orders,
                    totals.item_quantity,
                    totals.orders_with_upsell,
                    totals.orders_without_upsell,
                    totals.avg_with_upsell,
                    totals.avg_without_upsell,
                    sessions.users as users
                from totals
                cross join sessions
                """,
                (rs, rowNum) -> {
                    BigDecimal totalRevenue = normalizeMoney(rs.getBigDecimal("total_revenue"));
                    BigDecimal upsellRevenue = normalizeMoney(rs.getBigDecimal("upsell_revenue"));
                    long orders = rs.getLong("orders");
                    long itemQuantity = rs.getLong("item_quantity");
                    long users = rs.getLong("users");
                    long ordersWithUpsell = rs.getLong("orders_with_upsell");
                    long ordersWithoutUpsell = rs.getLong("orders_without_upsell");
                    BigDecimal avgWithUpsell = normalizeMoney(rs.getBigDecimal("avg_with_upsell"));
                    BigDecimal avgWithoutUpsell = normalizeMoney(rs.getBigDecimal("avg_without_upsell"));

                    return RevenueKpiDTO.builder()
                            .revenuePerUser(moneyRatio(totalRevenue, users))
                            .averageOrderValue(moneyRatio(totalRevenue, orders))
                            .upsellRevenue(upsellRevenue)
                            .upsellShare(decimalRatio(upsellRevenue, totalRevenue))
                            .itemsPerOrder(decimalRatio(BigDecimal.valueOf(itemQuantity), BigDecimal.valueOf(orders)))
                            .ordersWithUpsell(ordersWithUpsell)
                            .ordersWithoutUpsell(ordersWithoutUpsell)
                            .avgWithUpsell(avgWithUpsell)
                            .avgWithoutUpsell(avgWithoutUpsell)
                            .uplift(normalizeMoney(avgWithUpsell.subtract(avgWithoutUpsell)))
                            .build();
                },
                restaurantId,
                restaurantId
        );
    }

    public ExperimentMetricsDTO getExperimentMetrics(Long restaurantId) {
        List<ExperimentMetricRow> rows = jdbcTemplate.query(
                """
                with order_rows as (
                    select
                        co.id as order_id,
                        coalesce(nullif(btrim(co.variant), ''), 'A') as variant,
                        coalesce(co.totale, coalesce(sum(coi.quantity * coi.prezzo_unitario), 0)) as total_price,
                        coalesce(sum(coi.quantity), 0) as item_count
                    from customer_orders co
                    left join customer_order_items coi on coi.ordine_id = co.id
                    where co.ristoratore_id = ?
                    group by co.id, co.variant, co.totale
                )
                select
                    variant,
                    count(*) as orders,
                    coalesce(sum(total_price), 0) as revenue,
                    coalesce(avg(total_price), 0) as avg_order_value,
                    coalesce(sum(item_count), 0) as total_items
                from order_rows
                group by variant
                """,
                (rs, rowNum) -> new ExperimentMetricRow(
                        rs.getString("variant"),
                        rs.getLong("orders"),
                        normalizeMoney(rs.getBigDecimal("revenue")),
                        normalizeMoney(rs.getBigDecimal("avg_order_value")),
                        rs.getLong("total_items")
                ),
                restaurantId
        );

        Map<String, ExperimentVariantMetricsDTO> byVariant = new HashMap<>();
        for (ExperimentMetricRow row : rows) {
            String variant = "B".equalsIgnoreCase(row.variant()) ? "B" : "A";
            byVariant.put(variant, ExperimentVariantMetricsDTO.builder()
                    .orders(row.orders())
                    .revenue(row.revenue())
                    .avgOrderValue(row.avgOrderValue())
                    .itemsPerOrder(decimalRatio(BigDecimal.valueOf(row.totalItems()), BigDecimal.valueOf(row.orders())))
                    .build());
        }

        ExperimentVariantMetricsDTO variantA = byVariant.getOrDefault("A", emptyExperimentVariant());
        ExperimentVariantMetricsDTO variantB = byVariant.getOrDefault("B", emptyExperimentVariant());

        return ExperimentMetricsDTO.builder()
                .variantA(variantA)
                .variantB(variantB)
                .uplift(ExperimentUpliftDTO.builder()
                        .revenue(normalizeMoney(variantA.revenue().subtract(variantB.revenue())))
                        .avgOrderValue(normalizeMoney(variantA.avgOrderValue().subtract(variantB.avgOrderValue())))
                        .itemsPerOrder(variantA.itemsPerOrder().subtract(variantB.itemsPerOrder()).setScale(4, RoundingMode.HALF_UP))
                        .build())
                .build();
    }

    private ExperimentVariantMetricsDTO emptyExperimentVariant() {
        return ExperimentVariantMetricsDTO.builder()
                .orders(0L)
                .revenue(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .avgOrderValue(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .itemsPerOrder(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .build();
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


    public double safeDivide(double numerator, double denominator) {
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator) || denominator == 0.0d) {
            return 0.0d;
        }
        return numerator / denominator;
    }

    private double safeDouble(BigDecimal value) {
        return value == null ? 0.0d : value.doubleValue();
    }

    private BigDecimal decimalRatio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }
    private BigDecimal moneyRatio(BigDecimal numerator, long denominator) {
        if (denominator <= 0 || numerator == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return numerator.divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
    private BigDecimal normalizeMoney(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record CategoryBenchmark(BigDecimal orderRate, BigDecimal cartRate, int dishCount) {
    }

    private record ExperimentMetricRow(String variant, long orders, BigDecimal revenue, BigDecimal avgOrderValue, long totalItems) {
    }

    public static class DishFeatures {
        public Long dishId;
        public long impressions;
        public double rpi;
        public double ctr;
        public double orderRate;
        public double cartRate;
        public double popularity;
        public double price;

        public DishFeatures(Long dishId, long impressions, double rpi, double ctr, double orderRate, double cartRate, double popularity, double price) {
            this.dishId = dishId;
            this.impressions = impressions;
            this.rpi = rpi;
            this.ctr = ctr;
            this.orderRate = orderRate;
            this.cartRate = cartRate;
            this.popularity = popularity;
            this.price = price;
        }
    }
}
