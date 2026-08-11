package com.llmops.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handle @Valid validation errors on WebFlux @RequestBody bindings.
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(WebExchangeBindException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Validation Failed");
        response.put("details", errors);
        
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handle payload size limit exceptions and malformed JSON.
     */
    @ExceptionHandler(org.springframework.web.server.ServerWebInputException.class)
    public ResponseEntity<Map<String, Object>> handleServerWebInputException(org.springframework.web.server.ServerWebInputException ex) {
        Map<String, Object> response = new HashMap<>();
        if (ex.getCause() instanceof org.springframework.core.io.buffer.DataBufferLimitException) {
            response.put("error", "Payload Too Large");
            response.put("message", "Request body exceeds the maximum allowed size (1MB).");
            return ResponseEntity.status(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE).body(response);
        }
        
        response.put("error", "Bad Request");
        response.put("message", "Malformed request payload.");
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handle payload size limit exceptions directly (WebFlux throws this before decoding).
     */
    @ExceptionHandler(org.springframework.core.io.buffer.DataBufferLimitException.class)
    public ResponseEntity<Map<String, Object>> handleDataBufferLimitException(org.springframework.core.io.buffer.DataBufferLimitException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Payload Too Large");
        response.put("message", "Request body exceeds the maximum allowed size (1MB).");
        return ResponseEntity.status(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }
}
