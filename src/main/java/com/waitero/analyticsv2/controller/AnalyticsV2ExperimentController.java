package com.waitero.analyticsv2.controller;

import com.waitero.analyticsv2.dto.AnalyticsV2ExperimentResultsDTO;
import com.waitero.analyticsv2.service.AnalyticsV2ExperimentResultsService;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.security.AccessContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v2/experiment")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AnalyticsV2ExperimentController {

    private final AnalyticsV2ExperimentResultsService analyticsV2ExperimentResultsService;
    private final AccessContextService accessContextService;
    private final AnalyticsV2TimeRangeResolver timeRangeResolver;

    @GetMapping("/results")
    public AnalyticsV2ExperimentResultsDTO getResults(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return analyticsV2ExperimentResultsService.getResults(accessContextService.getActingRestaurantIdOrThrow(), timeRange);
    }
}
