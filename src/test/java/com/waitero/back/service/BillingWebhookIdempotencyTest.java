package com.waitero.back.service;

import com.stripe.model.Event;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class BillingWebhookIdempotencyTest {

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
    void shouldSkipAlreadyProcessedWebhookEvent() {
        Event event = mock(Event.class);
        when(stripeBillingService.verifyAndParseWebhook("{}", "sig")).thenReturn(event);
        when(event.getId()).thenReturn("evt_123");
        when(event.getType()).thenReturn("invoice.paid");
        when(stripeWebhookEventRepository.existsById("evt_123")).thenReturn(true);

        billingReviewService.handleStripeWebhook("{}", "sig");

        verify(stripeWebhookEventRepository).existsById("evt_123");
        verify(stripeWebhookEventRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(billingReviewRepository, never()).findFirstByStripeInvoiceId(org.mockito.ArgumentMatchers.anyString());
    }
}
