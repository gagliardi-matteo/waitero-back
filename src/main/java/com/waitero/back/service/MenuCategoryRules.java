package com.waitero.back.service;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class MenuCategoryRules {

    private static final Set<String> BEVERAGE_CODES = Set.of(
            "BEVANDA",
            "BIRRA_SPINA",
            "BIRRA_BOTTIGLIA",
            "COCKTAIL",
            "DISTILLATO",
            "VINO",
            "ANALCOLICO"
    );

    private static final Set<String> SIDE_CODES = Set.of(
            "CONTORNO",
            "TAGLIERE",
            "FRITTO"
    );

    private static final Set<String> DESSERT_CODES = Set.of("DOLCE");

    private static final Set<String> MAIN_CODES = Set.of(
            "ANTIPASTO",
            "PRIMO",
            "SECONDO",
            "PANINO",
            "TAGLIERE",
            "FRITTO"
    );

    private MenuCategoryRules() {
    }

    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    public static boolean isBeverage(String code) {
        return BEVERAGE_CODES.contains(normalize(code));
    }

    public static boolean isSide(String code) {
        return SIDE_CODES.contains(normalize(code));
    }

    public static boolean isDessert(String code) {
        return DESSERT_CODES.contains(normalize(code));
    }

    public static boolean isMainDish(String code) {
        return MAIN_CODES.contains(normalize(code));
    }

    public static boolean hasCategory(Set<String> codes, Predicate<String> matcher) {
        return codes.stream().anyMatch(matcher);
    }
}
