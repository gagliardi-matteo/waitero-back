package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "piatto_canonico")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PiattoCanonicale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String nomeCanonico;

    @Column(length = 100)
    private String categoria;
}
