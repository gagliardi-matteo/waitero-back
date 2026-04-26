package com.waitero.back.service;

import java.math.BigDecimal;

final class DishIntelligenceScoreCalculator {

    private final double affinityWeight;
    private final double explorationConstant;

    DishIntelligenceScoreCalculator(double affinityWeight, double explorationConstant) {
        this.affinityWeight = affinityWeight;
        this.explorationConstant = explorationConstant;
    }

    double computeRpi(BigDecimal revenue, long impressions) {
        double safeRevenue = revenue == null ? 0.0d : revenue.doubleValue();
        return (safeRevenue + 5.0d) / (impressions + 10.0d);
    }

    double computeCtr(long clicks, long impressions) {
        if (impressions <= 0L) {
            return 0.0d;
        }
        return clicks / (double) impressions;
    }

    double computeOrderRate(long orders, long views, long clicks) {
        long denominator = Math.max(views, clicks);
        if (denominator <= 0L) {
            return 0.0d;
        }
        return orders / (double) denominator;
    }

    double normalizeLift(BigDecimal lift) {
        double safeLift = lift == null ? 0.0d : Math.max(0.0d, lift.doubleValue());
        return safeLift <= 0.0d ? 0.0d : safeLift / (1.0d + safeLift);
    }

    double combineAffinity(BigDecimal lift, BigDecimal affinity) {
        double normalizedLift = normalizeLift(lift);
        double safeAffinity = affinity == null ? 0.0d : Math.max(0.0d, affinity.doubleValue());
        return (0.7d * normalizedLift) + (0.3d * safeAffinity);
    }

    double computeExplorationBoost(long totalImpressions, long dishImpressions) {
        if (totalImpressions <= 1L) {
            return 0.0d;
        }
        return explorationConstant * Math.sqrt(Math.log(totalImpressions) / (dishImpressions + 1.0d));
    }

    double computeScore(BigDecimal revenue, long impressions, double marginWeight, double affinityScore, long totalImpressions) {
        return (computeRpi(revenue, impressions) * marginWeight)
                + (affinityWeight * affinityScore)
                + computeExplorationBoost(totalImpressions, impressions);
    }
}
