package com.waitero.back.repository;

import com.waitero.back.entity.ExperimentConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExperimentConfigRepository extends JpaRepository<ExperimentConfig, Long> {
    Optional<ExperimentConfig> findByRestaurantId(Long restaurantId);
}