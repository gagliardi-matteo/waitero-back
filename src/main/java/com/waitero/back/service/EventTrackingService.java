package com.waitero.back.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.back.dto.EventTrackingRequest;
import com.waitero.back.entity.EventLog;
import com.waitero.back.repository.EventLogRepository;
import com.waitero.back.repository.PiattoRepository;
import com.waitero.back.repository.TavoloRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EventTrackingService {

    private static final int MAX_EVENTS_PER_SESSION_PER_MINUTE = 120;
    private static final Duration DEDUPE_WINDOW = Duration.ofSeconds(1);
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "view_dish",
            "view_menu_item",
            "click_dish",
            "add_to_cart",
            "remove_from_cart",
            "order_submitted",
            "scroll",
            "time_spent"
    );

    private final EventLogRepository eventLogRepository;
    private final PiattoRepository piattoRepository;
    private final TavoloRepository tavoloRepository;
    private final ObjectMapper objectMapper;

    // Registra un evento generico di tracking dopo aver validato contesto e duplicati.
    public void track(EventTrackingRequest request) {
        if (request == null) {
            throw new RuntimeException("Payload evento mancante");
        }

        String eventType = normalizeEventType(request.getEventType());
        String sessionId = normalize(request.getSessionId());
        if (!isValidDishContext(request) || !isValidTableContext(request)) {
            return;
        }
        if (tooManyEventsFromSession(sessionId) || existsRecentEvent(sessionId, request.getDishId(), eventType)) {
            return;
        }

        eventLogRepository.save(EventLog.builder()
                .eventType(eventType)
                .userId(normalize(request.getUserId()))
                .sessionId(sessionId)
                .restaurantId(request.getRestaurantId())
                .tableId(request.getTableId())
                .dishId(request.getDishId())
                .metadata(readMetadata(request))
                .build());
    }

    // Registra il momento in cui un ordine viene inviato, con i dati necessari per l'analytics.
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

    // Registra il tempo speso su un piatto per alimentare i segnali di interesse.
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

    // Verifica che il piatto, se presente, appartenga davvero al ristoratore corretto.
    private boolean isValidDishContext(EventTrackingRequest request) {
        if (request.getDishId() == null) {
            return true;
        }
        if (request.getRestaurantId() == null) {
            return false;
        }
        return piattoRepository.existsByIdAndRistoratoreId(request.getDishId(), request.getRestaurantId());
    }

    // Verifica che il tavolo, se presente, sia valido per quel locale.
    private boolean isValidTableContext(EventTrackingRequest request) {
        if (request.getTableId() == null) {
            return true;
        }
        if (request.getRestaurantId() == null) {
            return false;
        }
        return tavoloRepository.existsByRistoratoreIdAndNumero(request.getRestaurantId(), request.getTableId());
    }

    // Limita il numero di eventi per sessione per evitare rumore o abuso del tracking.
    private boolean tooManyEventsFromSession(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        LocalDateTime since = LocalDateTime.now().minus(RATE_LIMIT_WINDOW);
        return eventLogRepository.countBySessionIdAndCreatedAtAfter(sessionId, since) >= MAX_EVENTS_PER_SESSION_PER_MINUTE;
    }

    // Elimina duplicati ravvicinati dello stesso evento per evitare doppie registrazioni.
    private boolean existsRecentEvent(String sessionId, Long dishId, String eventType) {
        if (sessionId == null || eventType == null) {
            return false;
        }
        LocalDateTime since = LocalDateTime.now().minus(DEDUPE_WINDOW);
        return eventLogRepository.existsRecentDuplicate(sessionId, dishId, eventType, since);
    }

    // Normalizza e valida il tipo di evento ricevuto dal frontend.
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

    // Converte il metadata JSON in una mappa semplice da salvare nel log.
    private Map<String, Object> readMetadata(EventTrackingRequest request) {
        if (request.getMetadata() == null || request.getMetadata().isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(request.getMetadata(), new TypeReference<>() {
        });
    }

    // Pulisce gli input testuali eliminando spazi e stringhe vuote.
    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
