package com.waitero.back.service;

import com.waitero.back.dto.SecureTableAccessRequest;
import com.waitero.back.dto.SecureTableAccessResponse;
import com.waitero.back.entity.OrderStatus;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.entity.TableAccessLog;
import com.waitero.back.entity.TableDevice;
import com.waitero.back.entity.Tavolo;
import com.waitero.back.repository.OrdineRepository;
import com.waitero.back.repository.TableAccessLogRepository;
import com.waitero.back.repository.TableDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

@Service
@RequiredArgsConstructor
public class TableAccessService {

    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(OrderStatus.APERTO, OrderStatus.PARZIALMENTE_PAGATO);

    private final TavoloService tavoloService;
    private final OrdineRepository ordineRepository;
    private final TableDeviceRepository tableDeviceRepository;
    private final TableAccessLogRepository tableAccessLogRepository;
    private final OrderStreamService orderStreamService;
    private final PrivacyProtectionService privacyProtectionService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public SecureTableAccessResponse validateAndRegister(SecureTableAccessRequest request) {
        Tavolo tavolo = tavoloService.resolveActiveTableForAccess(request.getTablePublicId(), request.getRestaurantId(), request.getTableId());
        Long restaurantId = tavolo.getRistoratore().getId();
        Integer tableId = tavolo.getNumero();

        if (!tavoloService.validateQrAccess(request.getQrToken(), String.valueOf(restaurantId), tableId)) {
            return denied("INVALID_QR", "QR non valido", tavolo, 0);
        }

        if (!tavoloService.isWithinServiceHours(restaurantId)) {
            logAccess(tavolo, request, 0, "service_closed");
            return denied("SERVICE_CLOSED", "Servizio non disponibile in questo orario", tavolo, 0);
        }

        AccessRisk risk = evaluateRisk(tavolo, request);
        if (risk.distanceDenied) {
            logAccess(tavolo, request, risk.score, risk.reasonString());
            maybePublishAlert(restaurantId, tableId, risk);
            return denied("ACCESS_DENIED_DISTANCE", "Accesso negato: dispositivo troppo distante dal locale", tavolo, risk.score);
        }

        registerDevice(tavolo, request, risk.locationUnverified);
        logAccess(tavolo, request, risk.score, risk.reasonString());
        maybePublishAlert(restaurantId, tableId, risk);

        SecureTableAccessResponse response = SecureTableAccessResponse.builder()
                .allowed(true)
                .status("OK")
                .message("Accesso consentito")
                .restaurantId(restaurantId)
                .tableId(tableId)
                .tablePublicId(tavolo.getTablePublicId())
                .tableName(tavolo.getNome())
                .qrToken(tavolo.getQrToken())
                .riskScore(risk.score)
                .locationUnverified(risk.locationUnverified)
                .build();

        return response;
    }

    private AccessRisk evaluateRisk(Tavolo tavolo, SecureTableAccessRequest request) {
        AccessRisk risk = new AccessRisk();
        Ristoratore restaurant = tavolo.getRistoratore();
        String deviceId = privacyProtectionService.normalizeDeviceId(request.getDeviceId());
        String fingerprint = normalize(request.getFingerprint());

        if (deviceId == null) {
            risk.score += 1;
            risk.reasons.add("missing_device_id");
            return risk;
        }

        boolean hasActiveOrder = ordineRepository.existsByRistoratoreIdAndTableIdAndStatusIn(restaurant.getId(), tavolo.getNumero(), ACTIVE_STATUSES);
        TableDevice existingDevice = tableDeviceRepository.findFirstByTavoloIdAndDeviceIdOrderByLastSeenDescIdDesc(tavolo.getId(), deviceId).orElse(null);

        if (existingDevice == null && hasActiveOrder) {
            risk.score += 3;
            risk.reasons.add("new_device_on_active_order");
        }

        if (existingDevice != null
                && fingerprint != null
                && existingDevice.getFingerprint() != null
                && !privacyProtectionService.fingerprintMatches(existingDevice.getFingerprint(), fingerprint)) {
            risk.score += 2;
            risk.reasons.add("fingerprint_mismatch");
        }

        if (request.getLatitude() == null || request.getLongitude() == null || Boolean.TRUE.equals(request.getLocationUnavailable())) {
            risk.locationUnverified = true;
            risk.reasons.add("location_unverified");
        } else if (restaurant.getLatitude() != null && restaurant.getLongitude() != null && restaurant.getAllowedRadiusMeters() != null) {
            double distance = haversineMeters(restaurant.getLatitude(), restaurant.getLongitude(), request.getLatitude(), request.getLongitude());
            if (distance > restaurant.getAllowedRadiusMeters()) {
                risk.score += 5;
                risk.distanceDenied = true;
                risk.reasons.add("distance_outside_radius");
            }
        }

        return risk;
    }

    private void registerDevice(Tavolo tavolo, SecureTableAccessRequest request, boolean locationUnverified) {
        String deviceId = privacyProtectionService.normalizeDeviceId(request.getDeviceId());
        if (deviceId == null) {
            return;
        }

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        lockTableDeviceRegistration(tavolo.getId(), deviceId);
        TableDevice device = tableDeviceRepository.findFirstByTavoloIdAndDeviceIdOrderByLastSeenDescIdDesc(tavolo.getId(), deviceId)
                .orElseGet(() -> TableDevice.builder()
                        .tavolo(tavolo)
                        .deviceId(deviceId)
                        .firstSeen(now)
                        .build());

        device.setFingerprint(privacyProtectionService.fingerprintHash(request.getFingerprint()));
        device.setLastSeen(now);
        device.setLocationUnverified(locationUnverified);
        if (device.getFirstSeen() == null) {
            device.setFirstSeen(now);
        }
        tableDeviceRepository.save(device);
    }

    private void lockTableDeviceRegistration(Long tableId, String deviceId) {
        long lockKey = tableDeviceLockKey(tableId, deviceId);
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?)", rs -> null, lockKey);
    }

    private long tableDeviceLockKey(Long tableId, String deviceId) {
        CRC32 crc32 = new CRC32();
        crc32.update((tableId + ":" + deviceId).getBytes(StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    private void logAccess(Tavolo tavolo, SecureTableAccessRequest request, int riskScore, String reason) {
        String fingerprintHash = privacyProtectionService.fingerprintHash(request.getFingerprint());
        tableAccessLogRepository.save(TableAccessLog.builder()
                .tavolo(tavolo)
                .deviceId(privacyProtectionService.normalizeDeviceId(request.getDeviceId()))
                .fingerprint(fingerprintHash)
                .latitude(null)
                .longitude(null)
                .accuracy(null)
                .timestamp(java.time.LocalDateTime.now())
                .riskScore(riskScore)
                .reason(reason)
                .build());
    }

    private void maybePublishAlert(Long restaurantId, Integer tableId, AccessRisk risk) {
        if (risk.score < 4) {
            return;
        }
        orderStreamService.publishSuspiciousTableAccess(restaurantId, tableId, risk.score, risk.reasonString());
    }

    private SecureTableAccessResponse denied(String status, String message, Tavolo tavolo, int riskScore) {
        return SecureTableAccessResponse.builder()
                .allowed(false)
                .status(status)
                .message(message)
                .restaurantId(tavolo.getRistoratore().getId())
                .tableId(tavolo.getNumero())
                .tablePublicId(tavolo.getTablePublicId())
                .tableName(tavolo.getNome())
                .qrToken(tavolo.getQrToken())
                .riskScore(riskScore)
                .locationUnverified(false)
                .build();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    private static class AccessRisk {
        private int score;
        private boolean distanceDenied;
        private boolean locationUnverified;
        private final List<String> reasons = new ArrayList<>();

        private String reasonString() {
            return reasons.isEmpty() ? "none" : String.join(",", reasons);
        }
    }
}
