package com.xinhongru.config;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 上传工具类
 *
 * @author liuchang
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "upload-file")
public class UploadConfig {
    /** application.yml 中的上传扩展名目录；缺失类别不允许上传。 */
    private Map<String, String> format = Map.of();

    public Set<String> extensions(String type) {
        if ("all".equals(type)) {
            Set<String> result = new LinkedHashSet<>();
            for (String category : new String[] {"image", "video", "audio", "file"}) {
                result.addAll(extensions(category));
            }
            return result;
        }
        if (!Set.of("image", "video", "audio", "file").contains(type)) {
            throw new IllegalArgumentException("未知的上传类型");
        }
        return Arrays.stream(format.getOrDefault(type, "").split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .filter(value -> value.matches("[a-z0-9]+"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
