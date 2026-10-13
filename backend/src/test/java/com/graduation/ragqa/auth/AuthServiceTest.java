package com.graduation.ragqa.auth;

import com.graduation.ragqa.common.BusinessException;
import com.graduation.ragqa.config.SecurityProperties;
import com.graduation.ragqa.security.JwtService;
import com.graduation.ragqa.security.UserPrincipal;
import com.graduation.ragqa.user.DatabaseUserDetailsService;
import com.graduation.ragqa.user.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private DatabaseUserDetailsService userDetailsService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private RefreshTokenMapper refreshTokenMapper;
    @Mock
    private JwtService jwtService;

    private AuthService service;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        SecurityProperties properties = new SecurityProperties(
                "unused-in-this-test", 15, 7, "refresh", false);
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneOffset.UTC);
        service = new AuthService(
                authenticationManager,
                userDetailsService,
                userMapper,
                refreshTokenMapper,
                jwtService,
                properties,
                new SecureRandom(new byte[]{1, 2, 3}),
                clock
        );
        principal = new UserPrincipal(
                42L, "student01", "学生一", "hash", "ACTIVE", Set.of("STUDENT"));
        lenient().when(jwtService.createAccessToken(any())).thenReturn("access-token");
        lenient().when(jwtService.accessTokenSeconds()).thenReturn(900L);
    }

    @Test
    void loginStoresOnlyHashedRefreshToken() {
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        when(authenticationManager.authenticate(any())).thenReturn(authentication);

        AuthTokens tokens = service.login(new LoginRequest(" student01 ", "secret"), "test-agent");

        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        verify(refreshTokenMapper).insert(
                anyLong(), eq(42L), storedHash.capture(), any(LocalDateTime.class), eq("test-agent"));
        assertThat(storedHash.getValue()).hasSize(64).isNotEqualTo(tokens.refreshToken());
        assertThat(AuthService.hash(tokens.refreshToken())).isEqualTo(storedHash.getValue());
        assertThat(tokens.response().accessToken()).isEqualTo("access-token");
        assertThat(tokens.response().user().roles()).containsExactly("STUDENT");
        verify(userMapper).updateLastLogin(eq(42L), any(LocalDateTime.class));
    }

    @Test
    void refreshRejectsExpiredTokenWithoutIssuingNewOne() {
        RefreshTokenRow row = new RefreshTokenRow();
        row.setId(7L);
        row.setUserId(42L);
        row.setExpiresAt(LocalDateTime.of(2026, 9, 23, 9, 59));
        when(refreshTokenMapper.findByHash(anyString())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.refresh("expired-token", "test-agent"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("刷新令牌已失效");
    }

    @Test
    void refreshRotatesValidToken() {
        RefreshTokenRow row = new RefreshTokenRow();
        row.setId(7L);
        row.setUserId(42L);
        row.setExpiresAt(LocalDateTime.of(2026, 9, 24, 10, 0));
        when(refreshTokenMapper.findByHash(anyString())).thenReturn(Optional.of(row));
        when(userDetailsService.loadById(42L)).thenReturn(principal);

        AuthTokens tokens = service.refresh("old-token", "test-agent");

        verify(refreshTokenMapper).revokeById(eq(7L), any(LocalDateTime.class));
        verify(refreshTokenMapper).insert(
                anyLong(), eq(42L), anyString(), any(LocalDateTime.class), eq("test-agent"));
        assertThat(tokens.refreshToken()).isNotBlank().isNotEqualTo("old-token");
    }
}
