package com.graduation.ragqa.user;

import com.graduation.ragqa.auth.AuthResponse;
import com.graduation.ragqa.common.ApiResponse;
import com.graduation.ragqa.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class CurrentUserController {

    @GetMapping("/me")
    public ApiResponse<AuthResponse.CurrentUser> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(new AuthResponse.CurrentUser(
                principal.id(), principal.username(), principal.displayName(), principal.sortedRoles()));
    }
}

