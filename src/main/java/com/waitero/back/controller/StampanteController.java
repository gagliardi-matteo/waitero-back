package com.waitero.back.controller;

import com.waitero.back.dto.StampanteRequest;
import com.waitero.back.dto.StampanteResponse;
import com.waitero.back.service.StampanteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stampanti")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class StampanteController {

    private final StampanteService stampanteService;

    @PostMapping
    public ResponseEntity<StampanteResponse> create(@RequestBody StampanteRequest request) {
        return ResponseEntity.ok(stampanteService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<StampanteResponse> update(@PathVariable Long id, @RequestBody StampanteRequest request) {
        return ResponseEntity.ok(stampanteService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        stampanteService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<StampanteResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(stampanteService.findById(id));
    }

    @GetMapping("/ristorante/{ristoranteId}")
    public ResponseEntity<List<StampanteResponse>> findByRistorante(@PathVariable Long ristoranteId) {
        return ResponseEntity.ok(stampanteService.findByRistorante(ristoranteId));
    }

    @PatchMapping("/{id}/enable")
    public ResponseEntity<StampanteResponse> enable(@PathVariable Long id) {
        return ResponseEntity.ok(stampanteService.enable(id));
    }

    @PatchMapping("/{id}/disable")
    public ResponseEntity<StampanteResponse> disable(@PathVariable Long id) {
        return ResponseEntity.ok(stampanteService.disable(id));
    }

    @PostMapping("/{id}/test-print")
    public ResponseEntity<Void> testPrint(@PathVariable Long id) {
        stampanteService.testPrint(id);
        return ResponseEntity.accepted().build();
    }
}
