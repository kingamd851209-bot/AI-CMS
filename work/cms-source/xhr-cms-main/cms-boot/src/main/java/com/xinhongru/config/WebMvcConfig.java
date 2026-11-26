package com.xinhongru.config;

import com.xinhongru.interceptor.BaseInterceptor;
import com.xinhongru.interceptor.LegacyAdminPageRedirectInterceptor;
import com.xinhongru.interceptor.MemberSessionInterceptor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 向MVC中添加自定义组件 Created at 2021/4/30. */
@Slf4j
@Component
public class WebMvcConfig implements WebMvcConfigurer {
    private MemberSessionInterceptor memberSessionInterceptor;

    @Autowired
    public void setMemberSessionInterceptor(MemberSessionInterceptor memberSessionInterceptor) {
        this.memberSessionInterceptor = memberSessionInterceptor;
    }

    private BaseInterceptor baseInterceptor;

    @Autowired
    public void setBaseInterceptor(BaseInterceptor baseInterceptor) {
        this.baseInterceptor = baseInterceptor;
    }

    private LegacyAdminPageRedirectInterceptor legacyAdminPageRedirectInterceptor;

    @Autowired
    public void setLegacyAdminPageRedirectInterceptor(
            LegacyAdminPageRedirectInterceptor legacyAdminPageRedirectInterceptor) {
        this.legacyAdminPageRedirectInterceptor = legacyAdminPageRedirectInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(baseInterceptor).order(Ordered.HIGHEST_PRECEDENCE + 1);
        registry.addInterceptor(memberSessionInterceptor)
                // 拦截的路径 无需增加 server.servlet.context-path 里配置的前缀
                .addPathPatterns("/member/**")
                // 排除的路径
                .excludePathPatterns("/member/login.html", "/member/login", "/member/isLogin", "/member/captcha");
        registry.addInterceptor(legacyAdminPageRedirectInterceptor)
                .addPathPatterns(LegacyAdminPageRedirectInterceptor.pathPatterns());
    }
}
