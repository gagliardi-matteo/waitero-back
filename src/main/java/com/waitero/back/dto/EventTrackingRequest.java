package com.waitero.back.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class EventTrackingRequest {
    private String eventType;
    private String userId;
    private String sessionId;
    private Long restaurantId;
    private Integer tableId;
    private Long dishId;
    private JsonNode metadata;
}
