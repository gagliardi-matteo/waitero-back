package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SecureTableAccessResponse {
    private boolean allowed;
    private String status;
    private String message;
    private Long restaurantId;
    private Integer tableId;
    private String tablePublicId;
    private String tableName;
    private String qrToken;
    private Integer riskScore;
}
