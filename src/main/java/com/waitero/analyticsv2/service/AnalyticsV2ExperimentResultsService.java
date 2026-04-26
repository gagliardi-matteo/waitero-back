package com.waitero.analyticsv2.service;

import com.waitero.analyticsv2.dto.AnalyticsV2ExperimentGroupDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2ExperimentResultsDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2ExperimentSignificanceDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2ExperimentUpliftDTO;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
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
public class AnalyticsV2ExperimentResultsService {

    private static final String RESULTS_SQL = """
            with order_rows as (
                select
                    case
                        when upper(coalesce(nullif(btrim(co.variant), ''), '')) = 'A' then 'A'
                        when upper(coalesce(nullif(btrim(co.variant), ''), '')) = 'B' then 'B'
                        else null
                    end as variant,
                    coalesce(co.totale, coalesce(sum(coi.quantity * coi.prezzo_unitario), 0)) as total_price,
                    coalesce(co.item_count, coalesce(sum(coi.quantity), 0)) as item_count
                from customer_orders co
                left join customer_order_items coi on coi.ordine_id = co.id
                where co.ristoratore_id = :restaurantId
                  and upper(coalesce(co.status, '')) in ('PAGATO', 'PAID', 'COMPLETED')
                  and co.created_at >= :dateFromInclusive
                  and co.created_at < :dateToExclusive
                group by co.id, co.variant, co.totale, co.item_count
            )
            select
                variant,
                count(*) as orders,
                coalesce(sum(total_price), 0) as total_revenue,
                coalesce(avg(total_price), 0) as average_order_value,
                coalesce(avg(item_count), 0) as items_per_order,
                var_samp(total_price) as aov_variance
            from order_rows
            where variant is not null
            group by variant
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AnalyticsV2ExperimentResultsDTO getResults(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        if (restaurantId == null) {
            return emptyResults(timeRange);
        }

        Map<String, ExperimentRow> rowsByVariant = new LinkedHashMap<>();
        for (ExperimentRow row : jdbcTemplate.query(RESULTS_SQL, parameters(restaurantId, timeRange), (rs, rowNum) -> new ExperimentRow(
                rs.getString("variant"),
                rs.getLong("orders"),
                money(rs.getBigDecimal("total_revenue")),
                money(rs.getBigDecimal("average_order_value")),
                scaled(rs.getBigDecimal("items_per_order"), 4),
                rs.getBigDecimal("aov_variance") == null ? null : scaled(rs.getBigDecimal("aov_variance"), 6)
        ))) {
            rowsByVariant.put(normalizeVariant(row.variant()), row);
        }

        ExperimentRow rowA = rowsByVariant.getOrDefault("A", ExperimentRow.empty("A"));
        ExperimentRow rowB = rowsByVariant.getOrDefault("B", ExperimentRow.empty("B"));

        AnalyticsV2ExperimentGroupDTO groupA = toGroup(rowA);
        AnalyticsV2ExperimentGroupDTO groupB = toGroup(rowB);
        AnalyticsV2ExperimentUpliftDTO uplift = new AnalyticsV2ExperimentUpliftDTO(
                money(rowB.totalRevenue.subtract(rowA.totalRevenue)),
                percentChange(rowA.totalRevenue, rowB.totalRevenue, 4),
                money(rowB.averageOrderValue.subtract(rowA.averageOrderValue)),
                percentChange(rowA.averageOrderValue, rowB.averageOrderValue, 4),
                scaled(rowB.itemsPerOrder.subtract(rowA.itemsPerOrder), 4),
                percentChange(rowA.itemsPerOrder, rowB.itemsPerOrder, 4)
        );
        AnalyticsV2ExperimentSignificanceDTO significance = computeAovSignificance(rowA, rowB);

        return new AnalyticsV2ExperimentResultsDTO(
                timeRange.dateFrom(),
                timeRange.dateTo(),
                groupA,
                groupB,
                uplift,
                significance
        );
    }

    private AnalyticsV2ExperimentResultsDTO emptyResults(AnalyticsV2TimeRange timeRange) {
        ExperimentRow emptyA = ExperimentRow.empty("A");
        ExperimentRow emptyB = ExperimentRow.empty("B");
        return new AnalyticsV2ExperimentResultsDTO(
                timeRange.dateFrom(),
                timeRange.dateTo(),
                toGroup(emptyA),
                toGroup(emptyB),
                new AnalyticsV2ExperimentUpliftDTO(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4)),
                new AnalyticsV2ExperimentSignificanceDTO("average_order_value", "two_sided_z_test", null, null, false, false)
        );
    }

    private AnalyticsV2ExperimentGroupDTO toGroup(ExperimentRow row) {
        return new AnalyticsV2ExperimentGroupDTO(
                row.orders,
                row.totalRevenue,
                row.averageOrderValue,
                row.itemsPerOrder
        );
    }

    private AnalyticsV2ExperimentSignificanceDTO computeAovSignificance(ExperimentRow rowA, ExperimentRow rowB) {
        boolean sufficientSample = rowA.orders >= 2 && rowB.orders >= 2;
        if (!sufficientSample) {
            return new AnalyticsV2ExperimentSignificanceDTO("average_order_value", "two_sided_z_test", null, null, false, false);
        }

        double varianceA = rowA.aovVariance == null ? 0.0d : rowA.aovVariance.doubleValue();
        double varianceB = rowB.aovVariance == null ? 0.0d : rowB.aovVariance.doubleValue();
        double meanA = rowA.averageOrderValue.doubleValue();
        double meanB = rowB.averageOrderValue.doubleValue();
        double standardError = Math.sqrt((varianceA / rowA.orders) + (varianceB / rowB.orders));

        if (standardError == 0.0d) {
            BigDecimal pValue = Double.compare(meanA, meanB) == 0
                    ? scaled(BigDecimal.ONE, 6)
                    : BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
            return new AnalyticsV2ExperimentSignificanceDTO(
                    "average_order_value",
                    "two_sided_z_test",
                    null,
                    pValue,
                    pValue.compareTo(new BigDecimal("0.050000")) < 0,
                    true
            );
        }

        double zScore = (meanB - meanA) / standardError;
        double pValue = Math.max(0.0d, Math.min(1.0d, 2.0d * (1.0d - normalCdf(Math.abs(zScore)))));
        return new AnalyticsV2ExperimentSignificanceDTO(
                "average_order_value",
                "two_sided_z_test",
                scaled(BigDecimal.valueOf(zScore), 4),
                scaled(BigDecimal.valueOf(pValue), 6),
                pValue < 0.05d,
                true
        );
    }

    private double normalCdf(double value) {
        return 0.5d * (1.0d + erf(value / Math.sqrt(2.0d)));
    }

    private double erf(double value) {
        double sign = value < 0 ? -1.0d : 1.0d;
        double x = Math.abs(value);
        double t = 1.0d / (1.0d + 0.3275911d * x);
        double y = 1.0d - (((((1.061405429d * t) - 1.453152027d) * t + 1.421413741d) * t - 0.284496736d) * t + 0.254829592d) * t * Math.exp(-x * x);
        return sign * y;
    }

    private BigDecimal percentChange(BigDecimal base, BigDecimal target, int scale) {
        if (base == null || target == null) {
            return null;
        }
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            return target.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP) : null;
        }
        return target.subtract(base).divide(base, scale, RoundingMode.HALF_UP);
    }

    private String normalizeVariant(String variant) {
        return "B".equalsIgnoreCase(variant) ? "B" : "A";
    }

    private BigDecimal money(BigDecimal value) {
        return scaled(value, 2);
    }

    private BigDecimal scaled(BigDecimal value, int scale) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    private MapSqlParameterSource parameters(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        return timeRange.applyTo(new MapSqlParameterSource("restaurantId", restaurantId));
    }

    private record ExperimentRow(
            String variant,
            long orders,
            BigDecimal totalRevenue,
            BigDecimal averageOrderValue,
            BigDecimal itemsPerOrder,
            BigDecimal aovVariance
    ) {
        private static ExperimentRow empty(String variant) {
            return new ExperimentRow(
                    variant,
                    0L,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                    null
            );
        }
    }
}
