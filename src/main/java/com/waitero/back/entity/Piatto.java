package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Piatto {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nome;
    private String descrizione;
    private BigDecimal prezzo;
    private Boolean disponibile;

    @Enumerated(EnumType.STRING)
    private Categoria categoria;

    private String imageUrl;

    @Column(length = 512)
    private String ingredienti;

    @Column(length = 512)
    private String allergeni;

    @Column(nullable = false)
    @Builder.Default
    private Boolean consigliato = false;

    @ManyToOne
    private Ristoratore ristoratore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piatto_canonico_id")
    private PiattoCanonicale piattoCanonicale;
}
