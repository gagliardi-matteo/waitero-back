package com.waitero.back.service;

import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.waitero.back.dto.admin.billing.BillingReviewActionRequest;
import com.waitero.back.dto.admin.billing.BillingReviewDetailDto;
import com.waitero.back.dto.admin.billing.BillingReviewSummaryDto;
import com.waitero.back.dto.admin.billing.StripeInvoiceSummaryDto;
import com.waitero.back.entity.BillingAccount;
import com.waitero.back.entity.BillingGlobalConfig;
import com.waitero.back.entity.BillingReview;
import com.waitero.back.entity.BillingReviewOrderSnapshot;
import com.waitero.back.entity.BillingReviewStatus;
import com.waitero.back.entity.OrderStatus;
import com.waitero.back.entity.Ordine;
import com.waitero.back.entity.StripeWebhookEvent;
import com.waitero.back.repository.BillingAccountRepository;
import com.waitero.back.repository.BillingReviewRepository;
import com.waitero.back.repository.OrdineRepository;
import com.waitero.back.repository.StripeWebhookEventRepository;
import com.waitero.back.security.AccessContextService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BillingReviewService {

    private static final Logger log = LoggerFactory.getLogger(BillingReviewService.class);

    private final BillingAccountRepository billingAccountRepository;
    private final BillingReviewRepository billingReviewRepository;
    private final StripeWebhookEventRepository stripeWebhookEventRepository;
    private final OrdineRepository ordineRepository;
    private final BillingFeeCalculator billingFeeCalculator;
    private final BillingReviewMapper billingReviewMapper;
    private final BillingGlobalConfigService billingGlobalConfigService;
    private final StripeBillingService stripeBillingService;
    private final AccessContextService accessContextService;
    private final AdminAuditService adminAuditService;

    @Transactional(readOnly = true)
    public List<BillingReviewSummaryDto> findPendingReviews() {
        return billingReviewRepository.findByStatusOrderByCreatedAtAsc(BillingReviewStatus.CREATED).stream()
                .map(review -> {
                    BillingReview previousReview = previousReview(review);
                    return billingReviewMapper.toSummaryDto(review, previousReview, detectAnomalies(review, previousReview));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BillingReviewSummaryDto> findReviewsForRestaurant(Long restaurantId) {
        return billingReviewRepository.findByRistoratoreIdOrderByPeriodEndDescIdDesc(restaurantId).stream()
                .map(review -> {
                    BillingReview previousReview = previousReview(review);
                    return billingReviewMapper.toSummaryDto(review, previousReview, detectAnomalies(review, previousReview));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public BillingReviewDetailDto getReviewDetail(Long reviewId) {
        BillingReview review = getReviewForDetail(reviewId);
        StripeInvoiceSummaryDto stripeInvoice = review.getStripeInvoiceId() == null
                ? null
                : stripeBillingService.retrieveInvoiceSummary(review.getStripeInvoiceId());
        return billingReviewMapper.toDetailDto(review, stripeInvoice, detectAnomalies(review, previousReview(review)));
    }

    @Transactional
    public BillingReviewDetailDto approveReview(Long reviewId, BillingReviewActionRequest request) {
        BillingReview review = billingReviewRepository.findById(reviewId)
                .orElseThrow(() -> new RuntimeException("Billing review non trovata"));
        if (review.getStatus() != BillingReviewStatus.CREATED) {
            throw new RuntimeException("La review non e approvabile nello stato attuale");
        }

        BillingAccount account = billingAccountRepository.findByRistoratoreId(review.getRistoratore().getId())
                .orElseThrow(() -> new RuntimeException("Billing account non configurato"));

        String invoiceId = stripeBillingService.createDraftInvoice(account, review);
        review.setStripeInvoiceId(invoiceId);
        review.setStatus(BillingReviewStatus.APPROVED);
        review.setApprovedBy(accessContextService.getAuthenticatedUserId());
        review.setApprovedAt(LocalDateTime.now());
        review.setNotes(mergeNotes(review.getNotes(), request == null ? null : request.getNotes()));
        BillingReview saved = billingReviewRepository.save(review);

        adminAuditService.record("ADMIN_APPROVE_BILLING_REVIEW", saved.getRistoratore().getId(), "billing_review", saved.getId(), Map.of(
                "invoiceId", invoiceId,
                "periodStart", String.valueOf(saved.getPeriodStart()),
                "periodEnd", String.valueOf(saved.getPeriodEnd())
        ));

        return getReviewDetail(saved.getId());
    }

    @Transactional
    public BillingReviewDetailDto finalizeReview(Long reviewId, BillingReviewActionRequest request) {
        BillingReview review = billingReviewRepository.findById(reviewId)
                .orElseThrow(() -> new RuntimeException("Billing review non trovata"));
        if (review.getStatus() != BillingReviewStatus.APPROVED) {
            throw new RuntimeException("La review deve essere prima approvata");
        }
        if (review.getStripeInvoiceId() == null || review.getStripeInvoiceId().isBlank()) {
            throw new RuntimeException("Invoice Stripe mancante");
        }

        StripeInvoiceSummaryDto invoice = stripeBillingService.finalizeInvoice(review.getStripeInvoiceId());
        review.setStatus(BillingReviewStatus.INVOICED);
        review.setNotes(mergeNotes(review.getNotes(), request == null ? null : request.getNotes()));
        BillingReview saved = billingReviewRepository.save(review);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("invoiceId", saved.getStripeInvoiceId());
        metadata.put("invoiceStatus", invoice == null ? null : invoice.getStatus());
        adminAuditService.record("ADMIN_FINALIZE_BILLING_REVIEW", saved.getRistoratore().getId(), "billing_review", saved.getId(), metadata);

        return billingReviewMapper.toDetailDto(saved, invoice, detectAnomalies(saved, previousReview(saved)));
    }

    @Transactional
    public BillingReviewDetailDto rejectReview(Long reviewId, BillingReviewActionRequest request) {
        BillingReview review = billingReviewRepository.findById(reviewId)
                .orElseThrow(() -> new RuntimeException("Billing review non trovata"));
        if (review.getStatus() != BillingReviewStatus.CREATED) {
            throw new RuntimeException("La review puo essere rifiutata solo se ancora creata");
        }

        review.setStatus(BillingReviewStatus.REJECTED);
        review.setApprovedBy(accessContextService.getAuthenticatedUserId());
        review.setApprovedAt(LocalDateTime.now());
        review.setNotes(mergeNotes(review.getNotes(), request == null ? null : request.getNotes()));
        BillingReview saved = billingReviewRepository.save(review);
        adminAuditService.record("ADMIN_REJECT_BILLING_REVIEW", saved.getRistoratore().getId(), "billing_review", saved.getId(), Map.of());
        return getReviewDetail(saved.getId());
    }

    @Transactional
    public BillingReviewDetailDto syncReviewStatusFromStripe(Long reviewId, BillingReviewActionRequest request) {
        BillingReview review = billingReviewRepository.findById(reviewId)
                .orElseThrow(() -> new RuntimeException("Billing review non trovata"));
        if (review.getStripeInvoiceId() == null || review.getStripeInvoiceId().isBlank()) {
            throw new RuntimeException("La review non ha una invoice Stripe associata");
        }

        StripeInvoiceSummaryDto invoice = stripeBillingService.retrieveInvoiceSummary(review.getStripeInvoiceId());
        BillingReviewStatus mappedStatus = mapInvoiceSummaryStatus(invoice);
        BillingReviewStatus previousStatus = review.getStatus();

        if (mappedStatus != null && mappedStatus != review.getStatus()) {
            review.setStatus(mappedStatus);
        }
        review.setNotes(mergeNotes(review.getNotes(), request == null ? null : request.getNotes()));
        BillingReview saved = billingReviewRepository.save(review);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("invoiceId", saved.getStripeInvoiceId());
        metadata.put("previousStatus", previousStatus == null ? null : previousStatus.name());
        metadata.put("newStatus", saved.getStatus() == null ? null : saved.getStatus().name());
        metadata.put("stripeInvoiceStatus", invoice == null ? null : invoice.getStatus());
        adminAuditService.record("ADMIN_SYNC_BILLING_REVIEW_FROM_STRIPE", saved.getRistoratore().getId(), "billing_review", saved.getId(), metadata);

        return billingReviewMapper.toDetailDto(saved, invoice, detectAnomalies(saved, previousReview(saved)));
    }

    @Transactional
    public List<BillingReview> createScheduledReviewsForDate(LocalDate executionDate) {
        List<BillingReview> created = new ArrayList<>();
        for (BillingAccount account : billingAccountRepository.findByBillingEnabledTrue()) {
            if (!isBillingDueOn(account, executionDate)) {
                continue;
            }
            Optional<BillingReview> review = createReviewForAccountIfMissing(account, executionDate);
            review.ifPresent(created::add);
        }
        return created;
    }

    @Transactional
    public Optional<BillingReview> createReviewForAccountIfMissing(BillingAccount account, LocalDate executionDate) {
        BillingPeriod period = resolveBillingPeriod(account, executionDate);
        if (billingReviewRepository.existsByRistoratoreIdAndPeriodStartAndPeriodEnd(
                account.getRistoratore().getId(),
                period.startInclusive(),
                period.endInclusive()
        )) {
            log.info("Skipped duplicate billing review restaurantId={} periodStart={} periodEnd={}",
                    account.getRistoratore().getId(), period.startInclusive(), period.endInclusive());
            return Optional.empty();
        }

        List<Ordine> orders = ordineRepository.findOrdersForBilling(
                account.getRistoratore().getId(),
                OrderStatus.PAGATO,
                period.startInclusive().atStartOfDay(),
                period.endInclusive().plusDays(1).atStartOfDay()
        );
        BillingGlobalConfig globalConfig = billingGlobalConfigService.requireConfig();

        BigDecimal grossRevenue = orders.stream()
                .map(Ordine::getTotale)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = billingFeeCalculator.calculate(
                grossRevenue,
                globalConfig.getCommissionPercentage(),
                globalConfig.getMinimumMonthlyFee()
        );

        BillingReview review = BillingReview.builder()
                .ristoratore(account.getRistoratore())
                .periodStart(period.startInclusive())
                .periodEnd(period.endInclusive())
                .grossRevenueSnapshot(grossRevenue)
                .orderCountSnapshot(orders.size())
                .commissionPercentageSnapshot(globalConfig.getCommissionPercentage())
                .minimumMonthlyFeeSnapshot(globalConfig.getMinimumMonthlyFee())
                .calculatedFeeSnapshot(fee)
                .status(BillingReviewStatus.CREATED)
                .build();

        for (Ordine order : orders) {
            review.getOrderSnapshots().add(BillingReviewOrderSnapshot.builder()
                    .billingReview(review)
                    .orderId(order.getId())
                    .orderTotal(order.getTotale())
                    .build());
        }

        try {
            BillingReview saved = billingReviewRepository.save(review);
            log.info("Created billing review reviewId={} restaurantId={} periodStart={} periodEnd={} grossRevenue={} fee={} orderCount={}",
                    saved.getId(), saved.getRistoratore().getId(), saved.getPeriodStart(), saved.getPeriodEnd(),
                    saved.getGrossRevenueSnapshot(), saved.getCalculatedFeeSnapshot(), saved.getOrderCountSnapshot());
            return Optional.of(saved);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Duplicate billing review prevented at db level restaurantId={} periodStart={} periodEnd={}",
                    account.getRistoratore().getId(), period.startInclusive(), period.endInclusive());
            return Optional.empty();
        }
    }

    @Transactional
    public void handleStripeWebhook(String payload, String signatureHeader) {
        Event event = stripeBillingService.verifyAndParseWebhook(payload, signatureHeader);
        if (stripeWebhookEventRepository.existsById(event.getId())) {
            log.info("Skipping already processed Stripe webhook eventId={} type={}", event.getId(), event.getType());
            return;
        }

        StripeObject deserializedObject = event.getDataObjectDeserializer()
                .getObject()
                .orElse(null);
        Invoice invoice = deserializedObject instanceof Invoice castedInvoice ? castedInvoice : null;
        String invoiceId = invoice == null ? null : invoice.getId();

        stripeWebhookEventRepository.save(StripeWebhookEvent.builder()
                .eventId(event.getId())
                .eventType(event.getType())
                .invoiceId(invoiceId)
                .payload(payload)
                .build());

        if (invoiceId == null || invoiceId.isBlank()) {
            log.info("Processed Stripe webhook eventId={} type={} without invoice payload", event.getId(), event.getType());
            return;
        }

        BillingReview review = billingReviewRepository.findFirstByStripeInvoiceId(invoiceId)
                .orElse(null);
        if (review == null) {
            log.warn("Stripe webhook invoice not linked to any billing review eventId={} invoiceId={}", event.getId(), invoiceId);
            return;
        }

        BillingReviewStatus mappedStatus = mapWebhookStatus(event.getType());
        if (mappedStatus == null) {
            log.info("Ignoring unsupported Stripe webhook type={} invoiceId={}", event.getType(), invoiceId);
            return;
        }

        review.setStatus(mappedStatus);
        billingReviewRepository.save(review);
        log.info("Updated billing review from Stripe webhook eventId={} reviewId={} invoiceId={} status={}",
                event.getId(), review.getId(), invoiceId, mappedStatus);
    }

    boolean isBillingDueOn(BillingAccount account, LocalDate date) {
        if (account.getContractStartDate() == null || !date.isAfter(account.getContractStartDate())) {
            return false;
        }
        return resolveBillingAnchorDate(date.getYear(), date.getMonthValue(), account.getBillingDay()).equals(date);
    }

    BillingPeriod resolveBillingPeriod(BillingAccount account, LocalDate executionDate) {
        LocalDate currentAnchor = resolveBillingAnchorDate(executionDate.getYear(), executionDate.getMonthValue(), account.getBillingDay());
        if (!currentAnchor.equals(executionDate)) {
            throw new RuntimeException("Data scheduler non coerente con billing day");
        }
        YearMonth previousMonth = YearMonth.from(executionDate.minusMonths(1));
        LocalDate previousAnchor = resolveBillingAnchorDate(previousMonth.getYear(), previousMonth.getMonthValue(), account.getBillingDay());
        return new BillingPeriod(previousAnchor, currentAnchor.minusDays(1));
    }

    private BillingReview getReviewForDetail(Long reviewId) {
        return billingReviewRepository.findDetailedById(reviewId)
                .orElseThrow(() -> new RuntimeException("Billing review non trovata"));
    }

    private BillingReview previousReview(BillingReview review) {
        return billingReviewRepository.findPreviousReviews(review.getRistoratore().getId(), review.getPeriodStart()).stream()
                .findFirst()
                .orElse(null);
    }

    private List<String> detectAnomalies(BillingReview current, BillingReview previous) {
        List<String> anomalies = new ArrayList<>();
        if (current.getOrderCountSnapshot() == null || current.getOrderCountSnapshot() == 0) {
            anomalies.add("NO_ORDERS");
        }
        BigDecimal variableFee = current.getGrossRevenueSnapshot().multiply(current.getCommissionPercentageSnapshot());
        if (current.getCalculatedFeeSnapshot().compareTo(current.getMinimumMonthlyFeeSnapshot()) == 0
                && variableFee.compareTo(current.getMinimumMonthlyFeeSnapshot()) < 0) {
            anomalies.add("MINIMUM_FEE_APPLIED");
        }
        if (previous != null && previous.getGrossRevenueSnapshot() != null && previous.getGrossRevenueSnapshot().signum() > 0) {
            BigDecimal diff = current.getGrossRevenueSnapshot().subtract(previous.getGrossRevenueSnapshot()).abs();
            BigDecimal ratio = diff.divide(previous.getGrossRevenueSnapshot(), 4, java.math.RoundingMode.HALF_UP);
            if (ratio.compareTo(new BigDecimal("0.50")) >= 0) {
                anomalies.add("REVENUE_SPIKE");
            }
        }
        return anomalies;
    }

    private BillingReviewStatus mapWebhookStatus(String eventType) {
        return switch (eventType) {
            case "invoice.paid" -> BillingReviewStatus.PAID;
            case "invoice.payment_failed" -> BillingReviewStatus.FAILED;
            case "invoice.finalized" -> BillingReviewStatus.INVOICED;
            default -> null;
        };
    }

    private BillingReviewStatus mapInvoiceSummaryStatus(StripeInvoiceSummaryDto invoice) {
        if (invoice == null || invoice.getStatus() == null || invoice.getStatus().isBlank()) {
            return null;
        }
        return switch (invoice.getStatus().toLowerCase()) {
            case "paid" -> BillingReviewStatus.PAID;
            case "open" -> BillingReviewStatus.INVOICED;
            case "draft" -> BillingReviewStatus.APPROVED;
            case "uncollectible", "void" -> BillingReviewStatus.FAILED;
            default -> null;
        };
    }

    private LocalDate resolveBillingAnchorDate(int year, int month, int billingDay) {
        YearMonth yearMonth = YearMonth.of(year, month);
        int normalizedDay = Math.min(Math.max(1, billingDay), yearMonth.lengthOfMonth());
        return yearMonth.atDay(normalizedDay);
    }

    private String mergeNotes(String existing, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return existing;
        }
        return existing == null || existing.isBlank()
                ? incoming.trim()
                : existing + "\n" + incoming.trim();
    }

    record BillingPeriod(LocalDate startInclusive, LocalDate endInclusive) {
    }
}
