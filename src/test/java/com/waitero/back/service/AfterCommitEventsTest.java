package com.waitero.back.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AfterCommitEventsTest {
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }

    @Test void eventIsVisibleOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicInteger deliveries = new AtomicInteger();
        AfterCommitEvents.publish(deliveries::incrementAndGet);
        assertEquals(0, deliveries.get(), "SSE must not race the database commit");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(1, deliveries.get());
    }

    @Test void rollbackDoesNotPublishAnOrderThatDoesNotExist() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicInteger deliveries = new AtomicInteger();
        AfterCommitEvents.publish(deliveries::incrementAndGet);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertEquals(0, deliveries.get());
    }

    @Test void nonTransactionalNotificationsRemainImmediate() {
        AtomicInteger deliveries = new AtomicInteger();
        AfterCommitEvents.publish(deliveries::incrementAndGet);
        assertEquals(1, deliveries.get());
    }
}
