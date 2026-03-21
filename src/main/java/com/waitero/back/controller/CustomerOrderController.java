package com.waitero.back.controller;

import com.waitero.back.dto.CustomerDraftDTO;
import com.waitero.back.dto.CustomerDraftMutationRequest;
import com.waitero.back.dto.CustomerOrderRequest;
import com.waitero.back.dto.OrdineDTO;
import com.waitero.back.service.CustomerDraftService;
import com.waitero.back.service.OrderStreamService;
import com.waitero.back.service.OrdineService;
import com.waitero.back.service.TavoloService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/customer/orders")
@RequiredArgsConstructor
public class CustomerOrderController {

    private final OrdineService ordineService;
    private final CustomerDraftService customerDraftService;
    private final OrderStreamService orderStreamService;
    private final TavoloService tavoloService;

    @PostMapping
    public ResponseEntity<OrdineDTO> createOrAppend(@RequestBody CustomerOrderRequest request) {
        return ResponseEntity.ok(ordineService.createOrAppend(request));
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentOrder(
            @RequestParam String token,
            @RequestParam String restaurantId,
            @RequestParam Integer tableId,
            @RequestParam String deviceId,
            @RequestParam(required = false) String fingerprint
    ) {
        return ordineService.getCurrentCustomerOrder(token, restaurantId, tableId, deviceId, fingerprint)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "Nessun ordine attivo")));
    }

    @GetMapping("/draft")
    public ResponseEntity<CustomerDraftDTO> getDraft(
            @RequestParam String token,
            @RequestParam String restaurantId,
            @RequestParam Integer tableId,
            @RequestParam String deviceId,
            @RequestParam(required = false) String fingerprint
    ) {
        return ResponseEntity.ok(customerDraftService.getDraft(token, restaurantId, tableId, deviceId, fingerprint));
    }

    @PostMapping("/draft/items")
    public ResponseEntity<CustomerDraftDTO> mutateDraft(@RequestBody CustomerDraftMutationRequest request) {
        return ResponseEntity.ok(customerDraftService.mutate(request));
    }

    @GetMapping("/stream")
    public SseEmitter stream(
            @RequestParam String token,
            @RequestParam String restaurantId,
            @RequestParam Integer tableId,
            @RequestParam String deviceId,
            @RequestParam(required = false) String fingerprint
    ) {
        if (!tavoloService.validateCustomerAccess(token, restaurantId, tableId, deviceId, fingerprint)) {
            throw new RuntimeException("Accesso tavolo non autorizzato");
        }

        return orderStreamService.subscribeCustomerTable(Long.parseLong(restaurantId), tableId);
    }
}
