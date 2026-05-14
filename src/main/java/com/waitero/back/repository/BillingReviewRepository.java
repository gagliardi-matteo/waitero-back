package com.waitero.back.repository;

import com.waitero.back.entity.BillingReview;
import com.waitero.back.entity.BillingReviewStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BillingReviewRepository extends JpaRepository<BillingReview, Long> {
    boolean existsByRistoratoreIdAndPeriodStartAndPeriodEnd(Long ristoratoreId, LocalDate periodStart, LocalDate periodEnd);

    @EntityGraph(attributePaths = "orderSnapshots")
    @Query("select br from BillingReview br where br.id = :id")
    Optional<BillingReview> findDetailedById(@Param("id") Long id);

    List<BillingReview> findByStatusOrderByCreatedAtAsc(BillingReviewStatus status);

    List<BillingReview> findByRistoratoreIdOrderByPeriodEndDescIdDesc(Long ristoratoreId);

    @Query("""
            select br
            from BillingReview br
            where br.ristoratore.id = :restaurantId
              and br.periodEnd < :currentPeriodStart
            order by br.periodEnd desc, br.id desc
            """)
    List<BillingReview> findPreviousReviews(
            @Param("restaurantId") Long restaurantId,
            @Param("currentPeriodStart") LocalDate currentPeriodStart
    );

    Optional<BillingReview> findFirstByStripeInvoiceId(String stripeInvoiceId);
}
