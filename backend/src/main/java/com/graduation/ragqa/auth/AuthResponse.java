package com.graduation.ragqa.auth;

import java.util.List;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        CurrentUser user
) {
    public record CurrentUser(Long id, String username, String displayName, List<String> roles) {
    }
}

