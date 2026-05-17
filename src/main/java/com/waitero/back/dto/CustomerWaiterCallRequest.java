package com.waitero.back.dto;

import lombok.Data;

@Data
public class CustomerWaiterCallRequest {
    private String token;
    private String restaurantId;
    private Integer tableId;
    private String deviceId;
    private String fingerprint;
}
