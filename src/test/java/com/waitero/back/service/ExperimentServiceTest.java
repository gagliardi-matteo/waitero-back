package com.waitero.back.service;

import com.waitero.back.entity.ExperimentAssignment;
import com.waitero.back.entity.ExperimentAssignmentId;
import com.waitero.back.entity.ExperimentConfig;
import com.waitero.back.repository.ExperimentAssignmentRepository;
import com.waitero.back.repository.ExperimentConfigRepository;
import com.waitero.back.repository.ExperimentModeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceTest {

    @Mock
    private ExperimentAssignmentRepository experimentAssignmentRepository;

    @Mock
    private ExperimentConfigRepository experimentConfigRepository;

    @Mock
    private ExperimentModeRepository experimentModeRepository;

    @InjectMocks
    private ExperimentService experimentService;

    @Test
    void shouldAssignDeterministicallyWithTableFallbackWhenSessionIsMissing() {
        when(experimentAssignmentRepository.findById(any())).thenReturn(Optional.empty());
        when(experimentConfigRepository.findByRestaurantId(100L)).thenReturn(Optional.empty());
        when(experimentModeRepository.findByRestaurantId(100L)).thenReturn(Optional.empty());
        when(experimentAssignmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String first = experimentService.getVariant(null, 100L, 12);
        String second = experimentService.getVariant(null, 100L, 12);

        assertEquals(first, second);
        assertTrue(ExperimentService.VARIANT_A.equals(first) || ExperimentService.VARIANT_B.equals(first));
    }

    @Test
    void shouldReusePersistedAssignmentForExistingSession() {
        ExperimentAssignmentId id = new ExperimentAssignmentId("session-1", 200L);
        when(experimentAssignmentRepository.findById(id)).thenReturn(Optional.of(
                ExperimentAssignment.builder()
                        .id(id)
                        .variant("B")
                        .build()
        ));

        assertEquals("B", experimentService.getVariant("session-1", 200L, 99));
    }

    @Test
    void shouldDefaultNewAssignmentsToV2WhenNoModeIsConfigured() {
        when(experimentAssignmentRepository.findById(any())).thenReturn(Optional.empty());
        when(experimentConfigRepository.findByRestaurantId(300L)).thenReturn(Optional.of(
                ExperimentConfig.builder()
                        .restaurantId(300L)
                        .holdoutPercent(0)
                        .build()
        ));
        when(experimentModeRepository.findByRestaurantId(300L)).thenReturn(Optional.empty());
        when(experimentAssignmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(ExperimentService.VARIANT_B, experimentService.getVariant("session-rollout", 300L, 7));
        assertEquals(ExperimentService.VARIANT_B, experimentService.getVariant(null, 300L, null));
    }

    @Test
    void shouldPinFallbackVariantToV1ForTheSameAssignment() {
        AtomicReference<ExperimentAssignment> stored = new AtomicReference<>();
        when(experimentAssignmentRepository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(experimentConfigRepository.findByRestaurantId(400L)).thenReturn(Optional.of(
                ExperimentConfig.builder()
                        .restaurantId(400L)
                        .holdoutPercent(0)
                        .build()
        ));
        when(experimentModeRepository.findByRestaurantId(400L)).thenReturn(Optional.empty());
        when(experimentAssignmentRepository.save(any())).thenAnswer(invocation -> {
            ExperimentAssignment assignment = invocation.getArgument(0);
            stored.set(assignment);
            return assignment;
        });

        assertEquals(ExperimentService.VARIANT_B, experimentService.getVariant("session-fallback", 400L, 11));
        assertEquals(ExperimentService.VARIANT_A, experimentService.pinVariant("session-fallback", 400L, 11, ExperimentService.VARIANT_A));
        assertEquals(ExperimentService.VARIANT_A, experimentService.getVariant("session-fallback", 400L, 11));
    }

    @Test
    void shouldAssignVariantCWhenForceCModeIsConfigured() {
        when(experimentAssignmentRepository.findById(any())).thenReturn(Optional.empty());
        when(experimentConfigRepository.findByRestaurantId(500L)).thenReturn(Optional.of(
                ExperimentConfig.builder()
                        .restaurantId(500L)
                        .holdoutPercent(0)
                        .build()
        ));
        when(experimentModeRepository.findByRestaurantId(500L)).thenReturn(Optional.of(
                com.waitero.back.entity.ExperimentMode.builder()
                        .restaurantId(500L)
                        .mode(ExperimentService.MODE_FORCE_C)
                        .build()
        ));
        when(experimentAssignmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(ExperimentService.VARIANT_C, experimentService.getVariant("session-force-c", 500L, 9));
    }
}
