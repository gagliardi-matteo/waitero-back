package com.waitero.back.repository;

import com.waitero.back.entity.PiattoCanonicale;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PiattoCanonicaleRepository extends JpaRepository<PiattoCanonicale, Long> {
    Optional<PiattoCanonicale> findByNomeCanonicoIgnoreCaseAndCategoriaIgnoreCase(String nomeCanonico, String categoria);
}
