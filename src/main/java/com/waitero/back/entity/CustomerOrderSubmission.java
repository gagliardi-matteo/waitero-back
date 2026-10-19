package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "customer_order_submission",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_customer_order_submission_intent",
                columnNames = {"ristoratore_id", "table_id", "request_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerOrderSubmission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ristoratore_id", nullable = false)
    private Long restaurantId;

    @Column(name = "table_id", nullable = false)
    private Integer tableId;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ordine_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private Ordine order;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
