package com.waitero.back.controller;

import com.waitero.back.dto.DishActionPlanDTO;
import com.waitero.back.dto.DishIntelligenceDTO;
import com.waitero.back.dto.DishInsightApplyResultDTO;
import com.waitero.back.dto.InsightDTO;
import com.waitero.back.security.AccessContextService;
import com.waitero.back.service.DishIntelligenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/dish-intelligence")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DishIntelligenceController {

    private final DishIntelligenceService dishIntelligenceService;
    private final AccessContextService accessContextService;

    // Ritorna la lista di insight per tutti i piatti del locale.
    @GetMapping
    public List<DishIntelligenceDTO> getDishIntelligence(@RequestParam("ristoranteId") Long restaurantId) {
        return dishIntelligenceService.getDishIntelligence(validateRestaurantScope(restaurantId));
    }

    // Ritorna il piano d'azione generato dagli insight.
    @GetMapping("/action-plan")
    public DishActionPlanDTO getDishActionPlan(@RequestParam("ristoranteId") Long restaurantId) {
        return dishIntelligenceService.getDishActionPlan(validateRestaurantScope(restaurantId));
    }

    // Ritorna solo la lista piatta degli insight, usata dalla UI per i tooltip e le card.
    @GetMapping("/insights")
    public List<InsightDTO> getDishInsights(@RequestParam("ristoranteId") Long restaurantId) {
        return dishIntelligenceService.getDishInsights(validateRestaurantScope(restaurantId));
    }

    // Applica gli insight attualmente calcolati al menu del locale.
    @PostMapping("/insights/apply")
    public DishInsightApplyResultDTO applyDishInsights(@RequestParam("ristoranteId") Long restaurantId) {
        return dishIntelligenceService.applyDishInsights(validateRestaurantScope(restaurantId));
    }

    private Long validateRestaurantScope(Long restaurantId) {
        if (restaurantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ristoranteId is required");
        }
        if (accessContextService.isMaster()) {
            return restaurantId;
        }
        Long actingRestaurantId = accessContextService.getActingRestaurantIdOrThrow();
        if (!Objects.equals(actingRestaurantId, restaurantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ristoranteId is outside the authenticated scope");
        }
        return restaurantId;
    }
}
