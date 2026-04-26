package com.waitero.back.service;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.Categoria;
import com.waitero.back.entity.Piatto;
import com.waitero.back.repository.IngredienteRepository;
import com.waitero.back.repository.PiattoIngredienteRepository;
import com.waitero.back.repository.PiattoIngredienteRistoratoreRepository;
import com.waitero.back.repository.PiattoRepository;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.repository.ServiceHourRepository;
import com.waitero.back.security.AccessContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private PiattoRepository piattoRepo;

    @Mock
    private RistoratoreRepository ristoratoreRepo;

    @Mock
    private ServiceHourRepository serviceHourRepository;

    @Mock
    private DishNormalizationService dishNormalizationService;

    @Mock
    private PiattoIngredienteRepository piattoIngredienteRepository;

    @Mock
    private PiattoIngredienteRistoratoreRepository piattoIngredienteRistoratoreRepository;

    @Mock
    private IngredienteRepository ingredienteRepository;

    @Mock
    private AccessContextService accessContextService;

    @InjectMocks
    private MenuService menuService;

    @Test
    void shouldPreserveDecisionOwnedFieldsWhenUpdatingDishData() {
        Piatto entity = Piatto.builder()
                .id(10L)
                .nome("Carbonara")
                .descrizione("Prima")
                .prezzo(new BigDecimal("12.00"))
                .categoria(Categoria.PRIMO)
                .disponibile(true)
                .consigliato(false)
                .build();

        PiattoDTO incoming = new PiattoDTO();
        incoming.setNome("Carbonara Premium");
        incoming.setDescrizione("Nuova descrizione");
        incoming.setPrezzo(new BigDecimal("14.00"));
        incoming.setCategoria(Categoria.PRIMO.name());
        incoming.setDisponibile(false);
        incoming.setConsigliato(true);

        assertTrue(menuService.isDecisionFieldUpdateRequested(entity, incoming));

        menuService.updateFromDTO(entity, incoming);

        assertTrue(entity.getDisponibile());
        assertFalse(entity.getConsigliato());
    }

    @Test
    void shouldForceNeutralDecisionStateWhenCreatingDishFromDto() {
        PiattoDTO incoming = new PiattoDTO();
        incoming.setId(99L);
        incoming.setNome("Nuovo piatto");
        incoming.setDescrizione("Descrizione");
        incoming.setPrezzo(new BigDecimal("8.00"));
        incoming.setCategoria(Categoria.SECONDO.name());
        incoming.setDisponibile(false);
        incoming.setConsigliato(true);

        Piatto created = menuService.fromDTO(incoming);

        assertTrue(created.getDisponibile());
        assertFalse(created.getConsigliato());
    }
}
