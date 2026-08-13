package com.rocketpartners.onboarding.posdiscountengine.controller;

import com.rocketpartners.onboarding.posdiscountengine.service.DiscountValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps discount-engine domain exceptions to HTTP responses. A {@link DiscountValidationException}
 * (unknown eligibility code, conflicting exclusivity codes, invalid line item) becomes a 400.
 * Malformed JSON is already handled as a 400 by Spring's default message-conversion error handling,
 * so it needs no mapping here.
 */
@RestControllerAdvice
public class DiscountEngineExceptionHandler {

    @ExceptionHandler(DiscountValidationException.class)
    public ResponseEntity<Map<String, String>> onValidationError(DiscountValidationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
