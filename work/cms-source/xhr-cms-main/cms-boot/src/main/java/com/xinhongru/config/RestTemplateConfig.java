package com.xinhongru.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * @Description @ClassName RestTemplateConfig @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/9/3
 * 10:51
 */
@Slf4j
@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate(ClientHttpRequestFactory factory) {
        return new RestTemplate(factory);
    }

    @Bean
    public ClientHttpRequestFactory simpleClientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        /** 读取超时时间,单位为ms */
        factory.setReadTimeout(10 * 60 * 1000);
        /** 连接超时时间 单位为ms */
        factory.setConnectTimeout(5000);
        return factory;
    }
}
