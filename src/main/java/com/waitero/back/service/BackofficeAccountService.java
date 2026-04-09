package com.waitero.back.service;

import com.waitero.back.dto.BackofficeProfileDTO;
import com.waitero.back.dto.ChangePasswordRequest;
import com.waitero.back.dto.UpdateProfileRequest;
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

@Service
@RequiredArgsConstructor
public class BackofficeAccountService {

    private static final String LOCAL_PROVIDER = "LOCAL";

    private final AccessContextService accessContextService;
    private final BackofficeUserRepository backofficeUserRepository;
    private final RistoratoreRepository ristoratoreRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public BackofficeProfileDTO getProfile() {
        BackofficeUser user = getCurrentUser();
        return toProfile(user);
    }

    @Transactional
    public BackofficeProfileDTO updateProfile(UpdateProfileRequest request) {
        BackofficeUser user = getCurrentUser();
        if (request == null || request.getNome() == null || request.getNome().trim().isBlank()) {
            throw new RuntimeException("Nome obbligatorio");
        }

        String name = request.getNome().trim();
        user.setNome(name);

        if (user.getRole() == BackofficeRole.RISTORATORE && user.getRestaurantId() != null) {
            Ristoratore restaurant = ristoratoreRepository.findById(user.getRestaurantId())
                    .orElseThrow(() -> new RuntimeException("Ristorante non trovato"));
            restaurant.setNome(name);
            ristoratoreRepository.save(restaurant);
        }

        return toProfile(backofficeUserRepository.save(user));
    }

    @Transactional
    public BackofficeProfileDTO changePassword(ChangePasswordRequest request) {
        BackofficeUser user = getCurrentUser();
        if (!LOCAL_PROVIDER.equalsIgnoreCase(user.getProvider())) {
            throw new RuntimeException("La password e modificabile solo per account locali");
        }
        if (request == null || request.getNewPassword() == null) {
            throw new RuntimeException("Nuova password obbligatoria");
        }
        if (request.getNewPassword().length() < 8) {
            throw new RuntimeException("La nuova password deve contenere almeno 8 caratteri");
        }

        boolean hasPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
        if (hasPassword) {
            if (request.getCurrentPassword() == null || request.getCurrentPassword().isBlank()) {
                throw new RuntimeException("Password attuale obbligatoria");
            }
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
                throw new RuntimeException("Password attuale non valida");
            }
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        return toProfile(backofficeUserRepository.save(user));
    }

    private BackofficeUser getCurrentUser() {
        return backofficeUserRepository.findById(accessContextService.getAuthenticatedUserId())
                .orElseThrow(() -> new RuntimeException("Utente non trovato"));
    }

    private BackofficeProfileDTO toProfile(BackofficeUser user) {
        return BackofficeProfileDTO.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .nome(user.getNome())
                .role(user.getRole().name())
                .restaurantId(user.getRestaurantId())
                .hasPassword(user.getPasswordHash() != null && !user.getPasswordHash().isBlank())
                .build();
    }
}
