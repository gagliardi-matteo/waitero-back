package com.waitero.back.service;

import com.waitero.back.dto.CustomerLegalAcceptanceRequest;
import com.waitero.back.dto.LegalConfigResponse;
import com.waitero.back.dto.LegalStatusResponse;
import com.waitero.back.entity.LegalAcceptance;
import com.waitero.back.entity.LegalAcceptanceType;
import com.waitero.back.entity.Tavolo;
import com.waitero.back.repository.LegalAcceptanceRepository;
import com.waitero.back.security.AccessContextService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class LegalAcceptanceService {

    private final LegalAcceptanceRepository legalAcceptanceRepository;
    private final AccessContextService accessContextService;
    private final TavoloService tavoloService;

    @Value("${waitero.legal.contract-version:1.0}")
    private String contractVersion;

    @Value("${waitero.legal.privacy-version:1.0}")
    private String privacyVersion;

    @Value("${waitero.legal.terms-version:1.0}")
    private String termsVersion;

    @Value("${waitero.legal.allergen-disclaimer-version:1.0}")
    private String allergenDisclaimerVersion;

    @Value("${waitero.legal.contract-url:/legal/terms-client-v1.0.html}")
    private String contractUrl;

    @Value("${waitero.legal.privacy-url:/legal/privacy-client-v1.0.html}")
    private String privacyUrl;

    @Value("${waitero.legal.terms-url:/legal/terms-client-v1.0.html}")
    private String termsUrl;

    @Value("${waitero.legal.allergen-disclaimer-url:/legal/disclaimer-allergeni-v1.0.html}")
    private String allergenDisclaimerUrl;

    public LegalConfigResponse getConfig() {
        return LegalConfigResponse.builder()
                .contractVersion(contractVersion)
                .privacyVersion(privacyVersion)
                .termsVersion(termsVersion)
                .allergenDisclaimerVersion(allergenDisclaimerVersion)
                .contractUrl(contractUrl)
                .privacyUrl(privacyUrl)
                .termsUrl(termsUrl)
                .allergenDisclaimerUrl(allergenDisclaimerUrl)
                .build();
    }

    public LegalStatusResponse getBackofficeStatus() {
        Long restaurantId = accessContextService.getActingRestaurantIdOrThrow();
        boolean accepted = legalAcceptanceRepository.existsByTypeAndRestaurantIdAndContractVersionAndPrivacyVersion(
                LegalAcceptanceType.BACKOFFICE,
                restaurantId,
                contractVersion,
                privacyVersion
        );
        return LegalStatusResponse.builder()
                .accepted(accepted)
                .config(getConfig())
                .build();
    }

    @Transactional
    public LegalStatusResponse acceptBackoffice(HttpServletRequest request) {
        Long restaurantId = accessContextService.getActingRestaurantIdOrThrow();
        if (!legalAcceptanceRepository.existsByTypeAndRestaurantIdAndContractVersionAndPrivacyVersion(
                LegalAcceptanceType.BACKOFFICE,
                restaurantId,
                contractVersion,
                privacyVersion
        )) {
            legalAcceptanceRepository.save(LegalAcceptance.builder()
                    .type(LegalAcceptanceType.BACKOFFICE)
                    .restaurantId(restaurantId)
                    .contractVersion(contractVersion)
                    .privacyVersion(privacyVersion)
                    .acceptedAt(LocalDateTime.now())
                    .ipAddress(clientIp(request))
                    .userAgent(truncate(request.getHeader("User-Agent"), 512))
                    .build());
        }
        return getBackofficeStatus();
    }

    public LegalStatusResponse getCustomerStatus(String sessionId) {
        boolean accepted = normalize(sessionId) != null
                && legalAcceptanceRepository.existsByTypeAndSessionIdAndTermsVersionAndPrivacyVersionAndAllergenDisclaimerVersion(
                LegalAcceptanceType.CUSTOMER_QR,
                sessionId.trim(),
                termsVersion,
                privacyVersion,
                allergenDisclaimerVersion
        );
        return LegalStatusResponse.builder()
                .accepted(accepted)
                .config(getConfig())
                .build();
    }

    public LegalStatusResponse acceptCustomer(CustomerLegalAcceptanceRequest request, HttpServletRequest httpRequest) {
        String sessionId = normalize(request.getSessionId());
        String qrToken = normalize(request.getQrToken());
        if (sessionId == null) {
            throw new RuntimeException("Dati accettazione cliente incompleti");
        }

        Tavolo tavolo = resolveTableIfAvailable(request);
        Long restaurantId = tavolo != null ? tavolo.getRistoratore().getId() : parseLongOrNull(request.getRestaurantId());
        Integer tableNumber = tavolo != null ? tavolo.getNumero() : request.getTableId();
        String tablePublicId = tavolo != null ? tavolo.getTablePublicId() : normalize(request.getTablePublicId());

        if (!legalAcceptanceRepository.existsByTypeAndSessionIdAndTermsVersionAndPrivacyVersionAndAllergenDisclaimerVersion(
                LegalAcceptanceType.CUSTOMER_QR,
                sessionId,
                termsVersion,
                privacyVersion,
                allergenDisclaimerVersion
        )) {
            legalAcceptanceRepository.save(LegalAcceptance.builder()
                    .type(LegalAcceptanceType.CUSTOMER_QR)
                    .restaurantId(restaurantId)
                    .tablePublicId(tablePublicId)
                    .tableNumber(tableNumber)
                    .qrTokenHash(qrToken != null ? sha256(qrToken) : null)
                    .sessionId(sessionId)
                    .termsVersion(termsVersion)
                    .privacyVersion(privacyVersion)
                    .allergenDisclaimerVersion(allergenDisclaimerVersion)
                    .acceptedAt(LocalDateTime.now())
                    .ipAddress(clientIp(httpRequest))
                    .userAgent(truncate(httpRequest.getHeader("User-Agent"), 512))
                    .build());
        }

        return getCustomerStatus(sessionId);
    }

    private Tavolo resolveTableIfAvailable(CustomerLegalAcceptanceRequest request) {
        try {
            return tavoloService.resolveActiveTableForAccess(
                    normalize(request.getTablePublicId()),
                    normalize(request.getRestaurantId()),
                    request.getTableId()
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Long parseLongOrNull(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return truncate(forwardedFor.split(",")[0].trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 non disponibile", ex);
        }
    }
}
