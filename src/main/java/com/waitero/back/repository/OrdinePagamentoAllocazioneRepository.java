package com.waitero.back.repository;

import com.waitero.back.entity.OrdinePagamentoAllocazione;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrdinePagamentoAllocazioneRepository extends JpaRepository<OrdinePagamentoAllocazione, Long> {

    @Query("""
            select a
            from OrdinePagamentoAllocazione a
            join fetch a.payment
            join fetch a.orderItem
            where a.payment.id in :paymentIds
            order by a.payment.id asc, a.id asc
            """)
    List<OrdinePagamentoAllocazione> findAllByPaymentIdsOrdered(@Param("paymentIds") List<Long> paymentIds);
}
