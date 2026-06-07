package com.waitero.back.controller;

import com.waitero.back.dto.CustomerLegalAcceptanceRequest;
import com.waitero.back.dto.LegalStatusResponse;
import com.waitero.back.service.LegalAcceptanceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/legal")
@RequiredArgsConstructor
public class CustomerLegalAcceptanceController {

    private final LegalAcceptanceService legalAcceptanceService;

    @GetMapping("/status")
    public LegalStatusResponse status(@RequestParam(required = false) String sessionId) {
        return legalAcceptanceService.getCustomerStatus(sessionId);
    }

    @PostMapping("/accept")
    public ResponseEntity<LegalStatusResponse> accept(
            @RequestBody CustomerLegalAcceptanceRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(legalAcceptanceService.acceptCustomer(request, httpRequest));
    }
}
