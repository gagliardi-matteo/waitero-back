package com.waitero.analyticsv2;

import com.waitero.analyticsv2.dto.AnalyticsV2DishMetricsDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2OverviewDTO;
import com.waitero.analyticsv2.dto.MenuRankedDishV2DTO;
import com.waitero.analyticsv2.service.AnalyticsV2Service;
import com.waitero.analyticsv2.service.CoOccurrenceV2Service;
import com.waitero.analyticsv2.service.MenuIntelligenceV2Service;
import com.waitero.analyticsv2.service.UpsellV2Service;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRangeResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsV2BehaviorIT extends AnalyticsV2IntegrationTestSupport {

    @Autowired
    private AnalyticsV2Service analyticsV2Service;

    @Autowired
    private MenuIntelligenceV2Service menuIntelligenceV2Service;

    @Autowired
    private CoOccurrenceV2Service coOccurrenceV2Service;

    @Autowired
    private UpsellV2Service upsellV2Service;

    @Autowired
    private AnalyticsV2TimeRangeResolver timeRangeResolver;

    @Test
    void shouldUseLast30DaysByDefaultAndNeverFallBackToAllTime() {
        AnalyticsV2TimeRange defaultRange = timeRangeResolver.resolve(null, null);
        AnalyticsV2TimeRange extendedRange = timeRangeResolver.resolve(today().minusDays(60), today());

        AnalyticsV2OverviewDTO defaultOverview = analyticsV2Service.getOverview(VALIDATION_RESTAURANT_ID, defaultRange);
        AnalyticsV2OverviewDTO extendedOverview = analyticsV2Service.getOverview(VALIDATION_RESTAURANT_ID, extendedRange);

        assertEquals(4L, defaultOverview.totalOrders());
        assertBigDecimalEquals(new BigDecimal("63.00"), defaultOverview.totalRevenue());
        assertEquals(5L, extendedOverview.totalOrders());
        assertBigDecimalEquals(new BigDecimal("78.00"), extendedOverview.totalRevenue());
    }

    @Test
    void shouldReturnSafeDefaultsWhenNoOrdersExist() {
        AnalyticsV2TimeRange timeRange = defaultRange();

        AnalyticsV2OverviewDTO overview = analyticsV2Service.getOverview(EMPTY_RESTAURANT_ID, timeRange);
        List<AnalyticsV2DishMetricsDTO> dishMetrics = analyticsV2Service.getDishMetrics(EMPTY_RESTAURANT_ID, timeRange);
        List<MenuRankedDishV2DTO> ranking = menuIntelligenceV2Service.getRankedMenu(EMPTY_RESTAURANT_ID, 10, timeRange);

        assertEquals(0L, overview.totalOrders());
        assertBigDecimalEquals(BigDecimal.ZERO.setScale(2), overview.totalRevenue());
        assertBigDecimalEquals(BigDecimal.ZERO.setScale(2), overview.averageOrderValue());
        assertBigDecimalEquals(BigDecimal.ZERO.setScale(4), overview.itemsPerOrder());
        assertEquals(2, dishMetrics.size());
        assertTrue(dishMetrics.stream().allMatch(dish -> dish.orderCount() == 0L));
        assertTrue(dishMetrics.stream().allMatch(dish -> dish.quantitySold() == 0L));
        assertTrue(dishMetrics.stream().allMatch(dish -> dish.revenuePerDish().compareTo(BigDecimal.ZERO.setScale(2)) == 0));
        assertEquals(List.of(EMPTY_DISH_1_ID, EMPTY_DISH_2_ID), ranking.stream().map(MenuRankedDishV2DTO::dishId).toList());
        assertTrue(ranking.stream().allMatch(dish -> dish.rankingScore().compareTo(BigDecimal.ZERO.setScale(6)) == 0));
        assertTrue(coOccurrenceV2Service.getTopRelated(EMPTY_RESTAURANT_ID, EMPTY_DISH_1_ID, 10, true, timeRange).isEmpty());
        assertTrue(upsellV2Service.getDishSuggestions(EMPTY_RESTAURANT_ID, EMPTY_DISH_1_ID, 10, timeRange).isEmpty());
        assertTrue(upsellV2Service.getCartSuggestions(EMPTY_RESTAURANT_ID, List.of(EMPTY_DISH_1_ID), 10, timeRange).isEmpty());
    }

    @Test
    void shouldHandleSingleDishOrdersAndRemainDeterministic() {
        AnalyticsV2TimeRange timeRange = defaultRange();

        List<MenuRankedDishV2DTO> firstRanking = menuIntelligenceV2Service.getRankedMenu(SINGLE_RESTAURANT_ID, 10, timeRange);
        List<MenuRankedDishV2DTO> secondRanking = menuIntelligenceV2Service.getRankedMenu(SINGLE_RESTAURANT_ID, 10, timeRange);

        assertIterableEquals(firstRanking, secondRanking);
        assertEquals(List.of(SINGLE_DISH_BASE_ID, SINGLE_DISH_OTHER_ID), firstRanking.stream().map(MenuRankedDishV2DTO::dishId).toList());
        assertTrue(coOccurrenceV2Service.getTopRelated(SINGLE_RESTAURANT_ID, SINGLE_DISH_BASE_ID, 10, true, timeRange).isEmpty());
        assertTrue(upsellV2Service.getDishSuggestions(SINGLE_RESTAURANT_ID, SINGLE_DISH_BASE_ID, 10, timeRange).isEmpty());
        assertTrue(upsellV2Service.getCartSuggestions(SINGLE_RESTAURANT_ID, List.of(SINGLE_DISH_BASE_ID), 10, timeRange).isEmpty());
    }
}