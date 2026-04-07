package com.waitero.back.controller;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.Piatto;
import com.waitero.back.service.MenuIntelligenceService;
import com.waitero.back.service.MenuService;
import com.waitero.back.service.RistoratoreService;
import com.waitero.back.service.TavoloService;
import com.waitero.back.service.UpsellService;
import com.waitero.back.util.QrTokenRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class MenuPublicController {

    private final MenuService menuService;
    private final RistoratoreService ristoratoreService;
    private final TavoloService tavoloService;
    private final UpsellService upsellService;
    private final MenuIntelligenceService menuIntelligenceService;

    @GetMapping("/menu/piatti/{id}")
    public List<PiattoDTO> getPiatti(@PathVariable Long id) {
        Map<Long, MenuIntelligenceService.DishSignal> signals = menuIntelligenceService.getDishSignals(id);
        return menuService.toDTOList(menuService.getPublicPiattiByRistoratore(id))
                .stream()
                .map(dto -> enrichWithSignal(dto, signals.get(dto.getId())))
                .toList();
    }

    @GetMapping("/dettaglio-piatto/{id}")
    public PiattoDTO getDettaglioPiatto(@PathVariable Long id){
        Piatto piatto = menuService.getPublicPiattoById(id);
        PiattoDTO dto = menuService.toDTO(piatto);
        MenuIntelligenceService.DishSignal signal = menuIntelligenceService.getDishSignals(piatto.getRistoratore().getId()).get(dto.getId());
        return enrichWithSignal(dto, signal);
    }

    @GetMapping("/upsell/{dishId}")
    public List<PiattoDTO> getUpsellSuggestions(@PathVariable Long dishId, @RequestParam Long restaurantId) {
        Map<Long, MenuIntelligenceService.DishSignal> signals = menuIntelligenceService.getDishSignals(restaurantId);
        return menuService.toDTOList(upsellService.getUpsellSuggestions(dishId, restaurantId))
                .stream()
                .map(dto -> enrichWithSignal(dto, signals.get(dto.getId())))
                .toList();
    }

    @GetMapping("/upsell/cart-suggestions")
    public List<PiattoDTO> getCartUpsellSuggestions(@RequestParam Long restaurantId, @RequestParam List<Long> dishIds) {
        Map<Long, MenuIntelligenceService.DishSignal> signals = menuIntelligenceService.getDishSignals(restaurantId);
        return menuService.toDTOList(upsellService.getCartUpsellSuggestions(dishIds, restaurantId))
                .stream()
                .map(dto -> enrichWithSignal(dto, signals.get(dto.getId())))
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

    private PiattoDTO enrichWithSignal(PiattoDTO dto, MenuIntelligenceService.DishSignal signal) {
        if (signal == null) {
            dto.setNumeroOrdini(0);
            dto.setViews(0L);
            dto.setClicks(0L);
            dto.setAddToCart(0L);
            dto.setViewToCartRate(BigDecimal.ZERO);
            dto.setViewToOrderRate(BigDecimal.ZERO);
            dto.setPerformanceLabel("stable");
            return dto;
        }

        dto.setNumeroOrdini(Math.toIntExact(signal.orderCount()));
        dto.setViews(signal.views());
        dto.setClicks(signal.clicks());
        dto.setAddToCart(signal.addToCart());
        dto.setViewToCartRate(signal.viewToCartRate());
        dto.setViewToOrderRate(signal.viewToOrderRate());
        dto.setPerformanceLabel(signal.performanceLabel());
        return dto;
    }
}
