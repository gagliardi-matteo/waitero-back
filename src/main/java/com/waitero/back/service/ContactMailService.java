package com.waitero.back.service;

import com.waitero.back.dto.ContactDemoRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
public class ContactMailService {

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String recipient;
    private final String fromAddress;

    public ContactMailService(
            JavaMailSender mailSender,
            @Value("${app.contact.enabled:false}") boolean enabled,
            @Value("${app.contact.recipient:info@waiter-o.com}") String recipient,
            @Value("${app.contact.from-address:}") String fromAddress) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.recipient = recipient;
        this.fromAddress = fromAddress;
    }

    public void sendDemoRequest(ContactDemoRequest request) {
        if (!enabled || fromAddress == null || fromAddress.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Il servizio email non è ancora configurato.");
        }

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setTo(recipient);
        mail.setFrom(fromAddress);
        mail.setReplyTo(request.email().trim());
        mail.setSubject("Nuova richiesta demo WaiterO - " + clean(request.venue()));
        mail.setText(buildBody(request));

        try {
            mailSender.send(mail);
        } catch (MailException exception) {
            log.error("Invio richiesta demo via SMTP non riuscito: {}", exception.getMessage(), exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invio email temporaneamente non disponibile.", exception);
        }
    }

    private String buildBody(ContactDemoRequest request) {
        return """
                Nuova richiesta demo dalla landing WaiterO

                Nome: %s
                Locale: %s
                Email: %s
                Telefono: %s
                Città: %s

                Messaggio:
                %s
                """.formatted(
                clean(request.name()),
                clean(request.venue()),
                clean(request.email()),
                clean(request.phone()),
                clean(request.city()),
                clean(request.message()));
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replace("\r", " ").replace("\n", " ");
    }
}
