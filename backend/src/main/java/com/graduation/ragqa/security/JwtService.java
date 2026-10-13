package com.graduation.ragqa.security;

import com.graduation.ragqa.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    private final SecurityProperties properties;
    private final SecretKey key;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtService(SecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtService(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(properties.jwtSecret());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("APP_JWT_SECRET must be valid Base64", exception);
        }
        if (secret.length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET must contain at least 32 decoded bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String createAccessToken(UserPrincipal principal) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(Duration.ofMinutes(properties.accessTokenMinutes()));
        return Jwts.builder()
                .subject(principal.id().toString())
                .claim("username", principal.username())
                .claim("displayName", principal.displayName())
                .claim("roles", principal.sortedRoles())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    public AccessClaims parseAccessToken(String token) {
        Claims claims = Jwts.parser().verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        List<?> rawRoles = claims.get("roles", List.class);
        List<String> roles = rawRoles == null
                ? List.of()
                : rawRoles.stream().map(String::valueOf).toList();
        return new AccessClaims(
                Long.valueOf(claims.getSubject()),
                claims.get("username", String.class),
                claims.get("displayName", String.class),
                roles
        );
    }

    public long accessTokenSeconds() {
        return Duration.ofMinutes(properties.accessTokenMinutes()).toSeconds();
    }

    public record AccessClaims(Long userId, String username, String displayName, List<String> roles) {
    }
}
