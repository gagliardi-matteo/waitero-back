package com.waitero.back.service;

import com.waitero.analyticsv2.repository.AnalyticsV2MetricsRepository;
import com.waitero.analyticsv2.repository.CoOccurrenceV2Repository;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.dto.DishInsightApplyResultDTO;
import com.waitero.back.dto.InsightDTO;
import com.waitero.back.entity.Categoria;
import com.waitero.back.entity.Piatto;
import com.waitero.back.repository.DishIntelligenceEngagementRepository;
import com.waitero.back.repository.PiattoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DishIntelligenceServiceTest {

    @Mock
    private PiattoRepository piattoRepository;

    @Mock
    private AnalyticsV2MetricsRepository analyticsV2MetricsRepository;

    @Mock
    private CoOccurrenceV2Repository coOccurrenceV2Repository;

    @Mock
    private DishIntelligenceEngagementRepository dishIntelligenceEngagementRepository;

    @Mock
    private AnalyticsV2TimeRangeResolver analyticsV2TimeRangeResolver;

    @Mock
    private DishInsightPlanner dishInsightPlanner;

    @InjectMocks
    private DishIntelligenceService dishIntelligenceService;

    @Test
    void shouldApplyAutomaticInsightActionsInBatch() {
        Long restaurantId = 1L;
        Piatto promotedDish = dish(10L, "Promoted Dish", true, false);
        Piatto fixDish = dish(11L, "Fix Dish", true, true);
        Piatto removeDish = dish(12L, "Remove Dish", true, true);
        Piatto upsellTarget = dish(13L, "Upsell Target", true, false);
        List<Piatto> dishes = List.of(promotedDish, fixDish, removeDish, upsellTarget);

        AnalyticsV2TimeRange timeRange = new AnalyticsV2TimeRange(LocalDate.now().minusDays(29), LocalDate.now());

        when(piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId)).thenReturn(dishes);
        when(analyticsV2TimeRangeResolver.resolve(null, null)).thenReturn(timeRange);
        when(analyticsV2MetricsRepository.fetchDishMetrics(eq(restaurantId), eq(false), eq(timeRange))).thenReturn(List.of());
        when(dishIntelligenceEngagementRepository.fetchDishEngagement(eq(restaurantId), eq(timeRange))).thenReturn(List.of());
        when(coOccurrenceV2Repository.fetchTopRelatedDishesForBaseDishes(eq(restaurantId), any(), eq(true), anyInt(), eq(timeRange)))
                .thenReturn(List.of());
        when(dishInsightPlanner.plan(any(), any(), any(), anyInt())).thenReturn(List.of(
                InsightDTO.builder().type("PROMOTE").dishId(10L).message("promo").build(),
                InsightDTO.builder().type("FIX_CONVERSION").dishId(11L).message("fix").build(),
                InsightDTO.builder().type("REMOVE").dishId(12L).message("remove").build(),
                InsightDTO.builder().type("UPSELL").dishId(10L).targetDishId(13L).message("upsell").build()
        ));
        when(piattoRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DishInsightApplyResultDTO result = dishIntelligenceService.applyDishInsights(restaurantId);

        assertEquals(4, result.appliedCount());
        assertEquals(1, result.promotedCount());
        assertEquals(1, result.deprioritizedCount());
        assertEquals(1, result.removedCount());
        assertEquals(1, result.upsellActivatedCount());
        assertEquals(List.of(10L, 11L, 12L, 13L), result.updatedDishIds());

        assertTrue(promotedDish.getConsigliato());
        assertFalse(fixDish.getConsigliato());
        assertFalse(removeDish.getDisponibile());
        assertFalse(removeDish.getConsigliato());
        assertTrue(upsellTarget.getConsigliato());

        ArgumentCaptor<List<Piatto>> savedDishesCaptor = ArgumentCaptor.forClass(List.class);
        verify(piattoRepository).saveAll(savedDishesCaptor.capture());
        assertEquals(List.of(10L, 11L, 12L, 13L),
                savedDishesCaptor.getValue().stream().map(Piatto::getId).toList());
    }

    private Piatto dish(Long id, String name, boolean available, boolean recommended) {
        return Piatto.builder()
                .id(id)
                .nome(name)
                .categoria(Categoria.PRIMO)
                .prezzo(new BigDecimal("10.00"))
                .disponibile(available)
                .consigliato(recommended)
                .build();
    }
}
