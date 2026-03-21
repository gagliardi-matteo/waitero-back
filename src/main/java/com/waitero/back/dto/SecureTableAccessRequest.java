package com.waitero.back.dto;

import lombok.Data;

@Data
public class SecureTableAccessRequest {
    private String tablePublicId;
    private String qrToken;
    private String restaurantId;
    private Integer tableId;
    private String deviceId;
    private String fingerprint;
    private Double latitude;
    private Double longitude;
    private Double accuracy;
}
