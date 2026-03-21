package com.waitero.back.controller;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.Piatto;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.service.MenuService;
import com.waitero.back.service.RistoratoreService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/menu")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MenuController {

    private final MenuService menuService;
    private final RistoratoreService ristoratoreService;

    @GetMapping("/piatti")
    public List<PiattoDTO> getPiatti() {
        return menuService.getPiatti()
                .stream()
                .map(menuService::toDTO)
                .toList();
    }

    @GetMapping("/piatto/{id}")
    public PiattoDTO getPiatto(@PathVariable Long id){

        Piatto piattoEntity = menuService.getPiattoById(id);
        return menuService.toDTO(piattoEntity);

    }

    @PostMapping(value = "/piatti/{id}", consumes = "multipart/form-data")
    public PiattoDTO creaPiatto(
            @PathVariable Long id,
            @RequestPart("dto") PiattoDTO dto,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) {
        Piatto entity = menuService.fromDTO(dto);
        Optional<Ristoratore> ristoratore = ristoratoreService.findRistoratoreById(id);
        if(ristoratore.isPresent()){
            entity.setRistoratore(ristoratore.get());
        }
        entity.setDisponibile(true);
        if (image != null && !image.isEmpty()) {
            String ext = FilenameUtils.getExtension(image.getOriginalFilename());
            String filename = UUID.randomUUID() + "." + ext;
            Path path = Paths.get("/app/uploads", filename);
            try {
                Files.copy(image.getInputStream(), path);
                entity.setImageUrl(filename); // salva solo il nome nel DB
            } catch (IOException e) {
                throw new RuntimeException("Errore salvataggio immagine", e);
            }
        }

        return menuService.toDTO(menuService.creaPiatto(entity));
    }

    @PutMapping("/piatti/{id}")
    public PiattoDTO aggiornaPiatto(@PathVariable Long id, @RequestBody PiattoDTO dto) {
        Piatto piatto = menuService.getPiattoById(id);
        menuService.updateFromDTO(piatto, dto);
        return menuService.toDTO(menuService.aggiornaPiatto(id, piatto));
    }

    @PutMapping(value = "/piatti/{id}/con-immagine", consumes = MULTIPART_FORM_DATA_VALUE)
    public PiattoDTO aggiornaPiattoConImmagine(
            @PathVariable Long id,
            @RequestPart("dto") PiattoDTO dto,
            @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        Piatto piatto = menuService.getPiattoById(id);
        menuService.updateFromDTO(piatto, dto);

        if (file != null && !file.isEmpty()) {
            String ext = FilenameUtils.getExtension(file.getOriginalFilename());
            String filename = UUID.randomUUID() + "." + ext;
            Path path = Paths.get("/app/uploads", filename);
            try {
                Files.copy(file.getInputStream(), path);
                piatto.setImageUrl(filename);
            } catch (IOException e) {
                throw new RuntimeException("Errore salvataggio immagine", e);
            }
        }

        return menuService.toDTO(menuService.aggiornaPiatto(id, piatto));
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

