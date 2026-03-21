package com.waitero.back.controller;

import com.waitero.back.dto.AddressSuggestionDTO;
import com.waitero.back.dto.PublicRestaurantDTO;
import com.waitero.back.dto.RestaurantSettingsDTO;
import com.waitero.back.dto.RestaurantSettingsRequest;
import com.waitero.back.service.GeocodingService;
import com.waitero.back.service.RistoratoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RistoranteController {

    private final RistoratoreService ristoratoreService;
    private final GeocodingService geocodingService;

    @GetMapping("/api/customer/ristorante/{id}")
    public ResponseEntity<PublicRestaurantDTO> getRistorante(@PathVariable Long id) {
        return ristoratoreService.findPublicRestaurantById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/restaurant/settings")
    public ResponseEntity<RestaurantSettingsDTO> getRestaurantSettings() {
        return ResponseEntity.ok(ristoratoreService.getAuthenticatedRestaurantSettings());
    }

    @PutMapping("/api/restaurant/settings")
    public ResponseEntity<RestaurantSettingsDTO> updateRestaurantSettings(@RequestBody RestaurantSettingsRequest request) {
        return ResponseEntity.ok(ristoratoreService.updateAuthenticatedRestaurantSettings(request));
    }

    @GetMapping("/api/restaurant/address-search")
    public ResponseEntity<List<AddressSuggestionDTO>> searchAddress(
            @RequestParam String q,
            @RequestParam(required = false) String city
    ) {
        return ResponseEntity.ok(geocodingService.searchSuggestions(q, city));
    }
}
