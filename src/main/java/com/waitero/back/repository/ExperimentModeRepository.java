package com.waitero.back.repository;

import com.waitero.back.entity.ExperimentMode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExperimentModeRepository extends JpaRepository<ExperimentMode, Long> {
    Optional<ExperimentMode> findByRestaurantId(Long restaurantId);
}