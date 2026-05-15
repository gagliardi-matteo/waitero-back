package com.waitero.back.repository;

import com.waitero.back.entity.StripeWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, String> {
    long deleteByProcessedAtBefore(LocalDateTime cutoff);
}
