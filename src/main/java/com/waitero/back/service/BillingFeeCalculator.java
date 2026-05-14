package com.waitero.back.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class BillingFeeCalculator {

    public BigDecimal calculate(BigDecimal grossRevenue, BigDecimal commissionPercentage, BigDecimal minimumMonthlyFee) {
        if (grossRevenue == null) {
            throw new RuntimeException("Gross revenue obbligatoria");
        }
        if (commissionPercentage == null) {
            throw new RuntimeException("Commissione obbligatoria");
        }
        if (minimumMonthlyFee == null) {
            throw new RuntimeException("Fee minima obbligatoria");
        }
        if (grossRevenue.signum() < 0) {
            throw new RuntimeException("Gross revenue non valida");
        }
        if (commissionPercentage.signum() < 0) {
            throw new RuntimeException("Commissione non valida");
        }
        if (minimumMonthlyFee.signum() < 0) {
            throw new RuntimeException("Fee minima non valida");
        }

        BigDecimal variableFee = grossRevenue
                .multiply(commissionPercentage)
                .setScale(2, RoundingMode.HALF_UP);
        return variableFee.max(minimumMonthlyFee.setScale(2, RoundingMode.HALF_UP));
    }
}
