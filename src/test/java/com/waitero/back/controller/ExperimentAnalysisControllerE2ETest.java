package com.waitero.back.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.back.BackApplication;
import com.waitero.back.dto.AuthResponse;
import com.waitero.back.dto.LocalLoginRequest;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.entity.ExperimentConfig;
import com.waitero.back.repository.BackofficeUserRepository;
import com.waitero.back.repository.ExperimentConfigRepository;
import com.waitero.back.service.ExperimentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "waitero.experiment.min-sessions-per-variant=2",
                "waitero.experiment.min-orders-per-variant=1",
                "waitero.experiment.min-active-days-per-variant=2",
                "waitero.experiment.max-uplift-drift=0.20",
                "waitero.experiment.autopilot.cooldown-minutes=30"
        }
)
@ActiveProfiles("test")
class ExperimentAnalysisControllerE2ETest {

    private static final long RESTAURANT_ID = 930001L;
    private static final long TABLE_ID = 930011L;
    private static final long DISH_ID = 930101L;
    private static final String EMAIL = "experiment-analysis-e2e@test.local";
    private static final String PASSWORD = "waitero-e2e-123";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BackofficeUserRepository backofficeUserRepository;

    @Autowired
    private ExperimentConfigRepository experimentConfigRepository;

    @Autowired
    private Clock analyticsV2Clock;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        cleanup();
        seedRestaurant();
        seedBackofficeUser();
        seedExperimentConfig();
        seedOrders();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void shouldAuthenticateAndReturnExperimentAnalysisOverRealHttp() throws Exception {
        LocalLoginRequest loginRequest = new LocalLoginRequest();
        loginRequest.setEmail(EMAIL);
        loginRequest.setPassword(PASSWORD);

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                baseUrl("/api/auth/local-login"),
                loginJsonEntity(loginRequest),
                AuthResponse.class
        );

        assertEquals(HttpStatus.OK, loginResponse.getStatusCode());
        assertNotNull(loginResponse.getBody());
        assertNotNull(loginResponse.getBody().getAccessToken());
        assertFalse(loginResponse.getBody().getAccessToken().isBlank());

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginResponse.getBody().getAccessToken());

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl("/api/experiment/analysis?ristoranteId=" + RESTAURANT_ID),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        JsonNode root = objectMapper.readTree(response.getBody());
        assertTrue(root.hasNonNull("metrics"));
        assertTrue(root.hasNonNull("winner"));
        assertTrue(root.hasNonNull("currentMode"));

        JsonNode metrics = root.get("metrics");
        assertVariantMetrics(metrics, "A");
        assertVariantMetrics(metrics, "B");
        assertVariantMetrics(metrics, "C");

        assertEquals("C", root.get("winner").asText());
        assertEquals("MODE_" + ExperimentService.MODE_ABC, root.get("currentMode").asText());
        assertEquals("MODE_" + ExperimentService.MODE_FORCE_C, root.get("targetMode").asText());
        assertEquals("SWITCH_TO_C", root.get("action").asText());
        assertTrue(metrics.get("C").get("rps").decimalValue().compareTo(metrics.get("B").get("rps").decimalValue()) > 0);
    }

    @Test
    void shouldRejectUnauthenticatedRequest() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl("/api/experiment/analysis?ristoranteId=" + RESTAURANT_ID),
                String.class
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    private HttpEntity<LocalLoginRequest> loginJsonEntity(LocalLoginRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(request, headers);
    }

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private void assertVariantMetrics(JsonNode metrics, String variant) {
        assertTrue(metrics.hasNonNull(variant), "Missing metrics for variant " + variant);
        JsonNode node = metrics.get(variant);

        assertTrue(node.hasNonNull("totalRevenue"));
        assertTrue(node.get("totalRevenue").isNumber());
        assertTrue(node.hasNonNull("totalOrders"));
        assertTrue(node.get("totalOrders").canConvertToLong());
        assertTrue(node.hasNonNull("totalSessions"));
        assertTrue(node.get("totalSessions").canConvertToLong());
        assertTrue(node.hasNonNull("rps"));
        assertTrue(node.get("rps").isNumber());
        assertTrue(node.hasNonNull("aov"));
        assertTrue(node.get("aov").isNumber());
        assertTrue(node.hasNonNull("cr"));
        assertTrue(node.get("cr").isNumber());
        assertFalse(node.get("totalRevenue").isNull());
        assertFalse(node.get("rps").isNull());
        assertFalse(node.get("aov").isNull());
        assertFalse(node.get("cr").isNull());
    }

    private void seedRestaurant() {
        LocalDateTime createdAt = today().minusDays(90).atTime(9, 0);
        jdbcTemplate.update(
                "insert into ristoratore (id, email, nome, password, provider, provider_id, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                RESTAURANT_ID,
                EMAIL,
                "Experiment Analysis E2E",
                "pwd",
                "LOCAL",
                null,
                createdAt
        );
        jdbcTemplate.update(
                "insert into tavoli (id, ristoratore_id, table_public_id, numero, nome, coperti, attivo, qr_token, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TABLE_ID,
                RESTAURANT_ID,
                "tbl_experiment_analysis_e2e",
                1,
                "Experiment Table",
                4,
                true,
                "qr-experiment-analysis-e2e",
                createdAt,
                createdAt
        );
        jdbcTemplate.update(
                "insert into piatto (id, nome, descrizione, prezzo, disponibile, categoria, image_url, ingredienti, allergeni, consigliato, ristoratore_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                DISH_ID,
                "Experiment Analysis Dish",
                "Dish for experiment analysis controller e2e test",
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

    private void seedBackofficeUser() {
        backofficeUserRepository.save(BackofficeUser.builder()
                .email(EMAIL)
                .nome("Experiment Analysis User")
                .provider("LOCAL")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(BackofficeRole.RISTORATORE)
                .restaurantId(RESTAURANT_ID)
                .createdAt(today().minusDays(60).atStartOfDay())
                .build());
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
        insertPaidOrder(9301001L, "A", "sess-a-1", new BigDecimal("10.00"), 8);
        insertPaidOrder(9301002L, "A", "sess-a-2", new BigDecimal("10.00"), 6);
        insertPaidOrder(9301008L, "A", "sess-a-3", new BigDecimal("10.00"), 4);

        insertPaidOrder(9301003L, "B", "sess-b-1", new BigDecimal("10.00"), 7);
        insertPaidOrder(9301004L, "B", "sess-b-2", new BigDecimal("10.00"), 5);
        insertPaidOrder(9301009L, "B", "sess-b-3", new BigDecimal("10.00"), 3);

        insertPaidOrder(9301005L, "C", "sess-c-1", new BigDecimal("12.00"), 7);
        insertPaidOrder(9301006L, "C", "sess-c-2", new BigDecimal("12.00"), 6);
        insertPaidOrder(9301007L, "C", "sess-c-3", new BigDecimal("12.00"), 4);
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
                "experiment-analysis-order-" + orderId,
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
                "Experiment Analysis Dish",
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
        backofficeUserRepository.findFirstByEmailIgnoreCaseAndProviderIgnoreCase(EMAIL, "LOCAL")
                .ifPresent(backofficeUserRepository::delete);
        jdbcTemplate.update("delete from dish_cooccurrence where base_dish_id in (select id from piatto where ristoratore_id = ?) or suggested_dish_id in (select id from piatto where ristoratore_id = ?)", RESTAURANT_ID, RESTAURANT_ID);
        jdbcTemplate.update("delete from piatto where ristoratore_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from tavoli where ristoratore_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from backoffice_user where restaurant_id = ?", RESTAURANT_ID);
        jdbcTemplate.update("delete from ristoratore where id = ?", RESTAURANT_ID);
    }
}
