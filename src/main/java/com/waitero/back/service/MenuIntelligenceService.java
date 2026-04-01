package com.waitero.back.service;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MenuIntelligenceService {

    private final JdbcTemplate jdbcTemplate;

    public Map<Long, DishSignal> getDishSignals(Long restaurantId) {
        List<DishSignal> signals = jdbcTemplate.query(
                """
                select
                    p.id as dish_id,
                    coalesce(ev.views, 0) as views,
                    coalesce(ev.clicks, 0) as clicks,
                    coalesce(ev.add_to_cart, 0) as add_to_cart,
                    coalesce(ord.order_count, 0) as order_count
                from piatto p
                left join (
                    select
                        dish_id,
                        count(*) filter (where event_type = 'view_dish') as views,
                        count(*) filter (where event_type = 'click_dish') as clicks,
                        count(*) filter (where event_type = 'add_to_cart') as add_to_cart
                    from event_log
                    where restaurant_id = ?
                      and dish_id is not null
                    group by dish_id
                ) ev on ev.dish_id = p.id
                left join (
                    select
                        coi.piatto_id as dish_id,
                        count(distinct co.id) as order_count
                    from customer_order_items coi
                    join customer_orders co on co.id = coi.ordine_id
                    where co.ristoratore_id = ?
                    group by coi.piatto_id
                ) ord on ord.dish_id = p.id
                where p.ristoratore_id = ?
                """,
                (rs, rowNum) -> {
                    long views = rs.getLong("views");
                    long addToCart = rs.getLong("add_to_cart");
                    long orderCount = rs.getLong("order_count");
                    return DishSignal.builder()
                            .dishId(rs.getLong("dish_id"))
                            .views(views)
                            .clicks(rs.getLong("clicks"))
                            .addToCart(addToCart)
                            .orderCount(orderCount)
                            .viewToCartRate(ratio(addToCart, views))
                            .viewToOrderRate(ratio(orderCount, views))
                            .performanceLabel(resolvePerformanceLabel(views, addToCart, orderCount))
                            .build();
                },
                restaurantId,
                restaurantId,
                restaurantId
        );

        Map<Long, DishSignal> result = new LinkedHashMap<>();
        for (DishSignal signal : signals) {
            result.put(signal.dishId(), signal);
        }
        return result;
    }

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private String resolvePerformanceLabel(long views, long addToCart, long orderCount) {
        if (views >= 10 && orderCount == 0) {
            return "high_interest_low_conversion";
        }
        if (orderCount >= 5 || (views >= 10 && ratio(orderCount, views).compareTo(new BigDecimal("0.1500")) >= 0)) {
            return "top_performer";
        }
        if (addToCart > 0 && orderCount == 0) {
            return "cart_abandonment";
        }
        return "stable";
    }

    @Builder
    public record DishSignal(
            Long dishId,
            long views,
            long clicks,
            long addToCart,
            long orderCount,
            BigDecimal viewToCartRate,
            BigDecimal viewToOrderRate,
            String performanceLabel
    ) {
    }
}
