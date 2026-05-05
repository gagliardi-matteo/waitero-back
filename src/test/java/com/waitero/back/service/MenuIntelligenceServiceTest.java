package com.waitero.back.service;

import com.waitero.analyticsv2.dto.MenuRankedDishV2DTO;
import com.waitero.analyticsv2.service.MenuIntelligenceV2Service;
import com.waitero.analyticsv2.support.AnalyticsV2JsonLogger;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.dto.DishIntelligenceDTO;
import com.waitero.back.entity.BusinessType;
import com.waitero.back.entity.MenuCategory;
import com.waitero.back.entity.Piatto;
import com.waitero.back.repository.PiattoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuIntelligenceServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PerformanceLabelResolver performanceLabelResolver;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private ExperimentService experimentService;

    @Mock
    private AnalyticsV2JsonLogger analyticsV2JsonLogger;

    @Mock
    private PiattoRepository piattoRepository;

    @Mock
    private MenuIntelligenceV2Service menuIntelligenceV2Service;

    @Mock
    private AnalyticsV2TimeRangeResolver analyticsV2TimeRangeResolver;

    @Mock
    private DishIntelligenceService dishIntelligenceService;

    @Mock
    private MenuRankingExperimentLogger menuRankingExperimentLogger;

    @InjectMocks
    private MenuIntelligenceService menuIntelligenceService;

    @Test
    void shouldRankUsingVariantCByDishScoreAndKeepDeterministicOrdering() {
        Long restaurantId = 10L;
        List<Piatto> dishes = List.of(
                dish(1L, "Primo Alpha", category("PRIMO", "Primi", 10), "12.00"),
                dish(2L, "Primo Beta", category("PRIMO", "Primi", 10), "11.00"),
                dish(3L, "Drink Gamma", category("BEVANDA", "Bevande", 60), "5.00")
        );
        when(experimentService.getVariant("session-c", restaurantId, 4)).thenReturn(ExperimentService.VARIANT_C);
        when(piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId)).thenReturn(dishes);
        when(dishIntelligenceService.getDishIntelligence(restaurantId)).thenReturn(List.of(
                dishScore(2L, "Primo Beta", "0.800000"),
                dishScore(1L, "Primo Alpha", "0.800000"),
                dishScore(3L, "Drink Gamma", "0.950000")
        ));

        List<Piatto> first = menuIntelligenceService.rankDishesByRevenue(restaurantId, "session-c", 4);
        List<Piatto> second = menuIntelligenceService.rankDishesByRevenue(restaurantId, "session-c", 4);

        assertIterableEquals(List.of(3L, 1L, 2L), first.stream().map(Piatto::getId).toList());
        assertEquals(first.stream().map(Piatto::getId).toList(), second.stream().map(Piatto::getId).toList());
        verify(menuRankingExperimentLogger, times(2)).logDishScoreRanking(eq(restaurantId), eq("session-c"), anyList(), any());
    }

    @Test
    void shouldFallbackToLegacyRankingWhenVariantCFails() {
        Long restaurantId = 20L;
        List<Piatto> dishes = List.of(
                dish(11L, "Legacy High", category("PRIMO", "Primi", 10), "14.00"),
                dish(12L, "Legacy Low", category("SECONDO", "Secondi", 20), "10.00")
        );
        when(experimentService.getVariant("session-fallback-c", restaurantId, 8)).thenReturn(ExperimentService.VARIANT_C);
        when(piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId)).thenReturn(dishes);
        when(dishIntelligenceService.getDishIntelligence(restaurantId)).thenThrow(new RuntimeException("dish intelligence offline"));
        when(analyticsService.getDishFeatures(restaurantId)).thenReturn(List.of(
                new AnalyticsService.DishFeatures(11L, 2L, 0.20d, 0.10d, 0.10d, 0.10d, 1.0d, 14.0d),
                new AnalyticsService.DishFeatures(12L, 2L, 0.10d, 0.05d, 0.05d, 0.05d, 0.2d, 10.0d)
        ));
        when(analyticsService.normalize(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(restaurantId))).thenReturn(0L);

        List<Piatto> ranked = menuIntelligenceService.rankDishesByRevenue(restaurantId, "session-fallback-c", 8);

        assertIterableEquals(List.of(11L, 12L), ranked.stream().map(Piatto::getId).toList());
        verify(experimentService).pinVariant("session-fallback-c", restaurantId, 8, ExperimentService.VARIANT_A);
        verify(menuRankingExperimentLogger).logDishScoreFallback(eq(restaurantId), eq("session-fallback-c"), any(RuntimeException.class));
    }

    @Test
    void shouldHideUnavailableDishesEvenWhenLegacyRankingIsServedFromCache() {
        Long restaurantId = 30L;
        Piatto visibleDish = dish(21L, "Visible Dish", category("PRIMO", "Primi", 10), "12.00");
        Piatto removedDishFirstLoad = dish(22L, "Removed Later", category("SECONDO", "Secondi", 20), "10.00");
        Piatto removedDishSecondLoad = dish(22L, "Removed Later", category("SECONDO", "Secondi", 20), "10.00");
        removedDishSecondLoad.setDisponibile(false);

        when(experimentService.getVariant("session-a", restaurantId, 2)).thenReturn(ExperimentService.VARIANT_A);
        when(piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId)).thenReturn(
                List.of(visibleDish, removedDishFirstLoad),
                List.of(visibleDish, removedDishSecondLoad)
        );
        when(analyticsService.getDishFeatures(restaurantId)).thenReturn(List.of(
                new AnalyticsService.DishFeatures(21L, 4L, 0.20d, 0.10d, 0.05d, 0.10d, 1.0d, 12.0d),
                new AnalyticsService.DishFeatures(22L, 4L, 0.10d, 0.05d, 0.03d, 0.05d, 0.5d, 10.0d)
        ));
        when(analyticsService.normalize(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(restaurantId))).thenReturn(40L);

        List<Piatto> first = menuIntelligenceService.rankDishesByRevenue(restaurantId, "session-a", 2);
        List<Piatto> second = menuIntelligenceService.rankDishesByRevenue(restaurantId, "session-a", 2);

        assertIterableEquals(List.of(21L, 22L), first.stream().map(Piatto::getId).toList());
        assertIterableEquals(List.of(21L), second.stream().map(Piatto::getId).toList());
    }

    private Piatto dish(Long id, String name, MenuCategory category, String price) {
        return Piatto.builder()
                .id(id)
                .nome(name)
                .categoria(category)
                .prezzo(new BigDecimal(price))
                .disponibile(true)
                .build();
    }

    private MenuCategory category(String code, String label, int sortOrder) {
        return MenuCategory.builder()
                .businessType(BusinessType.RISTORANTE)
                .code(code)
                .label(label)
                .sortOrder(sortOrder)
                .active(true)
                .build();
    }

    private DishIntelligenceDTO dishScore(Long dishId, String name, String score) {
        return DishIntelligenceDTO.builder()
                .dishId(dishId)
                .name(name)
                .score(new BigDecimal(score))
                .rpi(BigDecimal.ZERO)
                .ctr(BigDecimal.ZERO)
                .orderRate(BigDecimal.ZERO)
                .affinityScore(BigDecimal.ZERO)
                .explorationBoost(BigDecimal.ZERO)
                .performanceCategory("MEDIUM")
                .insights(List.of())
                .build();
    }
}
