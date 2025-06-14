package com.waitero.back.repository;

import com.waitero.back.entity.Piatto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PiattoRepository extends JpaRepository<Piatto, Long> {
    List<Piatto> findAllByRistoratoreId(Long ristoratoreId);
}