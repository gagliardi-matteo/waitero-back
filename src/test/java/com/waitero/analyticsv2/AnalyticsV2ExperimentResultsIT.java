package com.waitero.analyticsv2;

import com.waitero.analyticsv2.dto.AnalyticsV2ExperimentResultsDTO;
import com.waitero.analyticsv2.service.AnalyticsV2ExperimentResultsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsV2ExperimentResultsIT extends AnalyticsV2IntegrationTestSupport {

    private static final long EXPERIMENT_RESTAURANT_ID = 910004L;
    private static final long EXPERIMENT_TABLE_ID = 910041L;
    private static final long EXPERIMENT_DISH_MAIN_ID = 910401L;
    private static final long EXPERIMENT_DISH_DRINK_ID = 910402L;
    private static final long EXPERIMENT_DISH_DESSERT_ID = 910403L;

    @Autowired
    private AnalyticsV2ExperimentResultsService analyticsV2ExperimentResultsService;

    @BeforeEach
    void seedExperimentRestaurant() {
        cleanupExperimentRestaurant();
        insertRestaurant(EXPERIMENT_RESTAURANT_ID, "experiment-analytics@test.local");
        insertTable(EXPERIMENT_TABLE_ID, EXPERIMENT_RESTAURANT_ID, "tbl_experiment_analytics");
        insertDish(EXPERIMENT_DISH_MAIN_ID, EXPERIMENT_RESTAURANT_ID, "Experiment Main", "PRIMO", new BigDecimal("12.00"));
        insertDish(EXPERIMENT_DISH_DRINK_ID, EXPERIMENT_RESTAURANT_ID, "Experiment Drink", "BEVANDA", new BigDecimal("4.00"));
        insertDish(EXPERIMENT_DISH_DESSERT_ID, EXPERIMENT_RESTAURANT_ID, "Experiment Dessert", "DOLCE", new BigDecimal("6.00"));

        insertOrder(9104001L, "A", atDaysAgo(5, 12, 0), new BigDecimal("20.00"), 2);
        insertOrderItem(9104101L, 9104001L, EXPERIMENT_DISH_MAIN_ID, "Experiment Main", new BigDecimal("12.00"), 1);
        insertOrderItem(9104102L, 9104001L, EXPERIMENT_DISH_DRINK_ID, "Experiment Drink", new BigDecimal("4.00"), 2);

        insertOrder(9104002L, "A", atDaysAgo(4, 13, 0), new BigDecimal("22.00"), 2);
        insertOrderItem(9104201L, 9104002L, EXPERIMENT_DISH_MAIN_ID, "Experiment Main", new BigDecimal("12.00"), 1);
        insertOrderItem(9104202L, 9104002L, EXPERIMENT_DISH_DESSERT_ID, "Experiment Dessert", new BigDecimal("10.00"), 1);

        insertOrder(9104003L, "B", atDaysAgo(3, 14, 0), new BigDecimal("30.00"), 3);
        insertOrderItem(9104301L, 9104003L, EXPERIMENT_DISH_MAIN_ID, "Experiment Main", new BigDecimal("12.00"), 1);
        insertOrderItem(9104302L, 9104003L, EXPERIMENT_DISH_DRINK_ID, "Experiment Drink", new BigDecimal("4.00"), 2);
        insertOrderItem(9104303L, 9104003L, EXPERIMENT_DISH_DESSERT_ID, "Experiment Dessert", new BigDecimal("10.00"), 1);

        insertOrder(9104004L, "B", atDaysAgo(2, 15, 0), new BigDecimal("28.00"), 3);
        insertOrderItem(9104401L, 9104004L, EXPERIMENT_DISH_MAIN_ID, "Experiment Main", new BigDecimal("12.00"), 1);
        insertOrderItem(9104402L, 9104004L, EXPERIMENT_DISH_DRINK_ID, "Experiment Drink", new BigDecimal("6.00"), 1);
        insertOrderItem(9104403L, 9104004L, EXPERIMENT_DISH_DESSERT_ID, "Experiment Dessert", new BigDecimal("10.00"), 1);

        insertOrder(9104005L, "H", atDaysAgo(1, 16, 0), new BigDecimal("100.00"), 4);
        insertOrderItem(9104501L, 9104005L, EXPERIMENT_DISH_MAIN_ID, "Experiment Main", new BigDecimal("25.00"), 4);

        insertOrder(9104006L, "B", atDaysAgo(40, 18, 0), new BigDecimal("99.00"), 5);
        insertOrderItem(9104601L, 9104006L, EXPERIMENT_DISH_MAIN_ID, "Experiment Main", new BigDecimal("19.80"), 5);
    }

    @AfterEach
    void clearExperimentRestaurant() {
        cleanupExperimentRestaurant();
    }

    @Test
    void shouldReturnRealPaidOrderExperimentMetricsAndBasicSignificance() {
        AnalyticsV2ExperimentResultsDTO results = analyticsV2ExperimentResultsService.getResults(EXPERIMENT_RESTAURANT_ID, defaultRange());

        assertEquals(2L, results.groupA().orders());
        assertEquals(2L, results.groupB().orders());
        assertBigDecimalEquals(new BigDecimal("42.00"), results.groupA().totalRevenue());
        assertBigDecimalEquals(new BigDecimal("58.00"), results.groupB().totalRevenue());
        assertBigDecimalEquals(new BigDecimal("21.00"), results.groupA().averageOrderValue());
        assertBigDecimalEquals(new BigDecimal("29.00"), results.groupB().averageOrderValue());
        assertBigDecimalEquals(new BigDecimal("2.0000"), results.groupA().itemsPerOrder());
        assertBigDecimalEquals(new BigDecimal("3.0000"), results.groupB().itemsPerOrder());

        assertBigDecimalEquals(new BigDecimal("16.00"), results.uplift().revenueDelta());
        assertBigDecimalEquals(new BigDecimal("0.3810"), results.uplift().revenuePct());
        assertBigDecimalEquals(new BigDecimal("8.00"), results.uplift().averageOrderValueDelta());
        assertBigDecimalEquals(new BigDecimal("0.3810"), results.uplift().averageOrderValuePct());
        assertBigDecimalEquals(new BigDecimal("1.0000"), results.uplift().itemsPerOrderDelta());
        assertBigDecimalEquals(new BigDecimal("0.5000"), results.uplift().itemsPerOrderPct());

        assertEquals("average_order_value", results.significance().metric());
        assertEquals("two_sided_z_test", results.significance().method());
        assertTrue(results.significance().sufficientSample());
        assertTrue(results.significance().statisticallySignificant());
        assertNotNull(results.significance().pValue());
        assertTrue(results.significance().pValue().compareTo(new BigDecimal("0.050000")) < 0);
    }

    private void cleanupExperimentRestaurant() {
        jdbcTemplate.update("delete from customer_order_payment_allocations where payment_id in (select id from customer_order_payments where ordine_id in (select id from customer_orders where ristoratore_id = ?))", EXPERIMENT_RESTAURANT_ID);
        jdbcTemplate.update("delete from customer_order_payments where ordine_id in (select id from customer_orders where ristoratore_id = ?)", EXPERIMENT_RESTAURANT_ID);
        jdbcTemplate.update("delete from customer_order_items where ordine_id in (select id from customer_orders where ristoratore_id = ?)", EXPERIMENT_RESTAURANT_ID);
        jdbcTemplate.update("delete from customer_orders where ristoratore_id = ?", EXPERIMENT_RESTAURANT_ID);
        jdbcTemplate.update("delete from dish_cooccurrence where base_dish_id in (select id from piatto where ristoratore_id = ?) or suggested_dish_id in (select id from piatto where ristoratore_id = ?)", EXPERIMENT_RESTAURANT_ID, EXPERIMENT_RESTAURANT_ID);
        jdbcTemplate.update("delete from piatto where ristoratore_id = ?", EXPERIMENT_RESTAURANT_ID);
        jdbcTemplate.update("delete from tavoli where ristoratore_id = ?", EXPERIMENT_RESTAURANT_ID);
        jdbcTemplate.update("delete from backoffice_user where restaurant_id = ?", EXPERIMENT_RESTAURANT_ID);
        jdbcTemplate.update("delete from ristoratore where id = ?", EXPERIMENT_RESTAURANT_ID);
    }

    private void insertRestaurant(long restaurantId, String email) {
        jdbcTemplate.update(
                "insert into ristoratore (id, email, nome, password, provider, provider_id, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                restaurantId,
                email,
                "Experiment Test Restaurant " + restaurantId,
                "pwd",
                "LOCAL",
                null,
                atDaysAgo(60, 9, 0)
        );
    }

    private void insertTable(long tableId, long restaurantId, String publicId) {
        LocalDateTime createdAt = atDaysAgo(60, 9, 15);
        jdbcTemplate.update(
                "insert into tavoli (id, ristoratore_id, table_public_id, numero, nome, coperti, attivo, qr_token, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tableId,
                restaurantId,
                publicId,
                1,
                "Table " + restaurantId,
                4,
                true,
                "qr-" + publicId,
                createdAt,
                createdAt
        );
    }

    private void insertDish(long dishId, long restaurantId, String name, String category, BigDecimal price) {
        jdbcTemplate.update(
                "insert into piatto (id, nome, descrizione, prezzo, disponibile, categoria, image_url, ingredienti, allergeni, consigliato, ristoratore_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                dishId,
                name,
                name + " description",
                price,
                true,
                category,
                null,
                null,
                null,
                false,
                restaurantId
        );
    }

    private void insertOrder(long orderId, String variant, LocalDateTime createdAt, BigDecimal total, int itemCount) {
        jdbcTemplate.update(
                "insert into customer_orders (id, created_at, paid_at, payment_mode, status, table_id, updated_at, ristoratore_id, note_cucina, totale, variant, item_count) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId,
                createdAt,
                createdAt.plusMinutes(20),
                "CARD",
                "PAGATO",
                (int) EXPERIMENT_TABLE_ID,
                createdAt.plusMinutes(25),
                EXPERIMENT_RESTAURANT_ID,
                "experiment-test-order-" + orderId,
                total,
                variant,
                itemCount
        );
    }

    private void insertOrderItem(long itemId, long orderId, long dishId, String name, BigDecimal unitPrice, int quantity) {
        jdbcTemplate.update(
                "insert into customer_order_items (id, created_at, image_url, nome, prezzo_unitario, quantity, ordine_id, piatto_id, source, source_dish_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                itemId,
                atDaysAgo(1, 11, 0),
                null,
                name,
                unitPrice,
                quantity,
                orderId,
                dishId,
                null,
                null
        );
    }

    private LocalDateTime atDaysAgo(int daysAgo, int hour, int minute) {
        return today().minusDays(daysAgo).atTime(hour, minute);
    }
}


