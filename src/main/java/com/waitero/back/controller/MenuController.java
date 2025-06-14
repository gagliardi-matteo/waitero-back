package com.waitero.back.controller;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.CategoriaPiatto;
import com.waitero.back.entity.Piatto;
import com.waitero.back.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/categorie")
    public List<CategoriaPiatto> getCategorie() {
        return menuService.getCategorie();
    }

    @PostMapping("/categorie")
    public CategoriaPiatto creaCategoria(@RequestBody Map<String, String> payload) {
        return menuService.creaCategoria(payload.get("nome"));
    }

    @GetMapping("/piatti")
    public List<PiattoDTO> getPiatti() {
        return menuService.getPiatti()
                .stream()
                .map(menuService::toDTO)
                .toList();
    }

    @PostMapping("/piatti")
    public PiattoDTO creaPiatto(@RequestBody PiattoDTO dto) {
        Piatto entity = menuService.fromDTO(dto);
        return menuService.toDTO(menuService.creaPiatto(entity));
    }

    @PutMapping("/piatti/{id}")
    public Piatto aggiornaPiatto(@PathVariable Long id, @RequestBody Piatto piatto) {
        return menuService.aggiornaPiatto(id, piatto);
    }

    @DeleteMapping("/piatti/{id}")
    public void eliminaPiatto(@PathVariable Long id) {
        menuService.eliminaPiatto(id);
    }

    @GetMapping("/piattiRistoratore/{id}")
    public List<PiattoDTO> getPiattiByRistoratore(@PathVariable Long id) {
        return menuService.getPiattiByRistoratore(id)
                .stream()
                .map(menuService::toDTO)
                .toList();
    }
}

