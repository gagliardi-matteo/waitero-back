package com.waitero.back.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class BackofficeProfileDTO {
    private Long userId;
    private String email;
    private String nome;
    private String role;
    private Long restaurantId;
    private String businessType;
    private boolean hasPassword;
}
