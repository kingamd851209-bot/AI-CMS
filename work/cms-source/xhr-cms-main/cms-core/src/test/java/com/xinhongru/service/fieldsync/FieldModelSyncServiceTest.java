package com.xinhongru.service.fieldsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinhongru.ai.site.project.AiSiteProjectService;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.OneShotMapper;
import com.xinhongru.responseVo.oneshot.EditOneshotDto;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.context.SiteContextService;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

class FieldModelSyncServiceTest {
    SiteContextService siteContext = mock(SiteContextService.class);
    AiSiteProjectService projectService = mock(AiSiteProjectService.class);
    OneShotService oneShotService = mock(OneShotService.class);
    OneShotMapper oneShotMapper = mock(OneShotMapper.class);
    FieldModelSyncService service =
            new FieldModelSyncService(siteContext, projectService, oneShotService, oneShotMapper);

    {
        var permissions =
                mock(
                        com.xinhongru.permission.AdminPermissionService.class,
                        Mockito.RETURNS_DEEP_STUBS);
        when(permissions.snapshot(any()).hasMenuAccess(Mockito.anyInt())).thenReturn(true);
        service.setPermissionService(permissions);
        service.setPublicSearchProjectionService(
                mock(com.xinhongru.cms.search.PublicSearchProjectionService.class));
        when(oneShotMapper.lockFieldConfiguration(Mockito.anyLong(), Mockito.anyLong()))
                .thenAnswer(
                        call ->
                                oneShotService
                                        .getAllMenuBySiteProjectId(call.getArgument(0))
                                        .stream()
                                        .filter(
                                                menu ->
                                                        menu.getId().longValue()
                                                                == (Long) call.getArgument(1))
                                        .findFirst()
                                        .orElse(null));
    }

    private static String model(String title) {
        return com.xinhongru.content.model.ContentModelCodec.write(
                List.of(
                        new com.xinhongru.content.model.ContentFieldDefinition()
                                .setFieldName("singleLine1")
                                .setTitle(title)
                                .setFieldType("SingleLine")
                                .setMaxLength(255)
                                .setIsOpen("on")));
    }

    private static OneshotEntity menu(int id, int parentId, String name, String modelField) {
        return new OneshotEntity()
                .setId(id)
                .setSiteProjectId(1L)
                .setParentId(parentId)
                .setClassName(name)
                .setModelField(model(modelField));
    }

    /**
     * 同站点：把父栏目 B 重映射为提供方 A 并勾选“包含子栏目”。 前端同站点自动配对会把每行默认映射到自身；这些残留的 identity 映射（源=目标）不得
     * 覆盖掉由父栏目“包含子栏目”同步进来的子栏目配置。
     */
    @Test
    void sameSiteRemapWithIncludeSubKeepsChildSynced() {
        long siteId = 1L;
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(siteContext.requireCurrentSiteId(request)).thenReturn(siteId);

        // A(提供方) 与其子 a1；B(接收方) 与其子 b1（a1/b1 同名，供按名称匹配）
        OneshotEntity a = menu(1, 0, "产品中心", "A-config");
        OneshotEntity a1 = menu(2, 1, "新闻", "a1-config");
        OneshotEntity b = menu(3, 0, "新闻中心", "B-config");
        OneshotEntity b1 = menu(4, 3, "新闻", "b1-config");
        when(oneShotService.getAllMenuBySiteProjectId(siteId)).thenReturn(List.of(a, a1, b, b1));

        List<FieldModelSyncService.Mapping> mappings =
                List.of(
                        new FieldModelSyncService.Mapping(1, 1, false),
                        new FieldModelSyncService.Mapping(3, 1, true),
                        new FieldModelSyncService.Mapping(2, 2, false),
                        new FieldModelSyncService.Mapping(4, 4, false));

        service.apply(request, siteId, mappings);

        ArgumentCaptor<Integer> idCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> modelCap = ArgumentCaptor.forClass(String.class);
        verify(oneShotMapper, Mockito.atLeastOnce())
                .updateFieldConfig(idCap.capture(), any(), modelCap.capture(), any(), any());

        List<Integer> ids = idCap.getAllValues();
        List<String> models = modelCap.getAllValues();
        String lastModelForB1 = null;
        for (int i = 0; i < ids.size(); i++) {
            if (Integer.valueOf(4).equals(ids.get(i))) {
                lastModelForB1 = models.get(i);
            }
        }
        // b1 的最终写入应为 a1 的配置，而不是被 identity 映射写回自身
        assertThat(lastModelForB1).isEqualTo(model("a1-config"));
    }

    @Test
    void identityMappingIsSkipped() {
        long siteId = 1L;
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(siteContext.requireCurrentSiteId(request)).thenReturn(siteId);

        OneshotEntity b = menu(3, 0, "新闻中心", "B-config");
        when(oneShotService.getAllMenuBySiteProjectId(siteId)).thenReturn(List.of(b));

        List<FieldModelSyncService.Mapping> mappings =
                List.of(new FieldModelSyncService.Mapping(3, 3, true));

        FieldModelSyncService.ApplyResult result = service.apply(request, siteId, mappings);

        verify(oneShotMapper, Mockito.never()).updateFieldConfig(eq(3), any(), any(), any(), any());
        assertThat(result.syncedCount()).isZero();
    }

    /** 从目标站点拉取数据模型时，栏目类型（单篇/列表）也须一并同步。 */
    @Test
    void syncsPageTypeAlongWithFieldConfig() {
        long siteId = 1L;
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(siteContext.requireCurrentSiteId(request)).thenReturn(siteId);

        OneshotEntity provider = menu(1, 0, "产品", "A-config").setPageType(0);
        OneshotEntity receiver = menu(3, 0, "产品", "B-config").setPageType(1);
        when(oneShotService.getAllMenuBySiteProjectId(siteId))
                .thenReturn(List.of(provider, receiver));

        List<FieldModelSyncService.Mapping> mappings =
                List.of(new FieldModelSyncService.Mapping(3, 1, false));

        service.apply(request, siteId, mappings);

        ArgumentCaptor<EditOneshotDto> typeCap = ArgumentCaptor.forClass(EditOneshotDto.class);
        verify(oneShotMapper).updateEdit(typeCap.capture());
        assertThat(typeCap.getValue().getId()).isEqualTo(3);
        assertThat(typeCap.getValue().getPageType()).isEqualTo(0);
    }

    /** 同站点：接收方 B 无子栏目，提供方 A 有子栏目 a1（名“新闻”），勾选“包含子栏目”时 应把 a1 复制/新建到 B 下，并复制其字段配置。 */
    @Test
    void sameSiteIncludeSubCreatesMissingChildren() {
        long siteId = 1L;
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(siteContext.requireCurrentSiteId(request)).thenReturn(siteId);

        // A(提供方) 与其子 a1；B(接收方) 无子栏目
        OneshotEntity a = menu(1, 0, "产品中心", "A-config");
        OneshotEntity a1 =
                menu(2, 1, "新闻", "a1-model")
                        .setMenuField(model("a1-menu"))
                        .setModelList("a1-list")
                        .setModelSearch("a1-search");
        OneshotEntity b = menu(3, 0, "新闻中心", "B-config");
        when(oneShotService.getAllMenuBySiteProjectId(siteId)).thenReturn(List.of(a, a1, b));

        when(oneShotMapper.insertMenu(any(EditOneshotDto.class)))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(0, EditOneshotDto.class).setId(99);
                            return 1;
                        });

        List<FieldModelSyncService.Mapping> mappings =
                List.of(new FieldModelSyncService.Mapping(3, 1, true));

        FieldModelSyncService.ApplyResult result = service.apply(request, siteId, mappings);

        assertThat(result.createdSubMenuCount()).isEqualTo(1);

        // 新建栏目的字段配置通过 insertMenu 的入参复制（menuField/modelField/modelList/modelSearch）
        ArgumentCaptor<EditOneshotDto> dtoCap = ArgumentCaptor.forClass(EditOneshotDto.class);
        verify(oneShotMapper).insertMenu(dtoCap.capture());
        EditOneshotDto dto = dtoCap.getValue();
        assertThat(dto.getParentId()).isEqualTo(b.getId());
        assertThat(dto.getClassName()).isEqualTo("新闻");
        assertThat(dto.getMenuField()).isEqualTo(model("a1-menu"));
        assertThat(dto.getModelField()).isEqualTo(model("a1-model"));
        assertThat(dto.getModelList()).isEqualTo("a1-list");
        assertThat(dto.getModelSearch()).isEqualTo("a1-search");

        verify(oneShotMapper).assignBizcodeToOwnId(99);
    }

    /** 接收方 B 已有子栏目 bX（名“栏目X”），提供方 a1（名“新闻”）不匹配 → 新建，且 bX 不被更新。 */
    @Test
    void missingChildWithDifferentNameIsCreated() {
        long siteId = 1L;
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(siteContext.requireCurrentSiteId(request)).thenReturn(siteId);

        OneshotEntity a = menu(1, 0, "产品中心", "A-config");
        OneshotEntity a1 = menu(2, 1, "新闻", "a1-config");
        OneshotEntity b = menu(3, 0, "新闻中心", "B-config");
        OneshotEntity bx = menu(4, 3, "栏目X", "bx-config");
        when(oneShotService.getAllMenuBySiteProjectId(siteId)).thenReturn(List.of(a, a1, b, bx));

        when(oneShotMapper.insertMenu(any(EditOneshotDto.class)))
                .thenAnswer(
                        inv -> {
                            inv.getArgument(0, EditOneshotDto.class).setId(99);
                            return 1;
                        });

        List<FieldModelSyncService.Mapping> mappings =
                List.of(new FieldModelSyncService.Mapping(3, 1, true));

        FieldModelSyncService.ApplyResult result = service.apply(request, siteId, mappings);

        assertThat(result.createdSubMenuCount()).isEqualTo(1);
        // bX 未被更新（名称不匹配 → 新建而非更新）
        verify(oneShotMapper, Mockito.never()).updateFieldConfig(eq(4), any(), any(), any(), any());
        verify(oneShotMapper).assignBizcodeToOwnId(99);
    }
}
