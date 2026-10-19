package com.waitero.back.service;

import com.waitero.back.dto.PaymentRequest;
import com.waitero.back.dto.PaymentAllocationRequest;
import com.waitero.back.entity.*;
import com.waitero.back.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final UUID paymentSessionId = UUID.randomUUID();
    private final Ristoratore paymentRestaurant = Ristoratore.builder().id(7L).build();
    private final DemoSessionService paymentSessions = mock(DemoSessionService.class);
    private final OrdineRepository paymentOrders = mock(OrdineRepository.class);
    private final DemoStreamService paymentStreams = mock(DemoStreamService.class);
    private final DemoSession paymentSession = DemoSession.builder().id(paymentSessionId).ristoratore(paymentRestaurant)
            .tavolo(Tavolo.builder().numero(3).waiterCallPending(false).build()).build();

    private Ordine splitOrder() {
        Ordine order = Ordine.builder().id(11L).ristoratore(paymentRestaurant).tableId(3).status(OrderStatus.APERTO)
                .totale(new BigDecimal("30.00")).items(new ArrayList<>()).payments(new ArrayList<>()).build();
        order.getItems().add(OrdineItem.builder().id(21L).ordine(order).piatto(Piatto.builder().id(1L).build())
                .nome("Pizza").quantity(2).prezzoUnitario(new BigDecimal("10.00")).build());
        order.getItems().add(OrdineItem.builder().id(22L).ordine(order).piatto(Piatto.builder().id(2L).build())
                .nome("Birra").quantity(2).prezzoUnitario(new BigDecimal("5.00")).build());
        return order;
    }

    private DemoOrderService paymentService(Ordine order) {
        when(paymentSessions.require("token-a")).thenReturn(paymentSession);
        when(paymentOrders.findByIdAndDemoSessionId(11L, paymentSessionId)).thenReturn(Optional.of(order));
        when(paymentOrders.saveAndFlush(order)).thenReturn(order);
        return new DemoOrderService(paymentSessions, mock(DemoSessionRepository.class), paymentOrders,
                mock(PiattoRepository.class), paymentStreams, mock(CustomerOrderSubmissionRepository.class), mock(JdbcTemplate.class));
    }

    private PaymentAllocationRequest allocation(long item, int quantity) {
        PaymentAllocationRequest result = new PaymentAllocationRequest();
        result.setOrderItemId(item);
        result.setQuantity(quantity);
        return result;
    }

    @Test
    void splitChargesOnlySelectedDishesAndPersistsQuantitiesAcrossReads() {
        Ordine order = splitOrder();
        DemoOrderService service = paymentService(order);
        PaymentRequest request = new PaymentRequest();
        request.setPaymentMode("SPLIT_ITEMS");
        request.setAllocations(java.util.List.of(allocation(21L, 1), allocation(22L, 1)));
        request.setAmount(new BigDecimal("30.00")); // Selection, not a client-supplied total, determines the charge.

        var result = service.pay("token-a", 11L, request);

        assertEquals(new BigDecimal("15.00"), result.getPaidAmount());
        assertEquals(new BigDecimal("15.00"), result.getRemainingAmount());
        assertEquals("PARZIALMENTE_PAGATO", result.getStatus());
        assertEquals(1, result.getItems().get(0).getPaidQuantity());
        assertEquals(1, result.getItems().get(0).getRemainingQuantity());
        assertEquals(2, result.getPayments().get(0).getAllocations().size());
        assertEquals(1, service.get("token-a", 11L).getItems().get(0).getPaidQuantity());

        result = service.pay("token-a", 11L, request);
        assertEquals(new BigDecimal("30.00"), result.getPaidAmount());
        assertEquals(new BigDecimal("0.00"), result.getRemainingAmount());
        assertEquals("PAGATO", result.getStatus());
        assertEquals(0, result.getItems().get(0).getRemainingQuantity());
        assertThrows(ResponseStatusException.class, () -> service.pay("token-a", 11L, request));
    }

    @Test
    void rejectsEmptyForeignAndExcessiveSelectionsWithoutCharging() {
        Ordine order = splitOrder();
        DemoOrderService service = paymentService(order);
        PaymentRequest request = new PaymentRequest();
        request.setPaymentMode("SPLIT_ITEMS");
        assertThrows(ResponseStatusException.class, () -> service.pay("token-a", 11L, request));
        for (var selection : java.util.List.of(
                java.util.List.of(allocation(999L, 1)),
                java.util.List.of(allocation(21L, 0)),
                java.util.List.of(allocation(21L, 3)),
                java.util.List.of(allocation(21L, 2), allocation(21L, 1)))) {
            request.setAllocations(selection);
            assertThrows(ResponseStatusException.class, () -> service.pay("token-a", 11L, request));
        }
        assertEquals(0, order.getPayments().size());
        verify(paymentOrders, never()).saveAndFlush(any());
    }

    @Test
    void rejectsPreviouslyPaidQuantitiesWhileOtherDishesRemainUnpaid() {
        Ordine order = splitOrder();
        DemoOrderService service = paymentService(order);
        PaymentRequest request = new PaymentRequest();
        request.setPaymentMode("SPLIT_ITEMS");
        request.setAllocations(java.util.List.of(allocation(21L, 2)));
        service.pay("token-a", 11L, request);
        request.setAllocations(java.util.List.of(allocation(21L, 1)));
        assertThrows(ResponseStatusException.class, () -> service.pay("token-a", 11L, request));
        assertEquals(new BigDecimal("10.00"), service.get("token-a", 11L).getRemainingAmount());
        assertEquals(1, order.getPayments().size());
    }

    @Test
    void waiterCallIsStoredOnSessionTableAndCanBeAcknowledged() {
        DemoOrderService service = paymentService(splitOrder());
        service.callWaiter("token-a");
        assertEquals(true, service.waiterCallState("token-a").pending());
        org.junit.jupiter.api.Assertions.assertNotNull(service.waiterCallState("token-a").calledAt());
        verify(paymentStreams).publishWaiterCall(paymentSessionId, 7L, 3, true);
        service.clearWaiterCall("token-a");
        assertEquals(false, service.waiterCallState("token-a").pending());
        verify(paymentStreams).publishWaiterCall(paymentSessionId, 7L, 3, false);
    }

    @Test
    void rejectsOrdersPastConfiguredLimit() {
        DemoSessionService sessions = mock(DemoSessionService.class);
        DemoSession session = DemoSession.builder().id(UUID.randomUUID()).orderCount(10)
                .ristoratore(Ristoratore.builder().id(7L).build())
                .tavolo(Tavolo.builder().numero(3).build()).build();
        when(sessions.require("token-a")).thenReturn(session);
        DemoOrderService service = new DemoOrderService(sessions, mock(DemoSessionRepository.class),
                mock(OrdineRepository.class), mock(PiattoRepository.class), mock(DemoStreamService.class),
                mock(CustomerOrderSubmissionRepository.class), mock(JdbcTemplate.class));
        ReflectionTestUtils.setField(service, "maxOrders", 10);
        ReflectionTestUtils.setField(service, "maxLines", 20);
        ReflectionTestUtils.setField(service, "maxQuantity", 10);

        com.waitero.back.dto.CustomerOrderItemRequest item = new com.waitero.back.dto.CustomerOrderItemRequest();
        item.setDishId(1L);
        item.setQuantity(1);
        com.waitero.back.dto.DemoOrderRequest request = new com.waitero.back.dto.DemoOrderRequest();
        request.setRequestId(UUID.randomUUID().toString());
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
                orders, mock(PiattoRepository.class), mock(DemoStreamService.class),
                mock(CustomerOrderSubmissionRepository.class), mock(JdbcTemplate.class));

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
                orders, mock(PiattoRepository.class), mock(DemoStreamService.class),
                mock(CustomerOrderSubmissionRepository.class), mock(JdbcTemplate.class));
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
