package com.waitero.analyticsv2;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.time.Clock;

@AutoConfiguration
@ComponentScan(basePackages = "com.waitero.analyticsv2")
public class AnalyticsV2AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock analyticsV2Clock() {
        return Clock.systemDefaultZone();
    }
}