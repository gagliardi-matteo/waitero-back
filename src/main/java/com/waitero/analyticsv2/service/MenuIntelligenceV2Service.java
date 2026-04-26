package com.waitero.analyticsv2.service;

import com.waitero.analyticsv2.dto.AnalyticsV2DishMetricsDTO;
import com.waitero.analyticsv2.dto.MenuRankedDishV2DTO;
import com.waitero.analyticsv2.repository.AnalyticsV2MetricsRepository;
import com.waitero.analyticsv2.repository.CoOccurrenceV2Repository;
import com.waitero.analyticsv2.support.AnalyticsV2JsonLogger;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.DecimalScaleV2;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MenuIntelligenceV2Service {

    private final AnalyticsV2MetricsRepository metricsRepository;
    private final CoOccurrenceV2Repository coOccurrenceRepository;
    private final AnalyticsV2JsonLogger shadowLogger;

    public List<MenuRankedDishV2DTO> getRankedMenu(Long restaurantId, int limit, AnalyticsV2TimeRange timeRange) {
        if (restaurantId == null) {
            return List.of();
        }

        List<AnalyticsV2DishMetricsDTO> dishes = metricsRepository.fetchDishMetrics(restaurantId, true, timeRange);
        if (dishes.isEmpty()) {
            shadowLogger.logRanking(restaurantId, timeRange, List.of());
            return List.of();
        }

        Map<Long, BigDecimal> coOccurrenceBoostByDish = coOccurrenceRepository.fetchComplementaryBoostByDish(restaurantId, timeRange);
        BigDecimal totalRevenue = dishes.stream()
                .map(AnalyticsV2DishMetricsDTO::revenuePerDish)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<MenuRankedDishV2DTO> ranked = dishes.stream()
                .map(dish -> {
                    BigDecimal revenueShare = DecimalScaleV2.ratio(dish.revenuePerDish(), totalRevenue);
                    BigDecimal frequency = DecimalScaleV2.scaled(dish.orderFrequencyPerDish(), 4);
                    BigDecimal coOccurrenceBoost = DecimalScaleV2.scaled(coOccurrenceBoostByDish.get(dish.dishId()), 4);

                    BigDecimal rankingScore = revenueShare.multiply(new BigDecimal("0.55"))
                            .add(frequency.multiply(new BigDecimal("0.35")))
                            .add(coOccurrenceBoost.multiply(new BigDecimal("0.10")));

                    return new MenuRankedDishV2DTO(
                            dish.dishId(),
                            dish.dishName(),
                            dish.description(),
                            dish.category(),
                            dish.currentPrice(),
                            dish.imageUrl(),
                            dish.orderCount(),
                            dish.quantitySold(),
                            dish.revenuePerDish(),
                            dish.orderFrequencyPerDish(),
                            coOccurrenceBoost,
                            DecimalScaleV2.scaled(rankingScore, 6)
                    );
                })
                .sorted(Comparator
                        .comparing(MenuRankedDishV2DTO::rankingScore).reversed()
                        .thenComparing(MenuRankedDishV2DTO::revenuePerDish).reversed()
                        .thenComparing(MenuRankedDishV2DTO::orderCount).reversed()
                        .thenComparing(MenuRankedDishV2DTO::dishId))
                .limit(limit <= 0 ? dishes.size() : limit)
                .toList();

        shadowLogger.logRanking(restaurantId, timeRange, ranked);
        return ranked;
    }
}
