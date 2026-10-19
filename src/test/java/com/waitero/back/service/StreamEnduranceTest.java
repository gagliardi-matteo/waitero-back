package com.waitero.back.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Deterministic emitter lifecycle tests; these do not claim socket/proxy endurance. */
class StreamEnduranceTest {
    @SuppressWarnings("unchecked")
    private static <K> Map<K, CopyOnWriteArrayList<SseEmitter>> map(Object service, String name) throws Exception {
        Field field = service.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (Map<K, CopyOnWriteArrayList<SseEmitter>>) field.get(service);
    }
    private static SseEmitter dead() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("socket gone")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        doThrow(new IllegalStateException("already complete")).when(emitter).complete();
        return emitter;
    }
    @Test void failedCompletionMustNotAbortOrderFanoutOrLeakEmitter() throws Exception {
        OrderStreamService service = new OrderStreamService();
        SseEmitter live = mock(SseEmitter.class);
        Map<Long, CopyOnWriteArrayList<SseEmitter>> streams = map(service, "emittersByRestaurant");
        streams.put(1L, new CopyOnWriteArrayList<>(List.of(dead(), live)));
        assertDoesNotThrow(() -> service.publishOrderUpdate(1L, "UPDATED", 10L, "APERTO"));
        verify(live).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(List.of(live), streams.get(1L));
    }
    @Test void failedCompletionMustNotAbortCustomerOrHeartbeatCleanup() throws Exception {
        OrderStreamService service = new OrderStreamService();
        Map<String, CopyOnWriteArrayList<SseEmitter>> streams = map(service, "emittersByCustomerTable");
        streams.put("1:2", new CopyOnWriteArrayList<>(List.of(dead())));
        assertDoesNotThrow(() -> service.publishCustomerTableUpdate(1L, 2, "UPDATED"));
        assertTrue(streams.isEmpty());
        streams.put("1:2", new CopyOnWriteArrayList<>(List.of(dead())));
        assertDoesNotThrow(service::publishHeartbeats);
        assertTrue(streams.isEmpty());
    }
    @Test void demoDeadEmittersMustNotAffectOtherSessionsAndMustBeRemoved() throws Exception {
        DemoStreamService service = new DemoStreamService();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        SseEmitter liveA = mock(SseEmitter.class), liveB = mock(SseEmitter.class);
        Map<UUID, CopyOnWriteArrayList<SseEmitter>> streams = map(service, "emitters");
        streams.put(a, new CopyOnWriteArrayList<>(List.of(dead(), liveA)));
        streams.put(b, new CopyOnWriteArrayList<>(List.of(liveB)));
        assertDoesNotThrow(() -> service.publish(a, 10L, "APERTO"));
        verify(liveA, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(liveB, never()).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(List.of(liveA), streams.get(a));
        streams.get(a).add(dead());
        assertDoesNotThrow(() -> service.close(a));
        assertFalse(streams.containsKey(a));
        assertTrue(streams.containsKey(b));
    }
    @Test void concurrentSubscribeAndDisconnectMustNotDetachLiveEmitters() throws Exception {
        OrderStreamService order = new OrderStreamService();
        DemoStreamService demo = new DemoStreamService();
        UUID session = UUID.randomUUID();
        Method removeOrder = OrderStreamService.class.getDeclaredMethod("removeRestaurantEmitter", Long.class, SseEmitter.class);
        Method removeDemo = DemoStreamService.class.getDeclaredMethod("remove", UUID.class, SseEmitter.class);
        removeOrder.setAccessible(true); removeDemo.setAccessible(true);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        long memoryBefore = usedMemory(); int threadsBefore = Thread.activeCount();
        List<SseEmitter> retainedOrder = new CopyOnWriteArrayList<>(), retainedDemo = new CopyOnWriteArrayList<>();
        long started = System.nanoTime();
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) tasks.add(() -> {
                for (int cycle = 0; cycle < 500; cycle++) {
                    SseEmitter o = order.subscribe(1L), d = demo.subscribe(session);
                    if (cycle % 100 == 0) { retainedOrder.add(o); retainedDemo.add(d); }
                    else { removeOrder.invoke(order, 1L, o); removeDemo.invoke(demo, session, d); }
                }
                return null;
            });
            for (Future<Void> future : pool.invokeAll(tasks)) future.get(30, TimeUnit.SECONDS);
            Map<Long, CopyOnWriteArrayList<SseEmitter>> orderMap = map(order, "emittersByRestaurant");
            Map<UUID, CopyOnWriteArrayList<SseEmitter>> demoMap = map(demo, "emitters");
            assertEquals(40, orderMap.get(1L).size()); assertEquals(40, demoMap.get(session).size());
            assertTrue(orderMap.get(1L).containsAll(retainedOrder)); assertTrue(demoMap.get(session).containsAll(retainedDemo));
            for (SseEmitter emitter : retainedOrder) removeOrder.invoke(order, 1L, emitter);
            for (SseEmitter emitter : retainedDemo) removeDemo.invoke(demo, session, emitter);
            assertTrue(orderMap.isEmpty()); assertTrue(demoMap.isEmpty());
            System.out.printf("SSE_LIFECYCLE subscriptions=8000 removed=8000 orphan=0 elapsedMs=%d heapBefore=%d heapAfter=%d threadsBefore=%d threadsDuring=%d%n", (System.nanoTime()-started)/1_000_000, memoryBefore, usedMemory(), threadsBefore, Thread.activeCount());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    private static long usedMemory() { Runtime r = Runtime.getRuntime(); return r.totalMemory()-r.freeMemory(); }
}
