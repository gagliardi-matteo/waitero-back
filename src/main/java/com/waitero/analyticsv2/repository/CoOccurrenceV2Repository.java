package com.waitero.analyticsv2.repository;

import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.DecimalScaleV2;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class CoOccurrenceV2Repository {

    private static final String QUALIFIED_ORDERS_CTE = """
            with qualified_orders as (
                select o.id
                from customer_orders o
                where o.ristoratore_id = :restaurantId
                  and upper(coalesce(o.status, '')) in ('PAGATO', 'PAID', 'COMPLETED')
                  and o.created_at >= :dateFromInclusive
                  and o.created_at < :dateToExclusive
            ), order_dishes as (
                select distinct
                    oi.ordine_id,
                    oi.piatto_id
                from customer_order_items oi
                join qualified_orders qo on qo.id = oi.ordine_id
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<RelatedDishRow> fetchTopRelatedDishes(Long restaurantId, Long dishId, boolean onlyAvailable, int limit, AnalyticsV2TimeRange timeRange) {
        return fetchTopRelatedDishesForBaseDishes(restaurantId, List.of(dishId), onlyAvailable, limit, timeRange).stream()
                .map(row -> new RelatedDishRow(
                        row.dishId(),
                        row.dishName(),
                        row.description(),
                        row.category(),
                        row.price(),
                        row.available(),
                        row.imageUrl(),
                        row.pairOrderCount(),
                        row.relatedDishOrderCount(),
                        row.affinity(),
                        row.lift()
                ))
                .toList();
    }

    public List<BaseRelatedDishRow> fetchTopRelatedDishesForBaseDishes(
            Long restaurantId,
            List<Long> dishIds,
            boolean onlyAvailable,
            int limit,
            AnalyticsV2TimeRange timeRange
    ) {
        if (dishIds == null || dishIds.isEmpty()) {
            return List.of();
        }

        String availabilityFilter = onlyAvailable ? " and coalesce(p.disponibile, false) = true " : "";
        String sql = QUALIFIED_ORDERS_CTE + """
                , base_order_counts as (
                    select
                        od.piatto_id as base_dish_id,
                        count(distinct od.ordine_id) as base_order_count
                    from order_dishes od
                    where od.piatto_id in (:dishIds)
                    group by od.piatto_id
                ), dish_order_counts as (
                    select
                        od.piatto_id as dish_id,
                        count(distinct od.ordine_id) as dish_order_count
                    from order_dishes od
                    group by od.piatto_id
                ), related_pairs as (
                    select
                        od1.piatto_id as base_dish_id,
                        od2.piatto_id as related_dish_id,
                        count(*) as pair_order_count
                    from order_dishes od1
                    join order_dishes od2
                      on od1.ordine_id = od2.ordine_id
                     and od1.piatto_id <> od2.piatto_id
                    where od1.piatto_id in (:dishIds)
                    group by od1.piatto_id, od2.piatto_id
                ), total_orders as (
                    select count(*) as total_order_count
                    from qualified_orders
                ), scored_pairs as (
                    select
                        rp.base_dish_id,
                        p.id as dish_id,
                        p.nome as dish_name,
                        p.descrizione as description,
                        cast(p.categoria as varchar) as category,
                        coalesce(p.prezzo, 0) as price,
                        coalesce(p.disponibile, false) as available,
                        p.image_url as image_url,
                        rp.pair_order_count,
                        coalesce(doc.dish_order_count, 0) as related_dish_order_count,
                        case
                            when boc.base_order_count = 0 then 0
                            else rp.pair_order_count::numeric / boc.base_order_count
                        end as affinity,
                        case
                            when boc.base_order_count = 0 or coalesce(doc.dish_order_count, 0) = 0 or to2.total_order_count = 0 then 0
                            else (rp.pair_order_count::numeric * to2.total_order_count) / (boc.base_order_count * doc.dish_order_count)
                        end as lift
                    from related_pairs rp
                    join piatto p
                      on p.id = rp.related_dish_id
                     and p.ristoratore_id = :restaurantId
                    join base_order_counts boc on boc.base_dish_id = rp.base_dish_id
                    left join dish_order_counts doc on doc.dish_id = rp.related_dish_id
                    cross join total_orders to2
                    where p.id <> rp.base_dish_id
                """ + availabilityFilter + """
                ), ranked_pairs as (
                    select
                        scored_pairs.*,
                        row_number() over (
                            partition by base_dish_id
                            order by pair_order_count desc, affinity desc, lift desc, dish_id asc
                        ) as rn
                    from scored_pairs
                )
                select
                    base_dish_id,
                    dish_id,
                    dish_name,
                    description,
                    category,
                    price,
                    available,
                    image_url,
                    pair_order_count,
                    related_dish_order_count,
                    affinity,
                    lift
                from ranked_pairs
                where rn <= :limit
                order by base_dish_id asc, rn asc
                """;

        return jdbcTemplate.query(sql, parameters(restaurantId, timeRange)
                .addValue("dishIds", dishIds)
                .addValue("limit", limit), baseRelatedDishRowMapper());
    }

    public Map<Long, BigDecimal> fetchComplementaryBoostByDish(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        String sql = QUALIFIED_ORDERS_CTE + """
                , qualified_order_count as (
                    select count(*) as total_orders
                    from qualified_orders
                ), complementary_pairs as (
                    select
                        od1.piatto_id as base_dish_id,
                        count(*) as complementary_pair_order_count
                    from order_dishes od1
                    join order_dishes od2
                      on od1.ordine_id = od2.ordine_id
                     and od1.piatto_id <> od2.piatto_id
                    join piatto suggested
                      on suggested.id = od2.piatto_id
                     and suggested.ristoratore_id = :restaurantId
                    where coalesce(suggested.disponibile, false) = true
                      and cast(suggested.categoria as varchar) in ('BEVANDA', 'CONTORNO', 'DOLCE')
                    group by od1.piatto_id
                )
                select
                    cp.base_dish_id,
                    case
                        when qoc.total_orders = 0 then 0
                        else cp.complementary_pair_order_count::numeric / qoc.total_orders
                    end as cooccurrence_boost
                from complementary_pairs cp
                cross join qualified_order_count qoc
                """;

        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, parameters(restaurantId, timeRange), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            result.put(
                    rs.getLong("base_dish_id"),
                    DecimalScaleV2.scaled(rs.getBigDecimal("cooccurrence_boost"), 4)
            );
        });
        return result;
    }

    private MapSqlParameterSource parameters(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        return timeRange.applyTo(new MapSqlParameterSource("restaurantId", restaurantId));
    }

    private RowMapper<BaseRelatedDishRow> baseRelatedDishRowMapper() {
        return (rs, rowNum) -> new BaseRelatedDishRow(
                rs.getLong("base_dish_id"),
                rs.getLong("dish_id"),
                rs.getString("dish_name"),
                rs.getString("description"),
                rs.getString("category"),
                DecimalScaleV2.money(rs.getBigDecimal("price")),
                rs.getBoolean("available"),
                rs.getString("image_url"),
                rs.getLong("pair_order_count"),
                rs.getLong("related_dish_order_count"),
                DecimalScaleV2.scaled(rs.getBigDecimal("affinity"), 4),
                DecimalScaleV2.scaled(rs.getBigDecimal("lift"), 4)
        );
    }

    public record RelatedDishRow(
            Long dishId,
            String dishName,
            String description,
            String category,
            BigDecimal price,
            Boolean available,
            String imageUrl,
            long pairOrderCount,
            long relatedDishOrderCount,
            BigDecimal affinity,
            BigDecimal lift
    ) {
    }

    public record BaseRelatedDishRow(
            Long baseDishId,
            Long dishId,
            String dishName,
            String description,
            String category,
            BigDecimal price,
            Boolean available,
            String imageUrl,
            long pairOrderCount,
            long relatedDishOrderCount,
            BigDecimal affinity,
            BigDecimal lift
    ) {
    }
}