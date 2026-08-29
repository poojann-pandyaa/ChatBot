package com.llmops.gateway.controller;

import com.llmops.gateway.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    private ResponseCookie createRefreshCookie(String refreshToken, long maxAgeSeconds, ServerWebExchange exchange) {
        boolean isSecure = "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme()) ||
                           "https".equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto"));
                           
        return ResponseCookie.from("refresh_token", refreshToken != null ? refreshToken : "")
                .httpOnly(true)
                .secure(isSecure)
                .path("/api/auth")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<Map<String, Object>>> register(@RequestBody Map<String, String> body, ServerWebExchange exchange) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "").trim();

        if (username.isBlank() || password.isBlank()) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "username and password are required");
            return Mono.just(ResponseEntity.badRequest().body(err));
        }

        return authService.register(username, password)
                .map(result -> {
                    ResponseCookie cookie = createRefreshCookie(result.refreshToken(), 7 * 24 * 3600L, exchange);
                    Map<String, Object> responseBody = new HashMap<>();
                    responseBody.put("token", result.accessToken());
                    responseBody.put("user_id", result.userId());
                    responseBody.put("expires_in", result.expiresInSeconds());
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .header(HttpHeaders.SET_COOKIE, cookie.toString())
                            .<Map<String, Object>>body(responseBody);
                })
                .onErrorResume(IllegalArgumentException.class, e -> {
                    Map<String, Object> err = new HashMap<>();
                    err.put("error", e.getMessage());
                    return Mono.just(ResponseEntity.badRequest().body(err));
                });
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody Map<String, String> body, ServerWebExchange exchange) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "").trim();

        if (username.isBlank() || password.isBlank()) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "username and password are required");
            return Mono.just(ResponseEntity.badRequest().body(err));
        }

        return authService.login(username, password)
                .map(result -> {
                    ResponseCookie cookie = createRefreshCookie(result.refreshToken(), 7 * 24 * 3600L, exchange);
                    Map<String, Object> responseBody = new HashMap<>();
                    responseBody.put("token", result.accessToken());
                    responseBody.put("user_id", result.userId());
                    responseBody.put("expires_in", result.expiresInSeconds());
                    return ResponseEntity.ok()
                            .header(HttpHeaders.SET_COOKIE, cookie.toString())
                            .<Map<String, Object>>body(responseBody);
                })
                .onErrorResume(IllegalArgumentException.class, e -> {
                    Map<String, Object> err = new HashMap<>();
                    err.put("error", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err));
                });
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh(@CookieValue(value = "refresh_token", required = false) String refreshToken, ServerWebExchange exchange) {
        if (refreshToken == null || refreshToken.isBlank()) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", "Missing refresh token");
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err));
        }

        return authService.refresh(refreshToken)
                .map(result -> {
                    ResponseCookie cookie = createRefreshCookie(result.refreshToken(), 7 * 24 * 3600L, exchange);
                    Map<String, Object> responseBody = new HashMap<>();
                    responseBody.put("token", result.accessToken());
                    responseBody.put("user_id", result.userId());
                    responseBody.put("expires_in", result.expiresInSeconds());
                    return ResponseEntity.ok()
                            .header(HttpHeaders.SET_COOKIE, cookie.toString())
                            .<Map<String, Object>>body(responseBody);
                })
                .onErrorResume(IllegalArgumentException.class, e -> {
                    // Invalid/expired refresh token — clear the cookie
                    ResponseCookie clearCookie = createRefreshCookie("", 0, exchange);
                    Map<String, Object> err = new HashMap<>();
                    err.put("error", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                            .body(err));
                });
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(@CookieValue(value = "refresh_token", required = false) String refreshToken, ServerWebExchange exchange) {
        return authService.logout(refreshToken)
                .then(Mono.fromSupplier(() -> {
                    ResponseCookie clearCookie = createRefreshCookie("", 0, exchange);
                    return ResponseEntity.noContent()
                            .<Void>header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                            .build();
                }));
    }
}
