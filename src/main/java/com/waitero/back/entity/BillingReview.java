package com.waitero.back.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "billing_review")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ristoratore_id", nullable = false)
    private Ristoratore ristoratore;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "gross_revenue_snapshot", nullable = false, precision = 14, scale = 2)
    private BigDecimal grossRevenueSnapshot;

    @Column(name = "order_count_snapshot", nullable = false)
    private Integer orderCountSnapshot;

    @Column(name = "commission_percentage_snapshot", nullable = false, precision = 8, scale = 6)
    private BigDecimal commissionPercentageSnapshot;

    @Column(name = "minimum_monthly_fee_snapshot", nullable = false, precision = 12, scale = 2)
    private BigDecimal minimumMonthlyFeeSnapshot;

    @Column(name = "calculated_fee_snapshot", nullable = false, precision = 14, scale = 2)
    private BigDecimal calculatedFeeSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BillingReviewStatus status;

    @Column(name = "stripe_invoice_id", length = 128)
    private String stripeInvoiceId;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "billingReview", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BillingReviewOrderSnapshot> orderSnapshots = new ArrayList<>();

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
