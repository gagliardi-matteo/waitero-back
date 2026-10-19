package com.waitero.back.dto;

import lombok.Data;
import java.util.List;

@Data
public class DemoOrderRequest {
    private String requestId;
    private String noteCucina;
    private List<CustomerOrderItemRequest> items;
}
