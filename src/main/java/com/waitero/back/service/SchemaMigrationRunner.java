package com.waitero.back.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SchemaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureRestaurantColumns();
        ensureTablePublicIdColumn();
        ensureDishColumns();
        ensureCustomerOrderColumns();
        ensureDishCooccurrenceTable();
        ensureEventLogTable();
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

