package com.waitero.back.repository;

import com.waitero.back.entity.OrdinePagamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrdinePagamentoRepository extends JpaRepository<OrdinePagamento, Long> {

    @Query("""
            select p
            from OrdinePagamento p
            join fetch p.ordine
            where p.ordine.id in :orderIds
            order by p.ordine.id asc, p.createdAt asc, p.id asc
            """)
    List<OrdinePagamento> findAllByOrderIdsOrdered(@Param("orderIds") List<Long> orderIds);
}
