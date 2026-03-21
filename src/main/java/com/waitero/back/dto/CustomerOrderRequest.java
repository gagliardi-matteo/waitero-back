package com.waitero.back.dto;

import lombok.Data;

import java.util.List;

@Data
public class CustomerOrderRequest {
    private String token;
    private String restaurantId;
    private Integer tableId;
    private String deviceId;
    private String fingerprint;
    private List<CustomerOrderItemRequest> items;
}
