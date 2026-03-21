package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderEventDTO {
    private String type;
    private Long orderId;
    private Long restaurantId;
    private Integer tableId;
    private String status;
    private Integer riskScore;
    private String reason;
    private String message;
}
