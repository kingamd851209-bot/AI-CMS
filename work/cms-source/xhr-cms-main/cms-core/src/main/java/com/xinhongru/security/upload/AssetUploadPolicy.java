package com.xinhongru.security.upload;

import com.xinhongru.config.UploadConfig;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

/** 上传扩展名统一读取配置；可内联展示的图片额外检查文件签名。 */
@Component
public class AssetUploadPolicy {
    private final UploadConfig config;

    public AssetUploadPolicy(UploadConfig config) {
        this.config = config;
    }

    public String validate(MultipartFile file, String type) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String name = file.getOriginalFilename();
        if (name == null
                || name.length() > 255
                || name.contains("/")
                || name.contains("\\")
                || !name.equals(name.trim())
                || name.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("非法文件名");
        }
        String extension = UploadDelivery.extension(name);
        if (!config.extensions(type).contains(extension)) {
            throw new IllegalArgumentException("不允许的文件格式");
        }
        if (Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp").contains(extension)) {
            byte[] header;
            try (var input = file.getInputStream()) {
                header = input.readNBytes(16);
            }
            boolean matches =
                    switch (extension) {
                        case "jpg", "jpeg" -> startsWith(header, 0xff, 0xd8, 0xff);
                        case "png" -> startsWith(header, 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10);
                        case "gif" ->
                                startsWith(header, 'G', 'I', 'F', '8', '7', 'a')
                                        || startsWith(header, 'G', 'I', 'F', '8', '9', 'a');
                        case "webp" ->
                                startsWith(header, 'R', 'I', 'F', 'F')
                                        && header.length >= 12
                                        && Arrays.equals(
                                                Arrays.copyOfRange(header, 8, 12),
                                                new byte[] {'W', 'E', 'B', 'P'});
                        default -> startsWith(header, 'B', 'M');
                    };
            if (!matches) throw new IllegalArgumentException("图片内容与扩展名不符");
            String declared = file.getContentType();
            if (declared != null && !declared.isBlank()) {
                declared = declared.split(";", 2)[0].trim();
                if (!"application/octet-stream".equalsIgnoreCase(declared)
                        && !UploadDelivery.contentType(name).equalsIgnoreCase(declared)) {
                    throw new IllegalArgumentException("图片声明类型与扩展名不符");
                }
            }
        }
        return extension;
    }

    private static boolean startsWith(byte[] value, int... prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (Byte.toUnsignedInt(value[index]) != prefix[index]) return false;
        }
        return true;
    }
}
