package com.waitero.back.dto.admin.billing;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class AdminUpsertBillingAccountRequest {
    private Boolean billingEnabled;
    private BigDecimal commissionPercentage;
    private BigDecimal minimumMonthlyFee;
    private LocalDate contractStartDate;
    private String stripeCustomerId;
    private String defaultPaymentMethodId;
}
