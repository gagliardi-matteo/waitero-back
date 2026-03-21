package com.waitero.back.repository;

import com.waitero.back.entity.PiattoIngredienteRistoratore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PiattoIngredienteRistoratoreRepository extends JpaRepository<PiattoIngredienteRistoratore, Long> {
    List<PiattoIngredienteRistoratore> findAllByPiattoIdOrderByIngredienteNomeAsc(Long piattoId);
    void deleteAllByPiattoId(Long piattoId);
}
