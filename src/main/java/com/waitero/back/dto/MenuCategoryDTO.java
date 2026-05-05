package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MenuCategoryDTO {
    private Long id;
    private String businessType;
    private String code;
    private String label;
    private Integer sortOrder;
}
