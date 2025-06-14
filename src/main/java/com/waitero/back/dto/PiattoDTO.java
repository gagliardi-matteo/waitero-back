package com.waitero.back.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Data
@Getter
@Setter
public class PiattoDTO {
    private Long id;
    private String nome;
    private String descrizione;
    private BigDecimal prezzo;
    private Boolean disponibile;
    private Long categoriaId;
}