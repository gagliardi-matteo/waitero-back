package com.waitero.back.repository;

import com.waitero.back.entity.AdminAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {
    List<AdminAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<AdminAuditLog> findByRestaurantIdOrderByCreatedAtDesc(Long restaurantId, Pageable pageable);
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
