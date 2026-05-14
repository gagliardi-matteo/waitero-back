package com.waitero.back.repository;

import com.waitero.back.entity.OrderStatus;
import com.waitero.back.entity.Ordine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrdineRepository extends JpaRepository<Ordine, Long> {
    Optional<Ordine> findFirstByRistoratoreIdAndTableIdAndStatusInOrderByCreatedAtDesc(Long ristoratoreId, Integer tableId, Collection<OrderStatus> statuses);
    List<Ordine> findAllByRistoratoreIdAndStatusInOrderByCreatedAtDesc(Long ristoratoreId, Collection<OrderStatus> statuses);
    boolean existsByRistoratoreIdAndTableIdAndStatusIn(Long ristoratoreId, Integer tableId, Collection<OrderStatus> statuses);

    @Query(value = """
            select
                o.id as id,
                o.table_id as tableId,
                o.status as status,
                o.paid_at as paidAt,
                o.created_at as createdAt,
                o.updated_at as updatedAt,
                o.totale as totale,
                coalesce(sum(oi.quantity), 0) as itemCount
            from customer_orders o
            left join customer_order_items oi on oi.ordine_id = o.id
            where o.ristoratore_id = :restaurantId
              and o.status in (:statuses)
            group by o.id, o.table_id, o.status, o.paid_at, o.created_at, o.updated_at, o.totale
            order by o.updated_at desc, o.id desc
            """, nativeQuery = true)
    List<OrderSummaryProjection> findOrderSummariesByRestaurantAndStatuses(
            @Param("restaurantId") Long restaurantId,
            @Param("statuses") Collection<String> statuses
    );

    @Query(value = """
            select
                o.id as id,
                o.table_id as tableId,
                o.status as status,
                o.paid_at as paidAt,
                o.created_at as createdAt,
                o.updated_at as updatedAt,
                o.totale as totale,
                coalesce(sum(oi.quantity), 0) as itemCount
            from customer_orders o
            left join customer_order_items oi on oi.ordine_id = o.id
            where o.ristoratore_id = :restaurantId
            group by o.id, o.table_id, o.status, o.paid_at, o.created_at, o.updated_at, o.totale
            order by coalesce(o.paid_at, o.updated_at) desc, o.id desc
            """, nativeQuery = true)
    List<OrderSummaryProjection> findAllOrderSummariesByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query(value = """
            select
                o.id as id,
                o.table_id as tableId,
                o.status as status,
                o.paid_at as paidAt,
                o.created_at as createdAt,
                o.updated_at as updatedAt,
                o.totale as totale,
                coalesce(sum(oi.quantity), 0) as itemCount
            from customer_orders o
            left join customer_order_items oi on oi.ordine_id = o.id
            where o.ristoratore_id = :restaurantId
              and (:status = '' or o.status = :status)
              and (
                :search = ''
                or cast(o.id as text) ilike concat('%', :search, '%')
                or cast(o.table_id as text) ilike concat('%', :search, '%')
                or lower(o.status) like lower(concat('%', :search, '%'))
              )
            group by o.id, o.table_id, o.status, o.paid_at, o.created_at, o.updated_at, o.totale
            order by coalesce(o.paid_at, o.updated_at) desc, o.id desc
            """,
            countQuery = """
            select count(*)
            from customer_orders o
            where o.ristoratore_id = :restaurantId
              and (:status = '' or o.status = :status)
              and (
                :search = ''
                or cast(o.id as text) ilike concat('%', :search, '%')
                or cast(o.table_id as text) ilike concat('%', :search, '%')
                or lower(o.status) like lower(concat('%', :search, '%'))
              )
            """,
            nativeQuery = true)
    Page<OrderSummaryProjection> findPagedOrderSummariesByRestaurant(
            @Param("restaurantId") Long restaurantId,
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable
    );

    @Query("""
            select o
            from Ordine o
            where o.ristoratore.id = :restaurantId
              and o.status = :status
              and o.paidAt is not null
              and o.paidAt >= :fromInclusive
              and o.paidAt < :toExclusive
            order by o.paidAt asc, o.id asc
            """)
    List<Ordine> findOrdersForBilling(
            @Param("restaurantId") Long restaurantId,
            @Param("status") OrderStatus status,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive
    );
}
