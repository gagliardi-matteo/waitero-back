package com.waitero.back.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LLMIngredientResponse {
    private String nome;
    private String categoria;
    private BigDecimal grammi;
}
