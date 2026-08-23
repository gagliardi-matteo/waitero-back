package com.waitero.back.service;

import com.waitero.back.dto.PaymentRequest;
import com.waitero.back.entity.*;
import com.waitero.back.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;
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

    @Test
    void recordsPartialDemoPaymentAndReturnsUpdatedBalance() {
        UUID sessionId = UUID.randomUUID();
        Ristoratore restaurant = Ristoratore.builder().id(7L).build();
        DemoSession session = DemoSession.builder().id(sessionId).ristoratore(restaurant).build();
        Ordine order = Ordine.builder().id(11L).ristoratore(restaurant).tableId(3).status(OrderStatus.APERTO)
                .totale(new BigDecimal("30.00")).items(new ArrayList<>()).payments(new ArrayList<>()).build();
        DemoSessionService sessions = mock(DemoSessionService.class);
        when(sessions.require("token-a")).thenReturn(session);
        OrdineRepository orders = mock(OrdineRepository.class);
        when(orders.findByIdAndDemoSessionId(11L, sessionId)).thenReturn(Optional.of(order));
        when(orders.saveAndFlush(order)).thenReturn(order);
        DemoOrderService service = new DemoOrderService(sessions, mock(DemoSessionRepository.class),
                orders, mock(PiattoRepository.class), mock(DemoStreamService.class));
        PaymentRequest request = new PaymentRequest();
        request.setPaymentMode("PARTIAL");
        request.setAmount(new BigDecimal("12.50"));

        var result = service.pay("token-a", 11L, request);

        assertEquals(OrderStatus.PARZIALMENTE_PAGATO.name(), result.getStatus());
        assertEquals(new BigDecimal("12.50"), result.getPaidAmount());
        assertEquals(new BigDecimal("17.50"), result.getRemainingAmount());
        assertEquals(1, result.getPayments().size());
        verify(orders).saveAndFlush(order);
    }
}
