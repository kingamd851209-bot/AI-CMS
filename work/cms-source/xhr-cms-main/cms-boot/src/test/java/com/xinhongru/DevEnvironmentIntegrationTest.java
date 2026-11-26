package com.xinhongru;

import static org.assertj.core.api.Assertions.*;

import com.xinhongru.constant.WebConst;
import com.xinhongru.security.identity.SessionPrincipal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/** Opt-in: uses the configured dev database/Redis and the normal application lifecycle. */
@EnabledIfSystemProperty(named = "cms.dev.integration", matches = "true")
class DevEnvironmentIntegrationTest {
    @Test
    void startsAllModulesAndSharesInvalidatesSessionsThroughRedis() throws Exception {
        String namespace = "cms:verification:" + UUID.randomUUID();
        String adminFrontend = "http://127.0.0.1:41999";
        try (var context =
                SpringApplication.run(
                        CmsApplication.class,
                        "--spring.profiles.active=dev",
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--cms.admin.frontend-base-url=" + adminFrontend,
                        "--spring.main.allow-circular-references=false",
                        "--spring.session.data.redis.namespace=" + namespace,
                        "--logging.config=classpath:logback-test.xml",
                        "--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
                        "--cms.password-reset.enabled=false")) {
            assertThat(context.getEnvironment().getActiveProfiles()).contains("dev");
            assertThat(
                            context.getBean(
                                            "requestMappingHandlerMapping",
                                            RequestMappingHandlerMapping.class)
                                    .getHandlerMethods()
                                    .size())
                    .isGreaterThan(400);
            assertThat(context.getBean("springSessionRepositoryFilter")).isNotNull();
            var jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            var template = new RedisTemplate<String, Object>();
            template.setConnectionFactory(context.getBean(RedisConnectionFactory.class));
            template.setKeySerializer(new StringRedisSerializer());
            template.setHashKeySerializer(new StringRedisSerializer());
            template.setValueSerializer(new JdkSerializationRedisSerializer());
            template.setHashValueSerializer(new JdkSerializationRedisSerializer());
            template.afterPropertiesSet();
            var writer = repository(template, namespace);
            var reader = repository(template, namespace);
            var session = writer.createSession();
            session.setMaxInactiveInterval(Duration.ofMinutes(2));
            session.setAttribute(
                    WebConst.LOGIN_SESSION_KEY,
                    new SessionPrincipal(WebConst.SUPER_ADMIN_ID, SessionPrincipal.Realm.ADMIN));
            session.setAttribute(
                    WebConst.MEMBER_SESSION_KEY,
                    new SessionPrincipal(Integer.MAX_VALUE, SessionPrincipal.Realm.MEMBER));
            writer.save(session);
            try (var client =
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build()) {
                var loaded = reader.findById(session.getId());
                assertThat(loaded).isNotNull();
                assertThat((Object) loaded.getAttribute(WebConst.LOGIN_SESSION_KEY))
                        .isEqualTo(
                                new SessionPrincipal(
                                        WebConst.SUPER_ADMIN_ID, SessionPrincipal.Realm.ADMIN));
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                String base = "http://127.0.0.1:" + port;
                assertThat(get(client, base + "/actuator/health/readiness", null).statusCode())
                        .isEqualTo(200);
                var login = get(client, base + "/admin/login", null);
                assertThat(login.statusCode()).isEqualTo(302);
                assertThat(login.headers().firstValue("Location")).contains(adminFrontend + "/admin/login");
                assertThat(get(client, base + "/api/admin/v2/system/profile", null).statusCode())
                        .isEqualTo(401);
                String cookie =
                        "SESSION="
                                + Base64.getEncoder()
                                        .encodeToString(
                                                session.getId().getBytes(StandardCharsets.UTF_8));
                var profile = get(client, base + "/api/admin/v2/system/profile", cookie);
                assertThat(profile.statusCode())
                        .as("Redis session cookie authenticates an admin through HTTP")
                        .isEqualTo(200);
                var logout =
                        client.send(
                                HttpRequest.newBuilder(
                                                URI.create(base + "/api/admin/v2/auth/logout"))
                                        .header("Cookie", cookie)
                                        .header("Origin", base)
                                        .POST(HttpRequest.BodyPublishers.noBody())
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertThat(logout.statusCode()).isEqualTo(200);
                assertThat(reader.findById(session.getId())).isNull();
                assertThat(get(client, base + "/api/admin/v2/system/profile", cookie).statusCode())
                        .isEqualTo(401);
            } finally {
                writer.deleteById(session.getId());
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SessionRepository<Session> repository(
            RedisTemplate<String, Object> template, String namespace) {
        var repository = new RedisSessionRepository(template);
        repository.setRedisKeyNamespace(namespace);
        return (SessionRepository) repository;
    }

    private HttpResponse<String> get(HttpClient client, String url, String cookie)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET();
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
