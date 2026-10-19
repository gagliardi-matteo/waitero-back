package com.waitero.back.repository;

import com.waitero.back.entity.CustomerOrderSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerOrderSubmissionRepository extends JpaRepository<CustomerOrderSubmission, Long> {
    Optional<CustomerOrderSubmission> findByRestaurantIdAndTableIdAndRequestId(
            Long restaurantId,
            Integer tableId,
            String requestId
    );
}
