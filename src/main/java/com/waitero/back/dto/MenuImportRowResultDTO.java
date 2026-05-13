package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MenuImportRowResultDTO {
    private int rowNumber;
    private String nome;
    private String status;
    private String message;
}
