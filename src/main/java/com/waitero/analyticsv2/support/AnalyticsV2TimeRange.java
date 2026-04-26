package com.waitero.analyticsv2.support;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record AnalyticsV2TimeRange(
        LocalDate dateFrom,
        LocalDate dateTo
) {

    public static AnalyticsV2TimeRange resolve(LocalDate requestedDateFrom, LocalDate requestedDateTo, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        LocalDate normalizedDateTo = requestedDateTo != null ? requestedDateTo : today;
        LocalDate normalizedDateFrom = requestedDateFrom != null ? requestedDateFrom : normalizedDateTo.minusDays(29);

        if (normalizedDateFrom.isAfter(normalizedDateTo)) {
            LocalDate swappedFrom = normalizedDateTo;
            normalizedDateTo = normalizedDateFrom;
            normalizedDateFrom = swappedFrom;
        }

        return new AnalyticsV2TimeRange(normalizedDateFrom, normalizedDateTo);
    }

    public LocalDateTime dateFromInclusive() {
        return dateFrom.atStartOfDay();
    }

    public LocalDateTime dateToExclusive() {
        return dateTo.plusDays(1).atStartOfDay();
    }

    public MapSqlParameterSource applyTo(MapSqlParameterSource parameters) {
        return parameters
                .addValue("dateFromInclusive", dateFromInclusive())
                .addValue("dateToExclusive", dateToExclusive());
    }
}