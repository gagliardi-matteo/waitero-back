package com.waitero.back.repository;

import com.waitero.back.entity.Ristoratore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RistoratoreRepository extends JpaRepository<Ristoratore, Long> {
    Optional<Ristoratore> findByProviderId(String providerId);
    Optional<Ristoratore> findByEmail(String email);
}