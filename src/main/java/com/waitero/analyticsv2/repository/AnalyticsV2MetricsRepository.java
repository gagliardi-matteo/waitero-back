package com.waitero.analyticsv2.repository;

import com.waitero.analyticsv2.dto.AnalyticsV2DishMetricsDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2OverviewDTO;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.DecimalScaleV2;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class AnalyticsV2MetricsRepository {

    private static final String QUALIFIED_ORDERS_CTE = """
            with qualified_orders as (
                select o.id
                from customer_orders o
                where o.ristoratore_id = :restaurantId
                  and upper(coalesce(o.status, '')) in ('PAGATO', 'PAID', 'COMPLETED')
                  and o.created_at >= :dateFromInclusive
                  and o.created_at < :dateToExclusive
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AnalyticsV2OverviewDTO fetchOverview(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        String sql = QUALIFIED_ORDERS_CTE + """
                , order_totals as (
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
                """;

        return jdbcTemplate.queryForObject(sql, parameters(restaurantId, timeRange), (rs, rowNum) ->
                new AnalyticsV2OverviewDTO(
                        rs.getLong("total_orders"),
                        DecimalScaleV2.money(rs.getBigDecimal("total_revenue")),
                        DecimalScaleV2.money(rs.getBigDecimal("average_order_value")),
                        DecimalScaleV2.scaled(rs.getBigDecimal("items_per_order"), 4)
                )
        );
    }

    public List<AnalyticsV2DishMetricsDTO> fetchDishMetrics(Long restaurantId, boolean onlyAvailable, AnalyticsV2TimeRange timeRange) {
        String availabilityFilter = onlyAvailable ? " and coalesce(p.disponibile, false) = true " : "";
        String sql = QUALIFIED_ORDERS_CTE + """
                , total_orders as (
                    select count(*) as total_orders
                    from qualified_orders
                ), dish_order_rows as (
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
                    p.nome as dish_name,
                    p.descrizione as description,
                    cast(p.categoria as varchar) as category,
                    coalesce(p.prezzo, 0) as current_price,
                    coalesce(p.disponibile, false) as available,
                    p.image_url as image_url,
                    coalesce(dor.order_count, 0) as order_count,
                    coalesce(dor.quantity_sold, 0) as quantity_sold,
                    coalesce(dor.revenue_per_dish, 0) as revenue_per_dish,
                    case
                        when to2.total_orders = 0 then 0
                        else coalesce(dor.order_count, 0)::numeric / to2.total_orders
                    end as order_frequency_per_dish
                from piatto p
                cross join total_orders to2
                left join dish_order_rows dor on dor.dish_id = p.id
                where p.ristoratore_id = :restaurantId
                """ + availabilityFilter + """
                order by coalesce(dor.revenue_per_dish, 0) desc, coalesce(dor.order_count, 0) desc, p.id asc
                """;

        return jdbcTemplate.query(sql, parameters(restaurantId, timeRange), (rs, rowNum) ->
                new AnalyticsV2DishMetricsDTO(
                        rs.getLong("dish_id"),
                        rs.getString("dish_name"),
                        rs.getString("description"),
                        rs.getString("category"),
                        DecimalScaleV2.money(rs.getBigDecimal("current_price")),
                        rs.getBoolean("available"),
                        rs.getString("image_url"),
                        rs.getLong("order_count"),
                        rs.getLong("quantity_sold"),
                        DecimalScaleV2.money(rs.getBigDecimal("revenue_per_dish")),
                        DecimalScaleV2.scaled(rs.getBigDecimal("order_frequency_per_dish"), 4)
                )
        );
    }

    private MapSqlParameterSource parameters(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        return timeRange.applyTo(new MapSqlParameterSource("restaurantId", restaurantId));
    }
}