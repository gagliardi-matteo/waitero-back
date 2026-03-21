package com.waitero.back.repository;

import com.waitero.back.entity.TableAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableAccessLogRepository extends JpaRepository<TableAccessLog, Long> {
}
