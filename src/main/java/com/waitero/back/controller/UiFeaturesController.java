package com.waitero.back.controller;

import com.waitero.back.dto.UiFeaturesDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ui/features")
@PreAuthorize("isAuthenticated()")
public class UiFeaturesController {

    @Value("${waitero.ui.explainability-balloons-enabled}")
    private boolean explainabilityBalloonsEnabled;

    @GetMapping
    public UiFeaturesDTO getFeatures() {
        return new UiFeaturesDTO(explainabilityBalloonsEnabled);
    }
}
