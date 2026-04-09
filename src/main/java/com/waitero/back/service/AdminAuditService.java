package com.waitero.back.service;

import com.waitero.back.dto.admin.AdminAuditLogDto;
import com.waitero.back.entity.AdminAuditLog;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.repository.AdminAuditLogRepository;
import com.waitero.back.security.AccessContextService;
import com.waitero.back.security.BackofficePrincipal;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);
    private static final int MAX_LIMIT = 100;

    private final AdminAuditLogRepository adminAuditLogRepository;
    private final AccessContextService accessContextService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, Long restaurantId, String entityType, Object entityId, Map<String, Object> metadata) {
        try {
            BackofficePrincipal principal = accessContextService.requirePrincipal();
            if (principal.role() != BackofficeRole.MASTER) {
                return;
            }

            adminAuditLogRepository.save(AdminAuditLog.builder()
                    .masterUserId(principal.userId())
                    .restaurantId(restaurantId)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId == null ? null : String.valueOf(entityId))
                    .metadata(safeMetadata(metadata))
                    .build());
        } catch (Exception ex) {
            log.warn("Unable to write admin audit log for action {}", action, ex);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCurrentImpersonationMutation(String method, String uri, int status) {
        try {
            BackofficePrincipal principal = accessContextService.requirePrincipal();
            if (principal.role() != BackofficeRole.MASTER || !principal.isImpersonating()) {
                return;
            }

            adminAuditLogRepository.save(AdminAuditLog.builder()
                    .masterUserId(principal.userId())
                    .restaurantId(principal.actingRestaurantId())
                    .action("IMPERSONATED_" + method.toUpperCase())
                    .entityType(resolveEntityType(uri))
                    .metadata(Map.of(
                            "method", method,
                            "uri", uri,
                            "status", status
                    ))
                    .build());
        } catch (Exception ex) {
            log.warn("Unable to write impersonation audit log for {} {}", method, uri, ex);
        }
    }

    @Transactional(readOnly = true)
    public List<AdminAuditLogDto> findRecent(Long restaurantId, Integer limit) {
        int size = Math.max(1, Math.min(limit == null ? 30 : limit, MAX_LIMIT));
        PageRequest pageRequest = PageRequest.of(0, size);
        List<AdminAuditLog> logs = restaurantId == null
                ? adminAuditLogRepository.findAllByOrderByCreatedAtDesc(pageRequest)
                : adminAuditLogRepository.findByRestaurantIdOrderByCreatedAtDesc(restaurantId, pageRequest);
        return logs.stream().map(this::toDto).toList();
    }

    private Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        return metadata == null ? new HashMap<>() : new HashMap<>(metadata);
    }

    private String resolveEntityType(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String normalized = uri.startsWith("/api/") ? uri.substring(5) : uri;
        int slashIndex = normalized.indexOf('/');
        return slashIndex < 0 ? normalized : normalized.substring(0, slashIndex);
    }

    private AdminAuditLogDto toDto(AdminAuditLog log) {
        return AdminAuditLogDto.builder()
                .id(log.getId())
                .masterUserId(log.getMasterUserId())
                .restaurantId(log.getRestaurantId())
                .action(log.getAction())
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .metadata(log.getMetadata())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
