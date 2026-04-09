package com.waitero.back.repository;

import com.waitero.back.entity.Tavolo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TavoloRepository extends JpaRepository<Tavolo, Long> {
    List<Tavolo> findAllByRistoratoreIdOrderByNumeroAsc(Long ristoratoreId);
    Optional<Tavolo> findByRistoratoreIdAndNumero(Long ristoratoreId, Integer numero);
    Optional<Tavolo> findByRistoratoreIdAndNumeroAndAttivoTrue(Long ristoratoreId, Integer numero);
    Optional<Tavolo> findByTablePublicId(String tablePublicId);
    boolean existsByRistoratoreIdAndNumero(Long ristoratoreId, Integer numero);
}
