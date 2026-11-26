package com.xinhongru.utils;

import com.xinhongru.config.PublicStorageRoots;

import lombok.extern.slf4j.Slf4j;

import net.coobird.thumbnailator.Thumbnails;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * @Description 图片压缩助手 @ClassName ThumbnailsUtils @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/4/28 16:22
 */
@Slf4j
@Component
public class ThumbnailsUtils {
    private final PublicStorageRoots storage;

    public ThumbnailsUtils(PublicStorageRoots storage) {
        this.storage = storage;
    }

    /**
     * 指定区域裁切图片
     *
     * @param originalImagePath 原图路径
     * @param thumbnailPath 目标图片路径
     * @param x 起始X坐标
     * @param y 起始Y坐标
     * @param width 裁切宽度
     * @param height 裁切高度
     * @param scale 缩放比例
     * @param quality 压缩质量
     * @return
     */
    public boolean thumbnailsImage(
            String originalImagePath,
            String thumbnailPath,
            int x,
            int y,
            int width,
            int height,
            double scale,
            float quality,
            String siteCode) {
        String filePath = storage.resolveReference(originalImagePath, siteCode).toString();
        try {
            Thumbnails.of(new File(filePath))
                    .sourceRegion(x, y, width, height)
                    // 缩放比例
                    .scale(scale)
                    // 压缩质量
                    .outputQuality(quality)
                    .toFile(storage.resolveReference(thumbnailPath, siteCode).toFile());
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 指定区域裁切图片
     *
     * @param originalImagePath 原图路径
     * @param thumbnailPath 目标图片路径
     * @param x 起始X坐标
     * @param y 起始Y坐标
     * @param width 裁切宽度
     * @param height 裁切高度
     * @return
     */
    public boolean thumbnailsImage(
            String originalImagePath,
            String thumbnailPath,
            int x,
            int y,
            int width,
            int height,
            String siteCode) {
        return thumbnailsImage(
                originalImagePath, thumbnailPath, x, y, width, height, 1, 1F, siteCode);
    }
}
