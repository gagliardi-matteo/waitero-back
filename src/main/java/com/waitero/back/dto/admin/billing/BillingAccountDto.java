package com.waitero.back.dto.admin.billing;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class BillingAccountDto {
    private Long id;
    private Long restaurantId;
    private String restaurantName;
    private String stripeCustomerId;
    private String defaultPaymentMethodId;
    private boolean billingEnabled;
    private BigDecimal commissionPercentage;
    private BigDecimal minimumMonthlyFee;
    private Integer billingDay;
    private LocalDate contractStartDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
