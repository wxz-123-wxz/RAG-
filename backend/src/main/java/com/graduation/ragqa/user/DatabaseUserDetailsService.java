package com.graduation.ragqa.user;

import com.graduation.ragqa.security.UserPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public DatabaseUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userMapper.findAuthByUsername(username.trim())
                .map(DatabaseUserDetailsService::toPrincipal)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    public UserPrincipal loadById(Long userId) {
        return userMapper.findAuthById(userId)
                .map(DatabaseUserDetailsService::toPrincipal)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    private static UserPrincipal toPrincipal(UserAuthRow row) {
        Set<String> roles = row.getRoleCodes() == null || row.getRoleCodes().isBlank()
                ? Set.of()
                : Arrays.stream(row.getRoleCodes().split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new UserPrincipal(
                row.getId(),
                row.getUsername(),
                row.getDisplayName(),
                row.getPasswordHash(),
                row.getStatus(),
                roles
        );
    }
}

