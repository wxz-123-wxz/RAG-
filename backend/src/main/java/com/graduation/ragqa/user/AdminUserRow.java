package com.graduation.ragqa.user;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public class AdminUserRow {
    private Long id;
    private String username;
    private String displayName;
    private String status;
    private String roleCodes;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRoleCodes() { return roleCodes; }
    public void setRoleCodes(String roleCodes) { this.roleCodes = roleCodes; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<String> getRoles() {
        if (roleCodes == null || roleCodes.isBlank()) return List.of();
        return Arrays.stream(roleCodes.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
}
