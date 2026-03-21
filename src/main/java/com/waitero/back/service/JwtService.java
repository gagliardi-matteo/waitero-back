package com.waitero.back.service;

import com.waitero.back.entity.Ristoratore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    @Value("${qr.token.secret}")
    private String qrSecret;

    @Value("${qr.token.expiration}")
    private long qrTokenExpirationMs;

    private Key key;
    private Key qrKey;

    @PostConstruct
    public void init() {
        key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        qrKey = Keys.hmacShaKeyFor(qrSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(Ristoratore ristoratore) {
        return Jwts.builder()
                .setSubject(ristoratore.getId().toString())
                .claim("email", ristoratore.getEmail())
                .claim("provider", ristoratore.getProvider())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Ristoratore ristoratore) {
        return Jwts.builder()
                .setSubject(ristoratore.getId().toString())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshExpirationMs))
                .signWith(key)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long extractUserId(String token) {
        Claims claims = Jwts.parserBuilder().setSigningKey(key).build()
                .parseClaimsJws(token).getBody();
        return Long.valueOf(claims.getSubject());
    }

    public String generateQrToken(Long restaurantId, Integer tableId) {
        return Jwts.builder()
                .claim("restaurantId", restaurantId)
                .claim("tableId", tableId)
                .claim("type", "qr")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + qrTokenExpirationMs))
                .signWith(qrKey)
                .compact();
    }

    public boolean validateQrToken(String token, String restaurantId, int tableId) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(qrKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            Object rIdRaw = claims.get("restaurantId");
            Object tIdRaw = claims.get("tableId");
            String type = claims.get("type", String.class);

            int rId = (rIdRaw instanceof Integer) ? (Integer) rIdRaw : Integer.parseInt(rIdRaw.toString());
            int tId = (tIdRaw instanceof Integer) ? (Integer) tIdRaw : Integer.parseInt(tIdRaw.toString());

            return "qr".equals(type)
                    && rId == Integer.parseInt(restaurantId)
                    && tId == tableId;
        } catch (ExpiredJwtException e) {
            Claims claims = e.getClaims();
            String rId = String.valueOf(claims.get("restaurantId", Integer.class));
            Integer tId = claims.get("tableId", Integer.class);
            String type = claims.get("type", String.class);

            return "qr".equals(type)
                    && rId.equals(restaurantId)
                    && tId == tableId;
        } catch (Exception e) {
            System.out.println("Errore validazione QR token");
            e.printStackTrace();
            return false;
        }
    }
}
