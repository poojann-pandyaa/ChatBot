package com.llmops.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * Signs and validates JWTs using HS256 with a secret key from configuration.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long ttlMillis;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.ttl-hours:24}") long ttlHours) {
        // Pad the key if it's too short (JJWT requires >= 256 bits for HS256)
        String paddedSecret = secret.length() < 32
                ? String.format("%-32s", secret).replace(' ', '0')
                : secret;
        this.signingKey = Keys.hmacShaKeyFor(paddedSecret.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = ttlHours * 3600_000L;
    }

    /**
     * Issues a signed JWT for the given userId.
     */
    public String generateToken(String userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates the token and returns the userId (subject) if valid.
     * Returns empty if token is invalid or expired.
     */
    public Optional<String> validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
