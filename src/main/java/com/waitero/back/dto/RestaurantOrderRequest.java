package com.waitero.back.dto;

import lombok.Data;

import java.util.List;

@Data
public class RestaurantOrderRequest {
    private Integer tableId;
    private List<CustomerOrderItemRequest> items;
}
