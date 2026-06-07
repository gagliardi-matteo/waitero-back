package com.waitero.back.dto;

import com.waitero.back.entity.ModelloStampante;
import com.waitero.back.entity.TipoConnessione;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class StampanteResponse {
    private Long id;
    private Long ristoranteId;
    private String nome;
    private ModelloStampante modello;
    private TipoConnessione tipoConnessione;
    private String ipAddress;
    private Integer porta;
    private Boolean abilitata;
    private LocalDateTime dataCreazione;
}
