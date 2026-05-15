package com.waitero.back.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "stripe_webhook_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StripeWebhookEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 96)
    private String eventType;

    @Column(name = "invoice_id", length = 128)
    private String invoiceId;

    @Column(name = "customer_id", length = 128)
    private String customerId;

    @Column(name = "billing_review_id")
    private Long billingReviewId;

    @Column(name = "processing_status", nullable = false, length = 32)
    @Builder.Default
    private String processingStatus = "PROCESSED";

    @Column(name = "error_summary", length = 512)
    private String errorSummary;

    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @PrePersist
    void onCreate() {
        if (processedAt == null) {
            processedAt = LocalDateTime.now();
        }
    }
}
