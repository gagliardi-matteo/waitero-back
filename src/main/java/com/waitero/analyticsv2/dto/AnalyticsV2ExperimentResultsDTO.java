package com.waitero.analyticsv2.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

public record AnalyticsV2ExperimentResultsDTO(
        LocalDate dateFrom,
        LocalDate dateTo,
        @JsonProperty("A") AnalyticsV2ExperimentGroupDTO groupA,
        @JsonProperty("B") AnalyticsV2ExperimentGroupDTO groupB,
        AnalyticsV2ExperimentUpliftDTO uplift,
        AnalyticsV2ExperimentSignificanceDTO significance
) {
}
