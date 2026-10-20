package com.waitero.back.service;

import com.waitero.back.dto.ContactDemoRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactMailServiceTest {
    @Test
    void sendsToWaiteroFromAuthenticatedMailboxAndRepliesToCustomer() {
        JavaMailSender sender = mock(JavaMailSender.class);
        ContactMailService service = new ContactMailService(sender, true, "info@waiter-o.com", "sender@cdevia.com");
        service.sendDemoRequest(new ContactDemoRequest("Mario", "Locale", "cliente@example.com", "", "Roma", "Demo", ""));
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertArrayEquals(new String[]{"info@waiter-o.com"}, message.getValue().getTo());
        assertEquals("sender@cdevia.com", message.getValue().getFrom());
        assertEquals("cliente@example.com", message.getValue().getReplyTo());
    }
}
