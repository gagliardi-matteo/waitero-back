package com.waitero.back.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LegalStatusResponse {
    private boolean accepted;
    private LegalConfigResponse config;
}
