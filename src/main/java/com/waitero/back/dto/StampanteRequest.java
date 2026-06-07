package com.waitero.back.dto;

import com.waitero.back.entity.ModelloStampante;
import com.waitero.back.entity.TipoConnessione;
import lombok.Data;

@Data
public class StampanteRequest {
    private Long ristoranteId;
    private String nome;
    private ModelloStampante modello;
    private TipoConnessione tipoConnessione;
    private String ipAddress;
    private Integer porta;
    private Boolean abilitata;
}
