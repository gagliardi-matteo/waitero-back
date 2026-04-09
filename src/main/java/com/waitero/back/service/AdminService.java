package com.waitero.back.service;

import com.waitero.back.dto.admin.AdminRestaurantSummaryDto;
import com.waitero.back.dto.admin.CreateRestaurantRequest;
import com.waitero.back.dto.admin.ImpersonationResponse;
import com.waitero.back.dto.admin.ResetRestaurantPasswordRequest;
import com.waitero.back.dto.admin.StartImpersonationRequest;
import com.waitero.back.entity.BackofficeRole;
import com.waitero.back.entity.BackofficeUser;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.repository.BackofficeUserRepository;
import com.waitero.back.repository.RistoratoreRepository;
import com.waitero.back.security.AccessContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminService {

    private static final String LOCAL_PROVIDER = "LOCAL";

    private final AccessContextService accessContextService;
    private final BackofficeUserRepository backofficeUserRepository;
    private final RistoratoreRepository ristoratoreRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditService adminAuditService;

    @Transactional(readOnly = true)
    public List<AdminRestaurantSummaryDto> searchRestaurants(String query) {
        ensureMaster();
        String normalizedQuery = normalizeQuery(query);
        List<Ristoratore> restaurants = normalizedQuery == null
                ? ristoratoreRepository.findAllByOrderByCreatedAtDesc()
                : ristoratoreRepository.searchForAdmin(normalizedQuery);
        return restaurants.stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public AdminRestaurantSummaryDto createRestaurant(CreateRestaurantRequest request) {
        ensureMaster();
        validateCreateRestaurantRequest(request);

        String email = request.getEmail().trim().toLowerCase();
        if (backofficeUserRepository.existsByEmailIgnoreCaseAndProviderIgnoreCase(email, LOCAL_PROVIDER)) {
            throw new RuntimeException("Esiste gia un ristoratore con questa email");
        }

        Ristoratore restaurant = Ristoratore.builder()
                .email(email)
                .nome(request.getNome().trim())
                .provider(LOCAL_PROVIDER)
                .address(trimToNull(request.getAddress()))
                .city(trimToNull(request.getCity()))
                .allowedRadiusMeters(100)
                .createdAt(LocalDateTime.now())
                .build();
        Ristoratore savedRestaurant = ristoratoreRepository.save(restaurant);

        BackofficeUser user = BackofficeUser.builder()
                .email(email)
                .nome(savedRestaurant.getNome())
                .provider(LOCAL_PROVIDER)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(BackofficeRole.RISTORATORE)
                .restaurantId(savedRestaurant.getId())
                .createdAt(LocalDateTime.now())
                .build();
        backofficeUserRepository.save(user);
        adminAuditService.record("ADMIN_CREATE_RESTAURANT", savedRestaurant.getId(), "restaurant", savedRestaurant.getId(), Map.of("email", email, "name", savedRestaurant.getNome()));

        return toSummary(savedRestaurant);
    }

    @Transactional
    public void resetRestaurantPassword(Long restaurantId, ResetRestaurantPasswordRequest request) {
        ensureMaster();
        if (restaurantId == null) {
            throw new RuntimeException("Ristorante mancante");
        }
        validatePassword(request == null ? null : request.getPassword());

        BackofficeUser user = backofficeUserRepository.findFirstByRestaurantIdAndRole(restaurantId, BackofficeRole.RISTORATORE)
                .orElseThrow(() -> new RuntimeException("Utente ristoratore non trovato"));
        user.setProvider(LOCAL_PROVIDER);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        backofficeUserRepository.save(user);
        adminAuditService.record("ADMIN_RESET_RESTAURANT_PASSWORD", restaurantId, "backoffice_user", user.getId(), Map.of("email", user.getEmail()));
    }

    @Transactional(readOnly = true)
    public ImpersonationResponse startImpersonation(StartImpersonationRequest request) {
        ensureMaster();
        if (request == null || request.getRestaurantId() == null) {
            throw new RuntimeException("Ristorante da impersonare mancante");
        }

        BackofficeUser masterUser = backofficeUserRepository.findById(accessContextService.getAuthenticatedUserId())
                .orElseThrow(() -> new RuntimeException("Utente master non trovato"));
        Ristoratore restaurant = ristoratoreRepository.findById(request.getRestaurantId())
                .orElseThrow(() -> new RuntimeException("Ristorante non trovato"));

        String token = jwtService.generateImpersonationAccessToken(masterUser, restaurant.getId());
        adminAuditService.record("ADMIN_START_IMPERSONATION", restaurant.getId(), "restaurant", restaurant.getId(), Map.of("restaurantName", restaurant.getNome()));
        return ImpersonationResponse.builder()
                .accessToken(token)
                .actingRestaurantId(restaurant.getId())
                .restaurantName(restaurant.getNome())
                .build();
    }

    private void ensureMaster() {
        if (accessContextService.getRole() != BackofficeRole.MASTER) {
            throw new RuntimeException("Operazione consentita solo ai master");
        }
    }

    private void validateCreateRestaurantRequest(CreateRestaurantRequest request) {
        if (request == null) {
            throw new RuntimeException("Dati ristorante mancanti");
        }
        if (request.getNome() == null || request.getNome().trim().isBlank()) {
            throw new RuntimeException("Nome ristorante obbligatorio");
        }
        if (request.getEmail() == null || request.getEmail().trim().isBlank()) {
            throw new RuntimeException("Email obbligatoria");
        }
        validatePassword(request.getPassword());
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new RuntimeException("La password deve contenere almeno 8 caratteri");
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String normalized = query.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AdminRestaurantSummaryDto toSummary(Ristoratore restaurant) {
        return AdminRestaurantSummaryDto.builder()
                .id(restaurant.getId())
                .nome(restaurant.getNome())
                .email(restaurant.getEmail())
                .city(restaurant.getCity())
                .createdAt(restaurant.getCreatedAt())
                .build();
    }
}



