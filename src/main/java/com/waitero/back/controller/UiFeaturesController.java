package com.waitero.back.controller;

import com.waitero.back.dto.UiFeaturesDTO;
import com.waitero.back.dto.UiFeaturesUpdateRequest;
import com.waitero.back.service.AdminAuditService;
import com.waitero.back.service.UiFeaturesService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ui/features")
@RequiredArgsConstructor
public class UiFeaturesController {

    private final UiFeaturesService uiFeaturesService;
    private final AdminAuditService adminAuditService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public UiFeaturesDTO getFeatures() {
        return uiFeaturesService.getFeatures();
    }

    @PutMapping
    @PreAuthorize("hasRole('MASTER')")
    public UiFeaturesDTO updateFeatures(@RequestBody UiFeaturesUpdateRequest request) {
        UiFeaturesDTO updated = uiFeaturesService.updateFeatures(request.explainabilityBalloonsEnabled());
        adminAuditService.record(
                "UI_TOOLTIP_VISIBILITY_UPDATED",
                null,
                "ui_feature_config",
                1L,
                Map.of("explainabilityBalloonsEnabled", request.explainabilityBalloonsEnabled())
        );
        return updated;
    }
}
