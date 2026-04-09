package com.waitero.back.repository;

import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BackofficeUserRepository extends JpaRepository<BackofficeUser, Long> {
    Optional<BackofficeUser> findByProviderId(String providerId);
    Optional<BackofficeUser> findFirstByEmailIgnoreCaseAndProviderIgnoreCase(String email, String provider);
    Optional<BackofficeUser> findFirstByEmailIgnoreCaseAndRole(String email, BackofficeRole role);
    Optional<BackofficeUser> findFirstByRestaurantIdAndRole(Long restaurantId, BackofficeRole role);
    boolean existsByEmailIgnoreCaseAndProviderIgnoreCase(String email, String provider);
}
