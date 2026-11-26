package com.xinhongru.site.storage;

import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** 静态发布存储目录站点化：把站点编码追加到发布根目录之下，实现多站点隔离。 */
@Service
public class SiteStoragePathResolver {
    private static final String SITE_CODE_MISSING = "STATIC_PUBLISH_SITE_CODE_MISSING";

    private SiteProjectMapper projectMapper;

    @Autowired
    public void setProjectMapper(SiteProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    /** siteProjectId -> 站点编码；站点缺失或编码为空 -> 抛 STATIC_PUBLISH_SITE_CODE_MISSING。 */
    public String requireSiteCode(Long siteProjectId) {
        if (siteProjectId == null) {
            throw new IllegalStateException(SITE_CODE_MISSING + ": 发布栏目未绑定站点");
        }
        SiteProjectEntity site = projectMapper.selectById(siteProjectId);
        String siteCode = site == null ? null : site.getProjectCode();
        if (StringUtils.isBlank(siteCode)) {
            throw new IllegalStateException(
                    SITE_CODE_MISSING + ": 站点(id=" + siteProjectId + ")未配置编码 projectCode");
        }
        return siteCode;
    }

    /** baseRoot + "/" + siteCode；siteCode 非法路径片段 -> 拒绝。 */
    public String siteRootPath(String baseRoot, String siteCode) {
        if (StringUtils.isBlank(baseRoot)) {
            throw new IllegalStateException("尚未配置静态页保存目录请配置cms.html.save-root属性");
        }
        if (StringUtils.isBlank(siteCode)) {
            throw new IllegalStateException(SITE_CODE_MISSING + ": 站点编码为空");
        }
        assertSafePathSegment(siteCode);
        return trimTrailingSlash(baseRoot) + "/" + siteCode;
    }

    /** 便捷组合：requireSiteRootPath(baseRoot, siteProjectId)。 */
    public String requireSiteRootPath(String baseRoot, Long siteProjectId) {
        return siteRootPath(baseRoot, requireSiteCode(siteProjectId));
    }

    private static void assertSafePathSegment(String siteCode) {
        Path segment;
        try {
            segment = Path.of(siteCode).normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("站点编码非法，不能作为目录使用: " + siteCode, ex);
        }
        String name = segment.toString();
        if (segment.isAbsolute()
                || segment.getNameCount() != 1
                || name.equals(".")
                || name.equals("..")
                || name.startsWith("..")
                || !name.equals(name.replace("\\", "/"))) {
            throw new IllegalArgumentException("站点编码非法，不能作为目录使用: " + siteCode);
        }
    }

    private static String trimTrailingSlash(String path) {
        String normalized = path;
        while (normalized.endsWith("/") || normalized.endsWith("\\")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
