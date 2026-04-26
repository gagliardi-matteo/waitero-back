package com.waitero.back.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record DishInsightApplyResultDTO(
        int appliedCount,
        int promotedCount,
        int deprioritizedCount,
        int removedCount,
        int upsellActivatedCount,
        List<Long> updatedDishIds
) {
}
