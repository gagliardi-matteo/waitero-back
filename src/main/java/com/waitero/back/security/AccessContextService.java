package com.waitero.back.security;

import com.waitero.back.entity.BackofficeRole;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AccessContextService {

    public BackofficePrincipal requirePrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new RuntimeException("Contesto autenticazione mancante");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof BackofficePrincipal backofficePrincipal)) {
            throw new RuntimeException("Principal backoffice non disponibile");
        }
        return backofficePrincipal;
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
