package com.xinhongru.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS 跨域配置 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Value("${cms.cors.admin-allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins =
                allowedOrigins.isBlank()
                        ? new String[] {"http://localhost:5173", "http://127.0.0.1:5173"}
                        : allowedOrigins.split(",");

        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
