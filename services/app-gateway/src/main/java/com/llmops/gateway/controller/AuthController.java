package com.llmops.gateway.controller;

import com.llmops.gateway.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Handles user authentication. Issues signed JWT tokens.
 *
 * Hardcoded demo users (extend to a DB-backed user store in production):
 *   alice / alice123
 *   bob   / bob123
 *   admin / admin123
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // Hardcoded demo user credentials: username → password
    private static final Map<String, String> USERS = Map.of(
            "alice", "alice123",
            "bob",   "bob123",
            "admin", "admin123"
    );

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * POST /api/auth/login
     * Body: { "user_id": "alice", "password": "alice123" }
     * Returns: { "token": "...", "user_id": "alice", "expires_at": "..." }
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, Object>>> login(
            @RequestBody Map<String, String> body) {

        String userId   = body.getOrDefault("user_id", "").trim();
        String password = body.getOrDefault("password", "").trim();

        if (userId.isBlank() || password.isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                    .<Map<String, Object>>body(Map.of("error", "user_id and password are required")));
        }

        String expectedPassword = USERS.get(userId);
        if (expectedPassword == null || !expectedPassword.equals(password)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .<Map<String, Object>>body(Map.of("error", "Invalid credentials")));
        }

        String token = jwtService.generateToken(userId);
        String expiresAt = Instant.now().plus(24, ChronoUnit.HOURS).toString();

        Map<String, Object> response = Map.of(
                "token", token,
                "user_id", userId,
                "expires_at", expiresAt
        );
        return Mono.just(ResponseEntity.ok(response));
    }
}
