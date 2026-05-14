package com.waitero.back.dto.admin.billing;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class BillingGlobalConfigDto {
    private BigDecimal commissionPercentage;
    private BigDecimal minimumMonthlyFee;
    private LocalDateTime updatedAt;
}
