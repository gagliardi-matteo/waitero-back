package com.waitero.back.service;

import com.waitero.analyticsv2.dto.UpsellSuggestionV2DTO;
import com.waitero.analyticsv2.service.UpsellV2Service;
import com.waitero.analyticsv2.support.AnalyticsV2JsonLogger;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.dto.DishIntelligenceDTO;
import com.waitero.back.entity.Categoria;
import com.waitero.back.entity.Piatto;
import com.waitero.back.repository.DishCooccurrenceRepository;
import com.waitero.back.repository.OrdineItemRepository;
import com.waitero.back.repository.PiattoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpsellServiceTest {

    @Mock
    private OrdineItemRepository ordineItemRepository;

    @Mock
    private DishCooccurrenceRepository dishCooccurrenceRepository;

    @Mock
    private PiattoRepository piattoRepository;

    @Mock
    private MenuIntelligenceService menuIntelligenceService;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private ExperimentService experimentService;

    @Mock
    private AnalyticsV2JsonLogger analyticsV2JsonLogger;

    @Mock
    private UpsellV2Service upsellV2Service;

    @Mock
    private AnalyticsV2TimeRangeResolver analyticsV2TimeRangeResolver;

    @Mock
    private DishIntelligenceService dishIntelligenceService;

    @InjectMocks
    private UpsellService upsellService;

    @Test
    void shouldBoostVariantCSuggestionsUsingDishIntelligenceWhileFilteringUnavailableDishes() {
        Long restaurantId = 1L;
        Long baseDishId = 10L;
        Piatto baseDish = dish(baseDishId, "Base", Categoria.PRIMO, "12.00", true);
        Piatto suggestionA = dish(21L, "Suggestion A", Categoria.PRIMO, "5.00", true);
        Piatto suggestionB = dish(22L, "Suggestion B", Categoria.PRIMO, "6.00", true);
        Piatto suggestionC = dish(23L, "Suggestion C", Categoria.PRIMO, "7.00", true);
        Piatto unavailableSuggestion = dish(24L, "Unavailable", Categoria.PRIMO, "4.00", false);

        when(experimentService.getVariant("session-c", restaurantId, 4)).thenReturn(ExperimentService.VARIANT_C);
        when(piattoRepository.findByIdAndRistoratoreId(baseDishId, restaurantId)).thenReturn(Optional.of(baseDish));
        when(piattoRepository.findAllByRistoratoreId(restaurantId)).thenReturn(List.of(
                baseDish,
                suggestionA,
                suggestionB,
                suggestionC,
                unavailableSuggestion
        ));
        when(dishCooccurrenceRepository.findAvailableSuggestions(baseDishId, restaurantId)).thenReturn(List.of());
        when(analyticsService.getDishFeatures(restaurantId)).thenReturn(List.of());
        when(dishIntelligenceService.getDishIntelligence(restaurantId)).thenReturn(List.of(
                intelligence(21L, "Suggestion A", "0.05"),
                intelligence(22L, "Suggestion B", "0.10"),
                intelligence(23L, "Suggestion C", "1.00")
        ));

        List<Piatto> ranked = upsellService.getUpsellSuggestions(baseDishId, restaurantId, "session-c", 4);

        assertIterableEquals(List.of(23L, 21L), ranked.stream().map(Piatto::getId).toList());
    }

    @Test
    void shouldFilterUnavailableV2SuggestionsForVariantB() {
        Long restaurantId = 2L;
        Long baseDishId = 30L;
        Piatto availableSuggestion = dish(41L, "Available", Categoria.BEVANDA, "3.50", true);
        Piatto unavailableSuggestion = dish(42L, "Unavailable", Categoria.BEVANDA, "4.00", false);

        when(experimentService.getVariant("session-b", restaurantId, 7)).thenReturn(ExperimentService.VARIANT_B);
        when(piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId)).thenReturn(List.of(
                availableSuggestion,
                unavailableSuggestion
        ));
        when(upsellV2Service.getDishSuggestions(eq(restaurantId), eq(baseDishId), eq(2), any())).thenReturn(List.of(
                new UpsellSuggestionV2DTO(42L, "Unavailable", null, null, null, null, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, null),
                new UpsellSuggestionV2DTO(41L, "Available", null, null, null, null, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, null)
        ));

        List<Piatto> ranked = upsellService.getUpsellSuggestions(baseDishId, restaurantId, "session-b", 7);

        assertIterableEquals(List.of(41L), ranked.stream().map(Piatto::getId).toList());
    }

    private Piatto dish(Long id, String name, Categoria category, String price, boolean available) {
        return Piatto.builder()
                .id(id)
                .nome(name)
                .categoria(category)
                .prezzo(new BigDecimal(price))
                .disponibile(available)
                .build();
    }

    private DishIntelligenceDTO intelligence(Long dishId, String name, String affinityScore) {
        return DishIntelligenceDTO.builder()
                .dishId(dishId)
                .name(name)
                .score(BigDecimal.ZERO)
                .rpi(BigDecimal.ZERO)
                .ctr(BigDecimal.ZERO)
                .orderRate(BigDecimal.ZERO)
                .affinityScore(new BigDecimal(affinityScore))
                .explorationBoost(BigDecimal.ZERO)
                .performanceCategory("MEDIUM")
                .insights(List.of())
                .build();
    }
}
