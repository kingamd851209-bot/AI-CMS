package com.xinhongru.cms.uipackage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.xinhongru.entity.UiPackageUploadRecordEntity;
import com.xinhongru.mapper.UiPackageUploadRecordMapper;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.mapper.SiteProjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Slf4j
@Service
public class UiPackageService {
    private static final String UPLOAD_TYPE_UPLOAD = "UPLOAD";
    private static final String UPLOAD_TYPE_UPGRADE = "UPGRADE";
    private static final String UPLOAD_TYPE_REUPLOAD = "REUPLOAD";

    private final SiteProjectMapper projectMapper;
    private final UiPackageStorageService storageService;
    private final UiPackageHttpServer httpServer;
    private final UiPackageUploadRecordMapper uploadRecordMapper;

    public UiPackageService(
            SiteProjectMapper projectMapper,
            UiPackageStorageService storageService,
            UiPackageHttpServer httpServer,
            UiPackageUploadRecordMapper uploadRecordMapper) {
        this.projectMapper = projectMapper;
        this.storageService = storageService;
        this.httpServer = httpServer;
        this.uploadRecordMapper = uploadRecordMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public UiPackageStatusView uploadUiPackage(
            Long siteId,
            String filename,
            byte[] archiveBytes,
            Long uploadUserId,
            String uploadUser) {
        SiteProjectEntity site = requireSiteForUpdate(siteId);
        if (filename == null || filename.isBlank()) {
            filename = "ui-package.zip";
        }
        String version =
                "v"
                        + Instant.now().toEpochMilli()
                        + "-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        try {
            storageService.deploy(site.getProjectCode(), version, archiveBytes);
            // 上传后立即建立 active 链接，保证未启动服务的站点也能浏览/识别文件
            storageService.activate(site.getProjectCode(), version);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("UIPackage 部署失败: " + e.getMessage(), e);
        }

        site.setUiPackageName(filename)
                .setUiPackageVersion(version)
                .setUiPackageStatus("UPLOADED")
                .setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);

        recordUpload(
                site,
                version,
                filename,
                archiveBytes,
                UPLOAD_TYPE_UPLOAD,
                uploadUserId,
                uploadUser);

        return new UiPackageStatusView("UPLOADED", filename, version, null);
    }

    /** 升级现有 UIPackage：版本号不变，原地覆盖同一个版本目录。 解压时重名文件覆盖、新文件写入，未在新包中出现的旧文件保持不动。 */
    @Transactional(rollbackFor = Exception.class)
    public UiPackageStatusView upgradeUiPackage(
            Long siteId,
            String filename,
            byte[] archiveBytes,
            Long uploadUserId,
            String uploadUser) {
        SiteProjectEntity site = requireSiteForUpdate(siteId);
        String version = site.getUiPackageVersion();
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("UIPackage 未上传，无法升级");
        }
        if (filename == null || filename.isBlank()) {
            filename = "ui-package.zip";
        }

        try {
            storageService.deploy(site.getProjectCode(), version, archiveBytes);
            storageService.activate(site.getProjectCode(), version);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("UIPackage 升级失败: " + e.getMessage(), e);
        }

        site.setUiPackageName(filename).setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);

        recordUpload(
                site,
                version,
                filename,
                archiveBytes,
                UPLOAD_TYPE_UPGRADE,
                uploadUserId,
                uploadUser);

        String status = site.getUiPackageStatus() == null ? "UPLOADED" : site.getUiPackageStatus();
        String accessUrl =
                "RUNNING".equals(status)
                        ? httpServer.getAccessUrl(site.getProjectCode(), site.getSiteServiceIp())
                        : null;
        return new UiPackageStatusView(status, filename, version, accessUrl);
    }

    /** 重新上传 UIPackage：生成新版本，旧包整体被替换（旧版本目录删除）。 */
    @Transactional(rollbackFor = Exception.class)
    public UiPackageStatusView reuploadUiPackage(
            Long siteId,
            String filename,
            byte[] archiveBytes,
            Long uploadUserId,
            String uploadUser) {
        SiteProjectEntity site = requireSiteForUpdate(siteId);
        String oldVersion = site.getUiPackageVersion();
        if (filename == null || filename.isBlank()) {
            filename = "ui-package.zip";
        }
        String version =
                "v"
                        + Instant.now().toEpochMilli()
                        + "-"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        try {
            storageService.deploy(site.getProjectCode(), version, archiveBytes);
            storageService.activate(site.getProjectCode(), version);
            // 旧包整体丢弃：active 切到新版本后再删除旧版本目录
            if (oldVersion != null && !oldVersion.isBlank() && !oldVersion.equals(version)) {
                storageService.deleteVersion(site.getProjectCode(), oldVersion);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("UIPackage 重新上传失败: " + e.getMessage(), e);
        }

        site.setUiPackageName(filename)
                .setUiPackageVersion(version)
                .setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);

        recordUpload(
                site,
                version,
                filename,
                archiveBytes,
                UPLOAD_TYPE_REUPLOAD,
                uploadUserId,
                uploadUser);

        String status = site.getUiPackageStatus() == null ? "UPLOADED" : site.getUiPackageStatus();
        String accessUrl =
                "RUNNING".equals(status)
                        ? httpServer.getAccessUrl(site.getProjectCode(), site.getSiteServiceIp())
                        : null;
        return new UiPackageStatusView(status, filename, version, accessUrl);
    }

    /**
     * 把源站点的 UIPackage 静态资源整体复制到目标站点目录，并把目标站点的 ui_package_version / ui_package_name
     * 对齐到源站点版本（覆盖目标站点静态资源）。
     *
     * @param cancelled 取消标记：为 true 时停止后续文件复制并抛异常，由调用方按"已停止"处理
     */
    @Transactional(rollbackFor = Exception.class)
    public UiPackageStatusView copyFromSite(
            Long sourceSiteId, Long targetSiteId, BooleanSupplier cancelled) {
        SiteProjectEntity source = requireSite(sourceSiteId);
        SiteProjectEntity target = requireSiteForUpdate(targetSiteId);

        String version = source.getUiPackageVersion();
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("源站点未上传 UIPackage，无法同步静态资源");
        }
        String sourceCode = source.getProjectCode();
        String targetCode = target.getProjectCode();
        if (sourceCode == null
                || sourceCode.isBlank()
                || targetCode == null
                || targetCode.isBlank()) {
            throw new IllegalStateException("站点编码未配置，无法同步静态资源");
        }

        storageService.copySiteDir(sourceCode, targetCode, cancelled);

        String status = target.getUiPackageStatus();
        boolean running = "RUNNING".equals(status);
        // 未在运行的站点重建 active 链接，指向复制过来的版本，保证文件可浏览
        if (!running) {
            try {
                storageService.activate(targetCode, version);
            } catch (Exception e) {
                log.warn(
                        "重建目标站点 UIPackage active 链接失败: targetCode={}, version={}",
                        targetCode,
                        version,
                        e);
            }
        }

        target.setUiPackageName(source.getUiPackageName())
                .setUiPackageVersion(version)
                .setUpdateTime(LocalDateTime.now());
        if (status == null || status.isBlank() || "NOT_UPLOADED".equals(status)) {
            target.setUiPackageStatus("UPLOADED");
        }
        projectMapper.updateById(target);

        return new UiPackageStatusView(
                target.getUiPackageStatus(),
                target.getUiPackageName(),
                version,
                running ? httpServer.getAccessUrl(targetCode, target.getSiteServiceIp()) : null);
    }

    private void recordUpload(
            SiteProjectEntity site,
            String version,
            String filename,
            byte[] archiveBytes,
            String uploadType,
            Long uploadUserId,
            String uploadUser) {
        try {
            uploadRecordMapper.insert(
                    new UiPackageUploadRecordEntity()
                            .setSiteId(site.getId())
                            .setProjectCode(site.getProjectCode())
                            .setVersion(version)
                            .setPackageName(filename)
                            .setFileSize(archiveBytes == null ? null : (long) archiveBytes.length)
                            .setUploadType(uploadType)
                            .setUploadUserId(uploadUserId)
                            .setUploadUser(uploadUser)
                            .setUploadTime(LocalDateTime.now())
                            .setDeleted(0));
        } catch (Exception e) {
            // 记录失败不影响上传本身
            log.warn("写入 UIPackage 上传记录失败: siteId={}, version={}", site.getId(), version, e);
        }
    }

    public List<UiPackageUploadRecordView> listUploadRecords(Long siteId) {
        requireSite(siteId);
        return uploadRecordMapper
                .selectList(
                        Wrappers.<UiPackageUploadRecordEntity>lambdaQuery()
                                .eq(UiPackageUploadRecordEntity::getSiteId, siteId)
                                .eq(UiPackageUploadRecordEntity::getDeleted, 0)
                                .orderByDesc(
                                        UiPackageUploadRecordEntity::getUploadTime,
                                        UiPackageUploadRecordEntity::getId))
                .stream()
                .map(UiPackageUploadRecordView::from)
                .toList();
    }

    /** 启动共享 HTTP 服务，并恢复重启前正在运行的站点，不改写持久化状态。 */
    public void restoreRunningServices() throws IOException {
        httpServer.start();
        List<SiteProjectEntity> sites =
                projectMapper.selectList(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .eq(SiteProjectEntity::getUiPackageStatus, "RUNNING"));
        for (SiteProjectEntity site : sites) {
            try {
                if (site.getProjectCode() == null
                        || site.getProjectCode().isBlank()
                        || site.getUiPackageVersion() == null
                        || site.getUiPackageVersion().isBlank()) {
                    throw new IllegalStateException("站点编码或 UI Package 版本为空");
                }
                storageService.activate(site.getProjectCode(), site.getUiPackageVersion());
                httpServer.activateSite(site.getProjectCode());
            } catch (RuntimeException e) {
                log.error("恢复站点 {} 的 UI Package 服务失败", site.getId(), e);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public UiPackageStatusView startService(Long siteId) {
        SiteProjectEntity site = requireSiteForUpdate(siteId);

        // 打印站点详细信息
        log.info("========== 站点数据库信息 ==========");
        log.info("siteId: {}", siteId);
        log.info("projectCode: {}", site.getProjectCode());
        log.info("projectName: {}", site.getProjectName());
        log.info("uiPackageStatus: {}", site.getUiPackageStatus());
        log.info("uiPackageVersion: {}", site.getUiPackageVersion());
        log.info("uiPackageName: {}", site.getUiPackageName());
        log.info("====================================");

        if (site.getUiPackageVersion() == null) {
            throw new IllegalStateException("UIPackage 未上传");
        }

        String projectCode = site.getProjectCode();
        log.info("启动站点 {} 的 UI Package 服务, projectCode={}", siteId, projectCode);

        // 如果已经在运行且 activeSites 中存在，直接返回状态
        if ("RUNNING".equals(site.getUiPackageStatus())
                && httpServer.getAccessUrl(projectCode, site.getSiteServiceIp()) != null) {
            String accessUrl = httpServer.getAccessUrl(projectCode, site.getSiteServiceIp());
            log.info("站点 {} 已经在运行状态, accessUrl={}", siteId, accessUrl);
            return new UiPackageStatusView(
                    "RUNNING", site.getUiPackageName(), site.getUiPackageVersion(), accessUrl);
        }

        // 如果数据库状态是 RUNNING 但 activeSites 中没有，需要重新激活
        log.info(
                "需要重新激活站点 {}, 数据库状态={}, 内存中存在={}",
                siteId,
                site.getUiPackageStatus(),
                httpServer.getAccessUrl(projectCode, site.getSiteServiceIp()) != null);

        storageService.activate(site.getProjectCode(), site.getUiPackageVersion());

        // 启动 HTTP 服务器
        try {
            if (!httpServer.isRunning()) {
                log.info("HTTP 服务器未运行，正在启动...");
                httpServer.start();
            }
            httpServer.activateSite(projectCode);
            log.info("站点 {} 已激活", projectCode);
        } catch (Exception e) {
            log.error("HTTP 服务启动失败", e);
            throw new IllegalStateException("HTTP 服务启动失败: " + e.getMessage(), e);
        }

        site.setUiPackageStatus("RUNNING").setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);

        String accessUrl = httpServer.getAccessUrl(projectCode, site.getSiteServiceIp());
        log.info("站点 {} 服务启动完成, accessUrl={}", siteId, accessUrl);
        return new UiPackageStatusView(
                "RUNNING", site.getUiPackageName(), site.getUiPackageVersion(), accessUrl);
    }

    @Transactional(rollbackFor = Exception.class)
    public UiPackageStatusView stopService(Long siteId) {
        SiteProjectEntity site = requireSiteForUpdate(siteId);
        if (!"RUNNING".equals(site.getUiPackageStatus())) {
            throw new IllegalStateException("服务未在运行");
        }

        httpServer.deactivateSite(site.getProjectCode());

        site.setUiPackageStatus("STOPPED").setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);

        return new UiPackageStatusView(
                "STOPPED", site.getUiPackageName(), site.getUiPackageVersion(), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteUiPackage(Long siteId) {
        SiteProjectEntity site = requireSiteForUpdate(siteId);

        // 如果正在运行，先停用
        if ("RUNNING".equals(site.getUiPackageStatus())) {
            httpServer.deactivateSite(site.getProjectCode());
        }

        if (site.getUiPackageVersion() != null) {
            storageService.delete(site.getProjectCode());
        }

        site.setUiPackageName(null)
                .setUiPackageVersion(null)
                .setUiPackageStatus("NOT_UPLOADED")
                .setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);
    }

    public UiPackageStatusView setPort(Long siteId, int port) {
        SiteProjectEntity site = requireSite(siteId);
        try {
            httpServer.setPort(port);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("端口设置失败: " + e.getMessage(), e);
        }
        String status =
                site.getUiPackageStatus() == null ? "NOT_UPLOADED" : site.getUiPackageStatus();
        if ("RUNNING".equals(status)) {
            String accessUrl =
                    httpServer.getAccessUrl(site.getProjectCode(), site.getSiteServiceIp());
            return new UiPackageStatusView(
                    "RUNNING", site.getUiPackageName(), site.getUiPackageVersion(), accessUrl);
        }
        return new UiPackageStatusView(
                status, site.getUiPackageName(), site.getUiPackageVersion(), null);
    }

    public UiPackageStatusView getUiPackageStatus(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        String status =
                site.getUiPackageStatus() == null ? "NOT_UPLOADED" : site.getUiPackageStatus();

        // 如果数据库状态是 RUNNING 但内存中没有，尝试自动重新激活
        if ("RUNNING".equals(status)) {
            String accessUrl =
                    httpServer.getAccessUrl(site.getProjectCode(), site.getSiteServiceIp());
            if (accessUrl == null && site.getUiPackageVersion() != null) {
                // 服务需要重新激活
                log.info("检测到站点 {} 需要重新激活, projectCode={}", siteId, site.getProjectCode());
                try {
                    storageService.activate(site.getProjectCode(), site.getUiPackageVersion());
                    if (!httpServer.isRunning()) {
                        httpServer.start();
                    }
                    httpServer.activateSite(site.getProjectCode());
                    accessUrl =
                            httpServer.getAccessUrl(site.getProjectCode(), site.getSiteServiceIp());
                    log.info("站点 {} 自动重新激活完成, accessUrl={}", siteId, accessUrl);
                } catch (Exception e) {
                    log.warn("站点 {} 自动重新激活失败: {}", siteId, e.getMessage());
                    // 激活失败，返回 STOPPED 状态
                    return new UiPackageStatusView(
                            "STOPPED", site.getUiPackageName(), site.getUiPackageVersion(), null);
                }
            }
            return new UiPackageStatusView(
                    "RUNNING", site.getUiPackageName(), site.getUiPackageVersion(), accessUrl);
        }

        return new UiPackageStatusView(
                status, site.getUiPackageName(), site.getUiPackageVersion(), null);
    }

    public List<UiPackageStorageService.FileInfo> listUiPackageFiles(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        if (site.getUiPackageVersion() == null) {
            return List.of();
        }
        return storageService.listFiles(site.getProjectCode(), site.getUiPackageVersion());
    }

    public String getAccessUrl(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        if (!"RUNNING".equals(site.getUiPackageStatus())) {
            return null;
        }
        return httpServer.getAccessUrl(site.getProjectCode(), site.getSiteServiceIp());
    }

    public String getFileContent(Long siteId, String filePath) {
        SiteProjectEntity site = requireSite(siteId);
        if (site.getUiPackageVersion() == null) {
            throw new IllegalStateException("UIPackage 未上传");
        }
        return storageService.getFileContent(
                site.getProjectCode(), site.getUiPackageVersion(), filePath);
    }

    private SiteProjectEntity requireSite(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("站点 ID 不能为空");
        }
        SiteProjectEntity site = projectMapper.selectById(siteId);
        if (site == null || !Objects.equals(site.getDeleted(), 0)) {
            throw new IllegalArgumentException("站点不存在或已删除");
        }
        return site;
    }

    private SiteProjectEntity requireSiteForUpdate(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("站点 ID 不能为空");
        }
        SiteProjectEntity site = projectMapper.selectByIdForUpdate(siteId);
        if (site == null || !Objects.equals(site.getDeleted(), 0)) {
            throw new IllegalArgumentException("站点不存在或已删除");
        }
        return site;
    }

    public record UiPackageStatusView(
            String status, String packageName, String version, String accessUrl) {}

    public record UiPackageUploadRecordView(
            Long id,
            String version,
            String packageName,
            Long fileSize,
            String uploadType,
            Long uploadUserId,
            String uploadUser,
            @JsonFormat(
                            shape = JsonFormat.Shape.STRING,
                            pattern = "yyyy-MM-dd HH:mm:ss",
                            timezone = "GMT+8")
                    LocalDateTime uploadTime) {
        static UiPackageUploadRecordView from(UiPackageUploadRecordEntity entity) {
            return new UiPackageUploadRecordView(
                    entity.getId(),
                    entity.getVersion(),
                    entity.getPackageName(),
                    entity.getFileSize(),
                    entity.getUploadType(),
                    entity.getUploadUserId(),
                    entity.getUploadUser(),
                    entity.getUploadTime());
        }
    }
}
