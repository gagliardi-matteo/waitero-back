package com.waitero.back.service;

import com.waitero.back.entity.Ordine;
import com.waitero.back.entity.Stampante;
import com.waitero.back.repository.OrdineRepository;
import com.waitero.back.repository.StampanteRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderPrintService {

    private static final Logger log = LoggerFactory.getLogger(OrderPrintService.class);

    private final OrdineRepository ordineRepository;
    private final StampanteRepository stampanteRepository;

    @Transactional(readOnly = true)
    public void printOrder(Long ordineId) {
        Ordine ordine = ordineRepository.findById(ordineId)
                .orElseThrow(() -> new RuntimeException("Ordine non trovato"));
        Long ristoranteId = ordine.getRistoratore().getId();
        List<Stampante> stampanti = stampanteRepository.findByRistoranteIdAndAbilitataTrue(ristoranteId);

        StringBuilder builder = new StringBuilder()
                .append("[PRINT]\n\n")
                .append("Ordine: ").append(ordine.getId()).append("\n\n")
                .append("POS Sunmi -> stampa eseguita\n\n");

        for (Stampante stampante : stampanti) {
            builder.append("Stampante:\n")
                    .append(stampante.getModello()).append("\n")
                    .append(formatEndpoint(stampante)).append("\n\n");
        }

        log.info(builder.toString().trim());
    }

    private String formatEndpoint(Stampante stampante) {
        if (stampante.getIpAddress() == null || stampante.getPorta() == null) {
            return stampante.getTipoConnessione().name();
        }
        return stampante.getIpAddress() + ":" + stampante.getPorta();
    }
}
