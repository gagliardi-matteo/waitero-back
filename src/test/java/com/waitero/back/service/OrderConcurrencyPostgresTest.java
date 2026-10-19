package com.waitero.back.service;

import com.waitero.back.BackApplication;
import com.waitero.back.dto.CustomerOrderItemRequest;
import com.waitero.back.dto.CustomerOrderRequest;
import com.waitero.back.dto.PiattoPortionDTO;
import com.waitero.back.entity.Tavolo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(classes = BackApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderConcurrencyPostgresTest {
    private static final long RESTAURANT_ID = 9400001L;
    private static final long CATEGORY_ID = 9400002L;
    private static final long DISH_ID = 9400003L;
    private static final long SECOND_DISH_ID = 9400004L;
    private static final int TABLE_NUMBER = 7;

    @Autowired private OrdineService ordineService;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private TavoloService tavoloService;
    @MockitoBean private ExperimentService experimentService;
    @MockitoBean private UpsellService upsellService;
    @MockitoBean private EventTrackingService eventTrackingService;
    @MockitoBean private OrderStreamService orderStreamService;
    @MockitoBean private CustomerDraftService customerDraftService;
    @MockitoBean private DishPortionService dishPortionService;
    @MockitoBean private OrderPrintService orderPrintService;

    @BeforeEach
    void setUp() {
        cleanup();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into ristoratore (id, email, nome, password, provider, created_at) values (?, ?, ?, ?, ?, ?)",
                RESTAURANT_ID, "order-race@test.local", "Order Race", "pwd", "LOCAL", now);
        jdbc.update("insert into menu_category (id, business_type, code, label, sort_order, active) values (?, ?, ?, ?, ?, ?)",
                CATEGORY_ID, "RISTORANTE", "RACE", "Race", 1, true);
        insertDish(DISH_ID, "Race dish", "10.00");
        insertDish(SECOND_DISH_ID, "Second race dish", "7.00");

        when(tavoloService.validateCustomerAccess(anyString(), anyString(), anyInt(), anyString(), nullable(String.class))).thenReturn(true);
        when(tavoloService.requireActiveTable(eq(RESTAURANT_ID), eq(TABLE_NUMBER)))
                .thenReturn(Tavolo.builder().numero(TABLE_NUMBER).attivo(true).build());
        when(experimentService.getVariant(any(), eq(RESTAURANT_ID), eq(TABLE_NUMBER))).thenReturn(ExperimentService.VARIANT_HOLDOUT);
        PiattoPortionDTO portion = new PiattoPortionDTO();
        portion.setKey(DishPortionService.DEFAULT_PORTION_KEY);
        portion.setLabel("Standard");
        portion.setPrice(new BigDecimal("10.00"));
        when(dishPortionService.resolvePortion(any(), any())).thenReturn(portion);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void simultaneousFirstSubmitsMustCreateOneActiveOrderAndPreserveBothQuantities() throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            clearOrders();
            runConcurrent(request(DISH_ID), request(DISH_ID));
            assertEquals(1, activeOrderCount(), "iteration=" + iteration);
            assertEquals(2, quantityForDish(DISH_ID), "iteration=" + iteration);
        }
    }

    @Test
    void simultaneousAppendsToExistingLineMustNotLoseAnUpdate() throws Exception {
        ordineService.createOrAppend(request(DISH_ID));
        for (int iteration = 0; iteration < 20; iteration++) {
            runConcurrent(request(DISH_ID), request(DISH_ID));
            assertEquals(1 + (iteration + 1) * 2, quantityForDish(DISH_ID), "iteration=" + iteration);
            assertEquals(1, activeOrderCount(), "iteration=" + iteration);
        }
    }

    @Test
    void simultaneousAppendsOfDifferentDishesMustPreserveBothLines() throws Exception {
        ordineService.createOrAppend(request(DISH_ID));
        for (int iteration = 0; iteration < 20; iteration++) {
            runConcurrent(request(DISH_ID), request(SECOND_DISH_ID));
            assertEquals(2 + iteration, quantityForDish(DISH_ID), "first dish iteration=" + iteration);
            assertEquals(1 + iteration, quantityForDish(SECOND_DISH_ID), "second dish iteration=" + iteration);
            assertEquals(1, activeOrderCount(), "iteration=" + iteration);
        }
    }

    @Test
    void invalidPayloadsAreRejectedAtomically() {
        CustomerOrderRequest mixed = request(DISH_ID);
        CustomerOrderItemRequest invalid = item(DISH_ID, 0);
        mixed.setItems(List.of(mixed.getItems().getFirst(), invalid));
        assertThrows(RuntimeException.class, () -> ordineService.createOrAppend(mixed));
        assertEquals(0, activeOrderCount());

        for (Integer invalidQuantity : List.of(-1, 0)) {
            CustomerOrderRequest invalidRequest = request(DISH_ID);
            invalidRequest.getItems().getFirst().setQuantity(invalidQuantity);
            assertThrows(RuntimeException.class, () -> ordineService.createOrAppend(invalidRequest));
        }
        CustomerOrderRequest nullDish = request(DISH_ID);
        nullDish.getItems().getFirst().setDishId(null);
        assertThrows(RuntimeException.class, () -> ordineService.createOrAppend(nullDish));
        CustomerOrderRequest nullQuantity = request(DISH_ID);
        nullQuantity.getItems().getFirst().setQuantity(null);
        assertThrows(RuntimeException.class, () -> ordineService.createOrAppend(nullQuantity));
        assertEquals(0, activeOrderCount());
    }

    @Test
    void hugeTotalsAndIntegerOverflowAreRejectedBeforeAnythingIsPersisted() {
        CustomerOrderRequest huge = request(DISH_ID);
        huge.getItems().getFirst().setQuantity(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> ordineService.createOrAppend(huge));
        assertEquals(0, activeOrderCount());

        CustomerOrderRequest overflowing = request(DISH_ID);
        overflowing.setItems(List.of(
                item(DISH_ID, Integer.MAX_VALUE),
                item(DISH_ID, 1)
        ));
        assertThrows(ArithmeticException.class, () -> ordineService.createOrAppend(overflowing));
        assertEquals(0, activeOrderCount());
    }

    @Test
    void sequentialRetryOfSameSubmitIntentMustNotAppendTwice() {
        CustomerOrderRequest sameIntent = request(DISH_ID);
        ordineService.createOrAppend(sameIntent);
        ordineService.createOrAppend(sameIntent);
        assertEquals(1, quantityForDish(DISH_ID));
    }

    @Test
    void concurrentDuplicateOfSameSubmitIntentMustNotAppendTwice() throws Exception {
        CustomerOrderRequest sameIntent = request(DISH_ID);
        runConcurrent(sameIntent, sameIntent);
        assertEquals(1, quantityForDish(DISH_ID));
    }

    @Test
    void identicalButDistinctSubmitIntentsMustBothAppend() {
        ordineService.createOrAppend(request(DISH_ID));
        ordineService.createOrAppend(request(DISH_ID));
        assertEquals(2, quantityForDish(DISH_ID));
    }

    @Test
    void requestIdReuseWithDifferentPayloadIsRejected() {
        CustomerOrderRequest original = request(DISH_ID);
        ordineService.createOrAppend(original);
        original.getItems().getFirst().setQuantity(2);
        assertThrows(IllegalArgumentException.class, () -> ordineService.createOrAppend(original));
        assertEquals(1, quantityForDish(DISH_ID));
    }

    @Test
    void missingOrMalformedRequestIdIsRejectedWithoutPersistence() {
        CustomerOrderRequest missing = request(DISH_ID);
        missing.setRequestId(null);
        assertThrows(IllegalArgumentException.class, () -> ordineService.createOrAppend(missing));

        CustomerOrderRequest malformed = request(DISH_ID);
        malformed.setRequestId("not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> ordineService.createOrAppend(malformed));
        assertEquals(0, activeOrderCount());
    }

    private void insertDish(long dishId, String name, String price) {
        jdbc.update("insert into piatto (id, nome, descrizione, prezzo, disponibile, categoria_id, consigliato, ristoratore_id) values (?, ?, ?, ?, ?, ?, ?, ?)",
                dishId, name, "Race", new BigDecimal(price), true, CATEGORY_ID, false, RESTAURANT_ID);
    }

    private void runConcurrent(CustomerOrderRequest firstRequest, CustomerOrderRequest secondRequest) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> submitAfterBarrier(ready, start, firstRequest));
            Future<?> second = executor.submit(() -> submitAfterBarrier(ready, start, secondRequest));
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }
    }

    private void submitAfterBarrier(CountDownLatch ready, CountDownLatch start, CustomerOrderRequest request) {
        try {
            ready.countDown();
            start.await();
            ordineService.createOrAppend(request);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }

    private CustomerOrderRequest request(long dishId) {
        CustomerOrderRequest request = new CustomerOrderRequest();
        request.setToken("qr");
        request.setRestaurantId(Long.toString(RESTAURANT_ID));
        request.setTableId(TABLE_NUMBER);
        request.setDeviceId("race-device");
        request.setRequestId(UUID.randomUUID().toString());
        request.setItems(List.of(item(dishId, 1)));
        return request;
    }

    private CustomerOrderItemRequest item(long dishId, Integer quantity) {
        CustomerOrderItemRequest item = new CustomerOrderItemRequest();
        item.setDishId(dishId);
        item.setQuantity(quantity);
        return item;
    }

    private Integer activeOrderCount() {
        return jdbc.queryForObject(
                "select count(*) from customer_orders where ristoratore_id = ? and table_id = ? and status in ('APERTO','IN_PREPARAZIONE','PRONTO')",
                Integer.class, RESTAURANT_ID, TABLE_NUMBER);
    }

    private Integer quantityForDish(long dishId) {
        return jdbc.queryForObject(
                "select coalesce(sum(i.quantity), 0) from customer_order_items i join customer_orders o on o.id=i.ordine_id where o.ristoratore_id=? and o.table_id=? and i.piatto_id=?",
                Integer.class, RESTAURANT_ID, TABLE_NUMBER, dishId);
    }

    private void clearOrders() {
        jdbc.update("delete from customer_order_submission where ristoratore_id = ?", RESTAURANT_ID);
        jdbc.update("delete from customer_order_items where ordine_id in (select id from customer_orders where ristoratore_id = ?)", RESTAURANT_ID);
        jdbc.update("delete from customer_orders where ristoratore_id = ?", RESTAURANT_ID);
    }

    private void cleanup() {
        clearOrders();
        jdbc.update("delete from piatto where id in (?, ?)", DISH_ID, SECOND_DISH_ID);
        jdbc.update("delete from menu_category where id = ?", CATEGORY_ID);
        jdbc.update("delete from ristoratore where id = ?", RESTAURANT_ID);
    }
}
