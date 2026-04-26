package com.waitero.back.controller;

import com.waitero.back.dto.ExperimentAnalysisResponseDTO;
import com.waitero.back.security.AccessContextService;
import com.waitero.back.service.ExperimentIntelligenceService;
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
@RequestMapping("/api/experiment")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ExperimentAnalysisController {

    private final ExperimentIntelligenceService experimentIntelligenceService;
    private final AccessContextService accessContextService;

    @GetMapping("/analysis")
    public ExperimentAnalysisResponseDTO getExperimentAnalysis(
            @RequestParam("ristoranteId") Long restaurantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        return ExperimentAnalysisResponseDTO.from(
                experimentIntelligenceService.getExperimentAnalysis(validateRestaurantScope(restaurantId), dateFrom, dateTo)
        );
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
