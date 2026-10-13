package com.graduation.ragqa.auth;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.graduation.ragqa.common.BusinessException;
import com.graduation.ragqa.user.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void register(RegisterRequest request) {
        String username = request.username().trim();
        if (userMapper.countByUsername(username) > 0) {
            throw BusinessException.conflict("USERNAME_EXISTS", "该账号已存在");
        }
        Long roleId = userMapper.findRoleIdByCode("STUDENT")
                .orElseThrow(() -> new BusinessException("ROLE_NOT_FOUND", "默认角色不存在", HttpStatus.INTERNAL_SERVER_ERROR));
        Long userId = IdWorker.getId();
        userMapper.insertUser(userId, username, passwordEncoder.encode(request.password()), request.displayName().trim());
        userMapper.insertUserRole(userId, roleId, userId);
    }
}
