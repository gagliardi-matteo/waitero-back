package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class OrdineItemDTO {
    private Long id;
    private Long dishId;
    private String nome;
    private BigDecimal prezzoUnitario;
    private Integer quantita;
    private Integer paidQuantity;
    private Integer remainingQuantity;
    private BigDecimal subtotale;
    private String imageUrl;
}