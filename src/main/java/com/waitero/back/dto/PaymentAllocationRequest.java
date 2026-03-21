package com.waitero.back.dto;

import lombok.Data;

@Data
public class PaymentAllocationRequest {
    private Long orderItemId;
    private Integer quantity;
}