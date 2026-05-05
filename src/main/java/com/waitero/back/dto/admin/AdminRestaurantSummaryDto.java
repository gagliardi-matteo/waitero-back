package com.waitero.back.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminRestaurantSummaryDto {
    private Long id;
    private String businessType;
    private String nome;
    private String email;
    private String city;
    private LocalDateTime createdAt;
}
