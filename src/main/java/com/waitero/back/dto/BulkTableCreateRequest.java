package com.waitero.back.dto;

import lombok.Data;

@Data
public class BulkTableCreateRequest {
    private Integer count;
    private Integer coperti;
    private Integer startingNumber;
    private String namePrefix;
    private Boolean attivo;
}
