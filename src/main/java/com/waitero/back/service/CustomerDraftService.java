package com.waitero.back.service;

import com.waitero.back.dto.CustomerDraftDTO;
import com.waitero.back.dto.CustomerDraftItemDTO;
import com.waitero.back.dto.CustomerDraftMutationRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CustomerDraftService {

    private final Map<String, ConcurrentHashMap<Long, Integer>> draftsByTable = new ConcurrentHashMap<>();
    private final OrderStreamService orderStreamService;
    private final TavoloService tavoloService;

    public CustomerDraftService(OrderStreamService orderStreamService, TavoloService tavoloService) {
        this.orderStreamService = orderStreamService;
        this.tavoloService = tavoloService;
    }

    public CustomerDraftDTO getDraft(String token, String restaurantId, Integer tableId, String deviceId, String fingerprint) {
        validate(token, restaurantId, tableId, deviceId, fingerprint);
        return toDTO(Long.parseLong(restaurantId), tableId, getDraftMap(key(restaurantId, tableId)));
    }

    public CustomerDraftDTO mutate(CustomerDraftMutationRequest request) {
        validate(request.getToken(), request.getRestaurantId(), request.getTableId(), request.getDeviceId(), request.getFingerprint());
        if (request.getDishId() == null || request.getDelta() == null || request.getDelta() == 0) {
            throw new RuntimeException("Mutazione bozza non valida");
        }

        String key = key(request.getRestaurantId(), request.getTableId());
        ConcurrentHashMap<Long, Integer> draft = getDraftMap(key);
        draft.compute(request.getDishId(), (dishId, quantity) -> {
            int current = quantity == null ? 0 : quantity;
            int next = current + request.getDelta();
            return next <= 0 ? null : next;
        });

        if (draft.isEmpty()) {
            draftsByTable.remove(key);
        }

        Long restaurantId = Long.parseLong(request.getRestaurantId());
        orderStreamService.publishCustomerTableUpdate(restaurantId, request.getTableId(), "DRAFT_UPDATED");
        return toDTO(restaurantId, request.getTableId(), getDraftMap(key));
    }

    public void clearDraft(Long restaurantId, Integer tableId) {
        String key = key(String.valueOf(restaurantId), tableId);
        draftsByTable.remove(key);
        orderStreamService.publishCustomerTableUpdate(restaurantId, tableId, "DRAFT_CLEARED");
    }

    private void validate(String token, String restaurantId, Integer tableId, String deviceId, String fingerprint) {
        if (token == null || restaurantId == null || tableId == null) {
            throw new RuntimeException("Dati tavolo mancanti");
        }

        if (!tavoloService.validateCustomerAccess(token, restaurantId, tableId, deviceId, fingerprint)) {
            throw new RuntimeException("Accesso tavolo non autorizzato");
        }
    }

    private String key(String restaurantId, Integer tableId) {
        return restaurantId + ":" + tableId;
    }

    private ConcurrentHashMap<Long, Integer> getDraftMap(String key) {
        return draftsByTable.computeIfAbsent(key, unused -> new ConcurrentHashMap<>());
    }

    private CustomerDraftDTO toDTO(Long restaurantId, Integer tableId, Map<Long, Integer> draft) {
        List<CustomerDraftItemDTO> items = new ArrayList<>();
        draft.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .sorted(Comparator.comparingLong(Map.Entry::getKey))
                .forEach(entry -> items.add(CustomerDraftItemDTO.builder()
                        .dishId(entry.getKey())
                        .quantity(entry.getValue())
                        .build()));

        return CustomerDraftDTO.builder()
                .restaurantId(restaurantId)
                .tableId(tableId)
                .items(items)
                .build();
    }
}
