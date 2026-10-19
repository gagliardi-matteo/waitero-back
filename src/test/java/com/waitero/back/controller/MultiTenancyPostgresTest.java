package com.waitero.back.controller;

import com.waitero.back.BackApplication;
import com.waitero.back.dto.AuthResponse;
import com.waitero.back.dto.LocalLoginRequest;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.repository.BackofficeUserRepository;
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
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = BackApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MultiTenancyPostgresTest {

    private static final long RESTAURANT_A = 940001L;
    private static final long RESTAURANT_B = 940002L;
    private static final long CATEGORY_ID = 940010L;
    private static final long TABLE_A = 940011L;
    private static final long TABLE_B = 940012L;
    private static final long DISH_A = 940101L;
    private static final long DISH_B = 940102L;
    private static final long ORDER_A = 940201L;
    private static final long ORDER_B = 940202L;
    private static final String EMAIL_A = "tenant-a@test.local";
    private static final String EMAIL_B = "tenant-b@test.local";
    private static final String PASSWORD = "tenant-test-123";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BackofficeUserRepository backofficeUserRepository;

    @BeforeEach
    void setUp() {
        cleanup();
        seedRestaurant(RESTAURANT_A, EMAIL_A, "Tenant A");
        seedRestaurant(RESTAURANT_B, EMAIL_B, "Tenant B");
        seedTable(TABLE_A, RESTAURANT_A, 1, "tenant-table-a");
        seedTable(TABLE_B, RESTAURANT_B, 2, "tenant-table-b");
        jdbcTemplate.update("insert into menu_category (id, business_type, code, label, sort_order, active) values (?, ?, ?, ?, ?, ?)",
                CATEGORY_ID, "RISTORANTE", "TENANT_TEST", "Tenant test", 99, true);
        seedDish(DISH_A, RESTAURANT_A, "Dish A");
        seedDish(DISH_B, RESTAURANT_B, "Dish B secret");
        seedOrder(ORDER_A, RESTAURANT_A, 1);
        seedOrder(ORDER_B, RESTAURANT_B, 2);
        seedUser(RESTAURANT_A, EMAIL_A);
        seedUser(RESTAURANT_B, EMAIL_B);
    }

    @AfterEach
    void tearDown() { cleanup(); }

    @Test
    void authenticatedRestaurantMustNotReadAnotherRestaurantsMenuByPathId() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/menu/piattiRistoratore/" + RESTAURANT_B),
                HttpMethod.GET,
                authorized(null, login(EMAIL_A)),
                String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "Tenant A read Tenant B menu: " + response.getBody());
    }

    @Test
    void ownRestaurantCanReadItsMenu() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/menu/piattiRistoratore/" + RESTAURANT_A),
                HttpMethod.GET,
                authorized(null, login(EMAIL_A)),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void authenticatedMenuReadsAndListsMustRemainTenantScoped() {
        String tokenA = login(EMAIL_A);
        ResponseEntity<String> directDish = restTemplate.exchange(url("/api/menu/piatto/" + DISH_B),
                HttpMethod.GET, authorized(null, tokenA), String.class);
        ResponseEntity<String> ownList = restTemplate.exchange(url("/api/menu/piatti"),
                HttpMethod.GET, authorized(null, tokenA), String.class);

        assertEquals(HttpStatus.NOT_FOUND, directDish.getStatusCode());
        assertEquals(HttpStatus.OK, ownList.getStatusCode());
        assertFalse(ownList.getBody().contains("Dish B secret"), ownList.getBody());
    }

    @Test
    void authenticatedRestaurantMustNotUpdateOrDeleteAnotherRestaurantsDish() {
        String tokenA = login(EMAIL_A);
        String payload = "{\"nome\":\"PWNED\",\"prezzo\":1,\"disponibile\":false}";
        ResponseEntity<String> update = restTemplate.exchange(url("/api/menu/piatti/" + DISH_B),
                HttpMethod.PUT, authorized(payload, tokenA), String.class);
        ResponseEntity<String> delete = restTemplate.exchange(url("/api/menu/piatti/" + DISH_B),
                HttpMethod.DELETE, authorized(null, tokenA), String.class);

        assertEquals(HttpStatus.NOT_FOUND, update.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, delete.getStatusCode());
        assertEquals("Dish B secret", jdbcTemplate.queryForObject("select nome from piatto where id = ?", String.class, DISH_B));
    }

    @Test
    void authenticatedRestaurantMustNotReadOrPayAnotherRestaurantsOrder() {
        String tokenA = login(EMAIL_A);
        ResponseEntity<String> read = restTemplate.exchange(url("/api/orders/" + ORDER_B),
                HttpMethod.GET, authorized(null, tokenA), String.class);
        ResponseEntity<String> pay = restTemplate.exchange(url("/api/orders/" + ORDER_B + "/pay"),
                HttpMethod.POST, authorized(null, tokenA), String.class);

        assertEquals(HttpStatus.NOT_FOUND, read.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, pay.getStatusCode());
        assertEquals("APERTO", jdbcTemplate.queryForObject("select status from customer_orders where id = ?", String.class, ORDER_B));
    }

    @Test
    void orderCollectionsMustNotContainAnotherRestaurantsOrder() {
        ResponseEntity<String> response = restTemplate.exchange(url("/api/orders/active"),
                HttpMethod.GET, authorized(null, login(EMAIL_A)), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().contains(String.valueOf(ORDER_B)), response.getBody());
    }

    @Test
    void authenticatedRestaurantMustNotUpdateRegenerateOrDeleteAnotherRestaurantsTable() {
        String tokenA = login(EMAIL_A);
        String payload = "{\"numero\":77,\"nome\":\"PWNED\",\"coperti\":99,\"attivo\":false}";
        ResponseEntity<String> update = restTemplate.exchange(url("/api/tables/" + TABLE_B),
                HttpMethod.PUT, authorized(payload, tokenA), String.class);
        ResponseEntity<String> regenerate = restTemplate.exchange(url("/api/tables/" + TABLE_B + "/regenerate-token"),
                HttpMethod.POST, authorized(null, tokenA), String.class);
        ResponseEntity<String> delete = restTemplate.exchange(url("/api/tables/" + TABLE_B),
                HttpMethod.DELETE, authorized(null, tokenA), String.class);

        assertEquals(HttpStatus.NOT_FOUND, update.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, regenerate.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, delete.getStatusCode());
        assertEquals("tenant-table-b", jdbcTemplate.queryForObject("select table_public_id from tavoli where id = ?", String.class, TABLE_B));
    }

    @Test
    void tableCollectionsMustNotContainAnotherRestaurantsTable() {
        ResponseEntity<String> response = restTemplate.exchange(url("/api/tables"),
                HttpMethod.GET, authorized(null, login(EMAIL_A)), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().contains("tenant-table-b"), response.getBody());
    }

    private String login(String email) {
        LocalLoginRequest request = new LocalLoginRequest();
        request.setEmail(email);
        request.setPassword(PASSWORD);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(url("/api/auth/local-login"),
                new HttpEntity<>(request, headers), AuthResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody().getAccessToken();
    }

    private HttpEntity<?> authorized(Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private void seedRestaurant(long id, String email, String name) {
        jdbcTemplate.update("insert into ristoratore (id, email, nome, password, provider, provider_id, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                id, email, name, "pwd", "LOCAL", null, LocalDateTime.now());
    }

    private void seedDish(long id, long restaurantId, String name) {
        jdbcTemplate.update("insert into piatto (id, nome, descrizione, prezzo, disponibile, categoria_id, consigliato, ristoratore_id) values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, "tenant isolation", new BigDecimal("10.00"), true, CATEGORY_ID, false, restaurantId);
    }

    private void seedUser(long restaurantId, String email) {
        backofficeUserRepository.save(BackofficeUser.builder()
                .email(email).nome(email).provider("LOCAL")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(BackofficeRole.RISTORATORE).restaurantId(restaurantId)
                .createdAt(LocalDateTime.now()).build());
    }

    private void seedTable(long id, long restaurantId, int number, String publicId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("insert into tavoli (id, ristoratore_id, table_public_id, numero, nome, coperti, attivo, qr_token, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, restaurantId, publicId, number, "Table " + number, 4, true, "qr-" + publicId, now, now);
    }

    private void seedOrder(long id, long restaurantId, int tableNumber) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("insert into customer_orders (id, created_at, status, table_id, updated_at, ristoratore_id, totale, item_count, variant) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, now, "APERTO", tableNumber, now, restaurantId, new BigDecimal("10.00"), 1, "A");
    }

    private void cleanup() {
        jdbcTemplate.update("delete from customer_order_submission where ristoratore_id in (?, ?)", RESTAURANT_A, RESTAURANT_B);
        jdbcTemplate.update("delete from customer_order_items where ordine_id in (?, ?)", ORDER_A, ORDER_B);
        jdbcTemplate.update("delete from customer_orders where id in (?, ?)", ORDER_A, ORDER_B);
        jdbcTemplate.update("delete from piatto where id in (?, ?)", DISH_A, DISH_B);
        jdbcTemplate.update("delete from menu_category where id = ?", CATEGORY_ID);
        jdbcTemplate.update("delete from backoffice_user where restaurant_id in (?, ?)", RESTAURANT_A, RESTAURANT_B);
        jdbcTemplate.update("delete from tavoli where id in (?, ?)", TABLE_A, TABLE_B);
        jdbcTemplate.update("delete from ristoratore where id in (?, ?)", RESTAURANT_A, RESTAURANT_B);
    }
}
