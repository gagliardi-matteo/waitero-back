package com.waitero.back.controller;

import com.waitero.back.dto.CustomerLegalAcceptanceRequest;
import com.waitero.back.dto.LegalConfigResponse;
import com.waitero.back.dto.LegalStatusResponse;
import com.waitero.back.service.LegalAcceptanceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/legal")
@RequiredArgsConstructor
public class LegalAcceptanceController {

    private final LegalAcceptanceService legalAcceptanceService;

    @GetMapping("/config")
    public LegalConfigResponse config() {
        return legalAcceptanceService.getConfig();
    }

    @GetMapping("/backoffice/status")
    public LegalStatusResponse backofficeStatus() {
        return legalAcceptanceService.getBackofficeStatus();
    }

    @PostMapping("/backoffice/accept")
    public LegalStatusResponse acceptBackoffice(HttpServletRequest request) {
        return legalAcceptanceService.acceptBackoffice(request);
    }

    @GetMapping("/customer/status")
    public LegalStatusResponse customerStatus(@RequestParam(required = false) String sessionId) {
        return legalAcceptanceService.getCustomerStatus(sessionId);
    }

    @PostMapping("/customer/accept")
    public ResponseEntity<LegalStatusResponse> acceptCustomer(
            @RequestBody CustomerLegalAcceptanceRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(legalAcceptanceService.acceptCustomer(request, httpRequest));
    }
}
