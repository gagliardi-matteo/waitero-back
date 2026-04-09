package com.waitero.back.service;

import com.waitero.back.dto.GeocodeResult;
import com.waitero.back.dto.PublicRestaurantDTO;
import com.waitero.back.dto.RestaurantSettingsDTO;
import com.waitero.back.dto.RestaurantSettingsRequest;
import com.waitero.back.dto.ServiceHourDTO;
import com.waitero.back.dto.ServiceHourRequest;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.entity.ServiceHour;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.repository.ServiceHourRepository;
import lombok.RequiredArgsConstructor;
import com.waitero.back.security.AccessContextService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RistoratoreService {

    private final RistoratoreRepository ristoratoreRepository;
    private final ServiceHourRepository serviceHourRepository;
    private final GeocodingService geocodingService;
    private final AccessContextService accessContextService;

    public Optional<Ristoratore> findRistoratoreById(Long id) {
        return ristoratoreRepository.findById(id);
    }

    public Optional<PublicRestaurantDTO> findPublicRestaurantById(Long id) {
        return ristoratoreRepository.findById(id).map(this::toPublicRestaurantDto);
    }

    @Transactional(readOnly = true)
    public RestaurantSettingsDTO getAuthenticatedRestaurantSettings() {
        Ristoratore restaurant = getAuthenticatedRestaurant();
        return toSettingsDto(restaurant, serviceHourRepository.findAllByRistoratoreIdOrderByDayOfWeekAscStartTimeAsc(restaurant.getId()));
    }

    @Transactional
    public RestaurantSettingsDTO updateAuthenticatedRestaurantSettings(RestaurantSettingsRequest request) {
        Ristoratore restaurant = getAuthenticatedRestaurant();
        validateRequest(request);

        restaurant.setNome(request.getNome().trim());
        restaurant.setAddress(request.getAddress().trim());
        restaurant.setCity(request.getCity() == null ? null : request.getCity().trim());
        restaurant.setAllowedRadiusMeters(request.getAllowedRadiusMeters());

        if (hasExplicitSelection(request)) {
            restaurant.setLatitude(request.getLatitude());
            restaurant.setLongitude(request.getLongitude());
            restaurant.setFormattedAddress(request.getFormattedAddress());
            restaurant.setCity(request.getCity());
            restaurant.setAddress(request.getAddress().trim());
        } else {
            GeocodeResult geocode = geocodingService.geocodeAddress(request.getAddress(), request.getCity());
            if (!geocode.isHasStreetNumber()) {
                throw new RuntimeException("Inserisci un indirizzo completo di numero civico");
            }
            restaurant.setLatitude(geocode.getLatitude());
            restaurant.setLongitude(geocode.getLongitude());
            restaurant.setFormattedAddress(geocode.getFormattedAddress());
            restaurant.setCity(geocode.getCity());
            restaurant.setAddress(geocode.getResolvedAddress());
        }

        Ristoratore saved = ristoratoreRepository.save(restaurant);
        serviceHourRepository.deleteAllByRistoratoreId(saved.getId());
        List<ServiceHour> hours = normalizeServiceHours(saved, request.getServiceHours());
        serviceHourRepository.saveAll(hours);

        return toSettingsDto(saved, serviceHourRepository.findAllByRistoratoreIdOrderByDayOfWeekAscStartTimeAsc(saved.getId()));
    }

    private boolean hasExplicitSelection(RestaurantSettingsRequest request) {
        return Boolean.TRUE.equals(request.getHasStreetNumber())
                && request.getLatitude() != null
                && request.getLongitude() != null
                && request.getFormattedAddress() != null
                && !request.getFormattedAddress().isBlank();
    }

    private List<ServiceHour> normalizeServiceHours(Ristoratore restaurant, List<ServiceHourRequest> requests) {
        if (requests == null) {
            return List.of();
        }

        return requests.stream()
                .filter(item -> item.getDayOfWeek() != null && item.getStartTime() != null && item.getEndTime() != null)
                .map(item -> {
                    DayOfWeek day = DayOfWeek.valueOf(item.getDayOfWeek().trim().toUpperCase());
                    LocalTime start = LocalTime.parse(item.getStartTime().trim());
                    LocalTime end = LocalTime.parse(item.getEndTime().trim());
                    if (!end.isAfter(start)) {
                        throw new RuntimeException("Fascia oraria non valida per " + day);
                    }
                    return ServiceHour.builder()
                            .ristoratore(restaurant)
                            .dayOfWeek(day)
                            .startTime(start)
                            .endTime(end)
                            .build();
                })
                .sorted(Comparator.comparing(ServiceHour::getDayOfWeek).thenComparing(ServiceHour::getStartTime))
                .toList();
    }

    private RestaurantSettingsDTO toSettingsDto(Ristoratore restaurant, List<ServiceHour> serviceHours) {
        return RestaurantSettingsDTO.builder()
                .id(restaurant.getId())
                .nome(restaurant.getNome())
                .email(restaurant.getEmail())
                .address(restaurant.getAddress())
                .city(restaurant.getCity())
                .formattedAddress(restaurant.getFormattedAddress())
                .latitude(restaurant.getLatitude())
                .longitude(restaurant.getLongitude())
                .allowedRadiusMeters(restaurant.getAllowedRadiusMeters())
                .serviceHours(serviceHours.stream()
                        .map(item -> ServiceHourDTO.builder()
                                .id(item.getId())
                                .dayOfWeek(item.getDayOfWeek().name())
                                .startTime(item.getStartTime().toString())
                                .endTime(item.getEndTime().toString())
                                .build())
                        .toList())
                .build();
    }

    private PublicRestaurantDTO toPublicRestaurantDto(Ristoratore restaurant) {
        return PublicRestaurantDTO.builder()
                .id(restaurant.getId())
                .nome(restaurant.getNome())
                .formattedAddress(restaurant.getFormattedAddress())
                .build();
    }

    private void validateRequest(RestaurantSettingsRequest request) {
        if (request == null) {
            throw new RuntimeException("Dati ristorante mancanti");
        }
        if (request.getNome() == null || request.getNome().trim().isBlank()) {
            throw new RuntimeException("Nome ristorante obbligatorio");
        }
        if (request.getAddress() == null || request.getAddress().trim().isBlank()) {
            throw new RuntimeException("Indirizzo obbligatorio");
        }
        if (request.getAllowedRadiusMeters() == null || request.getAllowedRadiusMeters() < 20) {
            throw new RuntimeException("Raggio consentito non valido");
        }
    }

    private Ristoratore getAuthenticatedRestaurant() {
        Long restaurantId = accessContextService.getActingRestaurantIdOrThrow();
        return ristoratoreRepository.findById(restaurantId)
                .orElseThrow(() -> new RuntimeException("Ristorante non trovato"));
    }
}




