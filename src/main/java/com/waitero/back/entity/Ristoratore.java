package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ristoratore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String nome;

    private String password;

    private String provider;

    private String providerId;

    @Column(length = 255)
    private String address;

    @Column(length = 255)
    private String city;

    @Column(length = 255)
    private String formattedAddress;

    private Double latitude;

    private Double longitude;

    @Column(name = "allowed_radius_meters")
    private Integer allowedRadiusMeters;
}
