package com.waitero.back.dto.billing;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CreateSetupIntentResponse {
    private Long billingAccountId;
    private String stripeCustomerId;
    private String setupIntentId;
    private String clientSecret;
    private String publishableKey;
}
