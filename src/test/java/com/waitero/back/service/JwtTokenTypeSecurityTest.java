package com.waitero.back.service;

import com.waitero.back.config.JwtAuthenticationFilter;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.repository.BackofficeUserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtTokenTypeSecurityTest {

    private static final String JWT_SECRET = "test-jwt-secret-that-is-at-least-32-characters-long";
    private static final String QR_SECRET = "test-qr-secret-that-is-at-least-32-characters-long";

    private JwtService jwtService;
    private BackofficeUser user;

    @BeforeEach
    void setUp() {
        Environment environment = mock(Environment.class);
        jwtService = new JwtService(environment);
        ReflectionTestUtils.setField(jwtService, "activeJwtSecret", JWT_SECRET);
        ReflectionTestUtils.setField(jwtService, "legacyJwtSecret", "");
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", 60_000L);
        ReflectionTestUtils.setField(jwtService, "refreshExpirationMs", 120_000L);
        ReflectionTestUtils.setField(jwtService, "deviceTrustExpirationMs", 120_000L);
        ReflectionTestUtils.setField(jwtService, "activeQrSecret", QR_SECRET);
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
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accessAndRefreshTokensAreAcceptedOnlyForTheirOwnPurpose() {
        String access = jwtService.generateAccessToken(user);
        String refresh = jwtService.generateRefreshToken(user);

        assertTrue(jwtService.validateToken(access));
        assertFalse(jwtService.validateToken(refresh), "a refresh token must never authenticate a request");
        assertTrue(jwtService.validateRefreshToken(refresh));
        assertFalse(jwtService.validateRefreshToken(access), "an access token must never refresh a session");
    }

    @Test
    void invalidExpiredLegacyAndUnknownTokenTypesFailClosed() {
        assertFalse(jwtService.validateToken("not-a-jwt"));
        assertFalse(jwtService.validateRefreshToken("not-a-jwt"));

        String expiredAccess = signedToken("access", new Date(System.currentTimeMillis() - 1_000));
        String legacyWithoutType = signedToken(null, new Date(System.currentTimeMillis() + 60_000));
        String unknownType = signedToken("other", new Date(System.currentTimeMillis() + 60_000));

        assertFalse(jwtService.validateToken(expiredAccess));
        assertFalse(jwtService.validateRefreshToken(expiredAccess));
        assertFalse(jwtService.validateToken(legacyWithoutType));
        assertFalse(jwtService.validateRefreshToken(legacyWithoutType));
        assertFalse(jwtService.validateToken(unknownType));
        assertFalse(jwtService.validateRefreshToken(unknownType));
    }

    @Test
    void authenticationFilterRejectsRefreshTokenButAcceptsAccessToken() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

        MockHttpServletResponse accessResponse = filterRequest(filter, jwtService.generateAccessToken(user));
        assertTrue(accessResponse.getStatus() < 400);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());

        SecurityContextHolder.clearContext();
        MockHttpServletResponse refreshResponse = filterRequest(filter, jwtService.generateRefreshToken(user));
        assertTrue(refreshResponse.getStatus() >= 400);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void refreshServiceRejectsAccessTokenAndAcceptsRefreshToken() {
        BackofficeUserRepository repository = mock(BackofficeUserRepository.class);
        when(repository.findById(user.getId())).thenReturn(Optional.of(user));
        AuthService authService = new AuthService(repository, jwtService, mock(PasswordEncoder.class));

        assertDoesNotThrow(() -> authService.refreshAccessToken(jwtService.generateRefreshToken(user)));
        assertThrows(RuntimeException.class, () -> authService.refreshAccessToken(jwtService.generateAccessToken(user)));
    }

    private MockHttpServletResponse filterRequest(JwtAuthenticationFilter filter, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setRequestURI("/api/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private String signedToken(String type, Date expiration) {
        var builder = Jwts.builder()
                .setSubject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("provider", user.getProvider())
                .claim("role", user.getRole().name())
                .claim("restaurantId", user.getRestaurantId())
                .setIssuedAt(new Date())
                .setExpiration(expiration);
        if (type != null) {
            builder.claim("type", type);
        }
        return builder.signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
