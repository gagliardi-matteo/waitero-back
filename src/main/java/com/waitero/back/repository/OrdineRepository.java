package com.waitero.back.repository;

import com.waitero.back.entity.OrderStatus;
import com.waitero.back.entity.Ordine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrdineRepository extends JpaRepository<Ordine, Long> {
    Optional<Ordine> findFirstByRistoratoreIdAndTableIdAndStatusInOrderByCreatedAtDesc(Long ristoratoreId, Integer tableId, Collection<OrderStatus> statuses);
    List<Ordine> findAllByRistoratoreIdAndStatusInOrderByCreatedAtDesc(Long ristoratoreId, Collection<OrderStatus> statuses);
    boolean existsByRistoratoreIdAndTableIdAndStatusIn(Long ristoratoreId, Integer tableId, Collection<OrderStatus> statuses);
}
