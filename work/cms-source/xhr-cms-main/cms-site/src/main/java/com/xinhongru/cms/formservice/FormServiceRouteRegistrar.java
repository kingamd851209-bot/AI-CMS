package com.xinhongru.cms.formservice;

import com.xinhongru.cms.siteservice.SiteRouteRegistry;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把表单服务接口注册到站点服务（全局路由，对所有站点生效）。
 *
 * <p>站点路由仅支持精确路径或数字占位符，因此这里登记固定子路径， 表单分类通过 {@code categoryId} 参数传入。注册为全局处理器后，
 * 统一进程与独立进程模式的站点服务都会暴露这些接口。
 */
@Slf4j
@Component
public class FormServiceRouteRegistrar {
    private static final List<String> ROUTES =
            List.of(
                    "/api/form/public/definition",
                    "/api/form/public/captcha",
                    "/api/form/public/submit",
                    "/api/form/public/script.js",
                    "/api/form/definition",
                    "/api/form/display",
                    "/api/form/submit",
                    "/api/form/query",
                    "/api/form/auth-check",
                    "/api/form/captcha");

    private final SiteRouteRegistry routeRegistry;
    private final FormServiceApiController controller;

    public FormServiceRouteRegistrar(
            SiteRouteRegistry routeRegistry, FormServiceApiController controller) {
        this.routeRegistry = routeRegistry;
        this.controller = controller;
    }

    @PostConstruct
    public void register() {
        for (String route : ROUTES) {
            routeRegistry.registerGlobal(route, controller::handle);
        }
        log.info("表单服务接口已注册到站点服务: {}", ROUTES);
    }
}
