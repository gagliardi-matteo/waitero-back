package com.waitero.back.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);

    private final BillingReviewService billingReviewService;

    @Scheduled(cron = "0 0 2 * * *")
    public void createNightlyBillingReviews() {
        LocalDate today = LocalDate.now();
        int created = billingReviewService.createScheduledReviewsForDate(today).size();
        log.info("Billing scheduler completed executionDate={} createdReviews={}", today, created);
    }
}
