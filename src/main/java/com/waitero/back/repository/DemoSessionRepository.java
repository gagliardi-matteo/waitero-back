package com.waitero.back.repository;

import com.waitero.back.entity.DemoSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.*;

public interface DemoSessionRepository extends JpaRepository<DemoSession, UUID> {
    Optional<DemoSession> findByTokenHash(String tokenHash);
    List<DemoSession> findAllByExpiresAtBefore(LocalDateTime cutoff);
}
