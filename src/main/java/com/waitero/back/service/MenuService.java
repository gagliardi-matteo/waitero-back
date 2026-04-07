package com.waitero.back.service;

import com.waitero.back.dto.IngredienteDTO;
import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.Categoria;
import com.waitero.back.entity.Ingrediente;
import com.waitero.back.entity.Piatto;
import com.waitero.back.entity.PiattoCanonicale;
import com.waitero.back.entity.PiattoIngrediente;
import com.waitero.back.entity.PiattoIngredienteRistoratore;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.entity.ServiceHour;
import com.waitero.back.repository.IngredienteRepository;
import com.waitero.back.repository.PiattoIngredienteRepository;
import com.waitero.back.repository.PiattoIngredienteRistoratoreRepository;
import com.waitero.back.repository.PiattoRepository;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.repository.ServiceHourRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MenuService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Europe/Rome");

    private final PiattoRepository piattoRepo;
    private final RistoratoreRepository ristoratoreRepo;
    private final ServiceHourRepository serviceHourRepository;
    private final DishNormalizationService dishNormalizationService;
    private final PiattoIngredienteRepository piattoIngredienteRepository;
    private final PiattoIngredienteRistoratoreRepository piattoIngredienteRistoratoreRepository;
    private final IngredienteRepository ingredienteRepository;

    private Ristoratore getRistoratoreAutenticato() {
        Long id = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return ristoratoreRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Ristoratore non trovato"));
    }

    public List<Piatto> getPiatti() {
        Ristoratore ristoratore = getRistoratoreAutenticato();
        return piattoRepo.findAllByRistoratoreIdWithCanonical(ristoratore.getId());
    }

    public Piatto getPiattoById(Long id) {
        return piattoRepo.findByIdWithCanonical(id).orElseThrow(() -> new RuntimeException("Piatto non trovato"));
    }

    public List<Piatto> getPiattiByRistoratore(Long id) {
        return piattoRepo.findAllByRistoratoreIdWithCanonical(id);
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

    @Transactional
    public Piatto creaPiatto(Piatto piatto) {
        Ristoratore ristoratore = getRistoratoreAutenticato();
        piatto.setRistoratore(ristoratore);
        piatto.setPiattoCanonicale(dishNormalizationService.normalizeDish(piatto.getNome()));
        if (piatto.getConsigliato() == null) {
            piatto.setConsigliato(false);
        }
        Piatto saved = piattoRepo.save(piatto);
        syncDishIngredients(saved, normalizeText(piatto.getIngredienti()));
        return piattoRepo.save(saved);
    }

    public void updateFromDTO(Piatto entity, PiattoDTO dto) {
        entity.setNome(dto.getNome());
        entity.setDescrizione(dto.getDescrizione());
        entity.setPrezzo(dto.getPrezzo());
        entity.setDisponibile(dto.getDisponibile());
        entity.setCategoria(Categoria.valueOf(dto.getCategoria()));
        entity.setIngredienti(normalizeText(dto.getIngredienti()));
        entity.setAllergeni(normalizeText(dto.getAllergeni()));
        entity.setConsigliato(Boolean.TRUE.equals(dto.getConsigliato()));
    }

    @Transactional
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
        esistente.setConsigliato(Boolean.TRUE.equals(nuovo.getConsigliato()));
        syncDishIngredients(esistente, esistente.getIngredienti());
        return piattoRepo.save(esistente);
    }

    @Transactional
    public void eliminaPiatto(Long id) {
        Ristoratore ristoratore = getRistoratoreAutenticato();
        Piatto piatto = piattoRepo.findByIdAndRistoratoreId(id, ristoratore.getId())
                .orElseThrow(() -> new RuntimeException("Piatto non trovato o non associato al ristoratore autenticato"));
        piattoIngredienteRistoratoreRepository.deleteAllByPiattoId(piatto.getId());
        piattoRepo.delete(piatto);
    }

    public PiattoDTO toDTO(Piatto piatto) {
        return toDTO(piatto, Map.of(), Collections.emptyMap());
    }

    public List<PiattoDTO> toDTOList(List<Piatto> piatti) {
        if (piatti == null || piatti.isEmpty()) {
            return List.of();
        }

        Map<Long, List<IngredienteDTO>> structuredIngredientsByDishId = loadStructuredIngredientsByDishId(piatti);
        Map<Long, List<IngredienteDTO>> canonicalIngredientsByCanonicalDishId = loadCanonicalIngredientsByCanonicalDishId(piatti);

        return piatti.stream()
                .map(piatto -> toDTO(piatto, structuredIngredientsByDishId, canonicalIngredientsByCanonicalDishId))
                .toList();
    }

    private PiattoDTO toDTO(
            Piatto piatto,
            Map<Long, List<IngredienteDTO>> structuredIngredientsByDishId,
            Map<Long, List<IngredienteDTO>> canonicalIngredientsByCanonicalDishId
    ) {
        PiattoDTO dto = new PiattoDTO();
        dto.setId(piatto.getId());
        dto.setNome(piatto.getNome());
        dto.setDescrizione(piatto.getDescrizione());
        dto.setPrezzo(piatto.getPrezzo());
        dto.setDisponibile(piatto.getDisponibile());
        dto.setCategoria(String.valueOf(piatto.getCategoria()));
        dto.setImageUrl(piatto.getImageUrl());
        List<IngredienteDTO> structuredIngredients = resolveStructuredIngredients(
                piatto,
                structuredIngredientsByDishId,
                canonicalIngredientsByCanonicalDishId
        );
        dto.setIngredienti(buildIngredientDisplay(structuredIngredients, piatto.getIngredienti()));
        dto.setIngredientiStrutturati(structuredIngredients);
        dto.setAllergeni(piatto.getAllergeni());
        dto.setConsigliato(Boolean.TRUE.equals(piatto.getConsigliato()));
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
        p.setConsigliato(Boolean.TRUE.equals(dto.getConsigliato()));
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

    private void syncDishIngredients(Piatto piatto, String ingredientiText) {
        List<IngredienteDTO> requestedIngredients = hasText(ingredientiText)
                ? parseIngredientText(ingredientiText)
                : canonicalIngredientsForDish(piatto.getPiattoCanonicale());

        piattoIngredienteRistoratoreRepository.deleteAllByPiattoId(piatto.getId());
        if (requestedIngredients.isEmpty()) {
            piatto.setIngredienti(null);
            return;
        }

        List<PiattoIngredienteRistoratore> relations = new ArrayList<>();
        for (IngredienteDTO requestedIngredient : requestedIngredients) {
            String ingredientName = normalizeText(requestedIngredient.getNome());
            if (ingredientName == null) {
                continue;
            }

            Ingrediente ingrediente = ingredienteRepository.findByNomeIgnoreCase(ingredientName)
                    .orElseGet(() -> ingredienteRepository.save(
                            Ingrediente.builder()
                                    .nome(ingredientName)
                                    .categoria(normalizeText(requestedIngredient.getCategoria()))
                                    .build()
                    ));

            relations.add(PiattoIngredienteRistoratore.builder()
                    .piatto(piatto)
                    .ingrediente(ingrediente)
                    .grammi(requestedIngredient.getGrammi())
                    .build());
        }

        piattoIngredienteRistoratoreRepository.saveAll(relations);
        piatto.setIngredienti(buildIngredientDisplay(toIngredienteDTOs(relations), ingredientiText));
    }

    private List<IngredienteDTO> resolveStructuredIngredients(
            Piatto piatto,
            Map<Long, List<IngredienteDTO>> structuredIngredientsByDishId,
            Map<Long, List<IngredienteDTO>> canonicalIngredientsByCanonicalDishId
    ) {
        List<IngredienteDTO> preloadedRestaurantIngredients = structuredIngredientsByDishId.get(piatto.getId());
        if (preloadedRestaurantIngredients != null && !preloadedRestaurantIngredients.isEmpty()) {
            return preloadedRestaurantIngredients;
        }

        if (structuredIngredientsByDishId.isEmpty() && canonicalIngredientsByCanonicalDishId.isEmpty()) {
            List<PiattoIngredienteRistoratore> restaurantRelations = piattoIngredienteRistoratoreRepository.findAllByPiattoIdOrderByIngredienteNomeAsc(piatto.getId());
            if (!restaurantRelations.isEmpty()) {
                return toIngredienteDTOs(restaurantRelations);
            }
            return canonicalIngredientsForDish(piatto.getPiattoCanonicale());
        }

        Long canonicalDishId = piatto.getPiattoCanonicale() != null ? piatto.getPiattoCanonicale().getId() : null;
        if (canonicalDishId == null) {
            return List.of();
        }
        return canonicalIngredientsByCanonicalDishId.getOrDefault(canonicalDishId, List.of());
    }

    private Map<Long, List<IngredienteDTO>> loadStructuredIngredientsByDishId(List<Piatto> piatti) {
        List<Long> dishIds = piatti.stream()
                .map(Piatto::getId)
                .filter(Objects::nonNull)
                .toList();
        if (dishIds.isEmpty()) {
            return Map.of();
        }

        return piattoIngredienteRistoratoreRepository.findAllByPiattoIdInOrderByPiattoIdAscIngredienteNomeAsc(dishIds)
                .stream()
                .collect(Collectors.groupingBy(
                        relation -> relation.getPiatto().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(relation -> IngredienteDTO.builder()
                                .nome(relation.getIngrediente().getNome())
                                .categoria(relation.getIngrediente().getCategoria())
                                .grammi(relation.getGrammi())
                                .build(), Collectors.toList())
                ));
    }

    private Map<Long, List<IngredienteDTO>> loadCanonicalIngredientsByCanonicalDishId(List<Piatto> piatti) {
        List<Long> canonicalDishIds = piatti.stream()
                .map(Piatto::getPiattoCanonicale)
                .filter(Objects::nonNull)
                .map(PiattoCanonicale::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (canonicalDishIds.isEmpty()) {
            return Map.of();
        }

        return piattoIngredienteRepository.findAllByPiattoCanonicaleIdInOrderByPiattoCanonicaleIdAscIngredienteNomeAsc(canonicalDishIds)
                .stream()
                .collect(Collectors.groupingBy(
                        relation -> relation.getPiattoCanonicale().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(this::toIngredienteDTO, Collectors.toList())
                ));
    }

    private List<IngredienteDTO> resolveStructuredIngredients(Piatto piatto) {
        List<PiattoIngredienteRistoratore> restaurantRelations = piattoIngredienteRistoratoreRepository.findAllByPiattoIdOrderByIngredienteNomeAsc(piatto.getId());
        if (!restaurantRelations.isEmpty()) {
            return toIngredienteDTOs(restaurantRelations);
        }
        return canonicalIngredientsForDish(piatto.getPiattoCanonicale());
    }

    private List<IngredienteDTO> canonicalIngredientsForDish(PiattoCanonicale piattoCanonicale) {
        if (piattoCanonicale == null || piattoCanonicale.getId() == null) {
            return List.of();
        }
        return piattoIngredienteRepository.findAllByPiattoCanonicaleIdOrderByIngredienteNomeAsc(piattoCanonicale.getId())
                .stream()
                .map(this::toIngredienteDTO)
                .collect(Collectors.toList());
    }

    private IngredienteDTO toIngredienteDTO(PiattoIngrediente relation) {
        return IngredienteDTO.builder()
                .nome(relation.getIngrediente().getNome())
                .categoria(relation.getIngrediente().getCategoria())
                .grammi(relation.getGrammi())
                .build();
    }

    private List<IngredienteDTO> toIngredienteDTOs(List<PiattoIngredienteRistoratore> relations) {
        return relations.stream()
                .map(relation -> IngredienteDTO.builder()
                        .nome(relation.getIngrediente().getNome())
                        .categoria(relation.getIngrediente().getCategoria())
                        .grammi(relation.getGrammi())
                        .build())
                .collect(Collectors.toList());
    }

    private List<IngredienteDTO> parseIngredientText(String ingredientiText) {
        Map<String, IngredienteDTO> deduped = new LinkedHashMap<>();
        for (String rawValue : ingredientiText.split(",")) {
            String normalized = normalizeText(rawValue);
            if (normalized == null) {
                continue;
            }
            deduped.putIfAbsent(normalized.toLowerCase(), IngredienteDTO.builder()
                    .nome(normalized)
                    .build());
        }
        return new ArrayList<>(deduped.values());
    }

    private String buildIngredientDisplay(List<IngredienteDTO> ingredients, String fallbackValue) {
        if (ingredients != null && !ingredients.isEmpty()) {
            return ingredients.stream()
                    .map(IngredienteDTO::getNome)
                    .filter(this::hasText)
                    .collect(Collectors.joining(", "));
        }
        return normalizeText(fallbackValue);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
