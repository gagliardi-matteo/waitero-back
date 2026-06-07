package com.waitero.back.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomerLegalAcceptanceRequest {
    private String sessionId;
    private String tablePublicId;
    private String restaurantId;
    private Integer tableId;
    private String qrToken;
}
