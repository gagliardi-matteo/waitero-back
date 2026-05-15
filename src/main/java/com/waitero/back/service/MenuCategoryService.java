package com.waitero.back.service;

import com.waitero.back.dto.MenuCategoryDTO;
import com.waitero.back.dto.PiattoDTO;
import com.waitero.back.entity.BusinessType;
import com.waitero.back.entity.MenuCategory;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.repository.MenuCategoryRepository;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.security.AccessContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MenuCategoryService {

    private final MenuCategoryRepository menuCategoryRepository;
    private final RistoratoreRepository ristoratoreRepository;
    private final AccessContextService accessContextService;

    @Transactional(readOnly = true)
    public List<MenuCategoryDTO> getAuthenticatedCategories() {
        Ristoratore restaurant = getAuthenticatedRestaurant();
        return listByBusinessType(resolveBusinessType(restaurant));
    }

    @Transactional(readOnly = true)
    public MenuCategory resolveCategory(Ristoratore restaurant, PiattoDTO dto) {
        BusinessType businessType = resolveBusinessType(restaurant);
        if (dto == null) {
            throw new RuntimeException("Categoria obbligatoria");
        }

        if (dto.getCategoriaId() != null) {
            return menuCategoryRepository.findByIdAndBusinessTypeAndActiveTrue(dto.getCategoriaId(), businessType)
                    .orElseThrow(() -> new RuntimeException("Categoria non valida per il locale"));
        }

        String code = MenuCategoryRules.normalize(dto.getCategoriaCode());
        if (code == null) {
            code = MenuCategoryRules.normalize(dto.getCategoria());
        }
        if (code != null) {
            return menuCategoryRepository.findByBusinessTypeAndCodeIgnoreCaseAndActiveTrue(businessType, code)
                    .orElseGet(() -> resolveCategoryByLabel(businessType, dto.getCategoria()));
        }

        return resolveCategoryByLabel(businessType, dto.getCategoria());
    }

    public List<MenuCategoryDTO> listByBusinessType(BusinessType businessType) {
        BusinessType resolvedBusinessType = businessType == null ? BusinessType.RISTORANTE : businessType;
        return menuCategoryRepository.findAllByBusinessTypeAndActiveTrueOrderBySortOrderAscLabelAsc(resolvedBusinessType)
                .stream()
                .map(category -> MenuCategoryDTO.builder()
                        .id(category.getId())
                        .businessType(category.getBusinessType().name())
                        .code(category.getCode())
                        .label(category.getLabel())
                        .sortOrder(category.getSortOrder())
                        .build())
                .toList();
    }

    private Ristoratore getAuthenticatedRestaurant() {
        Long restaurantId = accessContextService.getActingRestaurantIdOrThrow();
        return ristoratoreRepository.findById(restaurantId)
                .orElseThrow(() -> new RuntimeException("Locale non trovato"));
    }

    private MenuCategory resolveCategoryByLabel(BusinessType businessType, String label) {
        String normalizedLabel = label == null ? null : label.trim();
        if (normalizedLabel == null || normalizedLabel.isBlank()) {
            throw new RuntimeException("Categoria obbligatoria");
        }

        return menuCategoryRepository.findByBusinessTypeAndLabelIgnoreCaseAndActiveTrue(businessType, normalizedLabel)
                .orElseThrow(() -> new RuntimeException("Categoria non valida per il locale"));
    }

    private BusinessType resolveBusinessType(Ristoratore restaurant) {
        if (restaurant == null || restaurant.getBusinessType() == null) {
            return BusinessType.RISTORANTE;
        }
        return restaurant.getBusinessType();
    }
}
