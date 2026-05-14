package com.waitero.back.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PiattoPortionDTO {
    private String key;
    private String label;
    private BigDecimal price;
}
