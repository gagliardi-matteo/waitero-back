package com.waitero.analyticsv2.controller;

import com.waitero.analyticsv2.dto.AnalyticsV2DebugCompareDTO;
import com.waitero.analyticsv2.service.AnalyticsV2DebugComparisonService;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.security.AccessContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Objects;

@RestController
@RequestMapping("/api/v2/debug")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AnalyticsV2DebugController {

    private final AnalyticsV2DebugComparisonService debugComparisonService;
    private final AnalyticsV2TimeRangeResolver timeRangeResolver;
    private final AccessContextService accessContextService;

    @GetMapping("/compare")
    public AnalyticsV2DebugCompareDTO compare(
            @RequestParam Long restaurantId,
            @RequestParam(required = false) Long dishId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        ensureRestaurantAccess(restaurantId);
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return debugComparisonService.compare(restaurantId, dishId, timeRange);
    }

    private void ensureRestaurantAccess(Long restaurantId) {
        if (restaurantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "restaurantId is required");
        }
        if (accessContextService.isMaster()) {
            return;
        }

        Long actingRestaurantId = accessContextService.getActingRestaurantIdOrThrow();
        if (!Objects.equals(actingRestaurantId, restaurantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "restaurantId is outside the authenticated scope");
        }
    }
}