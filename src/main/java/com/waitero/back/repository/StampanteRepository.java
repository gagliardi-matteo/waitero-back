package com.waitero.back.repository;

import com.waitero.back.entity.Stampante;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StampanteRepository extends JpaRepository<Stampante, Long> {
    List<Stampante> findByRistoranteId(Long ristoranteId);
    List<Stampante> findByRistoranteIdAndAbilitataTrue(Long ristoranteId);
}
