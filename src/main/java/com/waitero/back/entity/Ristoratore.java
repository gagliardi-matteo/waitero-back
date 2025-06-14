package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Ristoratore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String nome;

    private String password; // solo se usi login classico

    private String provider; // es: "GOOGLE", "FACEBOOK", "APPLE"

    private String providerId; // es: sub dell'id_token
}
