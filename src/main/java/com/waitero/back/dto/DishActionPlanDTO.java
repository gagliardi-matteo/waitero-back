package com.waitero.back.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record DishActionPlanDTO(
        List<DishIntelligenceDTO> promote,
        List<DishIntelligenceDTO> demote,
        List<DishIntelligenceDTO> removeCandidates,
        List<DishUpsellPairDTO> upsellPairs
) {
}
