package com.waitero.back.entity;

public enum BusinessType {
    RISTORANTE,
    PUB;

    public static BusinessType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return BusinessType.valueOf(value.trim().toUpperCase());
    }
}
