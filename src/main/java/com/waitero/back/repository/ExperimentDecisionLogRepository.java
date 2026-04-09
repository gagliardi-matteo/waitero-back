package com.waitero.back.repository;

import com.waitero.back.entity.ExperimentDecisionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentDecisionLogRepository extends JpaRepository<ExperimentDecisionLog, Long> {
}