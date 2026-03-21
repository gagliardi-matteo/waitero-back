package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "table_device", indexes = {
        @Index(name = "idx_table_device_table_id", columnList = "table_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id", nullable = false)
    private Tavolo tavolo;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(length = 255)
    private String fingerprint;

    @Column(name = "first_seen", nullable = false)
    private LocalDateTime firstSeen;

    @Column(name = "last_seen", nullable = false)
    private LocalDateTime lastSeen;
}
