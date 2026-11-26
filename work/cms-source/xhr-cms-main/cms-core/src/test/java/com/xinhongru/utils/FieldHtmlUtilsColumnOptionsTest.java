package com.xinhongru.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.requestDto.fieldHtmlDto.FieldHtmlDto;
import com.xinhongru.service.content.ContentService;
import com.xinhongru.web.service.MenuService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class FieldHtmlUtilsColumnOptionsTest {
    private final MenuService menuService = mock(MenuService.class);

    @BeforeEach
    void setup() {
        FieldHtmlUtils fieldHtmlUtils = new FieldHtmlUtils();
        fieldHtmlUtils.setContentService(mock(ContentService.class));
        fieldHtmlUtils.setMenuService(menuService);
    }

    @Test
    void rendersChildColumnsAsOptions() {
        when(menuService.getOneshotListByParentId(109))
                .thenReturn(
                        List.of(
                                new OneshotEntity().setId(11).setClassName("新闻"),
                                new OneshotEntity().setId(12).setClassName("产品")));

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109_children$}");
        dto.setFieldValue("12");
        dto.setInputMultiple(false);
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html = FieldHtmlUtils.getFieldHtml(dto);

        assertThat(html).contains("<option value=\"11\">新闻</option>");
        assertThat(html).contains("<option value=\"12\" selected=\"selected\">产品</option>");
        assertThat(html).doesNotContain("multiple");
    }

    @Test
    void rendersMultipleWhenConfigured() {
        when(menuService.getOneshotListByParentId(109))
                .thenReturn(List.of(new OneshotEntity().setId(11).setClassName("新闻")));

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109_children$}");
        dto.setFieldValue(",11,");
        dto.setInputMultiple(true);
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html = FieldHtmlUtils.getFieldHtml(dto);

        assertThat(html).contains("type=\"hidden\"");
        assertThat(html).contains("value=\",11,\"");
        assertThat(html).contains("新闻");
    }

    @Test
    void rendersNotConfiguredWhenNoChildren() {
        when(menuService.getOneshotListByParentId(109)).thenReturn(List.of());

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109_children$}");
        dto.setFieldValue(null);
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html = FieldHtmlUtils.getFieldHtml(dto);

        assertThat(html).contains("尚未配置");
    }

    @Test
    void readonlyRendersTextInputWithColumnName() {
        when(menuService.getOneshotListByParentId(109))
                .thenReturn(List.of(new OneshotEntity().setId(12).setClassName("产品")));

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109_children$}");
        dto.setIsReadOnly("on");
        dto.setFieldValue("12");
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html = FieldHtmlUtils.getFieldHtml(dto);

        assertThat(html).contains("type=\"text\"");
        assertThat(html).contains("产品");
        assertThat(html).doesNotContain("<select");
    }

    @Test
    void listDisplayMapsStoredIdToColumnName() {
        when(menuService.getOneshotListByIdsRaw(List.of(11)))
                .thenReturn(List.of(new OneshotEntity().setId(11).setClassName("新闻")));

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109_children$}");
        dto.setFieldValue("11");
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html =
                FieldHtmlUtils.getListFieldHtml(
                        dto, new ContentEntity().setIndexTop(false), 1, 109);

        assertThat(html).contains("新闻");
        assertThat(html).doesNotContain(">11<");
    }

    @Test
    void listDisplayMapsMultiValuesToColumnNames() {
        when(menuService.getOneshotListByIdsRaw(List.of(11, 12)))
                .thenReturn(
                        List.of(
                                new OneshotEntity().setId(11).setClassName("新闻"),
                                new OneshotEntity().setId(12).setClassName("产品")));

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109_children$}");
        dto.setFieldValue(",11,12,");
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html =
                FieldHtmlUtils.getListFieldHtml(
                        dto, new ContentEntity().setIndexTop(false), 1, 109);

        assertThat(html).contains("新闻,产品");
    }

    @Test
    void listDisplayFallsBackToRawIdWhenColumnMissing() {
        when(menuService.getOneshotListByIdsRaw(List.of(11))).thenReturn(List.of());

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109_children$}");
        dto.setFieldValue("11");
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html =
                FieldHtmlUtils.getListFieldHtml(
                        dto, new ContentEntity().setIndexTop(false), 1, 109);

        assertThat(html).contains("11");
    }

    @Test
    void listDisplayHandlesNonNumericSegmentWithoutError() {
        when(menuService.getOneshotListByIdsRaw(List.of())).thenReturn(List.of());

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109_children$}");
        dto.setFieldValue("abc");
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html =
                FieldHtmlUtils.getListFieldHtml(
                        dto, new ContentEntity().setIndexTop(false), 1, 109);

        assertThat(html).contains("abc");
    }

    @Test
    void rendersChildrenFromMultipleParents() {
        when(menuService.getOneshotListByParentId(109))
                .thenReturn(List.of(new OneshotEntity().setId(11).setClassName("新闻")));
        when(menuService.getOneshotListByParentId(110))
                .thenReturn(List.of(new OneshotEntity().setId(12).setClassName("产品")));

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109,110_children$}");
        dto.setFieldValue("11");
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html = FieldHtmlUtils.getFieldHtml(dto);

        assertThat(html).contains("<option value=\"11\" selected=\"selected\">新闻</option>");
        assertThat(html).contains("<option value=\"12\">产品</option>");
    }

    @Test
    void dedupsOverlappingChildrenAcrossParents() {
        when(menuService.getOneshotListByParentId(109))
                .thenReturn(List.of(new OneshotEntity().setId(11).setClassName("新闻")));
        when(menuService.getOneshotListByParentId(110))
                .thenReturn(
                        List.of(
                                new OneshotEntity().setId(11).setClassName("新闻"),
                                new OneshotEntity().setId(12).setClassName("产品")));

        FieldHtmlDto dto = new FieldHtmlDto();
        dto.setFieldName("singleLine2");
        dto.setFieldType("SingleLine");
        dto.setTitle("所属栏目");
        dto.setDefaultValue("{$109,110_children$}");
        dto.setFieldValue(null);
        dto.setIsSuperAdmin(false);
        dto.setMaxLength(0);

        String html = FieldHtmlUtils.getFieldHtml(dto);

        assertThat(html).containsOnlyOnce("<option value=\"11\">新闻</option>");
        assertThat(html).contains("<option value=\"12\">产品</option>");
    }
}
