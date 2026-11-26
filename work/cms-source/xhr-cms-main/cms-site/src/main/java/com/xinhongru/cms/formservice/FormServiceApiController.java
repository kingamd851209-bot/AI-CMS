package com.xinhongru.cms.formservice;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.xinhongru.cms.siteservice.SiteRouteContext;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站点服务上的表单服务接口处理器（相当于 controller）。
 *
 * <p>由 {@link FormServiceRouteRegistrar} 注册到 {@code SiteRouteRegistry}，在站点服务（站点域名/端口）
 * 上暴露表单定义查询、表单显示、表单提交、表单查询、认证校验、图形验证码接口。 认证接口用站点级 key；公开留言入口不暴露查询 key，另行校验分类开关、同源提交、验证码与限流。
 *
 * <p>处理前会把请求派发到工作线程，以便安全进行阻塞式读库/读体。
 */
@Slf4j
@Component
public class FormServiceApiController {
    private static final String HEADER_KEY = "X-Form-Key";
    private static final HttpString CORS_ORIGIN = new HttpString("Access-Control-Allow-Origin");
    private static final HttpString CORS_METHODS = new HttpString("Access-Control-Allow-Methods");
    private static final HttpString CORS_HEADERS = new HttpString("Access-Control-Allow-Headers");

    private static final String PATH_DEFINITION = "api/form/definition";
    private static final String PATH_DISPLAY = "api/form/display";
    private static final String PATH_SUBMIT = "api/form/submit";
    private static final String PATH_QUERY = "api/form/query";
    private static final String PATH_AUTH_CHECK = "api/form/auth-check";
    private static final String PATH_CAPTCHA = "api/form/captcha";

    private final FormSiteApiService formSiteApiService;

    private final Environment environment;

    public FormServiceApiController(
            FormSiteApiService formSiteApiService, Environment environment) {
        this.formSiteApiService = formSiteApiService;
        this.environment = environment;
    }

    /** 自定义路由入口（CustomRouteHandler 签名）。返回 true 表示已接管该请求。 */
    public boolean handle(String path, HttpServerExchange exchange, SiteRouteContext ctx) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(() -> dispatch(path, exchange, ctx));
            return true;
        }
        dispatch(path, exchange, ctx);
        return true;
    }

    private void dispatch(String path, HttpServerExchange exchange, SiteRouteContext ctx) {
        try {
            if (path.startsWith("api/form/public/")) {
                dispatchPublic(path, exchange, ctx.siteId());
                return;
            }
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod().toString())) {
                writeOptions(exchange);
                return;
            }
            Long siteId = ctx.siteId();
            switch (path) {
                case PATH_DEFINITION -> handleDefinition(siteId, exchange);
                case PATH_DISPLAY -> handleDisplay(siteId, exchange);
                case PATH_SUBMIT -> handleSubmit(siteId, exchange);
                case PATH_QUERY -> handleQuery(siteId, exchange);
                case PATH_AUTH_CHECK -> handleAuthCheck(siteId, exchange);
                case PATH_CAPTCHA -> handleCaptcha(siteId, exchange);
                default -> writeJson(exchange, StatusCodes.NOT_FOUND, 404, "接口不存在", null);
            }
        } catch (FormApiException e) {
            writeJson(exchange, httpStatusOf(e.code()), e.code(), e.getMessage(), e.data());
        } catch (Exception e) {
            log.error("表单服务接口处理失败: path={}", path, e);
            writeJson(exchange, StatusCodes.INTERNAL_SERVER_ERROR, 500, "服务器内部错误", null);
        }
    }

    private void dispatchPublic(String path, HttpServerExchange exchange, Long siteId)
            throws java.io.IOException {
        String method = exchange.getRequestMethod().toString();
        if (path.equals("api/form/public/submit")) {
            if (!"POST".equals(method)) throw new FormApiException(405, "仅支持POST提交");
            if (!isSameOrigin(exchange, siteId)) throw new FormApiException(403, "仅接受本站页面提交");
            JSONObject body = readJsonBody(exchange, true);
            Map<String, Object> fields = new LinkedHashMap<>();
            Object raw = body.get("fields");
            if (!(raw instanceof Map<?, ?> input)) throw new FormApiException(422, "fields必须为对象");
            input.forEach((name, value) -> fields.put(String.valueOf(name), value));
            var request =
                    new FormSiteApiService.SubmitRequest(
                            intFrom(body, "categoryId"),
                            fields,
                            asString(body.get("captchaKey")),
                            asString(body.get("captchaCode")),
                            null);
            writeJson(
                    exchange,
                    200,
                    0,
                    "留言提交成功",
                    formSiteApiService.publicSubmit(siteId, request, clientIp(exchange)));
            return;
        }
        if (!"GET".equals(method)) throw new FormApiException(405, "仅支持GET读取");
        if (path.equals("api/form/public/script.js")) {
            try (var input =
                    FormServiceApiController.class.getResourceAsStream(
                            "/formservice/public-form.js")) {
                if (input == null) throw new IllegalStateException("留言控件未安装");
                exchange.getResponseHeaders()
                        .put(Headers.CONTENT_TYPE, "application/javascript;charset=UTF-8");
                exchange.getResponseHeaders()
                        .put(new HttpString("X-Content-Type-Options"), "nosniff");
                exchange.getResponseSender()
                        .send(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } else if (path.equals("api/form/public/definition")) {
            writeJson(
                    exchange,
                    200,
                    0,
                    "ok",
                    formSiteApiService.publicDefinition(siteId, intParam(exchange, "categoryId")));
        } else if (path.equals("api/form/public/captcha")) {
            writeJson(
                    exchange,
                    200,
                    0,
                    "ok",
                    formSiteApiService.publicCaptcha(siteId, intParam(exchange, "categoryId")));
        } else writeJson(exchange, 404, 404, "接口不存在", null);
    }

    private boolean isSameOrigin(HttpServerExchange exchange, Long siteId) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String property = "cms.form.public-origins." + siteId;
        // 显式页面Origin用于HTTPS终止代理；配置存在时替代实际连接，非法值不得回退。
        String expected =
                environment.containsProperty(property)
                        ? environment.getProperty(property)
                        : exchange.getRequestScheme() + "://" + exchange.getHostAndPort();
        if (origin == null || expected == null) return false;
        try {
            var source = new java.net.URI(origin);
            var target = new java.net.URI(expected);
            // 不读取客户端转发头，也不将资源/CDN地址当作页面来源。
            return isOrigin(source)
                    && isOrigin(target)
                    && source.getScheme().equalsIgnoreCase(target.getScheme())
                    && source.getHost().equalsIgnoreCase(target.getHost())
                    && effectivePort(source) == effectivePort(target);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static boolean isOrigin(java.net.URI uri) {
        return ("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getPort() <= 65535;
    }

    private static int effectivePort(java.net.URI uri) {
        return uri.getPort() >= 0
                ? uri.getPort()
                : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    // ---------------- 各接口 ----------------

    private void handleDefinition(Long siteId, HttpServerExchange exchange) {
        Map<String, Object> data =
                formSiteApiService.definition(
                        siteId, intParam(exchange, "categoryId"), keyOf(exchange, null));
        writeJson(exchange, StatusCodes.OK, 0, "ok", data);
    }

    private void handleDisplay(Long siteId, HttpServerExchange exchange) {
        Map<String, Object> data =
                formSiteApiService.display(
                        siteId,
                        intParam(exchange, "categoryId"),
                        intParam(exchange, "id"),
                        keyOf(exchange, null));
        writeJson(exchange, StatusCodes.OK, 0, "ok", data);
    }

    private void handleSubmit(Long siteId, HttpServerExchange exchange) {
        JSONObject body = readJsonBody(exchange);
        Map<String, Object> fields = new LinkedHashMap<>();
        JSONObject rawFields = body.getJSONObject("fields");
        if (rawFields != null) {
            fields.putAll(rawFields);
        }
        FormSiteApiService.SubmitRequest request =
                new FormSiteApiService.SubmitRequest(
                        intFrom(body, "categoryId"),
                        fields,
                        asString(body.get("captchaKey")),
                        asString(body.get("captchaCode")),
                        keyOf(exchange, body));
        Map<String, Object> data = formSiteApiService.submit(siteId, request, clientIp(exchange));
        writeJson(exchange, StatusCodes.OK, 0, "ok", data);
    }

    private void handleQuery(Long siteId, HttpServerExchange exchange) {
        Integer categoryId = intParam(exchange, "categoryId");
        Integer id = intParam(exchange, "id");
        String queryToken = strParam(exchange, "queryToken");
        String mobile = strParam(exchange, "mobile");
        String email = strParam(exchange, "email");
        String key = keyOf(exchange, null);

        // 带 id 且带任一凭证 → 提交人回查单条；否则按条件查列表
        boolean hasCredential = notBlank(queryToken) || notBlank(mobile) || notBlank(email);
        if (id != null && hasCredential) {
            Map<String, Object> data =
                    formSiteApiService.queryOne(
                            siteId, categoryId, id, queryToken, mobile, email, key);
            writeJson(exchange, StatusCodes.OK, 0, "ok", data);
            return;
        }
        FormSiteApiService.QueryListRequest request =
                new FormSiteApiService.QueryListRequest(
                        categoryId,
                        intParamOr(exchange, "page", 1),
                        intParamOr(exchange, "size", 10),
                        strParam(exchange, "keyword"),
                        strParam(exchange, "beginTime"),
                        strParam(exchange, "endTime"),
                        intParam(exchange, "status"),
                        key);
        writeJson(exchange, StatusCodes.OK, 0, "ok", formSiteApiService.queryList(siteId, request));
    }

    private void handleAuthCheck(Long siteId, HttpServerExchange exchange) {
        Map<String, Object> data = formSiteApiService.authCheck(siteId, keyOf(exchange, null));
        writeJson(exchange, StatusCodes.OK, 0, "ok", data);
    }

    private void handleCaptcha(Long siteId, HttpServerExchange exchange) {
        Map<String, Object> data =
                formSiteApiService.captcha(
                        siteId, intParam(exchange, "categoryId"), keyOf(exchange, null));
        writeJson(exchange, StatusCodes.OK, 0, "ok", data);
    }

    // ---------------- 请求解析 ----------------

    private String keyOf(HttpServerExchange exchange, JSONObject body) {
        String key = exchange.getRequestHeaders().getFirst(HEADER_KEY);
        if (isBlank(key)) {
            key = strParam(exchange, "key");
        }
        if (isBlank(key) && body != null) {
            key = asString(body.get("key"));
        }
        return isBlank(key) ? null : key;
    }

    private JSONObject readJsonBody(HttpServerExchange exchange) {
        return readJsonBody(exchange, false);
    }

    private JSONObject readJsonBody(HttpServerExchange exchange, boolean bounded) {
        try {
            exchange.startBlocking();
            byte[] data =
                    bounded
                            ? exchange.getInputStream().readNBytes(65537)
                            : exchange.getInputStream().readAllBytes();
            if (bounded && data.length > 65536) throw new FormApiException(413, "表单提交不能超过64KB");
            if (data.length == 0) {
                return new JSONObject();
            }
            String raw = new String(data, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                return new JSONObject();
            }
            JSONObject body = JSON.parseObject(raw);
            return body == null ? new JSONObject() : body;
        } catch (FormApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FormApiException(422, "请求体不是合法的 JSON");
        }
    }

    private String strParam(HttpServerExchange exchange, String name) {
        Deque<String> values = exchange.getQueryParameters().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.getFirst();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer intParam(HttpServerExchange exchange, String name) {
        String value = strParam(exchange, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new FormApiException(422, "参数 " + name + " 必须为整数");
        }
    }

    private int intParamOr(HttpServerExchange exchange, String name, int defaultValue) {
        Integer value = intParam(exchange, name);
        return value == null ? defaultValue : value;
    }

    private Integer intFrom(JSONObject body, String name) {
        Object value = body.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new FormApiException(422, "参数 " + name + " 必须为整数");
        }
    }

    private String clientIp(HttpServerExchange exchange) {
        try {
            if (exchange.getSourceAddress() == null
                    || exchange.getSourceAddress().getAddress() == null) {
                return null;
            }
            return exchange.getSourceAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 响应 ----------------

    private void writeJson(
            HttpServerExchange exchange, int httpStatus, int code, String message, Object data) {
        if (exchange.isResponseStarted()) {
            return;
        }
        exchange.setStatusCode(httpStatus);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json;charset=UTF-8");
        if (!exchange.getRequestPath().contains("/api/form/public/"))
            exchange.getResponseHeaders().put(CORS_ORIGIN, "*");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", data);
        exchange.getResponseSender().send(JSON.toJSONString(body));
    }

    private void writeOptions(HttpServerExchange exchange) {
        exchange.setStatusCode(StatusCodes.NO_CONTENT);
        exchange.getResponseHeaders().put(CORS_ORIGIN, "*");
        exchange.getResponseHeaders().put(CORS_METHODS, "GET,POST,OPTIONS");
        exchange.getResponseHeaders().put(CORS_HEADERS, "Content-Type,X-Form-Key");
        exchange.endExchange();
    }

    private int httpStatusOf(int code) {
        return switch (code) {
            case 403, 405, 413, 503 -> code;
            case 401 -> StatusCodes.UNAUTHORIZED;
            case 404 -> StatusCodes.NOT_FOUND;
            case 422 -> StatusCodes.UNPROCESSABLE_ENTITY;
            case 429 -> StatusCodes.TOO_MANY_REQUESTS;
            default -> StatusCodes.INTERNAL_SERVER_ERROR;
        };
    }

    private static String asString(Object value) {
        return value == null ? null : (value instanceof String text ? text : String.valueOf(value));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean notBlank(String value) {
        return !isBlank(value);
    }
}
