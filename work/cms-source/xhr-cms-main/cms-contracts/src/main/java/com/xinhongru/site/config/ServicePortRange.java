package com.xinhongru.site.config;

/** CMS 站点服务与 UI Package 服务统一使用的可配置端口范围。 */
public final class ServicePortRange {
    public static final int MIN = 1024;
    public static final int MAX = 49151;
    public static final String MESSAGE = "端口必须为 1024–49151 范围内的整数";

    private ServicePortRange() {}

    public static boolean isValid(Integer port) {
        return port != null && port >= MIN && port <= MAX;
    }

    public static int requireValid(int port) {
        if (!isValid(port)) {
            throw new IllegalArgumentException(MESSAGE);
        }
        return port;
    }
}
