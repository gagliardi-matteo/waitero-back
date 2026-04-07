package com.waitero.back.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface OrderSummaryProjection {
    Long getId();
    Integer getTableId();
    String getStatus();
    LocalDateTime getPaidAt();
    LocalDateTime getCreatedAt();
    LocalDateTime getUpdatedAt();
    BigDecimal getTotale();
    Integer getItemCount();
}
