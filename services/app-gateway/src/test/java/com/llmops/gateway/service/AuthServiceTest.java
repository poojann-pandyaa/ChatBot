package com.llmops.gateway.service;

import com.llmops.gateway.entity.User;
import com.llmops.gateway.repository.UserRepository;
import com.llmops.gateway.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private TokenBudgetService tokenBudgetService;
    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    private ReactiveValueOperations<String, String> valueOps;
    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, jwtService, tokenBudgetService, redisTemplate, 7, 15);
        ReflectionTestUtils.setField(authService, "passwordEncoder", passwordEncoder);
        
        // Mock redis ops for issueTokens
        // Note: issueTokens is called in login, which uses redisTemplate.opsForValue()
        // Wait, issueTokens is a private method called by login. 
        // Let's set it up only if needed, but it is needed.
    }

    @Test
    void testLoginReturnsUsernameInAuthResult() {
        // Arrange
        String rawPassword = "password123";
        String mockHash = "mock-hash";
        User mockUser = new User("user-123", "testuser", mockHash, null);

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(rawPassword, mockHash)).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(jwtService.generateToken("user-123")).thenReturn("mock-jwt-token");
        when(valueOps.set(anyString(), eq("user-123:testuser"), any())).thenReturn(Mono.just(true));

        // Act & Assert
        StepVerifier.create(authService.login("testuser", rawPassword))
                .assertNext(result -> {
                    assertEquals("user-123", result.userId());
                    assertEquals("testuser", result.username()); // Crucial assertion for Bug A
                    assertEquals("mock-jwt-token", result.accessToken());
                })
                .verifyComplete();
    }
}
