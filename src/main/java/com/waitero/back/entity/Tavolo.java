package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "tavoli",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tavolo_ristoratore_numero", columnNames = {"ristoratore_id", "numero"}),
                @UniqueConstraint(name = "uk_tavolo_public_id", columnNames = {"table_public_id"})
        },
        indexes = {
                @Index(name = "idx_tavolo_public_id", columnList = "table_public_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tavolo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ristoratore_id", nullable = false)
    private Ristoratore ristoratore;

    @Column(name = "table_public_id", nullable = false, length = 32)
    private String tablePublicId;

    @Column(nullable = false)
    private Integer numero;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    private Integer coperti;

    @Column(nullable = false)
    private Boolean attivo;

    @Column(nullable = false, length = 1024)
    private String qrToken;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
