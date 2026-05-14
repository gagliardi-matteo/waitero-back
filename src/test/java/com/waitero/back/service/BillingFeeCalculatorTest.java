package com.waitero.back.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BillingFeeCalculatorTest {

    private final BillingFeeCalculator calculator = new BillingFeeCalculator();

    @Test
    void shouldApplyCommissionWhenAboveMinimum() {
        BigDecimal fee = calculator.calculate(
                new BigDecimal("1000.00"),
                new BigDecimal("0.010000"),
                new BigDecimal("5.00")
        );

        assertEquals(new BigDecimal("10.00"), fee);
    }

    @Test
    void shouldApplyMinimumWhenCommissionIsLower() {
        BigDecimal fee = calculator.calculate(
                new BigDecimal("100.00"),
                new BigDecimal("0.010000"),
                new BigDecimal("5.00")
        );

        assertEquals(new BigDecimal("5.00"), fee);
    }
}
