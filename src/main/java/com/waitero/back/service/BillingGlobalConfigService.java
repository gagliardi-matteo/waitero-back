package com.waitero.back.service;

import com.waitero.back.dto.admin.billing.BillingGlobalConfigDto;
import com.waitero.back.dto.admin.billing.UpdateBillingGlobalConfigRequest;
import com.waitero.back.entity.BillingGlobalConfig;
import com.waitero.back.repository.BillingGlobalConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BillingGlobalConfigService {

    private static final long GLOBAL_CONFIG_ID = 1L;

    private final BillingGlobalConfigRepository billingGlobalConfigRepository;
    private final AdminAuditService adminAuditService;

    @Transactional(readOnly = true)
    public BillingGlobalConfigDto getConfig() {
        return toDto(requireConfig());
    }

    @Transactional(readOnly = true)
    public BillingGlobalConfig requireConfig() {
        return billingGlobalConfigRepository.findById(GLOBAL_CONFIG_ID)
                .orElseThrow(() -> new RuntimeException("Configurazione billing globale non disponibile"));
    }

    @Transactional
    public BillingGlobalConfigDto updateConfig(UpdateBillingGlobalConfigRequest request) {
        validate(request);
        BillingGlobalConfig config = billingGlobalConfigRepository.findById(GLOBAL_CONFIG_ID)
                .orElseGet(() -> BillingGlobalConfig.builder().id(GLOBAL_CONFIG_ID).build());
        config.setCommissionPercentage(request.getCommissionPercentage().setScale(6, RoundingMode.HALF_UP));
        config.setMinimumMonthlyFee(request.getMinimumMonthlyFee().setScale(2, RoundingMode.HALF_UP));
        BillingGlobalConfig saved = billingGlobalConfigRepository.save(config);
        adminAuditService.record("ADMIN_UPDATE_BILLING_GLOBAL_CONFIG", null, "billing_global_config", GLOBAL_CONFIG_ID, Map.of(
                "commissionPercentage", saved.getCommissionPercentage(),
                "minimumMonthlyFee", saved.getMinimumMonthlyFee()
        ));
        return toDto(saved);
    }

    private void validate(UpdateBillingGlobalConfigRequest request) {
        if (request == null) {
            throw new RuntimeException("Configurazione billing globale mancante");
        }
        if (request.getCommissionPercentage() == null || request.getCommissionPercentage().signum() < 0 || request.getCommissionPercentage().compareTo(BigDecimal.ONE) > 0) {
            throw new RuntimeException("Commissione globale non valida: usare un valore tra 0 e 1");
        }
        if (request.getMinimumMonthlyFee() == null || request.getMinimumMonthlyFee().signum() < 0) {
            throw new RuntimeException("Fee minima globale non valida");
        }
    }

    private BillingGlobalConfigDto toDto(BillingGlobalConfig config) {
        return BillingGlobalConfigDto.builder()
                .commissionPercentage(config.getCommissionPercentage())
                .minimumMonthlyFee(config.getMinimumMonthlyFee())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
