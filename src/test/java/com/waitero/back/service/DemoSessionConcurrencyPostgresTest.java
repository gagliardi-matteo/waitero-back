package com.waitero.back.service;

import com.waitero.back.BackApplication;
import com.waitero.back.dto.DemoSessionDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(classes = BackApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///demo_seed_concurrency")
class DemoSessionConcurrencyPostgresTest {
    @Autowired DemoSessionService sessions;
    @Autowired JdbcTemplate jdbc;

    @Test void coldConcurrentSeedingIsUniqueAndWarmCreationRepairsAMissingDish() throws Exception {
        var results = new ArrayList<DemoSessionDTO>();
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(20)) {
            var futures = new ArrayList<Future<DemoSessionDTO>>();
            for (int i = 0; i < 20; i++) futures.add(workers.submit(() -> { gate.await(); return sessions.create(); }));
            gate.countDown();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
        }
        Long restaurant = results.getFirst().getRestaurantId();
        assertTrue(results.stream().allMatch(s -> s.getRestaurantId().equals(restaurant)));
        assertEquals(20, new HashSet<>(results.stream().map(DemoSessionDTO::getToken).toList()).size());
        assertEquals(20, new HashSet<>(results.stream().map(DemoSessionDTO::getTablePublicId).toList()).size());
        assertEquals(14, jdbc.queryForObject("select count(*) from piatto where ristoratore_id = ?", Integer.class, restaurant));
        assertEquals(1, jdbc.queryForObject("select count(*) from ristoratore where email = 'demo@waitero.internal'", Integer.class));
        sessions.create();
        assertEquals(14, jdbc.queryForObject("select count(*) from piatto where ristoratore_id = ?", Integer.class, restaurant));
        jdbc.update("delete from piatto where ristoratore_id = ? and nome = 'Margherita'", restaurant);
        sessions.create();
        assertEquals(14, jdbc.queryForObject("select count(*) from piatto where ristoratore_id = ?", Integer.class, restaurant));
    }
}
