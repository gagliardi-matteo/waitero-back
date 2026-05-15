package com.waitero.back.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class PrivacyProtectionService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${app.privacy.hash-secret:}")
    private String hashSecret;

    public String normalizeDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return deviceId.trim();
    }

    public String fingerprintHash(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        String normalized = fingerprint.trim();
        if (hashSecret == null || hashSecret.isBlank()) {
            return sha256(normalized);
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hashSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return sha256(normalized);
        }
    }

    public boolean fingerprintMatches(String storedValue, String incomingFingerprint) {
        if (storedValue == null || storedValue.isBlank() || incomingFingerprint == null || incomingFingerprint.isBlank()) {
            return false;
        }
        String normalizedIncoming = incomingFingerprint.trim();
        return storedValue.equals(normalizedIncoming) || storedValue.equals(fingerprintHash(normalizedIncoming));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Impossibile calcolare l'hash privacy", ex);
        }
    }
}
