package com.waitero.analyticsv2.controller;

import com.waitero.analyticsv2.dto.AnalyticsV2DashboardDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2DishMetricsDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2OverviewDTO;
import com.waitero.analyticsv2.service.AnalyticsV2Service;
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
import java.util.List;

@RestController
@RequestMapping("/api/v2/analytics")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AnalyticsV2Controller {

    private final AnalyticsV2Service analyticsV2Service;
    private final AccessContextService accessContextService;
    private final AnalyticsV2TimeRangeResolver timeRangeResolver;

    // Ritorna l'overview V2 con lo stesso perimetro dati ma logica aggiornata.
    @GetMapping("/overview")
    public AnalyticsV2OverviewDTO getOverview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return analyticsV2Service.getOverview(accessContextService.getActingRestaurantIdOrThrow(), timeRange);
    }

    // Ritorna le metriche V2 per ciascun piatto.
    @GetMapping("/dishes")
    public List<AnalyticsV2DishMetricsDTO> getDishMetrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return analyticsV2Service.getDishMetrics(accessContextService.getActingRestaurantIdOrThrow(), timeRange);
    }

    // Ritorna il dashboard V2 usato dalla nuova UI analytics.
    @GetMapping("/dashboard")
    public AnalyticsV2DashboardDTO getDashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return analyticsV2Service.getDashboard(accessContextService.getActingRestaurantIdOrThrow(), timeRange);
    }
}
