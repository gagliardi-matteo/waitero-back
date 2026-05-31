package com.waitero.back.service;

import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    @Value("${jwt.active-secret:}")
    private String activeJwtSecret;

    @Value("${jwt.legacy-secret:}")
    private String legacyJwtSecret;

    @Value("${jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    @Value("${qr.token.active-secret:}")
    private String activeQrSecret;

    @Value("${qr.token.legacy-secret:}")
    private String legacyQrSecret;

    private final Environment environment;

    private Key activeKey;
    private Key activeQrKey;
    private List<Key> validationKeys;
    private List<Key> qrValidationKeys;

    public JwtService(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        ensureConfiguredSecrets();
        activeKey = toKey(activeJwtSecret, "jwt.active-secret");
        activeQrKey = toKey(activeQrSecret, "qr.token.active-secret");
        validationKeys = buildValidationKeys(activeJwtSecret, legacyJwtSecret, "jwt");
        qrValidationKeys = buildValidationKeys(activeQrSecret, legacyQrSecret, "qr");
    }

    public String generateAccessToken(BackofficeUser user) {
        return buildBackofficeToken(user, null, jwtExpirationMs);
    }

    public String generateRefreshToken(BackofficeUser user) {
        return buildBackofficeToken(user, null, refreshExpirationMs);
    }

    public String generateImpersonationAccessToken(BackofficeUser user, Long actingRestaurantId) {
        return buildBackofficeToken(user, actingRestaurantId, jwtExpirationMs);
    }

    private String buildBackofficeToken(BackofficeUser user, Long actingRestaurantId, long expirationMs) {
        var builder = Jwts.builder()
                .setSubject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("provider", user.getProvider())
                .claim("role", user.getRole().name())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs));

        if (user.getRestaurantId() != null) {
            builder.claim("restaurantId", user.getRestaurantId());
        }
        if (actingRestaurantId != null) {
            builder.claim("actingRestaurantId", actingRestaurantId);
        }

        return builder.signWith(activeKey).compact();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long extractUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public BackofficeRole extractRole(String token) {
        String role = parseClaims(token).get("role", String.class);
        return BackofficeRole.valueOf(role);
    }

    public Long extractRestaurantId(String token) {
        return extractLongClaim(parseClaims(token), "restaurantId");
    }

    public Long extractActingRestaurantId(String token) {
        return extractLongClaim(parseClaims(token), "actingRestaurantId");
    }

    private Long extractLongClaim(Claims claims, String claimName) {
        Object value = claims.get(claimName);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer integerValue) {
            return integerValue.longValue();
        }
        if (value instanceof Long longValue) {
            return longValue;
        }
        return Long.parseLong(value.toString());
    }

    private Claims parseClaims(String token) {
        return parseClaimsWithKnownKeys(token, validationKeys);
    }

    public String generateQrToken(Long restaurantId, Integer tableId) {
        return Jwts.builder()
                .claim("restaurantId", restaurantId)
                .claim("tableId", tableId)
                .claim("type", "qr")
                .setIssuedAt(new Date())
                .signWith(activeQrKey)
                .compact();
    }

    public boolean validateQrToken(String token, String restaurantId, int tableId) {
        try {
            Claims claims = parseQrClaims(token);

            Object rIdRaw = claims.get("restaurantId");
            Object tIdRaw = claims.get("tableId");
            String type = claims.get("type", String.class);

            long rId = (rIdRaw instanceof Number numberValue) ? numberValue.longValue() : Long.parseLong(rIdRaw.toString());
            int tId = (tIdRaw instanceof Integer) ? (Integer) tIdRaw : Integer.parseInt(tIdRaw.toString());

            return "qr".equals(type)
                    && rId == Long.parseLong(restaurantId)
                    && tId == tableId;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseQrClaims(String token) {
        return parseClaimsWithKnownKeys(token, qrValidationKeys, true);
    }

    private Claims parseClaimsWithKnownKeys(String token, List<Key> keys) {
        return parseClaimsWithKnownKeys(token, keys, false);
    }

    private Claims parseClaimsWithKnownKeys(String token, List<Key> keys, boolean allowExpired) {
        JwtException lastException = null;
        for (Key candidateKey : keys) {
            try {
                return Jwts.parserBuilder()
                        .setSigningKey(candidateKey)
                        .build()
                        .parseClaimsJws(token)
                        .getBody();
            } catch (ExpiredJwtException ex) {
                if (allowExpired) {
                    return ex.getClaims();
                }
                lastException = ex;
            } catch (JwtException ex) {
                lastException = ex;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new JwtException("Nessuna chiave valida disponibile");
    }

    private List<Key> buildValidationKeys(String activeSecret, String legacySecret, String label) {
        List<Key> keys = new ArrayList<>();
        keys.add(toKey(activeSecret, label + ".active"));
        if (legacySecret != null && !legacySecret.isBlank() && !legacySecret.equals(activeSecret)) {
            keys.add(toKey(legacySecret, label + ".legacy"));
        }
        return List.copyOf(keys);
    }

    private Key toKey(String secret, String label) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Secret mancante per " + label);
        }
        if (secret.trim().length() < 32) {
            throw new IllegalStateException("Secret troppo corto per " + label);
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private void ensureConfiguredSecrets() {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (production) {
            requireConfigured(activeJwtSecret, "JWT_ACTIVE_SECRET");
            requireConfigured(activeQrSecret, "QR_ACTIVE_SECRET");
        }
    }

    private void requireConfigured(String value, String envName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(envName + " non configurata");
        }
    }
}

