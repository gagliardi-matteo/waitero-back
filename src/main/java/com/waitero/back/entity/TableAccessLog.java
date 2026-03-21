package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "table_access_log", indexes = {
        @Index(name = "idx_table_access_log_table_timestamp", columnList = "table_id,timestamp")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id", nullable = false)
    private Tavolo tavolo;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(length = 255)
    private String fingerprint;

    private Double latitude;

    private Double longitude;

    private Double accuracy;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "risk_score", nullable = false)
    private Integer riskScore;

    @Column(length = 255)
    private String reason;
}
