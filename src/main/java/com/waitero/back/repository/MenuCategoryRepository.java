package com.waitero.back.repository;

import com.waitero.back.entity.BusinessType;
import com.waitero.back.entity.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {
    List<MenuCategory> findAllByBusinessTypeAndActiveTrueOrderBySortOrderAscLabelAsc(BusinessType businessType);
    Optional<MenuCategory> findByIdAndBusinessTypeAndActiveTrue(Long id, BusinessType businessType);
    Optional<MenuCategory> findByBusinessTypeAndCodeIgnoreCaseAndActiveTrue(BusinessType businessType, String code);
}
