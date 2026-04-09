package com.waitero.back.dto;

import com.waitero.back.entity.BackofficeRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthMeResponse {
    private Long userId;
    private BackofficeRole role;
    private Long restaurantId;
    private Long actingRestaurantId;
}
