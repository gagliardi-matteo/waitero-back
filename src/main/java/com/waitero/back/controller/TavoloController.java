package com.waitero.back.controller;

import com.waitero.back.dto.TavoloDTO;
import com.waitero.back.dto.TavoloRequest;
import com.waitero.back.dto.BulkTableCreateRequest;
import com.waitero.back.service.TavoloService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TavoloController {

    private final TavoloService tavoloService;

    @GetMapping
    public List<TavoloDTO> getTables() {
        return tavoloService.getAuthenticatedRestaurantTables();
    }

    @PostMapping
    public ResponseEntity<TavoloDTO> createTable(@RequestBody TavoloRequest request) {
        return ResponseEntity.ok(tavoloService.createForAuthenticatedRestaurant(request));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<TavoloDTO>> bulkCreateTables(@RequestBody BulkTableCreateRequest request) {
        return ResponseEntity.ok(tavoloService.bulkCreateForAuthenticatedRestaurant(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TavoloDTO> updateTable(@PathVariable Long id, @RequestBody TavoloRequest request) {
        return ResponseEntity.ok(tavoloService.updateForAuthenticatedRestaurant(id, request));
    }

    @PostMapping("/{id}/regenerate-token")
    public ResponseEntity<TavoloDTO> regenerateToken(@PathVariable Long id) {
        return ResponseEntity.ok(tavoloService.regenerateQrTokenForAuthenticatedRestaurant(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTable(@PathVariable Long id) {
        tavoloService.deleteForAuthenticatedRestaurant(id);
        return ResponseEntity.noContent().build();
    }
}
