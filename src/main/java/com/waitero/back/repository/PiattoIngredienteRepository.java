package com.waitero.back.repository;

import com.waitero.back.entity.PiattoIngrediente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PiattoIngredienteRepository extends JpaRepository<PiattoIngrediente, Long> {
    Optional<PiattoIngrediente> findByPiattoCanonicaleIdAndIngredienteId(Long piattoCanonicaleId, Long ingredienteId);
    List<PiattoIngrediente> findAllByPiattoCanonicaleIdOrderByIngredienteNomeAsc(Long piattoCanonicaleId);
}
