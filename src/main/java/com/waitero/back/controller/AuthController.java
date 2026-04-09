package com.waitero.back.controller;

import com.waitero.back.dto.AuthMeResponse;
import com.waitero.back.dto.AuthResponse;
import com.waitero.back.dto.BackofficeProfileDTO;
import com.waitero.back.dto.ChangePasswordRequest;
import com.waitero.back.dto.IdTokenRequest;
import com.waitero.back.dto.LocalLoginRequest;
import com.waitero.back.dto.RefreshTokenRequest;
import com.waitero.back.dto.UpdateProfileRequest;
import com.waitero.back.security.AccessContextService;
import com.waitero.back.security.BackofficePrincipal;
import com.waitero.back.service.AuthService;
import com.waitero.back.service.BackofficeAccountService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.GeneralSecurityException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final BackofficeAccountService backofficeAccountService;
    private final AccessContextService accessContextService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody IdTokenRequest request) throws GeneralSecurityException, IOException {
        AuthResponse response = authService.loginWithGoogle(request.getIdToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/local-login")
    public ResponseEntity<AuthResponse> localLogin(@RequestBody LocalLoginRequest request) {
        AuthResponse response = authService.loginWithLocalCredentials(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<AuthResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshAccessToken(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/userID")
    public Long getUserId(HttpServletRequest request){
        return accessContextService.getAuthenticatedUserId();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthMeResponse> me(Authentication authentication) {
        BackofficePrincipal principal = (BackofficePrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(AuthMeResponse.builder()
                .userId(principal.userId())
                .role(principal.role())
                .restaurantId(principal.restaurantId())
                .actingRestaurantId(principal.actingRestaurantId())
                .build());
    }

    @GetMapping("/profile")
    public BackofficeProfileDTO getProfile() {
        return backofficeAccountService.getProfile();
    }

    @PutMapping("/profile")
    public BackofficeProfileDTO updateProfile(@RequestBody UpdateProfileRequest request) {
        return backofficeAccountService.updateProfile(request);
    }

    @PutMapping("/password")
    public BackofficeProfileDTO changePassword(@RequestBody ChangePasswordRequest request) {
        return backofficeAccountService.changePassword(request);
    }
}

