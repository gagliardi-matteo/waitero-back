package com.waitero.back.service;

import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.back.dto.ExperimentVariantPerformanceDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExperimentAnalyticsService {

    private static final String SQL = """
            select
                variant,
                count(*) as total_orders,
                coalesce(sum(total_amount), 0) as total_revenue,
                count(distinct session_id) as total_sessions,
                count(distinct order_day) as active_days
            from (
                select
                    case
                        when upper(coalesce(nullif(btrim(co.variant), ''), '')) = 'A' then 'A'
                        when upper(coalesce(nullif(btrim(co.variant), ''), '')) = 'B' then 'B'
                        when upper(coalesce(nullif(btrim(co.variant), ''), '')) = 'C' then 'C'
                        else null
                    end as variant,
                    coalesce(co.totale, 0) as total_amount,
                    nullif(btrim(co.session_id), '') as session_id,
                    cast(co.created_at as date) as order_day
                from customer_orders co
                where co.ristoratore_id = :restaurantId
                  and co.created_at >= :dateFromInclusive
                  and co.created_at < :dateToExclusive
            ) rows
            where variant is not null
            group by variant
            order by variant
            """;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public Map<String, ExperimentVariantPerformanceDTO> computeMetrics(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        Map<String, ExperimentVariantPerformanceDTO> byVariant = new LinkedHashMap<>();
        byVariant.put(ExperimentService.VARIANT_A, emptyVariant(ExperimentService.VARIANT_A));
        byVariant.put(ExperimentService.VARIANT_B, emptyVariant(ExperimentService.VARIANT_B));
        byVariant.put(ExperimentService.VARIANT_C, emptyVariant(ExperimentService.VARIANT_C));

        MapSqlParameterSource parameters = timeRange.applyTo(new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId));

        List<ExperimentMetricRow> rows = namedParameterJdbcTemplate.query(
                SQL,
                parameters,
                (rs, rowNum) -> new ExperimentMetricRow(
                        rs.getString("variant"),
                        normalizeMoney(rs.getBigDecimal("total_revenue")),
                        rs.getLong("total_orders"),
                        rs.getLong("total_sessions"),
                        rs.getLong("active_days")
                )
        );

        for (ExperimentMetricRow row : rows) {
            byVariant.put(row.variant(), ExperimentVariantPerformanceDTO.builder()
                    .variant(row.variant())
                    .totalRevenue(row.totalRevenue())
                    .totalOrders(row.totalOrders())
                    .totalSessions(row.totalSessions())
                    .rps(moneyRatio(row.totalRevenue(), row.totalSessions()))
                    .aov(moneyRatio(row.totalRevenue(), row.totalOrders()))
                    .cr(decimalRatio(row.totalOrders(), row.totalSessions()))
                    .activeDays(row.activeDays())
                    .build());
        }

        return byVariant;
    }

    private ExperimentVariantPerformanceDTO emptyVariant(String variant) {
        return ExperimentVariantPerformanceDTO.builder()
                .variant(variant)
                .totalRevenue(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .totalOrders(0L)
                .totalSessions(0L)
                .rps(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .aov(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .cr(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .activeDays(0L)
                .build();
    }

    private BigDecimal moneyRatio(BigDecimal numerator, long denominator) {
        if (denominator <= 0L || numerator == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return numerator.divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimalRatio(long numerator, long denominator) {
        if (denominator <= 0L) {
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

    private record ExperimentMetricRow(
            String variant,
            BigDecimal totalRevenue,
            long totalOrders,
            long totalSessions,
            long activeDays
    ) {
    }
}
