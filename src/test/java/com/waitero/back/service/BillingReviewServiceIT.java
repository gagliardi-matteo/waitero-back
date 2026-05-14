package com.waitero.back.service;

import com.waitero.back.BackApplication;
import com.waitero.back.entity.BillingReview;
import com.waitero.back.repository.BillingReviewRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = BackApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BillingReviewServiceIT {

    private static final long RESTAURANT_ID = 940001L;

    @Autowired
    private BillingReviewService billingReviewService;

    @Autowired
    private BillingReviewRepository billingReviewRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanup();
        seedRestaurantAndBillingAccount();
        seedPaidOrder();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void shouldCreateOnlyOneReviewForSameBillingCycle() {
        LocalDate executionDate = LocalDate.of(2026, 6, 17);

        List<BillingReview> firstRun = billingReviewService.createScheduledReviewsForDate(executionDate);
        List<BillingReview> secondRun = billingReviewService.createScheduledReviewsForDate(executionDate);

        assertEquals(1, firstRun.size());
        assertEquals(0, secondRun.size());
        assertEquals(1, billingReviewRepository.findByStatusOrderByCreatedAtAsc(com.waitero.back.entity.BillingReviewStatus.CREATED).size());
    }

    private void seedRestaurantAndBillingAccount() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 17, 9, 0);
        jdbcTemplate.update(
                "insert into ristoratore (id, email, nome, password, provider, provider_id, business_type, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                RESTAURANT_ID,
                "billing-it@test.local",
                "Billing IT Restaurant",
                "pwd",
                "LOCAL",
                null,
                "RISTORANTE",
                createdAt
        );
        jdbcTemplate.update(
                """
                insert into billing_account (
                    ristoratore_id, stripe_customer_id, default_payment_method_id, billing_enabled,
                    commission_percentage, minimum_monthly_fee, billing_day, contract_start_date, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                RESTAURANT_ID,
                "cus_test",
                "pm_test",
                true,
                new BigDecimal("0.010000"),
                new BigDecimal("5.00"),
                17,
                LocalDate.of(2026, 5, 17),
                createdAt,
                createdAt
        );
    }

    private void seedPaidOrder() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 6, 1, 12, 30);
        jdbcTemplate.update(
                """
                insert into customer_orders (
                    id, created_at, paid_at, payment_mode, status, table_id, updated_at, ristoratore_id, note_cucina, totale, variant, session_id, item_count
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                9401001L,
                paidAt.minusMinutes(20),
                paidAt,
                "CARD",
                "PAGATO",
                1,
                paidAt.plusMinutes(5),
                RESTAURANT_ID,
                "billing-it-order",
                new BigDecimal("250.00"),
                "A",
                "billing-it-session",
                1
        );
    }

    private void cleanup() {
        jdbcTemplate.update("delete from stripe_webhook_event where invoice_id like 'in_test%' or event_id like 'evt_test%'");
        jdbcTemplate.update("delete from billing_review_order_snapshot where billing_review_id in (select id from billing_review where ristoratore_id = ?)", RESTAURANT_ID);
        jdbcTemplate.update("delete from billing_review where ristoratore_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from billing_account where ristoratore_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from customer_orders where ristoratore_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from ristoratore where id = ?", RESTAURANT_ID);
    }
}
