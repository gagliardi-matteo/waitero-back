package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "stampanti",
        indexes = {
                @Index(name = "idx_stampante_ristorante", columnList = "ristorante_id"),
                @Index(name = "idx_stampante_ristorante_abilitata", columnList = "ristorante_id, abilitata")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Stampante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ristorante_id", nullable = false)
    private Ristoratore ristorante;

    @Column(nullable = false, length = 120)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ModelloStampante modello;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_connessione", nullable = false, length = 30)
    private TipoConnessione tipoConnessione;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    private Integer porta;

    @Column(nullable = false)
    private Boolean abilitata;

    @Column(name = "data_creazione", nullable = false)
    private LocalDateTime dataCreazione;
}
