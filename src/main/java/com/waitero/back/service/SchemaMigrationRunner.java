package com.waitero.back.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
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
        ensureTablePublicIdColumn();
        ensureDishColumns();
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

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = ? and column_name = ?",
                Integer.class,
                tableName,
                columnName
        );
        return count != null && count > 0;
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
