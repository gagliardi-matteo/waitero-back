package com.waitero.back.service;

import com.waitero.back.entity.DemoSession;
import com.waitero.back.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoSessionServiceTest {
    @Test
    void rejectsExpiredSession() {
        DemoSessionRepository repository = mock(DemoSessionRepository.class);
        DemoSession expired = DemoSession.builder()
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .lastActivityAt(LocalDateTime.now().minusMinutes(2)).build();
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        DemoSessionService service = new DemoSessionService(repository, mock(RistoratoreRepository.class),
                mock(TavoloRepository.class), mock(OrdineRepository.class), mock(MenuCategoryRepository.class), mock(PiattoRepository.class),
                mock(DemoStreamService.class), mock(org.springframework.jdbc.core.JdbcTemplate.class));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.require("valid-looking-token"));
        assertEquals(410, error.getStatusCode().value());
    }
}
