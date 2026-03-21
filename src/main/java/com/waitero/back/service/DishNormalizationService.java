package com.waitero.back.service;

import com.waitero.back.dto.LLMIngredientResponse;
import com.waitero.back.dto.LLMResponse;
import com.waitero.back.entity.AliasPiatto;
import com.waitero.back.entity.Ingrediente;
import com.waitero.back.entity.PiattoCanonicale;
import com.waitero.back.entity.PiattoIngrediente;
import com.waitero.back.repository.AliasPiattoRepository;
import com.waitero.back.repository.IngredienteRepository;
import com.waitero.back.repository.PiattoCanonicaleRepository;
import com.waitero.back.repository.PiattoIngredienteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DishNormalizationService {

    private final AliasPiattoRepository aliasPiattoRepository;
    private final PiattoCanonicaleRepository piattoCanonicaleRepository;
    private final IngredienteRepository ingredienteRepository;
    private final PiattoIngredienteRepository piattoIngredienteRepository;
    private final LLMService llmService;

    @Transactional
    public PiattoCanonicale normalizeDish(String nomePiatto) {
        String normalizedDishName = normalizeRequiredText(nomePiatto, "Il nome del piatto e obbligatorio");

        AliasPiatto existingAlias = aliasPiattoRepository.findByNomeOriginaleIgnoreCase(normalizedDishName)
                .orElse(null);
        if (existingAlias != null) {
            return existingAlias.getPiattoCanonicale();
        }

        LLMResponse llmResponse;
        try {
            llmResponse = llmService.extract(normalizedDishName);
        } catch (RuntimeException ex) {
            log.error("Dish normalization fallback for {}", normalizedDishName, ex);
            llmResponse = LLMResponse.builder()
                    .nomeCanonico(toCanonicalFallbackName(normalizedDishName))
                    .categoria("altro")
                    .build();
        }

        String canonicalName = normalizeRequiredText(llmResponse.getNomeCanonico(), "LLM nome canonico mancante");
        String category = normalizeRequiredText(llmResponse.getCategoria(), "LLM categoria mancante");

        PiattoCanonicale piattoCanonicale = piattoCanonicaleRepository
                .findByNomeCanonicoIgnoreCaseAndCategoriaIgnoreCase(canonicalName, category)
                .orElseGet(() -> piattoCanonicaleRepository.save(
                        PiattoCanonicale.builder()
                                .nomeCanonico(canonicalName)
                                .categoria(category)
                                .build()
                ));

        if (llmResponse.getIngredienti() != null) {
            for (LLMIngredientResponse ingredientResponse : llmResponse.getIngredienti()) {
                String ingredientName = normalizeOptionalText(ingredientResponse.getNome());
                if (ingredientName == null) {
                    continue;
                }

                Ingrediente ingrediente = ingredienteRepository.findByNomeIgnoreCase(ingredientName)
                        .orElseGet(() -> ingredienteRepository.save(
                                Ingrediente.builder()
                                        .nome(ingredientName)
                                        .categoria(normalizeOptionalText(ingredientResponse.getCategoria()))
                                        .build()
                        ));

                PiattoIngrediente relation = piattoIngredienteRepository
                        .findByPiattoCanonicaleIdAndIngredienteId(piattoCanonicale.getId(), ingrediente.getId())
                        .orElseGet(() -> PiattoIngrediente.builder()
                                .piattoCanonicale(piattoCanonicale)
                                .ingrediente(ingrediente)
                                .build());

                relation.setGrammi(ingredientResponse.getGrammi());
                piattoIngredienteRepository.save(relation);
            }
        }

        aliasPiattoRepository.save(
                AliasPiatto.builder()
                        .nomeOriginale(normalizedDishName)
                        .piattoCanonicale(piattoCanonicale)
                        .build()
        );

        return piattoCanonicale;
    }

    private String normalizeRequiredText(String value, String errorMessage) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        return normalized;
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String toCanonicalFallbackName(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
