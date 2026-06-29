package com.waitero.back.repository;

import com.waitero.back.entity.TableAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface TableAccessLogRepository extends JpaRepository<TableAccessLog, Long> {
    long deleteByTimestampBefore(LocalDateTime cutoff);

    Optional<TableAccessLog> findFirstByTavoloRistoratoreIdAndTavoloNumeroAndDeviceIdOrderByTimestampDescIdDesc(
            Long restaurantId,
            Integer tableId,
            String deviceId
    );
}
