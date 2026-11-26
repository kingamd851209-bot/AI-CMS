package com.xinhongru.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * @Description @ClassName FileUtils @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/9/3 11:39
 */
@Slf4j
public class FileUtils {

    /**
     * 删除文件
     *
     * @param pathname
     * @return
     */
    public static boolean removeFile(String pathname) {
        File file = new File(pathname);
        if (file.exists()) {
            file.delete();
        } else {
            log.warn("文件{}不存在", pathname);
        }
        return true;
    }
}
