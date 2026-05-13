package com.waitero.back.service;

import com.waitero.back.dto.UiFeaturesDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UiFeaturesService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${waitero.ui.explainability-balloons-enabled:true}")
    private boolean defaultExplainabilityBalloonsEnabled;

    @Transactional(readOnly = true)
    public UiFeaturesDTO getFeatures() {
        Boolean enabled = jdbcTemplate.query(
                "SELECT explainability_balloons_enabled FROM ui_feature_config WHERE id = 1",
                resultSet -> resultSet.next() ? resultSet.getBoolean("explainability_balloons_enabled") : null
        );
        return new UiFeaturesDTO(enabled != null ? enabled : defaultExplainabilityBalloonsEnabled);
    }

    @Transactional
    public UiFeaturesDTO updateFeatures(boolean explainabilityBalloonsEnabled) {
        jdbcTemplate.update(
                """
                INSERT INTO ui_feature_config (id, explainability_balloons_enabled, updated_at)
                VALUES (1, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO UPDATE SET
                    explainability_balloons_enabled = EXCLUDED.explainability_balloons_enabled,
                    updated_at = EXCLUDED.updated_at
                """,
                explainabilityBalloonsEnabled
        );
        return new UiFeaturesDTO(explainabilityBalloonsEnabled);
    }
}
