package com.waitero.back.service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceItem;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.InvoiceCreateParams;
import com.stripe.param.InvoiceFinalizeInvoiceParams;
import com.stripe.param.InvoiceItemCreateParams;
import com.stripe.param.InvoiceRetrieveParams;
import com.stripe.param.InvoiceUpdateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.waitero.back.config.StripeBillingProperties;
import com.waitero.back.dto.admin.billing.StripeInvoiceSummaryDto;
import com.waitero.back.dto.billing.CreateSetupIntentResponse;
import com.waitero.back.entity.BillingAccount;
import com.waitero.back.entity.BillingReview;
import com.waitero.back.entity.Ristoratore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StripeBillingService {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingService.class);

    private final StripeBillingProperties stripeBillingProperties;
    private final BillingReviewMapper billingReviewMapper;

    @PostConstruct
    void init() {
        if (stripeBillingProperties.getSecretKey() != null && !stripeBillingProperties.getSecretKey().isBlank()) {
            Stripe.apiKey = stripeBillingProperties.getSecretKey();
        }
    }

    @Transactional(readOnly = true)
    public String ensureCustomer(BillingAccount account) {
        if (account.getStripeCustomerId() != null && !account.getStripeCustomerId().isBlank()) {
            return account.getStripeCustomerId();
        }
        Ristoratore restaurant = account.getRistoratore();
        try {
            Customer customer = Customer.create(CustomerCreateParams.builder()
                    .setEmail(restaurant.getEmail())
                    .setName(restaurant.getNome())
                    .putMetadata("restaurantId", String.valueOf(restaurant.getId()))
                    .putMetadata("restaurantName", restaurant.getNome() == null ? "" : restaurant.getNome())
                    .build());
            log.info("Created Stripe customer for restaurantId={} customerId={}", restaurant.getId(), customer.getId());
            return customer.getId();
        } catch (StripeException ex) {
            throw new RuntimeException("Impossibile creare il customer Stripe", ex);
        }
    }

    public CreateSetupIntentResponse createSepaSetupIntent(BillingAccount account) {
        String customerId = ensureCustomer(account);
        try {
            SetupIntent setupIntent = SetupIntent.create(SetupIntentCreateParams.builder()
                    .setCustomer(customerId)
                    .addPaymentMethodType("sepa_debit")
                    .putMetadata("restaurantId", String.valueOf(account.getRistoratore().getId()))
                    .putMetadata("billingAccountId", String.valueOf(account.getId()))
                    .build());
            return CreateSetupIntentResponse.builder()
                    .billingAccountId(account.getId())
                    .stripeCustomerId(customerId)
                    .setupIntentId(setupIntent.getId())
                    .clientSecret(setupIntent.getClientSecret())
                    .publishableKey(stripeBillingProperties.getPublishableKey())
                    .build();
        } catch (StripeException ex) {
            throw new RuntimeException("Impossibile creare il SetupIntent Stripe", ex);
        }
    }

    public String completeSepaSetup(BillingAccount account, String setupIntentId) {
        if (setupIntentId == null || setupIntentId.isBlank()) {
            throw new RuntimeException("SetupIntent mancante");
        }
        try {
            SetupIntent setupIntent = SetupIntent.retrieve(setupIntentId);
            if (!"succeeded".equalsIgnoreCase(setupIntent.getStatus())) {
                throw new RuntimeException("Il SetupIntent Stripe non e ancora completato");
            }
            if (setupIntent.getCustomer() == null || !setupIntent.getCustomer().equals(account.getStripeCustomerId())) {
                throw new RuntimeException("Il SetupIntent Stripe non appartiene al customer atteso");
            }
            String paymentMethodId = setupIntent.getPaymentMethod();
            if (paymentMethodId == null || paymentMethodId.isBlank()) {
                throw new RuntimeException("Payment method Stripe mancante");
            }

            Customer customer = Customer.retrieve(account.getStripeCustomerId());
            customer.update(CustomerUpdateParams.builder()
                    .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                            .setDefaultPaymentMethod(paymentMethodId)
                            .build())
                    .build());
            return paymentMethodId;
        } catch (StripeException ex) {
            throw new RuntimeException("Impossibile completare la configurazione SEPA Stripe", ex);
        }
    }

    public String createDraftInvoice(BillingAccount account, BillingReview review) {
        if (account.getStripeCustomerId() == null || account.getStripeCustomerId().isBlank()) {
            throw new RuntimeException("Stripe customer non configurato");
        }
        if (account.getDefaultPaymentMethodId() == null || account.getDefaultPaymentMethodId().isBlank()) {
            throw new RuntimeException("Metodo di pagamento Stripe non configurato");
        }

        try {
            Invoice invoice = Invoice.create(InvoiceCreateParams.builder()
                    .setCustomer(account.getStripeCustomerId())
                    .setAutoAdvance(false)
                    .setCollectionMethod(InvoiceCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
                    .setDefaultPaymentMethod(account.getDefaultPaymentMethodId())
                    .putMetadata("billingReviewId", String.valueOf(review.getId()))
                    .putMetadata("restaurantId", String.valueOf(review.getRistoratore().getId()))
                    .build());

            InvoiceItem.create(InvoiceItemCreateParams.builder()
                    .setCustomer(account.getStripeCustomerId())
                    .setInvoice(invoice.getId())
                    .setCurrency(normalizedCurrency())
                    .setAmount(toStripeAmount(review.getCalculatedFeeSnapshot()))
                    .setDescription(buildInvoiceDescription(review))
                    .putMetadata("billingReviewId", String.valueOf(review.getId()))
                    .build());

            invoice.update(InvoiceUpdateParams.builder()
                    .setAutoAdvance(false)
                    .setCollectionMethod(InvoiceUpdateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
                    .setDefaultPaymentMethod(account.getDefaultPaymentMethodId())
                    .build());

            log.info("Created Stripe draft invoice reviewId={} restaurantId={} invoiceId={}",
                    review.getId(), review.getRistoratore().getId(), invoice.getId());
            return invoice.getId();
        } catch (StripeException ex) {
            throw new RuntimeException("Impossibile creare la invoice Stripe", ex);
        }
    }

    public StripeInvoiceSummaryDto retrieveInvoiceSummary(String invoiceId) {
        if (invoiceId == null || invoiceId.isBlank()) {
            return null;
        }
        try {
            Invoice invoice = Invoice.retrieve(invoiceId, InvoiceRetrieveParams.builder().build(), null);
            return billingReviewMapper.toStripeInvoiceSummaryDto(
                    invoice.getId(),
                    invoice.getStatus(),
                    invoice.getCollectionMethod(),
                    invoice.getHostedInvoiceUrl(),
                    invoice.getInvoicePdf(),
                    fromStripeAmount(invoice.getAmountDue()),
                    fromStripeAmount(invoice.getAmountPaid()),
                    Boolean.TRUE.equals(invoice.getAutoAdvance())
            );
        } catch (StripeException ex) {
            throw new RuntimeException("Impossibile recuperare la invoice Stripe", ex);
        }
    }

    public StripeInvoiceSummaryDto finalizeInvoice(String invoiceId) {
        if (invoiceId == null || invoiceId.isBlank()) {
            throw new RuntimeException("Invoice Stripe mancante");
        }
        try {
            Invoice invoice = Invoice.retrieve(invoiceId);
            Invoice finalized = invoice.finalizeInvoice(InvoiceFinalizeInvoiceParams.builder()
                    .setAutoAdvance(true)
                    .build());
            log.info("Finalized Stripe invoice invoiceId={} status={} autoAdvance={}",
                    finalized.getId(), finalized.getStatus(), finalized.getAutoAdvance());
            return retrieveInvoiceSummary(finalized.getId());
        } catch (StripeException ex) {
            throw new RuntimeException("Impossibile finalizzare la invoice Stripe", ex);
        }
    }

    public Event verifyAndParseWebhook(String payload, String signatureHeader) {
        if (stripeBillingProperties.getWebhookSecret() == null || stripeBillingProperties.getWebhookSecret().isBlank()) {
            throw new RuntimeException("Stripe webhook secret non configurato");
        }
        try {
            return Webhook.constructEvent(payload, signatureHeader, stripeBillingProperties.getWebhookSecret());
        } catch (SignatureVerificationException ex) {
            throw new RuntimeException("Firma webhook Stripe non valida", ex);
        }
    }

    public PaymentMethod retrievePaymentMethod(String paymentMethodId) {
        try {
            return PaymentMethod.retrieve(paymentMethodId);
        } catch (StripeException ex) {
            throw new RuntimeException("Impossibile recuperare il payment method Stripe", ex);
        }
    }

    private String normalizedCurrency() {
        String currency = stripeBillingProperties.getCurrency();
        return currency == null || currency.isBlank() ? "eur" : currency.toLowerCase();
    }

    private Long toStripeAmount(BigDecimal amount) {
        return amount
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();
    }

    private BigDecimal fromStripeAmount(Long amount) {
        if (amount == null) {
            return null;
        }
        return BigDecimal.valueOf(amount).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    private String buildInvoiceDescription(BillingReview review) {
        return "Commissione WaiterO " + review.getPeriodStart() + " - " + review.getPeriodEnd();
    }
}
