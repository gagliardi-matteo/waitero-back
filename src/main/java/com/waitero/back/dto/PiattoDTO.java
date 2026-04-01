package com.waitero.back.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class PiattoDTO {
    private Long id;
    private String nome;
    private String descrizione;
    private BigDecimal prezzo;
    private Boolean disponibile;
    private String categoria;
    private String imageUrl;
    private String ingredienti;
    private String allergeni;
    private Boolean consigliato;
    private Integer numeroOrdini;
    private Long views;
    private Long clicks;
    private Long addToCart;
    private BigDecimal viewToCartRate;
    private BigDecimal viewToOrderRate;
    private String performanceLabel;
    private List<IngredienteDTO> ingredientiStrutturati = new ArrayList<>();
}
