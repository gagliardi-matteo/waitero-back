package com.waitero.back.controller;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.service.RistoratoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customer/ristorante")
@RequiredArgsConstructor
public class RistoranteController {

    private final RistoratoreService ristoratoreService;

    @GetMapping("/{id}")
    public ResponseEntity<Ristoratore> getRistorante(@PathVariable Long id) {
        return ristoratoreService.findRistoratoreById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

}
