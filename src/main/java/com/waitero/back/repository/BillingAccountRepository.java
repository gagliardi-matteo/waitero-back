package com.waitero.back.repository;

import com.waitero.back.entity.BillingAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillingAccountRepository extends JpaRepository<BillingAccount, Long> {
    Optional<BillingAccount> findByRistoratoreId(Long ristoratoreId);
    List<BillingAccount> findByBillingEnabledTrue();
}
