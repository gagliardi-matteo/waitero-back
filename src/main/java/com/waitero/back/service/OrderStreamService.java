package com.waitero.back.service;

import com.waitero.back.dto.OrderEventDTO;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class OrderStreamService {

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emittersByRestaurant = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByCustomerTable = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long restaurantId) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersByRestaurant.computeIfAbsent(restaurantId, id -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeRestaurantEmitter(restaurantId, emitter));
        emitter.onTimeout(() -> removeRestaurantEmitter(restaurantId, emitter));
        emitter.onError(ex -> removeRestaurantEmitter(restaurantId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(OrderEventDTO.builder()
                            .type("CONNECTED")
                            .restaurantId(restaurantId)
                            .build()));
        } catch (IOException e) {
            emitter.complete();
            removeRestaurantEmitter(restaurantId, emitter);
        }

        return emitter;
    }

    public SseEmitter subscribeCustomerTable(Long restaurantId, Integer tableId) {
        String key = customerTableKey(restaurantId, tableId);
        SseEmitter emitter = new SseEmitter(0L);
        emittersByCustomerTable.computeIfAbsent(key, unused -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeCustomerEmitter(key, emitter));
        emitter.onTimeout(() -> removeCustomerEmitter(key, emitter));
        emitter.onError(ex -> removeCustomerEmitter(key, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(OrderEventDTO.builder()
                            .type("CUSTOMER_CONNECTED")
                            .restaurantId(restaurantId)
                            .tableId(tableId)
                            .build()));
        } catch (IOException e) {
            emitter.complete();
            removeCustomerEmitter(key, emitter);
        }

        return emitter;
    }

    public void publishOrderUpdate(Long restaurantId, String type, Long orderId, String status) {
        OrderEventDTO payload = OrderEventDTO.builder()
                .type(type)
                .orderId(orderId)
                .restaurantId(restaurantId)
                .status(status)
                .build();
        broadcastRestaurantEvent(restaurantId, "orders-updated", payload);
    }

    public void publishCustomerTableUpdate(Long restaurantId, Integer tableId, String type) {
        String key = customerTableKey(restaurantId, tableId);
        OrderEventDTO payload = OrderEventDTO.builder()
                .type(type)
                .restaurantId(restaurantId)
                .tableId(tableId)
                .build();

        List<SseEmitter> emitters = emittersByCustomerTable.get(key);
        if (emitters == null) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("customer-order-updated").data(payload));
            } catch (IOException e) {
                emitter.complete();
                removeCustomerEmitter(key, emitter);
            }
        }
    }

    public void publishSuspiciousTableAccess(Long restaurantId, Integer tableId, Integer riskScore, String reason) {
        OrderEventDTO payload = OrderEventDTO.builder()
                .type("SUSPICIOUS_TABLE_ACCESS")
                .restaurantId(restaurantId)
                .tableId(tableId)
                .riskScore(riskScore)
                .reason(reason)
                .message("Possible external device accessing table")
                .build();
        broadcastRestaurantEvent(restaurantId, "orders-updated", payload);
    }

    @Scheduled(fixedDelay = 20000)
    public void publishHeartbeats() {
        broadcastHeartbeats(emittersByRestaurant);
        broadcastHeartbeats(emittersByCustomerTable);
    }

    private void broadcastRestaurantEvent(Long restaurantId, String eventName, OrderEventDTO payload) {
        List<SseEmitter> emitters = emittersByRestaurant.get(restaurantId);
        if (emitters == null) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException e) {
                emitter.complete();
                removeRestaurantEmitter(restaurantId, emitter);
            }
        }
    }

    private String customerTableKey(Long restaurantId, Integer tableId) {
        return restaurantId + ":" + tableId;
    }

    private void removeRestaurantEmitter(Long restaurantId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByRestaurant.get(restaurantId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByRestaurant.remove(restaurantId);
        }
    }

    private void removeCustomerEmitter(String key, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByCustomerTable.get(key);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByCustomerTable.remove(key);
        }
    }

    private <K> void broadcastHeartbeats(Map<K, CopyOnWriteArrayList<SseEmitter>> emittersByKey) {
        for (Map.Entry<K, CopyOnWriteArrayList<SseEmitter>> entry : emittersByKey.entrySet()) {
            K key = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("keepalive"));
                } catch (IOException e) {
                    emitter.complete();
                    if (key instanceof Long restaurantId) {
                        removeRestaurantEmitter(restaurantId, emitter);
                    } else if (key instanceof String customerKey) {
                        removeCustomerEmitter(customerKey, emitter);
                    }
                }
            }
        }
    }
}
