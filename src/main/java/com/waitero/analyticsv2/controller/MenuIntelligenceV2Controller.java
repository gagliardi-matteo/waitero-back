package com.waitero.analyticsv2.controller;

import com.waitero.analyticsv2.dto.MenuRankedDishV2DTO;
import com.waitero.analyticsv2.dto.RelatedDishV2DTO;
import com.waitero.analyticsv2.service.CoOccurrenceV2Service;
import com.waitero.analyticsv2.service.MenuIntelligenceV2Service;
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
@RequestMapping("/api/v2/menu-intelligence")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MenuIntelligenceV2Controller {

    private final MenuIntelligenceV2Service menuIntelligenceV2Service;
    private final CoOccurrenceV2Service coOccurrenceV2Service;
    private final AnalyticsV2TimeRangeResolver timeRangeResolver;
    private final AccessContextService accessContextService;

    // Ritorna il ranking V2 dei piatti del locale.
    @GetMapping("/ranking")
    public List<MenuRankedDishV2DTO> getRanking(
            @RequestParam Long restaurantId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return menuIntelligenceV2Service.getRankedMenu(validateRestaurantScope(restaurantId), limit, timeRange);
    }

    // Ritorna i piatti correlati a un piatto specifico.
    @GetMapping("/related/{dishId}")
    public List<RelatedDishV2DTO> getTopRelated(
            @PathVariable Long dishId,
            @RequestParam Long restaurantId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        AnalyticsV2TimeRange timeRange = timeRangeResolver.resolve(dateFrom, dateTo);
        return coOccurrenceV2Service.getTopRelated(validateRestaurantScope(restaurantId), dishId, limit, true, timeRange);
    }
    private Long validateRestaurantScope(Long restaurantId) {
        if (!restaurantId.equals(accessContextService.getActingRestaurantIdOrThrow())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "restaurantId is outside the authenticated scope");
        }
        return restaurantId;
    }
}
