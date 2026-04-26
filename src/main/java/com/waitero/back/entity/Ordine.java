package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customer_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ordine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Ristoratore ristoratore;

    @Column(nullable = false)
    private Integer tableId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    private String paymentMode;

    @Column(name = "note_cucina", length = 1000)
    private String noteCucina;

    private LocalDateTime paidAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(precision = 10, scale = 2)
    private BigDecimal totale;

    @Column(length = 20)
    private String variant;

    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Column(name = "item_count")
    private Integer itemCount;

    @OneToMany(mappedBy = "ordine", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrdineItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "ordine", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrdinePagamento> payments = new ArrayList<>();
}
