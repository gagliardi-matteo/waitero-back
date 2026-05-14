package com.waitero.back.security;

import com.waitero.back.entity.BackofficeRole;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AccessContextService {

    public Optional<BackofficePrincipal> findPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof BackofficePrincipal backofficePrincipal)) {
            return Optional.empty();
        }
        return Optional.of(backofficePrincipal);
    }

    public BackofficePrincipal requirePrincipal() {
        return findPrincipal()
                .orElseThrow(() -> new RuntimeException("Principal backoffice non disponibile"));
    }

    public Long getAuthenticatedUserId() {
        return requirePrincipal().userId();
    }

    public BackofficeRole getRole() {
        return requirePrincipal().role();
    }

    public Long getOwnedRestaurantId() {
        return requirePrincipal().restaurantId();
    }

    public Long getActingRestaurantIdOrThrow() {
        Long restaurantId = requirePrincipal().effectiveRestaurantId();
        if (restaurantId == null) {
            throw new RuntimeException("Nessun locale operativo selezionato");
        }
        return restaurantId;
    }

    public boolean isMaster() {
        return getRole() == BackofficeRole.MASTER;
    }

    public boolean isImpersonating() {
        return requirePrincipal().isImpersonating();
    }
}
