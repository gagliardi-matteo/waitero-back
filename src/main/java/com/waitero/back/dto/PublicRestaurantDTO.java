package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PublicRestaurantDTO {
    private Long id;
    private String nome;
    private String formattedAddress;
}
