package com.waitero.back.security;

import com.waitero.back.entity.BackofficeRole;

public record BackofficePrincipal(
        Long userId,
        BackofficeRole role,
        Long restaurantId,
        Long actingRestaurantId
) {
    public boolean isMaster() {
        return role == BackofficeRole.MASTER;
    }

    public boolean isImpersonating() {
        return actingRestaurantId != null;
    }

    public Long effectiveRestaurantId() {
        return actingRestaurantId != null ? actingRestaurantId : restaurantId;
    }
}
