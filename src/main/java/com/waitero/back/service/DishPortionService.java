package com.waitero.back.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.back.dto.PiattoPortionDTO;
import com.waitero.back.entity.Piatto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DishPortionService {

    public static final String DEFAULT_PORTION_KEY = "default";
    public static final String DEFAULT_PORTION_LABEL = "Standard";

    private final ObjectMapper objectMapper;

    public List<PiattoPortionDTO> readConfiguredPortions(Piatto piatto) {
        if (piatto == null || piatto.getPortionOptionsJson() == null || piatto.getPortionOptionsJson().isBlank()) {
            return List.of();
        }

        try {
            List<PiattoPortionDTO> parsed = objectMapper.readValue(
                    piatto.getPortionOptionsJson(),
                    new TypeReference<List<PiattoPortionDTO>>() {}
            );
            return normalizeConfiguredPortions(parsed);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Configurazione porzioni non valida", ex);
        }
    }

    public List<PiattoPortionDTO> normalizeConfiguredPortions(List<PiattoPortionDTO> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }

        List<PiattoPortionDTO> normalized = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();

        for (PiattoPortionDTO raw : requested) {
            if (raw == null) {
                continue;
            }

            String label = normalizeLabel(raw.getLabel());
            BigDecimal price = normalizePrice(raw.getPrice());
            if (label == null || price == null) {
                continue;
            }

            String key = buildUniqueKey(raw.getKey(), label, usedKeys);
            usedKeys.add(key);

            PiattoPortionDTO dto = new PiattoPortionDTO();
            dto.setKey(key);
            dto.setLabel(label);
            dto.setPrice(price);
            normalized.add(dto);
        }

        return normalized;
    }

    public String serializeConfiguredPortions(List<PiattoPortionDTO> portions) {
        List<PiattoPortionDTO> normalized = normalizeConfiguredPortions(portions);
        if (normalized.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Impossibile serializzare le porzioni", ex);
        }
    }

    public PiattoPortionDTO resolvePortion(Piatto piatto, String requestedKey) {
        List<PiattoPortionDTO> configured = readConfiguredPortions(piatto);
        if (!configured.isEmpty()) {
            if (requestedKey == null || requestedKey.isBlank()) {
                return clonePortion(configured.get(0));
            }

            return configured.stream()
                    .filter(portion -> Objects.equals(portion.getKey(), requestedKey.trim()))
                    .findFirst()
                    .map(this::clonePortion)
                    .orElseThrow(() -> new RuntimeException("Porzione non valida per il piatto selezionato"));
        }

        return defaultPortionFor(piatto);
    }

    public PiattoPortionDTO defaultPortionFor(Piatto piatto) {
        if (piatto == null || piatto.getPrezzo() == null) {
            throw new RuntimeException("Prezzo piatto non disponibile");
        }

        PiattoPortionDTO dto = new PiattoPortionDTO();
        dto.setKey(DEFAULT_PORTION_KEY);
        dto.setLabel(DEFAULT_PORTION_LABEL);
        dto.setPrice(normalizePrice(piatto.getPrezzo()));
        return dto;
    }

    private PiattoPortionDTO clonePortion(PiattoPortionDTO portion) {
        PiattoPortionDTO dto = new PiattoPortionDTO();
        dto.setKey(portion.getKey());
        dto.setLabel(portion.getLabel());
        dto.setPrice(portion.getPrice());
        return dto;
    }

    private String buildUniqueKey(String explicitKey, String label, Set<String> usedKeys) {
        String baseKey = normalizeKey(explicitKey);
        if (baseKey == null) {
            baseKey = normalizeKey(label);
        }
        if (baseKey == null) {
            baseKey = "portion";
        }

        String candidate = baseKey;
        int index = 2;
        while (usedKeys.contains(candidate)) {
            candidate = baseKey + "-" + index;
            index += 1;
        }
        return candidate;
    }

    private String normalizeLabel(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }

    private BigDecimal normalizePrice(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }
}
