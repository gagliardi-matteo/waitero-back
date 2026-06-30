package com.waitero.back.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.waitero.back.dto.AuthResponse;
import com.waitero.back.dto.DeviceTrustLoginRequest;
import com.waitero.back.dto.LocalLoginRequest;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.repository.BackofficeUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final String GOOGLE_PROVIDER = "GOOGLE";
    private static final String LOCAL_PROVIDER = "LOCAL";

    private final BackofficeUserRepository backofficeUserRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    @Value("${google.auth.client-ids}")
    private String googleAuthClientIds;

    @Transactional
    public AuthResponse loginWithGoogle(String idTokenString) throws GeneralSecurityException, IOException {
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), JacksonFactory.getDefaultInstance()
        )
                .setAudience(getGoogleAuthClientIds())
                .build();

        GoogleIdToken idToken = verifier.verify(idTokenString);
        if (idToken == null) {
            throw new RuntimeException("ID Token non valido");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String email = payload.getEmail();
        String sub = payload.getSubject();
        String name = (String) payload.get("name");
        Optional<BackofficeUser> byProviderId = backofficeUserRepository.findByProviderId(sub);
        Optional<BackofficeUser> byMasterEmail = backofficeUserRepository.findFirstByEmailIgnoreCaseAndRole(email, BackofficeRole.MASTER);
        Optional<BackofficeUser> byRistoratoreEmail = backofficeUserRepository.findFirstByEmailIgnoreCaseAndRole(email, BackofficeRole.RISTORATORE);

        log.info(
                "Google login attempt email={} sub={} byProviderId={} byMasterEmail={} byRistoratoreEmail={}",
                email,
                sub,
                summarizeUser(byProviderId),
                summarizeUser(byMasterEmail),
                summarizeUser(byRistoratoreEmail)
        );

        BackofficeUser user = byProviderId
                .or(() -> byMasterEmail)
                .or(() -> byRistoratoreEmail)
                .orElseThrow(() -> new RuntimeException(
                        "Account Google non autorizzato. email=" + email +
                                ", sub=" + sub +
                                ", byProviderId=" + summarizeUser(byProviderId) +
                                ", byMasterEmail=" + summarizeUser(byMasterEmail) +
                                ", byRistoratoreEmail=" + summarizeUser(byRistoratoreEmail)
                ));

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

        return buildResponse(user, normalize(request.getDeviceId()));
    }

    @Transactional(readOnly = true)
    public AuthResponse loginWithDeviceTrust(DeviceTrustLoginRequest request) {
        if (request == null || request.getDeviceId() == null || request.getDeviceTrustToken() == null) {
            throw new RuntimeException("Token dispositivo obbligatorio");
        }

        String deviceId = normalize(request.getDeviceId());
        String trustToken = request.getDeviceTrustToken().trim();
        if (!jwtService.validateToken(trustToken) || !jwtService.isDeviceTrustToken(trustToken)) {
            throw new RuntimeException("Token dispositivo non valido");
        }

        String tokenDeviceId = normalize(jwtService.extractDeviceId(trustToken));
        if (tokenDeviceId == null || !tokenDeviceId.equals(deviceId)) {
            throw new RuntimeException("Token dispositivo non valido");
        }

        Long userId = jwtService.extractUserId(trustToken);
        BackofficeUser user = backofficeUserRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utente non trovato"));

        if (user.getRole() != BackofficeRole.RISTORATORE || user.getRestaurantId() == null) {
            throw new RuntimeException("Account locale non associato a un locale");
        }

        return buildResponse(user, deviceId);
    }

    public AuthResponse refreshAccessToken(String refreshToken) {
        if (!jwtService.validateToken(refreshToken)) {
            throw new RuntimeException("Refresh token non valido");
        }

        Long userId = jwtService.extractUserId(refreshToken);
        BackofficeUser user = backofficeUserRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utente non trovato"));

        return buildResponse(user, null);
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

    private AuthResponse buildResponse(BackofficeUser user, String deviceId) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        String deviceTrustToken = deviceId == null ? null : jwtService.generateDeviceTrustToken(user, deviceId);
        return new AuthResponse(accessToken, refreshToken, deviceTrustToken);
    }

    private AuthResponse buildResponse(BackofficeUser user) {
        return buildResponse(user, null);
    }

    private String summarizeUser(Optional<BackofficeUser> user) {
        return user.map(value -> "found{id=" + value.getId()
                        + ",email=" + value.getEmail()
                        + ",role=" + value.getRole()
                        + ",provider=" + value.getProvider()
                        + ",providerId=" + value.getProviderId()
                        + "}")
                .orElse("none");
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private List<String> getGoogleAuthClientIds() {
        return Arrays.stream(googleAuthClientIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toList());
    }
}
