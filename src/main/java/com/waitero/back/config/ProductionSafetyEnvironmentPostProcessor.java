package com.waitero.back.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.Locale;

/** Validate effective production overrides before any datasource or schema initialization. */
public final class ProductionSafetyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (Arrays.stream(environment.getActiveProfiles()).noneMatch("prod"::equals)) return;
        String ddl = environment.getProperty("spring.jpa.hibernate.ddl-auto", "validate").toLowerCase(Locale.ROOT);
        if (!ddl.equals("validate") && !ddl.equals("none")) {
            throw new IllegalStateException("Production requires Hibernate ddl-auto=validate or none; schema changes must be applied separately.");
        }
        String nativeDdl = environment.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto", "none");
        String jpaAction = environment.getProperty("spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "none");
        if (environment.getProperty("spring.jpa.generate-ddl", Boolean.class, false)
                || (!nativeDdl.equalsIgnoreCase("none") && !nativeDdl.equalsIgnoreCase("validate"))
                || !jpaAction.equalsIgnoreCase("none")) {
            throw new IllegalStateException("Production cannot enable alternate Hibernate/JPA schema-generation actions.");
        }
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch("test"::equals)) {
            throw new IllegalStateException("Production cannot enable the test profile.");
        }
        if (environment.getProperty("spring.jpa.show-sql", Boolean.class, false)
                || environment.getProperty("debug", Boolean.class, false)
                || environment.getProperty("trace", Boolean.class, false)) {
            throw new IllegalStateException("Production cannot enable SQL, debug or trace output.");
        }
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
