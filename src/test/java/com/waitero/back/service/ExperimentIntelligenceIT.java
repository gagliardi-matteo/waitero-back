package com.waitero.back.service;

import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.back.BackApplication;
import com.waitero.back.dto.ExperimentAnalysisDTO;
import com.waitero.back.dto.ExperimentVariantPerformanceDTO;
import com.waitero.back.entity.ExperimentConfig;
import com.waitero.back.repository.ExperimentConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = BackApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "waitero.experiment.min-sessions-per-variant=2",
                "waitero.experiment.min-orders-per-variant=1",
                "waitero.experiment.min-active-days-per-variant=2",
                "waitero.experiment.max-uplift-drift=0.20",
                "waitero.experiment.autopilot.cooldown-minutes=30"
        }
)
@ActiveProfiles("test")
class ExperimentIntelligenceIT {

    private static final long RESTAURANT_ID = 920001L;
    private static final long TABLE_ID = 920011L;
    private static final long DISH_ID = 920101L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock analyticsV2Clock;

    @Autowired
    private ExperimentAnalyticsService experimentAnalyticsService;

    @Autowired
    private ExperimentIntelligenceService experimentIntelligenceService;

    @Autowired
    private ExperimentConfigRepository experimentConfigRepository;

    @BeforeEach
    void setUp() {
        cleanup();
        seedRestaurant();
        seedExperimentConfig();
        seedOrders();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void shouldComputeMetricsPerVariantFromCustomerOrders() {
        AnalyticsV2TimeRange range = AnalyticsV2TimeRange.resolve(null, null, analyticsV2Clock);

        var metrics = experimentAnalyticsService.computeMetrics(RESTAURANT_ID, range);

        ExperimentVariantPerformanceDTO a = metrics.get(ExperimentService.VARIANT_A);
        ExperimentVariantPerformanceDTO b = metrics.get(ExperimentService.VARIANT_B);
        ExperimentVariantPerformanceDTO c = metrics.get(ExperimentService.VARIANT_C);

        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);

        assertEquals(3L, a.totalOrders());
        assertEquals(3L, a.totalSessions());
        assertEquals(new BigDecimal("10.00"), a.rps());

        assertEquals(3L, b.totalOrders());
        assertEquals(3L, b.totalSessions());
        assertEquals(new BigDecimal("10.00"), b.rps());

        assertEquals(3L, c.totalOrders());
        assertEquals(3L, c.totalSessions());
        assertEquals(new BigDecimal("12.00"), c.rps());
        assertEquals(new BigDecimal("12.00"), c.aov());
    }

    @Test
    void shouldSuggestVariantCWhenItBeatsBaselineB() {
        ExperimentAnalysisDTO analysis = experimentIntelligenceService.getExperimentAnalysis(RESTAURANT_ID, null, null);

        assertEquals(ExperimentService.VARIANT_C, analysis.suggestedWinner());
        assertEquals(ExperimentService.MODE_FORCE_C, analysis.targetMode());
        assertEquals("SWITCH_TO_C", analysis.action());
        assertTrue(analysis.sufficientData());
        assertTrue(analysis.stable());
        assertFalse(analysis.upliftVsBaseline().compareTo(new BigDecimal("0.0500")) <= 0);
    }

    private void seedRestaurant() {
        LocalDateTime createdAt = today().minusDays(90).atTime(9, 0);
        jdbcTemplate.update(
                "insert into ristoratore (id, email, nome, password, provider, provider_id, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                RESTAURANT_ID,
                "experiment-intelligence@test.local",
                "Experiment Intelligence Test",
                "pwd",
                "LOCAL",
                null,
                createdAt
        );
        jdbcTemplate.update(
                "insert into tavoli (id, ristoratore_id, table_public_id, numero, nome, coperti, attivo, qr_token, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TABLE_ID,
                RESTAURANT_ID,
                "tbl_experiment_intelligence",
                1,
                "Test Table",
                4,
                true,
                "qr-experiment-intelligence",
                createdAt,
                createdAt
        );
        jdbcTemplate.update(
                "insert into piatto (id, nome, descrizione, prezzo, disponibile, categoria, image_url, ingredienti, allergeni, consigliato, ristoratore_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                DISH_ID,
                "Experiment Dish",
                "Dish for experiment intelligence tests",
                new BigDecimal("10.00"),
                true,
                "PRIMO",
                null,
                null,
                null,
                false,
                RESTAURANT_ID
        );
    }

    private void seedExperimentConfig() {
        experimentConfigRepository.save(ExperimentConfig.builder()
                .restaurantId(RESTAURANT_ID)
                .autopilotEnabled(true)
                .minSampleSize(2)
                .minUpliftPercent(5.0d)
                .minConfidence(0.95d)
                .holdoutPercent(5)
                .updatedAt(today().atStartOfDay().atZone(analyticsV2Clock.getZone()).toInstant())
                .build());
        jdbcTemplate.update(
                "insert into experiment_mode (restaurant_id, mode) values (?, ?) on conflict (restaurant_id) do update set mode = excluded.mode",
                RESTAURANT_ID,
                ExperimentService.MODE_ABC
        );
    }

    private void seedOrders() {
        insertPaidOrder(9201001L, "A", "sess-a-1", new BigDecimal("10.00"), 8);
        insertPaidOrder(9201002L, "A", "sess-a-2", new BigDecimal("10.00"), 6);
        insertPaidOrder(9201008L, "A", "sess-a-3", new BigDecimal("10.00"), 4);

        insertPaidOrder(9201003L, "B", "sess-b-1", new BigDecimal("10.00"), 7);
        insertPaidOrder(9201004L, "B", "sess-b-2", new BigDecimal("10.00"), 5);
        insertPaidOrder(9201009L, "B", "sess-b-3", new BigDecimal("10.00"), 3);

        insertPaidOrder(9201005L, "C", "sess-c-1", new BigDecimal("12.00"), 7);
        insertPaidOrder(9201006L, "C", "sess-c-2", new BigDecimal("12.00"), 6);
        insertPaidOrder(9201007L, "C", "sess-c-3", new BigDecimal("12.00"), 4);
    }

    private void insertPaidOrder(long orderId, String variant, String sessionId, BigDecimal total, int daysAgo) {
        LocalDateTime createdAt = today().minusDays(daysAgo).atTime(12, 0);
        jdbcTemplate.update(
                "insert into customer_orders (id, created_at, paid_at, payment_mode, status, table_id, updated_at, ristoratore_id, note_cucina, totale, variant, session_id, item_count) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId,
                createdAt,
                createdAt.plusMinutes(20),
                "CARD",
                "PAGATO",
                (int) TABLE_ID,
                createdAt.plusMinutes(25),
                RESTAURANT_ID,
                "experiment-order-" + orderId,
                total,
                variant,
                sessionId,
                1
        );
        jdbcTemplate.update(
                "insert into customer_order_items (id, created_at, image_url, nome, prezzo_unitario, quantity, ordine_id, piatto_id, source, source_dish_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderId + 1000L,
                createdAt,
                null,
                "Experiment Dish",
                total,
                1,
                orderId,
                DISH_ID,
                null,
                null
        );
    }

    private LocalDate today() {
        return LocalDate.now(analyticsV2Clock);
    }

    private void cleanup() {
        jdbcTemplate.update("delete from experiment_decision_log where restaurant_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from experiment_mode where restaurant_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from experiment_config where restaurant_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from customer_order_payment_allocations where payment_id in (select id from customer_order_payments where ordine_id in (select id from customer_orders where ristoratore_id = ?))", RESTAURANT_ID);
        jdbcTemplate.update("delete from customer_order_payments where ordine_id in (select id from customer_orders where ristoratore_id = ?)", RESTAURANT_ID);
        jdbcTemplate.update("delete from customer_order_items where ordine_id in (select id from customer_orders where ristoratore_id = ?)", RESTAURANT_ID);
        jdbcTemplate.update("delete from customer_orders where ristoratore_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from dish_cooccurrence where base_dish_id in (select id from piatto where ristoratore_id = ?) or suggested_dish_id in (select id from piatto where ristoratore_id = ?)", RESTAURANT_ID, RESTAURANT_ID);
        jdbcTemplate.update("delete from piatto where ristoratore_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from tavoli where ristoratore_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from backoffice_user where restaurant_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from ristoratore where id = ?", RESTAURANT_ID);
    }
}
