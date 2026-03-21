package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "alias_piatto")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AliasPiatto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String nomeOriginale;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "piatto_canonico_id", nullable = false)
    private PiattoCanonicale piattoCanonicale;
}
