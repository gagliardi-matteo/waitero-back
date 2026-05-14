package com.waitero.back.dto.admin.billing;

import com.waitero.back.entity.BillingReviewStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class BillingReviewDetailDto {
    private Long id;
    private Long restaurantId;
    private String restaurantName;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal grossRevenue;
    private Integer orderCount;
    private BigDecimal commissionPercentage;
    private BigDecimal minimumMonthlyFee;
    private BigDecimal calculatedFee;
    private BillingReviewStatus status;
    private String stripeInvoiceId;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<String> anomalies;
    private StripeInvoiceSummaryDto stripeInvoice;
    private List<BillingReviewOrderSnapshotDto> orderSnapshots;
}
