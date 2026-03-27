package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class OrdineDTO {
    private Long id;
    private Long restaurantId;
    private Integer tableId;
    private String status;
    private String paymentMode;
    private String noteCucina;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private BigDecimal totale;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private List<OrdineItemDTO> items;
    private List<OrdinePaymentDTO> payments;
}