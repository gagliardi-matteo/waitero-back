package com.waitero.back.dto.admin.billing;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class BillingReviewOrderSnapshotDto {
    private Long id;
    private Long orderId;
    private BigDecimal orderTotal;
    private LocalDateTime createdAt;
}
