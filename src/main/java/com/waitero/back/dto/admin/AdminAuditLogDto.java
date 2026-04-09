package com.waitero.back.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAuditLogDto {
    private UUID id;
    private Long masterUserId;
    private Long restaurantId;
    private String action;
    private String entityType;
    private String entityId;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
}
