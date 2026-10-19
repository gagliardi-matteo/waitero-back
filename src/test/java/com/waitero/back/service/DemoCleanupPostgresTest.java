package com.waitero.back.service;

import com.waitero.back.BackApplication;
import com.waitero.back.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(classes = BackApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DemoCleanupPostgresTest {
    @Autowired DemoSessionService sessions;
    @Autowired DemoOrderService orders;
    @Autowired JdbcTemplate jdbc;

    @Test void expiryMustRemoveOrdersAndIdempotencyLedgerWithoutForeignKeyFailure() {
        DemoSessionDTO session = sessions.create();
        Long dishId = jdbc.queryForObject("select min(id) from piatto where ristoratore_id = ?", Long.class, session.getRestaurantId());
        CustomerOrderItemRequest item = new CustomerOrderItemRequest();
        item.setDishId(dishId); item.setQuantity(1);
        DemoOrderRequest request = new DemoOrderRequest();
        request.setRequestId(UUID.randomUUID().toString()); request.setItems(List.of(item));
        Long orderId = orders.submit(session.getToken(), request).getId();
        assertEquals(1, jdbc.queryForObject("select count(*) from customer_order_submission where ordine_id = ?", Integer.class, orderId));
        jdbc.update("update demo_session set expires_at = current_timestamp - interval '1 minute' where table_id = (select id from tavoli where table_public_id = ?)", session.getTablePublicId());
        assertDoesNotThrow(sessions::cleanup);
        assertEquals(0, jdbc.queryForObject("select count(*) from customer_orders where id = ?", Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject("select count(*) from customer_order_submission where ordine_id = ?", Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject("select count(*) from tavoli where table_public_id = ?", Integer.class, session.getTablePublicId()));
    }
}
