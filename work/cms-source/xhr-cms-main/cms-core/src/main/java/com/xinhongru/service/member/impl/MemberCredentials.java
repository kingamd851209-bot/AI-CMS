package com.xinhongru.service.member.impl;

import com.xinhongru.constant.ErrorConstant;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.mapper.MemberCredentialMapper;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class MemberCredentials {
    // 历史会员账号目录的栏目号。保留现有账号身份与登录范围。
    public static final int DIRECTORY_CLASS_ID = 183;
    private static final String HASH_VERSION = "{pbkdf2-sha256-v1}";
    private final Pbkdf2PasswordEncoder encoder = new Pbkdf2PasswordEncoder(
            "", 16, 600_000, Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    private final MemberCredentialMapper mapper;

    public MemberCredentials(MemberCredentialMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public ContentEntity authenticate(String username, String rawPassword, String ip) {
        ContentEntity member = mapper.lockMember(username, DIRECTORY_CLASS_ID);
        if (member == null) {
            throw invalidCredentials();
        }
        String hash = mapper.findHash(member.getId());
        if (hash == null) {
            String legacy = member.getSingleLine5();
            if (legacy == null || !MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8), legacy.getBytes(StandardCharsets.UTF_8))) {
                throw invalidCredentials();
            }
            store(member.getId(), rawPassword);
        } else {
            if (!hash.startsWith(HASH_VERSION) || !encoder.matches(rawPassword, hash.substring(HASH_VERSION.length()))) {
                throw invalidCredentials();
            }
            if (member.getSingleLine5() != null) {
                mapper.clearLegacy(member.getId(), DIRECTORY_CLASS_ID);
            }
        }
        member.setSingleLine5(null);
        int count = StringUtils.isBlank(member.getSingleLine8()) ? 0 : Integer.parseInt(member.getSingleLine8());
        mapper.recordLogin(member.getId(), DIRECTORY_CLASS_ID, Integer.toString(count + 1), ip);
        return member;
    }

    /** 调用方须在已授权的内容写入事务内持有相同会员记录的行锁。 */
    @Transactional
    public void store(int memberId, String rawPassword) {
        if (StringUtils.isBlank(rawPassword) || rawPassword.length() > 500) {
            throw new IllegalArgumentException("会员密码格式无效");
        }
        mapper.saveHash(memberId, HASH_VERSION + encoder.encode(rawPassword));
        mapper.clearLegacy(memberId, DIRECTORY_CLASS_ID);
    }

    public static boolean isDirectory(Integer classId) {
        return Integer.valueOf(DIRECTORY_CLASS_ID).equals(classId);
    }

    public boolean isConfigured(ContentEntity member) {
        return StringUtils.isNotBlank(member.getSingleLine5())
                || (member.getId() != null && mapper.findHash(member.getId()) != null);
    }

    public static boolean isPasswordField(Integer classId, String fieldName) {
        return isDirectory(classId) && ("singleLine5".equalsIgnoreCase(fieldName)
                || "single_line5".equalsIgnoreCase(fieldName));
    }

    private BusinessException invalidCredentials() {
        return BusinessException.withErrorCode(ErrorConstant.Auth.USERNAME_PASSWORD_ERROR);
    }
}
