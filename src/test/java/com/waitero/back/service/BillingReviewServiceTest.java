package com.waitero.back.service;

import com.waitero.back.dto.admin.billing.BillingReviewActionRequest;
import com.waitero.back.entity.BillingAccount;
import com.waitero.back.entity.BillingGlobalConfig;
import com.waitero.back.entity.BillingReview;
import com.waitero.back.entity.BillingReviewStatus;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.repository.BillingAccountRepository;
import com.waitero.back.repository.BillingReviewRepository;
import com.waitero.back.repository.OrdineRepository;
import com.waitero.back.repository.StripeWebhookEventRepository;
import com.waitero.back.security.AccessContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingReviewServiceTest {

    @Mock
    private BillingAccountRepository billingAccountRepository;
    @Mock
    private BillingReviewRepository billingReviewRepository;
    @Mock
    private StripeWebhookEventRepository stripeWebhookEventRepository;
    @Mock
    private OrdineRepository ordineRepository;
    @Mock
    private BillingFeeCalculator billingFeeCalculator;
    @Mock
    private BillingReviewMapper billingReviewMapper;
    @Mock
    private BillingGlobalConfigService billingGlobalConfigService;
    @Mock
    private StripeBillingService stripeBillingService;
    @Mock
    private AccessContextService accessContextService;
    @Mock
    private AdminAuditService adminAuditService;

    @InjectMocks
    private BillingReviewService billingReviewService;

    @Test
    void shouldRejectApproveWhenReviewNotCreated() {
        BillingReview review = BillingReview.builder()
                .id(10L)
                .status(BillingReviewStatus.APPROVED)
                .build();
        when(billingReviewRepository.findById(10L)).thenReturn(Optional.of(review));

        assertThrows(RuntimeException.class, () -> billingReviewService.approveReview(10L, new BillingReviewActionRequest()));
    }

    @Test
    void shouldResolvePeriodUsingPreviousBillingAnchor() {
        BillingAccount account = BillingAccount.builder()
                .billingDay(31)
                .build();

        BillingReviewService.BillingPeriod period = billingReviewService.resolveBillingPeriod(account, LocalDate.of(2026, 2, 28));

        assertEquals(LocalDate.of(2026, 1, 31), period.startInclusive());
        assertEquals(LocalDate.of(2026, 2, 27), period.endInclusive());
    }

    @Test
    void shouldFinalizeApprovedReview() {
        Ristoratore restaurant = Ristoratore.builder().id(99L).nome("Test").build();
        BillingReview review = BillingReview.builder()
                .id(20L)
                .ristoratore(restaurant)
                .status(BillingReviewStatus.APPROVED)
                .stripeInvoiceId("in_test")
                .grossRevenueSnapshot(new BigDecimal("100.00"))
                .commissionPercentageSnapshot(new BigDecimal("0.010000"))
                .minimumMonthlyFeeSnapshot(new BigDecimal("5.00"))
                .calculatedFeeSnapshot(new BigDecimal("5.00"))
                .periodStart(LocalDate.of(2026, 4, 17))
                .periodEnd(LocalDate.of(2026, 5, 16))
                .orderCountSnapshot(2)
                .build();

        when(billingReviewRepository.findById(20L)).thenReturn(Optional.of(review));
        when(stripeBillingService.finalizeInvoice("in_test")).thenReturn(null);
        when(billingReviewRepository.save(any(BillingReview.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingReviewRepository.findPreviousReviews(99L, LocalDate.of(2026, 4, 17))).thenReturn(java.util.List.of());
        when(billingReviewMapper.toDetailDto(any(), any(), any())).thenAnswer(invocation -> null);

        billingReviewService.finalizeReview(20L, new BillingReviewActionRequest());

        assertEquals(BillingReviewStatus.INVOICED, review.getStatus());
    }
}
