package com.waitero.analyticsv2.controller;

import com.waitero.analyticsv2.dto.UpsellSuggestionV2DTO;
import com.waitero.analyticsv2.service.UpsellV2Service;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import lombok.RequiredArgsConstructor;
import com.waitero.back.security.AccessContextService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
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
@PreAuthorize("isAuthenticated()")
public class UpsellV2Controller {

    private final UpsellV2Service upsellV2Service;
    private final AnalyticsV2TimeRangeResolver timeRangeResolver;
    private final AccessContextService accessContextService;

    // Ritorna i suggerimenti upsell associati a un singolo piatto.
    @GetMapping("/dish/{dishId}")
    public List<UpsellSuggestionV2DTO> getDishSuggestions(
            @PathVariable Long dishId,
            @RequestParam Long restaurantId,
            @RequestParam(defaultValue = "4") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return upsellV2Service.getDishSuggestions(validateRestaurantScope(restaurantId), dishId, limit, timeRange);
    }

    // Ritorna i suggerimenti upsell del carrello corrente.
    @GetMapping("/cart")
    public List<UpsellSuggestionV2DTO> getCartSuggestions(
            @RequestParam Long restaurantId,
            @RequestParam List<Long> dishIds,
            @RequestParam(defaultValue = "4") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return upsellV2Service.getCartSuggestions(validateRestaurantScope(restaurantId), dishIds, limit, timeRange);
    }
    private Long validateRestaurantScope(Long restaurantId) {
        if (!restaurantId.equals(accessContextService.getActingRestaurantIdOrThrow())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "restaurantId is outside the authenticated scope");
        }
        return restaurantId;
    }
}
