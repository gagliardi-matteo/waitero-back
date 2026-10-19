package com.waitero.back.service;

import com.waitero.back.dto.*;
import com.waitero.back.entity.*;
import com.waitero.back.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DemoOrderService {
    private static final List<OrderStatus> ACTIVE = List.of(OrderStatus.APERTO, OrderStatus.PARZIALMENTE_PAGATO);
    private final DemoSessionService sessionService;
    private final DemoSessionRepository sessions;
    private final OrdineRepository orders;
    private final PiattoRepository dishes;
    private final DemoStreamService streams;
    private final CustomerOrderSubmissionRepository submissions;
    private final JdbcTemplate jdbcTemplate;
    @Value("${app.demo.max-orders:10}") private int maxOrders;
    @Value("${app.demo.max-lines:20}") private int maxLines;
    @Value("${app.demo.max-quantity:10}") private int maxQuantity;

    @Transactional
    public OrdineDTO submit(String token, DemoOrderRequest request) {
        DemoSession session = sessionService.require(token);
        validate(request);
        String requestId = normalizeRequestId(request.getRequestId());
        String payloadHash = hashSubmitPayload(request);
        lockActiveOrderMutation(session.getRistoratore().getId(), session.getTavolo().getNumero());
        Optional<CustomerOrderSubmission> prior = submissions.findByRestaurantIdAndTableIdAndRequestId(
                session.getRistoratore().getId(), session.getTavolo().getNumero(), requestId);
        if (prior.isPresent()) {
            if (!MessageDigest.isEqual(prior.get().getPayloadHash().getBytes(StandardCharsets.US_ASCII),
                    payloadHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("requestId già usato con un payload differente");
            }
            return dto(prior.get().getOrder());
        }
        if (session.getOrderCount() >= maxOrders) throw limit("Hai raggiunto il limite di ordini per questa demo");
        Ordine order = orders.findAllByDemoSessionIdAndStatusInOrderByCreatedAtDesc(session.getId(), ACTIVE).stream().findFirst()
                .orElseGet(() -> newOrder(session));
        Map<Long, OrdineItem> existing = new HashMap<>();
        order.getItems().forEach(item -> existing.put(item.getPiatto().getId(), item));
        for (CustomerOrderItemRequest line : request.getItems()) {
            Piatto dish = dishes.findByIdAndRistoratoreId(line.getDishId(), session.getRistoratore().getId())
                    .filter(p -> Boolean.TRUE.equals(p.getDisponibile()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Piatto non disponibile"));
            OrdineItem current = existing.get(dish.getId());
            if (current != null) current.setQuantity(current.getQuantity() + line.getQuantity());
            else {
                current = OrdineItem.builder().ordine(order).piatto(dish).nome(dish.getNome()).prezzoUnitario(dish.getPrezzo())
                        .portionKey("standard").portionLabel("Standard").quantity(line.getQuantity()).imageUrl(dish.getImageUrl())
                        .source("demo").createdAt(LocalDateTime.now()).build();
                order.getItems().add(current); existing.put(dish.getId(), current);
            }
        }
        order.setNoteCucina(normalizeNote(request.getNoteCucina()));
        order.setUpdatedAt(LocalDateTime.now()); recalculate(order);
        Ordine saved = orders.saveAndFlush(order);
        submissions.saveAndFlush(CustomerOrderSubmission.builder()
                .restaurantId(session.getRistoratore().getId())
                .tableId(session.getTavolo().getNumero())
                .requestId(requestId)
                .payloadHash(payloadHash)
                .order(saved)
                .createdAt(LocalDateTime.now())
                .build());
        session.setOrderCount(session.getOrderCount() + 1); sessions.save(session);
        streams.publish(session.getId(), saved.getId(), saved.getStatus().name());
        return dto(saved);
    }

    @Transactional(readOnly = true)
    public List<OrdineDTO> active(String token) { DemoSession s=sessionService.require(token); return map(orders.findAllByDemoSessionIdAndStatusInOrderByCreatedAtDesc(s.getId(), ACTIVE)); }
    @Transactional(readOnly = true)
    public CustomerOrderStateDTO state(String token) {
        DemoSession s = sessionService.require(token);
        OrdineDTO current = orders.findAllByDemoSessionIdAndStatusInOrderByCreatedAtDesc(s.getId(), ACTIVE)
                .stream().findFirst().map(this::dto).orElse(null);
        return CustomerOrderStateDTO.builder().currentOrder(current)
                .draft(CustomerDraftDTO.builder().restaurantId(s.getRistoratore().getId())
                        .tableId(s.getTavolo().getNumero()).items(List.of()).build()).build();
    }
    @Transactional(readOnly = true)
    public List<OrderSummaryDTO> activeSummaries(String token) { return active(token).stream().map(this::summary).toList(); }
    @Transactional(readOnly = true)
    public List<OrdineDTO> history(String token) { DemoSession s=sessionService.require(token); return map(orders.findAllByDemoSessionIdOrderByCreatedAtDesc(s.getId()).stream().filter(o -> !ACTIVE.contains(o.getStatus())).toList()); }
    @Transactional(readOnly = true)
    public OrdineDTO get(String token, Long id) { DemoSession s=sessionService.require(token); return dto(orders.findByIdAndDemoSessionId(id,s.getId()).orElseThrow(this::notFound)); }

    @Transactional
    public OrdineDTO pay(String token, Long id, PaymentRequest request) {
        DemoSession session = sessionService.require(token);
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                "demo-payment:" + session.getId() + ":" + id);
        Ordine order = orders.findByIdAndDemoSessionId(id, session.getId()).orElseThrow(this::notFound);
        if (!ACTIVE.contains(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ordine demo già chiuso");
        }

        if (order.getPayments() == null) order.setPayments(new ArrayList<>());
        BigDecimal paid = paidAmount(order);
        BigDecimal remaining = order.getTotale().subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        String mode = request == null || request.getPaymentMode() == null || request.getPaymentMode().isBlank()
                ? "FULL" : request.getPaymentMode().trim().toUpperCase(Locale.ROOT);
        List<OrdinePagamentoAllocazione> allocations = buildAllocations(order, request);
        if ("SPLIT_ITEMS".equals(mode) && allocations.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seleziona almeno un piatto");
        BigDecimal amount = !allocations.isEmpty()
                ? allocations.stream().map(a -> a.getUnitPrice().multiply(BigDecimal.valueOf(a.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP)
                : request == null || request.getAmount() == null
                ? remaining
                : request.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Importo pagamento non valido");
        if (amount.compareTo(remaining) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Importo superiore al residuo");

        String participant = request == null || request.getParticipantName() == null || request.getParticipantName().isBlank()
                ? null
                : request.getParticipantName().trim().substring(0, Math.min(100, request.getParticipantName().trim().length()));
        OrdinePagamento payment = OrdinePagamento.builder().ordine(order).amount(amount).paymentMode(mode)
                .participantName(participant).createdAt(LocalDateTime.now()).allocations(new ArrayList<>()).build();
        for (OrdinePagamentoAllocazione allocation : allocations) {
            allocation.setPayment(payment);
            payment.getAllocations().add(allocation);
        }
        order.getPayments().add(payment);
        BigDecimal newRemaining = remaining.subtract(amount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        order.setPaymentMode(mode);
        order.setUpdatedAt(LocalDateTime.now());
        if (newRemaining.compareTo(BigDecimal.ZERO) == 0) {
            order.setStatus(OrderStatus.PAGATO);
            order.setPaidAt(LocalDateTime.now());
        } else {
            order.setStatus(OrderStatus.PARZIALMENTE_PAGATO);
            order.setPaidAt(null);
        }
        Ordine saved = orders.saveAndFlush(order);
        streams.publish(session.getId(), saved.getId(), saved.getStatus().name());
        return dto(saved);
    }

    @Transactional
    public OrdineDTO complete(String token, Long id) {
        DemoSession s=sessionService.require(token); Ordine order=orders.findByIdAndDemoSessionId(id,s.getId()).orElseThrow(this::notFound);
        order.setStatus(OrderStatus.PAGATO); order.setPaidAt(LocalDateTime.now()); order.setUpdatedAt(LocalDateTime.now());
        Ordine saved=orders.save(order); streams.publish(s.getId(),saved.getId(),saved.getStatus().name()); return dto(saved);
    }

    private void lockActiveOrderMutation(Long restaurantId, Integer tableId) {
        String resourceKey = "active-order:" + restaurantId + ":" + tableId;
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, resourceKey);
    }

    public record WaiterCallState(boolean pending, LocalDateTime calledAt) {}

    @Transactional(readOnly = true)
    public WaiterCallState waiterCallState(String token) {
        Tavolo table = sessionService.require(token).getTavolo();
        return new WaiterCallState(Boolean.TRUE.equals(table.getWaiterCallPending()), table.getWaiterCalledAt());
    }

    @Transactional
    public void callWaiter(String token) {
        DemoSession session = sessionService.require(token);
        Tavolo table = session.getTavolo();
        table.setWaiterCallPending(true);
        table.setWaiterCalledAt(LocalDateTime.now());
        streams.publishWaiterCall(session.getId(), session.getRistoratore().getId(), table.getNumero(), true);
    }

    @Transactional
    public void clearWaiterCall(String token) {
        DemoSession session = sessionService.require(token);
        session.getTavolo().setWaiterCallPending(false);
        session.getTavolo().setWaiterCalledAt(null);
        streams.publishWaiterCall(session.getId(), session.getRistoratore().getId(), session.getTavolo().getNumero(), false);
    }

    private Map<Long, Integer> paidQuantities(Ordine order) {
        Map<Long, Integer> result = new HashMap<>();
        for (OrdinePagamento payment : Optional.ofNullable(order.getPayments()).orElse(List.of())) {
            for (OrdinePagamentoAllocazione allocation : Optional.ofNullable(payment.getAllocations()).orElse(List.of())) {
                result.merge(allocation.getOrderItem().getId(), allocation.getQuantity(), Integer::sum);
            }
        }
        return result;
    }

    private List<OrdinePagamentoAllocazione> buildAllocations(Ordine order, PaymentRequest request) {
        if (request == null || request.getAllocations() == null) return List.of();
        Map<Long, Integer> paid = paidQuantities(order);
        Map<Long, Integer> selected = new HashMap<>();
        Map<Long, OrdineItem> items = new HashMap<>();
        order.getItems().forEach(item -> items.put(item.getId(), item));
        List<OrdinePagamentoAllocazione> result = new ArrayList<>();
        for (PaymentAllocationRequest entry : request.getAllocations()) {
            if (entry == null || entry.getOrderItemId() == null || entry.getQuantity() == null || entry.getQuantity() <= 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allocazione pagamento non valida");
            OrdineItem item = items.get(entry.getOrderItemId());
            if (item == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Riga ordine non trovata");
            long quantity = (long) selected.getOrDefault(item.getId(), 0) + entry.getQuantity();
            if (quantity > item.getQuantity() - paid.getOrDefault(item.getId(), 0))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantita selezionata superiore al residuo");
            selected.put(item.getId(), (int) quantity);
            result.add(OrdinePagamentoAllocazione.builder().orderItem(item).quantity(entry.getQuantity())
                    .unitPrice(item.getPrezzoUnitario()).build());
        }
        return result;
    }
    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId obbligatorio");
        try { return UUID.fromString(requestId.trim()).toString(); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("requestId non valido"); }
    }
    private String hashSubmitPayload(DemoOrderRequest request) {
        StringBuilder canonical = new StringBuilder();
        appendHashValue(canonical, request.getNoteCucina());
        for (CustomerOrderItemRequest item : request.getItems()) {
            appendHashValue(canonical, item == null ? null : item.getDishId());
            appendHashValue(canonical, item == null ? null : item.getQuantity());
            appendHashValue(canonical, item == null ? null : item.getPortionKey());
            appendHashValue(canonical, item == null ? null : item.getSource());
            appendHashValue(canonical, item == null ? null : item.getSourceDishId());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 non disponibile", impossible);
        }
    }
    private void appendHashValue(StringBuilder target, Object value) {
        String normalized = value == null ? "" : value.toString().trim();
        target.append(normalized.length()).append(':').append(normalized).append('|');
    }

    private Ordine newOrder(DemoSession s) { LocalDateTime now=LocalDateTime.now(); return Ordine.builder().ristoratore(s.getRistoratore())
            .tableId(s.getTavolo().getNumero()).status(OrderStatus.APERTO).createdAt(now).updatedAt(now).totale(BigDecimal.ZERO)
            .variant("DEMO").sessionId(s.getId().toString()).locationUnverified(false).itemCount(0).demo(true).demoSession(s)
            .items(new ArrayList<>()).payments(new ArrayList<>()).build(); }
    private void validate(DemoOrderRequest r) { if(r==null||r.getItems()==null||r.getItems().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ordine vuoto");
        if(r.getItems().size()>maxLines) throw limit("Troppe righe nell'ordine demo");
        for(CustomerOrderItemRequest i:r.getItems()) if(i.getDishId()==null||i.getQuantity()==null||i.getQuantity()<1||i.getQuantity()>maxQuantity) throw limit("Quantità non valida per la demo"); }
    private void recalculate(Ordine o) { BigDecimal total=o.getItems().stream().map(i->i.getPrezzoUnitario().multiply(BigDecimal.valueOf(i.getQuantity()))).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.HALF_UP); o.setTotale(total); o.setItemCount(o.getItems().stream().mapToInt(OrdineItem::getQuantity).sum()); }
    private String normalizeNote(String n){if(n==null||n.isBlank())return null;return n.trim().substring(0,Math.min(500,n.trim().length()));}
    private List<OrdineDTO> map(List<Ordine> values){return values.stream().map(this::dto).toList();}
    private BigDecimal paidAmount(Ordine o){return Optional.ofNullable(o.getPayments()).orElse(List.of()).stream().map(OrdinePagamento::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.HALF_UP);}
    private OrdineDTO dto(Ordine order) {
        Map<Long, Integer> quantities = paidQuantities(order);
        List<OrdineItemDTO> items = order.getItems().stream().map(item -> OrdineItemDTO.builder()
                .id(item.getId()).dishId(item.getPiatto().getId()).nome(item.getNome())
                .prezzoUnitario(item.getPrezzoUnitario()).portionKey(item.getPortionKey()).portionLabel(item.getPortionLabel())
                .quantita(item.getQuantity()).paidQuantity(quantities.getOrDefault(item.getId(), 0))
                .remainingQuantity(Math.max(0, item.getQuantity() - quantities.getOrDefault(item.getId(), 0)))
                .subtotale(item.getPrezzoUnitario().multiply(BigDecimal.valueOf(item.getQuantity())))
                .imageUrl(item.getImageUrl()).build()).toList();
        BigDecimal paid = paidAmount(order);
        List<OrdinePaymentDTO> payments = Optional.ofNullable(order.getPayments()).orElse(List.of()).stream()
                .map(payment -> OrdinePaymentDTO.builder().id(payment.getId()).amount(payment.getAmount())
                        .paymentMode(payment.getPaymentMode()).participantName(payment.getParticipantName()).createdAt(payment.getCreatedAt())
                        .allocations(Optional.ofNullable(payment.getAllocations()).orElse(List.of()).stream()
                                .map(a -> OrdinePaymentAllocationDTO.builder().id(a.getId()).orderItemId(a.getOrderItem().getId())
                                        .itemName(a.getOrderItem().getNome()).quantity(a.getQuantity()).unitPrice(a.getUnitPrice())
                                        .subtotal(a.getUnitPrice().multiply(BigDecimal.valueOf(a.getQuantity()))).build()).toList())
                        .build()).toList();
        return OrdineDTO.builder().id(order.getId()).restaurantId(order.getRistoratore().getId()).tableId(order.getTableId())
                .status(order.getStatus().name()).paymentMode(order.getPaymentMode()).noteCucina(order.getNoteCucina())
                .paidAt(order.getPaidAt()).createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt()).totale(order.getTotale())
                .paidAmount(paid).remainingAmount(order.getTotale().subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP))
                .locationUnverified(false).items(items).payments(payments).build();
    }
    private OrderSummaryDTO summary(OrdineDTO o){return OrderSummaryDTO.builder().id(o.getId()).tableId(o.getTableId()).status(o.getStatus()).paidAt(o.getPaidAt()).createdAt(o.getCreatedAt()).updatedAt(o.getUpdatedAt()).totale(o.getTotale()).itemCount(o.getItems().stream().mapToInt(OrdineItemDTO::getQuantita).sum()).locationUnverified(false).build();}
    private ResponseStatusException limit(String m){return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,m);}
    private ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Ordine demo non trovato");}
}
