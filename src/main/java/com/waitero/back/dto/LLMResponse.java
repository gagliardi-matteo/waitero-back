package com.waitero.back.dto;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LLMResponse {
    private String nomeCanonico;
    private String categoria;

    @Builder.Default
    private List<LLMIngredientResponse> ingredienti = new ArrayList<>();
}
