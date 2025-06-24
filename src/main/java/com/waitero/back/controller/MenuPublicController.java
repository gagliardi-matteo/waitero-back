package com.waitero.back.controller;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.service.JwtService;
import com.waitero.back.service.MenuService;
import com.waitero.back.service.RistoratoreService;
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
    private final JwtService jwtService;

    @GetMapping("/menu/piatti/{id}")
    public List<PiattoDTO> getPiatti(@PathVariable Long id) {
        return menuService.getPiattiByRistoratore(id)
                .stream()
                .map(menuService::toDTO)
                .toList();
    }

    @PostMapping("/validate-token")
    public ResponseEntity<?> validateToken(@RequestBody QrTokenRequest request) {
        boolean valid = jwtService.validateQrToken(
                request.token(),
                request.restaurantId(),
                request.tableId()
        );
        return ResponseEntity.ok(Collections.singletonMap("valid", valid));
    }


}
