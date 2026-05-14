package com.waitero.back.service;

import com.waitero.back.dto.admin.billing.BillingAccountDto;
import com.waitero.back.dto.admin.billing.BillingReviewDetailDto;
import com.waitero.back.dto.admin.billing.BillingReviewOrderSnapshotDto;
import com.waitero.back.dto.admin.billing.BillingReviewSummaryDto;
import com.waitero.back.dto.admin.billing.StripeInvoiceSummaryDto;
import com.waitero.back.dto.billing.RestaurantBillingAccountDto;
import com.waitero.back.entity.BillingAccount;
import com.waitero.back.entity.BillingReview;
import com.waitero.back.entity.BillingReviewOrderSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BillingReviewMapper {

    public BillingAccountDto toAdminAccountDto(BillingAccount account) {
        return BillingAccountDto.builder()
                .id(account.getId())
                .restaurantId(account.getRistoratore().getId())
                .restaurantName(account.getRistoratore().getNome())
                .stripeCustomerId(account.getStripeCustomerId())
                .defaultPaymentMethodId(account.getDefaultPaymentMethodId())
                .billingEnabled(account.isBillingEnabled())
                .commissionPercentage(account.getCommissionPercentage())
                .minimumMonthlyFee(account.getMinimumMonthlyFee())
                .billingDay(account.getBillingDay())
                .contractStartDate(account.getContractStartDate())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    public RestaurantBillingAccountDto toRestaurantAccountDto(BillingAccount account) {
        return RestaurantBillingAccountDto.builder()
                .id(account.getId())
                .stripeCustomerId(account.getStripeCustomerId())
                .defaultPaymentMethodId(account.getDefaultPaymentMethodId())
                .billingEnabled(account.isBillingEnabled())
                .commissionPercentage(account.getCommissionPercentage())
                .minimumMonthlyFee(account.getMinimumMonthlyFee())
                .billingDay(account.getBillingDay())
                .contractStartDate(account.getContractStartDate())
                .updatedAt(account.getUpdatedAt())
                .build();
    }

    public BillingReviewSummaryDto toSummaryDto(BillingReview review, BillingReview previousReview, List<String> anomalies) {
        return BillingReviewSummaryDto.builder()
                .id(review.getId())
                .restaurantId(review.getRistoratore().getId())
                .restaurantName(review.getRistoratore().getNome())
                .periodStart(review.getPeriodStart())
                .periodEnd(review.getPeriodEnd())
                .grossRevenue(review.getGrossRevenueSnapshot())
                .calculatedFee(review.getCalculatedFeeSnapshot())
                .revenueDelta(delta(review.getGrossRevenueSnapshot(), previousReview == null ? null : previousReview.getGrossRevenueSnapshot()))
                .feeDelta(delta(review.getCalculatedFeeSnapshot(), previousReview == null ? null : previousReview.getCalculatedFeeSnapshot()))
                .orderCount(review.getOrderCountSnapshot())
                .status(review.getStatus())
                .approvedAt(review.getApprovedAt())
                .anomalies(anomalies)
                .build();
    }

    public BillingReviewDetailDto toDetailDto(BillingReview review, StripeInvoiceSummaryDto stripeInvoice, List<String> anomalies) {
        return BillingReviewDetailDto.builder()
                .id(review.getId())
                .restaurantId(review.getRistoratore().getId())
                .restaurantName(review.getRistoratore().getNome())
                .periodStart(review.getPeriodStart())
                .periodEnd(review.getPeriodEnd())
                .grossRevenue(review.getGrossRevenueSnapshot())
                .orderCount(review.getOrderCountSnapshot())
                .commissionPercentage(review.getCommissionPercentageSnapshot())
                .minimumMonthlyFee(review.getMinimumMonthlyFeeSnapshot())
                .calculatedFee(review.getCalculatedFeeSnapshot())
                .status(review.getStatus())
                .stripeInvoiceId(review.getStripeInvoiceId())
                .approvedBy(review.getApprovedBy())
                .approvedAt(review.getApprovedAt())
                .notes(review.getNotes())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .anomalies(anomalies)
                .stripeInvoice(stripeInvoice)
                .orderSnapshots(review.getOrderSnapshots().stream().map(this::toOrderSnapshotDto).toList())
                .build();
    }

    public StripeInvoiceSummaryDto toStripeInvoiceSummaryDto(
            String invoiceId,
            String status,
            String collectionMethod,
            String hostedInvoiceUrl,
            String invoicePdf,
            BigDecimal amountDue,
            BigDecimal amountPaid,
            boolean autoAdvance
    ) {
        return StripeInvoiceSummaryDto.builder()
                .invoiceId(invoiceId)
                .status(status)
                .collectionMethod(collectionMethod)
                .hostedInvoiceUrl(hostedInvoiceUrl)
                .invoicePdf(invoicePdf)
                .amountDue(amountDue)
                .amountPaid(amountPaid)
                .autoAdvance(autoAdvance)
                .build();
    }

    private BillingReviewOrderSnapshotDto toOrderSnapshotDto(BillingReviewOrderSnapshot snapshot) {
        return BillingReviewOrderSnapshotDto.builder()
                .id(snapshot.getId())
                .orderId(snapshot.getOrderId())
                .orderTotal(snapshot.getOrderTotal())
                .createdAt(snapshot.getCreatedAt())
                .build();
    }

    private BigDecimal delta(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null) {
            return null;
        }
        return current.subtract(previous);
    }
}
