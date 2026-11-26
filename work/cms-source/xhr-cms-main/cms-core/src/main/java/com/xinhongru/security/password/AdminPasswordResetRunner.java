package com.xinhongru.security.password;

import com.xinhongru.entity.UserEntity;
import com.xinhongru.mapper.UserMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AdminPasswordResetProperties.class)
public class AdminPasswordResetRunner implements CommandLineRunner {
    private final AdminPasswordResetProperties properties;
    private final UserMapper userMapper;
    private final Sm3PasswordHashService passwordHashService;

    /** 独立进程（站点服务子 JVM）不执行密码重置，避免多进程重复副作用。 */
    @Value("${cms.site-service.standalone.enabled:false}")
    private boolean standaloneEnabled;

    @Override
    public void run(String... args) {
        if (standaloneEnabled) {
            return;
        }
        if (!properties.isEnabled()) {
            return;
        }
        if (StringUtils.isBlank(properties.getResetValue())) {
            throw new IllegalStateException(
                    "cms.password-reset.reset-value must not be blank when password reset is"
                            + " enabled");
        }
        List<UserEntity> users = userMapper.getUserList();
        log.warn(
                "Admin password reset is enabled; resetting {} admin user passwords", users.size());
        for (UserEntity user : users) {
            UserEntity update = new UserEntity();
            update.setId(user.getId());
            update.setPassword(passwordHashService.hash(properties.getResetValue()));
            userMapper.updateUserInfo(update);
            log.warn("Admin password reset applied for user: {}", user.getUsername());
        }
    }
}
