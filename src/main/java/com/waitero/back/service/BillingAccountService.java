package com.waitero.back.service;

import com.waitero.back.dto.admin.billing.AdminUpsertBillingAccountRequest;
import com.waitero.back.dto.admin.billing.BillingAccountDto;
import com.waitero.back.dto.billing.CreateSetupIntentResponse;
import com.waitero.back.dto.billing.RestaurantBillingAccountDto;
import com.waitero.back.entity.BillingAccount;
import com.waitero.back.entity.BillingGlobalConfig;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.repository.BillingAccountRepository;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.security.AccessContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BillingAccountService {

    private final BillingAccountRepository billingAccountRepository;
    private final RistoratoreRepository ristoratoreRepository;
    private final BillingReviewMapper billingReviewMapper;
    private final BillingGlobalConfigService billingGlobalConfigService;
    private final StripeBillingService stripeBillingService;
    private final AccessContextService accessContextService;
    private final AdminAuditService adminAuditService;

    @Transactional
    public BillingAccountDto upsertForAdmin(Long restaurantId, AdminUpsertBillingAccountRequest request) {
        if (restaurantId == null) {
            throw new RuntimeException("Locale mancante");
        }
        validateUpsertRequest(request);

        Ristoratore restaurant = ristoratoreRepository.findById(restaurantId)
                .orElseThrow(() -> new RuntimeException("Locale non trovato"));

        BillingAccount account = billingAccountRepository.findByRistoratoreId(restaurantId)
                .orElseGet(() -> BillingAccount.builder().ristoratore(restaurant).build());
        BillingGlobalConfig globalConfig = billingGlobalConfigService.requireConfig();

        account.setBillingEnabled(Boolean.TRUE.equals(request.getBillingEnabled()));
        account.setCommissionPercentage(globalConfig.getCommissionPercentage());
        account.setMinimumMonthlyFee(globalConfig.getMinimumMonthlyFee().setScale(2, RoundingMode.HALF_UP));
        account.setContractStartDate(request.getContractStartDate());
        account.setBillingDay(request.getContractStartDate().getDayOfMonth());
        account.setStripeCustomerId(trimToNull(request.getStripeCustomerId()));
        account.setDefaultPaymentMethodId(trimToNull(request.getDefaultPaymentMethodId()));

        BillingAccount saved = billingAccountRepository.save(account);
        adminAuditService.record("ADMIN_UPSERT_BILLING_ACCOUNT", restaurantId, "billing_account", saved.getId(), Map.of(
                "billingEnabled", saved.isBillingEnabled(),
                "billingDay", saved.getBillingDay()
        ));
        return billingReviewMapper.toAdminAccountDto(saved);
    }

    @Transactional(readOnly = true)
    public BillingAccountDto getAdminAccount(Long restaurantId) {
        BillingAccount account = billingAccountRepository.findByRistoratoreId(restaurantId)
                .orElseThrow(() -> new RuntimeException("Billing account non configurato"));
        return billingReviewMapper.toAdminAccountDto(account);
    }

    @Transactional(readOnly = true)
    public RestaurantBillingAccountDto getCurrentRestaurantAccount() {
        BillingAccount account = requireCurrentRestaurantAccount();
        return billingReviewMapper.toRestaurantAccountDto(account);
    }

    @Transactional
    public CreateSetupIntentResponse createCurrentRestaurantSetupIntent() {
        BillingAccount account = requireCurrentRestaurantAccount();
        if (account.getStripeCustomerId() == null || account.getStripeCustomerId().isBlank()) {
            account.setStripeCustomerId(stripeBillingService.ensureCustomer(account));
            billingAccountRepository.save(account);
        }
        return stripeBillingService.createSepaSetupIntent(account);
    }

    @Transactional
    public RestaurantBillingAccountDto completeCurrentRestaurantSetupIntent(String setupIntentId) {
        BillingAccount account = requireCurrentRestaurantAccount();
        if (account.getStripeCustomerId() == null || account.getStripeCustomerId().isBlank()) {
            throw new RuntimeException("Stripe customer non configurato");
        }
        String paymentMethodId = stripeBillingService.completeSepaSetup(account, setupIntentId);
        account.setDefaultPaymentMethodId(paymentMethodId);
        BillingAccount saved = billingAccountRepository.save(account);
        return billingReviewMapper.toRestaurantAccountDto(saved);
    }

    @Transactional(readOnly = true)
    public BillingAccount requireCurrentRestaurantAccount() {
        Long restaurantId = accessContextService.getActingRestaurantIdOrThrow();
        return billingAccountRepository.findByRistoratoreId(restaurantId)
                .orElseThrow(() -> new RuntimeException("Billing account non configurato"));
    }

    private void validateUpsertRequest(AdminUpsertBillingAccountRequest request) {
        if (request == null) {
            throw new RuntimeException("Richiesta billing mancante");
        }
        if (request.getContractStartDate() == null || request.getContractStartDate().isAfter(LocalDate.now().plusYears(10))) {
            throw new RuntimeException("Data contratto non valida");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
