package com.waitero.back.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.waitero.back.dto.AuthResponse;
import com.waitero.back.dto.LocalLoginRequest;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.repository.BackofficeUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String GOOGLE_PROVIDER = "GOOGLE";
    private static final String LOCAL_PROVIDER = "LOCAL";

    private final BackofficeUserRepository backofficeUserRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse loginWithGoogle(String idTokenString) throws GeneralSecurityException, IOException {
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), JacksonFactory.getDefaultInstance()
        )
                .setAudience(List.of("910347869788-astuldpi4hi3hb0osucuoclhfjdh5dtj.apps.googleusercontent.com"))
                .build();

        GoogleIdToken idToken = verifier.verify(idTokenString);
        if (idToken == null) {
            throw new RuntimeException("ID Token non valido");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String email = payload.getEmail();
        String sub = payload.getSubject();
        String name = (String) payload.get("name");

        BackofficeUser user = backofficeUserRepository.findByProviderId(sub)
                .or(() -> backofficeUserRepository.findFirstByEmailIgnoreCaseAndRole(email, BackofficeRole.MASTER))
                .or(() -> backofficeUserRepository.findFirstByEmailIgnoreCaseAndRole(email, BackofficeRole.RISTORATORE))
                .orElseThrow(() -> new RuntimeException("Account Google non autorizzato"));

        linkGoogleAccountIfNeeded(user, sub, name);
        return buildResponse(user);
    }

    public AuthResponse loginWithLocalCredentials(LocalLoginRequest request) {
        if (request == null || request.getEmail() == null || request.getPassword() == null) {
            throw new RuntimeException("Email e password obbligatorie");
        }

        String email = request.getEmail().trim();
        BackofficeUser user = backofficeUserRepository.findFirstByEmailIgnoreCaseAndProviderIgnoreCase(email, LOCAL_PROVIDER)
                .orElseThrow(() -> new RuntimeException("Credenziali non valide"));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Credenziali non valide");
        }

        if (user.getRole() != BackofficeRole.RISTORATORE || user.getRestaurantId() == null) {
            throw new RuntimeException("Account locale non associato a un locale");
        }

        return buildResponse(user);
    }

    public AuthResponse refreshAccessToken(String refreshToken) {
        if (!jwtService.validateToken(refreshToken)) {
            throw new RuntimeException("Refresh token non valido");
        }

        Long userId = jwtService.extractUserId(refreshToken);
        BackofficeUser user = backofficeUserRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utente non trovato"));

        return buildResponse(user);
    }

    private void linkGoogleAccountIfNeeded(BackofficeUser user, String providerId, String name) {
        // Allow relinking when the same backoffice account is reached by email but the stored
        // Google subject is stale. This keeps Google Sign-In usable after account migrations
        // or previous test links on the same email address.
        user.setProviderId(providerId);
        if (user.getRole() == BackofficeRole.MASTER) {
            user.setProvider(GOOGLE_PROVIDER);
        }
        if (name != null && !name.isBlank()) {
            user.setNome(name);
        }
        backofficeUserRepository.save(user);
    }

    private AuthResponse buildResponse(BackofficeUser user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken);
    }
}
