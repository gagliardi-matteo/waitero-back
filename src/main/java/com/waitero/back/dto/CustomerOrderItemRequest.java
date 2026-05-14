package com.waitero.back.dto;

import lombok.Data;

@Data
public class CustomerOrderItemRequest {
    private Long dishId;
    private Integer quantity;
    private String portionKey;
    private String source;
    private Long sourceDishId;
}
