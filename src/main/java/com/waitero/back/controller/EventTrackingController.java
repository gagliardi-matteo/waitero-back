package com.waitero.back.controller;

import com.waitero.back.dto.EventTrackingRequest;
import com.waitero.back.service.EventTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventTrackingController {

    private final EventTrackingService eventTrackingService;

    // Riceve un evento di tracking e lo inoltra al service di persistenza.
    @PostMapping
    public ResponseEntity<Void> track(@RequestBody EventTrackingRequest request) {
        eventTrackingService.track(request);
        return ResponseEntity.accepted().build();
    }
}
