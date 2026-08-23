package com.llmops.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.WebFilterChain;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Disabled;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Disabled("Disabled because local Docker daemon is not active. Enable in environment with active Docker daemon.")
class RateLimitFilterTest {

    @Container
    public static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("rate-limit.chat.requests-per-minute", () -> 2);
        registry.add("rate-limit.chat.burst-capacity", () -> 2);
        registry.add("rate-limit.auth.requests-per-minute", () -> 1);
        registry.add("rate-limit.auth.burst-capacity", () -> 1);
    }

    @Autowired
    private RateLimitFilter rateLimitFilter;

    private WebFilterChain filterChain;

    @BeforeEach
    void setUp() {
        filterChain = mock(WebFilterChain.class);
        when(filterChain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void testChatRateLimiting() {
        // User 1 requests
        for (int i = 0; i < 2; i++) {
            MockServerWebExchange exchange = createChatExchange("user1");
            StepVerifier.create(rateLimitFilter.filter(exchange, filterChain)).verifyComplete();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("2");
            assertThat(Integer.parseInt(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))).isEqualTo(1 - i);
        }

        // 3rd request should fail
        MockServerWebExchange overLimitExchange = createChatExchange("user1");
        StepVerifier.create(rateLimitFilter.filter(overLimitExchange, filterChain)).verifyComplete();
        assertThat(overLimitExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overLimitExchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(overLimitExchange.getResponse().getHeaders().getFirst("Retry-After")).isNotNull();

        // User 2 should have an independent bucket
        MockServerWebExchange user2Exchange = createChatExchange("user2");
        StepVerifier.create(rateLimitFilter.filter(user2Exchange, filterChain)).verifyComplete();
        assertThat(user2Exchange.getResponse().getStatusCode()).isNull();
        assertThat(user2Exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("1");
    }

    @Test
    void testAuthIpRateLimiting() {
        // IP 1 request
        MockServerWebExchange exchange = createAuthExchange("192.168.1.1");
        StepVerifier.create(rateLimitFilter.filter(exchange, filterChain)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("1");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");

        // 2nd request should fail
        MockServerWebExchange overLimitExchange = createAuthExchange("192.168.1.1");
        StepVerifier.create(rateLimitFilter.filter(overLimitExchange, filterChain)).verifyComplete();
        assertThat(overLimitExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overLimitExchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");

        // IP 2 should have independent bucket
        MockServerWebExchange ip2Exchange = createAuthExchange("192.168.1.2");
        StepVerifier.create(rateLimitFilter.filter(ip2Exchange, filterChain)).verifyComplete();
        assertThat(ip2Exchange.getResponse().getStatusCode()).isNull();
        assertThat(ip2Exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
    }

    private MockServerWebExchange createChatExchange(String userId) {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/chat").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(JwtAuthFilter.USER_ID_ATTR, userId);
        return exchange;
    }

    private MockServerWebExchange createAuthExchange(String ipAddress) {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new java.net.InetSocketAddress(ipAddress, 8080))
                .build();
        return MockServerWebExchange.from(request);
    }
}
