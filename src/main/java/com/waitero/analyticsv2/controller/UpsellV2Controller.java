package com.waitero.analyticsv2.controller;

import com.waitero.analyticsv2.dto.UpsellSuggestionV2DTO;
import com.waitero.analyticsv2.service.UpsellV2Service;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v2/upsell")
@RequiredArgsConstructor
public class UpsellV2Controller {

    private final UpsellV2Service upsellV2Service;
    private final AnalyticsV2TimeRangeResolver timeRangeResolver;

    @GetMapping("/dish/{dishId}")
    public List<UpsellSuggestionV2DTO> getDishSuggestions(
            @PathVariable Long dishId,
            @RequestParam Long restaurantId,
            @RequestParam(defaultValue = "4") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return upsellV2Service.getDishSuggestions(restaurantId, dishId, limit, timeRange);
    }

    @GetMapping("/cart")
    public List<UpsellSuggestionV2DTO> getCartSuggestions(
            @RequestParam Long restaurantId,
            @RequestParam List<Long> dishIds,
            @RequestParam(defaultValue = "4") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return upsellV2Service.getCartSuggestions(restaurantId, dishIds, limit, timeRange);
    }
}