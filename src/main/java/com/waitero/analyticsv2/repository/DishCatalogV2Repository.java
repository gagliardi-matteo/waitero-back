package com.waitero.analyticsv2.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DishCatalogV2Repository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<DishCatalogRow> findDish(Long restaurantId, Long dishId) {
        if (restaurantId == null || dishId == null) {
            return Optional.empty();
        }

        String sql = """
                select
                    p.id as dish_id,
                    p.nome as dish_name,
                    p.descrizione as description,
                    cast(p.categoria as varchar) as category,
                    coalesce(p.prezzo, 0) as price,
                    coalesce(p.disponibile, false) as available,
                    p.image_url as image_url
                from piatto p
                where p.ristoratore_id = :restaurantId
                  and p.id = :dishId
                """;

        List<DishCatalogRow> rows = jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("dishId", dishId), (rs, rowNum) -> new DishCatalogRow(
                rs.getLong("dish_id"),
                rs.getString("dish_name"),
                rs.getString("description"),
                rs.getString("category"),
                rs.getBigDecimal("price"),
                rs.getBoolean("available"),
                rs.getString("image_url")
        ));

        return rows.stream().findFirst();
    }

    public Map<Long, DishCatalogRow> findDishMap(Long restaurantId, Collection<Long> dishIds, boolean onlyAvailable) {
        if (restaurantId == null || dishIds == null || dishIds.isEmpty()) {
            return Map.of();
        }

        String availabilityFilter = onlyAvailable ? " and coalesce(p.disponibile, false) = true " : "";
        String sql = """
                select
                    p.id as dish_id,
                    p.nome as dish_name,
                    p.descrizione as description,
                    cast(p.categoria as varchar) as category,
                    coalesce(p.prezzo, 0) as price,
                    coalesce(p.disponibile, false) as available,
                    p.image_url as image_url
                from piatto p
                where p.ristoratore_id = :restaurantId
                  and p.id in (:dishIds)
                """ + availabilityFilter + """
                order by p.id asc
                """;

        Map<Long, DishCatalogRow> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("dishIds", dishIds), rs -> {
            DishCatalogRow row = new DishCatalogRow(
                    rs.getLong("dish_id"),
                    rs.getString("dish_name"),
                    rs.getString("description"),
                    rs.getString("category"),
                    rs.getBigDecimal("price"),
                    rs.getBoolean("available"),
                    rs.getString("image_url")
            );
            result.put(row.dishId(), row);
        });

        return result;
    }

    public record DishCatalogRow(
            Long dishId,
            String dishName,
            String description,
            String category,
            BigDecimal price,
            Boolean available,
            String imageUrl
    ) {
    }
}
