package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomerDraftItemDTO {
    private String lineKey;
    private Long dishId;
    private String portionKey;
    private Integer quantity;
}
