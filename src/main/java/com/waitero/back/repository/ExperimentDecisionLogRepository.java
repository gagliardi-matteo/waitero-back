package com.waitero.back.repository;

import com.waitero.back.entity.ExperimentDecisionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface ExperimentDecisionLogRepository extends JpaRepository<ExperimentDecisionLog, Long> {
    Optional<ExperimentDecisionLog> findFirstByRestaurantIdAndDecisionInOrderByCreatedAtDesc(Long restaurantId, Collection<String> decisions);
}
