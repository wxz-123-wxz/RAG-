package com.graduation.ragqa.user;

import com.graduation.ragqa.common.ApiResponse;
import com.graduation.ragqa.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final AdminUserService service;

    public AdminUserController(AdminUserService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<AdminUserRow>> list() { return ApiResponse.success(service.list()); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminUserRow> create(@Valid @RequestBody AdminUserRequests.CreateRequest request,
                                            @AuthenticationPrincipal UserPrincipal operator) {
        return ApiResponse.success(service.create(request, operator.id()));
    }

    @PatchMapping("/{userId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeStatus(@PathVariable Long userId,
                             @Valid @RequestBody AdminUserRequests.StatusRequest request,
                             @AuthenticationPrincipal UserPrincipal operator) {
        service.changeStatus(userId, request.status(), operator.id());
    }
}
