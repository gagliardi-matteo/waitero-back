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
public class BillingReviewSummaryDto {
    private Long id;
    private Long restaurantId;
    private String restaurantName;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal grossRevenue;
    private BigDecimal calculatedFee;
    private BigDecimal revenueDelta;
    private BigDecimal feeDelta;
    private Integer orderCount;
    private BillingReviewStatus status;
    private LocalDateTime approvedAt;
    private List<String> anomalies;
}
