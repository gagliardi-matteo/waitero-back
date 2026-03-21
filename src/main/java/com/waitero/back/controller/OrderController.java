package com.waitero.back.controller;

import com.waitero.back.dto.OrdineDTO;
import com.waitero.back.dto.PaymentRequest;
import com.waitero.back.dto.RestaurantOrderRequest;
import com.waitero.back.service.JwtService;
import com.waitero.back.service.OrderStreamService;
import com.waitero.back.service.OrdineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrdineService ordineService;
    private final JwtService jwtService;
    private final OrderStreamService orderStreamService;

    @GetMapping("/active")
    public ResponseEntity<List<OrdineDTO>> getActiveOrders() {
        return ResponseEntity.ok(ordineService.getActiveOrdersForAuthenticatedRestaurant());
    }

    @GetMapping("/history")
    public ResponseEntity<List<OrdineDTO>> getHistoryOrders() {
        return ResponseEntity.ok(ordineService.getHistoryOrdersForAuthenticatedRestaurant());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrdineDTO> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(ordineService.getOrderForAuthenticatedRestaurant(id));
    }

    @PostMapping("/manual")
    public ResponseEntity<OrdineDTO> createManualOrder(@RequestBody RestaurantOrderRequest request) {
        return ResponseEntity.ok(ordineService.createOrAppendByRestaurant(request));
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<OrdineDTO> payOrder(@PathVariable Long id, @RequestBody(required = false) PaymentRequest request) {
        return ResponseEntity.ok(ordineService.payOrder(id, request));
    }

    @GetMapping("/stream")
    public SseEmitter stream(@RequestParam String token) {
        if (!jwtService.validateToken(token)) {
            throw new RuntimeException("Token non valido");
        }

        Long restaurantId = jwtService.extractUserId(token);
        return orderStreamService.subscribe(restaurantId);
    }
}
