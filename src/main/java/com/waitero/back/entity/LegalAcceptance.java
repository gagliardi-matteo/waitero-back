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
@Table(name = "legal_acceptance")
public class LegalAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LegalAcceptanceType type;

    @Column(name = "restaurant_id")
    private Long restaurantId;

    @Column(name = "table_public_id", length = 64)
    private String tablePublicId;

    @Column(name = "table_number")
    private Integer tableNumber;

    @Column(name = "qr_token_hash", length = 128)
    private String qrTokenHash;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "contract_version", length = 32)
    private String contractVersion;

    @Column(name = "privacy_version", nullable = false, length = 32)
    private String privacyVersion;

    @Column(name = "terms_version", length = 32)
    private String termsVersion;

    @Column(name = "allergen_disclaimer_version", length = 32)
    private String allergenDisclaimerVersion;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;
}
