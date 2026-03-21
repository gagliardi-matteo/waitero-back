package com.waitero.back.repository;

import com.waitero.back.entity.AliasPiatto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AliasPiattoRepository extends JpaRepository<AliasPiatto, Long> {
    Optional<AliasPiatto> findByNomeOriginaleIgnoreCase(String nomeOriginale);
}
