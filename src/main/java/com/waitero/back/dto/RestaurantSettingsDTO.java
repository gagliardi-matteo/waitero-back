package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RestaurantSettingsDTO {
    private Long id;
    private String businessType;
    private String nome;
    private String email;
    private String address;
    private String city;
    private String formattedAddress;
    private Double latitude;
    private Double longitude;
    private Integer allowedRadiusMeters;
    private List<ServiceHourDTO> serviceHours;
}
