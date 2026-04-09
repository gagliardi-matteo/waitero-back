package com.waitero.back.service;

import com.waitero.back.entity.ExperimentAssignment;
import com.waitero.back.entity.ExperimentAssignmentId;
import com.waitero.back.entity.ExperimentConfig;
import com.waitero.back.entity.ExperimentMode;
import com.waitero.back.repository.ExperimentAssignmentRepository;
import com.waitero.back.repository.ExperimentConfigRepository;
import com.waitero.back.repository.ExperimentModeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExperimentService {

    public static final String VARIANT_A = "A";
    public static final String VARIANT_B = "B";
    public static final String VARIANT_HOLDOUT = "HOLDOUT";
    public static final String MODE_AB = "AB";
    public static final String MODE_FORCE_A = "FORCE_A";
    public static final String MODE_FORCE_B = "FORCE_B";

    private final ExperimentAssignmentRepository experimentAssignmentRepository;
    private final ExperimentConfigRepository experimentConfigRepository;
    private final ExperimentModeRepository experimentModeRepository;

    @Transactional
    public String getVariant(String sessionId, Long restaurantId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (restaurantId == null || normalizedSessionId == null) {
            return VARIANT_A;
        }

        ExperimentAssignmentId id = new ExperimentAssignmentId(normalizedSessionId, restaurantId);
        return experimentAssignmentRepository.findById(id)
                .map(existing -> normalizeVariant(existing.getVariant()))
                .orElseGet(() -> assignAndPersist(id, normalizedSessionId, restaurantId));
    }

    @Transactional(readOnly = true)
    public String getExperimentMode(Long restaurantId) {
        if (restaurantId == null) {
            return MODE_AB;
        }
        return experimentModeRepository.findByRestaurantId(restaurantId)
                .map(ExperimentMode::getMode)
                .map(this::normalizeMode)
                .orElse(MODE_AB);
    }

    @Transactional
    public void setExperimentMode(Long restaurantId, String mode) {
        if (restaurantId == null) {
            return;
        }
        String normalizedMode = normalizeMode(mode);
        ExperimentMode experimentMode = experimentModeRepository.findByRestaurantId(restaurantId)
                .orElseGet(() -> ExperimentMode.builder()
                        .restaurantId(restaurantId)
                        .mode(MODE_AB)
                        .build());
        experimentMode.setMode(normalizedMode);
        experimentModeRepository.save(experimentMode);
    }

    private String assignAndPersist(ExperimentAssignmentId id, String sessionId, Long restaurantId) {
        String assignedVariant = calculateVariant(sessionId, restaurantId);

        if (!VARIANT_HOLDOUT.equals(assignedVariant) && hasUnsafeDistribution(restaurantId)) {
            assignedVariant = deterministicVariant(sessionId, restaurantId);
        }

        return persistIfMissing(id, assignedVariant);
    }

    private String calculateVariant(String sessionId, Long restaurantId) {
        int bucket = bucket(sessionId, restaurantId);
        int holdoutPercent = readHoldoutPercent(restaurantId);
        if (bucket < holdoutPercent) {
            return VARIANT_HOLDOUT;
        }

        String mode = getExperimentMode(restaurantId);
        if (MODE_FORCE_A.equals(mode)) {
            return VARIANT_A;
        }
        if (MODE_FORCE_B.equals(mode)) {
            return VARIANT_B;
        }
        return bucket < 50 ? VARIANT_A : VARIANT_B;
    }

    private String persistIfMissing(ExperimentAssignmentId id, String variant) {
        try {
            return normalizeVariant(experimentAssignmentRepository.save(
                    ExperimentAssignment.builder()
                            .id(id)
                            .variant(variant)
                            .createdAt(Instant.now())
                            .build()
            ).getVariant());
        } catch (DataIntegrityViolationException ex) {
            return experimentAssignmentRepository.findById(id)
                    .map(ExperimentAssignment::getVariant)
                    .map(this::normalizeVariant)
                    .orElse(variant);
        }
    }

    private String deterministicVariant(String sessionId, Long restaurantId) {
        return bucket(sessionId, restaurantId) < 50 ? VARIANT_A : VARIANT_B;
    }

    private int bucket(String sessionId, Long restaurantId) {
        return Math.floorMod((sessionId + restaurantId).hashCode(), 100);
    }

    private int readHoldoutPercent(Long restaurantId) {
        return experimentConfigRepository.findByRestaurantId(restaurantId)
                .map(ExperimentConfig::getHoldoutPercent)
                .map(value -> Math.max(0, Math.min(value, 100)))
                .orElse(10);
    }

    private boolean hasUnsafeDistribution(Long restaurantId) {
        Map<String, Long> counts = experimentAssignmentRepository.countVariantsByRestaurant(restaurantId)
                .stream()
                .map(row -> Map.entry(normalizeVariant(row.getVariant()), row.getCount() == null ? 0L : row.getCount()))
                .filter(entry -> VARIANT_A.equals(entry.getKey()) || VARIANT_B.equals(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));

        long a = counts.getOrDefault(VARIANT_A, 0L);
        long b = counts.getOrDefault(VARIANT_B, 0L);
        long total = a + b;
        if (total < 20) {
            return false;
        }

        double minShare = Math.min(a, b) / (double) total;
        return minShare < 0.30d;
    }

    private String normalizeVariant(String variant) {
        if (variant == null || variant.isBlank()) {
            return VARIANT_A;
        }
        String normalized = variant.trim().toUpperCase(Locale.ROOT);
        if (VARIANT_HOLDOUT.equals(normalized)) {
            return VARIANT_HOLDOUT;
        }
        if (VARIANT_B.equals(normalized)) {
            return VARIANT_B;
        }
        return VARIANT_A;
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_AB;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (MODE_FORCE_A.equals(normalized) || MODE_FORCE_B.equals(normalized)) {
            return normalized;
        }
        return MODE_AB;
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        String normalized = sessionId.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }
}

