package com.waitero.back.dto;

import lombok.Data;

@Data
public class CustomerDraftMutationRequest {
    private String token;
    private String restaurantId;
    private Integer tableId;
    private String deviceId;
    private String fingerprint;
    private Long dishId;
    private Integer delta;
}
