package com.xinhongru.cms.siteservice;

import lombok.Getter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义路由条目 —— URL 模式匹配 + 处理器包装。
 *
 * <p>URL 占位符语法与 SiteHttpServer 默认路由一致： {@code {id}} {@code {classId}} {@code {pageIndex}} {@code
 * {pageSize}} {@code {parentId}} — 编译为正则捕获组 {@code (\d+)}。
 */
@Getter
public class RouteHandlerEntry {
    private static final Set<String> URL_PLACEHOLDERS =
            Set.of("{parentId}", "{classId}", "{pageIndex}", "{pageSize}", "{id}");

    private final String urlPattern;
    private final Pattern compiledPattern;
    private final List<String> paramNames;
    private final CustomRouteHandler handler;

    /**
     * @param urlPattern URL 模式，如 "/news/{pageIndex}"
     * @param handler 处理器
     */
    public RouteHandlerEntry(String urlPattern, CustomRouteHandler handler) {
        this.urlPattern = urlPattern;
        this.handler = handler;
        this.paramNames = new ArrayList<>();

        String regex = compileToRegex(urlPattern);
        this.compiledPattern = Pattern.compile(regex);
    }

    /** 检查路径是否匹配此路由。 */
    public boolean matches(String normalizedPath) {
        return compiledPattern.matcher(normalizedPath).matches();
    }

    /**
     * 从匹配的路径中提取占位符参数。
     *
     * @param normalizedPath 规范化路径
     * @return 参数名 → 参数值的映射，未匹配返回空 Map
     */
    public Map<String, String> extractParams(String normalizedPath) {
        Matcher matcher = compiledPattern.matcher(normalizedPath);
        if (!matcher.matches()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            String value = matcher.group(i + 1);
            if (value != null) {
                params.put(paramNames.get(i), value);
            }
        }
        return params;
    }

    /** 将 URL 模式编译为正则表达式。 复用 SiteHttpServer 的占位符替换逻辑。 */
    private String compileToRegex(String url) {
        String normalized = url;
        // 去掉前后 /
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);

        String patternStr = normalized;
        List<String> foundParams = new ArrayList<>();

        for (String placeholder : URL_PLACEHOLDERS) {
            if (patternStr.contains(placeholder)) {
                String paramName = placeholder.substring(1, placeholder.length() - 1);
                foundParams.add(paramName);
                patternStr = patternStr.replace(placeholder, "___PARAM_" + paramName + "___");
            }
        }

        // 转义正则特殊字符
        patternStr = escapeRegex(patternStr);

        // 恢复捕获组
        for (String paramName : foundParams) {
            patternStr = patternStr.replace("___PARAM_" + paramName + "___", "(\\d+)");
        }

        paramNames.addAll(foundParams);
        return "^" + patternStr + "$";
    }

    private static String escapeRegex(String str) {
        return str.replaceAll("([.^$+?{}\\[\\]\\\\|()])", "\\\\$1");
    }

    @Override
    public String toString() {
        return "RouteHandlerEntry{pattern='" + urlPattern + "', params=" + paramNames + "}";
    }
}
