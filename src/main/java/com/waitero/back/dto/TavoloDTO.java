package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TavoloDTO {
    private Long id;
    private Long restaurantId;
    private String tablePublicId;
    private Integer numero;
    private String nome;
    private Integer coperti;
    private Boolean attivo;
    private String qrToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
