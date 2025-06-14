package com.waitero.back.service;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.CategoriaPiatto;
import com.waitero.back.entity.Piatto;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.repository.CategoriaPiattoRepository;
import com.waitero.back.repository.PiattoRepository;
import com.waitero.back.repository.RistoratoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final CategoriaPiattoRepository categoriaRepo;
    private final PiattoRepository piattoRepo;
    private final RistoratoreRepository ristoratoreRepo;

    private Ristoratore getRistoratoreAutenticato() {
        Long id = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return ristoratoreRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Ristoratore non trovato"));
    }

    public List<CategoriaPiatto> getCategorie() {
        Ristoratore ristoratore = getRistoratoreAutenticato();
        return categoriaRepo.findAllByRistoratoreId(ristoratore.getId());
    }

    public CategoriaPiatto creaCategoria(String nome) {
        Ristoratore ristoratore = getRistoratoreAutenticato();
        CategoriaPiatto c = new CategoriaPiatto();
        c.setNome(nome);
        c.setRistoratore(ristoratore);
        return categoriaRepo.save(c);
    }

    public List<Piatto> getPiatti() {
        Ristoratore ristoratore = getRistoratoreAutenticato();
        return piattoRepo.findAllByRistoratoreId(ristoratore.getId());
    }

    public List<Piatto> getPiattiByRistoratore(Long id){
        return piattoRepo.findAllByRistoratoreId(id);
    }

    public Piatto creaPiatto(Piatto piatto) {
        Ristoratore ristoratore = getRistoratoreAutenticato();
        piatto.setRistoratore(ristoratore);
        return piattoRepo.save(piatto);
    }

    public Piatto aggiornaPiatto(Long id, Piatto nuovo) {
        Piatto esistente = piattoRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Piatto non trovato"));
        esistente.setNome(nuovo.getNome());
        esistente.setDescrizione(nuovo.getDescrizione());
        esistente.setPrezzo(nuovo.getPrezzo());
        esistente.setDisponibile(nuovo.getDisponibile());
        esistente.setCategoria(nuovo.getCategoria());
        return piattoRepo.save(esistente);
    }

    public void eliminaPiatto(Long id) {
        piattoRepo.deleteById(id);
    }

    public PiattoDTO toDTO(Piatto piatto) {
        PiattoDTO dto = new PiattoDTO();
        dto.setId(piatto.getId());
        dto.setNome(piatto.getNome());
        dto.setDescrizione(piatto.getDescrizione());
        dto.setPrezzo(piatto.getPrezzo());
        dto.setDisponibile(piatto.getDisponibile());
        dto.setCategoriaId(piatto.getCategoria().getId());
        return dto;
    }

    public Piatto fromDTO(PiattoDTO dto) {
        Piatto p = new Piatto();
        p.setId(dto.getId());
        p.setNome(dto.getNome());
        p.setDescrizione(dto.getDescrizione());
        p.setPrezzo(dto.getPrezzo());
        p.setDisponibile(dto.getDisponibile());
        CategoriaPiatto categoria = categoriaRepo.findById(dto.getCategoriaId())
                .orElseThrow(() -> new RuntimeException("Categoria non trovata"));
        p.setCategoria(categoria);
        return p;
    }


}
