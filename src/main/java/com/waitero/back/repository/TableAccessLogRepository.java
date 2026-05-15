package com.waitero.back.repository;

import com.waitero.back.entity.TableAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface TableAccessLogRepository extends JpaRepository<TableAccessLog, Long> {
    long deleteByTimestampBefore(LocalDateTime cutoff);
}
