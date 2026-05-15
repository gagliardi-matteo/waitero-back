package com.waitero.back.repository;

import com.waitero.back.entity.EventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface EventLogRepository extends JpaRepository<EventLog, UUID> {
    long countBySessionIdAndCreatedAtAfter(String sessionId, LocalDateTime createdAt);
    long deleteByCreatedAtBefore(LocalDateTime createdAt);

    @Query("""
            select count(e) > 0
            from EventLog e
            where e.sessionId = :sessionId
              and e.eventType = :eventType
              and ((:dishId is null and e.dishId is null) or e.dishId = :dishId)
              and e.createdAt >= :createdAt
            """)
    boolean existsRecentDuplicate(
            @Param("sessionId") String sessionId,
            @Param("dishId") Long dishId,
            @Param("eventType") String eventType,
            @Param("createdAt") LocalDateTime createdAt
    );
}
