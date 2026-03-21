package com.waitero.back.dto;

import lombok.Data;

import java.util.List;

@Data
public class RestaurantSettingsRequest {
    private String nome;
    private String address;
    private String city;
    private Integer allowedRadiusMeters;
    private Double latitude;
    private Double longitude;
    private String formattedAddress;
    private Boolean hasStreetNumber;
    private List<ServiceHourRequest> serviceHours;
}
