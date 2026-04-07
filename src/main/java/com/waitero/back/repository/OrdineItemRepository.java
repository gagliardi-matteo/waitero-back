package com.waitero.back.repository;

import com.waitero.back.entity.OrdineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrdineItemRepository extends JpaRepository<OrdineItem, Long> {

    @Query("""
            select i
            from OrdineItem i
            join fetch i.piatto
            where i.ordine.id in :orderIds
            order by i.ordine.id asc, i.createdAt asc, i.id asc
            """)
    List<OrdineItem> findAllByOrderIdsOrdered(@Param("orderIds") List<Long> orderIds);

    @Query(value = """
            select oi.piatto_id as dishId,
                   count(distinct oi.ordine_id) as orderCount
            from customer_order_items oi
            join customer_orders o on o.id = oi.ordine_id
            where o.ristoratore_id = :restaurantId
            group by oi.piatto_id
            """, nativeQuery = true)
    List<DishOrderCountProjection> countDishOccurrencesByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query(value = """
            select oi1.piatto_id as baseDishId,
                   oi2.piatto_id as suggestedDishId,
                   count(distinct oi1.ordine_id) as pairCount
            from customer_order_items oi1
            join customer_order_items oi2
              on oi1.ordine_id = oi2.ordine_id
             and oi1.piatto_id <> oi2.piatto_id
            join customer_orders o on o.id = oi1.ordine_id
            where o.ristoratore_id = :restaurantId
            group by oi1.piatto_id, oi2.piatto_id
            """, nativeQuery = true)
    List<DishCooccurrenceCountProjection> countDishPairsByRestaurant(@Param("restaurantId") Long restaurantId);
}
