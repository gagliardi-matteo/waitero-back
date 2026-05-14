package com.waitero.back.controller;

import com.waitero.back.dto.admin.billing.AdminUpsertBillingAccountRequest;
import com.waitero.back.dto.admin.billing.BillingGlobalConfigDto;
import com.waitero.back.dto.admin.billing.BillingAccountDto;
import com.waitero.back.dto.admin.billing.BillingReviewActionRequest;
import com.waitero.back.dto.admin.billing.BillingReviewDetailDto;
import com.waitero.back.dto.admin.billing.BillingReviewSummaryDto;
import com.waitero.back.dto.admin.billing.UpdateBillingGlobalConfigRequest;
import com.waitero.back.service.BillingAccountService;
import com.waitero.back.service.BillingGlobalConfigService;
import com.waitero.back.service.BillingReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
public class AdminBillingController {

    private final BillingAccountService billingAccountService;
    private final BillingGlobalConfigService billingGlobalConfigService;
    private final BillingReviewService billingReviewService;

    @GetMapping("/config")
    public BillingGlobalConfigDto getGlobalConfig() {
        return billingGlobalConfigService.getConfig();
    }

    @PutMapping("/config")
    public BillingGlobalConfigDto updateGlobalConfig(@RequestBody UpdateBillingGlobalConfigRequest request) {
        return billingGlobalConfigService.updateConfig(request);
    }

    @PutMapping("/accounts/{restaurantId}")
    public BillingAccountDto upsertAccount(
            @PathVariable Long restaurantId,
            @RequestBody AdminUpsertBillingAccountRequest request
    ) {
        return billingAccountService.upsertForAdmin(restaurantId, request);
    }

    @GetMapping("/accounts/{restaurantId}")
    public BillingAccountDto getAccount(@PathVariable Long restaurantId) {
        return billingAccountService.getAdminAccount(restaurantId);
    }

    @GetMapping("/reviews/pending")
    public List<BillingReviewSummaryDto> getPendingReviews() {
        return billingReviewService.findPendingReviews();
    }

    @GetMapping("/reviews/restaurant/{restaurantId}")
    public List<BillingReviewSummaryDto> getRestaurantReviews(@PathVariable Long restaurantId) {
        return billingReviewService.findReviewsForRestaurant(restaurantId);
    }

    @GetMapping("/reviews/{id}")
    public BillingReviewDetailDto getReview(@PathVariable Long id) {
        return billingReviewService.getReviewDetail(id);
    }

    @PostMapping("/reviews/{id}/approve")
    public BillingReviewDetailDto approveReview(
            @PathVariable Long id,
            @RequestBody(required = false) BillingReviewActionRequest request
    ) {
        return billingReviewService.approveReview(id, request);
    }

    @PostMapping("/reviews/{id}/finalize")
    public BillingReviewDetailDto finalizeReview(
            @PathVariable Long id,
            @RequestBody(required = false) BillingReviewActionRequest request
    ) {
        return billingReviewService.finalizeReview(id, request);
    }

    @PostMapping("/reviews/{id}/reject")
    public BillingReviewDetailDto rejectReview(
            @PathVariable Long id,
            @RequestBody(required = false) BillingReviewActionRequest request
    ) {
        return billingReviewService.rejectReview(id, request);
    }

    @PostMapping("/reviews/{id}/sync-stripe-status")
    public BillingReviewDetailDto syncReviewFromStripe(
            @PathVariable Long id,
            @RequestBody(required = false) BillingReviewActionRequest request
    ) {
        return billingReviewService.syncReviewStatusFromStripe(id, request);
    }
}
