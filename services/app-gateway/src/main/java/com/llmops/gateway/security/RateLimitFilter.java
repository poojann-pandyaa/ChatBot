package com.llmops.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * WebFilter that applies rate limiting using Redis-backed Bucket4j.
 * Applies per-user rate limits for /api/chat and IP-based rate limits for /api/auth.
 */
@Component
@Order(-9) // Run after JwtAuthFilter (which is -10)
public class RateLimitFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String RATE_LIMITED_CHAT_PATH = "/api/chat";
    private static final String RATE_LIMITED_AUTH_PATH = "/api/auth";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AsyncProxyManager<byte[]> proxyManager;

    @Value("${rate-limit.chat.requests-per-minute:20}")
    private int chatRequestsPerMinute;

    @Value("${rate-limit.chat.burst-capacity:20}")
    private int chatBurstCapacity;

    @Value("${rate-limit.auth.requests-per-minute:5}")
    private int authRequestsPerMinute;

    @Value("${rate-limit.auth.burst-capacity:5}")
    private int authBurstCapacity;

    public RateLimitFilter(RedisConnectionFactory redisConnectionFactory) {
        LettuceConnectionFactory lettuceConnectionFactory = (LettuceConnectionFactory) redisConnectionFactory;
        RedisClient redisClient = (RedisClient) lettuceConnectionFactory.getNativeClient();
        
        this.proxyManager = LettuceBasedProxyManager.builderFor(redisClient)
                .withExpirationStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1)))
                .build()
                .asAsync();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (RATE_LIMITED_CHAT_PATH.equals(path)) {
            String userId = (String) exchange.getAttributes().get(JwtAuthFilter.USER_ID_ATTR);
            if (userId != null) {
                return applyRateLimit(exchange, chain, "chat:" + userId, chatRequestsPerMinute, chatBurstCapacity, "User '" + userId + "'");
            }
        } else if (path.startsWith(RATE_LIMITED_AUTH_PATH)) {
            String clientIp = "unknown";
            if (exchange.getRequest().getRemoteAddress() != null) {
                clientIp = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            }
            return applyRateLimit(exchange, chain, "auth:" + clientIp, authRequestsPerMinute, authBurstCapacity, "IP '" + clientIp + "'");
        }

        return chain.filter(exchange);
    }

    private Mono<Void> applyRateLimit(ServerWebExchange exchange, WebFilterChain chain, String key, int rpm, int burst, String identifierLog) {
        AsyncBucketProxy bucket = proxyManager.builder().build(key.getBytes(StandardCharsets.UTF_8), () -> CompletableFuture.completedFuture(createBucketConfig(rpm, burst)));
        
        return Mono.fromCompletionStage(bucket.tryConsumeAndReturnRemaining(1))
                .flatMap(probe -> {
                    long remaining = probe.getRemainingTokens();
                    // Prompt requested: X-RateLimit-Reset (seconds until next refill)
                    long resetSeconds = probe.getNanosToWaitForReset() / 1_000_000_000L;
                    
                    exchange.getResponse().getHeaders().set("X-RateLimit-Limit", String.valueOf(burst));
                    exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(remaining));
                    exchange.getResponse().getHeaders().set("X-RateLimit-Reset", String.valueOf(resetSeconds));

                    if (probe.isConsumed()) {
                        return chain.filter(exchange);
                    }

                    long waitForRefillSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000L;
                    if (waitForRefillSeconds == 0) {
                        waitForRefillSeconds = 1;
                    }

                    log.warn("Rate limit exceeded for {}. Key: {}", identifierLog, key);
                    return writeTooManyRequests(exchange, identifierLog, rpm, waitForRefillSeconds);
                });
    }

    private BucketConfiguration createBucketConfig(int requestsPerMinute, int burstCapacity) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(burstCapacity)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return BucketConfiguration.builder().addLimit(limit).build();
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange, String identifierLog, int rpm, long retryAfterSeconds) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
        try {
            byte[] bytes = mapper.writeValueAsBytes(Map.of(
                    "error", "Rate limit exceeded",
                    "message", String.format("%s has exceeded %d requests/minute. Please wait %d seconds.", identifierLog, rpm, retryAfterSeconds)
            ));
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }
}
