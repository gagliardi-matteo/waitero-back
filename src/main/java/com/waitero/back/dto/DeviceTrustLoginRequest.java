package com.waitero.back.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceTrustLoginRequest {
    private String deviceId;
    private String deviceTrustToken;
}
