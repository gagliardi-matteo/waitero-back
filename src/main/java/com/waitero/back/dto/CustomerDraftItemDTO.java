package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomerDraftItemDTO {
    private Long dishId;
    private Integer quantity;
}