package com.waitero.back.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class ProductionSafetyEnvironmentPostProcessorTest {
    private final ProductionSafetyEnvironmentPostProcessor guard = new ProductionSafetyEnvironmentPostProcessor();

    @ParameterizedTest
    @ValueSource(strings = {"create", "create-drop", "update", "CREATE"})
    void rejectsDestructiveAndUncontrolledEffectiveOverrides(String ddl) {
        var environment = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", ddl);
        environment.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(environment, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"validate", "none"})
    void allowsReadOnlySchemaModes(String ddl) {
        var environment = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", ddl);
        environment.setActiveProfiles("prod");
        assertDoesNotThrow(() -> guard.postProcessEnvironment(environment, null));
    }

    @Test
    void rejectsTestProfileAndSqlDebugOutput() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "test");
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(environment, null));
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.jpa.show-sql", "true");
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(environment, null));
    }

    @Test
    void blocksAlternativeJpaSchemaGeneration() {
        var environment = new MockEnvironment().withProperty("spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "drop-and-create");
        environment.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(environment, null));
        environment.setProperty("spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "none");
        environment.setProperty("spring.jpa.generate-ddl", "true");
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(environment, null));
    }

    @Test
    void startsWithActualProductionConfigWithoutInitializingAnyDatasource() {
        var application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run("--spring.profiles.active=prod")) {
            var env = context.getEnvironment();
            assertEquals("validate", env.getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("false", env.getProperty("spring.jpa.show-sql"));
            assertEquals("always", env.getProperty("spring.sql.init.mode"));
            assertEquals("classpath:db/migration/V20260915__customer_order_submit_idempotency.sql",
                    env.getProperty("spring.sql.init.schema-locations"));
            assertEquals("false", env.getProperty("spring.jpa.defer-datasource-initialization"));
            assertNull(env.getProperty("spring.datasource.hikari.connection-timeout"));
            assertNull(env.getProperty("spring.jpa.properties.hibernate.connection.handling_mode"));
            assertEquals("never", env.getProperty("server.error.include-stacktrace"));
        }
    }

    @Test
    void registeredGuardBlocksStartupBeforeDatasourceUsingCommandLineOverride() {
        var application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        var error = assertThrows(IllegalStateException.class, () -> application.run(
                "--spring.profiles.active=prod", "--spring.jpa.hibernate.ddl-auto=create-drop"));
        assertTrue(error.getMessage().contains("ddl-auto"));
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyApplication { }
}
