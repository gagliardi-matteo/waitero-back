package com.waitero.back.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class PerformanceLabelResolver {

    public String resolve(long views, long addToCart, long orderCount) {
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

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}