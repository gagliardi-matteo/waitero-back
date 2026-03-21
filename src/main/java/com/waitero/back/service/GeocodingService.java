package com.waitero.back.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waitero.back.dto.AddressSuggestionDTO;
import com.waitero.back.dto.GeocodeResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class GeocodingService {
    private static final String GOOGLE_GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json";
    private static final String GOOGLE_AUTOCOMPLETE_URL = "https://maps.googleapis.com/maps/api/place/autocomplete/json";
    private static final String GOOGLE_PLACE_DETAILS_URL = "https://maps.googleapis.com/maps/api/place/details/json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${google.maps.api-key:}")
    private String apiKey;

    public GeocodeResult geocodeAddress(String address, String city) {
        ensureApiKeyConfigured();

        String query = buildQuery(address, city);
        if (query.isBlank()) {
            throw new RuntimeException("Indirizzo non valido");
        }

        try {
            JsonNode root = executeRequest(buildGeocodeUri(query));
            JsonNode results = root.path("results");
            if (!"OK".equals(root.path("status").asText()) || !results.isArray() || results.isEmpty()) {
                throw new RuntimeException("Indirizzo non trovato sulla mappa");
            }

            JsonNode best = rankGoogleResults(results, address, city).stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Indirizzo non trovato sulla mappa"));

            String streetNumber = getAddressComponent(best.path("address_components"), "street_number");
            String route = getAddressComponent(best.path("address_components"), "route");
            String detectedCity = firstNonBlank(
                    getAddressComponent(best.path("address_components"), "locality"),
                    getAddressComponent(best.path("address_components"), "administrative_area_level_3"),
                    getAddressComponent(best.path("address_components"), "administrative_area_level_2"),
                    city
            );

            if (!hasText(streetNumber) || !hasText(route)) {
                throw new RuntimeException("Inserisci un indirizzo completo di numero civico e seleziona un suggerimento preciso");
            }

            return new GeocodeResult(
                    best.path("geometry").path("location").path("lat").asDouble(),
                    best.path("geometry").path("location").path("lng").asDouble(),
                    best.path("formatted_address").asText(query),
                    detectedCity,
                    route + ", " + streetNumber,
                    true
            );
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Geocodifica Google non riuscita", ex);
        }
    }

    public List<AddressSuggestionDTO> searchSuggestions(String address, String city) {
        if (!hasText(address) || address.trim().length() < 3) {
            return List.of();
        }
        ensureApiKeyConfigured();

        try {
            JsonNode root = executeRequest(buildAutocompleteUri(address, city));
            JsonNode predictions = root.path("predictions");
            if (!"OK".equals(root.path("status").asText()) && !"ZERO_RESULTS".equals(root.path("status").asText())) {
                return List.of();
            }
            if (!predictions.isArray() || predictions.isEmpty()) {
                return List.of();
            }

            List<AddressSuggestionDTO> suggestions = new ArrayList<>();
            int count = 0;
            for (JsonNode prediction : predictions) {
                if (count >= 5) {
                    break;
                }
                JsonNode details = fetchPlaceDetails(prediction.path("place_id").asText());
                if (details == null || details.isMissingNode() || details.isEmpty()) {
                    continue;
                }
                String route = getAddressComponent(details.path("address_components"), "route");
                if (!hasText(route)) {
                    continue;
                }
                String streetNumber = getAddressComponent(details.path("address_components"), "street_number");
                String detectedCity = firstNonBlank(
                        getAddressComponent(details.path("address_components"), "locality"),
                        getAddressComponent(details.path("address_components"), "administrative_area_level_3"),
                        getAddressComponent(details.path("address_components"), "administrative_area_level_2"),
                        city
                );
                String formattedAddress = details.path("formatted_address").asText(prediction.path("description").asText(address));
                String resolvedAddress = hasText(streetNumber) ? route + ", " + streetNumber : route;
                suggestions.add(new AddressSuggestionDTO(
                        resolvedAddress,
                        detectedCity,
                        formattedAddress,
                        details.path("geometry").path("location").path("lat").asDouble(),
                        details.path("geometry").path("location").path("lng").asDouble(),
                        hasText(streetNumber)
                ));
                count++;
            }

            return suggestions.stream()
                    .sorted(Comparator.comparingInt((AddressSuggestionDTO item) -> scoreSuggestion(item, address, city)).reversed())
                    .toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private JsonNode fetchPlaceDetails(String placeId) throws Exception {
        if (!hasText(placeId)) {
            return null;
        }
        JsonNode root = executeRequest(buildPlaceDetailsUri(placeId));
        if (!"OK".equals(root.path("status").asText())) {
            return null;
        }
        return root.path("result");
    }

    private int scoreSuggestion(AddressSuggestionDTO item, String address, String city) {
        int score = item.isHasStreetNumber() ? 1000 : 0;
        String normalizedAddress = normalize(address);
        String normalizedCity = normalize(city);
        if (item.isHasStreetNumber() && normalize(item.getAddress()).contains(normalizedAddress)) {
            score += 250;
        }
        if (normalize(item.getFormattedAddress()).contains(normalizedAddress)) {
            score += 150;
        }
        if (hasText(city) && normalize(item.getFormattedAddress()).contains(normalizedCity)) {
            score += 100;
        }
        return score;
    }

    private List<JsonNode> rankGoogleResults(JsonNode results, String address, String city) {
        List<JsonNode> ranked = new ArrayList<>();
        results.forEach(ranked::add);
        ranked.sort(Comparator.comparingInt((JsonNode item) -> scoreGoogleResult(item, address, city)).reversed());
        return ranked;
    }

    private int scoreGoogleResult(JsonNode item, String address, String city) {
        String streetNumber = getAddressComponent(item.path("address_components"), "street_number");
        String route = getAddressComponent(item.path("address_components"), "route");
        String locality = firstNonBlank(
                getAddressComponent(item.path("address_components"), "locality"),
                getAddressComponent(item.path("address_components"), "administrative_area_level_3"),
                getAddressComponent(item.path("address_components"), "administrative_area_level_2")
        );

        int score = hasText(streetNumber) ? 1000 : 0;
        if (hasText(route) && normalize(address).contains(normalize(route))) {
            score += 200;
        }
        if (hasText(city) && hasText(locality) && normalize(locality).contains(normalize(city))) {
            score += 120;
        }
        if (normalize(item.path("formatted_address").asText("")).contains(normalize(address))) {
            score += 120;
        }
        return score;
    }

    private URI buildGeocodeUri(String query) {
        return URI.create(GOOGLE_GEOCODE_URL
                + "?address=" + encode(query)
                + "&components=country:IT"
                + "&language=it"
                + "&key=" + encode(apiKey));
    }

    private URI buildAutocompleteUri(String address, String city) {
        String input = buildQuery(address, city);
        return URI.create(GOOGLE_AUTOCOMPLETE_URL
                + "?input=" + encode(input)
                + "&types=address"
                + "&components=country:it"
                + "&language=it"
                + "&key=" + encode(apiKey));
    }

    private URI buildPlaceDetailsUri(String placeId) {
        return URI.create(GOOGLE_PLACE_DETAILS_URL
                + "?place_id=" + encode(placeId)
                + "&fields=address_component,formatted_address,geometry"
                + "&language=it"
                + "&key=" + encode(apiKey));
    }

    private JsonNode executeRequest(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.USER_AGENT, "Waitero/1.0 (google geocoding)")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Google Maps API non disponibile");
        }
        return objectMapper.readTree(response.body());
    }

    private String getAddressComponent(JsonNode components, String type) {
        if (components == null || !components.isArray()) {
            return null;
        }
        for (JsonNode component : components) {
            for (JsonNode itemType : component.path("types")) {
                if (type.equals(itemType.asText())) {
                    return component.path("long_name").asText(null);
                }
            }
        }
        return null;
    }

    private String buildQuery(String address, String city) {
        String addressPart = address == null ? "" : address.trim();
        String cityPart = city == null ? "" : city.trim();
        if (addressPart.isBlank()) {
            return cityPart;
        }
        if (cityPart.isBlank()) {
            return addressPart;
        }
        return addressPart + ", " + cityPart;
    }

    private void ensureApiKeyConfigured() {
        if (!hasText(apiKey)) {
            throw new RuntimeException("Google Maps API key non configurata");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
