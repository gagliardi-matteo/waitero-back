package com.waitero.back.controller;

import com.waitero.back.dto.ContactDemoRequest;
import com.waitero.back.service.ContactMailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/contact")
public class ContactController {

    private static final int MAX_REQUESTS_PER_HOUR = 5;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final ContactMailService contactMailService;
    private final Map<String, ArrayDeque<Instant>> attemptsByIp = new ConcurrentHashMap<>();

    public ContactController(ContactMailService contactMailService) {
        this.contactMailService = contactMailService;
    }

    @PostMapping("/demo")
    public ResponseEntity<Void> requestDemo(@Valid @RequestBody ContactDemoRequest request, HttpServletRequest servletRequest) {
        if (request.website() != null && !request.website().isBlank()) {
            return ResponseEntity.accepted().build();
        }

        enforceRateLimit(clientIp(servletRequest));
        contactMailService.sendDemoRequest(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private void enforceRateLimit(String ip) {
        Instant cutoff = Instant.now().minus(WINDOW);
        ArrayDeque<Instant> attempts = attemptsByIp.computeIfAbsent(ip, ignored -> new ArrayDeque<>());
        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
                attempts.removeFirst();
            }
            if (attempts.size() >= MAX_REQUESTS_PER_HOUR) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Troppe richieste. Riprova più tardi.");
            }
            attempts.addLast(Instant.now());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
