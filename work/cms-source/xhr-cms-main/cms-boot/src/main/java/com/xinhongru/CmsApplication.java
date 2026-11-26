package com.xinhongru;

import com.xinhongru.constant.WebConst;
import com.xinhongru.utils.RedisUtils;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.TimeZone;

/**
 * @author 永远的神
 */
@Slf4j
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class CmsApplication {
    public static void main(String[] args) throws UnknownHostException {
        // 在 Spring、调度器和业务对象初始化前统一 JVM 默认时区。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        ConfigurableApplicationContext applicationContext =
                SpringApplication.run(CmsApplication.class, args);
        logApplicationStartup(applicationContext.getEnvironment());
    }

    /**
     * 输出程序启动成功后的基本信息
     *
     * @param environment
     */
    private static void logApplicationStartup(Environment environment) {
        String protocol = "http";
        if (environment.getProperty("server.ssl.key-store") != null) {
            protocol = "https";
        }
        String serverPort = environment.getProperty("server.port");
        String contextPath = environment.getProperty("server.servlet.context-path");
        if (StringUtils.isBlank(contextPath)) {
            contextPath = "/";
        }
        String hostAddress = "localhost";
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("The host name could not be determined, using `localhost` as fall");
        }
        RedisUtils.set(WebConst.LOCAL_URL, protocol + "://localhost:" + serverPort + contextPath);
        log.info(
                "\n"
                    + "---------------------------------------------------------------------------------------\n"
                    + "\tRuijieApplication '{}' is running! Access URLs:\n"
                    + "\tLocal: \t\t\t\t{}://localhost:{}{}\n"
                    + "\tLocal Api: \t\t\t{}://localhost:{}{}doc.html\n"
                    + "\tExternal: \t\t\t{}://{}:{}{}\n"
                    + "\tExternal Api: \t\t{}://{}:{}{}doc.html\n"
                    + "\tProfile(s): \t\t{}\n"
                    + "---------------------------------------------------------------------------------------",
                environment.getProperty("spring.application.name"),
                protocol,
                serverPort,
                contextPath,
                protocol,
                serverPort,
                contextPath,
                protocol,
                hostAddress,
                serverPort,
                contextPath,
                protocol,
                hostAddress,
                serverPort,
                contextPath,
                environment.getActiveProfiles());
    }
}
