package com.waitero.back.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "experiment_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExperimentConfig {

    @Id
    @Column(name = "restaurant_id")
    private Long restaurantId;

    @Column(name = "autopilot_enabled", nullable = false)
    private boolean autopilotEnabled;

    @Column(name = "min_sample_size", nullable = false)
    private int minSampleSize;

    @Column(name = "min_uplift_percent", nullable = false)
    private double minUpliftPercent;

    @Column(name = "min_confidence", nullable = false)
    private double minConfidence;

    @Column(name = "holdout_percent", nullable = false)
    private int holdoutPercent;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}