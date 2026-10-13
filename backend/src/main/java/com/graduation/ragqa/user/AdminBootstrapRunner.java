package com.graduation.ragqa.user;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.graduation.ragqa.config.BootstrapAdminProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final BootstrapAdminProperties properties;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapRunner(
            BootstrapAdminProperties properties,
            UserMapper userMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.password() == null || properties.password().isBlank()) {
            return;
        }
        String username = properties.username().trim();
        if (userMapper.countByUsername(username) > 0) {
            log.info("Bootstrap administrator already exists; no changes made");
            return;
        }
        Long roleId = userMapper.findRoleIdByCode("SYS_ADMIN")
                .orElseThrow(() -> new IllegalStateException("SYS_ADMIN role is missing"));
        Long userId = IdWorker.getId();
        userMapper.insertUser(
                userId,
                username,
                passwordEncoder.encode(properties.password()),
                properties.displayName()
        );
        userMapper.insertUserRole(userId, roleId, userId);
        log.info("Bootstrap administrator created; remove APP_BOOTSTRAP_ADMIN_PASSWORD before restart");
    }
}

