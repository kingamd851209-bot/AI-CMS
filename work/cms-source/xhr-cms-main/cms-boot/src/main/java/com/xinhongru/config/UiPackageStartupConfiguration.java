package com.xinhongru.config;

import com.xinhongru.cms.uipackage.UiPackageService;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 管理进程启动时恢复 UI Package HTTP 服务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cms.ui-package.auto-start", havingValue = "true")
public class UiPackageStartupConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "cms.site-service.standalone.enabled",
            havingValue = "false",
            matchIfMissing = true)
    ApplicationRunner uiPackageStartupRunner(UiPackageService uiPackageService) {
        return args -> uiPackageService.restoreRunningServices();
    }
}
