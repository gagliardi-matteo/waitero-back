package com.waitero.back.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeocodeResult {
    private Double latitude;
    private Double longitude;
    private String formattedAddress;
    private String city;
    private String resolvedAddress;
    private boolean hasStreetNumber;
}
