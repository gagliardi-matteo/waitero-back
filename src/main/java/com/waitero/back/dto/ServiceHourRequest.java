package com.waitero.back.dto;

import lombok.Data;

@Data
public class ServiceHourRequest {
    private String dayOfWeek;
    private String startTime;
    private String endTime;
}
