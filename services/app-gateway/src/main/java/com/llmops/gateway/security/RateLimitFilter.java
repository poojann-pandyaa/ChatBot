package com.llmops.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * WebFilter that applies per-user rate limiting on /api/chat.
 * Uses Bucket4j in-memory token buckets: 20 requests per minute per user.
 */
@Component
@Order(-9) // Run after JwtAuthFilter (which is -10)
public class RateLimitFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final int REQUESTS_PER_MINUTE = 20;
    private static final String RATE_LIMITED_PATH = "/api/chat";

    private final Cache<String, Bucket> userBuckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Only rate-limit chat requests
        if (!RATE_LIMITED_PATH.equals(path)) {
            return chain.filter(exchange);
        }

        // Get the authenticated userId injected by JwtAuthFilter
        String userId = (String) exchange.getAttributes().get(JwtAuthFilter.USER_ID_ATTR);
        if (userId == null) {
            // Unauthenticated request — JwtAuthFilter will already handle this, pass through
            return chain.filter(exchange);
        }

        Bucket bucket = userBuckets.get(userId, this::createBucket);

        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        }

        log.warn("Rate limit exceeded for user: {}", userId);
        return writeTooManyRequests(exchange, userId);
    }

    private Bucket createBucket(String userId) {
        // intervally = all tokens restored at once per period (fixed-window semantics)
        // This ensures 20 req/min is a hard cap regardless of request duration
        Bandwidth limit = Bandwidth.classic(
                REQUESTS_PER_MINUTE,
                Refill.intervally(REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
        );
        return Bucket.builder().addLimit(limit).build();
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange, String userId) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Retry-After", "60");
        try {
            byte[] bytes = mapper.writeValueAsBytes(Map.of(
                    "error", "Rate limit exceeded",
                    "message", String.format("User '%s' has exceeded %d requests/minute. Please wait 60 seconds.", userId, REQUESTS_PER_MINUTE)
            ));
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }
}
