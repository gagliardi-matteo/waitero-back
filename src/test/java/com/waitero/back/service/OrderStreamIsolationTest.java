package com.waitero.back.service;

import com.waitero.back.BackApplication;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BackApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderStreamIsolationTest {

    private static final long RESTAURANT_A = 960001L;
    private static final long RESTAURANT_B = 960002L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private OrderStreamService streamService;
    @MockitoBean private TavoloService tavoloService;

    @Test
    void restaurantEventsMustNeverCrossTenantStreams() throws Exception {
        SseEmitter streamA = mock(SseEmitter.class);
        SseEmitter streamB = mock(SseEmitter.class);
        restaurantEmitters().put(RESTAURANT_A, new CopyOnWriteArrayList<>(List.of(streamA)));
        restaurantEmitters().put(RESTAURANT_B, new CopyOnWriteArrayList<>(List.of(streamB)));
        streamService.publishOrderUpdate(RESTAURANT_A, "ORDER_A_ONLY", 111L, "APERTO");
        verify(streamA).send(any(SseEmitter.SseEventBuilder.class));
        verify(streamB, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void customerEventsMustBeIsolatedByRestaurantAndTable() throws Exception {
        SseEmitter a1 = mock(SseEmitter.class);
        SseEmitter a2 = mock(SseEmitter.class);
        SseEmitter b1 = mock(SseEmitter.class);
        customerEmitters().put(RESTAURANT_A + ":1", new CopyOnWriteArrayList<>(List.of(a1)));
        customerEmitters().put(RESTAURANT_A + ":2", new CopyOnWriteArrayList<>(List.of(a2)));
        customerEmitters().put(RESTAURANT_B + ":1", new CopyOnWriteArrayList<>(List.of(b1)));
        streamService.publishCustomerTableUpdate(RESTAURANT_A, 1, "A1_ONLY");
        verify(a1).send(any(SseEmitter.SseEventBuilder.class));
        verify(a2, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(b1, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void mixedTokenAndTenantContextMustRejectSubscriptionWithoutCreatingEmitter() throws Exception {
        when(tavoloService.validateCustomerAccess("token-a", String.valueOf(RESTAURANT_B), 2, "device-a", null))
                .thenReturn(false);
        int before = customerEmitterCount();

        mockMvc.perform(get("/api/customer/orders/stream")
                        .param("token", "token-a")
                        .param("restaurantId", String.valueOf(RESTAURANT_B))
                        .param("tableId", "2")
                        .param("deviceId", "device-a"))
                .andExpect(status().isForbidden());

        assertEquals(before, customerEmitterCount());
    }

    @Test
    void repeatedConnectAndDisconnectMustRemoveEveryEmitter() throws Exception {
        CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 100; i++) {
            SseEmitter emitter = mock(SseEmitter.class);
            doThrow(new IllegalStateException("closed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
            emitters.add(emitter);
        }
        restaurantEmitters().put(RESTAURANT_A, emitters);
        assertEquals(100, restaurantEmitterCount());
        streamService.publishOrderUpdate(RESTAURANT_A, "CLEANUP", 1L, "APERTO");
        assertEquals(0, restaurantEmitterCount());
    }

    private String token(long restaurantId) {
        return jwtService.generateAccessToken(BackofficeUser.builder().id(restaurantId + 1000)
                .email("sse-" + restaurantId + "@test.local").provider("LOCAL")
                .role(BackofficeRole.RISTORATORE).restaurantId(restaurantId)
                .createdAt(LocalDateTime.now()).build());
    }

    @SuppressWarnings("unchecked")
    private int restaurantEmitterCount() throws Exception {
        Field field = OrderStreamService.class.getDeclaredField("emittersByRestaurant");
        field.setAccessible(true);
        return ((Map<Long, CopyOnWriteArrayList<SseEmitter>>) field.get(streamService)).values().stream().mapToInt(List::size).sum();
    }

    @SuppressWarnings("unchecked")
    private int customerEmitterCount() throws Exception {
        Field field = OrderStreamService.class.getDeclaredField("emittersByCustomerTable");
        field.setAccessible(true);
        return ((Map<String, CopyOnWriteArrayList<SseEmitter>>) field.get(streamService)).values().stream().mapToInt(List::size).sum();
    }
    @SuppressWarnings("unchecked")
    private Map<Long, CopyOnWriteArrayList<SseEmitter>> restaurantEmitters() throws Exception {
        Field field = OrderStreamService.class.getDeclaredField("emittersByRestaurant");
        field.setAccessible(true);
        return (Map<Long, CopyOnWriteArrayList<SseEmitter>>) field.get(streamService);
    }

    @SuppressWarnings("unchecked")
    private Map<String, CopyOnWriteArrayList<SseEmitter>> customerEmitters() throws Exception {
        Field field = OrderStreamService.class.getDeclaredField("emittersByCustomerTable");
        field.setAccessible(true);
        return (Map<String, CopyOnWriteArrayList<SseEmitter>>) field.get(streamService);
    }

}
