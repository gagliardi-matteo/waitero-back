package com.waitero.back.service;


import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.waitero.back.dto.AuthResponse;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.repository.RistoratoreRepository;
import lombok.RequiredArgsConstructor;
import com.google.api.client.json.jackson2.JacksonFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final RistoratoreRepository ristoratoreRepository;
    private final JwtService jwtService;

    public AuthResponse loginWithGoogle(String idTokenString) throws GeneralSecurityException, IOException {
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), JacksonFactory.getDefaultInstance()
        )
                .setAudience(List.of("910347869788-astuldpi4hi3hb0osucuoclhfjdh5dtj.apps.googleusercontent.com"))
                .build();

        GoogleIdToken idToken = verifier.verify(idTokenString);
        if (idToken == null) throw new RuntimeException("ID Token non valido");

        GoogleIdToken.Payload payload = idToken.getPayload();
        String email = payload.getEmail();
        String sub = payload.getSubject();
        String name = (String) payload.get("name");

        Ristoratore user = ristoratoreRepository.findByProviderId(sub)
                .orElseGet(() -> ristoratoreRepository.save(
                        Ristoratore.builder()
                                .email(email)
                                .nome(name)
                                .provider("GOOGLE")
                                .providerId(sub)
                                .build()
                ));

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return new AuthResponse(accessToken, refreshToken);
    }

    public AuthResponse refreshAccessToken(String refreshToken) {
        if (!jwtService.validateToken(refreshToken)) {
            throw new RuntimeException("Refresh token non valido");
        }

        Long userId = jwtService.extractUserId(refreshToken);

        Ristoratore user = ristoratoreRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utente non trovato"));

        String newAccessToken = jwtService.generateAccessToken(user);
        String newRefreshToken = jwtService.generateRefreshToken(user); // opzionale

        return new AuthResponse(newAccessToken, newRefreshToken);
    }
}


