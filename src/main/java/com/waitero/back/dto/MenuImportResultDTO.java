package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MenuImportResultDTO {
    private int totalRows;
    private int createdRows;
    private int updatedRows;
    private int failedRows;
    private List<MenuImportRowResultDTO> rowResults;
}
