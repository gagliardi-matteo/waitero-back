package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 32)
    @Builder.Default
    private BusinessType businessType = BusinessType.RISTORANTE;

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

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
