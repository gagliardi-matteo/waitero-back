package com.waitero.back.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LegalConfigResponse {
    private String contractVersion;
    private String privacyVersion;
    private String termsVersion;
    private String allergenDisclaimerVersion;
    private String contractUrl;
    private String privacyUrl;
    private String termsUrl;
    private String allergenDisclaimerUrl;
}
