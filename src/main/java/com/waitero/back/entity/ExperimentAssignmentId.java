package com.waitero.back.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class ExperimentAssignmentId implements Serializable {

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "restaurant_id")
    private Long restaurantId;
}