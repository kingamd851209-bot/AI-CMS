package com.xinhongru.service.member.impl;

import com.xinhongru.constant.ErrorConstant;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.exception.BusinessException;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.security.MemberAuthenticationService;
import com.xinhongru.security.SessionIdentityService;
import com.xinhongru.service.member.MemberService;
import com.xinhongru.utils.RequestOriginUtils;
import com.xinhongru.utils.RsaUtils;
import com.xinhongru.web.mapper.InfoMapper;
import com.xinhongru.web.pojo.MemberLoginDto;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MemberServiceImpl implements MemberService {
    private static final int LIMIT = 3;
    private static final String LOCKED = "您输入密码已经错误3次，请10分钟后尝试";
    private static final DefaultRedisScript<Long> RESERVE_ATTEMPT = new DefaultRedisScript<>("""
            local attempts = tonumber(redis.call('GET', KEYS[1]) or '0')
            if attempts >= tonumber(ARGV[1]) then
                return 0
            end
            attempts = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return attempts
            """, Long.class);
    private final SessionIdentityService sessions;
    private final MemberAuthenticationService authentication;
    private final InfoMapper infoMapper;
    private final MemberCredentials credentials;
    private final StringRedisTemplate redis;

    public MemberServiceImpl(SessionIdentityService sessions, MemberAuthenticationService authentication,
                             InfoMapper infoMapper, MemberCredentials credentials, StringRedisTemplate redis) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.infoMapper = infoMapper;
        this.credentials = credentials;
        this.redis = redis;
    }

    @Override
    public ResultVO toLogin(
            MemberLoginDto loginDto, HttpServletResponse response, HttpServletRequest request) {
        String ip = RequestOriginUtils.trustedClientIp(request);
        String attemptKey = "cms:member-login:attempts:" + ip;
        Long attempt = redis.execute(RESERVE_ATTEMPT, List.of(attemptKey), Integer.toString(LIMIT), "600");
        if (attempt == null || attempt == 0) {
            return ResultVO.fail(LOCKED);
        }
        ContentEntity user;
        try {
            user = authenticate(loginDto, ip);
        } catch (BusinessException invalidCredentials) {
            return ResultVO.fail(attempt >= LIMIT ? LOCKED
                    : "登录失败，再输入错误" + (LIMIT - attempt) + "次，将会锁定10分钟");
        }
        ContentEntity loginLog = new ContentEntity();
        loginLog.setSingleLine5(user.getId().toString());
        loginLog.setSingleLine6(user.getTitle());
        loginLog.setSingleLine7(ip);
        loginLog.setStatus(1);
        loginLog.setClassid(186);
        infoMapper.addArticle(loginLog);
        sessions.loginMember(request, user.getId());
        redis.delete(attemptKey);
        return ResultVO.success(authentication.requireAuthenticated(request));
    }

    @Override
    public ResultVO isLogin(HttpServletRequest request) {
        return ResultVO.success(authentication.requireAuthenticated(request));
    }

    private ContentEntity authenticate(MemberLoginDto login, String ip) {
        if (StringUtils.isBlank(login.getUsername()) || StringUtils.isBlank(login.getPassword())) {
            throw BusinessException.withErrorCode(ErrorConstant.Auth.USERNAME_PASSWORD_IS_EMPTY);
        }
        String rawPassword = RsaUtils.decryptByPrivateKey(login.getPassword());
        if (StringUtils.isBlank(rawPassword)) {
            throw BusinessException.withErrorCode(ErrorConstant.Auth.PASSWORD_FORMAT_ERROR);
        }
        return credentials.authenticate(login.getUsername(), rawPassword, ip);
    }
}
