package com.rocketpartners.onboarding.posdiscountengine.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness endpoint for the load balancer.
 *
 * <p>This is <strong>not optional</strong>: the planned AWS Application Load Balancer needs a path
 * that returns 200 to consider an ECS task healthy, and Spring Boot answers 404 on {@code /}.
 * Without {@code /health} every task would look unhealthy and restart-loop. See {@code CLAUDE.md},
 * Phase 3.</p>
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
