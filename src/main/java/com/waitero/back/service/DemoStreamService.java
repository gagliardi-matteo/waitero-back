package com.waitero.back.service;

import com.waitero.back.dto.OrderEventDTO;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Service
public class DemoStreamService {
    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID sessionId) {
        SseEmitter emitter = new SseEmitter(3_600_000L);
        emitters.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> remove(sessionId, emitter);
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(error -> remove.run());
        send(emitter, "connected", OrderEventDTO.builder().type("CONNECTED").build(), remove);
        return emitter;
    }

    public void publish(UUID sessionId, Long orderId, String status) {
        OrderEventDTO event = OrderEventDTO.builder().type("ORDER_UPDATED").orderId(orderId).status(status).build();
        for (SseEmitter emitter : emitters.getOrDefault(sessionId, new CopyOnWriteArrayList<>())) {
            send(emitter, "orders-updated", event, () -> remove(sessionId, emitter));
            send(emitter, "customer-order-updated", event, () -> remove(sessionId, emitter));
        }
    }

    public void close(UUID sessionId) {
        for (SseEmitter emitter : emitters.getOrDefault(sessionId, new CopyOnWriteArrayList<>())) emitter.complete();
        emitters.remove(sessionId);
    }

    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        emitters.forEach((id, list) -> list.forEach(emitter -> send(emitter, "heartbeat", "keepalive", () -> remove(id, emitter))));
    }

    private void send(SseEmitter emitter, String name, Object data, Runnable onError) {
        try { emitter.send(SseEmitter.event().name(name).data(data)); }
        catch (IOException ex) { emitter.complete(); onError.run(); }
    }
    private void remove(UUID id, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(id); if (list == null) return;
        list.remove(emitter); if (list.isEmpty()) emitters.remove(id);
    }
}
