package com.waitero.back.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SchemaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.master.bootstrap-emails:}")
    private String masterBootstrapEmails;

    @Value("${app.local-login.restaurant-bootstrap-password:}")
    private String restaurantBootstrapPassword;

    @Override
    public void run(ApplicationArguments args) {
        ensureRestaurantColumns();
        ensureBackofficeUserTable();
        ensureTablePublicIdColumn();
        ensureDishColumns();
        ensureCustomerOrderColumns();
        ensureCustomerOrderItemColumns();
        ensureDishCooccurrenceTable();
        ensureExperimentAssignmentTable();
        ensureExperimentConfigTable();
        ensureExperimentModeTable();
        ensureExperimentDecisionLogTable();
        ensureEventLogTable();
        ensureAdminAuditLogTable();
    }

    private void ensureRestaurantColumns() {
        if (!tableExists("ristoratore")) {
            return;
        }

        if (!columnExists("ristoratore", "created_at")) {
            jdbcTemplate.execute("ALTER TABLE ristoratore ADD COLUMN created_at timestamp(6) without time zone");
            log.info("Added missing column ristoratore.created_at");
        }

        jdbcTemplate.execute("UPDATE ristoratore SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL");
        jdbcTemplate.execute("ALTER TABLE ristoratore ALTER COLUMN created_at SET NOT NULL");
    }

    private void ensureBackofficeUserTable() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS backoffice_user (
                    id BIGSERIAL PRIMARY KEY,
                    email varchar(255) NOT NULL,
                    nome varchar(255) NOT NULL,
                    provider varchar(255),
                    provider_id varchar(255),
                    password_hash varchar(255),
                    role varchar(32) NOT NULL,
                    restaurant_id bigint,
                    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
        );

        if (!columnExists("backoffice_user", "password_hash")) {
            jdbcTemplate.execute("ALTER TABLE backoffice_user ADD COLUMN password_hash varchar(255)");
            log.info("Added missing column backoffice_user.password_hash");
        }

        jdbcTemplate.execute("ALTER TABLE backoffice_user DROP CONSTRAINT IF EXISTS ukkognhgnn51dkma6qdtpb9gv2d");
        jdbcTemplate.execute("ALTER TABLE backoffice_user DROP CONSTRAINT IF EXISTS backoffice_user_email_key");
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_backoffice_user_email_unique");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_backoffice_user_email ON backoffice_user(lower(email))");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_backoffice_user_provider_id_unique ON backoffice_user(provider_id) WHERE provider_id IS NOT NULL");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_backoffice_user_local_email_unique ON backoffice_user(lower(email)) WHERE upper(provider) = 'LOCAL'");
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_backoffice_user_master_email_unique ON backoffice_user(lower(email)) WHERE role = 'MASTER'");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_backoffice_user_role ON backoffice_user(role)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_backoffice_user_restaurant_id ON backoffice_user(restaurant_id)");

        if (tableExists("ristoratore")) {
            jdbcTemplate.execute(
                    """
                    INSERT INTO backoffice_user (email, nome, provider, role, restaurant_id, created_at)
                    SELECT r.email,
                           COALESCE(NULLIF(btrim(r.nome), ''), r.email),
                           'LOCAL',
                           'RISTORATORE',
                           r.id,
                           COALESCE(r.created_at, CURRENT_TIMESTAMP)
                    FROM ristoratore r
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM backoffice_user bu
                        WHERE lower(bu.email) = lower(r.email)
                          AND upper(COALESCE(bu.provider, '')) = 'LOCAL'
                    )
                    """
            );

            jdbcTemplate.execute(
                    """
                    DELETE FROM backoffice_user bu
                    WHERE bu.role = 'RISTORATORE'
                      AND upper(COALESCE(bu.provider, '')) <> 'LOCAL'
                      AND EXISTS (
                          SELECT 1
                          FROM backoffice_user local_bu
                          WHERE lower(local_bu.email) = lower(bu.email)
                            AND local_bu.role = 'RISTORATORE'
                            AND upper(COALESCE(local_bu.provider, '')) = 'LOCAL'
                      )
                    """
            );

            jdbcTemplate.execute(
                    """
                    UPDATE backoffice_user bu
                    SET restaurant_id = r.id,
                        role = 'RISTORATORE',
                        provider = 'LOCAL',
                        provider_id = NULL,
                        nome = COALESCE(NULLIF(btrim(r.nome), ''), bu.nome)
                    FROM ristoratore r
                    WHERE lower(bu.email) = lower(r.email)
                      AND bu.role = 'RISTORATORE'
                      AND upper(COALESCE(bu.provider, '')) = 'LOCAL'
                    """
            );

            seedRestaurantLocalPasswords();
        }

        for (String email : parseBootstrapEmails()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO backoffice_user (email, nome, provider, role, created_at)
                    SELECT ?, ?, 'GOOGLE', 'MASTER', CURRENT_TIMESTAMP
                    WHERE NOT EXISTS (
                        SELECT 1 FROM backoffice_user WHERE lower(email) = lower(?) AND role = 'MASTER'
                    )
                    """,
                    email,
                    email,
                    email
            );
        }
    }

    private void seedRestaurantLocalPasswords() {
        if (restaurantBootstrapPassword == null || restaurantBootstrapPassword.isBlank()) {
            return;
        }

        String passwordHash = passwordEncoder.encode(restaurantBootstrapPassword);
        int updated = jdbcTemplate.update(
                """
                UPDATE backoffice_user
                SET password_hash = ?
                WHERE role = 'RISTORATORE'
                  AND upper(COALESCE(provider, '')) = 'LOCAL'
                  AND password_hash IS NULL
                """,
                passwordHash
        );
        if (updated > 0) {
            log.info("Seeded local password for {} restaurant users", updated);
        }
    }

    private void ensureTablePublicIdColumn() {
        if (!tableExists("tavoli")) {
            return;
        }

        if (!columnExists("tavoli", "table_public_id")) {
            jdbcTemplate.execute("ALTER TABLE tavoli ADD COLUMN table_public_id varchar(32)");
            log.info("Added missing column tavoli.table_public_id");
        }

        List<Long> tableIds = jdbcTemplate.queryForList(
                "select id from tavoli where table_public_id is null or btrim(table_public_id) = ''",
                Long.class
        );

        for (Long tableId : tableIds) {
            jdbcTemplate.update(
                    "update tavoli set table_public_id = ? where id = ?",
                    generateUniqueTablePublicId(),
                    tableId
            );
        }

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_tavolo_public_id ON tavoli(table_public_id)");
        jdbcTemplate.execute(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1
                        FROM pg_constraint
                        WHERE conname = 'uk_tavolo_public_id'
                    ) THEN
                        ALTER TABLE tavoli
                        ADD CONSTRAINT uk_tavolo_public_id UNIQUE (table_public_id);
                    END IF;
                END
                $$;
                """
        );
        jdbcTemplate.execute("ALTER TABLE tavoli ALTER COLUMN table_public_id SET NOT NULL");
    }

    private void ensureDishColumns() {
        if (!tableExists("piatto")) {
            return;
        }

        if (!columnExists("piatto", "ingredienti")) {
            jdbcTemplate.execute("ALTER TABLE piatto ADD COLUMN ingredienti varchar(512)");
            log.info("Added missing column piatto.ingredienti");
        }

        if (!columnExists("piatto", "allergeni")) {
            jdbcTemplate.execute("ALTER TABLE piatto ADD COLUMN allergeni varchar(512)");
            log.info("Added missing column piatto.allergeni");
        }

        if (!columnExists("piatto", "consigliato")) {
            jdbcTemplate.execute("ALTER TABLE piatto ADD COLUMN consigliato boolean NOT NULL DEFAULT false");
            log.info("Added missing column piatto.consigliato");
        }
    }

    private void ensureCustomerOrderColumns() {
        if (!tableExists("customer_orders")) {
            return;
        }

        if (!columnExists("customer_orders", "note_cucina")) {
            jdbcTemplate.execute("ALTER TABLE customer_orders ADD COLUMN note_cucina varchar(1000)");
            log.info("Added missing column customer_orders.note_cucina");
        }

        if (!columnExists("customer_orders", "totale")) {
            jdbcTemplate.execute("ALTER TABLE customer_orders ADD COLUMN totale numeric(10,2)");
            log.info("Added missing column customer_orders.totale");
        }

        jdbcTemplate.execute(
                """
                UPDATE customer_orders o
                SET totale = totals.total_amount
                FROM (
                    SELECT ordine_id,
                           COALESCE(SUM(prezzo_unitario * quantity), 0)::numeric(10,2) AS total_amount
                    FROM customer_order_items
                    GROUP BY ordine_id
                ) totals
                WHERE o.id = totals.ordine_id
                  AND (o.totale IS NULL OR o.totale <> totals.total_amount)
                """
        );
        jdbcTemplate.execute("UPDATE customer_orders SET totale = 0 WHERE totale IS NULL");
        jdbcTemplate.execute("ALTER TABLE customer_orders ALTER COLUMN totale SET NOT NULL");

        if (!columnExists("customer_orders", "variant")) {
            jdbcTemplate.execute("ALTER TABLE customer_orders ADD COLUMN variant varchar(1)");
            log.info("Added missing column customer_orders.variant");
        }
        jdbcTemplate.execute("UPDATE customer_orders SET variant = 'A' WHERE variant IS NULL OR btrim(variant) = ''");
        jdbcTemplate.execute("ALTER TABLE customer_orders ALTER COLUMN variant SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE customer_orders ALTER COLUMN variant TYPE varchar(20)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_customer_orders_variant ON customer_orders(ristoratore_id, variant)");
    }

    private void ensureCustomerOrderItemColumns() {
        if (!tableExists("customer_order_items")) {
            return;
        }

        if (!columnExists("customer_order_items", "source")) {
            jdbcTemplate.execute("ALTER TABLE customer_order_items ADD COLUMN source varchar(50)");
            log.info("Added missing column customer_order_items.source");
        }

        if (!columnExists("customer_order_items", "source_dish_id")) {
            jdbcTemplate.execute("ALTER TABLE customer_order_items ADD COLUMN source_dish_id bigint");
            log.info("Added missing column customer_order_items.source_dish_id");
        }

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_customer_order_items_source ON customer_order_items(source)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_customer_order_items_source_dish_id ON customer_order_items(source_dish_id)");
    }
    private void ensureDishCooccurrenceTable() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS dish_cooccurrence (
                    base_dish_id bigint NOT NULL,
                    suggested_dish_id bigint NOT NULL,
                    count bigint NOT NULL,
                    confidence double precision NOT NULL,
                    PRIMARY KEY (base_dish_id, suggested_dish_id),
                    CONSTRAINT fk_dish_cooccurrence_base FOREIGN KEY (base_dish_id) REFERENCES piatto(id) ON DELETE CASCADE,
                    CONSTRAINT fk_dish_cooccurrence_suggested FOREIGN KEY (suggested_dish_id) REFERENCES piatto(id) ON DELETE CASCADE
                )
                """
        );
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_dish_cooccurrence_base ON dish_cooccurrence(base_dish_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_dish_cooccurrence_suggested ON dish_cooccurrence(suggested_dish_id)");
    }
    private void ensureExperimentAssignmentTable() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS experiment_assignment (
                    session_id VARCHAR(100),
                    restaurant_id BIGINT,
                    variant VARCHAR(1),
                    created_at TIMESTAMP,
                    PRIMARY KEY (session_id, restaurant_id)
                )
                """
        );
        jdbcTemplate.execute("UPDATE experiment_assignment SET variant = 'A' WHERE variant IS NULL OR btrim(variant) = ''");
        jdbcTemplate.execute("UPDATE experiment_assignment SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL");
        jdbcTemplate.execute("ALTER TABLE experiment_assignment ALTER COLUMN variant TYPE varchar(20)");
        jdbcTemplate.execute("ALTER TABLE experiment_assignment ALTER COLUMN variant SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE experiment_assignment ALTER COLUMN created_at SET NOT NULL");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_experiment_assignment_restaurant_variant ON experiment_assignment(restaurant_id, variant)");
    }
    private void ensureExperimentConfigTable() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS experiment_config (
                    restaurant_id BIGINT PRIMARY KEY,
                    autopilot_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    min_sample_size INT NOT NULL DEFAULT 50,
                    min_uplift_percent DOUBLE PRECISION NOT NULL DEFAULT 5.0,
                    min_confidence DOUBLE PRECISION NOT NULL DEFAULT 0.95,
                    holdout_percent INT NOT NULL DEFAULT 10,
                    updated_at TIMESTAMP NOT NULL
                )
                """
        );
        jdbcTemplate.execute("ALTER TABLE experiment_config ALTER COLUMN autopilot_enabled SET DEFAULT FALSE");
        jdbcTemplate.execute("ALTER TABLE experiment_config ALTER COLUMN min_sample_size SET DEFAULT 50");
        jdbcTemplate.execute("ALTER TABLE experiment_config ALTER COLUMN min_uplift_percent SET DEFAULT 5.0");
        jdbcTemplate.execute("ALTER TABLE experiment_config ALTER COLUMN min_confidence SET DEFAULT 0.95");
        jdbcTemplate.execute("ALTER TABLE experiment_config ALTER COLUMN holdout_percent SET DEFAULT 10");
        jdbcTemplate.execute("UPDATE experiment_config SET updated_at = CURRENT_TIMESTAMP WHERE updated_at IS NULL");
    }

    private void ensureExperimentModeTable() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS experiment_mode (
                    restaurant_id BIGINT PRIMARY KEY,
                    mode VARCHAR(20)
                )
                """
        );
        jdbcTemplate.execute("UPDATE experiment_mode SET mode = 'AB' WHERE mode IS NULL OR btrim(mode) = ''");
        jdbcTemplate.execute("ALTER TABLE experiment_mode ALTER COLUMN mode SET NOT NULL");
    }

    private void ensureExperimentDecisionLogTable() {
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS experiment_decision_log (
                    id BIGSERIAL PRIMARY KEY,
                    restaurant_id BIGINT,
                    decision VARCHAR(20),
                    uplift DOUBLE PRECISION,
                    confidence DOUBLE PRECISION,
                    created_at TIMESTAMP
                )
                """
        );
        jdbcTemplate.execute("UPDATE experiment_decision_log SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_experiment_decision_log_restaurant_created_at ON experiment_decision_log(restaurant_id, created_at DESC)");
    }
    private void ensureEventLogTable() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS event_log (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                    event_type varchar(64) NOT NULL,
                    user_id varchar(128),
                    session_id varchar(128),
                    restaurant_id bigint,
                    table_id integer,
                    dish_id bigint,
                    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
                    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
        );
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_event_log_restaurant_created_at ON event_log(restaurant_id, created_at)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_event_log_type_created_at ON event_log(event_type, created_at)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_event_log_session_id ON event_log(session_id)");
    }


    private void ensureAdminAuditLogTable() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS admin_audit_log (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                    master_user_id bigint NOT NULL,
                    restaurant_id bigint,
                    action varchar(96) NOT NULL,
                    entity_type varchar(64),
                    entity_id varchar(128),
                    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
                    created_at timestamp(6) without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
        );
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_admin_audit_log_restaurant_created_at ON admin_audit_log(restaurant_id, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_admin_audit_log_master_created_at ON admin_audit_log(master_user_id, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_admin_audit_log_action_created_at ON admin_audit_log(action, created_at DESC)");
    }
    private List<String> parseBootstrapEmails() {
        if (masterBootstrapEmails == null || masterBootstrapEmails.isBlank()) {
            return List.of();
        }
        return Arrays.stream(masterBootstrapEmails.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private boolean tableExists(String tableName) {
        return jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            return objectExists(metaData.getTables(null, "public", tableName, new String[]{"TABLE"}));
        });
    }

    private boolean columnExists(String tableName, String columnName) {
        return jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            return objectExists(metaData.getColumns(null, "public", tableName, columnName));
        });
    }

    private boolean objectExists(ResultSet resultSet) throws SQLException {
        try (resultSet) {
            return resultSet.next();
        }
    }

    private String generateUniqueTablePublicId() {
        String candidate;
        do {
            candidate = "tbl_" + randomBase62(10);
        } while (tablePublicIdExists(candidate));
        return candidate;
    }

    private boolean tablePublicIdExists(String candidate) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from tavoli where table_public_id = ?",
                Integer.class,
                candidate
        );
        return count != null && count > 0;
    }

    private String randomBase62(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return builder.toString();
    }
}





