package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class OrdinePaymentDTO {
    private Long id;
    private BigDecimal amount;
    private String paymentMode;
    private String participantName;
    private LocalDateTime createdAt;
    private List<OrdinePaymentAllocationDTO> allocations;
}