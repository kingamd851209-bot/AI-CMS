package com.xinhongru.config;

import lombok.extern.slf4j.Slf4j;

import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TomcatConfig {
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> cookieProcessorCustomizer() {
        Rfc6265CookieProcessor cookieProcessor = new Rfc6265CookieProcessor();
        return (factory) ->
                factory.addContextCustomizers(
                        (context) -> context.setCookieProcessor(cookieProcessor));
    }
}
