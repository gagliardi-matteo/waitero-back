package com.waitero.back.repository;

import com.waitero.back.entity.DishCooccurrence;
import com.waitero.back.entity.DishCooccurrenceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DishCooccurrenceRepository extends JpaRepository<DishCooccurrence, DishCooccurrenceId> {

    @Query("""
            select dc
            from DishCooccurrence dc
            join fetch dc.suggestedDish suggested
            where dc.baseDish.id = :dishId
              and suggested.ristoratore.id = :restaurantId
              and coalesce(suggested.disponibile, false) = true
            """)
    List<DishCooccurrence> findAvailableSuggestions(@Param("dishId") Long dishId, @Param("restaurantId") Long restaurantId);

    @Modifying
    @Query("delete from DishCooccurrence dc where dc.baseDish.id in :baseDishIds")
    void deleteByBaseDishIds(@Param("baseDishIds") Collection<Long> baseDishIds);
}
