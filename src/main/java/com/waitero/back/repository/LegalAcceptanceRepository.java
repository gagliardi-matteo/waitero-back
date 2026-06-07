package com.waitero.back.repository;

import com.waitero.back.entity.LegalAcceptance;
import com.waitero.back.entity.LegalAcceptanceType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalAcceptanceRepository extends JpaRepository<LegalAcceptance, Long> {
    boolean existsByTypeAndRestaurantIdAndContractVersionAndPrivacyVersion(
            LegalAcceptanceType type,
            Long restaurantId,
            String contractVersion,
            String privacyVersion
    );

    boolean existsByTypeAndSessionIdAndTermsVersionAndPrivacyVersionAndAllergenDisclaimerVersion(
            LegalAcceptanceType type,
            String sessionId,
            String termsVersion,
            String privacyVersion,
            String allergenDisclaimerVersion
    );
}
