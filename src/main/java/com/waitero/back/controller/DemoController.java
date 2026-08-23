package com.waitero.back.controller;

import com.waitero.back.dto.*;
import com.waitero.back.entity.DemoSession;
import com.waitero.back.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/customer/demo")
@RequiredArgsConstructor
public class DemoController {
    private final DemoSessionService sessions;
    private final DemoOrderService orders;
    private final DemoStreamService streams;
    private final MenuService menuService;
    private final Map<String, CreationWindow> creationLimits = new ConcurrentHashMap<>();
    @Value("${app.demo.max-sessions-per-ip-hour:20}") private int maxSessionsPerIpHour;

    @PostMapping("/sessions")
    public DemoSessionDTO create(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        creationLimits.entrySet().removeIf(entry -> entry.getValue().started().isBefore(Instant.now().minus(Duration.ofHours(1))));
        CreationWindow window = creationLimits.compute(ip, (key, old) -> old == null || old.started().isBefore(Instant.now().minus(Duration.ofHours(1))) ? new CreationWindow(Instant.now(),1) : new CreationWindow(old.started(),old.count()+1));
        if (window.count() > maxSessionsPerIpHour) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Troppe sessioni demo. Riprova più tardi");
        return sessions.create();
    }

    @GetMapping("/sessions/current") public DemoSessionDTO current(@RequestParam String token) { return sessions.describe(token); }
    @GetMapping("/menu")
    public List<PiattoDTO> menu(@RequestParam String token) {
        DemoSession session = sessions.require(token);
        return menuService.toDTOList(menuService.getAvailablePiattiByRistoratore(session.getRistoratore().getId()));
    }
    @PostMapping("/orders") public OrdineDTO submit(@RequestParam String token, @RequestBody DemoOrderRequest request) { return orders.submit(token,request); }
    @GetMapping("/orders/state") public CustomerOrderStateDTO state(@RequestParam String token) { return orders.state(token); }
    @GetMapping("/orders/active") public List<OrdineDTO> active(@RequestParam String token) { return orders.active(token); }
    @GetMapping("/orders/active-summary") public List<OrderSummaryDTO> summaries(@RequestParam String token) { return orders.activeSummaries(token); }
    @GetMapping("/orders/history") public List<OrdineDTO> history(@RequestParam String token) { return orders.history(token); }
    @GetMapping("/orders/{id}") public OrdineDTO order(@RequestParam String token,@PathVariable Long id) { return orders.get(token,id); }
    @PostMapping("/orders/{id}/pay") public OrdineDTO pay(@RequestParam String token,@PathVariable Long id,@RequestBody PaymentRequest request) { return orders.pay(token,id,request); }
    @PostMapping("/orders/{id}/complete") public OrdineDTO complete(@RequestParam String token,@PathVariable Long id) { return orders.complete(token,id); }
    @GetMapping(value="/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String token) { DemoSession session=sessions.require(token); return streams.subscribe(session.getId()); }

    private record CreationWindow(Instant started,int count) {}
}
