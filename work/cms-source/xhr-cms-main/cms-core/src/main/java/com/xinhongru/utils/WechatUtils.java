package com.xinhongru.utils;

import com.alibaba.fastjson.JSONObject;
import com.xinhongru.config.PublicStorageRoots;
import com.xinhongru.dto.WechatResultDTO;
import com.xinhongru.properties.WechatProperties;
import com.xinhongru.security.upload.PublicAssetStorage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 微信导入素材逐项经过上传策略，公开地址不再由远端目录决定。 */
@Component
@ConditionalOnProperty(prefix = "cms.wechat", name = "enabled", havingValue = "true")
public class WechatUtils {
    private static final int MAX_ARCHIVE_BYTES = 20 * 1024 * 1024;
    private final WechatProperties properties;
    private final RestTemplate rest;
    private final PublicStorageRoots roots;
    private final PublicAssetStorage storage;

    public WechatUtils(
            WechatProperties properties,
            RestTemplate rest,
            PublicStorageRoots roots,
            PublicAssetStorage storage) {
        this.properties = properties;
        this.rest = rest;
        this.roots = roots;
        this.storage = storage;
    }

    public JSONObject wechatUpload(String address) {
        URI source = URI.create(address);
        if (!"https".equalsIgnoreCase(source.getScheme())
                || !"mp.weixin.qq.com".equalsIgnoreCase(source.getHost())) {
            throw new IllegalArgumentException("微文导入地址只能使用 https://mp.weixin.qq.com");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        WechatResultDTO result =
                rest.postForObject(
                        properties.getApiUrl(),
                        new HttpEntity<>(Map.of("url", address), headers),
                        WechatResultDTO.class);
        if (result == null || !"0".equals(String.valueOf(result.getError_code()))) {
            throw new IllegalStateException("微信文章导入失败");
        }
        String content = HtmlUtils.htmlUnescape(result.getResult());
        if (result.getImage_zip() != null && !result.getImage_zip().isBlank()) {
            Path directory =
                    roots.configuredRoot()
                            .resolve("global/imports")
                            .resolve(UUID.randomUUID().toString());
            String original = content;
            content =
                    rest.execute(
                            result.getImage_zip(),
                            HttpMethod.GET,
                            null,
                            response -> {
                                String imported = original;
                                int total = 0;
                                int count = 0;
                                try (var zip = new ZipInputStream(response.getBody())) {
                                    ZipEntry entry;
                                    while ((entry = zip.getNextEntry()) != null) {
                                        if (entry.isDirectory()) continue;
                                        if (++count > 500) throw new IOException("导入素材过多");
                                        String name = entry.getName().replace('\\', '/');
                                        name = name.substring(name.lastIndexOf('/') + 1);
                                        byte[] bytes =
                                                zip.readNBytes(MAX_ARCHIVE_BYTES - total + 1);
                                        total += bytes.length;
                                        if (total > MAX_ARCHIVE_BYTES)
                                            throw new IOException("导入素材超过20MB");
                                        String url = storage.store(bytes, name, "image", directory);
                                        String folder = result.getFolder_path();
                                        if (folder == null || folder.isBlank())
                                            throw new IOException("导入素材缺少引用目录");
                                        imported =
                                                imported.replace(
                                                        folder
                                                                + (folder.endsWith("/") ? "" : "/")
                                                                + name,
                                                        url);
                                    }
                                }
                                return imported;
                            });
        }
        JSONObject json = new JSONObject();
        json.put("content", content);
        json.put("success", true);
        return json;
    }
}
