package com.waitero.back.repository;

import com.waitero.back.entity.Ingrediente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IngredienteRepository extends JpaRepository<Ingrediente, Long> {
    Optional<Ingrediente> findByNomeIgnoreCase(String nome);
}
