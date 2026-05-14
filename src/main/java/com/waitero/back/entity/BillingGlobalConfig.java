package com.waitero.back.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "billing_global_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingGlobalConfig {

    @Id
    private Long id;

    @Column(name = "commission_percentage", nullable = false, precision = 8, scale = 6)
    private BigDecimal commissionPercentage;

    @Column(name = "minimum_monthly_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal minimumMonthlyFee;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (id == null) {
            id = 1L;
        }
        updatedAt = LocalDateTime.now();
    }
}
