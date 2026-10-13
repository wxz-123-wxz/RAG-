package com.graduation.ragqa.auth;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.graduation.ragqa.common.BusinessException;
import com.graduation.ragqa.config.SecurityProperties;
import com.graduation.ragqa.security.JwtService;
import com.graduation.ragqa.security.UserPrincipal;
import com.graduation.ragqa.user.DatabaseUserDetailsService;
import com.graduation.ragqa.user.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final DatabaseUserDetailsService userDetailsService;
    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtService jwtService;
    private final SecurityProperties properties;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(
            AuthenticationManager authenticationManager,
            DatabaseUserDetailsService userDetailsService,
            UserMapper userMapper,
            RefreshTokenMapper refreshTokenMapper,
            JwtService jwtService,
            SecurityProperties properties
    ) {
        this(authenticationManager, userDetailsService, userMapper, refreshTokenMapper, jwtService,
                properties, new SecureRandom(), Clock.systemUTC());
    }

    AuthService(
            AuthenticationManager authenticationManager,
            DatabaseUserDetailsService userDetailsService,
            UserMapper userMapper,
            RefreshTokenMapper refreshTokenMapper,
            JwtService jwtService,
            SecurityProperties properties,
            SecureRandom secureRandom,
            Clock clock
    ) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.jwtService = jwtService;
        this.properties = properties;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    @Transactional
    public AuthTokens login(LoginRequest request, String deviceInfo) {
        UserPrincipal principal;
        try {
            principal = (UserPrincipal) authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username().trim(), request.password())
            ).getPrincipal();
        } catch (AuthenticationException exception) {
            throw BusinessException.unauthorized("INVALID_CREDENTIALS", "用户名或密码错误");
        }
        LocalDateTime now = now();
        userMapper.updateLastLogin(principal.id(), now);
        return issueTokens(principal, deviceInfo, now);
    }

    @Transactional
    public AuthTokens refresh(String rawRefreshToken, String deviceInfo) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw BusinessException.unauthorized("REFRESH_TOKEN_INVALID", "刷新令牌无效");
        }
        LocalDateTime now = now();
        RefreshTokenRow stored = refreshTokenMapper.findByHash(hash(rawRefreshToken))
                .orElseThrow(() -> BusinessException.unauthorized(
                        "REFRESH_TOKEN_INVALID", "刷新令牌无效"));
        if (stored.getRevokedAt() != null || !stored.getExpiresAt().isAfter(now)) {
            throw BusinessException.unauthorized("REFRESH_TOKEN_EXPIRED", "刷新令牌已失效");
        }

        UserPrincipal principal = userDetailsService.loadById(stored.getUserId());
        if (!principal.isEnabled() || !principal.isAccountNonLocked()) {
            throw new BusinessException("ACCOUNT_DISABLED", "账号不可用", HttpStatus.FORBIDDEN);
        }
        refreshTokenMapper.revokeById(stored.getId(), now);
        return issueTokens(principal, deviceInfo, now);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenMapper.revokeByHash(hash(rawRefreshToken), now());
        }
    }

    private AuthTokens issueTokens(UserPrincipal principal, String deviceInfo, LocalDateTime now) {
        String refreshToken = randomToken();
        refreshTokenMapper.insert(
                IdWorker.getId(),
                principal.id(),
                hash(refreshToken),
                now.plusDays(properties.refreshTokenDays()),
                truncate(deviceInfo, 255)
        );
        AuthResponse response = new AuthResponse(
                jwtService.createAccessToken(principal),
                "Bearer",
                jwtService.accessTokenSeconds(),
                new AuthResponse.CurrentUser(
                        principal.id(), principal.username(), principal.displayName(), principal.sortedRoles())
        );
        return new AuthTokens(response, refreshToken);
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
