package com.xinhongru.cms.template;

import com.xinhongru.ai.site.project.AiSiteProjectService;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class TraditionalTemplatePackageService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log =
            LoggerFactory.getLogger(TraditionalTemplatePackageService.class);

    private final SiteProjectMapper projectMapper;
    private final TraditionalTemplatePackageValidator validator;
    private final TraditionalTemplatePackageStorageService storageService;
    private final TemplateStorageService templateStorageService;

    public TraditionalTemplatePackageService(
            SiteProjectMapper projectMapper,
            TraditionalTemplatePackageValidator validator,
            TraditionalTemplatePackageStorageService storageService,
            TemplateStorageService templateStorageService) {
        this.projectMapper = projectMapper;
        this.validator = validator;
        this.storageService = storageService;
        this.templateStorageService = templateStorageService;
    }

    @Transactional(rollbackFor = Exception.class)
    public TraditionalTemplatePackageUploadResult uploadTraditionalTemplatePackage(
            Long siteId, String originalFilename, byte[] archiveBytes) {
        SiteProjectEntity site = requireCmsManagedSiteForUpdate(siteId);
        TraditionalTemplatePackageManifest manifest =
                validator.validate(originalFilename, archiveBytes, site.getTemplateEntryPath());
        String version =
                "v"
                        + Instant.now().toEpochMilli()
                        + "-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TraditionalTemplatePackageDeployment deployment =
                storageService.stage(site.getProjectCode(), version, manifest, archiveBytes);
        site.setTraditionalTemplatePackageName(
                        originalFilename == null || originalFilename.isBlank()
                                ? "site-template.zip"
                                : originalFilename)
                .setTraditionalTemplatePackageVersion(version)
                .setTraditionalTemplateManifest(writeManifest(manifest))
                .setTraditionalTemplateRoot(deployment.root().toString())
                .setUpdateTime(LocalDateTime.now());
        try {
            if (projectMapper.updateById(site) != 1)
                throw new IllegalStateException("传统模板包数据库更新失败");
        } catch (RuntimeException ex) {
            throw ex;
        }
        activateAfterCommit(site.getProjectCode(), version);
        return new TraditionalTemplatePackageUploadResult(site, manifest);
    }

    private void activateAfterCommit(String projectCode, String version) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            storageService.activate(projectCode, version);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            storageService.activate(projectCode, version);
                        } catch (RuntimeException ex) {
                            log.warn(
                                    "传统模板包活动指针切换失败，将由后续读取按已提交站点记录恢复: projectCode={}, version={},"
                                            + " error={}",
                                    projectCode,
                                    version,
                                    ex.getMessage());
                        }
                    }
                });
    }

    @Transactional(rollbackFor = Exception.class)
    public SiteProjectEntity clearTraditionalTemplatePackage(Long siteId) {
        SiteProjectEntity site = requireCmsManagedSite(siteId);
        site.setTraditionalTemplatePackageName(null)
                .setTraditionalTemplatePackageVersion(null)
                .setTraditionalTemplateManifest(null)
                .setTraditionalTemplateRoot(null)
                .setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);
        return site;
    }

    public void requireActiveHtmlTemplate(Long siteId, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        SiteProjectEntity site = requireCmsManagedSite(siteId);

        // 优先校验传统模板包
        if (site.getTraditionalTemplateManifest() != null
                && !site.getTraditionalTemplateManifest().isBlank()) {
            TraditionalTemplatePackageManifest manifest = activeManifest(siteId);
            if (manifest.files().stream()
                    .noneMatch(file -> file.html() && file.path().equals(path))) {
                throw new IllegalArgumentException("模板路径不在当前站点已激活模板包中: " + path);
            }
            return;
        }

        // 如果没有传统模板包，检查 AI 生成的模板目录
        if (!existsAiTemplate(site.getProjectCode(), path)) {
            throw new IllegalArgumentException("模板文件不存在: " + path);
        }
    }

    /**
     * 检查 AI 生成的模板文件是否存在
     *
     * @param projectCode 站点代码
     * @param templatePath 模板路径（如 column-1001/index.html）
     * @return 是否存在
     */
    private boolean existsAiTemplate(String projectCode, String templatePath) {
        Path templateFile =
                templateStorageService.resolveTemplateDir(projectCode).resolve(templatePath);
        return Files.isRegularFile(templateFile);
    }

    public TraditionalTemplatePackageManifest activeManifest(Long siteId) {
        SiteProjectEntity site = requireCmsManagedSite(siteId);
        if (site.getTraditionalTemplateManifest() == null
                || site.getTraditionalTemplateManifest().isBlank()) {
            return null;
        }
        TraditionalTemplatePackageDeployment active = recoverActiveDeployment(site);
        if (!matchesSiteRecord(active, site)) {
            throw new IllegalStateException("传统模板包活动指针与站点记录不一致");
        }
        return readManifest(site.getTraditionalTemplateManifest());
    }

    private TraditionalTemplatePackageDeployment recoverActiveDeployment(SiteProjectEntity site) {
        if (site.getTraditionalTemplatePackageVersion() == null
                || site.getTraditionalTemplatePackageVersion().isBlank()
                || site.getTraditionalTemplateRoot() == null
                || site.getTraditionalTemplateRoot().isBlank()) {
            throw new IllegalStateException("传统模板包站点记录不完整");
        }
        TraditionalTemplatePackageDeployment active =
                storageService.activeDeployment(site.getProjectCode()).orElse(null);
        if (active == null || !matchesSiteRecord(active, site)) {
            try {
                storageService.activate(
                        site.getProjectCode(), site.getTraditionalTemplatePackageVersion());
            } catch (RuntimeException ex) {
                throw new IllegalStateException("传统模板包活动指针恢复失败", ex);
            }
            active = storageService.activeDeployment(site.getProjectCode()).orElse(null);
        }
        if (active == null) {
            throw new IllegalStateException("当前站点没有可用传统模板包目录");
        }
        return active;
    }

    private static boolean matchesSiteRecord(
            TraditionalTemplatePackageDeployment deployment, SiteProjectEntity site) {
        return Objects.equals(deployment.version(), site.getTraditionalTemplatePackageVersion())
                && Objects.equals(deployment.root().toString(), site.getTraditionalTemplateRoot());
    }

    private SiteProjectEntity requireCmsManagedSite(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("站点不能为空");
        }
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null || !Objects.equals(site.getDeleted(), 0)) {
            throw new IllegalArgumentException("站点不存在或已删除");
        }
        if (!supportsTraditionalTemplate(site)) {
            throw new IllegalStateException("Headless 站点不支持传统模板包上传");
        }
        return site;
    }

    private SiteProjectEntity requireCmsManagedSiteForUpdate(Long siteId) {
        if (siteId == null) throw new IllegalArgumentException("站点不能为空");
        SiteProjectEntity site = projectMapper.selectByIdForUpdate(siteId);
        if (site == null) throw new IllegalArgumentException("站点不存在或已删除");
        if (!supportsTraditionalTemplate(site))
            throw new IllegalStateException("Headless 站点不支持传统模板包上传");
        return site;
    }

    /**
     * 传统模板包仅适用于支持经典模板的站点（站点模式含 CMS_MANAGED）。 站点模式为逗号分隔多值时，同时支持经典模板与 Headless
     * 的站点仍可上传；存量未写模式的站点按经典模板处理。
     */
    private static boolean supportsTraditionalTemplate(SiteProjectEntity site) {
        String mode = site.getSiteMode();
        if (mode == null || mode.isBlank()) {
            return true;
        }
        return AiSiteProjectService.hasMode(mode, "CMS_MANAGED");
    }

    private static String writeManifest(TraditionalTemplatePackageManifest manifest) {
        try {
            return OBJECT_MAPPER.writeValueAsString(manifest);
        } catch (JacksonException ex) {
            throw new IllegalStateException("传统模板包 manifest 序列化失败", ex);
        }
    }

    private static TraditionalTemplatePackageManifest readManifest(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, TraditionalTemplatePackageManifest.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("传统模板包 manifest 损坏", ex);
        }
    }

    public record TraditionalTemplatePackageUploadResult(
            SiteProjectEntity site, TraditionalTemplatePackageManifest manifest) {}
}
