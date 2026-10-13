package com.graduation.ragqa.security;

import com.graduation.ragqa.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String VALID_SECRET =
            "dGVzdC1vbmx5LXNlY3JldC10aGF0LWlzLWF0LWxlYXN0LTMyLWJ5dGVzLWxvbmc=";

    @Test
    void createsAndParsesSignedAccessToken() {
        SecurityProperties properties = new SecurityProperties(VALID_SECRET, 15, 7, "refresh", false);
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC);
        JwtService service = new JwtService(properties, clock);
        UserPrincipal principal = new UserPrincipal(
                42L, "student01", "学生一", "not-exposed", "ACTIVE", Set.of("STUDENT"));

        String token = service.createAccessToken(principal);
        JwtService.AccessClaims claims = service.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.username()).isEqualTo("student01");
        assertThat(claims.displayName()).isEqualTo("学生一");
        assertThat(claims.roles()).containsExactly("STUDENT");
        assertThat(service.accessTokenSeconds()).isEqualTo(900);
        assertThat(principal.toString()).doesNotContain("not-exposed");
    }

    @Test
    void rejectsSecretsShorterThan256Bits() {
        String shortSecret = "c2hvcnQ=";
        SecurityProperties properties = new SecurityProperties(shortSecret, 15, 7, "refresh", false);

        assertThatThrownBy(() -> new JwtService(properties, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 decoded bytes");
    }
}

