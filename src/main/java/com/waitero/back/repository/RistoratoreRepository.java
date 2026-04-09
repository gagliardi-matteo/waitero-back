package com.waitero.back.repository;

import com.waitero.back.entity.Ristoratore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RistoratoreRepository extends JpaRepository<Ristoratore, Long> {
    Optional<Ristoratore> findByProviderId(String providerId);
    Optional<Ristoratore> findByEmail(String email);
    List<Ristoratore> findAllByOrderByCreatedAtDesc();

    @Query("""
            select r
            from Ristoratore r
            where lower(r.nome) like lower(concat('%', :query, '%'))
               or lower(r.email) like lower(concat('%', :query, '%'))
               or lower(coalesce(r.city, '')) like lower(concat('%', :query, '%'))
            order by r.createdAt desc
            """)
    List<Ristoratore> searchForAdmin(@Param("query") String query);
}
