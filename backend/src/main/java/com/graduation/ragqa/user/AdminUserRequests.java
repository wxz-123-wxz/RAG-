package com.graduation.ragqa.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AdminUserRequests {
    private AdminUserRequests() { }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Pattern(regexp = "STUDENT|KB_ADMIN|SYS_ADMIN") String roleCode
    ) { }

    public record StatusRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status
    ) { }
}
