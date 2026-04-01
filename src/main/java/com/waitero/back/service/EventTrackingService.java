package com.waitero.back.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.back.dto.EventTrackingRequest;
import com.waitero.back.entity.EventLog;
import com.waitero.back.repository.EventLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EventTrackingService {

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "view_dish",
            "click_dish",
            "add_to_cart",
            "remove_from_cart",
            "order_submitted",
            "scroll",
            "time_spent"
    );

    private final EventLogRepository eventLogRepository;
    private final ObjectMapper objectMapper;

    public void track(EventTrackingRequest request) {
        if (request == null) {
            throw new RuntimeException("Payload evento mancante");
        }

        String eventType = normalizeEventType(request.getEventType());
        eventLogRepository.save(EventLog.builder()
                .eventType(eventType)
                .userId(normalize(request.getUserId()))
                .sessionId(normalize(request.getSessionId()))
                .restaurantId(request.getRestaurantId())
                .tableId(request.getTableId())
                .dishId(request.getDishId())
                .metadata(readMetadata(request))
                .build());
    }

    public void trackOrderSubmitted(Long restaurantId, Integer tableId, String sessionId, Long userId, Long orderId, BigDecimal totalAmount, int itemCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", orderId);
        metadata.put("totalAmount", totalAmount);
        metadata.put("itemCount", itemCount);

        eventLogRepository.save(EventLog.builder()
                .eventType("order_submitted")
                .userId(userId != null ? String.valueOf(userId) : null)
                .sessionId(normalize(sessionId))
                .restaurantId(restaurantId)
                .tableId(tableId)
                .metadata(metadata)
                .build());
    }

    public void trackTimeSpent(Long restaurantId, Integer tableId, Long dishId, String sessionId, Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("seconds", duration.toSeconds());

        eventLogRepository.save(EventLog.builder()
                .eventType("time_spent")
                .sessionId(normalize(sessionId))
                .restaurantId(restaurantId)
                .tableId(tableId)
                .dishId(dishId)
                .metadata(metadata)
                .build());
    }

    private String normalizeEventType(String eventType) {
        String normalized = normalize(eventType);
        if (normalized == null) {
            throw new RuntimeException("Tipo evento mancante");
        }

        String value = normalized.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EVENT_TYPES.contains(value)) {
            throw new RuntimeException("Tipo evento non supportato: " + eventType);
        }
        return value;
    }

    private Map<String, Object> readMetadata(EventTrackingRequest request) {
        if (request.getMetadata() == null || request.getMetadata().isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(request.getMetadata(), new TypeReference<>() {
        });
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
