package com.waitero.analyticsv2;

import com.waitero.analyticsv2.dto.AnalyticsV2DishMetricsDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2OverviewDTO;
import com.waitero.analyticsv2.dto.RelatedDishV2DTO;
import com.waitero.analyticsv2.service.AnalyticsV2Service;
import com.waitero.analyticsv2.service.CoOccurrenceV2Service;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnalyticsV2MetricsValidationIT extends AnalyticsV2IntegrationTestSupport {

    @Autowired
    private AnalyticsV2Service analyticsV2Service;

    @Autowired
    private CoOccurrenceV2Service coOccurrenceV2Service;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Test
    void shouldMatchOverviewWithManualSql() {
        AnalyticsV2TimeRange timeRange = defaultRange();

        AnalyticsV2OverviewDTO overview = analyticsV2Service.getOverview(VALIDATION_RESTAURANT_ID, timeRange);
        Map<String, Object> manual = namedParameterJdbcTemplate.queryForMap(
                """
                with qualified_orders as (
                    select o.id
                    from customer_orders o
                    where o.ristoratore_id = :restaurantId
                      and upper(coalesce(o.status, '')) in ('PAGATO', 'PAID', 'COMPLETED')
                      and o.created_at >= :dateFromInclusive
                      and o.created_at < :dateToExclusive
                ), order_totals as (
                    select
                        qo.id as order_id,
                        coalesce(sum(oi.quantity * oi.prezzo_unitario), 0) as order_revenue,
                        coalesce(sum(oi.quantity), 0) as order_items
                    from qualified_orders qo
                    left join customer_order_items oi on oi.ordine_id = qo.id
                    group by qo.id
                )
                select
                    count(*) as total_orders,
                    coalesce(sum(order_revenue), 0) as total_revenue,
                    case when count(*) = 0 then 0 else sum(order_revenue) / count(*) end as average_order_value,
                    case when count(*) = 0 then 0 else sum(order_items)::numeric / count(*) end as items_per_order
                from order_totals
                """,
                parameters(VALIDATION_RESTAURANT_ID, timeRange)
        );

        assertEquals(4L, overview.totalOrders());
        assertEquals(((Number) manual.get("total_orders")).longValue(), overview.totalOrders());
        assertBigDecimalEquals(new BigDecimal("63.00"), overview.totalRevenue());
        assertBigDecimalEquals((BigDecimal) manual.get("total_revenue"), overview.totalRevenue());
        assertBigDecimalEquals(new BigDecimal("15.75"), overview.averageOrderValue());
        assertBigDecimalEquals((BigDecimal) manual.get("average_order_value"), overview.averageOrderValue());
        assertBigDecimalEquals(new BigDecimal("2.2500"), overview.itemsPerOrder());
        assertBigDecimalEquals((BigDecimal) manual.get("items_per_order"), overview.itemsPerOrder());
    }

    @Test
    void shouldMatchRevenuePerDishWithManualSql() {
        AnalyticsV2TimeRange timeRange = defaultRange();

        List<AnalyticsV2DishMetricsDTO> metrics = analyticsV2Service.getDishMetrics(VALIDATION_RESTAURANT_ID, timeRange);
        Map<Long, AnalyticsV2DishMetricsDTO> metricsByDishId = metrics.stream()
                .collect(Collectors.toMap(AnalyticsV2DishMetricsDTO::dishId, Function.identity()));

        List<Map<String, Object>> manualRows = namedParameterJdbcTemplate.queryForList(
                """
                with qualified_orders as (
                    select o.id
                    from customer_orders o
                    where o.ristoratore_id = :restaurantId
                      and upper(coalesce(o.status, '')) in ('PAGATO', 'PAID', 'COMPLETED')
                      and o.created_at >= :dateFromInclusive
                      and o.created_at < :dateToExclusive
                ), total_orders as (
                    select count(*) as total_orders
                    from qualified_orders
                ), dish_rows as (
                    select
                        oi.piatto_id as dish_id,
                        count(distinct oi.ordine_id) as order_count,
                        coalesce(sum(oi.quantity), 0) as quantity_sold,
                        coalesce(sum(oi.quantity * oi.prezzo_unitario), 0) as revenue_per_dish
                    from customer_order_items oi
                    join qualified_orders qo on qo.id = oi.ordine_id
                    group by oi.piatto_id
                )
                select
                    p.id as dish_id,
                    coalesce(dr.order_count, 0) as order_count,
                    coalesce(dr.quantity_sold, 0) as quantity_sold,
                    coalesce(dr.revenue_per_dish, 0) as revenue_per_dish,
                    case when to2.total_orders = 0 then 0 else coalesce(dr.order_count, 0)::numeric / to2.total_orders end as order_frequency_per_dish
                from piatto p
                cross join total_orders to2
                left join dish_rows dr on dr.dish_id = p.id
                where p.ristoratore_id = :restaurantId
                order by p.id asc
                """,
                parameters(VALIDATION_RESTAURANT_ID, timeRange)
        );

        assertEquals(4, manualRows.size());

        for (Map<String, Object> manualRow : manualRows) {
            Long dishId = ((Number) manualRow.get("dish_id")).longValue();
            AnalyticsV2DishMetricsDTO actual = metricsByDishId.get(dishId);
            assertFalse(actual == null, "Missing dish metrics for dishId=" + dishId);
            assertEquals(((Number) manualRow.get("order_count")).longValue(), actual.orderCount(), "orderCount mismatch for dishId=" + dishId);
            assertEquals(((Number) manualRow.get("quantity_sold")).longValue(), actual.quantitySold(), "quantitySold mismatch for dishId=" + dishId);
            assertBigDecimalEquals((BigDecimal) manualRow.get("revenue_per_dish"), actual.revenuePerDish());
            assertBigDecimalEquals((BigDecimal) manualRow.get("order_frequency_per_dish"), actual.orderFrequencyPerDish());
        }

        assertBigDecimalEquals(new BigDecimal("30.00"), metricsByDishId.get(VALIDATION_DISH_A_ID).revenuePerDish());
        assertBigDecimalEquals(new BigDecimal("15.00"), metricsByDishId.get(VALIDATION_DISH_B_ID).revenuePerDish());
        assertBigDecimalEquals(new BigDecimal("14.00"), metricsByDishId.get(VALIDATION_DISH_C_ID).revenuePerDish());
        assertBigDecimalEquals(new BigDecimal("4.00"), metricsByDishId.get(VALIDATION_DISH_D_ID).revenuePerDish());
    }

    @Test
    void shouldMatchManualCoOccurrencePairs() {
        AnalyticsV2TimeRange timeRange = defaultRange();

        List<RelatedDishV2DTO> related = coOccurrenceV2Service.getTopRelated(
                VALIDATION_RESTAURANT_ID,
                VALIDATION_DISH_A_ID,
                10,
                true,
                timeRange
        );

        List<Map<String, Object>> manualRows = namedParameterJdbcTemplate.queryForList(
                """
                with qualified_orders as (
                    select o.id
                    from customer_orders o
                    where o.ristoratore_id = :restaurantId
                      and upper(coalesce(o.status, '')) in ('PAGATO', 'PAID', 'COMPLETED')
                      and o.created_at >= :dateFromInclusive
                      and o.created_at < :dateToExclusive
                ), order_dishes as (
                    select distinct oi.ordine_id, oi.piatto_id
                    from customer_order_items oi
                    join qualified_orders qo on qo.id = oi.ordine_id
                ), base_orders as (
                    select count(distinct od.ordine_id) as base_order_count
                    from order_dishes od
                    where od.piatto_id = :dishId
                ), dish_order_counts as (
                    select od.piatto_id as dish_id, count(distinct od.ordine_id) as dish_order_count
                    from order_dishes od
                    group by od.piatto_id
                ), related_pairs as (
                    select od2.piatto_id as related_dish_id, count(*) as pair_order_count
                    from order_dishes od1
                    join order_dishes od2 on od1.ordine_id = od2.ordine_id and od1.piatto_id <> od2.piatto_id
                    where od1.piatto_id = :dishId
                    group by od2.piatto_id
                ), total_orders as (
                    select count(*) as total_order_count from qualified_orders
                )
                select
                    rp.related_dish_id,
                    rp.pair_order_count,
                    coalesce(doc.dish_order_count, 0) as related_dish_order_count,
                    case when bo.base_order_count = 0 then 0 else rp.pair_order_count::numeric / bo.base_order_count end as affinity,
                    case when bo.base_order_count = 0 or coalesce(doc.dish_order_count, 0) = 0 or to2.total_order_count = 0 then 0
                         else (rp.pair_order_count::numeric * to2.total_order_count) / (bo.base_order_count * doc.dish_order_count)
                    end as lift
                from related_pairs rp
                left join dish_order_counts doc on doc.dish_id = rp.related_dish_id
                cross join base_orders bo
                cross join total_orders to2
                order by rp.pair_order_count desc, affinity desc, lift desc, rp.related_dish_id asc
                """,
                parameters(VALIDATION_RESTAURANT_ID, timeRange).addValue("dishId", VALIDATION_DISH_A_ID)
        );

        assertEquals(2, related.size());
        assertEquals(manualRows.size(), related.size());

        for (int i = 0; i < manualRows.size(); i++) {
            Map<String, Object> manualRow = manualRows.get(i);
            RelatedDishV2DTO actual = related.get(i);
            assertEquals(((Number) manualRow.get("related_dish_id")).longValue(), actual.dishId());
            assertEquals(((Number) manualRow.get("pair_order_count")).longValue(), actual.pairOrderCount());
            assertEquals(((Number) manualRow.get("related_dish_order_count")).longValue(), actual.relatedDishOrderCount());
            assertBigDecimalEquals(((BigDecimal) manualRow.get("affinity")).setScale(4, java.math.RoundingMode.HALF_UP), actual.affinity());
            assertBigDecimalEquals(((BigDecimal) manualRow.get("lift")).setScale(4, java.math.RoundingMode.HALF_UP), actual.lift());
        }

        assertEquals(List.of(VALIDATION_DISH_B_ID, VALIDATION_DISH_C_ID), related.stream().map(RelatedDishV2DTO::dishId).toList());
        assertBigDecimalEquals(new BigDecimal("0.6667"), related.get(0).affinity());
        assertBigDecimalEquals(new BigDecimal("1.3333"), related.get(0).lift());
    }

    private MapSqlParameterSource parameters(long restaurantId, AnalyticsV2TimeRange timeRange) {
        return timeRange.applyTo(new MapSqlParameterSource("restaurantId", restaurantId));
    }
}