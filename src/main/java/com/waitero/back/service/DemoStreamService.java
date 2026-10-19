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
        emitters.compute(sessionId, (ignored, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(emitter);
            return list;
        });
        Runnable remove = () -> remove(sessionId, emitter);
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(error -> remove.run());
        send(emitter, "connected", OrderEventDTO.builder().type("CONNECTED").build(), remove);
        return emitter;
    }

    public void publish(UUID sessionId, Long orderId, String status) {
        AfterCommitEvents.publish(() -> publishCommitted(sessionId, orderId, status));
    }

    private void publishCommitted(UUID sessionId, Long orderId, String status) {
        OrderEventDTO event = OrderEventDTO.builder().type("ORDER_UPDATED").orderId(orderId).status(status).build();
        for (SseEmitter emitter : emitters.getOrDefault(sessionId, new CopyOnWriteArrayList<>())) {
            send(emitter, "orders-updated", event, () -> remove(sessionId, emitter));
            send(emitter, "customer-order-updated", event, () -> remove(sessionId, emitter));
        }
    }

    public void close(UUID sessionId) {
        List<SseEmitter> closed = emitters.remove(sessionId);
        if (closed != null) for (SseEmitter emitter : closed) {
            try { emitter.complete(); } catch (IllegalStateException ignored) { }
        }
    }

    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        emitters.forEach((id, list) -> list.forEach(emitter -> send(emitter, "heartbeat", "keepalive", () -> remove(id, emitter))));
    }

    private void send(SseEmitter emitter, String name, Object data, Runnable onError) {
        try { emitter.send(SseEmitter.event().name(name).data(data)); }
        catch (IOException | IllegalStateException ex) {
            onError.run();
            try { emitter.complete(); } catch (IllegalStateException ignored) { }
        }
    }
    private void remove(UUID id, SseEmitter emitter) {
        emitters.computeIfPresent(id, (key, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
