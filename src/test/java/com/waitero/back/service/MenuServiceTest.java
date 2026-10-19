package com.waitero.back.service;

import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.BusinessType;
import com.waitero.back.entity.MenuCategory;
import com.waitero.back.entity.Piatto;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.repository.IngredienteRepository;
import com.waitero.back.repository.PiattoIngredienteRepository;
import com.waitero.back.repository.PiattoIngredienteRistoratoreRepository;
import com.waitero.back.repository.PiattoRepository;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.repository.ServiceHourRepository;
import com.waitero.back.security.AccessContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

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

    @Mock
    private MenuCategoryService menuCategoryService;

    @Mock
    private DishPortionService dishPortionService;

    @Mock
    private ServiceHourScheduleService serviceHourScheduleService;

    @InjectMocks
    private MenuService menuService;

    private Ristoratore restaurant;

    @BeforeEach
    void setUpAuthenticatedRestaurant() {
        restaurant = Ristoratore.builder().id(7L).build();
        org.mockito.Mockito.when(dishPortionService.normalizeConfiguredPortions(org.mockito.ArgumentMatchers.nullable(java.util.List.class)))
                .thenReturn(java.util.List.of());
    }

    @Test
    void shouldUpdateExplicitlyRequestedDecisionFields() {
        MenuCategory primoCategory = category(1L, "PRIMO", "Primi");
        Piatto entity = Piatto.builder()
                .id(10L)
                .nome("Carbonara")
                .descrizione("Prima")
                .prezzo(new BigDecimal("12.00"))
                .categoria(primoCategory)
                .disponibile(true)
                .consigliato(false)
                .ristoratore(restaurant)
                .build();

        PiattoDTO incoming = new PiattoDTO();
        incoming.setNome("Carbonara Premium");
        incoming.setDescrizione("Nuova descrizione");
        incoming.setPrezzo(new BigDecimal("14.00"));
        incoming.setCategoriaCode("PRIMO");
        incoming.setDisponibile(false);
        incoming.setConsigliato(true);

        org.mockito.Mockito.when(menuCategoryService.resolveCategory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(incoming)))
                .thenReturn(primoCategory);

        assertTrue(menuService.isDecisionFieldUpdateRequested(entity, incoming));

        menuService.updateFromDTO(entity, incoming);

        assertFalse(entity.getDisponibile());
        assertTrue(entity.getConsigliato());
    }

    @Test
    void shouldForceNeutralDecisionStateWhenCreatingDishFromDto() {
        org.mockito.Mockito.when(accessContextService.getActingRestaurantIdOrThrow()).thenReturn(7L);
        org.mockito.Mockito.when(ristoratoreRepo.findById(7L)).thenReturn(Optional.of(restaurant));
        MenuCategory secondoCategory = category(2L, "SECONDO", "Secondi");
        PiattoDTO incoming = new PiattoDTO();
        incoming.setId(99L);
        incoming.setNome("Nuovo piatto");
        incoming.setDescrizione("Descrizione");
        incoming.setPrezzo(new BigDecimal("8.00"));
        incoming.setCategoriaCode("SECONDO");
        incoming.setDisponibile(false);
        incoming.setConsigliato(true);

        org.mockito.Mockito.when(menuCategoryService.resolveCategory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(incoming)))
                .thenReturn(secondoCategory);

        Piatto created = menuService.fromDTO(incoming);

        assertTrue(created.getDisponibile());
        assertFalse(created.getConsigliato());
    }

    private MenuCategory category(Long id, String code, String label) {
        return MenuCategory.builder()
                .id(id)
                .businessType(BusinessType.RISTORANTE)
                .code(code)
                .label(label)
                .sortOrder(10)
                .active(true)
                .build();
    }
}
