package com.waitero.back.service;

import com.waitero.back.dto.DeviceTrustLoginRequest;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.repository.BackofficeUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceTrustTokenRegressionTest {

    private JwtService jwtService;
    private BackofficeUserRepository repository;
    private BackofficeUser user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(mock(Environment.class));
        ReflectionTestUtils.setField(jwtService, "activeJwtSecret", "test-jwt-secret-that-is-at-least-32-characters-long");
        ReflectionTestUtils.setField(jwtService, "legacyJwtSecret", "");
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", 60_000L);
        ReflectionTestUtils.setField(jwtService, "refreshExpirationMs", 120_000L);
        ReflectionTestUtils.setField(jwtService, "deviceTrustExpirationMs", 120_000L);
        ReflectionTestUtils.setField(jwtService, "activeQrSecret", "test-qr-secret-that-is-at-least-32-characters-long");
        ReflectionTestUtils.setField(jwtService, "legacyQrSecret", "");
        jwtService.init();

        user = BackofficeUser.builder()
                .id(42L)
                .email("owner@example.test")
                .nome("Owner")
                .provider("LOCAL")
                .role(BackofficeRole.RISTORATORE)
                .restaurantId(7L)
                .build();
        repository = mock(BackofficeUserRepository.class);
        when(repository.findById(42L)).thenReturn(Optional.of(user));
    }

    @Test
    void validDeviceTrustTokenStillPerformsDeviceLogin() {
        DeviceTrustLoginRequest request = new DeviceTrustLoginRequest();
        request.setDeviceId("kitchen-device-1");
        request.setDeviceTrustToken(jwtService.generateDeviceTrustToken(user, "kitchen-device-1"));

        AuthService authService = new AuthService(repository, jwtService, mock(PasswordEncoder.class));
        assertDoesNotThrow(() -> authService.loginWithDeviceTrust(request));
    }
}
