package com.waitero.back.dto.admin.billing;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class UpdateBillingGlobalConfigRequest {
    private BigDecimal commissionPercentage;
    private BigDecimal minimumMonthlyFee;
}
