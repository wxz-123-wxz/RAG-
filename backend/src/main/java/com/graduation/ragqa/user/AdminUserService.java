package com.graduation.ragqa.user;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.graduation.ragqa.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminUserService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public List<AdminUserRow> list() { return userMapper.findAllUsers(); }

    @Transactional
    public AdminUserRow create(AdminUserRequests.CreateRequest request, Long operatorId) {
        String username = request.username().trim();
        if (userMapper.countByUsername(username) > 0) {
            throw BusinessException.conflict("USERNAME_EXISTS", "该账号已存在");
        }
        Long roleId = userMapper.findRoleIdByCode(request.roleCode())
                .orElseThrow(() -> new BusinessException("ROLE_NOT_FOUND", "角色不存在", HttpStatus.BAD_REQUEST));
        Long userId = IdWorker.getId();
        userMapper.insertUser(userId, username, passwordEncoder.encode(request.password()), request.displayName().trim());
        userMapper.insertUserRole(userId, roleId, operatorId);
        return userMapper.findAllUsers().stream()
                .filter(row -> userId.equals(row.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Created user cannot be loaded"));
    }

    @Transactional
    public void changeStatus(Long userId, String status, Long operatorId) {
        if (userId.equals(operatorId) && "DISABLED".equals(status)) {
            throw BusinessException.conflict("CANNOT_DISABLE_SELF", "不能停用当前登录账号");
        }
        if (userMapper.findAuthById(userId).isEmpty()) {
            throw new BusinessException("USER_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        }
        userMapper.updateStatus(userId, status);
    }
}
