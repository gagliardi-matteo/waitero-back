package com.waitero.back.service;

import com.waitero.back.dto.*;
import com.waitero.back.entity.*;
import com.waitero.back.repository.OrdineItemRepository;
import com.waitero.back.repository.OrdinePagamentoAllocazioneRepository;
import com.waitero.back.repository.OrdinePagamentoRepository;
import com.waitero.back.repository.OrdineRepository;
import com.waitero.back.repository.PiattoRepository;
import com.waitero.back.repository.RistoratoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import com.waitero.back.security.AccessContextService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OrdineService {

    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(OrderStatus.APERTO, OrderStatus.PARZIALMENTE_PAGATO);
    private static final List<OrderStatus> HISTORY_STATUSES = List.of(OrderStatus.PAGATO, OrderStatus.ANNULLATO);

    private final OrdineRepository ordineRepository;
    private final OrdineItemRepository ordineItemRepository;
    private final OrdinePagamentoRepository ordinePagamentoRepository;
    private final OrdinePagamentoAllocazioneRepository ordinePagamentoAllocazioneRepository;
    private final RistoratoreRepository ristoratoreRepository;
    private final PiattoRepository piattoRepository;
    private final JwtService jwtService;
    private final OrderStreamService orderStreamService;
    private final CustomerDraftService customerDraftService;
    private final TavoloService tavoloService;
    private final UpsellService upsellService;
    private final EventTrackingService eventTrackingService;
    private final AccessContextService accessContextService;

    @Transactional
    public OrdineDTO createOrAppend(CustomerOrderRequest request) {
        validateCustomerRequest(request);
        Long restaurantId = Long.parseLong(request.getRestaurantId());
        return createOrAppendInternal(restaurantId, request.getTableId(), request.getItems(), request.getNoteCucina());
    }

    @Transactional
    public OrdineDTO createOrAppendByRestaurant(RestaurantOrderRequest request) {
        Long restaurantId = getAuthenticatedRestaurantId();
        if (request == null || request.getTableId() == null) {
            throw new RuntimeException("Tavolo non selezionato");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new RuntimeException("Ordine vuoto");
        }
        tavoloService.requireActiveTable(restaurantId, request.getTableId());
        return createOrAppendInternal(restaurantId, request.getTableId(), request.getItems(), null);
    }

    @Transactional(readOnly = true)
    public Optional<OrdineDTO> getCurrentCustomerOrder(String token, String restaurantId, Integer tableId, String deviceId, String fingerprint) {
        if (token == null || restaurantId == null || tableId == null) {
            return Optional.empty();
        }

        if (!tavoloService.validateCustomerAccess(token, restaurantId, tableId, deviceId, fingerprint)) {
            throw new RuntimeException("Token QR non valido");
        }

        Long rid = Long.parseLong(restaurantId);
        tavoloService.requireActiveTable(rid, tableId);
        return ordineRepository
                .findFirstByRistoratoreIdAndTableIdAndStatusInOrderByCreatedAtDesc(rid, tableId, ACTIVE_STATUSES)
                .map(this::toOptimizedDTO);
    }

    @Transactional(readOnly = true)
    public List<OrdineDTO> getActiveOrdersForAuthenticatedRestaurant() {
        Long ristoratoreId = getAuthenticatedRestaurantId();
        return mapOrdersToDTOs(ordineRepository.findAllByRistoratoreIdAndStatusInOrderByCreatedAtDesc(ristoratoreId, ACTIVE_STATUSES));
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryDTO> getActiveOrderSummariesForAuthenticatedRestaurant() {
        Long ristoratoreId = getAuthenticatedRestaurantId();
        return ordineRepository.findOrderSummariesByRestaurantAndStatuses(
                        ristoratoreId,
                        ACTIVE_STATUSES.stream().map(Enum::name).toList()
                ).stream()
                .map(this::toSummaryDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrdineDTO> getHistoryOrdersForAuthenticatedRestaurant() {
        Long ristoratoreId = getAuthenticatedRestaurantId();
        return mapOrdersToDTOs(ordineRepository.findAllByRistoratoreIdAndStatusInOrderByCreatedAtDesc(ristoratoreId, HISTORY_STATUSES));
    }

    @Transactional(readOnly = true)
    public List<OrderSummaryDTO> getAllOrderSummariesForAuthenticatedRestaurant() {
        Long ristoratoreId = getAuthenticatedRestaurantId();
        return ordineRepository.findAllOrderSummariesByRestaurant(ristoratoreId).stream()
                .map(this::toSummaryDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderSummaryPageDTO getPagedOrderSummariesForAuthenticatedRestaurant(String status, String search, int page, int size) {
        Long ristoratoreId = getAuthenticatedRestaurantId();
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.max(10, Math.min(size, 100));
        String normalizedStatus = normalizeSummaryStatus(status);
        String normalizedSearch = normalizeSummarySearch(search);

        Page<com.waitero.back.repository.OrderSummaryProjection> result = ordineRepository.findPagedOrderSummariesByRestaurant(
                ristoratoreId,
                normalizedStatus,
                normalizedSearch,
                PageRequest.of(normalizedPage, normalizedSize)
        );

        return OrderSummaryPageDTO.builder()
                .items(result.getContent().stream().map(this::toSummaryDTO).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .hasPrevious(result.hasPrevious())
                .build();
    }

    @Transactional(readOnly = true)
    public OrdineDTO getOrderForAuthenticatedRestaurant(Long orderId) {
        Long ristoratoreId = getAuthenticatedRestaurantId();
        Ordine ordine = ordineRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Ordine non trovato"));

        if (!ordine.getRistoratore().getId().equals(ristoratoreId)) {
            throw new RuntimeException("Ordine non accessibile");
        }

        return toOptimizedDTO(ordine);
    }

    @Transactional
    public OrdineDTO payOrder(Long orderId, PaymentRequest request) {
        Long ristoratoreId = getAuthenticatedRestaurantId();
        Ordine ordine = ordineRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Ordine non trovato"));

        if (!ordine.getRistoratore().getId().equals(ristoratoreId)) {
            throw new RuntimeException("Ordine non accessibile");
        }

        OrderFinancialSnapshot initialFinancials = refreshFinancials(ordine);
        BigDecimal residuo = initialFinancials.remainingAmount();
        if (residuo.compareTo(BigDecimal.ZERO) <= 0) {
            ordine.setStatus(OrderStatus.PAGATO);
            if (ordine.getPaidAt() == null) {
                ordine.setPaidAt(LocalDateTime.now());
            }
            return toDTO(ordine);
        }

        String paymentMode = normalizePaymentMode(request);
        List<OrdinePagamentoAllocazione> allocations = buildAllocations(ordine, request);
        BigDecimal amount = allocations.isEmpty()
                ? normalizePaymentAmount(request, residuo)
                : allocations.stream()
                    .map(a -> a.getUnitPrice().multiply(BigDecimal.valueOf(a.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Importo pagamento non valido");
        }

        if (amount.compareTo(residuo) > 0) {
            throw new RuntimeException("Importo superiore al residuo");
        }

        OrdinePagamento payment = OrdinePagamento.builder()
                .ordine(ordine)
                .amount(amount)
                .paymentMode(paymentMode)
                .participantName(normalizeParticipantName(request))
                .createdAt(LocalDateTime.now())
                .allocations(new ArrayList<>())
                .build();

        for (OrdinePagamentoAllocazione allocation : allocations) {
            allocation.setPayment(payment);
            payment.getAllocations().add(allocation);
        }

        ordine.getPayments().add(payment);
        ordine.setPaymentMode(paymentMode);
        ordine.setUpdatedAt(LocalDateTime.now());

        OrderFinancialSnapshot updatedFinancials = refreshFinancials(ordine);
        if (updatedFinancials.remainingAmount().compareTo(BigDecimal.ZERO) <= 0) {
            ordine.setStatus(OrderStatus.PAGATO);
            ordine.setPaidAt(LocalDateTime.now());
        } else {
            ordine.setStatus(OrderStatus.PARZIALMENTE_PAGATO);
            ordine.setPaidAt(null);
        }

        Ordine saved = ordineRepository.save(ordine);
        upsellService.refreshAggregatesForRestaurant(saved.getRistoratore().getId());
        if (saved.getStatus() == OrderStatus.PAGATO) {
            tavoloService.clearRegisteredDevices(saved.getRistoratore().getId(), saved.getTableId());
        }
        orderStreamService.publishOrderUpdate(saved.getRistoratore().getId(), "ORDER_PAYMENT_UPDATED", saved.getId(), saved.getStatus().name());
        orderStreamService.publishCustomerTableUpdate(saved.getRistoratore().getId(), saved.getTableId(), "ORDER_PAYMENT_UPDATED");
        return toDTO(saved);
    }

    public OrdineDTO toDTO(Ordine ordine) {
        Map<Long, Integer> paidQuantityByItem = calculatePaidQuantitiesByItem(ordine);

        List<OrdineItemDTO> items = ordine.getItems().stream()
                .map(item -> {
                    int paidQuantity = paidQuantityByItem.getOrDefault(item.getId(), 0);
                    int remainingQuantity = Math.max(item.getQuantity() - paidQuantity, 0);
                    return OrdineItemDTO.builder()
                            .id(item.getId())
                            .dishId(item.getPiatto().getId())
                            .nome(item.getNome())
                            .prezzoUnitario(item.getPrezzoUnitario())
                            .quantita(item.getQuantity())
                            .paidQuantity(paidQuantity)
                            .remainingQuantity(remainingQuantity)
                            .subtotale(item.getPrezzoUnitario().multiply(BigDecimal.valueOf(item.getQuantity())))
                            .imageUrl(item.getImageUrl())
                            .build();
                })
                .toList();

        OrderFinancialSnapshot financials = readFinancials(ordine);

        List<OrdinePaymentDTO> payments = ordine.getPayments().stream()
                .map(payment -> OrdinePaymentDTO.builder()
                        .id(payment.getId())
                        .amount(payment.getAmount())
                        .paymentMode(payment.getPaymentMode())
                        .participantName(payment.getParticipantName())
                        .createdAt(payment.getCreatedAt())
                        .allocations(payment.getAllocations().stream()
                                .map(allocation -> OrdinePaymentAllocationDTO.builder()
                                        .id(allocation.getId())
                                        .orderItemId(allocation.getOrderItem().getId())
                                        .itemName(allocation.getOrderItem().getNome())
                                        .quantity(allocation.getQuantity())
                                        .unitPrice(allocation.getUnitPrice())
                                        .subtotal(allocation.getUnitPrice().multiply(BigDecimal.valueOf(allocation.getQuantity())))
                                        .build())
                                .toList())
                        .build())
                .toList();

        return OrdineDTO.builder()
                .id(ordine.getId())
                .restaurantId(ordine.getRistoratore().getId())
                .tableId(ordine.getTableId())
                .status(ordine.getStatus().name())
                .paymentMode(ordine.getPaymentMode())
                .noteCucina(ordine.getNoteCucina())
                .paidAt(ordine.getPaidAt())
                .createdAt(ordine.getCreatedAt())
                .updatedAt(ordine.getUpdatedAt())
                .totale(financials.total())
                .paidAmount(financials.paidAmount())
                .remainingAmount(financials.remainingAmount())
                .items(items)
                .payments(payments)
                .build();
    }

    private OrdineDTO toOptimizedDTO(Ordine ordine) {
        return mapOrdersToDTOs(List.of(ordine)).stream()
                .findFirst()
                .orElseGet(() -> toDTO(ordine));
    }

    private List<OrdineDTO> mapOrdersToDTOs(List<Ordine> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = orders.stream()
                .map(Ordine::getId)
                .toList();

        List<OrdineItem> items = ordineItemRepository.findAllByOrderIdsOrdered(orderIds);
        Map<Long, List<OrdineItem>> itemsByOrderId = new LinkedHashMap<>();
        Map<Long, OrdineItem> itemById = new HashMap<>();
        for (OrdineItem item : items) {
            itemsByOrderId.computeIfAbsent(item.getOrdine().getId(), ignored -> new ArrayList<>()).add(item);
            itemById.put(item.getId(), item);
        }

        List<OrdinePagamento> payments = ordinePagamentoRepository.findAllByOrderIdsOrdered(orderIds);
        Map<Long, List<OrdinePagamento>> paymentsByOrderId = new LinkedHashMap<>();
        List<Long> paymentIds = new ArrayList<>();
        for (OrdinePagamento payment : payments) {
            paymentsByOrderId.computeIfAbsent(payment.getOrdine().getId(), ignored -> new ArrayList<>()).add(payment);
            paymentIds.add(payment.getId());
        }

        Map<Long, List<OrdinePagamentoAllocazione>> allocationsByPaymentId = new LinkedHashMap<>();
        if (!paymentIds.isEmpty()) {
            for (OrdinePagamentoAllocazione allocation : ordinePagamentoAllocazioneRepository.findAllByPaymentIdsOrdered(paymentIds)) {
                allocationsByPaymentId.computeIfAbsent(allocation.getPayment().getId(), ignored -> new ArrayList<>()).add(allocation);
            }
        }

        List<OrdineDTO> result = new ArrayList<>(orders.size());
        for (Ordine order : orders) {
            List<OrdineItem> orderItems = itemsByOrderId.getOrDefault(order.getId(), List.of());
            List<OrdinePagamento> orderPayments = paymentsByOrderId.getOrDefault(order.getId(), List.of());
            result.add(buildOptimizedDTO(order, orderItems, orderPayments, allocationsByPaymentId, itemById));
        }
        return result;
    }

    private OrdineDTO buildOptimizedDTO(
            Ordine ordine,
            List<OrdineItem> items,
            List<OrdinePagamento> payments,
            Map<Long, List<OrdinePagamentoAllocazione>> allocationsByPaymentId,
            Map<Long, OrdineItem> itemById
    ) {
        Map<Long, Integer> paidQuantityByItem = new HashMap<>();
        for (OrdinePagamento payment : payments) {
            for (OrdinePagamentoAllocazione allocation : allocationsByPaymentId.getOrDefault(payment.getId(), List.of())) {
                Long orderItemId = allocation.getOrderItem().getId();
                paidQuantityByItem.put(orderItemId, paidQuantityByItem.getOrDefault(orderItemId, 0) + allocation.getQuantity());
            }
        }

        List<OrdineItemDTO> itemDtos = items.stream()
                .map(item -> {
                    int paidQuantity = paidQuantityByItem.getOrDefault(item.getId(), 0);
                    int remainingQuantity = Math.max(item.getQuantity() - paidQuantity, 0);
                    return OrdineItemDTO.builder()
                            .id(item.getId())
                            .dishId(item.getPiatto().getId())
                            .nome(item.getNome())
                            .prezzoUnitario(item.getPrezzoUnitario())
                            .quantita(item.getQuantity())
                            .paidQuantity(paidQuantity)
                            .remainingQuantity(remainingQuantity)
                            .subtotale(item.getPrezzoUnitario().multiply(BigDecimal.valueOf(item.getQuantity())))
                            .imageUrl(item.getImageUrl())
                            .build();
                })
                .toList();

        BigDecimal paidAmount = payments.stream()
                .map(OrdinePagamento::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = ordine.getTotale() != null ? normalizeCurrency(ordine.getTotale()) : items.stream()
                .map(item -> item.getPrezzoUnitario().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal remainingAmount = total.subtract(paidAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        List<OrdinePaymentDTO> paymentDtos = payments.stream()
                .map(payment -> OrdinePaymentDTO.builder()
                        .id(payment.getId())
                        .amount(payment.getAmount())
                        .paymentMode(payment.getPaymentMode())
                        .participantName(payment.getParticipantName())
                        .createdAt(payment.getCreatedAt())
                        .allocations(allocationsByPaymentId.getOrDefault(payment.getId(), List.of()).stream()
                                .map(allocation -> {
                                    OrdineItem orderItem = itemById.get(allocation.getOrderItem().getId());
                                    String itemName = orderItem != null ? orderItem.getNome() : allocation.getOrderItem().getNome();
                                    return OrdinePaymentAllocationDTO.builder()
                                            .id(allocation.getId())
                                            .orderItemId(allocation.getOrderItem().getId())
                                            .itemName(itemName)
                                            .quantity(allocation.getQuantity())
                                            .unitPrice(allocation.getUnitPrice())
                                            .subtotal(allocation.getUnitPrice().multiply(BigDecimal.valueOf(allocation.getQuantity())))
                                            .build();
                                })
                                .toList())
                        .build())
                .toList();

        return OrdineDTO.builder()
                .id(ordine.getId())
                .restaurantId(ordine.getRistoratore().getId())
                .tableId(ordine.getTableId())
                .status(ordine.getStatus().name())
                .paymentMode(ordine.getPaymentMode())
                .noteCucina(ordine.getNoteCucina())
                .paidAt(ordine.getPaidAt())
                .createdAt(ordine.getCreatedAt())
                .updatedAt(ordine.getUpdatedAt())
                .totale(total)
                .paidAmount(paidAmount)
                .remainingAmount(remainingAmount)
                .items(itemDtos)
                .payments(paymentDtos)
                .build();
    }

    private OrderSummaryDTO toSummaryDTO(com.waitero.back.repository.OrderSummaryProjection projection) {
        return OrderSummaryDTO.builder()
                .id(projection.getId())
                .tableId(projection.getTableId())
                .status(projection.getStatus())
                .paidAt(projection.getPaidAt())
                .createdAt(projection.getCreatedAt())
                .updatedAt(projection.getUpdatedAt())
                .totale(projection.getTotale() != null ? normalizeCurrency(projection.getTotale()) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .itemCount(projection.getItemCount() != null ? projection.getItemCount() : 0)
                .build();
    }

    private String normalizeSummaryStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return "";
        }
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException("Stato ordine non valido");
        }
    }

    private String normalizeSummarySearch(String search) {
        if (search == null) {
            return "";
        }
        String normalized = search.trim();
        return normalized.isEmpty() ? "" : normalized;
    }

    private void validateCustomerRequest(CustomerOrderRequest request) {
        if (request.getToken() == null || request.getRestaurantId() == null || request.getTableId() == null || request.getDeviceId() == null) {
            throw new RuntimeException("Dati QR mancanti");
        }

        if (!tavoloService.validateCustomerAccess(request.getToken(), request.getRestaurantId(), request.getTableId(), request.getDeviceId(), request.getFingerprint())) {
            throw new RuntimeException("Token QR non valido");
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new RuntimeException("Ordine vuoto");
        }
    }

    private OrdineDTO createOrAppendInternal(Long restaurantId, Integer tableId, List<CustomerOrderItemRequest> itemsRequest, String noteCucina) {
        tavoloService.requireActiveTable(restaurantId, tableId);
        Ristoratore ristoratore = ristoratoreRepository.findById(restaurantId)
                .orElseThrow(() -> new RuntimeException("Ristorante non trovato"));

        Ordine ordine = ordineRepository
                .findFirstByRistoratoreIdAndTableIdAndStatusInOrderByCreatedAtDesc(restaurantId, tableId, ACTIVE_STATUSES)
                .orElseGet(() -> Ordine.builder()
                        .ristoratore(ristoratore)
                        .tableId(tableId)
                        .status(OrderStatus.APERTO)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .totale(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                        .items(new ArrayList<>())
                        .payments(new ArrayList<>())
                        .build());

        Map<Long, OrdineItem> existingItemsByDishId = new LinkedHashMap<>();
        for (OrdineItem item : ordine.getItems()) {
            existingItemsByDishId.put(item.getPiatto().getId(), item);
        }

        for (CustomerOrderItemRequest itemRequest : itemsRequest) {
            if (itemRequest.getDishId() == null || itemRequest.getQuantity() == null || itemRequest.getQuantity() <= 0) {
                continue;
            }

            Piatto piatto = piattoRepository.findById(itemRequest.getDishId())
                    .orElseThrow(() -> new RuntimeException("Piatto non trovato: " + itemRequest.getDishId()));

            if (!piatto.getRistoratore().getId().equals(restaurantId)) {
                throw new RuntimeException("Piatto non associato al ristorante autenticato");
            }

            if (Boolean.FALSE.equals(piatto.getDisponibile())) {
                throw new RuntimeException("Piatto non disponibile: " + piatto.getNome());
            }

            OrdineItem existing = existingItemsByDishId.get(piatto.getId());
            if (existing != null) {
                existing.setQuantity(existing.getQuantity() + itemRequest.getQuantity());
                continue;
            }

            OrdineItem nuovoItem = OrdineItem.builder()
                    .ordine(ordine)
                    .piatto(piatto)
                    .nome(piatto.getNome())
                    .prezzoUnitario(piatto.getPrezzo())
                    .quantity(itemRequest.getQuantity())
                    .imageUrl(piatto.getImageUrl())
                    .createdAt(LocalDateTime.now())
                    .build();

            ordine.getItems().add(nuovoItem);
            existingItemsByDishId.put(piatto.getId(), nuovoItem);
        }

        if (ordine.getItems().isEmpty()) {
            throw new RuntimeException("Nessun piatto valido da inviare");
        }

        ordine.setNoteCucina(mergeKitchenNotes(ordine.getNoteCucina(), noteCucina));
        reconcileOrderStatusAfterMutation(ordine);
        ordine.setUpdatedAt(LocalDateTime.now());
        Ordine saved = ordineRepository.save(ordine);
        upsellService.refreshAggregatesForRestaurant(saved.getRistoratore().getId());
        if (saved.getStatus() == OrderStatus.PAGATO) {
            tavoloService.clearRegisteredDevices(saved.getRistoratore().getId(), saved.getTableId());
        }
        customerDraftService.clearDraft(saved.getRistoratore().getId(), saved.getTableId());
        eventTrackingService.trackOrderSubmitted(
                saved.getRistoratore().getId(),
                saved.getTableId(),
                buildOrderSessionId(saved.getRistoratore().getId(), saved.getTableId()),
                saved.getRistoratore().getId(),
                saved.getId(),
                saved.getTotale(),
                saved.getItems().stream().mapToInt(OrdineItem::getQuantity).sum()
        );
        orderStreamService.publishOrderUpdate(saved.getRistoratore().getId(), "ORDER_UPDATED", saved.getId(), saved.getStatus().name());
        orderStreamService.publishCustomerTableUpdate(saved.getRistoratore().getId(), saved.getTableId(), "ORDER_UPDATED");
        return toDTO(saved);
    }

    private String buildOrderSessionId(Long restaurantId, Integer tableId) {
        return restaurantId + ":" + tableId;
    }

    private String mergeKitchenNotes(String existingNotes, String incomingNotes) {
        String normalizedIncoming = normalizeKitchenNotes(incomingNotes);
        if (normalizedIncoming == null) {
            return existingNotes;
        }

        String normalizedExisting = normalizeKitchenNotes(existingNotes);
        if (normalizedExisting == null) {
            return normalizedIncoming;
        }

        if (normalizedExisting.equals(normalizedIncoming)) {
            return normalizedExisting;
        }

        return normalizedExisting + "\n" + normalizedIncoming;
    }

    private String normalizeKitchenNotes(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }

        return normalized.length() > 1000 ? normalized.substring(0, 1000) : normalized;
    }

    private Long getAuthenticatedRestaurantId() {
        return accessContextService.getActingRestaurantIdOrThrow();
    }

    private String normalizePaymentMode(PaymentRequest request) {
        if (request == null || request.getPaymentMode() == null || request.getPaymentMode().isBlank()) {
            return "FULL";
        }
        return request.getPaymentMode().trim().toUpperCase();
    }

    private String normalizeParticipantName(PaymentRequest request) {
        if (request == null || request.getParticipantName() == null || request.getParticipantName().isBlank()) {
            return null;
        }
        return request.getParticipantName().trim();
    }

    private BigDecimal normalizePaymentAmount(PaymentRequest request, BigDecimal remainingAmount) {
        if (request == null || request.getAmount() == null) {
            return remainingAmount;
        }

        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Importo pagamento non valido");
        }

        if (request.getAmount().compareTo(remainingAmount) > 0) {
            throw new RuntimeException("Importo superiore al residuo");
        }

        return normalizeCurrency(request.getAmount());
    }

    private BigDecimal calculateTotalFromItems(Ordine ordine) {
        return ordine.getItems().stream()
                .map(item -> item.getPrezzoUnitario().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePaid(Ordine ordine) {
        return ordine.getPayments().stream()
                .map(OrdinePagamento::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private OrderFinancialSnapshot refreshFinancials(Ordine ordine) {
        BigDecimal total = calculateTotalFromItems(ordine);
        ordine.setTotale(total);
        BigDecimal paidAmount = calculatePaid(ordine);
        BigDecimal remainingAmount = total.subtract(paidAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new OrderFinancialSnapshot(total, paidAmount, remainingAmount);
    }

    private OrderFinancialSnapshot readFinancials(Ordine ordine) {
        BigDecimal total = ordine.getTotale() != null ? normalizeCurrency(ordine.getTotale()) : calculateTotalFromItems(ordine);
        BigDecimal paidAmount = calculatePaid(ordine);
        BigDecimal remainingAmount = total.subtract(paidAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new OrderFinancialSnapshot(total, paidAmount, remainingAmount);
    }

    private BigDecimal normalizeCurrency(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private Map<Long, Integer> calculatePaidQuantitiesByItem(Ordine ordine) {
        Map<Long, Integer> result = new HashMap<>();
        for (OrdinePagamento payment : ordine.getPayments()) {
            for (OrdinePagamentoAllocazione allocation : payment.getAllocations()) {
                Long orderItemId = allocation.getOrderItem().getId();
                result.put(orderItemId, result.getOrDefault(orderItemId, 0) + allocation.getQuantity());
            }
        }
        return result;
    }

    private List<OrdinePagamentoAllocazione> buildAllocations(Ordine ordine, PaymentRequest request) {
        if (request == null || request.getAllocations() == null || request.getAllocations().isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> paidQuantityByItem = calculatePaidQuantitiesByItem(ordine);
        Map<Long, OrdineItem> itemsById = new HashMap<>();
        for (OrdineItem item : ordine.getItems()) {
            itemsById.put(item.getId(), item);
        }

        List<OrdinePagamentoAllocazione> result = new ArrayList<>();
        for (PaymentAllocationRequest allocationRequest : request.getAllocations()) {
            if (allocationRequest.getOrderItemId() == null || allocationRequest.getQuantity() == null || allocationRequest.getQuantity() <= 0) {
                continue;
            }

            OrdineItem item = itemsById.get(allocationRequest.getOrderItemId());
            if (item == null) {
                throw new RuntimeException("Riga ordine non trovata: " + allocationRequest.getOrderItemId());
            }

            int alreadyPaid = paidQuantityByItem.getOrDefault(item.getId(), 0);
            int remaining = item.getQuantity() - alreadyPaid;
            if (allocationRequest.getQuantity() > remaining) {
                throw new RuntimeException("Quantita selezionata superiore al residuo per " + item.getNome());
            }

            result.add(OrdinePagamentoAllocazione.builder()
                    .orderItem(item)
                    .quantity(allocationRequest.getQuantity())
                    .unitPrice(item.getPrezzoUnitario())
                    .build());
        }

        return result;
    }

    private void reconcileOrderStatusAfterMutation(Ordine ordine) {
        OrderFinancialSnapshot financials = refreshFinancials(ordine);
        BigDecimal totale = financials.total();
        BigDecimal pagato = financials.paidAmount();

        if (pagato.compareTo(BigDecimal.ZERO) <= 0) {
            ordine.setStatus(OrderStatus.APERTO);
            ordine.setPaidAt(null);
            return;
        }

        if (pagato.compareTo(totale) >= 0) {
            ordine.setStatus(OrderStatus.PAGATO);
            if (ordine.getPaidAt() == null) {
                ordine.setPaidAt(LocalDateTime.now());
            }
            return;
        }

        ordine.setStatus(OrderStatus.PARZIALMENTE_PAGATO);
        ordine.setPaidAt(null);
    }

    private record OrderFinancialSnapshot(BigDecimal total, BigDecimal paidAmount, BigDecimal remainingAmount) {
    }
}







