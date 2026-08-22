package com.waitero.back.service;

import com.waitero.back.dto.*;
import com.waitero.back.entity.*;
import com.waitero.back.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
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
    @Value("${app.demo.max-orders:10}") private int maxOrders;
    @Value("${app.demo.max-lines:20}") private int maxLines;
    @Value("${app.demo.max-quantity:10}") private int maxQuantity;

    @Transactional
    public OrdineDTO submit(String token, DemoOrderRequest request) {
        DemoSession session = sessionService.require(token);
        validate(request);
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
    public OrdineDTO complete(String token, Long id) {
        DemoSession s=sessionService.require(token); Ordine order=orders.findByIdAndDemoSessionId(id,s.getId()).orElseThrow(this::notFound);
        order.setStatus(OrderStatus.PAGATO); order.setPaidAt(LocalDateTime.now()); order.setUpdatedAt(LocalDateTime.now());
        Ordine saved=orders.save(order); streams.publish(s.getId(),saved.getId(),saved.getStatus().name()); return dto(saved);
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
    private OrdineDTO dto(Ordine o){List<OrdineItemDTO> items=o.getItems().stream().map(i->OrdineItemDTO.builder().id(i.getId()).dishId(i.getPiatto().getId()).nome(i.getNome()).prezzoUnitario(i.getPrezzoUnitario()).portionKey(i.getPortionKey()).portionLabel(i.getPortionLabel()).quantita(i.getQuantity()).paidQuantity(0).remainingQuantity(i.getQuantity()).subtotale(i.getPrezzoUnitario().multiply(BigDecimal.valueOf(i.getQuantity()))).imageUrl(i.getImageUrl()).build()).toList(); return OrdineDTO.builder().id(o.getId()).restaurantId(o.getRistoratore().getId()).tableId(o.getTableId()).status(o.getStatus().name()).noteCucina(o.getNoteCucina()).paidAt(o.getPaidAt()).createdAt(o.getCreatedAt()).updatedAt(o.getUpdatedAt()).totale(o.getTotale()).paidAmount(BigDecimal.ZERO).remainingAmount(o.getStatus()==OrderStatus.PAGATO?BigDecimal.ZERO:o.getTotale()).locationUnverified(false).items(items).payments(List.of()).build();}
    private OrderSummaryDTO summary(OrdineDTO o){return OrderSummaryDTO.builder().id(o.getId()).tableId(o.getTableId()).status(o.getStatus()).paidAt(o.getPaidAt()).createdAt(o.getCreatedAt()).updatedAt(o.getUpdatedAt()).totale(o.getTotale()).itemCount(o.getItems().stream().mapToInt(OrdineItemDTO::getQuantita).sum()).locationUnverified(false).build();}
    private ResponseStatusException limit(String m){return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,m);}
    private ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Ordine demo non trovato");}
}
