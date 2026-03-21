package com.waitero.back.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddressSuggestionDTO {
    private String address;
    private String city;
    private String formattedAddress;
    private Double latitude;
    private Double longitude;
    private boolean hasStreetNumber;
}
