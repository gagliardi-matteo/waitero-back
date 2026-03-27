package com.waitero.back.controller;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.Piatto;
import com.waitero.back.service.MenuService;
import com.waitero.back.service.RistoratoreService;
import com.waitero.back.service.TavoloService;
import com.waitero.back.service.UpsellService;
import com.waitero.back.util.QrTokenRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class MenuPublicController {

    private final MenuService menuService;
    private final RistoratoreService ristoratoreService;
    private final TavoloService tavoloService;
    private final UpsellService upsellService;

    @GetMapping("/menu/piatti/{id}")
    public List<PiattoDTO> getPiatti(@PathVariable Long id) {
        return menuService.getPublicPiattiByRistoratore(id)
                .stream()
                .map(menuService::toDTO)
                .toList();
    }

    @GetMapping("/dettaglio-piatto/{id}")
    public PiattoDTO getDettaglioPiatto(@PathVariable Long id){
        Piatto piatto = menuService.getPublicPiattoById(id);
        return menuService.toDTO(piatto);
    }

    @GetMapping("/upsell/{dishId}")
    public List<PiattoDTO> getUpsellSuggestions(@PathVariable Long dishId, @RequestParam Long restaurantId) {
        return upsellService.getUpsellSuggestions(dishId, restaurantId)
                .stream()
                .map(menuService::toDTO)
                .toList();
    }

    @PostMapping("/validate-token")
    public ResponseEntity<?> validateToken(@RequestBody QrTokenRequest request) {
        boolean valid = tavoloService.validateQrAccess(
                request.token(),
                request.restaurantId(),
                request.tableId()
        );
        return ResponseEntity.ok(Collections.singletonMap("valid", valid));
    }
}
