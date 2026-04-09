package com.waitero.back.service;

import com.waitero.back.entity.Ristoratore;
import com.waitero.back.repository.RistoratoreRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExperimentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExperimentScheduler.class);

    private final RistoratoreRepository ristoratoreRepository;
    private final ExperimentIntelligenceService experimentIntelligenceService;

    @Scheduled(fixedDelay = 600000L, initialDelay = 600000L)
    public void runAutopilotEvaluation() {
        for (Ristoratore restaurant : ristoratoreRepository.findAll()) {
            try {
                experimentIntelligenceService.applyAutopilotIfEnabled(restaurant.getId());
            } catch (RuntimeException ex) {
                log.warn("Experiment autopilot evaluation failed for restaurant {}", restaurant.getId(), ex);
            }
        }
    }
}