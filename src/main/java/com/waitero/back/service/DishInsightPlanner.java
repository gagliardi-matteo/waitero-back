package com.waitero.back.service;

import com.waitero.back.dto.DishIntelligenceDTO;
import com.waitero.back.dto.InsightDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
class DishInsightPlanner {

    private static final String TYPE_PROMOTE = "PROMOTE";
    private static final String TYPE_FIX_CONVERSION = "FIX_CONVERSION";
    private static final String TYPE_UPSELL = "UPSELL";
    private static final String TYPE_REMOVE = "REMOVE";

    List<InsightDTO> plan(
            List<DishIntelligenceDTO> intelligence,
            Map<Long, Long> impressionsByDishId,
            Map<Long, UpsellTarget> upsellTargetsByDishId,
            int limit
    ) {
        if (intelligence == null || intelligence.isEmpty()) {
            return List.of();
        }

        Thresholds thresholds = Thresholds.from(intelligence, impressionsByDishId);
        Map<Long, String> dishNamesById = intelligence.stream()
                .filter(dish -> dish.dishId() != null)
                .collect(Collectors.toMap(
                        DishIntelligenceDTO::dishId,
                        dish -> safeDishName(dish.name(), dish.dishId()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        List<InsightCandidate> generated = new ArrayList<>();

        for (DishIntelligenceDTO dish : intelligence) {
            Long dishId = dish.dishId();
            if (dishId == null) {
                continue;
            }

            long impressions = impressionsByDishId.getOrDefault(dishId, 0L);
            double score = safeDecimal(dish.score());
            double ctr = safeDecimal(dish.ctr());
            double orderRate = safeDecimal(dish.orderRate());
            double affinityScore = safeDecimal(dish.affinityScore());

            if (orderRate >= thresholds.orderRateHighThreshold()
                    && impressions > 0L
                    && impressions <= thresholds.impressionLowThreshold()) {
                generated.add(new InsightCandidate(
                        TYPE_PROMOTE,
                        dishId,
                        null,
                        promoteMessage(dish),
                        score + orderRate,
                        typePriority(TYPE_PROMOTE)
                ));
            }

            if (ctr >= thresholds.ctrHighThreshold()
                    && orderRate <= thresholds.orderRateLowThreshold()
                    && ctr > orderRate
                    && impressions > 0L) {
                generated.add(new InsightCandidate(
                        TYPE_FIX_CONVERSION,
                        dishId,
                        null,
                        fixConversionMessage(dish),
                        score + (ctr - orderRate),
                        typePriority(TYPE_FIX_CONVERSION)
                ));
            }

            UpsellTarget upsellTarget = upsellTargetsByDishId.get(dishId);
            if (upsellTarget != null
                    && upsellTarget.targetDishId() != null
                    && affinityScore >= thresholds.affinityHighThreshold()) {
                generated.add(new InsightCandidate(
                        TYPE_UPSELL,
                        dishId,
                        upsellTarget.targetDishId(),
                        upsellMessage(dish, upsellTarget, dishNamesById),
                        Math.max(affinityScore, safeDecimal(upsellTarget.affinityScore())),
                        typePriority(TYPE_UPSELL)
                ));
            }

            if ("LOW".equalsIgnoreCase(dish.performanceCategory())
                    && impressions >= thresholds.impressionHighThreshold()) {
                generated.add(new InsightCandidate(
                        TYPE_REMOVE,
                        dishId,
                        null,
                        removeMessage(dish),
                        removeImpact(score, impressions, thresholds.impressionHighThreshold()),
                        typePriority(TYPE_REMOVE)
                ));
            }
        }

        int safeLimit = normalizeLimit(limit);
        Map<Long, InsightDTO> uniqueByDishId = new LinkedHashMap<>();
        generated.stream()
                .sorted(InsightCandidate.SORT_BY_IMPACT)
                .forEach(candidate -> uniqueByDishId.computeIfAbsent(candidate.dishId(), ignored -> candidate.toDto()));

        List<InsightDTO> result = uniqueByDishId.values().stream()
                .limit(safeLimit)
                .toList();
        if (!result.isEmpty()) {
            return result;
        }

        InsightDTO fallback = buildFallback(intelligence, upsellTargetsByDishId, dishNamesById);
        return fallback == null ? List.of() : List.of(fallback);
    }

    private InsightDTO buildFallback(
            List<DishIntelligenceDTO> intelligence,
            Map<Long, UpsellTarget> upsellTargetsByDishId,
            Map<Long, String> dishNamesById
    ) {
        DishIntelligenceDTO bestUpsellDish = intelligence.stream()
                .filter(dish -> dish.dishId() != null)
                .filter(dish -> upsellTargetsByDishId.containsKey(dish.dishId()))
                .max(Comparator
                        .comparing((DishIntelligenceDTO dish) -> safeDecimal(dish.affinityScore()))
                        .thenComparing(DishIntelligenceDTO::dishId))
                .orElse(null);
        if (bestUpsellDish != null) {
            UpsellTarget target = upsellTargetsByDishId.get(bestUpsellDish.dishId());
            return InsightDTO.builder()
                    .type(TYPE_UPSELL)
                    .dishId(bestUpsellDish.dishId())
                    .targetDishId(target.targetDishId())
                    .message(upsellMessage(bestUpsellDish, target, dishNamesById))
                    .build();
        }

        DishIntelligenceDTO bestDish = intelligence.stream()
                .filter(dish -> dish.dishId() != null)
                .max(Comparator
                        .comparing((DishIntelligenceDTO dish) -> safeDecimal(dish.score()))
                        .thenComparing(DishIntelligenceDTO::dishId))
                .orElse(null);
        if (bestDish == null) {
            return null;
        }
        return InsightDTO.builder()
                .type(TYPE_PROMOTE)
                .dishId(bestDish.dishId())
                .targetDishId(null)
                .message(fallbackPromoteMessage(bestDish))
                .build();
    }

    private String promoteMessage(DishIntelligenceDTO dish) {
        return safeDishName(dish.name(), dish.dishId())
                + ": converte bene ma e' poco visibile. Spostalo piu' in alto nel menu.";
    }

    private String fixConversionMessage(DishIntelligenceDTO dish) {
        return safeDishName(dish.name(), dish.dishId())
                + ": molti utenti cliccano ma non ordinano. Controlla prezzo o descrizione.";
    }

    private String upsellMessage(
            DishIntelligenceDTO dish,
            UpsellTarget upsellTarget,
            Map<Long, String> dishNamesById
    ) {
        String dishName = safeDishName(dish.name(), dish.dishId());
        String targetName = dishNamesById.get(upsellTarget.targetDishId());
        if (targetName == null || targetName.isBlank()) {
            return dishName + ": spesso ordinato insieme ad altri piatti. Usalo come suggerimento upsell.";
        }
        return dishName + ": abbinalo a " + targetName + " come suggerimento upsell.";
    }

    private String removeMessage(DishIntelligenceDTO dish) {
        return safeDishName(dish.name(), dish.dishId())
                + ": ha basse performance. Valuta di rimuoverlo o modificarlo.";
    }

    private String fallbackPromoteMessage(DishIntelligenceDTO dish) {
        return safeDishName(dish.name(), dish.dishId())
                + ": ha il potenziale migliore nel menu. Dagli piu' visibilita'.";
    }

    private String safeDishName(String name, Long dishId) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return dishId == null ? "Questo piatto" : "Piatto #" + dishId;
    }

    private double removeImpact(double score, long impressions, long impressionHighThreshold) {
        double impressionFactor = impressionHighThreshold <= 0L ? impressions : impressions / (double) impressionHighThreshold;
        return Math.max(0.0d, 1.0d - score) + impressionFactor;
    }

    private static double safeDecimal(BigDecimal value) {
        return value == null ? 0.0d : value.doubleValue();
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 5;
        }
        return Math.max(1, Math.min(limit, 10));
    }

    private static long percentileLong(List<Long> values, double fraction) {
        List<Long> sorted = values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.round(fraction * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double percentileDecimal(List<BigDecimal> values, double fraction) {
        List<BigDecimal> sorted = values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        int index = (int) Math.round(fraction * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))).doubleValue();
    }

    private static int typePriority(String type) {
        return switch (type) {
            case TYPE_PROMOTE -> 0;
            case TYPE_FIX_CONVERSION -> 1;
            case TYPE_UPSELL -> 2;
            case TYPE_REMOVE -> 3;
            default -> 9;
        };
    }

    record UpsellTarget(Long targetDishId, BigDecimal affinityScore) {
    }

    private record Thresholds(
            double ctrHighThreshold,
            double orderRateLowThreshold,
            double orderRateHighThreshold,
            double affinityHighThreshold,
            long impressionLowThreshold,
            long impressionHighThreshold
    ) {
        private static Thresholds from(List<DishIntelligenceDTO> intelligence, Map<Long, Long> impressionsByDishId) {
            return new Thresholds(
                    Math.max(percentileDecimal(intelligence.stream().map(DishIntelligenceDTO::ctr).toList(), 0.75d), 0.12d),
                    Math.max(percentileDecimal(intelligence.stream().map(DishIntelligenceDTO::orderRate).toList(), 0.25d), 0.08d),
                    Math.max(percentileDecimal(intelligence.stream().map(DishIntelligenceDTO::orderRate).toList(), 0.75d), 0.15d),
                    Math.max(percentileDecimal(intelligence.stream().map(DishIntelligenceDTO::affinityScore).toList(), 0.75d), 0.15d),
                    Math.max(10L, percentileLong(new ArrayList<>(impressionsByDishId.values()), 0.25d)),
                    Math.max(20L, percentileLong(new ArrayList<>(impressionsByDishId.values()), 0.75d))
            );
        }
    }

    private record InsightCandidate(
            String type,
            Long dishId,
            Long targetDishId,
            String message,
            double impact,
            int priority
    ) {
        private static final Comparator<InsightCandidate> SORT_BY_IMPACT = Comparator
                .comparing(InsightCandidate::impact, Comparator.reverseOrder())
                .thenComparing(InsightCandidate::priority)
                .thenComparing(InsightCandidate::dishId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(InsightCandidate::targetDishId, Comparator.nullsLast(Comparator.naturalOrder()));

        private InsightDTO toDto() {
            return InsightDTO.builder()
                    .type(type)
                    .dishId(dishId)
                    .targetDishId(targetDishId)
                    .message(message)
                    .build();
        }
    }
}
