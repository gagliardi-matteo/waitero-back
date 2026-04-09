package com.waitero.back.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "experiment_assignment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExperimentAssignment {

    @EmbeddedId
    private ExperimentAssignmentId id;

    @Column(length = 20, nullable = false)
    private String variant;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}