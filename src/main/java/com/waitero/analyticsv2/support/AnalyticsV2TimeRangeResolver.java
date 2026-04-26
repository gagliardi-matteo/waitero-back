package com.waitero.analyticsv2.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class AnalyticsV2TimeRangeResolver {

    private final Clock analyticsV2Clock;

    public AnalyticsV2TimeRange resolve(LocalDate dateFrom, LocalDate dateTo) {
        return AnalyticsV2TimeRange.resolve(dateFrom, dateTo, analyticsV2Clock);
    }
}