package com.waitero.back.service;

import com.waitero.back.dto.DemoSessionDTO;
import com.waitero.back.entity.*;
import com.waitero.back.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DemoSessionService {
    private static final String DEMO_EMAIL = "demo@waitero.internal";
    private static final List<String> EXPECTED_DISH_NAMES = List.of(
            "Bruschette al pomodoro", "Tagliere della casa", "Fritto misto",
            "Margherita", "Diavola", "Bufala", "WaiterO Special",
            "Classic Burger", "Crispy Chicken", "Pils 0,4L", "IPA 0,4L", "Weiss 0,5L",
            "Tiramisù", "Cheesecake");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DemoSessionRepository sessions;
    private final RistoratoreRepository restaurants;
    private final TavoloRepository tables;
    private final OrdineRepository orders;
    private final MenuCategoryRepository categories;
    private final PiattoRepository dishes;
    private final DemoStreamService streams;
    private final JdbcTemplate jdbc;

    @Value("${app.demo.session-minutes:60}") private long sessionMinutes;

    @Transactional
    public DemoSessionDTO create() {
        Ristoratore restaurant = restaurants.findByEmail(DEMO_EMAIL)
                .filter(r -> Boolean.TRUE.equals(r.getDemo()) && hasSeededMenu(r))
                .orElseGet(() -> {
                    // Only lazy seeding/repair needs serialization across instances.
                    jdbc.execute("select pg_advisory_xact_lock(741836021)");
                    return ensureRestaurant();
                });
        int tableNumber = 100_000 + RANDOM.nextInt(900_000_000);
        LocalDateTime now = LocalDateTime.now();
        Tavolo table = tables.save(Tavolo.builder()
                .ristoratore(restaurant).tablePublicId("demo_" + randomToken(9)).numero(tableNumber)
                .nome("Tavolo Demo").coperti(4).attivo(true).waiterCallPending(false)
                .qrToken(randomToken(32)).createdAt(now).updatedAt(now).build());
        String token = randomToken(32);
        DemoSession session = sessions.save(DemoSession.builder().id(UUID.randomUUID()).tokenHash(hash(token))
                .ristoratore(restaurant).tavolo(table).createdAt(now).lastActivityAt(now)
                .expiresAt(now.plusMinutes(sessionMinutes)).orderCount(0).build());
        return dto(session, token);
    }

    @Transactional
    public DemoSession require(String token) {
        if (token == null || token.isBlank() || token.length() > 256) throw invalid();
        DemoSession session = sessions.findByTokenHash(hash(token)).orElseThrow(this::invalid);
        if (!session.getExpiresAt().isAfter(LocalDateTime.now())) throw expired();
        session.setLastActivityAt(LocalDateTime.now());
        return session;
    }

    @Transactional(readOnly = true)
    public DemoSessionDTO describe(String token) {
        DemoSession session = require(token);
        return dto(session, token);
    }

    @Scheduled(fixedDelayString = "${app.demo.cleanup-ms:900000}")
    @Transactional
    public void cleanup() {
        for (DemoSession session : sessions.findAllByExpiresAtBefore(LocalDateTime.now())) {
            UUID id = session.getId(); Tavolo table = session.getTavolo();
            streams.close(id);
            // Hibernate-created schemas may have a non-cascading ledger FK.
            // The session is expired, so its submit tokens can no longer replay.
            jdbc.update("delete from customer_order_submission where ordine_id in (select id from customer_orders where demo_session_id = ?)", id);
            orders.deleteAll(orders.findAllByDemoSessionIdOrderByCreatedAtDesc(id));
            orders.flush();
            sessions.delete(session);
            sessions.flush();
            tables.delete(table);
        }
    }

    private Ristoratore ensureRestaurant() {
        Ristoratore restaurant = restaurants.findByEmail(DEMO_EMAIL).orElseGet(() -> restaurants.save(Ristoratore.builder()
                .email(DEMO_EMAIL).nome("Osteria WaiterO").provider("DEMO").businessType(BusinessType.RISTORANTE)
                .city("Demo").createdAt(LocalDateTime.now()).demo(true).build()));
        if (!Boolean.TRUE.equals(restaurant.getDemo())) { restaurant.setDemo(true); restaurant = restaurants.save(restaurant); }
        seedMenu(restaurant);
        return restaurant;
    }

    private boolean hasSeededMenu(Ristoratore restaurant) {
        List<String> names = dishes.findNamesByRistoratoreId(restaurant.getId());
        return EXPECTED_DISH_NAMES.stream().allMatch(expected -> names.stream().anyMatch(expected::equalsIgnoreCase));
    }

    private void seedMenu(Ristoratore restaurant) {
        seedCategory(restaurant, "ANTIPASTI", "Antipasti", 10, List.of(
                dish("Bruschette al pomodoro", "Pane croccante, pomodoro, basilico e olio EVO", "Glutine", "6.00"),
                dish("Tagliere della casa", "Salumi e formaggi selezionati", "Latte", "14.00"),
                dish("Fritto misto", "Verdure e sfizi fritti", "Glutine", "9.00")));
        seedCategory(restaurant, "PIZZE", "Pizze", 20, List.of(
                dish("Margherita", "Pomodoro, mozzarella e basilico", "Glutine, latte", "8.00"),
                dish("Diavola", "Pomodoro, mozzarella e salame piccante", "Glutine, latte", "10.00"),
                dish("Bufala", "Pomodoro, mozzarella di bufala e basilico", "Glutine, latte", "11.00"),
                dish("WaiterO Special", "Bufala, pomodorini, crudo e rucola", "Glutine, latte", "13.00")));
        seedCategory(restaurant, "BURGER", "Burger", 30, List.of(
                dish("Classic Burger", "Manzo, cheddar, insalata e salsa della casa", "Glutine, latte, uova", "12.00"),
                dish("Crispy Chicken", "Pollo croccante, insalata e maionese", "Glutine, uova", "13.00")));
        seedCategory(restaurant, "BIRRE", "Birre", 40, List.of(
                dish("Pils 0,4L", "Chiara, fresca e leggera", "Glutine", "5.00"),
                dish("IPA 0,4L", "Ambrata e luppolata", "Glutine", "6.00"),
                dish("Weiss 0,5L", "Birra di frumento", "Glutine", "6.00")));
        seedCategory(restaurant, "DOLCI", "Dolci", 50, List.of(
                dish("Tiramisù", "Mascarpone, caffè e cacao", "Glutine, latte, uova", "6.00"),
                dish("Cheesecake", "Cheesecake con salsa ai frutti rossi", "Glutine, latte", "6.00")));
    }

    private void seedCategory(Ristoratore r, String code, String label, int order, List<DishSeed> seeds) {
        MenuCategory category = categories.findByBusinessTypeAndCodeIgnoreCaseAndActiveTrue(BusinessType.RISTORANTE, code)
                .orElseGet(() -> categories.save(MenuCategory.builder().businessType(BusinessType.RISTORANTE).code(code).label(label).sortOrder(order).active(true).build()));
        for (DishSeed seed : seeds) if (dishes.findFirstByRistoratoreIdAndNomeIgnoreCase(r.getId(), seed.name()).isEmpty())
            dishes.save(Piatto.builder().ristoratore(r).categoria(category).nome(seed.name()).descrizione(seed.description())
                    .ingredienti(seed.description()).allergeni(seed.allergens()).prezzo(new BigDecimal(seed.price()))
                    .disponibile(true).consigliato(false).build());
    }

    private DishSeed dish(String n, String d, String a, String p) { return new DishSeed(n,d,a,p); }
    private DemoSessionDTO dto(DemoSession s, String token) { return DemoSessionDTO.builder().token(token)
            .restaurantId(s.getRistoratore().getId()).restaurantName(s.getRistoratore().getNome())
            .tableId(s.getTavolo().getNumero()).tablePublicId(s.getTavolo().getTablePublicId()).expiresAt(s.getExpiresAt()).build(); }
    private String randomToken(int bytes) { byte[] value = new byte[bytes]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private String hash(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Sessione demo non valida"); }
    private ResponseStatusException expired() { return new ResponseStatusException(HttpStatus.GONE, "La demo è terminata"); }
    private record DishSeed(String name, String description, String allergens, String price) {}
}
