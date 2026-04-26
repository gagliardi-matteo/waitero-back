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
    public static final String VARIANT_C = "C";
    public static final String VARIANT_HOLDOUT = "HOLDOUT";
    public static final String MODE_AB = "AB";
    public static final String MODE_ABC = "ABC";
    public static final String MODE_FORCE_A = "FORCE_A";
    public static final String MODE_FORCE_B = "FORCE_B";
    public static final String MODE_FORCE_C = "FORCE_C";

    private static final int DEFAULT_ROLLOUT_HOLDOUT_PERCENT = 5;

    private final ExperimentAssignmentRepository experimentAssignmentRepository;
    private final ExperimentConfigRepository experimentConfigRepository;
    private final ExperimentModeRepository experimentModeRepository;

    @Transactional
    public String getVariant(String sessionId, Long restaurantId) {
        return getVariant(sessionId, restaurantId, null);
    }

    @Transactional
    public String getVariant(String sessionId, Long restaurantId, Integer tableId) {
        if (restaurantId == null) {
            return VARIANT_A;
        }

        String assignmentKey = resolveAssignmentKey(sessionId, restaurantId, tableId);
        if (assignmentKey == null) {
            return defaultVariantWithoutAssignment(restaurantId);
        }

        ExperimentAssignmentId id = new ExperimentAssignmentId(assignmentKey, restaurantId);
        return experimentAssignmentRepository.findById(id)
                .map(existing -> normalizeVariant(existing.getVariant()))
                .orElseGet(() -> assignAndPersist(id, assignmentKey, restaurantId));
    }

    @Transactional
    public String pinVariant(String sessionId, Long restaurantId, Integer tableId, String variant) {
        String normalizedVariant = normalizeVariant(variant);
        if (restaurantId == null) {
            return normalizedVariant;
        }

        String assignmentKey = resolveAssignmentKey(sessionId, restaurantId, tableId);
        if (assignmentKey == null) {
            return normalizedVariant;
        }

        ExperimentAssignmentId id = new ExperimentAssignmentId(assignmentKey, restaurantId);
        ExperimentAssignment assignment = experimentAssignmentRepository.findById(id)
                .orElseGet(() -> ExperimentAssignment.builder()
                        .id(id)
                        .createdAt(Instant.now())
                        .build());
        assignment.setVariant(normalizedVariant);
        if (assignment.getCreatedAt() == null) {
            assignment.setCreatedAt(Instant.now());
        }
        return normalizeVariant(experimentAssignmentRepository.save(assignment).getVariant());
    }

    @Transactional(readOnly = true)
    public String getExperimentMode(Long restaurantId) {
        if (restaurantId == null) {
            return MODE_FORCE_B;
        }
        return experimentModeRepository.findByRestaurantId(restaurantId)
                .map(ExperimentMode::getMode)
                .map(this::normalizeMode)
                .orElse(MODE_FORCE_B);
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
                        .mode(MODE_FORCE_B)
                        .build());
        experimentMode.setMode(normalizedMode);
        experimentModeRepository.save(experimentMode);
    }

    private String assignAndPersist(ExperimentAssignmentId id, String assignmentKey, Long restaurantId) {
        String mode = getExperimentMode(restaurantId);
        String assignedVariant = calculateVariant(assignmentKey, restaurantId, mode);

        if ((MODE_AB.equals(mode) || MODE_ABC.equals(mode)) && hasUnsafeDistribution(restaurantId, mode)) {
            assignedVariant = deterministicVariant(assignmentKey, restaurantId, mode);
        }

        return persistIfMissing(id, assignedVariant);
    }

    private String calculateVariant(String assignmentKey, Long restaurantId, String mode) {
        if (MODE_FORCE_A.equals(mode)) {
            return VARIANT_A;
        }

        int bucket = bucket(assignmentKey, restaurantId);
        if (MODE_FORCE_B.equals(mode)) {
            return bucket < readHoldoutPercent(restaurantId) ? VARIANT_A : VARIANT_B;
        }
        if (MODE_FORCE_C.equals(mode)) {
            return bucket < readHoldoutPercent(restaurantId) ? VARIANT_A : VARIANT_C;
        }
        if (MODE_ABC.equals(mode)) {
            if (bucket < 34) {
                return VARIANT_A;
            }
            if (bucket < 67) {
                return VARIANT_B;
            }
            return VARIANT_C;
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

    private String defaultVariantWithoutAssignment(Long restaurantId) {
        String mode = getExperimentMode(restaurantId);
        if (MODE_FORCE_A.equals(mode)) {
            return VARIANT_A;
        }
        if (MODE_FORCE_B.equals(mode)) {
            return VARIANT_B;
        }
        if (MODE_FORCE_C.equals(mode)) {
            return VARIANT_C;
        }
        return VARIANT_A;
    }

    private String deterministicVariant(String assignmentKey, Long restaurantId, String mode) {
        return calculateVariant(assignmentKey, restaurantId, mode);
    }

    private int bucket(String assignmentKey, Long restaurantId) {
        return Math.floorMod((assignmentKey + restaurantId).hashCode(), 100);
    }

    private int readHoldoutPercent(Long restaurantId) {
        return experimentConfigRepository.findByRestaurantId(restaurantId)
                .map(ExperimentConfig::getHoldoutPercent)
                .map(value -> Math.max(0, Math.min(value, 100)))
                .orElse(DEFAULT_ROLLOUT_HOLDOUT_PERCENT);
    }

    private boolean hasUnsafeDistribution(Long restaurantId, String mode) {
        Map<String, Long> counts = experimentAssignmentRepository.countVariantsByRestaurant(restaurantId)
                .stream()
                .map(row -> Map.entry(normalizeVariant(row.getVariant()), row.getCount() == null ? 0L : row.getCount()))
                .filter(entry -> isTrackedVariant(entry.getKey(), mode))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));

        if (MODE_ABC.equals(mode)) {
            long a = counts.getOrDefault(VARIANT_A, 0L);
            long b = counts.getOrDefault(VARIANT_B, 0L);
            long c = counts.getOrDefault(VARIANT_C, 0L);
            long total = a + b + c;
            if (total < 30L) {
                return false;
            }

            double minShare = Math.min(a, Math.min(b, c)) / (double) total;
            return minShare < 0.20d;
        }

        long a = counts.getOrDefault(VARIANT_A, 0L);
        long b = counts.getOrDefault(VARIANT_B, 0L);
        long total = a + b;
        if (total < 20L) {
            return false;
        }

        double minShare = Math.min(a, b) / (double) total;
        return minShare < 0.30d;
    }

    private boolean isTrackedVariant(String variant, String mode) {
        if (MODE_ABC.equals(mode)) {
            return VARIANT_A.equals(variant) || VARIANT_B.equals(variant) || VARIANT_C.equals(variant);
        }
        return VARIANT_A.equals(variant) || VARIANT_B.equals(variant);
    }

    private String normalizeVariant(String variant) {
        if (variant == null || variant.isBlank()) {
            return VARIANT_A;
        }
        String normalized = variant.trim().toUpperCase(Locale.ROOT);
        if (VARIANT_HOLDOUT.equals(normalized)) {
            return VARIANT_HOLDOUT;
        }
        if (VARIANT_C.equals(normalized)) {
            return VARIANT_C;
        }
        if (VARIANT_B.equals(normalized)) {
            return VARIANT_B;
        }
        return VARIANT_A;
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_FORCE_B;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (MODE_FORCE_A.equals(normalized)
                || MODE_FORCE_B.equals(normalized)
                || MODE_FORCE_C.equals(normalized)
                || MODE_AB.equals(normalized)
                || MODE_ABC.equals(normalized)) {
            return normalized;
        }
        return MODE_FORCE_B;
    }

    private String resolveAssignmentKey(String sessionId, Long restaurantId, Integer tableId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId != null) {
            return normalizedSessionId;
        }
        if (restaurantId == null || tableId == null) {
            return null;
        }
        return normalizeSessionId("table-" + restaurantId + "-" + tableId);
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
