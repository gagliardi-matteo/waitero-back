package com.waitero.analyticsv2;

import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.back.BackApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

@SpringBootTest(classes = BackApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
abstract class AnalyticsV2IntegrationTestSupport {

    protected static final long VALIDATION_RESTAURANT_ID = 910001L;
    protected static final long VALIDATION_TABLE_ID = 910011L;
    protected static final long VALIDATION_DISH_A_ID = 910101L;
    protected static final long VALIDATION_DISH_B_ID = 910102L;
    protected static final long VALIDATION_DISH_C_ID = 910103L;
    protected static final long VALIDATION_DISH_D_ID = 910104L;

    protected static final long EMPTY_RESTAURANT_ID = 910002L;
    protected static final long EMPTY_TABLE_ID = 910021L;
    protected static final long EMPTY_DISH_1_ID = 910201L;
    protected static final long EMPTY_DISH_2_ID = 910202L;

    protected static final long SINGLE_RESTAURANT_ID = 910003L;
    protected static final long SINGLE_TABLE_ID = 910031L;
    protected static final long SINGLE_DISH_BASE_ID = 910301L;
    protected static final long SINGLE_DISH_OTHER_ID = 910302L;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected Clock analyticsV2Clock;

    @BeforeEach
    void seedAnalyticsFixtures() {
        cleanupAll();
        seedValidationRestaurant();
        seedEmptyRestaurant();
        seedSingleDishRestaurant();
    }

    @AfterEach
    void clearAnalyticsFixtures() {
        cleanupAll();
    }

    protected AnalyticsV2TimeRange defaultRange() {
        return AnalyticsV2TimeRange.resolve(null, null, analyticsV2Clock);
    }

    protected LocalDate today() {
        return LocalDate.now(analyticsV2Clock);
    }

    protected void assertBigDecimalEquals(BigDecimal expected, BigDecimal actual) {
        org.junit.jupiter.api.Assertions.assertNotNull(actual);
        org.junit.jupiter.api.Assertions.assertEquals(0, expected.compareTo(actual), () -> "Expected " + expected + " but was " + actual);
    }

    private void cleanupAll() {
        cleanupRestaurant(SINGLE_RESTAURANT_ID);
        cleanupRestaurant(EMPTY_RESTAURANT_ID);
        cleanupRestaurant(VALIDATION_RESTAURANT_ID);
    }

    private void cleanupRestaurant(long restaurantId) {
        jdbcTemplate.update("delete from customer_order_payment_allocations where payment_id in (select id from customer_order_payments where ordine_id in (select id from customer_orders where ristoratore_id = ?))", restaurantId);
        jdbcTemplate.update("delete from customer_order_payments where ordine_id in (select id from customer_orders where ristoratore_id = ?)", restaurantId);
        jdbcTemplate.update("delete from customer_order_items where ordine_id in (select id from customer_orders where ristoratore_id = ?)", restaurantId);
        jdbcTemplate.update("delete from customer_orders where ristoratore_id = ?", restaurantId);
        jdbcTemplate.update("delete from dish_cooccurrence where base_dish_id in (select id from piatto where ristoratore_id = ?) or suggested_dish_id in (select id from piatto where ristoratore_id = ?)", restaurantId, restaurantId);
        jdbcTemplate.update("delete from piatto where ristoratore_id = ?", restaurantId);
        jdbcTemplate.update("delete from tavoli where ristoratore_id = ?", restaurantId);
        jdbcTemplate.update("delete from backoffice_user where restaurant_id = ?", restaurantId);
        jdbcTemplate.update("delete from ristoratore where id = ?", restaurantId);
    }

    private void seedValidationRestaurant() {
        insertRestaurant(VALIDATION_RESTAURANT_ID, "validation-analytics@test.local");
        insertTable(VALIDATION_TABLE_ID, VALIDATION_RESTAURANT_ID, "tbl_validation_analytics");

        insertDish(VALIDATION_DISH_A_ID, VALIDATION_RESTAURANT_ID, "Rigatoni Analytics", "PRIMO", new BigDecimal("10.00"));
        insertDish(VALIDATION_DISH_B_ID, VALIDATION_RESTAURANT_ID, "Pairing Water", "BEVANDA", new BigDecimal("5.00"));
        insertDish(VALIDATION_DISH_C_ID, VALIDATION_RESTAURANT_ID, "Tiramisu Signal", "DOLCE", new BigDecimal("7.00"));
        insertDish(VALIDATION_DISH_D_ID, VALIDATION_RESTAURANT_ID, "Solo Side", "CONTORNO", new BigDecimal("4.00"));

        insertOrder(9101001L, VALIDATION_RESTAURANT_ID, VALIDATION_TABLE_ID, "PAGATO", atDaysAgo(5, 12, 0), new BigDecimal("20.00"));
        insertOrderItem(9101101L, 9101001L, VALIDATION_DISH_A_ID, "Rigatoni Analytics", new BigDecimal("10.00"), 1);
        insertOrderItem(9101102L, 9101001L, VALIDATION_DISH_B_ID, "Pairing Water", new BigDecimal("5.00"), 2);

        insertOrder(9101002L, VALIDATION_RESTAURANT_ID, VALIDATION_TABLE_ID, "PAGATO", atDaysAgo(4, 13, 0), new BigDecimal("17.00"));
        insertOrderItem(9101201L, 9101002L, VALIDATION_DISH_A_ID, "Rigatoni Analytics", new BigDecimal("10.00"), 1);
        insertOrderItem(9101202L, 9101002L, VALIDATION_DISH_C_ID, "Tiramisu Signal", new BigDecimal("7.00"), 1);

        insertOrder(9101003L, VALIDATION_RESTAURANT_ID, VALIDATION_TABLE_ID, "PAGATO", atDaysAgo(3, 14, 0), new BigDecimal("22.00"));
        insertOrderItem(9101301L, 9101003L, VALIDATION_DISH_A_ID, "Rigatoni Analytics", new BigDecimal("10.00"), 1);
        insertOrderItem(9101302L, 9101003L, VALIDATION_DISH_B_ID, "Pairing Water", new BigDecimal("5.00"), 1);
        insertOrderItem(9101303L, 9101003L, VALIDATION_DISH_C_ID, "Tiramisu Signal", new BigDecimal("7.00"), 1);

        insertOrder(9101004L, VALIDATION_RESTAURANT_ID, VALIDATION_TABLE_ID, "PAGATO", atDaysAgo(2, 15, 0), new BigDecimal("4.00"));
        insertOrderItem(9101401L, 9101004L, VALIDATION_DISH_D_ID, "Solo Side", new BigDecimal("4.00"), 1);

        insertOrder(9101005L, VALIDATION_RESTAURANT_ID, VALIDATION_TABLE_ID, "PAGATO", atDaysAgo(45, 18, 0), new BigDecimal("15.00"));
        insertOrderItem(9101501L, 9101005L, VALIDATION_DISH_A_ID, "Rigatoni Analytics", new BigDecimal("10.00"), 1);
        insertOrderItem(9101502L, 9101005L, VALIDATION_DISH_B_ID, "Pairing Water", new BigDecimal("5.00"), 1);

        insertOrder(9101006L, VALIDATION_RESTAURANT_ID, VALIDATION_TABLE_ID, "APERTO", atDaysAgo(1, 16, 0), new BigDecimal("15.00"));
        insertOrderItem(9101601L, 9101006L, VALIDATION_DISH_A_ID, "Rigatoni Analytics", new BigDecimal("10.00"), 1);
        insertOrderItem(9101602L, 9101006L, VALIDATION_DISH_B_ID, "Pairing Water", new BigDecimal("5.00"), 1);
    }

    private void seedEmptyRestaurant() {
        insertRestaurant(EMPTY_RESTAURANT_ID, "empty-analytics@test.local");
        insertTable(EMPTY_TABLE_ID, EMPTY_RESTAURANT_ID, "tbl_empty_analytics");
        insertDish(EMPTY_DISH_1_ID, EMPTY_RESTAURANT_ID, "Empty Primo", "PRIMO", new BigDecimal("12.00"));
        insertDish(EMPTY_DISH_2_ID, EMPTY_RESTAURANT_ID, "Empty Beverage", "BEVANDA", new BigDecimal("3.00"));
    }

    private void seedSingleDishRestaurant() {
        insertRestaurant(SINGLE_RESTAURANT_ID, "single-analytics@test.local");
        insertTable(SINGLE_TABLE_ID, SINGLE_RESTAURANT_ID, "tbl_single_analytics");
        insertDish(SINGLE_DISH_BASE_ID, SINGLE_RESTAURANT_ID, "Single Dish Base", "PRIMO", new BigDecimal("11.00"));
        insertDish(SINGLE_DISH_OTHER_ID, SINGLE_RESTAURANT_ID, "Single Dish Other", "DOLCE", new BigDecimal("6.00"));

        insertOrder(9103001L, SINGLE_RESTAURANT_ID, SINGLE_TABLE_ID, "PAGATO", atDaysAgo(2, 19, 0), new BigDecimal("11.00"));
        insertOrderItem(9103101L, 9103001L, SINGLE_DISH_BASE_ID, "Single Dish Base", new BigDecimal("11.00"), 1);
    }

    private void insertRestaurant(long restaurantId, String email) {
        jdbcTemplate.update(
                "insert into ristoratore (id, email, nome, password, provider, provider_id, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                restaurantId,
                email,
                "Analytics Test Restaurant " + restaurantId,
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

    private void insertOrder(long orderId, long restaurantId, long tableId, String status, LocalDateTime createdAt, BigDecimal total) {
        jdbcTemplate.update(
                "insert into customer_orders (id, created_at, paid_at, payment_mode, status, table_id, updated_at, ristoratore_id, note_cucina, totale, variant) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId,
                createdAt,
                "APERTO".equals(status) ? null : createdAt.plusMinutes(20),
                "CARD",
                status,
                (int) tableId,
                createdAt.plusMinutes(25),
                restaurantId,
                "analytics-test-order-" + orderId,
                total,
                "A"
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
