package com.waitero.back.service;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.Categoria;
import com.waitero.back.entity.Piatto;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.entity.ServiceHour;
import com.waitero.back.repository.PiattoRepository;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.repository.ServiceHourRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MenuService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Europe/Rome");

    private final PiattoRepository piattoRepo;
    private final RistoratoreRepository ristoratoreRepo;
    private final ServiceHourRepository serviceHourRepository;

    private Ristoratore getRistoratoreAutenticato() {
        Long id = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return ristoratoreRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Ristoratore non trovato"));
    }

    public List<Piatto> getPiatti() {
        Ristoratore ristoratore = getRistoratoreAutenticato();
        return piattoRepo.findAllByRistoratoreId(ristoratore.getId());
    }

    public Piatto getPiattoById(Long id){
        return piattoRepo.findById(id).orElseThrow(() -> new RuntimeException("Piatto non trovato"));
    }

    public List<Piatto> getPiattiByRistoratore(Long id){
        return piattoRepo.findAllByRistoratoreId(id);
    }

    public List<Piatto> getPublicPiattiByRistoratore(Long id) {
        ensureRestaurantServiceOpen(id);
        return getPiattiByRistoratore(id);
    }

    public Piatto getPublicPiattoById(Long id) {
        Piatto piatto = getPiattoById(id);
        ensureRestaurantServiceOpen(piatto.getRistoratore().getId());
        return piatto;
    }

    public Piatto creaPiatto(Piatto piatto) {
        Ristoratore ristoratore = getRistoratoreAutenticato();
        piatto.setRistoratore(ristoratore);
        return piattoRepo.save(piatto);
    }

    public void updateFromDTO(Piatto entity, PiattoDTO dto) {
        entity.setNome(dto.getNome());
        entity.setDescrizione(dto.getDescrizione());
        entity.setPrezzo(dto.getPrezzo());
        entity.setDisponibile(dto.getDisponibile());
        entity.setCategoria(Categoria.valueOf(dto.getCategoria()));
        entity.setIngredienti(normalizeText(dto.getIngredienti()));
        entity.setAllergeni(normalizeText(dto.getAllergeni()));
    }

    public Piatto aggiornaPiatto(Long id, Piatto nuovo) {
        Piatto esistente = piattoRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Piatto non trovato"));
        esistente.setNome(nuovo.getNome());
        esistente.setDescrizione(nuovo.getDescrizione());
        esistente.setPrezzo(nuovo.getPrezzo());
        esistente.setDisponibile(nuovo.getDisponibile());
        esistente.setCategoria(nuovo.getCategoria());
        esistente.setIngredienti(normalizeText(nuovo.getIngredienti()));
        esistente.setAllergeni(normalizeText(nuovo.getAllergeni()));
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
        dto.setCategoria(String.valueOf(piatto.getCategoria()));
        dto.setImageUrl(piatto.getImageUrl());
        dto.setIngredienti(piatto.getIngredienti());
        dto.setAllergeni(piatto.getAllergeni());
        return dto;
    }

    public Piatto fromDTO(PiattoDTO dto) {
        Piatto p = new Piatto();
        p.setId(dto.getId());
        p.setNome(dto.getNome());
        p.setDescrizione(dto.getDescrizione());
        p.setPrezzo(dto.getPrezzo());
        p.setDisponibile(dto.getDisponibile());
        p.setCategoria(Categoria.valueOf(dto.getCategoria()));
        p.setImageUrl(dto.getImageUrl());
        p.setIngredienti(normalizeText(dto.getIngredienti()));
        p.setAllergeni(normalizeText(dto.getAllergeni()));
        return p;
    }

    public void ensureRestaurantServiceOpen(Long restaurantId) {
        ZonedDateTime now = ZonedDateTime.now(SERVICE_ZONE);
        List<ServiceHour> hours = serviceHourRepository.findAllByRistoratoreIdAndDayOfWeekOrderByStartTimeAsc(restaurantId, DayOfWeek.from(now));
        if (hours.isEmpty()) {
            return;
        }

        LocalTime currentTime = now.toLocalTime();
        boolean isOpen = hours.stream().anyMatch(slot -> !currentTime.isBefore(slot.getStartTime()) && !currentTime.isAfter(slot.getEndTime()));
        if (!isOpen) {
            throw new RuntimeException("Servizio non disponibile in questo orario");
        }
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
