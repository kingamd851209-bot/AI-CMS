package com.xinhongru.ai.site.project;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserGroupEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.properties.ResourceProperties;
import com.xinhongru.service.userGroup.UserGroupService;
import com.xinhongru.site.SitePublishDirectory;
import com.xinhongru.site.config.ServicePortRange;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.site.locale.SiteLocaleService;
import com.xinhongru.site.mapper.SiteProjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class AiSiteProjectService {
    private static final String AI_SITE = "AI_SITE";
    private static final String SITE_MODE_CMS_MANAGED = "CMS_MANAGED";
    private static final String SITE_MODE_HEADLESS = "HEADLESS";
    private static final String TEMPLATE_PREVIEW = "TEMPLATE_PREVIEW";
    private static final String STRUCTURE_INDEPENDENT = "INDEPENDENT";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_SITE_SERVICE_PORT = 8080;

    private final SiteProjectMapper projectMapper;
    private final OneShotMapper oneShotMapper;
    private final SiteLocaleService siteLocaleService;

    public AiSiteProjectService(SiteProjectMapper projectMapper, OneShotMapper oneShotMapper) {
        this(projectMapper, oneShotMapper, null);
    }

    @Autowired
    public AiSiteProjectService(
            SiteProjectMapper projectMapper,
            OneShotMapper oneShotMapper,
            SiteLocaleService siteLocaleService) {
        this.projectMapper = Objects.requireNonNull(projectMapper, "projectMapper");
        this.oneShotMapper = Objects.requireNonNull(oneShotMapper, "oneShotMapper");
        this.siteLocaleService = siteLocaleService;
    }

    private UserGroupService userGroupService;

    @Autowired
    public void setUserGroupService(UserGroupService userGroupService) {
        this.userGroupService = userGroupService;
    }

    public List<SiteProjectEntity> listProjects() {
        return projectMapper.selectList(
                Wrappers.<SiteProjectEntity>lambdaQuery()
                        .eq(SiteProjectEntity::getDeleted, 0)
                        .orderByDesc(SiteProjectEntity::getUpdateTime, SiteProjectEntity::getId));
    }

    public List<SiteProjectEntity> listSites() {
        List<SiteProjectEntity> projects =
                projectMapper.selectList(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .eq(SiteProjectEntity::getDeleted, 0)
                                .eq(SiteProjectEntity::getProjectType, AI_SITE)
                                .orderByDesc(
                                        SiteProjectEntity::getUpdateTime,
                                        SiteProjectEntity::getId));
        return projects;
    }

    /** 按站点 id 集合批量查询（用于权限可见站点过滤，避免逐站点 N+1） */
    public List<SiteProjectEntity> listSites(Collection<Long> siteIds) {
        if (siteIds == null || siteIds.isEmpty()) {
            return List.of();
        }
        return projectMapper.selectList(
                Wrappers.<SiteProjectEntity>lambdaQuery()
                        .eq(SiteProjectEntity::getDeleted, 0)
                        .eq(SiteProjectEntity::getProjectType, AI_SITE)
                        .in(SiteProjectEntity::getId, siteIds)
                        .orderByDesc(SiteProjectEntity::getUpdateTime, SiteProjectEntity::getId));
    }

    public SiteProjectEntity getSite(Long siteId) {
        return requireSite(siteId);
    }

    @Transactional(rollbackFor = Exception.class)
    public SiteProjectEntity createSite(CreateSiteRequest request) {
        CreateSiteRequest safeRequest =
                request == null
                        ? new CreateSiteRequest(
                                null, null, null, null, null, null, null, null, null, null, null)
                        : request;
        String siteName = defaultText(safeRequest.siteName(), "未命名站点");
        String siteCode = normalizeCode(defaultText(safeRequest.siteCode(), siteName));
        rejectDuplicateSiteCode(siteCode);
        String sampleBaseUrl = blankToNull(safeRequest.sampleBaseUrl());
        String siteMode = normalizeSiteMode(safeRequest.siteMode());
        String siteDomain =
                blankToNull(defaultText(safeRequest.siteDomain(), domainFromUrl(sampleBaseUrl)));
        Integer siteServicePort =
                safeRequest.siteServicePort() != null
                        ? safeRequest.siteServicePort()
                        : DEFAULT_SITE_SERVICE_PORT;
        ServicePortRange.requireValid(siteServicePort);
        rejectDuplicateSiteEndpoint(siteDomain, siteServicePort, null);
        LocalDateTime now = LocalDateTime.now();
        SiteProjectEntity site =
                new SiteProjectEntity()
                        .setProjectName(siteName)
                        .setParentId(safeRequest.parentSiteId())
                        .setProjectType(AI_SITE)
                        .setProjectCode(siteCode)
                        .setSampleBaseUrl(sampleBaseUrl)
                        .setSiteDomain(siteDomain)
                        .setSiteServicePort(siteServicePort)
                        .setPublishDirectory(SitePublishDirectory.resolve(null, siteName, siteCode))
                        .setDefaultLanguage(defaultText(safeRequest.defaultLanguage(), "zh-CN"))
                        .setTemplateEntryPath(
                                blankToNull(
                                        defaultText(safeRequest.templateEntryPath(), "index.html")))
                        .setTemplatePackageStatus("NOT_UPLOADED")
                        .setSiteDescription(blankToNull(safeRequest.siteDescription()))
                        .setSiteServiceIp(
                                ResourceProperties.normalizeSiteDomain(
                                        safeRequest.siteServiceIp(), null))
                        .setStatus("DRAFT")
                        .setSiteMode(siteMode)
                        .setIsCurrent(0)
                        .setApplyStatus("NOT_APPLIED")
                        .setPreviewStatus("NOT_READY")
                        .setCreateTime(now)
                        .setUpdateTime(now)
                        .setDeleted(0);
        projectMapper.insert(site);
        OneshotEntity rootMenu =
                new OneshotEntity()
                        .setParentId(0)
                        .setClassName(siteName)
                        .setSiteProjectId(site.getId())
                        .setHasManageChannel(true)
                        .setHasFrontChannel(true)
                        .setIsDeleted(false)
                        .setIsSystem(false)
                        .setIsSubMenu(false)
                        .setIsCanDeleted(true)
                        .setIsCanAddSub(true)
                        .setIsCanEdit(true)
                        .setIsCanSort(true)
                        .setIsCanShowHidden(true)
                        .setIsShowCrumbs(true)
                        .setIsLoop(true);
        oneShotMapper.insertSiteRootMenu(rootMenu);
        // 新增栏目默认 bizcode 取自身 id
        oneShotMapper.assignBizcodeToOwnId(rootMenu.getId());
        Integer rootId = rootMenu.getId();
        if (rootId == null) {
            throw new IllegalStateException("创建站点根栏目失败");
        }
        rootMenu.setSiteRootId(rootId);
        oneShotMapper.updateSiteScopeByRoot(site.getId(), rootId);
        site.setRootMenuId(rootId).setSiteMode(siteMode).setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);
        if (siteLocaleService != null) {
            siteLocaleService.initializeDefaultLocale(site.getId(), site.getDefaultLanguage());
        }
        ensureDefaultGroups(site.getId());
        return site;
    }

    /** 新站点默认创建两条管理员组记录（超级管理员组、网站管理员组） */
    private void ensureDefaultGroups(Long siteId) {
        if (userGroupService == null || siteId == null) {
            return;
        }
        userGroupService.addUserGroup(
                new UserGroupEntity()
                        .setTitle("超级管理员组")
                        .setParentId(0)
                        .setEnabled(true)
                        .setSiteId(siteId)
                        .setGroupSetting("0")
                        .setOtherSetting("")
                        .setNote("站点默认超级管理员组"));
        userGroupService.addUserGroup(
                new UserGroupEntity()
                        .setTitle("网站管理员组")
                        .setParentId(0)
                        .setEnabled(true)
                        .setSiteId(siteId)
                        .setGroupSetting("0")
                        .setOtherSetting("")
                        .setNote("站点默认网站管理员组"));
    }

    @Transactional(rollbackFor = Exception.class)
    public SiteProjectEntity bindRootMenu(Long siteId, Integer rootMenuId) {
        SiteProjectEntity site = requireSite(siteId);
        if (!AI_SITE.equals(site.getProjectType())) {
            throw new IllegalArgumentException("站点不存在或已删除");
        }
        if (rootMenuId == null) {
            throw new IllegalArgumentException("根栏目ID不能为空");
        }
        OneshotEntity rootMenu = oneShotMapper.getModelById(rootMenuId);
        if (rootMenu == null || Boolean.TRUE.equals(rootMenu.getIsDeleted())) {
            throw new IllegalArgumentException("栏目不存在或已删除");
        }
        if (Boolean.TRUE.equals(rootMenu.getIsSystem())) {
            throw new IllegalArgumentException("不能绑定系统栏目");
        }
        if (rootMenu.getParentId() != 0) {
            throw new IllegalArgumentException("请选择根栏目");
        }
        if (rootMenuBelongsToAnotherSite(rootMenu, site)) {
            throw new IllegalStateException("根栏目已归属其他站点，不能绑定");
        }
        Integer previousRootMenuId = site.getRootMenuId();
        site.setRootMenuId(rootMenuId)
                .setSiteMode("CMS_MANAGED")
                .setUpdateTime(LocalDateTime.now());
        if (site.getApplyStatus() == null) {
            site.setApplyStatus("NOT_APPLIED");
        }
        if (site.getPreviewStatus() == null) {
            site.setPreviewStatus("NOT_READY");
        }
        if (previousRootMenuId != null && !previousRootMenuId.equals(rootMenuId)) {
            oneShotMapper.clearSiteScopeByRoot(site.getId(), previousRootMenuId);
        }
        oneShotMapper.updateSiteScopeByRoot(site.getId(), rootMenuId);
        projectMapper.updateById(site);
        return site;
    }

    @Transactional(rollbackFor = Exception.class)
    public SiteProjectEntity updatePublishDirectory(Long siteId, String publishDirectory) {
        SiteProjectEntity site = requireSite(siteId);
        site.setPublishDirectory(SitePublishDirectory.requireValid(publishDirectory))
                .setUpdateTime(LocalDateTime.now());
        try {
            projectMapper.updateById(site);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("发布目录已被其他站点使用", ex);
        }
        return site;
    }

    @Transactional(rollbackFor = Exception.class)
    public SiteProjectEntity deleteSite(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        if (!AI_SITE.equals(site.getProjectType())) {
            throw new IllegalArgumentException("站点不存在或已删除");
        }
        Integer rootMenuId = site.getRootMenuId();
        if (rootMenuId != null && oneShotMapper != null) {
            oneShotMapper.clearSiteScopeByRoot(site.getId(), rootMenuId);
        }
        site.setIsCurrent(0).setStatus("DELETED").setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);
        projectMapper.deleteById(siteId);
        site.setDeleted(1);
        return site;
    }

    @Transactional(rollbackFor = Exception.class)
    public SiteProjectEntity updateSite(Long siteId, UpdateSiteRequest request) {
        SiteProjectEntity site = getSite(siteId);
        if (request.siteName() != null && !request.siteName().isBlank()) {
            site.setProjectName(request.siteName().trim());
        }
        if (request.siteCode() != null && !request.siteCode().isBlank()) {
            String newCode = normalizeCode(request.siteCode().trim());
            if (!newCode.equals(site.getProjectCode())) {
                rejectDuplicateSiteCode(newCode);
            }
            site.setProjectCode(newCode);
        }
        if (request.siteDomain() != null) {
            site.setSiteDomain(blankToNull(request.siteDomain()));
        }
        if (request.siteServicePort() != null) {
            site.setSiteServicePort(ServicePortRange.requireValid(request.siteServicePort()));
        }
        rejectDuplicateSiteEndpoint(site.getSiteDomain(), site.getSiteServicePort(), site.getId());
        if (request.siteMode() != null && !request.siteMode().isBlank()) {
            site.setSiteMode(normalizeSiteMode(request.siteMode()));
        }
        site.setSiteDescription(blankToNull(request.siteDescription()));
        if (request.defaultLanguage() != null && !request.defaultLanguage().isBlank()) {
            site.setDefaultLanguage(request.defaultLanguage().trim());
        }
        if (request.siteServiceIp() != null) {
            site.setSiteServiceIp(
                    ResourceProperties.normalizeSiteDomain(
                            request.siteServiceIp(), site.getSiteServiceIp()));
        }
        site.setParentId(request.parentSiteId());
        site.setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);
        if (request.siteServiceIp() != null && site.getSiteServiceIp() == null) {
            projectMapper.update(
                    null,
                    Wrappers.<SiteProjectEntity>lambdaUpdate()
                            .eq(SiteProjectEntity::getId, siteId)
                            .set(SiteProjectEntity::getSiteServiceIp, null));
        }
        return site;
    }

    @Transactional(rollbackFor = Exception.class)
    public SiteProjectEntity clearTemplatePackage(Long siteId) {
        SiteProjectEntity site = requireSite(siteId);
        assertTemplatePackageCanBeCleared(site);
        site.setTemplatePackageName(null)
                .setTemplatePackageStatus("NOT_UPLOADED")
                .setCurrentBatchId(null)
                .setLastRecognitionBatchId(null)
                .setApplyStatus("NOT_APPLIED")
                .setPreviewStatus("NOT_READY")
                .setStatus("DRAFT")
                .setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(site);
        return site;
    }

    private void rejectDuplicateSiteCode(String siteCode) {
        List<SiteProjectEntity> duplicates =
                projectMapper.selectList(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .eq(SiteProjectEntity::getDeleted, 0)
                                .eq(SiteProjectEntity::getProjectType, AI_SITE)
                                .eq(SiteProjectEntity::getProjectCode, siteCode)
                                .last("limit 1"));
        if (duplicates != null && !duplicates.isEmpty()) {
            throw new IllegalStateException("站点编码已存在");
        }
    }

    /** 校验 (域名, 服务端口, 未删除) 组合唯一。 域名或端口为空时跳过，与数据库唯一索引对 NULL 不约束的语义一致。 */
    private void rejectDuplicateSiteEndpoint(
            String siteDomain, Integer siteServicePort, Long excludeSiteId) {
        if (siteDomain == null || siteDomain.isBlank() || siteServicePort == null) {
            return;
        }
        String normalizedDomain = siteDomain.trim().toLowerCase(Locale.ROOT);
        List<SiteProjectEntity> duplicates =
                projectMapper.selectList(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .eq(SiteProjectEntity::getDeleted, 0)
                                .eq(SiteProjectEntity::getProjectType, AI_SITE)
                                .apply("active_site_domain = {0}", normalizedDomain)
                                .eq(SiteProjectEntity::getSiteServicePort, siteServicePort)
                                .ne(excludeSiteId != null, SiteProjectEntity::getId, excludeSiteId)
                                .last("limit 1"));
        if (duplicates != null && !duplicates.isEmpty()) {
            throw new IllegalStateException("该域名与服务端口组合已存在");
        }
    }

    private void assertTemplatePackageCanBeCleared(SiteProjectEntity site) {
        if ("APPLIED".equals(site.getApplyStatus())) {
            throw new IllegalStateException("站点模板已应用，不能清空模板包");
        }
    }

    private boolean rootMenuBelongsToAnotherSite(OneshotEntity rootMenu, SiteProjectEntity site) {
        Long ownerProjectId = rootMenu.getSiteProjectId();
        if (ownerProjectId != null && !ownerProjectId.equals(site.getId())) {
            return true;
        }
        Integer ownerRootId = rootMenu.getSiteRootId();
        return ownerRootId != null && !ownerRootId.equals(rootMenu.getId());
    }

    private SiteProjectEntity requireSite(Long siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("siteId is required");
        }
        SiteProjectEntity site =
                projectMapper.selectOne(
                        Wrappers.<SiteProjectEntity>lambdaQuery()
                                .eq(SiteProjectEntity::getId, siteId)
                                .eq(SiteProjectEntity::getDeleted, 0));
        if (site == null) {
            throw new IllegalArgumentException("站点不存在或已删除");
        }
        if (!AI_SITE.equals(site.getProjectType())) {
            throw new IllegalArgumentException("站点不存在或已删除");
        }
        return site;
    }

    private static String normalizeCode(String value) {
        String normalized =
                value == null
                        ? ""
                        : value.trim()
                                .toUpperCase(Locale.ROOT)
                                .replaceAll("[^A-Z0-9]+", "-")
                                .replaceAll("(^-+|-+$)", "");
        if (normalized.isBlank()) {
            return "PREVIEW";
        }
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 站点模式归一化：支持逗号分隔多值（如 {@code CMS_MANAGED,HEADLESS}）。 去空、去重、转大写后按固定顺序拼接；为空默认 {@code
     * CMS_MANAGED}；含未知值报错。
     */
    private static String normalizeSiteMode(String value) {
        if (value == null || value.isBlank()) {
            return SITE_MODE_CMS_MANAGED;
        }
        Set<String> modes = new LinkedHashSet<>();
        for (String raw : value.split(",")) {
            String mode = raw.trim().toUpperCase(Locale.ROOT);
            if (mode.isEmpty()) {
                continue;
            }
            if (!SITE_MODE_CMS_MANAGED.equals(mode) && !SITE_MODE_HEADLESS.equals(mode)) {
                throw new IllegalArgumentException("站点模式不合法");
            }
            modes.add(mode);
        }
        if (modes.isEmpty()) {
            return SITE_MODE_CMS_MANAGED;
        }
        List<String> ordered = new ArrayList<>();
        if (modes.contains(SITE_MODE_CMS_MANAGED)) {
            ordered.add(SITE_MODE_CMS_MANAGED);
        }
        if (modes.contains(SITE_MODE_HEADLESS)) {
            ordered.add(SITE_MODE_HEADLESS);
        }
        return String.join(",", ordered);
    }

    /** 判断站点模式（逗号分隔多值）是否包含指定模式 */
    public static boolean hasMode(String siteMode, String mode) {
        if (siteMode == null || siteMode.isBlank() || mode == null) {
            return false;
        }
        for (String raw : siteMode.split(",")) {
            if (raw.trim().equalsIgnoreCase(mode)) {
                return true;
            }
        }
        return false;
    }

    private static String domainFromUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            return uri.getHost();
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    public record CreateSiteRequest(
            String siteName,
            String siteCode,
            String siteDomain,
            String sampleBaseUrl,
            String defaultLanguage,
            String templateEntryPath,
            String siteDescription,
            String siteMode,
            String siteServiceIp,
            Long parentSiteId,
            Integer siteServicePort) {
        public CreateSiteRequest(
                String siteName,
                String siteCode,
                String siteDomain,
                String sampleBaseUrl,
                String defaultLanguage,
                String templateEntryPath,
                String siteDescription) {
            this(
                    siteName,
                    siteCode,
                    siteDomain,
                    sampleBaseUrl,
                    defaultLanguage,
                    templateEntryPath,
                    siteDescription,
                    null,
                    null,
                    null,
                    null);
        }

        public CreateSiteRequest(
                String siteName,
                String siteCode,
                String siteDomain,
                String sampleBaseUrl,
                String defaultLanguage,
                String templateEntryPath,
                String siteDescription,
                String siteMode) {
            this(
                    siteName,
                    siteCode,
                    siteDomain,
                    sampleBaseUrl,
                    defaultLanguage,
                    templateEntryPath,
                    siteDescription,
                    siteMode,
                    null,
                    null,
                    null);
        }
    }

    public record UpdateSiteRequest(
            String siteName,
            String siteCode,
            String siteDomain,
            String siteMode,
            String siteDescription,
            String defaultLanguage,
            String siteServiceIp,
            Long parentSiteId,
            Integer siteServicePort) {}
}
