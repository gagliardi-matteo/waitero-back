package com.waitero.back.controller;

import com.waitero.back.dto.billing.CreateSetupIntentResponse;
import com.waitero.back.dto.billing.RestaurantBillingAccountDto;
import com.waitero.back.service.BillingAccountService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingAccountService billingAccountService;

    @GetMapping("/account")
    public RestaurantBillingAccountDto getAccount() {
        return billingAccountService.getCurrentRestaurantAccount();
    }

    @PostMapping("/setup-intents")
    public CreateSetupIntentResponse createSetupIntent() {
        return billingAccountService.createCurrentRestaurantSetupIntent();
    }

    @PostMapping("/setup-intents/{setupIntentId}/complete")
    public RestaurantBillingAccountDto completeSetupIntent(
            @PathVariable String setupIntentId,
            @RequestBody(required = false) CompleteSetupIntentRequest request
    ) {
        String effectiveSetupIntentId = setupIntentId;
        if (request != null && request.getSetupIntentId() != null && !request.getSetupIntentId().isBlank()) {
            effectiveSetupIntentId = request.getSetupIntentId();
        }
        return billingAccountService.completeCurrentRestaurantSetupIntent(effectiveSetupIntentId);
    }

    @Getter
    @Setter
    public static class CompleteSetupIntentRequest {
        private String setupIntentId;
    }
}
