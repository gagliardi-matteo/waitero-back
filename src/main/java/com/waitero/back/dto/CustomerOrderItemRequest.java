package com.waitero.back.dto;

import lombok.Data;

@Data
public class CustomerOrderItemRequest {
    private Long dishId;
    private Integer quantity;
}
