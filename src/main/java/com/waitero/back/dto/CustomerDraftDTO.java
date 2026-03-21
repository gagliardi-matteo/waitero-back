package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CustomerDraftDTO {
    private Long restaurantId;
    private Integer tableId;
    private List<CustomerDraftItemDTO> items;
}