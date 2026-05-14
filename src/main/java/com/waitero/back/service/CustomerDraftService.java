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

    private final Map<String, ConcurrentHashMap<String, DraftLine>> draftsByTable = new ConcurrentHashMap<>();
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
        ConcurrentHashMap<String, DraftLine> draft = getDraftMap(key);
        String lineKey = lineKey(request.getDishId(), request.getPortionKey());
        draft.compute(lineKey, (ignored, existingLine) -> {
            int current = existingLine == null ? 0 : existingLine.quantity();
            int next = current + request.getDelta();
            return next <= 0 ? null : new DraftLine(request.getDishId(), normalizePortionKey(request.getPortionKey()), next);
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

    private ConcurrentHashMap<String, DraftLine> getDraftMap(String key) {
        return draftsByTable.computeIfAbsent(key, unused -> new ConcurrentHashMap<>());
    }

    private CustomerDraftDTO toDTO(Long restaurantId, Integer tableId, Map<String, DraftLine> draft) {
        List<CustomerDraftItemDTO> items = new ArrayList<>();
        draft.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().quantity() > 0)
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> items.add(CustomerDraftItemDTO.builder()
                        .lineKey(entry.getKey())
                        .dishId(entry.getValue().dishId())
                        .portionKey(entry.getValue().portionKey())
                        .quantity(entry.getValue().quantity())
                        .build()));

        return CustomerDraftDTO.builder()
                .restaurantId(restaurantId)
                .tableId(tableId)
                .items(items)
                .build();
    }

    private String lineKey(Long dishId, String portionKey) {
        return dishId + "::" + normalizePortionKey(portionKey);
    }

    private String normalizePortionKey(String portionKey) {
        if (portionKey == null || portionKey.isBlank()) {
            return DishPortionService.DEFAULT_PORTION_KEY;
        }
        return portionKey.trim();
    }

    private record DraftLine(Long dishId, String portionKey, Integer quantity) {
    }
}
