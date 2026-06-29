package com.waitero.back.service;

import com.waitero.back.entity.Ordine;
import com.waitero.back.entity.OrdineItem;
import com.waitero.back.entity.Stampante;
import com.waitero.back.printer.PrinterAdapter;
import com.waitero.back.repository.OrdineRepository;
import com.waitero.back.repository.StampanteRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderPrintService {

    private static final Logger log = LoggerFactory.getLogger(OrderPrintService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALY);
    private static final String LOCATION_UNVERIFIED_WARNING = "Posizione non verificata. Controllare la presenza al tavolo";

    private final OrdineRepository ordineRepository;
    private final StampanteRepository stampanteRepository;
    private final List<PrinterAdapter> printerAdapters;

    @Transactional(readOnly = true)
    public void printOrder(Long ordineId) {
        printOrder(ordineId, Map.of());
    }

    @Transactional(readOnly = true)
    public void printOrder(Long ordineId, Map<String, Integer> newQuantitiesByLineKey) {
        Ordine ordine = ordineRepository.findById(ordineId)
                .orElseThrow(() -> new RuntimeException("Ordine non trovato"));
        Long ristoranteId = ordine.getRistoratore().getId();
        List<Stampante> stampanti = stampanteRepository.findByRistoranteIdAndAbilitataTrue(ristoranteId);
        String ticket = formatKitchenTicket(ordine, newQuantitiesByLineKey == null ? Map.of() : newQuantitiesByLineKey);

        log.info("[PRINT] Ordine {} pronto per POS Sunmi locale via evento SSE", ordine.getId());

        for (Stampante stampante : stampanti) {
            try {
                resolveAdapter(stampante).print(stampante, ticket);
                log.info("[PRINT] Ordine {} inviato a stampante {} ({})", ordine.getId(), stampante.getNome(), formatEndpoint(stampante));
            } catch (RuntimeException ex) {
                log.warn("[PRINT] Errore stampa ordine {} su stampante {} ({})",
                        ordine.getId(),
                        stampante.getNome(),
                        formatEndpoint(stampante),
                        ex);
            }
        }
    }

    private PrinterAdapter resolveAdapter(Stampante stampante) {
        return printerAdapters.stream()
                .filter(adapter -> adapter.supports(stampante.getModello()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Modello stampante non supportato: " + stampante.getModello()));
    }

    private String formatKitchenTicket(Ordine ordine, Map<String, Integer> newQuantitiesByLineKey) {
        StringBuilder builder = new StringBuilder()
                .append("========================\n")
                .append("WAITERO\n")
                .append("NUOVO ORDINE\n")
                .append("========================\n\n")
                .append("Tavolo: ").append(ordine.getTableId()).append("\n")
                .append("Ordine: #").append(ordine.getId()).append("\n")
                .append("Ora: ").append(ordine.getCreatedAt() != null ? TIME_FORMATTER.format(ordine.getCreatedAt()) : "-").append("\n\n");

        if (Boolean.TRUE.equals(ordine.getLocationUnverified())) {
            builder.append("ATTENZIONE:\n")
                    .append(wrap(LOCATION_UNVERIFIED_WARNING, 32))
                    .append("\n\n");
        }

        builder.append("------------------------\n\n");

        List<TicketLine> newLines = new ArrayList<>();
        List<TicketLine> printedLines = new ArrayList<>();
        for (OrdineItem item : ordine.getItems()) {
            int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
            int newQuantity = Math.min(newQuantitiesByLineKey.getOrDefault(orderLineKey(item), quantity), quantity);
            int printedQuantity = Math.max(quantity - newQuantity, 0);
            String itemName = formatItemName(item);

            if (newQuantity > 0) {
                newLines.add(new TicketLine(newQuantity, itemName));
            }
            if (printedQuantity > 0) {
                printedLines.add(new TicketLine(printedQuantity, itemName));
            }
        }

        if (!printedLines.isEmpty()) {
            builder.append("NUOVI PIATTI\n\n");
        }
        for (TicketLine line : newLines) {
            builder.append(line.quantity()).append("x ").append(line.name()).append("\n");
        }

        if (!printedLines.isEmpty()) {
            builder.append("\nGIA STAMPATI\n\n");
            for (TicketLine line : printedLines) {
                builder.append(line.quantity()).append("x ").append(line.name()).append("\n");
            }
        }

        if (ordine.getNoteCucina() != null && !ordine.getNoteCucina().isBlank()) {
            builder.append("\nNOTE:\n")
                    .append(wrap(ordine.getNoteCucina().trim(), 32))
                    .append("\n");
        }

        int totalItems = ordine.getItems().stream()
                .map(OrdineItem::getQuantity)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        return builder
                .append("\n------------------------\n\n")
                .append("Totale piatti: ").append(totalItems).append("\n\n")
                .append("========================\n")
                .toString();
    }

    private String formatItemName(OrdineItem item) {
        if (item.getPortionLabel() == null || item.getPortionLabel().isBlank() || "Standard".equalsIgnoreCase(item.getPortionLabel())) {
            return item.getNome();
        }
        return item.getNome() + " - " + item.getPortionLabel();
    }

    private String orderLineKey(OrdineItem item) {
        String portionKey = item.getPortionKey() == null || item.getPortionKey().isBlank()
                ? DishPortionService.DEFAULT_PORTION_KEY
                : item.getPortionKey().trim();
        return item.getPiatto().getId() + "::" + portionKey;
    }

    private String wrap(String value, int width) {
        StringBuilder result = new StringBuilder();
        StringBuilder line = new StringBuilder();
        for (String word : value.split("\\s+")) {
            if (word.length() > width) {
                if (!line.isEmpty()) {
                    result.append(line).append('\n');
                    line = new StringBuilder();
                }
                for (int index = 0; index < word.length(); index += width) {
                    result.append(word, index, Math.min(index + width, word.length())).append('\n');
                }
                continue;
            }

            int nextLength = line.isEmpty() ? word.length() : line.length() + 1 + word.length();
            if (nextLength > width) {
                result.append(line).append('\n');
                line = new StringBuilder(word);
            } else {
                if (!line.isEmpty()) {
                    line.append(' ');
                }
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            result.append(line);
        }
        return result.toString();
    }

    private String formatEndpoint(Stampante stampante) {
        if (stampante.getIpAddress() == null || stampante.getPorta() == null) {
            return stampante.getTipoConnessione().name();
        }
        return stampante.getIpAddress() + ":" + stampante.getPorta();
    }

    private record TicketLine(int quantity, String name) {
    }
}
