package com.waitero.back.repository;

import com.waitero.back.entity.Piatto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PiattoRepository extends JpaRepository<Piatto, Long> {
    List<Piatto> findAllByRistoratoreId(Long ristoratoreId);
    Optional<Piatto> findByIdAndRistoratoreId(Long id, Long ristoratoreId);
}
