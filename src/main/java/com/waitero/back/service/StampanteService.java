package com.waitero.back.service;

import com.waitero.back.dto.StampanteRequest;
import com.waitero.back.dto.StampanteResponse;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.entity.Stampante;
import com.waitero.back.entity.TipoConnessione;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.repository.StampanteRepository;
import com.waitero.back.security.AccessContextService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StampanteService {

    private static final Logger log = LoggerFactory.getLogger(StampanteService.class);

    private final StampanteRepository stampanteRepository;
    private final RistoratoreRepository ristoratoreRepository;
    private final AccessContextService accessContextService;

    @Transactional
    public StampanteResponse create(StampanteRequest request) {
        validateRequest(request);
        Long ristoranteId = resolveTargetRestaurantId(request.getRistoranteId());
        Ristoratore ristorante = ristoratoreRepository.findById(ristoranteId)
                .orElseThrow(() -> new RuntimeException("Locale non trovato"));

        Stampante stampante = Stampante.builder()
                .ristorante(ristorante)
                .nome(normalizeText(request.getNome()))
                .modello(request.getModello())
                .tipoConnessione(request.getTipoConnessione())
                .ipAddress(normalizeNullableText(request.getIpAddress()))
                .porta(request.getPorta())
                .abilitata(request.getAbilitata() == null || request.getAbilitata())
                .dataCreazione(LocalDateTime.now())
                .build();

        return toResponse(stampanteRepository.save(stampante));
    }

    @Transactional
    public StampanteResponse update(Long id, StampanteRequest request) {
        validateRequest(request);
        Stampante stampante = getOwnedStampante(id);
        stampante.setNome(normalizeText(request.getNome()));
        stampante.setModello(request.getModello());
        stampante.setTipoConnessione(request.getTipoConnessione());
        stampante.setIpAddress(normalizeNullableText(request.getIpAddress()));
        stampante.setPorta(request.getPorta());
        stampante.setAbilitata(request.getAbilitata() == null || request.getAbilitata());
        return toResponse(stampanteRepository.save(stampante));
    }

    @Transactional
    public void delete(Long id) {
        Stampante stampante = getOwnedStampante(id);
        stampanteRepository.delete(stampante);
    }

    @Transactional(readOnly = true)
    public StampanteResponse findById(Long id) {
        return toResponse(getOwnedStampante(id));
    }

    @Transactional(readOnly = true)
    public List<StampanteResponse> findByRistorante(Long ristoranteId) {
        requireRestaurantAccess(ristoranteId);
        return stampanteRepository.findByRistoranteId(ristoranteId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public StampanteResponse enable(Long id) {
        Stampante stampante = getOwnedStampante(id);
        stampante.setAbilitata(true);
        return toResponse(stampanteRepository.save(stampante));
    }

    @Transactional
    public StampanteResponse disable(Long id) {
        Stampante stampante = getOwnedStampante(id);
        stampante.setAbilitata(false);
        return toResponse(stampanteRepository.save(stampante));
    }

    @Transactional(readOnly = true)
    public void testPrint(Long id) {
        Stampante stampante = getOwnedStampante(id);
        log.info("""
                [TEST PRINT]

                Stampante esterna: {}
                Modello: {}
                Connessione: {}
                Endpoint: {}

                Stampa reale non ancora implementata.
                """,
                stampante.getNome(),
                stampante.getModello(),
                stampante.getTipoConnessione(),
                formatEndpoint(stampante)
        );
    }

    public StampanteResponse toResponse(Stampante stampante) {
        return StampanteResponse.builder()
                .id(stampante.getId())
                .ristoranteId(stampante.getRistorante().getId())
                .nome(stampante.getNome())
                .modello(stampante.getModello())
                .tipoConnessione(stampante.getTipoConnessione())
                .ipAddress(stampante.getIpAddress())
                .porta(stampante.getPorta())
                .abilitata(stampante.getAbilitata())
                .dataCreazione(stampante.getDataCreazione())
                .build();
    }

    private Stampante getOwnedStampante(Long id) {
        Stampante stampante = stampanteRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Stampante non trovata"));
        requireRestaurantAccess(stampante.getRistorante().getId());
        return stampante;
    }

    private Long resolveTargetRestaurantId(Long requestedRestaurantId) {
        Long actingRestaurantId = accessContextService.getActingRestaurantIdOrThrow();
        Long targetRestaurantId = requestedRestaurantId == null ? actingRestaurantId : requestedRestaurantId;
        requireRestaurantAccess(targetRestaurantId);
        return targetRestaurantId;
    }

    private void requireRestaurantAccess(Long ristoranteId) {
        Long actingRestaurantId = accessContextService.getActingRestaurantIdOrThrow();
        if (!actingRestaurantId.equals(ristoranteId)) {
            throw new RuntimeException("Stampante non accessibile");
        }
    }

    private void validateRequest(StampanteRequest request) {
        if (request == null) {
            throw new RuntimeException("Dati stampante mancanti");
        }
        if (normalizeText(request.getNome()).isBlank()) {
            throw new RuntimeException("Nome stampante obbligatorio");
        }
        if (request.getModello() == null) {
            throw new RuntimeException("Modello stampante obbligatorio");
        }
        if (request.getTipoConnessione() == null) {
            throw new RuntimeException("Tipo connessione obbligatorio");
        }
        if (request.getTipoConnessione() == TipoConnessione.TCP_IP) {
            if (normalizeNullableText(request.getIpAddress()) == null) {
                throw new RuntimeException("IP obbligatorio per stampanti TCP/IP");
            }
            if (request.getPorta() == null || request.getPorta() <= 0 || request.getPorta() > 65535) {
                throw new RuntimeException("Porta TCP/IP non valida");
            }
        }
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeNullableText(String value) {
        String normalized = normalizeText(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String formatEndpoint(Stampante stampante) {
        if (stampante.getIpAddress() == null || stampante.getPorta() == null) {
            return stampante.getTipoConnessione().name();
        }
        return stampante.getIpAddress() + ":" + stampante.getPorta();
    }
}
