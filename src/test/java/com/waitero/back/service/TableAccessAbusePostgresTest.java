package com.waitero.back.service;

import com.waitero.back.BackApplication;
import com.waitero.back.dto.SecureTableAccessRequest;
import com.waitero.back.dto.SecureTableAccessResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = BackApplication.class)
@ActiveProfiles("test")
class TableAccessAbusePostgresTest {

    private static final long RESTAURANT_A = 950001L;
    private static final long RESTAURANT_B = 950002L;
    private static final long TABLE_A = 950011L;
    private static final long TABLE_B = 950012L;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private TableAccessService tableAccessService;
    @MockitoBean private ServiceHourScheduleService serviceHourScheduleService;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        cleanup();
        when(serviceHourScheduleService.isOpenAt(any(), any())).thenReturn(true);
        seedRestaurant(RESTAURANT_A, "qr-a@test.local");
        seedRestaurant(RESTAURANT_B, "qr-b@test.local");
        tokenA = jwtService.generateQrToken(RESTAURANT_A, 1);
        tokenB = jwtService.generateQrToken(RESTAURANT_B, 2);
        seedTable(TABLE_A, RESTAURANT_A, 1, "public-a", tokenA);
        seedTable(TABLE_B, RESTAURANT_B, 2, "public-b", tokenB);
    }

    @AfterEach
    void tearDown() { cleanup(); }

    @Test
    void repeatedAndConcurrentSameDeviceScansCreateOneAssociation() throws Exception {
        assertTrue(access("public-a", RESTAURANT_A, 1, tokenA, "same-device", "fp-a").isAllowed());
        assertTrue(access("public-a", RESTAURANT_A, 1, tokenA, "same-device", "fp-a").isAllowed());

        List<CompletableFuture<SecureTableAccessResponse>> attempts = IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.supplyAsync(() ->
                        access("public-a", RESTAURANT_A, 1, tokenA, "same-device", "fp-a")))
                .toList();
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        assertTrue(attempts.stream().map(CompletableFuture::join).allMatch(SecureTableAccessResponse::isAllowed));
        assertEquals(1, deviceCount(TABLE_A, "same-device"));
    }

    @Test
    void sameQrFromDifferentDevicesRegistersEachOnce() {
        IntStream.range(0, 5).forEach(i -> assertTrue(
                access("public-a", RESTAURANT_A, 1, tokenA, "device-" + i, "fp-" + i).isAllowed()));
        assertEquals(5, tableDeviceCount(TABLE_A));
    }

    @Test
    void alteredAndCrossTenantTokensAreDeniedWithoutRegistration() {
        String altered = tokenA.substring(0, tokenA.length() - 1) + (tokenA.endsWith("x") ? "y" : "x");
        SecureTableAccessResponse alteredResponse = access("public-a", RESTAURANT_A, 1, altered, "bad-1", "fp");
        assertFalse(alteredResponse.isAllowed());
        assertEquals(null, alteredResponse.getQrToken(), "A denied request leaked the valid QR credential");
        assertFalse(access("public-a", RESTAURANT_A, 1, tokenB, "bad-2", "fp").isAllowed());
        assertFalse(access("public-b", RESTAURANT_B, 2, tokenA, "bad-3", "fp").isAllowed());
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from table_device", Integer.class));
    }

    @Test
    void conflictingPublicIdAndNumericContextMustBeDenied() {
        SecureTableAccessResponse response = access("public-a", RESTAURANT_B, 2, tokenA, "conflict", "fp");
        assertFalse(response.isAllowed(), "Contradictory publicId A plus numeric context B was silently accepted");
        assertEquals(0, deviceCount(TABLE_A, "conflict"));
        assertEquals(0, deviceCount(TABLE_B, "conflict"));
    }

    @Test
    void aDeviceMovingAtoBtoACannotBeAssociatedToWrongTable() {
        String device = "moving-device";
        assertTrue(access("public-a", RESTAURANT_A, 1, tokenA, device, "fp").isAllowed());
        assertTrue(access("public-b", RESTAURANT_B, 2, tokenB, device, "fp").isAllowed());
        assertTrue(access("public-a", RESTAURANT_A, 1, tokenA, device, "fp").isAllowed());
        assertEquals(1, deviceCount(TABLE_A, device));
        assertEquals(1, deviceCount(TABLE_B, device));
    }

    @Test
    void rotatedQrInvalidatesOldTokenEvenForPreviouslyRegisteredDevice() {
        assertTrue(access("public-a", RESTAURANT_A, 1, tokenA, "known-device", "fp").isAllowed());
        String rotated = jwtService.generateQrToken(RESTAURANT_A, 1);
        jdbcTemplate.update("update tavoli set qr_token = ? where id = ?", rotated, TABLE_A);

        assertFalse(access("public-a", RESTAURANT_A, 1, tokenA, "known-device", "fp").isAllowed());
        assertTrue(access("public-a", RESTAURANT_A, 1, rotated, "known-device", "fp").isAllowed());
        assertEquals(1, deviceCount(TABLE_A, "known-device"));
    }

    @Test
    void missingDeviceIdMustNotReturnAnUnusableAllowedSession() {
        SecureTableAccessResponse response = access("public-a", RESTAURANT_A, 1, tokenA, null, "fp");
        assertFalse(response.isAllowed(), "Access was allowed although customer APIs require a registered deviceId");
        assertEquals(0, tableDeviceCount(TABLE_A));
    }

    @Test
    void legacyDuplicateDeviceRowsMustNotCauseNonUniqueResultOrAdditionalDuplicates() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("insert into table_device (table_id, device_id, fingerprint, first_seen, last_seen, location_unverified) values (?, ?, ?, ?, ?, ?)",
                TABLE_A, "legacy-duplicate", "old-a", now.minusDays(2), now.minusDays(1), false);
        jdbcTemplate.update("insert into table_device (table_id, device_id, fingerprint, first_seen, last_seen, location_unverified) values (?, ?, ?, ?, ?, ?)",
                TABLE_A, "legacy-duplicate", "old-b", now.minusDays(1), now, false);

        assertTrue(access("public-a", RESTAURANT_A, 1, tokenA, "legacy-duplicate", null).isAllowed());
        assertEquals(2, deviceCount(TABLE_A, "legacy-duplicate"), "Legacy rows may remain, but access must not create a third row");
    }


    private SecureTableAccessResponse access(String publicId, long restaurantId, int tableNumber,
                                             String token, String deviceId, String fingerprint) {
        SecureTableAccessRequest request = new SecureTableAccessRequest();
        request.setTablePublicId(publicId);
        request.setRestaurantId(String.valueOf(restaurantId));
        request.setTableId(tableNumber);
        request.setQrToken(token);
        request.setDeviceId(deviceId);
        request.setFingerprint(fingerprint);
        request.setLocationUnavailable(true);
        return tableAccessService.validateAndRegister(request);
    }

    private void seedRestaurant(long id, String email) {
        jdbcTemplate.update("insert into ristoratore (id, email, nome, password, provider, created_at) values (?, ?, ?, ?, ?, ?)",
                id, email, email, "pwd", "LOCAL", LocalDateTime.now());
    }

    private void seedTable(long id, long restaurantId, int number, String publicId, String token) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("insert into tavoli (id, ristoratore_id, table_public_id, numero, nome, coperti, attivo, qr_token, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, restaurantId, publicId, number, "Table " + number, 4, true, token, now, now);
    }

    private int deviceCount(long tableId, String deviceId) {
        return jdbcTemplate.queryForObject("select count(*) from table_device where table_id = ? and device_id = ?",
                Integer.class, tableId, deviceId);
    }

    private int tableDeviceCount(long tableId) {
        return jdbcTemplate.queryForObject("select count(*) from table_device where table_id = ?", Integer.class, tableId);
    }

    private void cleanup() {
        jdbcTemplate.update("delete from table_access_log where table_id in (?, ?)", TABLE_A, TABLE_B);
        jdbcTemplate.update("delete from table_device where table_id in (?, ?)", TABLE_A, TABLE_B);
        jdbcTemplate.update("delete from tavoli where id in (?, ?)", TABLE_A, TABLE_B);
        jdbcTemplate.update("delete from ristoratore where id in (?, ?)", RESTAURANT_A, RESTAURANT_B);
    }
}
