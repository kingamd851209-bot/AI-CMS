package com.xinhongru.service.fieldsync;

import com.xinhongru.ai.site.project.AiSiteProjectService;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.site.entity.SiteProjectEntity;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 字段模型同步：从目标站点拉取栏目的字段设置配置（menuField/modelField/modelList/modelSearch） 覆盖到当前站点对应栏目。 */
@Service
public class FieldModelSyncService {
    private final SiteContextService siteContextService;
    private final AiSiteProjectService projectService;
    private final OneShotService oneShotService;
    private final OneShotMapper oneShotMapper;

    private com.xinhongru.cms.search.PublicSearchProjectionService publicSearchProjectionService;
    private com.xinhongru.permission.AdminPermissionService permissionService;

    @org.springframework.beans.factory.annotation.Autowired
    public void setPublicSearchProjectionService(
            com.xinhongru.cms.search.PublicSearchProjectionService value) {
        publicSearchProjectionService = value;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setPermissionService(com.xinhongru.permission.AdminPermissionService value) {
        permissionService = value;
    }

    public FieldModelSyncService(
            SiteContextService siteContextService,
            AiSiteProjectService projectService,
            OneShotService oneShotService,
            OneShotMapper oneShotMapper) {
        this.siteContextService = siteContextService;
        this.projectService = projectService;
        this.oneShotService = oneShotService;
        this.oneShotMapper = oneShotMapper;
    }

    /** 可同步的目标站点（用户可访问，含当前站点，用于同站点同步）。 */
    public List<TargetSite> listTargets(HttpServletRequest request) {
        siteContextService.requireCurrentSiteId(request);
        return siteContextService.listAvailableSites(request).stream()
                .filter(site -> site.getId() != null)
                .map(
                        site ->
                                new TargetSite(
                                        site.getId(), site.getProjectName(), site.getProjectCode()))
                .toList();
    }

    /** 预览：当前/目标站点栏目列表 + 基于名称的自动映射建议。 */
    public Preview preview(HttpServletRequest request, Long targetSiteId) {
        long currentSiteId = siteContextService.requireCurrentSiteId(request);
        siteContextService.assertCanAccessSite(request, targetSiteId);
        SiteProjectEntity currentSite = projectService.getSite(currentSiteId);
        SiteProjectEntity targetSite = projectService.getSite(targetSiteId);
        List<OneshotEntity> sourceMenus = oneShotService.getAllMenuBySiteProjectId(currentSiteId);
        List<OneshotEntity> targetMenus = oneShotService.getAllMenuBySiteProjectId(targetSiteId);
        return new Preview(
                toTarget(currentSite),
                toTarget(targetSite),
                toMenuSummaries(sourceMenus),
                toMenuSummaries(targetMenus),
                buildAutoMappings(sourceMenus, targetMenus));
    }

    /** 执行同步：将目标站点栏目的字段配置覆盖到当前站点对应栏目。 */
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(
            value = {"modelById", "oneshotListByParentId", "allMenu"},
            allEntries = true,
            beforeInvocation = true)
    public ApplyResult apply(
            HttpServletRequest request, Long targetSiteId, List<Mapping> mappings) {
        long currentSiteId = siteContextService.requireCurrentSiteId(request);
        siteContextService.assertCanAccessSite(request, targetSiteId);
        List<OneshotEntity> sourceMenus = oneShotService.getAllMenuBySiteProjectId(currentSiteId);
        List<OneshotEntity> targetMenus = oneShotService.getAllMenuBySiteProjectId(targetSiteId);
        Map<Integer, OneshotEntity> targetById =
                targetMenus.stream()
                        .filter(menu -> menu.getId() != null)
                        .collect(Collectors.toMap(OneshotEntity::getId, menu -> menu, (a, b) -> a));

        List<Mapping> safeMappings = mappings == null ? List.of() : mappings;
        Map<Integer, List<OneshotEntity>> receiverChildrenIndex = childIndex(sourceMenus);
        java.util.Set<Integer> planned = new java.util.TreeSet<>();
        for (Mapping mapping : safeMappings) {
            if (mapping == null
                    || mapping.sourceMenuId() == null
                    || mapping.targetMenuId() == null
                    || mapping.sourceMenuId().equals(mapping.targetMenuId())) continue;
            OneshotEntity receiver = findMenu(sourceMenus, mapping.sourceMenuId());
            OneshotEntity provider = targetById.get(mapping.targetMenuId());
            if (receiver == null || provider == null) continue;
            planned.add(receiver.getId());
            if (Boolean.TRUE.equals(mapping.includeSubMenus()))
                collectReceiverTargets(
                        receiver, provider, receiverChildrenIndex, targetMenus, planned);
        }
        Map<Integer, OneshotEntity> locked = new java.util.HashMap<>();
        for (Integer id : planned) requireReceiverAccess(request, id);
        for (Integer id : planned) {
            OneshotEntity value =
                    oneShotMapper.lockFieldConfiguration(currentSiteId, id.longValue());
            if (value == null
                    || !java.util.Objects.equals(value.getId(), id)
                    || !java.util.Objects.equals(value.getSiteProjectId(), currentSiteId))
                throw new IllegalArgumentException("栏目结构已变化，请重新预览");
            locked.put(id, value);
        }
        sourceMenus =
                sourceMenus.stream()
                        .map(menu -> menu == null ? null : locked.getOrDefault(menu.getId(), menu))
                        .collect(
                                java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        receiverChildrenIndex = childIndex(sourceMenus);

        List<Integer> syncedMenuIds = new ArrayList<>();
        int createdSub = 0;
        int updatedSub = 0;
        for (Mapping mapping : safeMappings) {
            if (mapping == null
                    || mapping.sourceMenuId() == null
                    || mapping.targetMenuId() == null) {
                continue;
            }
            // 源=目标为无操作；跳过以免其（同站点自动配对产生的）残留映射覆盖
            // 父栏目“包含子栏目”已同步到子栏目的配置
            if (mapping.sourceMenuId().equals(mapping.targetMenuId())) {
                continue;
            }
            OneshotEntity sourceMenu = findMenu(sourceMenus, mapping.sourceMenuId());
            OneshotEntity targetMenu = targetById.get(mapping.targetMenuId());
            if (sourceMenu == null || targetMenu == null) {
                continue;
            }
            copyFieldConfiguration(sourceMenu, targetMenu, locked, request);
            syncedMenuIds.add(sourceMenu.getId());
            if (Boolean.TRUE.equals(mapping.includeSubMenus())) {
                int[] sub =
                        copySubtree(
                                sourceMenu,
                                targetMenu,
                                receiverChildrenIndex,
                                targetMenus,
                                syncedMenuIds,
                                locked,
                                request);
                createdSub += sub[0];
                updatedSub += sub[1];
            }
        }
        return new ApplyResult(syncedMenuIds, syncedMenuIds.size(), createdSub, updatedSub);
    }

    private Map<Integer, List<OneshotEntity>> childIndex(List<OneshotEntity> menus) {
        Map<Integer, List<OneshotEntity>> result = new HashMap<>();
        for (OneshotEntity menu : menus)
            if (menu != null && menu.getId() != null)
                result.computeIfAbsent(menu.getParentId(), key -> new ArrayList<>()).add(menu);
        return result;
    }

    private void collectReceiverTargets(
            OneshotEntity receiver,
            OneshotEntity provider,
            Map<Integer, List<OneshotEntity>> index,
            List<OneshotEntity> providers,
            java.util.Set<Integer> targets) {
        for (OneshotEntity child : directChildren(providers, provider.getId())) {
            OneshotEntity matched =
                    matchMenu(child, index.getOrDefault(receiver.getId(), List.of()));
            if (matched != null) {
                // 同一接收节点可先被显式映射；集合去重不能跳过本映射实际会写入的子树。
                targets.add(matched.getId());
                collectReceiverTargets(matched, child, index, providers, targets);
            }
        }
    }

    private void requireReceiverAccess(HttpServletRequest request, Integer id) {
        if (!permissionService.snapshot(request).hasMenuAccess(id))
            throw com.xinhongru.security.AdminSecurityExceptionHandler.AdminSecurityException
                    .forbidden("CMS_MENU_ACCESS_DENIED", "无权更改栏目 " + id);
    }

    private void copyFieldConfiguration(
            OneshotEntity receiver,
            OneshotEntity provider,
            Map<Integer, OneshotEntity> locked,
            HttpServletRequest request) {
        if (!locked.containsKey(receiver.getId()))
            throw new IllegalArgumentException("栏目结构已变化，请重新预览");
        requireReceiverAccess(request, receiver.getId());
        String menuFields =
                com.xinhongru.content.model.ContentModelCodec.write(
                        com.xinhongru.content.model.ContentFieldCompatibility.retainExtensions(
                                com.xinhongru.content.model.ContentModelCodec.read(
                                        receiver.getMenuField()),
                                com.xinhongru.content.model.ContentModelCodec.read(
                                        provider.getMenuField())));
        String modelFields =
                com.xinhongru.content.model.ContentModelCodec.write(
                        com.xinhongru.content.model.ContentFieldCompatibility.retainExtensions(
                                com.xinhongru.content.model.ContentModelCodec.read(
                                        receiver.getModelField()),
                                com.xinhongru.content.model.ContentModelCodec.read(
                                        provider.getModelField())));
        oneShotMapper.updateFieldConfig(
                receiver.getId(),
                menuFields,
                modelFields,
                provider.getModelList(),
                provider.getModelSearch());
        syncPageType(receiver.getId(), provider.getPageType());
        // 后续映射可能再次命中同一栏目，兼容检查必须基于本事务最新写入配置。
        receiver.setMenuField(menuFields)
                .setModelField(modelFields)
                .setModelList(provider.getModelList())
                .setModelSearch(provider.getModelSearch());
        if (provider.getPageType() != null) receiver.setPageType(provider.getPageType());
        locked.put(receiver.getId(), receiver);
        publicSearchProjectionService.rebuildMenu(receiver.getSiteProjectId(), receiver.getId());
    }

    private TargetSite toTarget(SiteProjectEntity site) {
        return new TargetSite(site.getId(), site.getProjectName(), site.getProjectCode());
    }

    private List<MenuSummary> toMenuSummaries(List<OneshotEntity> menus) {
        if (menus == null) {
            return List.of();
        }
        return menus.stream()
                .filter(menu -> menu != null && menu.getId() != null)
                .map(
                        menu ->
                                new MenuSummary(
                                        menu.getId(),
                                        menu.getClassName(),
                                        menu.getClassEnName(),
                                        menu.getParentId(),
                                        menu.getPageType()))
                .toList();
    }

    private List<Mapping> buildAutoMappings(
            List<OneshotEntity> sourceMenus, List<OneshotEntity> targetMenus) {
        List<Mapping> mappings = new ArrayList<>();
        if (sourceMenus == null) {
            return mappings;
        }
        for (OneshotEntity source : sourceMenus) {
            if (source == null || source.getId() == null) {
                continue;
            }
            OneshotEntity matched = matchMenu(source, targetMenus);
            if (matched != null && matched.getId() != null) {
                mappings.add(new Mapping(source.getId(), matched.getId(), false));
            }
        }
        return mappings;
    }

    private OneshotEntity matchMenu(OneshotEntity source, List<OneshotEntity> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String name = source.getClassName();
        if (StringUtils.isNotBlank(name)) {
            for (OneshotEntity candidate : candidates) {
                if (candidate != null && name.equals(candidate.getClassName())) {
                    return candidate;
                }
            }
        }
        String enName = source.getClassEnName();
        if (StringUtils.isNotBlank(enName)) {
            for (OneshotEntity candidate : candidates) {
                if (candidate != null && enName.equals(candidate.getClassEnName())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private OneshotEntity findMenu(List<OneshotEntity> menus, Integer id) {
        if (menus == null || id == null) {
            return null;
        }
        for (OneshotEntity menu : menus) {
            if (menu != null && id.equals(menu.getId())) {
                return menu;
            }
        }
        return null;
    }

    /** 栏目类型（单篇/列表）决定字段设置的取数口径，须随数据模型一起同步。 */
    private void syncPageType(Integer menuId, Integer pageType) {
        if (menuId == null || pageType == null) {
            return;
        }
        oneShotMapper.updateEdit(new EditOneshotDto().setId(menuId).setPageType(pageType));
    }

    /**
     * 把 provider 的子栏目子树复制到 receiver 下：同名子栏目→更新字段配置；不存在→新建栏目并复制字段配置；递归。返回 int[]{createdCount,
     * updatedCount}。
     */
    private int[] copySubtree(
            OneshotEntity receiver,
            OneshotEntity provider,
            Map<Integer, List<OneshotEntity>> receiverChildrenIndex,
            List<OneshotEntity> targetMenus,
            List<Integer> syncedMenuIds,
            Map<Integer, OneshotEntity> locked,
            HttpServletRequest request) {
        int created = 0;
        int updated = 0;
        List<OneshotEntity> providerChildren = directChildren(targetMenus, provider.getId());
        List<OneshotEntity> receiverChildren =
                receiverChildrenIndex.computeIfAbsent(receiver.getId(), k -> new ArrayList<>());
        for (OneshotEntity pc : providerChildren) {
            OneshotEntity matched = matchMenu(pc, receiverChildren);
            OneshotEntity receiverChild;
            if (matched != null) {
                copyFieldConfiguration(matched, pc, locked, request);
                receiverChild = matched;
                updated++;
            } else {
                requireReceiverAccess(request, receiver.getId());
                if (Boolean.FALSE.equals(receiver.getIsCanAddSub()))
                    throw com.xinhongru.security.AdminSecurityExceptionHandler
                            .AdminSecurityException.forbidden(
                            "CMS_OPERATION_DENIED", "父栏目不允许新增子栏目");
                receiverChild = createColumnFrom(receiver, pc);
                locked.put(receiverChild.getId(), receiverChild);
                publicSearchProjectionService.rebuildMenu(
                        receiverChild.getSiteProjectId(), receiverChild.getId());
                receiverChildren.add(receiverChild);
                created++;
            }
            syncedMenuIds.add(receiverChild.getId());
            int[] sub =
                    copySubtree(
                            receiverChild,
                            pc,
                            receiverChildrenIndex,
                            targetMenus,
                            syncedMenuIds,
                            locked,
                            request);
            created += sub[0];
            updated += sub[1];
        }
        return new int[] {created, updated};
    }

    /** 依据源子栏目 src 在 receiver 下新建一个子栏目（复制可配置字段），返回带新 id 的实体。 */
    private OneshotEntity createColumnFrom(OneshotEntity receiver, OneshotEntity src) {
        EditOneshotDto dto =
                new EditOneshotDto()
                        .setParentId(receiver.getId())
                        .setSiteProjectId(receiver.getSiteProjectId())
                        .setSiteRootId(receiver.getSiteRootId())
                        .setClassName(src.getClassName())
                        .setClassEnName(src.getClassEnName())
                        .setHasManageChannel(src.getHasManageChannel())
                        .setHasFrontChannel(src.getHasFrontChannel())
                        .setShowPosition(src.getShowPosition())
                        .setPageType(src.getPageType())
                        .setListPageUrl(src.getListPageUrl())
                        .setListPageTemplate(src.getListPageTemplate())
                        .setDetailPageUrl(src.getDetailPageUrl())
                        .setDetailPageTemplate(src.getDetailPageTemplate())
                        .setIdentifies(src.getIdentifies())
                        .setSort(src.getSort())
                        .setMenuField(src.getMenuField())
                        .setModelField(src.getModelField())
                        .setModelList(src.getModelList())
                        .setModelSearch(src.getModelSearch())
                        .setIsSubMenu(true)
                        .setIsCanDeleted(src.getIsCanDeleted())
                        .setIsCanAddSub(src.getIsCanAddSub())
                        .setIsCanEdit(src.getIsCanEdit())
                        .setIsCanSort(src.getIsCanSort())
                        .setIsShowCrumbs(src.getIsShowCrumbs())
                        .setIsLoop(src.getIsLoop())
                        .setWebsitePublic(src.getWebsitePublic());
        oneShotMapper.insertMenu(dto);
        Integer newId = dto.getId();
        oneShotMapper.assignBizcodeToOwnId(newId);
        return new OneshotEntity()
                .setId(newId)
                .setParentId(receiver.getId())
                .setSiteProjectId(receiver.getSiteProjectId())
                .setSiteRootId(receiver.getSiteRootId())
                .setClassName(src.getClassName())
                .setClassEnName(src.getClassEnName())
                .setPageType(src.getPageType())
                .setMenuField(src.getMenuField())
                .setModelField(src.getModelField())
                .setModelList(src.getModelList())
                .setModelSearch(src.getModelSearch())
                .setIsCanAddSub(src.getIsCanAddSub());
    }

    private List<OneshotEntity> directChildren(List<OneshotEntity> menus, Integer parentId) {
        if (menus == null || parentId == null) {
            return List.of();
        }
        return menus.stream()
                .filter(menu -> menu != null && menu.getId() != null)
                .filter(menu -> Integer.valueOf(menu.getParentId()).equals(parentId))
                .toList();
    }

    public record TargetSite(Long id, String name, String code) {}

    public record MenuSummary(
            Integer id, String className, String classEnName, Integer parentId, Integer pageType) {}

    public record Mapping(Integer sourceMenuId, Integer targetMenuId, Boolean includeSubMenus) {}

    public record Preview(
            TargetSite currentSite,
            TargetSite targetSite,
            List<MenuSummary> sourceMenus,
            List<MenuSummary> targetMenus,
            List<Mapping> autoMappings) {}

    public record ApplyResult(
            List<Integer> syncedMenuIds,
            Integer syncedCount,
            Integer createdSubMenuCount,
            Integer updatedSubMenuCount) {}
}
