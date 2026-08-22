package com.waitero.back.service;

import com.waitero.back.entity.DemoSession;
import com.waitero.back.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DemoOrderServiceTest {
    @Test
    void rejectsOrdersPastConfiguredLimit() {
        DemoSessionService sessions = mock(DemoSessionService.class);
        DemoSession session = DemoSession.builder().id(UUID.randomUUID()).orderCount(10).build();
        when(sessions.require("token-a")).thenReturn(session);
        DemoOrderService service = new DemoOrderService(sessions, mock(DemoSessionRepository.class),
                mock(OrdineRepository.class), mock(PiattoRepository.class), mock(DemoStreamService.class));
        ReflectionTestUtils.setField(service, "maxOrders", 10);
        ReflectionTestUtils.setField(service, "maxLines", 20);
        ReflectionTestUtils.setField(service, "maxQuantity", 10);

        com.waitero.back.dto.CustomerOrderItemRequest item = new com.waitero.back.dto.CustomerOrderItemRequest();
        item.setDishId(1L);
        item.setQuantity(1);
        com.waitero.back.dto.DemoOrderRequest request = new com.waitero.back.dto.DemoOrderRequest();
        request.setItems(java.util.List.of(item));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.submit("token-a", request));
        assertEquals(429, error.getStatusCode().value());
    }

    @Test
    void activeOrdersAreAlwaysQueriedByCurrentSessionId() {
        UUID sessionA = UUID.randomUUID();
        DemoSessionService sessions = mock(DemoSessionService.class);
        when(sessions.require("token-a")).thenReturn(DemoSession.builder().id(sessionA).build());
        OrdineRepository orders = mock(OrdineRepository.class);
        when(orders.findAllByDemoSessionIdAndStatusInOrderByCreatedAtDesc(eq(sessionA), any())).thenReturn(java.util.List.of());
        DemoOrderService service = new DemoOrderService(sessions, mock(DemoSessionRepository.class),
                orders, mock(PiattoRepository.class), mock(DemoStreamService.class));

        service.active("token-a");

        verify(orders).findAllByDemoSessionIdAndStatusInOrderByCreatedAtDesc(eq(sessionA), any());
        verify(orders, never()).findAllByRistoratoreIdAndStatusInOrderByCreatedAtDesc(any(), any());
    }
}
