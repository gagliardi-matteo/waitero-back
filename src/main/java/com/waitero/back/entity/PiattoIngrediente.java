package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "piatto_ingrediente")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PiattoIngrediente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "piatto_canonico_id", nullable = false)
    private PiattoCanonicale piattoCanonicale;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ingrediente_id", nullable = false)
    private Ingrediente ingrediente;

    @Column(precision = 10, scale = 2)
    private BigDecimal grammi;
}
