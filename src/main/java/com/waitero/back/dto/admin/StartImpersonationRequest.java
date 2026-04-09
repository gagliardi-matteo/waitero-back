package com.waitero.back.dto.admin;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StartImpersonationRequest {
    private Long restaurantId;
    private String reason;
}
