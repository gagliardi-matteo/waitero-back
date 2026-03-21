package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ServiceHourDTO {
    private Long id;
    private String dayOfWeek;
    private String startTime;
    private String endTime;
}
