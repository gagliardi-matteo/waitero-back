package com.waitero.back.controller;

import com.google.api.client.auth.oauth2.RefreshTokenRequest;
import com.waitero.back.dto.AuthResponse;
import com.waitero.back.dto.IdTokenRequest;
import com.waitero.back.service.AuthService;
import com.waitero.back.service.JwtService;
import com.waitero.back.service.RistoratoreService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.GeneralSecurityException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RistoratoreService ristoratoreService;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody IdTokenRequest request) throws GeneralSecurityException, IOException {
        AuthResponse response = authService.loginWithGoogle(request.getIdToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<AuthResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        AuthResponse response = authService.refreshAccessToken(refreshToken);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/userID")
    public Long getUserId(HttpServletRequest request){
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Long.parseLong("0");
        }

        String token = authHeader.substring(7);
        Long userId = jwtService.extractUserId(token);
        return userId;
    }
}