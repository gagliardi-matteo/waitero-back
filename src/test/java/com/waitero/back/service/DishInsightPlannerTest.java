package com.waitero.back.service;

import com.waitero.back.dto.DishIntelligenceDTO;
import com.waitero.back.dto.InsightDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DishInsightPlannerTest {

    private final DishInsightPlanner planner = new DishInsightPlanner();

    @Test
    void shouldBuildActionableInsightsWithoutDuplicateDishes() {
        List<DishIntelligenceDTO> intelligence = List.of(
                dish(1L, "Promo Dish", "1.10", "0.10", "0.80", "0.05", "HIGH"),
                dish(2L, "Fix Dish", "0.55", "0.90", "0.03", "0.05", "MEDIUM"),
                dish(3L, "Upsell Dish", "0.70", "0.20", "0.20", "0.92", "MEDIUM"),
                dish(4L, "Remove Dish", "0.10", "0.03", "0.01", "0.02", "LOW"),
                dish(99L, "Target Dish", "0.60", "0.12", "0.12", "0.10", "MEDIUM")
        );

        Map<Long, Long> impressionsByDishId = new LinkedHashMap<>();
        impressionsByDishId.put(1L, 5L);
        impressionsByDishId.put(2L, 45L);
        impressionsByDishId.put(3L, 20L);
        impressionsByDishId.put(4L, 90L);
        impressionsByDishId.put(99L, 15L);

        Map<Long, DishInsightPlanner.UpsellTarget> upsellTargetsByDishId = Map.of(
                3L, new DishInsightPlanner.UpsellTarget(99L, new BigDecimal("0.8800"))
        );

        List<InsightDTO> insights = planner.plan(intelligence, impressionsByDishId, upsellTargetsByDishId, 10);

        assertEquals(4, insights.size());
        assertEquals(Set.of(1L, 2L, 3L, 4L), insights.stream().map(InsightDTO::dishId).collect(Collectors.toSet()));
        assertEquals(Set.of("PROMOTE", "FIX_CONVERSION", "UPSELL", "REMOVE"), insights.stream().map(InsightDTO::type).collect(Collectors.toSet()));
        assertEquals("Promo Dish: converte bene ma e' poco visibile. Spostalo piu' in alto nel menu.",
                insightByType(insights, "PROMOTE").message());
        assertEquals("Fix Dish: molti utenti cliccano ma non ordinano. Controlla prezzo o descrizione.",
                insightByType(insights, "FIX_CONVERSION").message());

        InsightDTO upsell = insightByType(insights, "UPSELL");
        assertEquals(3L, upsell.dishId());
        assertEquals(99L, upsell.targetDishId());
        assertEquals("Upsell Dish: abbinalo a Target Dish come suggerimento upsell.", upsell.message());

        assertEquals("Remove Dish: ha basse performance. Valuta di rimuoverlo o modificarlo.",
                insightByType(insights, "REMOVE").message());
    }

    @Test
    void shouldReturnFallbackInsightWhenNoRuleMatches() {
        List<DishIntelligenceDTO> intelligence = List.of(
                dish(7L, "Balanced Dish", "0.75", "0.08", "0.12", "0.04", "MEDIUM")
        );

        List<InsightDTO> insights = planner.plan(
                intelligence,
                Map.of(7L, 30L),
                Map.of(),
                5
        );

        assertEquals(1, insights.size());
        assertEquals("PROMOTE", insights.get(0).type());
        assertEquals(7L, insights.get(0).dishId());
        assertEquals("Balanced Dish: ha il potenziale migliore nel menu. Dagli piu' visibilita'.",
                insights.get(0).message());
    }

    private DishIntelligenceDTO dish(
            Long dishId,
            String name,
            String score,
            String ctr,
            String orderRate,
            String affinityScore,
            String performanceCategory
    ) {
        return DishIntelligenceDTO.builder()
                .dishId(dishId)
                .name(name)
                .score(new BigDecimal(score))
                .rpi(new BigDecimal("0.1000"))
                .ctr(new BigDecimal(ctr))
                .orderRate(new BigDecimal(orderRate))
                .affinityScore(new BigDecimal(affinityScore))
                .explorationBoost(new BigDecimal("0.0500"))
                .performanceCategory(performanceCategory)
                .insights(List.of())
                .build();
    }

    private InsightDTO insightByType(List<InsightDTO> insights, String type) {
        return insights.stream()
                .filter(insight -> type.equals(insight.type()))
                .findFirst()
                .orElseThrow();
    }
}
