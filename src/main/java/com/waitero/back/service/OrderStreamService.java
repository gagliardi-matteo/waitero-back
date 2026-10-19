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
        emittersByRestaurant.compute(restaurantId, (id, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(emitter);
            return list;
        });

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
        } catch (IOException | IllegalStateException e) {
            removeRestaurantEmitter(restaurantId, emitter);
            completeSafely(emitter);
        }

        return emitter;
    }

    public SseEmitter subscribeCustomerTable(Long restaurantId, Integer tableId) {
        String key = customerTableKey(restaurantId, tableId);
        SseEmitter emitter = new SseEmitter(0L);
        emittersByCustomerTable.compute(key, (unused, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(emitter);
            return list;
        });

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
        } catch (IOException | IllegalStateException e) {
            removeCustomerEmitter(key, emitter);
            completeSafely(emitter);
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
        AfterCommitEvents.publish(() -> publishCommittedCustomerTableUpdate(restaurantId, tableId, type));
    }

    private void publishCommittedCustomerTableUpdate(Long restaurantId, Integer tableId, String type) {
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
            } catch (IOException | IllegalStateException e) {
                removeCustomerEmitter(key, emitter);
                completeSafely(emitter);
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

    public void publishWaiterCall(Long restaurantId, Integer tableId) {
        OrderEventDTO payload = OrderEventDTO.builder()
                .type("WAITER_CALLED")
                .restaurantId(restaurantId)
                .tableId(tableId)
                .message("Il tavolo " + tableId + " ha chiamato il cameriere")
                .build();
        broadcastRestaurantEvent(restaurantId, "orders-updated", payload);
    }

    @Scheduled(fixedDelay = 20000)
    public void publishHeartbeats() {
        broadcastHeartbeats(emittersByRestaurant);
        broadcastHeartbeats(emittersByCustomerTable);
    }

    private void broadcastRestaurantEvent(Long restaurantId, String eventName, OrderEventDTO payload) {
        AfterCommitEvents.publish(() -> broadcastCommittedRestaurantEvent(restaurantId, eventName, payload));
    }

    private void broadcastCommittedRestaurantEvent(Long restaurantId, String eventName, OrderEventDTO payload) {
        List<SseEmitter> emitters = emittersByRestaurant.get(restaurantId);
        if (emitters == null) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                removeRestaurantEmitter(restaurantId, emitter);
                completeSafely(emitter);
            }
        }
    }

    private String customerTableKey(Long restaurantId, Integer tableId) {
        return restaurantId + ":" + tableId;
    }

    private void removeRestaurantEmitter(Long restaurantId, SseEmitter emitter) {
        emittersByRestaurant.computeIfPresent(restaurantId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private void removeCustomerEmitter(String key, SseEmitter emitter) {
        emittersByCustomerTable.computeIfPresent(key, (unused, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private <K> void broadcastHeartbeats(Map<K, CopyOnWriteArrayList<SseEmitter>> emittersByKey) {
        for (Map.Entry<K, CopyOnWriteArrayList<SseEmitter>> entry : emittersByKey.entrySet()) {
            K key = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("keepalive"));
                } catch (IOException | IllegalStateException e) {
                    if (key instanceof Long restaurantId) {
                        removeRestaurantEmitter(restaurantId, emitter);
                    } else if (key instanceof String customerKey) {
                        removeCustomerEmitter(customerKey, emitter);
                    }
                    completeSafely(emitter);
                }
            }
        }
    }

    private void completeSafely(SseEmitter emitter) {
        try { emitter.complete(); } catch (IllegalStateException ignored) { }
    }
}
