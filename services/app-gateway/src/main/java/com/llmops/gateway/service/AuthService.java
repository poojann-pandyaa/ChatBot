package com.llmops.gateway.service;

import com.llmops.gateway.entity.User;
import com.llmops.gateway.repository.UserRepository;
import com.llmops.gateway.security.JwtService;
import com.llmops.gateway.sharding.DataSourceContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final TokenBudgetService tokenBudgetService;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private final long refreshTtlDays;
    private final long accessTtlMinutes;

    private static final String REFRESH_PREFIX = "refresh:";

    public AuthService(
            UserRepository userRepository,
            JwtService jwtService,
            TokenBudgetService tokenBudgetService,
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Value("${jwt.refresh-ttl-days:7}") long refreshTtlDays,
            @Value("${jwt.access-ttl-minutes:15}") long accessTtlMinutes) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.tokenBudgetService = tokenBudgetService;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.refreshTtlDays = refreshTtlDays;
        this.accessTtlMinutes = accessTtlMinutes;
    }

    public record AuthResult(String accessToken, String refreshToken, String userId, long expiresInSeconds) {}

    /**
     * Registers a new user, hashes password, initializes budget, issues tokens.
     */
    public Mono<AuthResult> register(String username, String password) {
        return Mono.fromCallable(() -> {
            DataSourceContextHolder.setRoute(DataSourceContextHolder.RouteKey.SHARD_0_WRITE);
            try {
                if (userRepository.existsByUsername(username)) {
                    throw new IllegalArgumentException("Username already exists");
                }
                String userId = UUID.randomUUID().toString();
                String hash = passwordEncoder.encode(password);
                User user = new User(userId, username, hash, LocalDateTime.now());
                userRepository.save(user);
                return userId;
            } finally {
                DataSourceContextHolder.clear();
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(userId -> tokenBudgetService.initializeBudget(userId).thenReturn(userId))
        .flatMap(this::issueTokens);
    }

    /**
     * Authenticates a user and issues tokens.
     */
    public Mono<AuthResult> login(String username, String password) {
        return Mono.fromCallable(() -> {
            DataSourceContextHolder.setRoute(DataSourceContextHolder.RouteKey.SHARD_0_WRITE);
            try {
                return userRepository.findByUsername(username);
            } finally {
                DataSourceContextHolder.clear();
            }
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optUser -> {
                    if (optUser.isEmpty() || !passwordEncoder.matches(password, optUser.get().getPasswordHash())) {
                        return Mono.error(new IllegalArgumentException("Invalid credentials"));
                    }
                    return issueTokens(optUser.get().getId());
                });
    }

    /**
     * Exchanges a valid refresh token for a new access+refresh pair (rotation).
     */
    public Mono<AuthResult> refresh(String oldRefreshToken) {
        String key = REFRESH_PREFIX + oldRefreshToken;
        return redisTemplate.opsForValue().get(key)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid or expired refresh token")))
                .flatMap(userId -> 
                    // Delete the old token (rotation)
                    redisTemplate.delete(key).then(issueTokens(userId))
                );
    }

    /**
     * Revokes a refresh token.
     */
    public Mono<Void> logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.empty();
        }
        return redisTemplate.delete(REFRESH_PREFIX + refreshToken).then();
    }

    private Mono<AuthResult> issueTokens(String userId) {
        String accessToken = jwtService.generateToken(userId);
        String refreshToken = UUID.randomUUID().toString();
        
        return redisTemplate.opsForValue().set(
                REFRESH_PREFIX + refreshToken,
                userId,
                Duration.ofDays(refreshTtlDays)
        ).thenReturn(new AuthResult(accessToken, refreshToken, userId, accessTtlMinutes * 60));
    }
}
