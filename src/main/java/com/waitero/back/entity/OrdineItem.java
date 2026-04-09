package com.waitero.back.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrdineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "ordine_id")
    private Ordine ordine;

    @ManyToOne(optional = false)
    private Piatto piatto;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal prezzoUnitario;

    @Column(nullable = false)
    private Integer quantity;

    private String imageUrl;

    @Column(length = 50)
    private String source;

    @Column(name = "source_dish_id")
    private Long sourceDishId;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
