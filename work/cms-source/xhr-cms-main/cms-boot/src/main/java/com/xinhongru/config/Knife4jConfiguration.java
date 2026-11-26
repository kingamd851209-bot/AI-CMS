package com.xinhongru.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "knife4j.enable", havingValue = "true", matchIfMissing = true)
public class Knife4jConfiguration {
    @Bean
    public OpenAPI cmsOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("芳拓生物API文档")
                                .description("# com.xinhongru RESTful APIs")
                                .termsOfService("芳拓生物")
                                .contact(
                                        new Contact()
                                                .name("com.xinhongru")
                                                .url("https://www.xinhongru.com")
                                                .email("812022774@qq.com"))
                                .version("1.0"));
    }

    @Bean(value = "dockerBean")
    public GroupedOpenApi dockerBean() {
        return GroupedOpenApi.builder()
                .group("v1.0")
                .packagesToScan("com.xinhongru")
                .pathsToMatch("/**")
                .build();
    }
}
