package com.xinhongru.site.render;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 标记由站点服务成功渲染的栏目模板，供静态生成核对来源。 */
public final class StaticRenderProof {
    public static final String HEADER = "X-CMS-Static-Render";

    private StaticRenderProof() {}

    public static String of(Long siteId, Integer columnId, boolean detail, String templatePath) {
        String identity = siteId + "\n" + columnId + "\n" + detail + "\n" + templatePath;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法核对静态页面渲染来源", error);
        }
    }
}
