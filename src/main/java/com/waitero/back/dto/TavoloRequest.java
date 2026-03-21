package com.waitero.back.dto;

import lombok.Data;

@Data
public class TavoloRequest {
    private Integer numero;
    private String nome;
    private Integer coperti;
    private Boolean attivo;
}
