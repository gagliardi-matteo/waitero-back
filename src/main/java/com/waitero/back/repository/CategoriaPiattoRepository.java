package com.waitero.back.repository;

import com.waitero.back.entity.CategoriaPiatto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoriaPiattoRepository extends JpaRepository<CategoriaPiatto, Long> {
    List<CategoriaPiatto> findAllByRistoratoreId(Long ristoratoreId);
}