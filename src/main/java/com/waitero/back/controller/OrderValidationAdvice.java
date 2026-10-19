package com.waitero.back.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {CustomerOrderController.class, DemoController.class})
public class OrderValidationAdvice {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidOrderInput(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(Map.of("message", "Richiesta ordine non valida"));
    }
}
