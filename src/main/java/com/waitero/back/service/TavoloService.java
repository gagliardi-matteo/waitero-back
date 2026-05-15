package com.waitero.back.service;

import com.waitero.back.dto.TavoloDTO;
import com.waitero.back.dto.TavoloRequest;
import com.waitero.back.dto.BulkTableCreateRequest;
import com.waitero.back.entity.OrderStatus;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.entity.ServiceHour;
import com.waitero.back.entity.Tavolo;
import com.waitero.back.repository.OrdineRepository;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.repository.ServiceHourRepository;
import com.waitero.back.repository.TableDeviceRepository;
import com.waitero.back.repository.TavoloRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.waitero.back.security.AccessContextService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TavoloService {

    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(OrderStatus.APERTO, OrderStatus.PARZIALMENTE_PAGATO);
    private static final Logger log = LoggerFactory.getLogger(TavoloService.class);
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Europe/Rome");

    private final TavoloRepository tavoloRepository;
    private final RistoratoreRepository ristoratoreRepository;
    private final OrdineRepository ordineRepository;
    private final TableDeviceRepository tableDeviceRepository;
    private final ServiceHourRepository serviceHourRepository;
    private final JwtService jwtService;
    private final AccessContextService accessContextService;
    private final ServiceHourScheduleService serviceHourScheduleService;

    @Transactional
    public List<TavoloDTO> getAuthenticatedRestaurantTables() {
        Long restaurantId = getAuthenticatedRestaurantId();
        List<Tavolo> tables = tavoloRepository.findAllByRistoratoreIdOrderByNumeroAsc(restaurantId);
        tables.forEach(table -> ensurePersistentIdentifiers(table, restaurantId));
        List<TavoloDTO> result = tables.stream().map(this::toDTO).toList();
        log.info("Tables lookup for authenticated restaurantId={} -> {} tables", restaurantId, result.size());
        return result;
    }

    @Transactional
    public TavoloDTO createForAuthenticatedRestaurant(TavoloRequest request) {
        Long restaurantId = getAuthenticatedRestaurantId();
        validateRequest(request, restaurantId, null);

        Ristoratore ristoratore = ristoratoreRepository.findById(restaurantId)
                .orElseThrow(() -> new RuntimeException("Locale non trovato"));

        Tavolo tavolo = Tavolo.builder()
                .ristoratore(ristoratore)
                .tablePublicId(generateUniqueTablePublicId())
                .numero(request.getNumero())
                .nome(normalizeName(request))
                .coperti(request.getCoperti())
                .attivo(isActive(request))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        tavolo.setQrToken(jwtService.generateQrToken(restaurantId, tavolo.getNumero()));
        return toDTO(tavoloRepository.save(tavolo));
    }

    @Transactional
    public List<TavoloDTO> bulkCreateForAuthenticatedRestaurant(BulkTableCreateRequest request) {
        Long restaurantId = getAuthenticatedRestaurantId();
        validateBulkRequest(request);

        Ristoratore ristoratore = ristoratoreRepository.findById(restaurantId)
                .orElseThrow(() -> new RuntimeException("Locale non trovato"));

        List<Tavolo> existingTables = tavoloRepository.findAllByRistoratoreIdOrderByNumeroAsc(restaurantId);
        int nextNumber = request.getStartingNumber() != null
                ? request.getStartingNumber()
                : existingTables.stream()
                .map(Tavolo::getNumero)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        String namePrefix = request.getNamePrefix() == null || request.getNamePrefix().trim().isBlank()
                ? "T"
                : request.getNamePrefix().trim();

        List<TavoloDTO> created = new java.util.ArrayList<>();
        for (int offset = 0; offset < request.getCount(); offset++) {
            int tableNumber = nextNumber + offset;
            TavoloRequest singleRequest = new TavoloRequest();
            singleRequest.setNumero(tableNumber);
            singleRequest.setNome(namePrefix + tableNumber);
            singleRequest.setCoperti(request.getCoperti());
            singleRequest.setAttivo(request.getAttivo());
            validateRequest(singleRequest, restaurantId, null);

            Tavolo tavolo = Tavolo.builder()
                    .ristoratore(ristoratore)
                    .tablePublicId(generateUniqueTablePublicId())
                    .numero(tableNumber)
                    .nome(normalizeName(singleRequest))
                    .coperti(singleRequest.getCoperti())
                    .attivo(isActive(singleRequest))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            tavolo.setQrToken(jwtService.generateQrToken(restaurantId, tavolo.getNumero()));
            created.add(toDTO(tavoloRepository.save(tavolo)));
        }

        log.info("Bulk table generation completed for restaurantId={} count={} startingNumber={}", restaurantId, created.size(), nextNumber);
        return created;
    }

    @Transactional
    public TavoloDTO updateForAuthenticatedRestaurant(Long tableId, TavoloRequest request) {
        Long restaurantId = getAuthenticatedRestaurantId();
        Tavolo tavolo = getOwnedTable(tableId, restaurantId);
        validateRequest(request, restaurantId, tavolo.getId());

        boolean numeroChanged = !tavolo.getNumero().equals(request.getNumero());
        tavolo.setNumero(request.getNumero());
        tavolo.setNome(normalizeName(request));
        tavolo.setCoperti(request.getCoperti());
        tavolo.setAttivo(isActive(request));
        tavolo.setUpdatedAt(LocalDateTime.now());
        ensurePersistentIdentifiers(tavolo, restaurantId);

        if (numeroChanged) {
            tavolo.setQrToken(jwtService.generateQrToken(restaurantId, tavolo.getNumero()));
        }

        return toDTO(tavoloRepository.save(tavolo));
    }

    @Transactional
    public TavoloDTO regenerateQrTokenForAuthenticatedRestaurant(Long tableId) {
        Long restaurantId = getAuthenticatedRestaurantId();
        Tavolo tavolo = getOwnedTable(tableId, restaurantId);
        ensurePersistentIdentifiers(tavolo, restaurantId);
        tavolo.setQrToken(jwtService.generateQrToken(restaurantId, tavolo.getNumero()));
        tavolo.setUpdatedAt(LocalDateTime.now());
        return toDTO(tavoloRepository.save(tavolo));
    }

    @Transactional
    public void deleteForAuthenticatedRestaurant(Long tableId) {
        Long restaurantId = getAuthenticatedRestaurantId();
        Tavolo tavolo = getOwnedTable(tableId, restaurantId);
        boolean hasActiveOrders = ordineRepository.existsByRistoratoreIdAndTableIdAndStatusIn(restaurantId, tavolo.getNumero(), ACTIVE_STATUSES);
        if (hasActiveOrders) {
            throw new RuntimeException("Impossibile eliminare un tavolo con ordini attivi");
        }
        tableDeviceRepository.deleteAllByTavoloId(tavolo.getId());
        tavoloRepository.delete(tavolo);
    }

    @Transactional(readOnly = true)
    public Tavolo requireActiveTable(Long restaurantId, Integer tableNumber) {
        return tavoloRepository.findByRistoratoreIdAndNumeroAndAttivoTrue(restaurantId, tableNumber)
                .orElseThrow(() -> new RuntimeException("Tavolo non trovato o non attivo"));
    }

    @Transactional(readOnly = true)
    public Tavolo requireActiveTableByPublicId(String tablePublicId) {
        Tavolo tavolo = tavoloRepository.findByTablePublicId(tablePublicId)
                .orElseThrow(() -> new RuntimeException("Tavolo non trovato"));
        if (!Boolean.TRUE.equals(tavolo.getAttivo())) {
            throw new RuntimeException("Tavolo non attivo");
        }
        return tavolo;
    }

    @Transactional(readOnly = true)
    public Tavolo resolveActiveTableForAccess(String tablePublicId, String restaurantId, Integer tableId) {
        if (tablePublicId != null && !tablePublicId.isBlank()) {
            return requireActiveTableByPublicId(tablePublicId.trim());
        }
        if (restaurantId == null || tableId == null) {
            throw new RuntimeException("Dati tavolo mancanti");
        }
        return requireActiveTable(Long.parseLong(restaurantId), tableId);
    }

    @Transactional(readOnly = true)
    public boolean validateQrAccess(String token, String restaurantId, Integer tableId) {
        if (token == null || restaurantId == null || tableId == null) {
            return false;
        }

        if (!jwtService.validateQrToken(token, restaurantId, tableId)) {
            return false;
        }

        try {
            Long parsedRestaurantId = Long.parseLong(restaurantId);
            return tavoloRepository.findByRistoratoreIdAndNumeroAndAttivoTrue(parsedRestaurantId, tableId)
                    .map(Tavolo::getQrToken)
                    .filter(token::equals)
                    .isPresent();
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public boolean validateCustomerAccess(String token, String restaurantId, Integer tableId, String deviceId, String fingerprint) {
        if (!validateQrAccess(token, restaurantId, tableId) || deviceId == null || deviceId.isBlank()) {
            return false;
        }

        try {
            Long parsedRestaurantId = Long.parseLong(restaurantId);
            if (!isWithinServiceHours(parsedRestaurantId)) {
                return false;
            }

            Tavolo tavolo = requireActiveTable(parsedRestaurantId, tableId);
            return tableDeviceRepository.findByTavoloIdAndDeviceId(tavolo.getId(), deviceId.trim())
                    .map(device -> {
                        if (fingerprint == null || fingerprint.isBlank() || device.getFingerprint() == null || device.getFingerprint().isBlank()) {
                            return true;
                        }
                        return fingerprint.trim().equals(device.getFingerprint());
                    })
                    .orElse(false);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public boolean isWithinServiceHours(Long restaurantId) {
        ZonedDateTime now = ZonedDateTime.now(SERVICE_ZONE);
        List<ServiceHour> hours = serviceHourRepository.findAllByRistoratoreIdOrderByDayOfWeekAscStartTimeAsc(restaurantId);
        return serviceHourScheduleService.isOpenAt(hours, now);
    }

    @Transactional
    public void clearRegisteredDevices(Long restaurantId, Integer tableId) {
        tavoloRepository.findByRistoratoreIdAndNumero(restaurantId, tableId)
                .ifPresent(table -> tableDeviceRepository.deleteAllByTavoloId(table.getId()));
    }

    public TavoloDTO toDTO(Tavolo tavolo) {
        return TavoloDTO.builder()
                .id(tavolo.getId())
                .restaurantId(tavolo.getRistoratore().getId())
                .tablePublicId(tavolo.getTablePublicId())
                .numero(tavolo.getNumero())
                .nome(tavolo.getNome())
                .coperti(tavolo.getCoperti())
                .attivo(tavolo.getAttivo())
                .qrToken(tavolo.getQrToken())
                .createdAt(tavolo.getCreatedAt())
                .updatedAt(tavolo.getUpdatedAt())
                .build();
    }

    private Tavolo getOwnedTable(Long tableId, Long restaurantId) {
        Tavolo tavolo = tavoloRepository.findById(tableId)
                .orElseThrow(() -> new RuntimeException("Tavolo non trovato"));

        if (!tavolo.getRistoratore().getId().equals(restaurantId)) {
            throw new RuntimeException("Tavolo non accessibile");
        }

        return tavolo;
    }

    private void validateRequest(TavoloRequest request, Long restaurantId, Long currentTableId) {
        if (request == null) {
            throw new RuntimeException("Dati tavolo mancanti");
        }
        if (request.getNumero() == null || request.getNumero() <= 0) {
            throw new RuntimeException("Numero tavolo non valido");
        }
        if (request.getCoperti() == null || request.getCoperti() <= 0) {
            throw new RuntimeException("Numero coperti non valido");
        }
        if (normalizeName(request).isBlank()) {
            throw new RuntimeException("Nome tavolo obbligatorio");
        }

        tavoloRepository.findByRistoratoreIdAndNumero(restaurantId, request.getNumero())
                .filter(existing -> !existing.getId().equals(currentTableId))
                .ifPresent(existing -> {
                    throw new RuntimeException("Esiste gia un tavolo con questo numero");
                });
    }

    private void validateBulkRequest(BulkTableCreateRequest request) {
        if (request == null) {
            throw new RuntimeException("Dati generazione tavoli mancanti");
        }
        if (request.getCount() == null || request.getCount() <= 0) {
            throw new RuntimeException("Numero tavoli da generare non valido");
        }
        if (request.getCount() > 200) {
            throw new RuntimeException("Puoi generare al massimo 200 tavoli per volta");
        }
        if (request.getCoperti() == null || request.getCoperti() <= 0) {
            throw new RuntimeException("Numero coperti non valido");
        }
        if (request.getStartingNumber() != null && request.getStartingNumber() <= 0) {
            throw new RuntimeException("Numero iniziale non valido");
        }
    }

    private void ensurePersistentIdentifiers(Tavolo tavolo, Long restaurantId) {
        boolean changed = false;
        if (tavolo.getTablePublicId() == null || tavolo.getTablePublicId().isBlank()) {
            tavolo.setTablePublicId(generateUniqueTablePublicId());
            changed = true;
        }
        if (tavolo.getQrToken() == null || tavolo.getQrToken().isBlank()) {
            tavolo.setQrToken(jwtService.generateQrToken(restaurantId, tavolo.getNumero()));
            changed = true;
        }
        if (changed) {
            tavolo.setUpdatedAt(LocalDateTime.now());
            tavoloRepository.save(tavolo);
        }
    }

    private String generateUniqueTablePublicId() {
        String candidate;
        do {
            candidate = "tbl_" + randomBase62(10);
        } while (tavoloRepository.findByTablePublicId(candidate).isPresent());
        return candidate;
    }

    private String randomBase62(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return builder.toString();
    }

    private String normalizeName(TavoloRequest request) {
        return request.getNome() == null ? "" : request.getNome().trim();
    }

    private Boolean isActive(TavoloRequest request) {
        return request.getAttivo() == null || request.getAttivo();
    }

    private Long getAuthenticatedRestaurantId() {
        return accessContextService.getActingRestaurantIdOrThrow();
    }
}




