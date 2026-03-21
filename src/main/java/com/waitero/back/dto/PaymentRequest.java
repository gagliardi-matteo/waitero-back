package com.waitero.back.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class PaymentRequest {
    private String paymentMode;
    private BigDecimal amount;
    private String participantName;
    private List<PaymentAllocationRequest> allocations;
}