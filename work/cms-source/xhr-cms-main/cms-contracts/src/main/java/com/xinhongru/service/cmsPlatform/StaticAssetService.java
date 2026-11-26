package com.xinhongru.service.cmsPlatform;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 静态资源库：以文件视窗方式浏览/上传/下载/删除文件。
 *
 * <p>支持四种作用域：全局资源、站点 upload 资源、站点模板资源、站点 UIPackage 资源。 全局作用域上传按扩展名自动归入
 * images/videos/audios/pdf/ppt/excel/word 分类文件夹， 站点作用域上传直接落盘到当前路径。
 */
public interface StaticAssetService {
    enum AssetScope {
        GLOBAL,
        SITE_ASSETS,

        SITE_TEMPLATE,
        SITE_UIPACKAGE
    }

    /** 文件(目录)项 */
    record StaticAssetFileEntry(String name, boolean dir, long size, String ext, String path) {}

    /**
     * 列出目录（目录在前，按名称排序）
     *
     * @param scope 作用域
     * @param siteId 站点ID（站点作用域必填，全局忽略）
     * @param path 相对作用域根目录路径，空或 "/" 表示根
     */
    List<StaticAssetFileEntry> list(AssetScope scope, Long siteId, String path);

    /**
     * 新建文件夹
     *
     * @param scope 作用域
     * @param siteId 站点ID
     * @param path 父目录相对路径
     * @param name 文件夹名
     */
    void mkdir(AssetScope scope, Long siteId, String path, String name);

    /**
     * 上传文件。全局作用域按扩展名归入分类文件夹，站点作用域直接写入当前路径。
     *
     * @return 相对路径（如 images/abc.jpg）
     */
    String upload(AssetScope scope, Long siteId, MultipartFile file, String path)
            throws IOException;

    /** 删除文件或空目录（相对路径） */
    void delete(AssetScope scope, Long siteId, String path);

    /** 校验并解析相对路径为绝对路径（供下载使用），路径穿越时抛异常 */
    Path resolve(AssetScope scope, Long siteId, String rel);
}
