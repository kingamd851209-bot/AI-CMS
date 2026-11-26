package com.xinhongru.config;

import com.alibaba.druid.support.jakarta.StatViewServlet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class DruidConfig {
    @Bean
    @ConditionalOnProperty(name = "cms.druid.enabled", havingValue = "true")
    public ServletRegistrationBean<StatViewServlet> druidServlet(
            @Value("${cms.druid.login-username}") String loginUsername,
            @Value("${cms.druid.login-password}") String loginPassword,
            @Value("${cms.druid.allow:}") String allow,
            @Value("${cms.druid.deny:}") String deny) {
        if (loginUsername.isBlank() || loginPassword.isBlank()) {
            throw new IllegalStateException(
                    "cms.druid.login-username and cms.druid.login-password are required when"
                            + " cms.druid.enabled=true");
        }

        ServletRegistrationBean<StatViewServlet> servletRegistrationBean =
                new ServletRegistrationBean<>();
        servletRegistrationBean.setServlet(new StatViewServlet());
        servletRegistrationBean.addUrlMappings("/druid/*");
        Map<String, String> initParameters = new HashMap<>();
        initParameters.put("resetEnable", "false");
        initParameters.put("loginUsername", loginUsername);
        initParameters.put("loginPassword", loginPassword);
        if (!allow.isBlank()) {
            initParameters.put("allow", allow);
        }
        if (!deny.isBlank()) {
            initParameters.put("deny", deny);
        }
        servletRegistrationBean.setInitParameters(initParameters);
        return servletRegistrationBean;
    }
}
