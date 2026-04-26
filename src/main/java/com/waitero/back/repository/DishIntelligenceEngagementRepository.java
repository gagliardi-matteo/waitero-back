package com.waitero.back.repository;

import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class DishIntelligenceEngagementRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<DishEngagementRow> fetchDishEngagement(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        String sql = """
                select
                    p.id as dish_id,
                    coalesce(ev.impressions, 0) as impressions,
                    coalesce(ev.clicks, 0) as clicks,
                    coalesce(ev.views, 0) as views
                from piatto p
                left join (
                    select
                        dish_id,
                        count(*) filter (where event_type = 'view_menu_item') as impressions,
                        count(*) filter (where event_type = 'click_dish') as clicks,
                        count(*) filter (where event_type = 'view_dish') as views
                    from event_log
                    where restaurant_id = :restaurantId
                      and created_at >= :dateFromInclusive
                      and created_at < :dateToExclusive
                      and dish_id is not null
                    group by dish_id
                ) ev on ev.dish_id = p.id
                where p.ristoratore_id = :restaurantId
                order by p.id asc
                """;

        return jdbcTemplate.query(sql, parameters(restaurantId, timeRange), (rs, rowNum) -> new DishEngagementRow(
                rs.getLong("dish_id"),
                rs.getLong("impressions"),
                rs.getLong("clicks"),
                rs.getLong("views")
        ));
    }

    private MapSqlParameterSource parameters(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        return timeRange.applyTo(new MapSqlParameterSource("restaurantId", restaurantId));
    }

    public record DishEngagementRow(
            Long dishId,
            long impressions,
            long clicks,
            long views
    ) {
    }
}
