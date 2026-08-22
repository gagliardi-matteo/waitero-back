package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "demo_session")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DemoSession {
    @Id private UUID id;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ristoratore_id", nullable = false) private Ristoratore ristoratore;
    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "table_id", nullable = false, unique = true) private Tavolo tavolo;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "last_activity_at", nullable = false) private LocalDateTime lastActivityAt;
    @Column(name = "order_count", nullable = false) private Integer orderCount;
}
