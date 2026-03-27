package com.waitero.back.repository;

import com.waitero.back.entity.Piatto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PiattoRepository extends JpaRepository<Piatto, Long> {
    List<Piatto> findAllByRistoratoreId(Long ristoratoreId);
    Optional<Piatto> findByIdAndRistoratoreId(Long id, Long ristoratoreId);
    boolean existsByIdAndRistoratoreId(Long id, Long ristoratoreId);

    @Query("select p.id from Piatto p where p.ristoratore.id = :ristoratoreId")
    List<Long> findIdsByRistoratoreId(Long ristoratoreId);
}
