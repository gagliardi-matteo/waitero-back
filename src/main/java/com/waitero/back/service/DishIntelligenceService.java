package com.waitero.back.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.analyticsv2.dto.AnalyticsV2DishMetricsDTO;
import com.waitero.analyticsv2.repository.AnalyticsV2MetricsRepository;
import com.waitero.analyticsv2.repository.CoOccurrenceV2Repository;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import com.waitero.back.dto.DishActionPlanDTO;
import com.waitero.back.dto.DishInsightApplyResultDTO;
import com.waitero.back.dto.DishIntelligenceDTO;
import com.waitero.back.dto.DishUpsellPairDTO;
import com.waitero.back.dto.InsightDTO;
import com.waitero.back.entity.Piatto;
import com.waitero.back.repository.DishIntelligenceEngagementRepository;
import com.waitero.back.repository.PiattoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DishIntelligenceService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CATEGORY_HIGH = "HIGH";
    private static final String CATEGORY_MEDIUM = "MEDIUM";
    private static final String CATEGORY_LOW = "LOW";
    private static final String TYPE_PROMOTE = "PROMOTE";
    private static final String TYPE_FIX_CONVERSION = "FIX_CONVERSION";
    private static final String TYPE_UPSELL = "UPSELL";
    private static final String TYPE_REMOVE = "REMOVE";

    private final PiattoRepository piattoRepository;
    private final AnalyticsV2MetricsRepository analyticsV2MetricsRepository;
    private final CoOccurrenceV2Repository coOccurrenceV2Repository;
    private final DishIntelligenceEngagementRepository dishIntelligenceEngagementRepository;
    private final AnalyticsV2TimeRangeResolver analyticsV2TimeRangeResolver;
    private final DishInsightPlanner dishInsightPlanner;

    @Value("${waitero.dish-intelligence.affinity-weight:0.35}")
    private double affinityWeight;

    @Value("${waitero.dish-intelligence.exploration-constant:0.5}")
    private double explorationConstant;

    @Value("${waitero.dish-intelligence.affinity-top-k:3}")
    private int affinityTopK;

    @Value("${waitero.dish-intelligence.promote-limit:3}")
    private int promoteLimit;

    @Value("${waitero.dish-intelligence.demote-limit:3}")
    private int demoteLimit;

    @Value("${waitero.dish-intelligence.remove-limit:3}")
    private int removeLimit;

    @Value("${waitero.dish-intelligence.upsell-pairs-limit:5}")
    private int upsellPairsLimit;

    @Value("${waitero.dish-intelligence.insights-limit:7}")
    private int insightsLimit;

    @Value("${waitero.dish-intelligence.cache-ttl-minutes:3}")
    private long cacheTtlMinutes;

    private final Map<Long, CachedSnapshot> snapshotCache = new ConcurrentHashMap<>();

    // Restituisce la graduatoria dei piatti con score, categoria e segnali utili alla pagina intelligence.
    public List<DishIntelligenceDTO> getDishIntelligence(Long restaurantId) {
        return getSnapshot(restaurantId).intelligence();
    }

    // Trasforma il ranking in un piano operativo: promuovi, correggi, proponi upsell o rimuovi.
    public DishActionPlanDTO getDishActionPlan(Long restaurantId) {
        DishIntelligenceSnapshot snapshot = getSnapshot(restaurantId);
        List<DishIntelligenceDTO> intelligence = snapshot.intelligence();
        if (intelligence.isEmpty()) {
            return DishActionPlanDTO.builder()
                    .promote(List.of())
                    .demote(List.of())
                    .removeCandidates(List.of())
                    .upsellPairs(List.of())
                    .build();
        }

        List<DishIntelligenceDTO> promote = intelligence.stream()
                .filter(dto -> CATEGORY_HIGH.equals(dto.performanceCategory()))
                .limit(normalizeLimit(promoteLimit, 1, 10))
                .toList();
        if (promote.isEmpty()) {
            promote = intelligence.stream()
                    .limit(normalizeLimit(promoteLimit, 1, 10))
                    .toList();
        }

        List<DishIntelligenceDTO> removeCandidates = snapshot.candidates().stream()
                .filter(this::isRemoveCandidate)
                .map(snapshot.dtoByDishId()::get)
                .filter(Objects::nonNull)
                .limit(normalizeLimit(removeLimit, 1, 10))
                .toList();

        Set<Long> removeIds = removeCandidates.stream()
                .map(DishIntelligenceDTO::dishId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<DishIntelligenceDTO> demote = intelligence.stream()
                .filter(dto -> CATEGORY_LOW.equals(dto.performanceCategory()))
                .filter(dto -> !removeIds.contains(dto.dishId()))
                .limit(normalizeLimit(demoteLimit, 1, 10))
                .toList();
        if (demote.isEmpty()) {
            demote = intelligence.stream()
                    .filter(dto -> CATEGORY_LOW.equals(dto.performanceCategory()))
                    .limit(normalizeLimit(demoteLimit, 1, 10))
                    .toList();
        }

        List<DishUpsellPairDTO> upsellPairs = buildUpsellPairs(snapshot);

        return DishActionPlanDTO.builder()
                .promote(promote)
                .demote(demote)
                .removeCandidates(removeCandidates)
                .upsellPairs(upsellPairs)
                .build();
    }

    // Traduce i dati di performance in suggerimenti leggibili dal ristoratore.
    public List<InsightDTO> getDishInsights(Long restaurantId) {
        DishIntelligenceSnapshot snapshot = getSnapshot(restaurantId);
        if (snapshot.intelligence().isEmpty()) {
            return List.of();
        }

        DishIntelligenceScoreCalculator calculator = new DishIntelligenceScoreCalculator(affinityWeight, explorationConstant);
        Map<Long, Long> impressionsByDishId = snapshot.candidates().stream()
                .collect(Collectors.toMap(
                        DishCandidate::dishId,
                        DishCandidate::impressions,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        Map<Long, DishInsightPlanner.UpsellTarget> upsellTargetsByDishId = snapshot.candidates().stream()
                .map(candidate -> toUpsellTarget(candidate, calculator))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        UpsellTargetCandidate::dishId,
                        candidate -> new DishInsightPlanner.UpsellTarget(candidate.targetDishId(), scale(candidate.pairScore(), 4)),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        return dishInsightPlanner.plan(
                snapshot.intelligence(),
                impressionsByDishId,
                upsellTargetsByDishId,
                normalizeLimit(insightsLimit, 1, 10)
        );
    }

    @Transactional
    // Applica al catalogo i suggerimenti calcolati dal motore di intelligence.
    public DishInsightApplyResultDTO applyDishInsights(Long restaurantId) {
        if (restaurantId == null) {
            return emptyApplyResult();
        }

        List<InsightDTO> insights = getDishInsights(restaurantId);
        if (insights.isEmpty()) {
            return emptyApplyResult();
        }

        List<Piatto> dishes = piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId).stream()
                .sorted(Comparator.comparing(Piatto::getId))
                .toList();
        if (dishes.isEmpty()) {
            return emptyApplyResult();
        }

        Map<Long, Piatto> dishById = dishes.stream()
                .filter(dish -> dish.getId() != null)
                .collect(Collectors.toMap(Piatto::getId, dish -> dish, (left, right) -> left, LinkedHashMap::new));
        Map<Long, String> primaryInsightTypeByDishId = insights.stream()
                .filter(insight -> insight.dishId() != null && insight.type() != null)
                .collect(Collectors.toMap(InsightDTO::dishId, InsightDTO::type, (left, right) -> left, LinkedHashMap::new));

        LinkedHashSet<Long> promotedDishIds = new LinkedHashSet<>();
        LinkedHashSet<Long> deprioritizedDishIds = new LinkedHashSet<>();
        LinkedHashSet<Long> removedDishIds = new LinkedHashSet<>();
        LinkedHashSet<Long> upsellActivatedDishIds = new LinkedHashSet<>();
        LinkedHashSet<Long> changedDishIds = new LinkedHashSet<>();

        for (InsightDTO insight : insights) {
            applyInsight(
                    restaurantId,
                    insight,
                    dishById,
                    primaryInsightTypeByDishId,
                    changedDishIds,
                    promotedDishIds,
                    deprioritizedDishIds,
                    removedDishIds,
                    upsellActivatedDishIds
            );
        }

        if (!changedDishIds.isEmpty()) {
            List<Piatto> changedDishes = changedDishIds.stream()
                    .map(dishById::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Piatto::getId))
                    .toList();
            piattoRepository.saveAll(changedDishes);
            invalidateSnapshot(restaurantId);
        }

        return DishInsightApplyResultDTO.builder()
                .appliedCount(changedDishIds.size())
                .promotedCount(promotedDishIds.size())
                .deprioritizedCount(deprioritizedDishIds.size())
                .removedCount(removedDishIds.size())
                .upsellActivatedCount(upsellActivatedDishIds.size())
                .updatedDishIds(changedDishIds.stream().sorted().toList())
                .build();
    }

    @Transactional
    // Applica un singolo suggerimento al piatto corrispondente.
    public boolean applyInsight(Long restaurantId, InsightDTO insight) {
        if (restaurantId == null || insight == null || insight.type() == null) {
            return false;
        }

        List<Piatto> dishes = piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId).stream()
                .sorted(Comparator.comparing(Piatto::getId))
                .toList();
        if (dishes.isEmpty()) {
            return false;
        }

        Map<Long, Piatto> dishById = dishes.stream()
                .filter(dish -> dish.getId() != null)
                .collect(Collectors.toMap(Piatto::getId, dish -> dish, (left, right) -> left, LinkedHashMap::new));
        LinkedHashSet<Long> changedDishIds = new LinkedHashSet<>();
        LinkedHashSet<Long> promotedDishIds = new LinkedHashSet<>();
        LinkedHashSet<Long> deprioritizedDishIds = new LinkedHashSet<>();
        LinkedHashSet<Long> removedDishIds = new LinkedHashSet<>();
        LinkedHashSet<Long> upsellActivatedDishIds = new LinkedHashSet<>();

        applyInsight(
                restaurantId,
                insight,
                dishById,
                Map.of(),
                changedDishIds,
                promotedDishIds,
                deprioritizedDishIds,
                removedDishIds,
                upsellActivatedDishIds
        );

        if (changedDishIds.isEmpty()) {
            return false;
        }

        List<Piatto> changedDishes = changedDishIds.stream()
                .map(dishById::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Piatto::getId))
                .toList();
        piattoRepository.saveAll(changedDishes);
        invalidateSnapshot(restaurantId);
        return true;
    }

    // Legge lo snapshot in cache oppure lo ricostruisce se e' scaduto.
    private DishIntelligenceSnapshot getSnapshot(Long restaurantId) {
        if (restaurantId == null) {
            return DishIntelligenceSnapshot.empty();
        }

        Duration ttl = resolveCacheTtl();
        Instant now = Instant.now();
        CachedSnapshot cachedSnapshot = snapshotCache.get(restaurantId);
        if (ttl != null && cachedSnapshot != null && now.isBefore(cachedSnapshot.expiresAt())) {
            return cachedSnapshot.snapshot();
        }

        DishIntelligenceSnapshot snapshot = buildSnapshot(restaurantId);
        if (ttl != null) {
            snapshotCache.put(restaurantId, new CachedSnapshot(snapshot, now.plus(ttl)));
        }
        return snapshot;
    }

    // Invalida la cache locale quando cambia il catalogo o vengono applicati suggerimenti.
    private void invalidateSnapshot(Long restaurantId) {
        if (restaurantId != null) {
            snapshotCache.remove(restaurantId);
        }
    }

    // Ricostruisce la fotografia completa del locale per alimentare ranking e azioni.
    private DishIntelligenceSnapshot buildSnapshot(Long restaurantId) {
        if (restaurantId == null) {
            return DishIntelligenceSnapshot.empty();
        }

        List<Piatto> dishes = piattoRepository.findAllByRistoratoreIdWithCanonical(restaurantId).stream()
                .filter(dish -> !Boolean.FALSE.equals(dish.getDisponibile()))
                .sorted(Comparator.comparing(Piatto::getId))
                .toList();
        if (dishes.isEmpty()) {
            return DishIntelligenceSnapshot.empty();
        }

        AnalyticsV2TimeRange timeRange = analyticsV2TimeRangeResolver.resolve(null, null);
        Map<Long, AnalyticsV2DishMetricsDTO> orderMetricsByDishId = analyticsV2MetricsRepository.fetchDishMetrics(restaurantId, false, timeRange)
                .stream()
                .collect(Collectors.toMap(AnalyticsV2DishMetricsDTO::dishId, row -> row, (left, right) -> left, LinkedHashMap::new));
        Map<Long, DishIntelligenceEngagementRepository.DishEngagementRow> engagementByDishId = dishIntelligenceEngagementRepository.fetchDishEngagement(restaurantId, timeRange)
                .stream()
                .collect(Collectors.toMap(DishIntelligenceEngagementRepository.DishEngagementRow::dishId, row -> row, (left, right) -> left, LinkedHashMap::new));

        List<Long> dishIds = dishes.stream()
                .map(Piatto::getId)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        Map<Long, List<CoOccurrenceV2Repository.BaseRelatedDishRow>> affinityRowsByDishId = coOccurrenceV2Repository
                .fetchTopRelatedDishesForBaseDishes(
                        restaurantId,
                        dishIds,
                        true,
                        normalizeLimit(affinityTopK, 1, 10),
                        timeRange
                ).stream()
                .collect(Collectors.groupingBy(
                        CoOccurrenceV2Repository.BaseRelatedDishRow::baseDishId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        long totalImpressions = engagementByDishId.values().stream()
                .mapToLong(DishIntelligenceEngagementRepository.DishEngagementRow::impressions)
                .sum();
        DishIntelligenceScoreCalculator calculator = new DishIntelligenceScoreCalculator(affinityWeight, explorationConstant);

        List<DishCandidate> candidates = dishes.stream()
                .map(dish -> buildCandidate(
                        dish,
                        orderMetricsByDishId.get(dish.getId()),
                        engagementByDishId.get(dish.getId()),
                        affinityRowsByDishId.getOrDefault(dish.getId(), List.of()),
                        totalImpressions,
                        calculator
                ))
                .sorted(DishCandidate.SORT_BY_SCORE)
                .toList();

        Thresholds thresholds = Thresholds.from(candidates);
        List<DishIntelligenceDTO> intelligence = new ArrayList<>(candidates.size());
        Map<Long, DishIntelligenceDTO> dtoByDishId = new LinkedHashMap<>();
        for (DishCandidate candidate : candidates) {
            String performanceCategory = resolvePerformanceCategory(candidate, thresholds);
            List<String> insights = buildInsights(candidate, thresholds);
            DishIntelligenceDTO dto = DishIntelligenceDTO.builder()
                    .dishId(candidate.dishId())
                    .name(candidate.name())
                    .score(scale(candidate.score(), 6))
                    .rpi(scale(candidate.rpi(), 4))
                    .ctr(scale(candidate.ctr(), 4))
                    .orderRate(scale(candidate.orderRate(), 4))
                    .affinityScore(scale(candidate.affinityScore(), 4))
                    .explorationBoost(scale(candidate.explorationBoost(), 4))
                    .performanceCategory(performanceCategory)
                    .insights(Collections.unmodifiableList(insights))
                    .build();
            intelligence.add(dto);
            dtoByDishId.put(dto.dishId(), dto);
        }

        return new DishIntelligenceSnapshot(
                Collections.unmodifiableList(intelligence),
                Collections.unmodifiableList(candidates),
                Collections.unmodifiableMap(dtoByDishId)
        );
    }

    // Esegue la modifica concreta sul piatto in base al tipo di insight calcolato.
    private void applyInsight(
            Long restaurantId,
            InsightDTO insight,
            Map<Long, Piatto> dishById,
            Map<Long, String> primaryInsightTypeByDishId,
            LinkedHashSet<Long> changedDishIds,
            LinkedHashSet<Long> promotedDishIds,
            LinkedHashSet<Long> deprioritizedDishIds,
            LinkedHashSet<Long> removedDishIds,
            LinkedHashSet<Long> upsellActivatedDishIds
    ) {
        if (insight == null || insight.type() == null) {
            return;
        }

        int changeCountBefore = changedDishIds.size();
        switch (insight.type()) {
            case TYPE_PROMOTE -> applyPromotion(
                    dishById.get(insight.dishId()),
                    changedDishIds,
                    promotedDishIds
            );
            case TYPE_FIX_CONVERSION -> applyDeprioritization(
                    dishById.get(insight.dishId()),
                    changedDishIds,
                    deprioritizedDishIds
            );
            case TYPE_REMOVE -> applyRemoval(
                    dishById.get(insight.dishId()),
                    changedDishIds,
                    removedDishIds
            );
            case TYPE_UPSELL -> applyUpsellTargetPromotion(
                    dishById.get(insight.targetDishId()),
                    primaryInsightTypeByDishId.get(insight.targetDishId()),
                    changedDishIds,
                    upsellActivatedDishIds
            );
            default -> {
            }
        }

        if (changedDishIds.size() > changeCountBefore) {
            logAppliedInsight(restaurantId, insight);
        }
    }

    // Combina performance, margine e affinità con altri piatti in una singola entita di scoring.
    private DishCandidate buildCandidate(
            Piatto dish,
            AnalyticsV2DishMetricsDTO orderMetrics,
            DishIntelligenceEngagementRepository.DishEngagementRow engagement,
            List<CoOccurrenceV2Repository.BaseRelatedDishRow> affinityRows,
            long totalImpressions,
            DishIntelligenceScoreCalculator calculator
    ) {
        long impressions = engagement == null ? 0L : engagement.impressions();
        long clicks = engagement == null ? 0L : engagement.clicks();
        long views = engagement == null ? 0L : engagement.views();
        long orderCount = orderMetrics == null ? 0L : orderMetrics.orderCount();
        BigDecimal revenue = orderMetrics == null ? BigDecimal.ZERO : orderMetrics.revenuePerDish();
        double marginWeight = resolveMarginWeight(dish);
        double rpi = calculator.computeRpi(revenue, impressions);
        double ctr = calculator.computeCtr(clicks, impressions);
        double orderRate = calculator.computeOrderRate(orderCount, views, clicks);
        double affinityScore = averageAffinityScore(affinityRows, calculator);
        double explorationBoost = calculator.computeExplorationBoost(totalImpressions, impressions);
        double score = calculator.computeScore(revenue, impressions, marginWeight, affinityScore, totalImpressions);

        return new DishCandidate(
                dish.getId(),
                dish.getNome(),
                impressions,
                clicks,
                views,
                orderCount,
                revenue == null ? BigDecimal.ZERO : revenue,
                rpi,
                ctr,
                orderRate,
                affinityScore,
                explorationBoost,
                score,
                affinityRows
        );
    }

    private double resolveMarginWeight(Piatto dish) {
        if (dish == null || dish.getPrezzo() == null || dish.getPrezzo().compareTo(BigDecimal.ZERO) <= 0) {
            return 1.0d;
        }
        return 1.0d;
    }

    private double averageAffinityScore(
            List<CoOccurrenceV2Repository.BaseRelatedDishRow> affinityRows,
            DishIntelligenceScoreCalculator calculator
    ) {
        if (affinityRows == null || affinityRows.isEmpty()) {
            return 0.0d;
        }

        return affinityRows.stream()
                .limit(normalizeLimit(affinityTopK, 1, 10))
                .mapToDouble(row -> calculator.combineAffinity(row.lift(), row.affinity()))
                .average()
                .orElse(0.0d);
    }

    private String resolvePerformanceCategory(DishCandidate candidate, Thresholds thresholds) {
        if (Double.compare(thresholds.scoreHighThreshold(), thresholds.scoreLowThreshold()) == 0) {
            return CATEGORY_MEDIUM;
        }
        if (candidate.score() >= thresholds.scoreHighThreshold()) {
            return CATEGORY_HIGH;
        }
        if (candidate.score() <= thresholds.scoreLowThreshold()) {
            return CATEGORY_LOW;
        }
        return CATEGORY_MEDIUM;
    }

    private List<String> buildInsights(DishCandidate candidate, Thresholds thresholds) {
        List<String> insights = new ArrayList<>();
        if (candidate.ctr() >= thresholds.ctrHighThreshold()
                && candidate.orderRate() <= thresholds.orderRateLowThreshold()
                && candidate.impressions() > 0
                && candidate.clicks() > 0) {
            insights.add("High interest but low conversion (price/description issue)");
        }
        if (candidate.orderRate() >= thresholds.orderRateHighThreshold()
                && candidate.impressions() <= thresholds.impressionLowThreshold()
                && candidate.orderCount() > 0) {
            insights.add("Strong performer but underexposed");
        }
        if (candidate.affinityScore() >= thresholds.affinityHighThreshold() && candidate.affinityScore() > 0.0d) {
            insights.add("Strong upsell candidate");
        }
        if (thresholds.scoreLowThreshold() < thresholds.scoreHighThreshold()
                && candidate.score() <= thresholds.scoreLowThreshold()) {
            insights.add("Candidate for removal or improvement");
        }
        if (insights.isEmpty()) {
            if (candidate.impressions() == 0 && candidate.orderCount() == 0) {
                insights.add("Limited data, keep exploring before deciding");
            } else {
                insights.add("Performance stable");
            }
        }
        return insights;
    }

    private boolean isRemoveCandidate(DishCandidate candidate) {
        return candidate.score() <= 0.0d
                || (candidate.orderCount() == 0L && candidate.impressions() > 0L && candidate.affinityScore() < 0.15d);
    }

    // Estrae le coppie di piatti con la maggiore compatibilita per suggerimenti upsell.
    private List<DishUpsellPairDTO> buildUpsellPairs(DishIntelligenceSnapshot snapshot) {
        if (snapshot.candidates().isEmpty()) {
            return List.of();
        }

        DishIntelligenceScoreCalculator calculator = new DishIntelligenceScoreCalculator(affinityWeight, explorationConstant);
        return snapshot.candidates().stream()
                .flatMap(candidate -> candidate.affinityRows().stream()
                        .map(row -> DishUpsellPairCandidate.builder()
                                .baseDishId(candidate.dishId())
                                .baseDishName(candidate.name())
                                .suggestedDishId(row.dishId())
                                .suggestedDishName(row.dishName())
                                .pairScore(calculator.combineAffinity(row.lift(), row.affinity()))
                                .build()))
                .sorted(DishUpsellPairCandidate.SORT_BY_SCORE)
                .limit(normalizeLimit(upsellPairsLimit, 1, 20))
                .map(candidate -> DishUpsellPairDTO.builder()
                        .baseDishId(candidate.baseDishId())
                        .baseDishName(candidate.baseDishName())
                        .suggestedDishId(candidate.suggestedDishId())
                        .suggestedDishName(candidate.suggestedDishName())
                        .affinityScore(scale(candidate.pairScore(), 4))
                        .build())
                .toList();
    }

    private UpsellTargetCandidate toUpsellTarget(
            DishCandidate candidate,
            DishIntelligenceScoreCalculator calculator
    ) {
        if (candidate == null || candidate.affinityRows() == null || candidate.affinityRows().isEmpty()) {
            return null;
        }

        return candidate.affinityRows().stream()
                .map(row -> new UpsellTargetCandidate(
                        candidate.dishId(),
                        row.dishId(),
                        calculator.combineAffinity(row.lift(), row.affinity())
                ))
                .sorted(UpsellTargetCandidate.SORT_BY_SCORE)
                .findFirst()
                .orElse(null);
    }

    // Marca un piatto come prioritario nella visibilita del menu.
    private void applyPromotion(
            Piatto dish,
            LinkedHashSet<Long> changedDishIds,
            LinkedHashSet<Long> promotedDishIds
    ) {
        if (dish == null || dish.getId() == null) {
            return;
        }
        boolean changed = false;
        if (!Boolean.TRUE.equals(dish.getConsigliato())) {
            dish.setConsigliato(true);
            changed = true;
        }
        if (changed) {
            changedDishIds.add(dish.getId());
            promotedDishIds.add(dish.getId());
        }
    }

    // Riduce la priorita di un piatto che sta performando peggio del resto del catalogo.
    private void applyDeprioritization(
            Piatto dish,
            LinkedHashSet<Long> changedDishIds,
            LinkedHashSet<Long> deprioritizedDishIds
    ) {
        if (dish == null || dish.getId() == null || !Boolean.TRUE.equals(dish.getConsigliato())) {
            return;
        }
        dish.setConsigliato(false);
        changedDishIds.add(dish.getId());
        deprioritizedDishIds.add(dish.getId());
    }

    // Nasconde un piatto che ha scarso valore commerciale o quasi nessun segnale utile.
    private void applyRemoval(
            Piatto dish,
            LinkedHashSet<Long> changedDishIds,
            LinkedHashSet<Long> removedDishIds
    ) {
        if (dish == null || dish.getId() == null) {
            return;
        }
        boolean changed = false;
        if (!Boolean.FALSE.equals(dish.getDisponibile())) {
            dish.setDisponibile(false);
            changed = true;
        }
        if (Boolean.TRUE.equals(dish.getConsigliato())) {
            dish.setConsigliato(false);
            changed = true;
        }
        if (changed) {
            changedDishIds.add(dish.getId());
            removedDishIds.add(dish.getId());
        }
    }

    // Evidenzia il piatto come candidato per gli abbinamenti suggeriti.
    private void applyUpsellTargetPromotion(
            Piatto targetDish,
            String targetPrimaryInsightType,
            LinkedHashSet<Long> changedDishIds,
            LinkedHashSet<Long> upsellActivatedDishIds
    ) {
        if (targetDish == null || targetDish.getId() == null) {
            return;
        }
        if ("REMOVE".equals(targetPrimaryInsightType) || "FIX_CONVERSION".equals(targetPrimaryInsightType)) {
            return;
        }
        boolean changed = false;
        if (!Boolean.TRUE.equals(targetDish.getConsigliato())) {
            targetDish.setConsigliato(true);
            changed = true;
        }
        if (changed) {
            changedDishIds.add(targetDish.getId());
            upsellActivatedDishIds.add(targetDish.getId());
        }
    }

    private DishInsightApplyResultDTO emptyApplyResult() {
        return DishInsightApplyResultDTO.builder()
                .appliedCount(0)
                .promotedCount(0)
                .deprioritizedCount(0)
                .removedCount(0)
                .upsellActivatedCount(0)
                .updatedDishIds(List.of())
                .build();
    }

    private void logAppliedInsight(Long restaurantId, InsightDTO insight) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("restaurantId", restaurantId);
        payload.put("action", "APPLY_INSIGHT");
        payload.put("type", insight.type());
        payload.put("dishId", insight.dishId());
        if (insight.targetDishId() != null) {
            payload.put("targetDishId", insight.targetDishId());
        }
        log.info(toJson(payload));
    }

    private BigDecimal scale(double value, int scale) {
        double safeValue = Double.isFinite(value) ? value : 0.0d;
        return BigDecimal.valueOf(safeValue).setScale(scale, RoundingMode.HALF_UP);
    }

    private int normalizeLimit(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private Duration resolveCacheTtl() {
        if (cacheTtlMinutes <= 0L) {
            return null;
        }
        return Duration.ofMinutes(cacheTtlMinutes);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return payload.toString();
        }
    }

    private static double percentile(List<Double> values, double fraction) {
        if (values == null || values.isEmpty()) {
            return 0.0d;
        }
        List<Double> sorted = values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        int index = (int) Math.ceil(fraction * sorted.size()) - 1;
        int safeIndex = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(safeIndex);
    }

    private record DishCandidate(
            Long dishId,
            String name,
            long impressions,
            long clicks,
            long views,
            long orderCount,
            BigDecimal revenue,
            double rpi,
            double ctr,
            double orderRate,
            double affinityScore,
            double explorationBoost,
            double score,
            List<CoOccurrenceV2Repository.BaseRelatedDishRow> affinityRows
    ) {
        private static final Comparator<DishCandidate> SORT_BY_SCORE = Comparator
                .comparing(DishCandidate::score, Comparator.reverseOrder())
                .thenComparing(DishCandidate::orderCount, Comparator.reverseOrder())
                .thenComparing(DishCandidate::impressions, Comparator.reverseOrder())
                .thenComparing(DishCandidate::dishId);
    }

    private record DishIntelligenceSnapshot(
            List<DishIntelligenceDTO> intelligence,
            List<DishCandidate> candidates,
            Map<Long, DishIntelligenceDTO> dtoByDishId
    ) {
        private static DishIntelligenceSnapshot empty() {
            return new DishIntelligenceSnapshot(List.of(), List.of(), Map.of());
        }
    }

    private record CachedSnapshot(DishIntelligenceSnapshot snapshot, Instant expiresAt) {
    }

    private record Thresholds(
            double scoreLowThreshold,
            double scoreHighThreshold,
            double ctrHighThreshold,
            double orderRateLowThreshold,
            double orderRateHighThreshold,
            double impressionLowThreshold,
            double affinityHighThreshold
    ) {
        private static Thresholds from(List<DishCandidate> candidates) {
            return new Thresholds(
                    percentile(candidates.stream().map(DishCandidate::score).toList(), 0.25d),
                    percentile(candidates.stream().map(DishCandidate::score).toList(), 0.75d),
                    percentile(candidates.stream().map(DishCandidate::ctr).toList(), 0.75d),
                    percentile(candidates.stream().map(DishCandidate::orderRate).toList(), 0.25d),
                    percentile(candidates.stream().map(DishCandidate::orderRate).toList(), 0.75d),
                    percentile(candidates.stream().map(candidate -> (double) candidate.impressions()).toList(), 0.25d),
                    percentile(candidates.stream().map(DishCandidate::affinityScore).toList(), 0.75d)
            );
        }
    }

    @lombok.Builder
    private record DishUpsellPairCandidate(
            Long baseDishId,
            String baseDishName,
            Long suggestedDishId,
            String suggestedDishName,
            double pairScore
    ) {
        private static final Comparator<DishUpsellPairCandidate> SORT_BY_SCORE = Comparator
                .comparing(DishUpsellPairCandidate::pairScore, Comparator.reverseOrder())
                .thenComparing(DishUpsellPairCandidate::baseDishId)
                .thenComparing(DishUpsellPairCandidate::suggestedDishId);
    }

    private record UpsellTargetCandidate(
            Long dishId,
            Long targetDishId,
            double pairScore
    ) {
        private static final Comparator<UpsellTargetCandidate> SORT_BY_SCORE = Comparator
                .comparing(UpsellTargetCandidate::pairScore, Comparator.reverseOrder())
                .thenComparing(UpsellTargetCandidate::dishId)
                .thenComparing(UpsellTargetCandidate::targetDishId);
    }
}
