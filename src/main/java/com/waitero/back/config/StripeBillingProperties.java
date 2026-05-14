package com.waitero.back.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "stripe.billing")
@Getter
@Setter
public class StripeBillingProperties {
    private String secretKey;
    private String publishableKey;
    private String webhookSecret;
    private String currency = "eur";
}
