package com.graduation.ragqa.auth;

import com.graduation.ragqa.common.ApiResponse;
import com.graduation.ragqa.config.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RegistrationService registrationService;
    private final SecurityProperties properties;

    public AuthController(AuthService authService, RegistrationService registrationService, SecurityProperties properties) {
        this.authService = authService;
        this.registrationService = registrationService;
        this.properties = properties;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        registrationService.register(request);
        AuthTokens tokens = authService.login(new LoginRequest(request.username(), request.password()),
                servletRequest.getHeader("User-Agent"));
        setRefreshCookie(servletResponse, tokens.refreshToken(), properties.refreshTokenDays());
        return ApiResponse.success(tokens.response());
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        AuthTokens tokens = authService.login(request, servletRequest.getHeader("User-Agent"));
        setRefreshCookie(servletResponse, tokens.refreshToken(), properties.refreshTokenDays());
        return ApiResponse.success(tokens.response());
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            @CookieValue(name = "${app.security.refresh-cookie-name}", required = false) String refreshToken,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        AuthTokens tokens = authService.refresh(refreshToken, servletRequest.getHeader("User-Agent"));
        setRefreshCookie(servletResponse, tokens.refreshToken(), properties.refreshTokenDays());
        return ApiResponse.success(tokens.response());
    }

    @PostMapping("/logout")
    public void logout(
            @CookieValue(name = "${app.security.refresh-cookie-name}", required = false) String refreshToken,
            HttpServletResponse response
    ) {
        authService.logout(refreshToken);
        setRefreshCookie(response, "", 0);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void setRefreshCookie(HttpServletResponse response, String value, long days) {
        ResponseCookie cookie = ResponseCookie.from(properties.refreshCookieName(), value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(days == 0 ? Duration.ZERO : Duration.ofDays(days))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
