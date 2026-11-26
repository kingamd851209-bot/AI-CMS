package com.xinhongru.security.upload;

import com.xinhongru.config.PublicStorageRoots;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** 公开素材统一写入入口，验证完成后使用唯一文件名创建，失败清理半成品。 */
@Service
public class PublicAssetStorage {
    private final PublicStorageRoots roots;
    private final AssetUploadPolicy policy;

    public PublicAssetStorage(PublicStorageRoots roots, AssetUploadPolicy policy) {
        this.roots = roots;
        this.policy = policy;
    }

    public String store(MultipartFile file, String type, Path directory) throws IOException {
        String extension = policy.validate(file, type);
        if (file.getSize() > 20 * 1024 * 1024) throw new IllegalArgumentException("文件不能超过20MB");
        Path dir = roots.directory(directory);
        Path target = dir.resolve(UUID.randomUUID() + "." + extension);
        var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
        try (output;
                var input = file.getInputStream()) {
            if ("svg".equals(extension)) output.write(SafeSvg.read(input));
            else {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 20 * 1024 * 1024) throw new IllegalArgumentException("文件不能超过20MB");
                    output.write(buffer, 0, count);
                }
            }
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return roots.reference(target);
    }

    public String store(byte[] bytes, String name, String type, Path directory) throws IOException {
        return store(new BytesFile(name, bytes), type, directory);
    }

    private record BytesFile(String name, byte[] bytes) implements MultipartFile {
        public String getName() {
            return "file";
        }

        public String getOriginalFilename() {
            return name;
        }

        public String getContentType() {
            return "application/octet-stream";
        }

        public boolean isEmpty() {
            return bytes.length == 0;
        }

        public long getSize() {
            return bytes.length;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        public void transferTo(File target) throws IOException {
            Files.write(target.toPath(), bytes);
        }
    }
}
