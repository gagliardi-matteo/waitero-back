package com.waitero.back.controller;

import com.waitero.back.dto.admin.AdminAuditLogDto;
import com.waitero.back.dto.admin.AdminRestaurantSummaryDto;
import com.waitero.back.dto.admin.CreateRestaurantRequest;
import com.waitero.back.dto.admin.ImpersonationResponse;
import com.waitero.back.dto.admin.ResetRestaurantPasswordRequest;
import com.waitero.back.dto.admin.StartImpersonationRequest;
import com.waitero.back.service.AdminAuditService;
import com.waitero.back.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final AdminAuditService adminAuditService;

    @GetMapping("/audit-logs")
    public List<AdminAuditLogDto> getAuditLogs(
            @RequestParam(required = false) Long restaurantId,
            @RequestParam(required = false) Integer limit
    ) {
        return adminAuditService.findRecent(restaurantId, limit);
    }

    @GetMapping("/restaurants")
    public List<AdminRestaurantSummaryDto> searchRestaurants(@RequestParam(required = false) String q) {
        return adminService.searchRestaurants(q);
    }

    @PostMapping("/restaurants")
    public AdminRestaurantSummaryDto createRestaurant(@RequestBody CreateRestaurantRequest request) {
        return adminService.createRestaurant(request);
    }

    @PutMapping("/restaurants/{restaurantId}/password")
    public void resetRestaurantPassword(
            @PathVariable Long restaurantId,
            @RequestBody ResetRestaurantPasswordRequest request
    ) {
        adminService.resetRestaurantPassword(restaurantId, request);
    }

    @PostMapping("/impersonations")
    public ImpersonationResponse startImpersonation(@RequestBody StartImpersonationRequest request) {
        return adminService.startImpersonation(request);
    }
}

