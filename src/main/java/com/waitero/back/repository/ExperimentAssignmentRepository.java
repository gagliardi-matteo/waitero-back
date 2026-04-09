package com.waitero.back.repository;

import com.waitero.back.entity.ExperimentAssignment;
import com.waitero.back.entity.ExperimentAssignmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExperimentAssignmentRepository extends JpaRepository<ExperimentAssignment, ExperimentAssignmentId> {

    @Query("""
            select ea.variant as variant, count(ea) as count
            from ExperimentAssignment ea
            where ea.id.restaurantId = :restaurantId
            group by ea.variant
            """)
    List<VariantDistributionProjection> countVariantsByRestaurant(@Param("restaurantId") Long restaurantId);

    interface VariantDistributionProjection {
        String getVariant();
        Long getCount();
    }
}