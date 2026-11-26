package com.xinhongru.api.admin.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.mapper.ContentMapper;
import com.xinhongru.service.CheckRecordService;
import com.xinhongru.service.ContentHistoryService;
import com.xinhongru.service.content.ContentService;
import com.xinhongru.service.fieldsetting.FieldSettingService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.web.service.MenuService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class CmsCoreV2ApiControllerSelectOptionsTest {
    private final OneShotService oneShotService = mock(OneShotService.class);
    private final MenuService menuService = mock(MenuService.class);
    private final CmsCoreV2ApiController controller =
            new CmsCoreV2ApiController(
                    oneShotService,
                    menuService,
                    mock(ContentService.class),
                    mock(ContentMapper.class),
                    mock(ContentHistoryService.class),
                    mock(CheckRecordService.class),
                    mock(FieldSettingService.class),
                    mock(SiteContextService.class));

    @BeforeEach
    void sameSiteFixture() {
        for (int id : List.of(5, 6, 8, 11, 12, 13))
            when(oneShotService.getModelById(id))
                    .thenReturn(new OneshotEntity().setId(id).setSiteProjectId(1L));
    }

    @Test
    void childrenMenuOptionsMapsIdAndName() {
        when(menuService.getOneshotListByParentId(5))
                .thenReturn(List.of(new OneshotEntity().setId(11).setClassName("新闻")));

        var options = controller.childrenMenuOptions(1L, List.of(5));

        assertThat(options).hasSize(1);
        assertThat(options.get(0).value()).isEqualTo("11");
        assertThat(options.get(0).label()).isEqualTo("新闻");
        assertThat(options.get(0).group()).isNull();
    }

    @Test
    void childrenMenuOptionsEmptyWhenNoChildren() {
        when(menuService.getOneshotListByParentId(5)).thenReturn(List.of());
        assertThat(controller.childrenMenuOptions(1L, List.of(5))).isEmpty();
    }

    @Test
    void childrenMenuOptionsUnionsAndDedups() {
        when(menuService.getOneshotListByParentId(5))
                .thenReturn(
                        List.of(
                                new OneshotEntity().setId(11).setClassName("新闻"),
                                new OneshotEntity().setId(12).setClassName("产品")));
        when(menuService.getOneshotListByParentId(6))
                .thenReturn(
                        List.of(
                                new OneshotEntity().setId(12).setClassName("产品"),
                                new OneshotEntity().setId(13).setClassName("案例")));

        var options = controller.childrenMenuOptions(1L, List.of(5, 6));

        assertThat(options).extracting("value").containsExactly("11", "12", "13");
    }

    @Test
    void resolveRoutesChildrenQueryToChildColumns() {
        when(menuService.getOneshotListByParentId(5))
                .thenReturn(List.of(new OneshotEntity().setId(11).setClassName("新闻")));

        var options = controller.resolveDynamicSelectQuery(1L, 8, "{$5_children$}");

        assertThat(options).hasSize(1);
        assertThat(options.get(0).value()).isEqualTo("11");
        assertThat(options.get(0).label()).isEqualTo("新闻");
        assertThat(options.get(0).group()).isNull();
    }

    @Test
    void resolveRoutesMultiParentChildrenQuery() {
        when(menuService.getOneshotListByParentId(5))
                .thenReturn(List.of(new OneshotEntity().setId(11).setClassName("新闻")));
        when(menuService.getOneshotListByParentId(6))
                .thenReturn(List.of(new OneshotEntity().setId(12).setClassName("产品")));

        var options = controller.resolveDynamicSelectQuery(1L, 8, "{$5,6_children$}");

        assertThat(options).extracting("value").containsExactly("11", "12");
    }

    @Test
    void resolveChildrenQueryEmptyWhenNoChildren() {
        when(menuService.getOneshotListByParentId(5)).thenReturn(List.of());

        assertThat(controller.resolveDynamicSelectQuery(1L, 8, "{$5_children$}")).isEmpty();
    }
}
