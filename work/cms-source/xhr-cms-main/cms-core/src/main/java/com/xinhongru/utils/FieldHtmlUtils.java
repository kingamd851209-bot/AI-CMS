package com.xinhongru.utils;

import com.xinhongru.constant.WebConst;
import com.xinhongru.content.model.ContentFieldDefinition;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.requestDto.fieldHtmlDto.FieldHtmlDto;
import com.xinhongru.service.content.ContentService;
import com.xinhongru.web.select.SelectQuerySyntax;
import com.xinhongru.web.service.MenuService;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @Description @ClassName FieldHtmlUtils @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/30
 * 13:23
 */
@Slf4j
@Component
public class FieldHtmlUtils {
    private static ContentService contentService;
    private static MenuService menuService;

    @Autowired
    public void setContentService(ContentService contentService) {
        FieldHtmlUtils.contentService = contentService;
    }

    @Autowired
    public void setMenuService(MenuService menuService) {
        FieldHtmlUtils.menuService = menuService;
    }

    /** 只读字符串HTML代码 */
    public static final String READONLY_STRING = "readonly=\"readonly\"";

    /**
     * 添加修改页面的按照字段类型渲染html代码
     *
     * @param fieldHtmlDto
     * @return
     */
    public static String getFieldHtml(FieldHtmlDto fieldHtmlDto) {
        switch (fieldHtmlDto.getFieldType()) {
            case "SingleLine":
                return getSingleLineHtml(fieldHtmlDto);
            case "MultiLine":
                return getMultiLineHtml(fieldHtmlDto);
            case "Image":
                return getImageHtml(fieldHtmlDto);
            case "Video":
                return getVideoHtml(fieldHtmlDto);
            case "File":
                return getFileHtml(fieldHtmlDto);
            case "Editor":
                return getEditorHtml(fieldHtmlDto);
            case "DateTime":
                return getDateTimeHtml(fieldHtmlDto);
            default:
                return "";
        }
    }

    /**
     * 添加修改页面的按照字段类型渲染js代码
     *
     * @param fieldHtmlDto
     * @return
     */
    public static String getFieldScript(FieldHtmlDto fieldHtmlDto) {
        switch (fieldHtmlDto.getFieldType()) {
            case "SingleLine":
                return getSingleLineScript(fieldHtmlDto);
            case "Image":
                return getImageScript(fieldHtmlDto);
            case "Video":
                return getVideoScript(fieldHtmlDto);
            case "File":
                return getFileScript(fieldHtmlDto);
            case "Editor":
                return getEditorScript(fieldHtmlDto);
            case "DateTime":
                return getDateTimeScript(fieldHtmlDto);
            default:
                return "";
        }
    }

    private static String getSingleLineScript(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        if (StringUtils.isNotBlank(fieldHtmlDto.getDefaultValue())
                && fieldHtmlDto.getDefaultValue().startsWith(WebConst.SELECT_ELEMENT_STARTS_WITH)
                && fieldHtmlDto.getDefaultValue().endsWith(WebConst.SELECT_ELEMENT_ENDS_WITH)) {
            stringBuilder.append(
                    "<script>$(\".select"
                            + fieldHtmlDto.getFieldName()
                            + "\").select2(); $(\".select"
                            + fieldHtmlDto.getFieldName()
                            + "\").val(["
                            + fieldHtmlDto.getFieldValue()
                            + "]).trigger('change');</script>\n");
        }
        return stringBuilder.toString();
    }

    /**
     * 列表页面的按照字段类型渲染表头代码
     *
     * @param fieldSettingEntity
     * @return
     */
    public static String getListTitleHtml(ContentFieldDefinition fieldSettingEntity) {
        switch (fieldSettingEntity.getFieldType()) {
            case "SingleLine":
            case "DateTime":
            case "MultiLine":
                return getListTitleSingleLineHtml(fieldSettingEntity);
            case "Image":
                return getListTitleImageHtml(fieldSettingEntity);
            case "Video":
                return getListTitleVideoHtml(fieldSettingEntity);
            case "File":
                return getListTitleFileHtml(fieldSettingEntity);
            default:
                return "";
        }
    }

    /**
     * 列表页面的按照字段类型渲染表格行代码
     *
     * @param fieldHtmlDto
     * @param contentEntity
     * @param i
     * @return
     */
    public static String getListFieldHtml(
            FieldHtmlDto fieldHtmlDto, ContentEntity contentEntity, int i, Integer classId) {
        switch (fieldHtmlDto.getFieldType()) {
            case "SingleLine":
                return getListSingleLineHtml(fieldHtmlDto, contentEntity, i, classId);
            case "MultiLine":
                return getListMultiLineHtml(fieldHtmlDto, contentEntity, i, classId);
            case "Image":
                return getListImageHtml(fieldHtmlDto);
            case "Video":
                return getListVideoHtml(fieldHtmlDto);
            case "File":
                return getListFileHtml(fieldHtmlDto);
            case "DateTime":
                return getListDateTimeHtml(fieldHtmlDto);
            default:
                return "";
        }
    }

    /**
     * 列表页面的按照字段类型渲染搜索html代码
     *
     * @param fieldSettingEntity
     * @param searchMap
     * @return
     */
    public static String getListSearchHtml(
            ContentFieldDefinition fieldSettingEntity, Map searchMap, Integer classId) {
        switch (fieldSettingEntity.getFieldType()) {
            case "SingleLine":
            case "Editor":
            case "MultiLine":
                return getListSearchSingleLineHtml(fieldSettingEntity, searchMap, classId);
            case "DateTime":
                return getListSearchDateTimeHtml(fieldSettingEntity, searchMap);
            default:
                return "";
        }
    }

    /**
     * 列表页面的按照字段类型渲染搜索js代码
     *
     * @param fieldSettingEntity
     * @param searchMap
     * @return
     */
    public static String getListSearchJSHtml(
            ContentFieldDefinition fieldSettingEntity, Map searchMap) {
        switch (fieldSettingEntity.getFieldType()) {
            case "SingleLine":
                return getListSearchSingleLineJSHtml(fieldSettingEntity, searchMap);
            case "DateTime":
                return getListSearchDateTimeJSHtml(fieldSettingEntity);
            default:
                return "";
        }
    }

    private static String getListSearchSingleLineJSHtml(
            ContentFieldDefinition fieldSettingEntity, Map searchMap) {
        if (StringUtils.isNotBlank(fieldSettingEntity.getDefaultValue())
                && fieldSettingEntity
                        .getDefaultValue()
                        .startsWith(WebConst.SELECT_ELEMENT_STARTS_WITH)
                && fieldSettingEntity
                        .getDefaultValue()
                        .endsWith(WebConst.SELECT_ELEMENT_ENDS_WITH)) {
            if (!searchMap.isEmpty() && searchMap.containsKey(fieldSettingEntity.getFieldName())) {
                Object id = searchMap.get(fieldSettingEntity.getFieldName());
                if (id != null) {
                    FieldHtmlDto fieldHtmlDto = new FieldHtmlDto();
                    BeanUtils.copyProperties(fieldSettingEntity, fieldHtmlDto);
                    fieldHtmlDto.setFieldValue(String.valueOf(id));
                    return getSingleLineScript(fieldHtmlDto);
                }
            }
        }
        return "";
    }

    private static String getListSearchDateTimeJSHtml(ContentFieldDefinition fieldSettingEntity) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<script>datetimePickerInit('startTime_"
                        + fieldSettingEntity.getFieldName()
                        + "')</script>\n");
        stringBuilder.append(
                "<script>datetimePickerInit('endTime_"
                        + fieldSettingEntity.getFieldName()
                        + "')</script>\n");
        return stringBuilder.toString();
    }

    private static String getListSearchDateTimeHtml(
            ContentFieldDefinition fieldSettingEntity, Map searchMap) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<div class=\"input-group\">\n"
                        + "                 <span class=\"input-group-addon\">"
                        + fieldSettingEntity.getTitle()
                        + "</span>\n"
                        + "                 <input value=\""
                        + (!searchMap.isEmpty()
                                        && searchMap.containsKey(
                                                "startTime_" + fieldSettingEntity.getFieldName())
                                        && searchMap.get(
                                                        "startTime_"
                                                                + fieldSettingEntity.getFieldName())
                                                != null
                                ? searchMap.get("startTime_" + fieldSettingEntity.getFieldName())
                                : "")
                        + "\" id=\"startTime_"
                        + fieldSettingEntity.getFieldName()
                        + "\" name=\"startTime_"
                        + fieldSettingEntity.getFieldName()
                        + "\" type=\"text\" class=\"form-control get\" placeholder=\"请选择起始时间\""
                        + " style=\"width: 140px;\">\n"
                        + "                 <span class=\"input-group-addon\"> 至 </span>\n"
                        + "                 <input value=\""
                        + (!searchMap.isEmpty()
                                        && searchMap.containsKey(
                                                "endTime_" + fieldSettingEntity.getFieldName())
                                        && searchMap.get(
                                                        "endTime_"
                                                                + fieldSettingEntity.getFieldName())
                                                != null
                                ? searchMap.get("endTime_" + fieldSettingEntity.getFieldName())
                                : "")
                        + "\" id=\"endTime_"
                        + fieldSettingEntity.getFieldName()
                        + "\" name=\"endTime_"
                        + fieldSettingEntity.getFieldName()
                        + "\" type=\"text\" class=\"form-control get\" placeholder=\"请选择结束时间\""
                        + " style=\"width: 140px;\">\n"
                        + "             </div>");
        return stringBuilder.toString();
    }

    private static String getListSearchSingleLineHtml(
            ContentFieldDefinition fieldSettingEntity, Map searchMap, Integer classId) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<div class=\"input-group\">\n"
                        + "                 <span class=\"input-group-addon\">"
                        + fieldSettingEntity.getTitle()
                        + "</span>\n");
        // 如果默认值是以{$开头且必须以$}结尾则当前字段使用下拉类型来输出
        String defaultValue = fieldSettingEntity.getDefaultValue();
        if (defaultValue.startsWith(WebConst.SELECT_ELEMENT_STARTS_WITH)
                && defaultValue.endsWith(WebConst.SELECT_ELEMENT_ENDS_WITH)) {
            //            String ClassIDString = getClassIDString(defaultValue, classId);
            List<ContentEntity> articleByClassIdForSelect =
                    contentService.getArticleByClassIdForSelect(Collections.singletonList(classId));
            int length = 15;
            if (CollectionUtils.isNotEmpty(articleByClassIdForSelect)) {
                length =
                        articleByClassIdForSelect.stream()
                                .map(item -> item.getTitle())
                                .sorted(Comparator.comparingInt(String::length).reversed())
                                .findFirst()
                                .get()
                                .getBytes(StandardCharsets.UTF_8)
                                .length;
            }
            stringBuilder.append(
                    "<span style=\"width:" + ((length * 5.5) + 27) + "px;display:block;\">");

            getSelectHtml(fieldSettingEntity, stringBuilder, articleByClassIdForSelect, null);

            stringBuilder.append("</span>");
        } else {
            stringBuilder.append(
                    "                 <input type=\"text\" value=\""
                            + (!searchMap.isEmpty()
                                            && searchMap.containsKey(
                                                    fieldSettingEntity.getFieldName())
                                            && searchMap.get(fieldSettingEntity.getFieldName())
                                                    != null
                                    ? StringEscapeUtils.escapeHtml4(
                                            String.valueOf(
                                                    searchMap.get(
                                                            fieldSettingEntity.getFieldName())))
                                    : "")
                            + "\" name=\""
                            + fieldSettingEntity.getFieldName()
                            + "\" class=\"form-control get\" placeholder=\"请输入关键词\" style=\"width:"
                            + " 120px;\">\n");
        }
        stringBuilder.append("             </div>");
        return stringBuilder.toString();
    }

    private static String getListTitleFileHtml(ContentFieldDefinition fieldSettingEntity) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<th style=\"width: 90px;text-align:center;\">"
                        + fieldSettingEntity.getTitle()
                        + "</th>");
        return stringBuilder.toString();
    }

    private static String getListTitleVideoHtml(ContentFieldDefinition fieldSettingEntity) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<th style=\"width: 90px;text-align:center;\">"
                        + fieldSettingEntity.getTitle()
                        + "</th>");
        return stringBuilder.toString();
    }

    private static String getListTitleImageHtml(ContentFieldDefinition fieldSettingEntity) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<th style=\"width: 120px;text-align:center;\">"
                        + fieldSettingEntity.getTitle()
                        + "</th>");
        return stringBuilder.toString();
    }

    private static String getListTitleSingleLineHtml(ContentFieldDefinition fieldSettingEntity) {
        StringBuilder stringBuilder = new StringBuilder();
        if (fieldSettingEntity.getFieldName().equals("title")) {
            stringBuilder.append(
                    "<th style=\"min-width:90px;\">" + fieldSettingEntity.getTitle() + "</th>");
        } else if (fieldSettingEntity.getFieldName().equals("createTime")) {
            stringBuilder.append(
                    "<th style=\"width: 140px;text-align:center;\">"
                            + fieldSettingEntity.getTitle()
                            + "</th>");
        } else {
            stringBuilder.append(
                    "<th style=\"text-align:center;min-width:90px;\">"
                            + fieldSettingEntity.getTitle()
                            + "</th>");
        }
        return stringBuilder.toString();
    }

    private static String getListDateTimeHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<td style=\"text-align:center;vertical-align: middle;\">"
                        + fieldHtmlDto.getFieldValue()
                        + "</td>");
        return stringBuilder.toString();
    }

    private static String getListFileHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        File file = new File(fieldHtmlDto.getFieldValue());
        String fileName = file.getName();
        if (StringUtils.isNotBlank(fileName)) {
            stringBuilder.append(
                    "<td style=\"text-align:center;vertical-align: middle;\"><a style=\"cursor:"
                            + " pointer;\"\n"
                            + "                                      "
                            + " onclick=\"window.parent.showFile('"
                            + fieldHtmlDto.getFieldValue()
                            + "');\"\n"
                            + "                                      "
                            + " title=\"点击查看文件\">下载附件</a></td>");
        } else {
            stringBuilder.append(
                    "<td style=\"text-align:center;vertical-align: middle;\">暂无附件</td>");
        }
        return stringBuilder.toString();
    }

    private static String getListVideoHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        if (StringUtils.isNotBlank(fieldHtmlDto.getFieldValue())) {
            stringBuilder.append(
                    "<td style=\"text-align:center;vertical-align: middle;\"><a style=\"cursor:"
                            + " pointer;\"\n"
                            + "                                      "
                            + " onclick=\"window.parent.showVideo('"
                            + fieldHtmlDto.getFieldValue()
                            + "');\"\n"
                            + "                                      "
                            + " title=\"点击查看视频\">查看视频</a></td>");
        } else {
            stringBuilder.append(
                    "<td style=\"text-align:center;vertical-align: middle;\">暂无视频</td>");
        }
        return stringBuilder.toString();
    }

    private static String getListImageHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        if (StringUtils.isNotBlank(fieldHtmlDto.getFieldValue())) {
            stringBuilder.append("<td style=\"vertical-align: middle;text-align: center;\">\n");
            stringBuilder.append("<img ");
            stringBuilder.append(
                    "  onclick=\"window.parent.showImage('"
                            + fieldHtmlDto.getFieldValue()
                            + "');\" title=\"点击查看原图\"");
            stringBuilder.append(
                    "  src=\""
                            + fieldHtmlDto.getFieldValue()
                            + "\" onerror=\"this.onerror=null;this.replaceWith(document.createTextNode('图片加载失败'));\""
                            + " style=\"cursor:pointer;height:50px\">\n");
            stringBuilder.append("</td>");
        } else {
            stringBuilder.append(
                    "<td style=\"text-align:center;vertical-align: middle;\">暂无图片</td>");
        }
        return stringBuilder.toString();
    }

    private static String getListMultiLineHtml(
            FieldHtmlDto fieldHtmlDto, ContentEntity contentEntity, int i, Integer classId) {
        String recommend = getRecommend(contentEntity, i);
        StringBuilder stringBuilder = new StringBuilder();
        if ("note1".equals(fieldHtmlDto.getFieldName())
                && (classId == 209 || classId == 210 || classId == 211)) {
            // 简介1字段
            stringBuilder.append(
                    "<td style=\""
                            + (i == 0 ? "" : "text-align:center;")
                            + "vertical-align: middle;\">"
                            + fieldHtmlDto.getFieldValue()
                            + "</span>"
                            + recommend
                            + "</td>");
        } else {
            stringBuilder.append(
                    "<td style=\""
                            + (i == 0 ? "" : "text-align:center;")
                            + "vertical-align: middle;\">"
                            + fieldHtmlDto.getFieldValue()
                            + "</td>");
        }
        return stringBuilder.toString();
    }

    private static String getListSingleLineHtml(
            FieldHtmlDto fieldHtmlDto, ContentEntity contentEntity, int i, Integer classId) {
        String recommend = getRecommend(contentEntity, i);
        StringBuilder stringBuilder = new StringBuilder();

        if ("title".equals(fieldHtmlDto.getFieldName())) {
            // 标题字段
            stringBuilder.append(
                    "<td style=\"vertical-align: middle;\"><span class=\"info-title-span\" title=\""
                            + fieldHtmlDto.getTitle()
                            + "\">"
                            + fieldHtmlDto.getFieldValue()
                            + "</span>"
                            + recommend
                            + "</td>");
        } else if (StringUtils.isNotBlank(fieldHtmlDto.getIsWebUrl())) {
            // 网址类型默认为链接类型 并设置新窗口打开
            stringBuilder.append(
                    "<td style=\"text-align:center;vertical-align: middle;\"><span title=\""
                            + fieldHtmlDto.getTitle()
                            + "\"><a href=\""
                            + fieldHtmlDto.getFieldValue()
                            + "\" target=\"_blank\">"
                            + fieldHtmlDto.getFieldValue()
                            + "</span>"
                            + recommend
                            + "</td>");
        } else {
            // 普通字段
            String fieldValue = fieldHtmlDto.getFieldValue();
            // 如果默认值是以{$开头且必须以$}结尾则当前字段使用下拉类型来输出
            String defaultValue = fieldHtmlDto.getDefaultValue();
            // 下拉选项类型
            if (defaultValue.startsWith(WebConst.SELECT_ELEMENT_STARTS_WITH)
                    && defaultValue.endsWith(WebConst.SELECT_ELEMENT_ENDS_WITH)
                    && StringUtils.isNotBlank(fieldValue)) {
                List<Integer> columnParentIds =
                        SelectQuerySyntax.parseChildrenParentIds(defaultValue);
                if (!columnParentIds.isEmpty()) {
                    List<String> rawSegments = new ArrayList<>();
                    List<Integer> ids = new ArrayList<>();
                    for (String part : fieldValue.split(WebConst.COMMA)) {
                        String trimmed = part.trim();
                        if (trimmed.isEmpty()) {
                            continue;
                        }
                        rawSegments.add(trimmed);
                        try {
                            ids.add(Integer.valueOf(trimmed));
                        } catch (NumberFormatException ignored) {
                            ids.add(null);
                        }
                    }
                    List<Integer> queryIds = new ArrayList<>();
                    for (Integer id : ids) {
                        if (id != null && !queryIds.contains(id)) {
                            queryIds.add(id);
                        }
                    }
                    Map<Integer, String> nameById = new HashMap<>();
                    for (OneshotEntity column : menuService.getOneshotListByIdsRaw(queryIds)) {
                        if (column.getId() != null && column.getClassName() != null) {
                            nameById.put(column.getId(), column.getClassName());
                        }
                    }
                    List<String> names = new ArrayList<>();
                    for (int k = 0; k < rawSegments.size(); k++) {
                        Integer id = ids.get(k);
                        String name = id != null ? nameById.get(id) : null;
                        names.add(name != null ? name : rawSegments.get(k));
                    }
                    fieldValue = String.join(WebConst.COMMA, names);
                } else {
                    String ClassIDString = getClassIDString(defaultValue, classId);
                    List<ContentEntity> contentEntityList;
                    List<Integer> idList =
                            Arrays.stream(
                                            Arrays.stream(fieldValue.split(WebConst.COMMA))
                                                    .mapToInt(Integer::parseInt)
                                                    .toArray())
                                    .boxed()
                                    .collect(Collectors.toList());
                    contentEntityList =
                            contentService.getArticleByClassIdForSelect(
                                    Integer.valueOf(ClassIDString), idList);
                    if (CollectionUtils.isNotEmpty(contentEntityList)) {
                        fieldValue =
                                contentEntityList.stream()
                                        .map(item -> item.getTitle())
                                        .collect(Collectors.joining(WebConst.COMMA));
                    }
                }
            }
            stringBuilder.append(
                    "<td style=\"text-align:center;vertical-align: middle;\"><span title=\""
                            + fieldHtmlDto.getTitle()
                            + "\">"
                            + fieldValue
                            + "</span>"
                            + recommend
                            + "</td>");
        }
        return stringBuilder.toString();
    }

    @NotNull
    private static String getRecommend(ContentEntity contentEntity, int i) {
        String recommend = "";
        if (i == 0) {
            if (contentEntity.getIndexTop()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">首页置顶</span>";
            }
            if (contentEntity.getIndexElite()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">首页推荐</span>";
            }
            if (contentEntity.getIndexHot()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">首页热门</span>";
            }
            if (contentEntity.getChannelTop()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">频道置顶</span>";
            }
            if (contentEntity.getChannelElite()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">频道推荐</span>";
            }
            if (contentEntity.getChannelHot()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">频道热门</span>";
            }
            if (contentEntity.getMenuTop()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">栏目置顶</span>";
            }
            if (contentEntity.getMenuElite()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">栏目推荐</span>";
            }
            if (contentEntity.getMenuHot()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">栏目热门</span>";
            }
            if (contentEntity.getNavElite()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">导航推荐</span>";
            }
            if (contentEntity.getRightElite()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">右侧推荐</span>";
            }
            if (contentEntity.getLeftElite()) {
                recommend +=
                        "<span class=\"label label-success\" style=\"margin-left:"
                                + " 2px;\">左侧推荐</span>";
            }
        }
        return recommend;
    }

    /**
     * 根据字段设置获取classId字段
     *
     * @param defaultValue
     * @return
     */
    private static String getClassIDString(String defaultValue, Integer classId) {
        String ClassIDString;
        // 动态获取子栏目id 20200313
        /*if (defaultValue.replace(WebConst.SELECT_ELEMENT_STARTS_WITH, "").replace(WebConst.SELECT_ELEMENT_ENDS_WITH, "").contains("childid_")) {
            //defaultValue = getChildDefaultValue(defaultValue, MenuId);
        }*/
        // 自定义健和值字段
        // {$6_KeyField_ValueField_8$}
        String[] defaultValueArray =
                defaultValue
                        .replace(WebConst.SELECT_ELEMENT_STARTS_WITH, "")
                        .replace(WebConst.SELECT_ELEMENT_ENDS_WITH, "")
                        .split("_");
        if (defaultValueArray.length > 3) {
            ClassIDString = defaultValueArray[0];
        }
        // {$6_KeyField_ValueField$}
        else if (defaultValueArray.length > 2) {
            ClassIDString = defaultValueArray[0];
        }
        // 根据唯一值动态获取当前栏目的子栏目
        // {$child_HjQs1ZcVaF9K27bh$}
        else if (defaultValueArray.length > 1 && defaultValueArray[0].equals("child")) {
            OneshotEntity oneshotEntityByParentIdAndIdentifies =
                    menuService.getOneshotEntityByParentIdAndIdentifies(
                            classId, defaultValueArray[1]);
            ClassIDString = String.valueOf(oneshotEntityByParentIdAndIdentifies.getId());
        }
        // 根据父ID和唯一值动态获取子栏目
        // {$101_child_HjQs1ZcVaF9K27bh$}
        else if (defaultValueArray.length > 1 && defaultValueArray[1].equals("child")) {
            OneshotEntity oneshotEntityByParentIdAndIdentifies =
                    menuService.getOneshotEntityByParentIdAndIdentifies(
                            Integer.valueOf(defaultValueArray[0]), defaultValueArray[2]);
            ClassIDString = String.valueOf(oneshotEntityByParentIdAndIdentifies.getId());
        }
        // 自定义健字段
        // {$6_Title1$}
        else if (defaultValueArray.length > 1) {
            ClassIDString = defaultValueArray[0];
        } else {
            ClassIDString =
                    defaultValue
                            .replace(WebConst.SELECT_ELEMENT_STARTS_WITH, "")
                            .replace(WebConst.SELECT_ELEMENT_ENDS_WITH, "");
        }
        return ClassIDString;
    }

    private static String getDateTimeScript(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<script>datetimePickerInit('" + fieldHtmlDto.getFieldName() + "')</script>\n");
        return stringBuilder.toString();
    }

    private static String getEditorScript(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<!-- 实例化编辑器 -->\n"
                        + "<script type=\"text/javascript\">UE.getEditor('"
                        + fieldHtmlDto.getFieldName()
                        + "');</script>\n");
        return stringBuilder.toString();
    }

    private static String getFileScript(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<!-- 实例化文件上传 -->\n"
                        + " <script>\n"
                        + "     let "
                        + fieldHtmlDto.getFieldName()
                        + "_File=new UE.ui.Editor();\n"
                        + "     function uploadFile_"
                        + fieldHtmlDto.getFieldName()
                        + "() {\n"
                        + "        let fileDialog_"
                        + fieldHtmlDto.getFieldName()
                        + " = "
                        + fieldHtmlDto.getFieldName()
                        + "_File.getDialog(\"attachment\");\n"
                        + "         fileDialog_"
                        + fieldHtmlDto.getFieldName()
                        + ".render();\n"
                        + "         fileDialog_"
                        + fieldHtmlDto.getFieldName()
                        + ".open();\n"
                        + "     }\n"
                        + "     "
                        + fieldHtmlDto.getFieldName()
                        + "_File.render('"
                        + fieldHtmlDto.getFieldName()
                        + "_File');\n"
                        + "     "
                        + fieldHtmlDto.getFieldName()
                        + "_File.ready(function () {\n"
                        + "         "
                        + fieldHtmlDto.getFieldName()
                        + "_File.setDisabled();\n"
                        + "         "
                        + fieldHtmlDto.getFieldName()
                        + "_File.hide();//隐藏UE框体\n"
                        + "         "
                        + fieldHtmlDto.getFieldName()
                        + "_File.addListener('afterUpfile', function (t, arg) {\n"
                        + "             $(\"#"
                        + fieldHtmlDto.getFieldName()
                        + "\").val(arg[0].url);\n"
                        + "         });\n"
                        + "     });\n"
                        + " </script>\n");
        return stringBuilder.toString();
    }

    private static String getVideoScript(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<!-- 实例化视频上传 -->\n"
                        + "<script>\n"
                        + "    let "
                        + fieldHtmlDto.getFieldName()
                        + "_video;\n"
                        + "    let videoDialog_"
                        + fieldHtmlDto.getFieldName()
                        + ";\n"
                        + "    "
                        + fieldHtmlDto.getFieldName()
                        + "_video = new UE.ui.Editor();\n"
                        + "    function uploadVideo_"
                        + fieldHtmlDto.getFieldName()
                        + "() {\n"
                        + "        videoDialog_"
                        + fieldHtmlDto.getFieldName()
                        + " = "
                        + fieldHtmlDto.getFieldName()
                        + "_video.getDialog(\"insertvideo\");\n"
                        + "        videoDialog_"
                        + fieldHtmlDto.getFieldName()
                        + ".render();\n"
                        + "        videoDialog_"
                        + fieldHtmlDto.getFieldName()
                        + ".open();\n"
                        + "    }\n"
                        + "    "
                        + fieldHtmlDto.getFieldName()
                        + "_video.render('"
                        + fieldHtmlDto.getFieldName()
                        + "_video');\n"
                        + "    "
                        + fieldHtmlDto.getFieldName()
                        + "_video.ready(function () {\n"
                        + "        "
                        + fieldHtmlDto.getFieldName()
                        + "_video.setDisabled();\n"
                        + "        "
                        + fieldHtmlDto.getFieldName()
                        + "_video.hide();//隐藏UE框体\n"
                        + "        "
                        + fieldHtmlDto.getFieldName()
                        + "_video.addListener('afterUpVideo', function (t, arg) {\n"
                        + "          if(Array.isArray(arg)){"
                        + "               $(\"#"
                        + fieldHtmlDto.getFieldName()
                        + "\").val(arg[0].url);"
                        + "           }else{"
                        + "               $(\"#"
                        + fieldHtmlDto.getFieldName()
                        + "\").val(arg.url);"
                        + "           }\n"
                        + "        });\n"
                        + "    });\n"
                        + "</script>\n");
        return stringBuilder.toString();
    }

    /** 图片裁切正则表达式 */
    private static final Pattern patternCutImage =
            Pattern.compile(
                    "\\(((\\d|\\D)+)([^(\\d|\\D)]*?)\\)\\*\\(((\\d|\\D)+)([^(\\d|\\D)]*?)\\)");

    private static String getImageScript(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();

        // 图片裁切功能需要的代码
        int minWidth = 0;
        int minHeight = 0;

        Matcher matcher = patternCutImage.matcher(fieldHtmlDto.getBak().replace("px", ""));
        while (matcher.find()) {
            try {
                minWidth = Integer.valueOf(matcher.group(1));
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
            try {
                minHeight = Integer.valueOf(matcher.group(4));
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
        double scaleValue = 0.0D;
        if (minWidth > 0 && minHeight > 0) {
            scaleValue = StringDeal.formatDouble((minWidth * 1.0) / (minHeight * 1.0));
        }
        stringBuilder.append(
                "<!-- 实例化["
                        + fieldHtmlDto.getFieldName()
                        + "]图片上传 -->\n"
                        + "<script>\n"
                        + "    let "
                        + fieldHtmlDto.getFieldName()
                        + "_Image=new UE.ui.Editor();\n"
                        + "    function uploadImage_"
                        + fieldHtmlDto.getFieldName()
                        + "() {\n"
                        + "        let imageDialog_"
                        + fieldHtmlDto.getFieldName()
                        + " = "
                        + fieldHtmlDto.getFieldName()
                        + "_Image.getDialog(\"insertimage\");\n"
                        + "        imageDialog_"
                        + fieldHtmlDto.getFieldName()
                        + ".render();\n"
                        + "        imageDialog_"
                        + fieldHtmlDto.getFieldName()
                        + ".open();\n"
                        + "    }\n"
                        + "    "
                        + fieldHtmlDto.getFieldName()
                        + "_Image.render('"
                        + fieldHtmlDto.getFieldName()
                        + "_Image');\n"
                        + "    "
                        + fieldHtmlDto.getFieldName()
                        + "_Image.ready(function () {\n"
                        + "        "
                        + fieldHtmlDto.getFieldName()
                        + "_Image.setDisabled();\n"
                        + "        "
                        + fieldHtmlDto.getFieldName()
                        + "_Image.hide();//隐藏UE框体\n"
                        + "        "
                        + fieldHtmlDto.getFieldName()
                        + "_Image.addListener('beforeInsertImage', function (t, arg) {\n");
        //        stringBuilder.append("            let url=arg[0].src;\n");
        //        stringBuilder.append("                           $(\"#" +
        // fieldHtmlDto.getFieldName() + "\").val(url);\n");
        stringBuilder.append("            let url=arg[0].src;\n");
        if (minWidth > 0 && minHeight > 0) {
            stringBuilder.append(
                    "            if (url.indexOf('http://') == -1 && url.indexOf('https://') == -1)"
                            + " {\n"
                            + "               let img_url = url + '?' + Date.parse(new Date());\n"
                            + "               let img = new Image();\n"
                            + "               img.src = img_url;\n"
                            + "               let thisHeight = 0;\n"
                            + "               let thisWidth = 0;\n"
                            + "               img.onload = function () {\n"
                            + "                   thisHeight = img.height;\n"
                            + "                   thisWidth = img.width;\n"
                            + "                   if (thisHeight >= "
                            + minHeight
                            + " && thisWidth >= "
                            + minWidth
                            + ") {\n"
                            + "                       if ('"
                            + scaleValue
                            + "' != '' + ((thisWidth / thisHeight).toFixed(1)) + '') {\n"
                            + "                           window.parent.cutImage('"
                            + fieldHtmlDto.getFieldName()
                            + "', url, '"
                            + minWidth
                            + "', '"
                            + minHeight
                            + "');\n"
                            + "                       } else {\n");
            stringBuilder.append(
                    "                           $(\"#"
                            + fieldHtmlDto.getFieldName()
                            + "\").val(url);\n");
            stringBuilder.append("                       }\n");
            stringBuilder.append("                   } else {\n");
            stringBuilder.append(
                    "                       $(\"#"
                            + fieldHtmlDto.getFieldName()
                            + "\").val(\"\");\n");
            stringBuilder.append(
                    "                      "
                            + " window.parent.layerAlert('您上传的图片的尺寸与建议尺寸不符，请上传符合尺寸的图片；<br/>建议尺寸为："
                            + minWidth
                            + "px*"
                            + minHeight
                            + "px;上传图片的实际尺寸为' + thisWidth + 'px*' + thisHeight + 'px。');\n"
                            + "                   }\n"
                            + "               }\n"
                            + "            }else{\n");
            stringBuilder.append(
                    "                $(\"#" + fieldHtmlDto.getFieldName() + "\").val(url);\n");
            stringBuilder.append("            }\n");
        } else {
            stringBuilder.append(
                    "            $(\"#" + fieldHtmlDto.getFieldName() + "\").val(url);\n");
        }

        stringBuilder.append("        });\n");
        stringBuilder.append("    });\n" + "</script>\n");
        return stringBuilder.toString();
    }

    /**
     * 日历控件
     *
     * @param fieldHtmlDto
     * @return
     */
    private static String getDateTimeHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "           <div class=\"col-md-2\">\n"
                        + "                <div class=\"form-group\">\n"
                        + "                    <label>"
                        + getRedAsterisk(fieldHtmlDto)
                        + fieldHtmlDto.getTitle()
                        + getCopyFieldNameString(fieldHtmlDto)
                        + getRedBak(fieldHtmlDto)
                        + "</label>\n"
                        + "                    <div class=\"input-group col-md-11\">\n"
                        + "                        <div class=\"input-group-addon\">\n"
                        + "                            <i class=\"fa fa-calendar\"></i>\n"
                        + "                        </div>\n"
                        + "                        <input id=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" name=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" type=\"text\"");
        getCustomSetting(fieldHtmlDto, stringBuilder);
        stringBuilder.append(
                " placeholder=\"请选择"
                        + fieldHtmlDto.getTitle()
                        + "\""
                        + " value=\""
                        + (!fieldHtmlDto.getFieldValue().equals("null")
                                ? fieldHtmlDto.getFieldValue()
                                : "")
                        + "\">\n"
                        + "                    </div>\n"
                        + "                </div>\n"
                        + "            </div>\n");
        return stringBuilder.toString();
    }

    /**
     * 编辑器控件
     *
     * @param fieldHtmlDto
     * @return
     */
    private static String getEditorHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "<div class=\"col-md-12\">\n"
                        + "                 <div class=\"form-group\">\n"
                        + "                     <label>"
                        + getRedAsterisk(fieldHtmlDto)
                        + fieldHtmlDto.getTitle()
                        + getCopyFieldNameString(fieldHtmlDto)
                        + getRedBak(fieldHtmlDto)
                        + "</label>\n"
                        + "                     <div class=\"input-group\" style=\"width:"
                        + " 95.8%\">\n"
                        + "                         <!-- 加载编辑器的容器 -->\n"
                        + "                         <script id=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" name=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" type=\"text/plain\" style=\"width:100%;height:360px;\">"
                        + (!fieldHtmlDto.getFieldValue().equals("null")
                                ? fieldHtmlDto.getFieldValue()
                                : "")
                        + "</script>\n"
                        + "                     </div>\n"
                        + "                 </div>\n"
                        + "             </div>\n");
        return stringBuilder.toString();
    }

    /**
     * 文件上传控件
     *
     * @param fieldHtmlDto
     * @return
     */
    private static String getFileHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "           <div class=\"col-md-6\">\n"
                        + "                 <div class=\"form-group\">\n"
                        + "                     <label>"
                        + getRedAsterisk(fieldHtmlDto)
                        + fieldHtmlDto.getTitle()
                        + getCopyFieldNameString(fieldHtmlDto)
                        + getRedBak(fieldHtmlDto)
                        + "</label>\n"
                        + "                     <div class=\"input-group col-sm-11\">\n"
                        + "                         <script id=\""
                        + fieldHtmlDto.getFieldName()
                        + "_File\" type=\"text/plain\" style=\"width:0px;height:0px;\">\n"
                        + "                         </script>\n"
                        + "                         <div class=\"input-group-addon\">\n"
                        + "                             <i class=\"fa fa-upload\"></i>\n"
                        + "                         </div>\n"
                        + "                         <input "
                        + (Boolean.TRUE.equals(fieldHtmlDto.getIsSuperAdmin())
                                ? ""
                                : READONLY_STRING)
                        + " id=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" name=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" type=\"text\"");
        getCustomSetting(fieldHtmlDto, stringBuilder);
        stringBuilder.append(
                " placeholder=\"请上传"
                        + fieldHtmlDto.getTitle()
                        + "\""
                        + " value=\""
                        + (!"null".equals(fieldHtmlDto.getFieldValue())
                                ? fieldHtmlDto.getFieldValue()
                                : "")
                        + "\">\n"
                        + "                         <div class=\"input-group-btn"
                        + " photo-btn-list\">\n"
                        + "                             <a href=\"javascript:void(0);\" class=\"btn"
                        + " btn-primary btn-flat\" onclick=\"uploadFile_"
                        + fieldHtmlDto.getFieldName()
                        + "()\">从本地上传</a>\n"
                        + "                             <a href=\"javascript:void(0);\" class=\"btn"
                        + " btn-primary showFile\">下载文件</a>\n"
                        + "                             <a href=\"javascript:void(0);\" class=\"btn"
                        + " btn-primary btn-flat delete-value\">清除文件</a>\n"
                        + "                         </div>\n"
                        + "                     </div>\n"
                        + "                 </div>\n"
                        + "             </div>\n");
        return stringBuilder.toString();
    }

    /**
     * 视频上传控件
     *
     * @param fieldHtmlDto
     * @return
     */
    private static String getVideoHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "           <div class=\"col-md-6\">\n"
                        + "                 <div class=\"form-group\">\n"
                        + "                     <label>"
                        + getRedAsterisk(fieldHtmlDto)
                        + fieldHtmlDto.getTitle()
                        + getCopyFieldNameString(fieldHtmlDto)
                        + getRedBak(fieldHtmlDto)
                        + "</label>\n"
                        + "                     <div class=\"input-group col-sm-11\">\n"
                        + "                         <script id=\""
                        + fieldHtmlDto.getFieldName()
                        + "_video\" type=\"text/plain\" style=\"width:0px;height:0px;\">\n"
                        + "                         </script>\n"
                        + "                         <div class=\"input-group-addon\">\n"
                        + "                             <i class=\"fa fa-video-camera\"></i>\n"
                        + "                         </div>\n"
                        + "                         <input "
                        + (Boolean.TRUE.equals(fieldHtmlDto.getIsSuperAdmin())
                                ? ""
                                : READONLY_STRING)
                        + " id=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" name=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" type=\"text\"");
        getCustomSetting(fieldHtmlDto, stringBuilder);
        stringBuilder.append(
                " placeholder=\"请上传"
                        + fieldHtmlDto.getTitle()
                        + "\""
                        + " value=\""
                        + (!fieldHtmlDto.getFieldValue().equals("null")
                                ? fieldHtmlDto.getFieldValue()
                                : "")
                        + "\">\n"
                        + "                         <div class=\"input-group-btn"
                        + " photo-btn-list\">\n"
                        + "                             <a href=\"javascript:void(0);\" class=\"btn"
                        + " btn-primary btn-flat\" onclick=\"uploadVideo_"
                        + fieldHtmlDto.getFieldName()
                        + "()\">从本地上传</a>\n"
                        + "                             <a href=\"javascript:void(0);\" class=\"btn"
                        + " btn-primary showVideo\">预览视频</a>\n"
                        + "                             <a href=\"javascript:void(0);\" class=\"btn"
                        + " btn-primary btn-flat delete-value\">清除视频</a>\n"
                        + "                             </div>\n"
                        + "                     </div>\n"
                        + "                 </div>\n"
                        + "             </div>\n");
        return stringBuilder.toString();
    }

    /**
     * 图片控件
     *
     * @param fieldHtmlDto
     * @return
     */
    private static String getImageHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "           <div class=\"col-md-6\">\n"
                        + "                <div class=\"form-group\">\n"
                        + "                    <label>"
                        + getRedAsterisk(fieldHtmlDto)
                        + fieldHtmlDto.getTitle()
                        + getCopyFieldNameString(fieldHtmlDto)
                        + getRedBak(fieldHtmlDto)
                        + "</label>\n"
                        + "                    <div class=\"input-group col-sm-11\">\n"
                        + "                        <script id=\""
                        + fieldHtmlDto.getFieldName()
                        + "_Image\" type=\"text/plain\" style=\"width:0px;height:0px;\">\n"
                        + "                        </script>\n"
                        + "                        <div class=\"input-group-addon\">\n"
                        + "                            <i class=\"fa fa-image\"></i>\n"
                        + "                        </div>\n"
                        + "                        <input "
                        + (Boolean.TRUE.equals(fieldHtmlDto.getIsSuperAdmin())
                                ? ""
                                : READONLY_STRING)
                        + " name=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" id=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" type=\"text\"");
        getCustomSetting(fieldHtmlDto, stringBuilder);
        stringBuilder.append(
                " placeholder=\"请上传"
                        + fieldHtmlDto.getTitle()
                        + "\""
                        + " value=\""
                        + (!fieldHtmlDto.getFieldValue().equals("null")
                                ? fieldHtmlDto.getFieldValue()
                                : "")
                        + "\">\n"
                        + "                        <div class=\"input-group-btn photo-btn-list\">\n"
                        + "                            <a href=\"javascript:void(0);\" class=\"btn"
                        + " btn-primary btn-flat\" onclick=\"uploadImage_"
                        + fieldHtmlDto.getFieldName()
                        + "()\">从本地上传</a>\n"
                        + "                            <a href=\"javascript:void(0);\" class=\"btn"
                        + " btn-primary showImage\">预览图片</a>\n"
                        + "                            <a href=\"javascript:void(0);\" class=\"btn"
                        + " btn-primary btn-flat delete-value\">清除图片</a>\n"
                        + "                        </div>\n"
                        + "                    </div>\n"
                        + "                </div>\n"
                        + "            </div>\n");
        return stringBuilder.toString();
    }

    /**
     * 多行控件
     *
     * @param fieldHtmlDto
     * @return
     */
    private static String getMultiLineHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "           <div class=\"col-md-12\">\n"
                        + "                <div class=\"form-group\">\n"
                        + "                    <label>"
                        + getRedAsterisk(fieldHtmlDto)
                        + fieldHtmlDto.getTitle()
                        + getCopyFieldNameString(fieldHtmlDto)
                        + getRedBak(fieldHtmlDto)
                        + "</label>\n"
                        + "                    <div class=\"input-group\" style=\"width: 95.8%\">\n"
                        + "                        <div class=\"input-group-addon\">\n"
                        + "                            <i class=\"fa fa-text-width\"></i>\n"
                        + "                        </div>\n"
                        + "                        <textarea name=\""
                        + fieldHtmlDto.getFieldName()
                        + "\"");
        getCustomSetting(fieldHtmlDto, stringBuilder);
        stringBuilder.append(
                " placeholder=\"请输入"
                        + fieldHtmlDto.getTitle()
                        + "\""
                        + " rows=\"4\">"
                        + (!fieldHtmlDto.getFieldValue().equals("null")
                                ? fieldHtmlDto.getFieldValue()
                                : "")
                        + "</textarea>\n"
                        + "                    </div>\n"
                        + "                </div>\n"
                        + "            </div>\n");
        return stringBuilder.toString();
    }

    /**
     * 单行控件
     *
     * @param fieldHtmlDto
     * @return
     */
    private static String getSingleLineHtml(FieldHtmlDto fieldHtmlDto) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(
                "           <div class=\"col-md-6\">\n"
                        + "               <div class=\"form-group\">\n"
                        + "                   <label>"
                        + getRedAsterisk(fieldHtmlDto)
                        + fieldHtmlDto.getTitle()
                        + getCopyFieldNameString(fieldHtmlDto)
                        + getRedBak(fieldHtmlDto)
                        + "</label>\n"
                        + "                   <div class=\"input-group col-md-11\">\n");
        List<Integer> columnParentIds =
                SelectQuerySyntax.parseChildrenParentIds(fieldHtmlDto.getDefaultValue());
        if (!columnParentIds.isEmpty()) {
            if (StringUtils.isBlank(fieldHtmlDto.getIsReadOnly())
                    && Boolean.TRUE.equals(fieldHtmlDto.getInputMultiple())) {
                return getMultiselectHtmlNews(
                        getColumnOptionEntities(columnParentIds), fieldHtmlDto, "5");
            }
            stringBuilder.append(getColumnOptionsSelectHtml(fieldHtmlDto, columnParentIds));
            stringBuilder.append(
                    "                   </div>\n"
                            + "               </div>\n"
                            + "           </div>\n");
            return stringBuilder.toString();
        }
        // 如果默认值是以{$开头且必须以$}结尾则当前字段使用下拉类型来输出
        String defaultValue = fieldHtmlDto.getDefaultValue();
        if (defaultValue.startsWith(WebConst.SELECT_ELEMENT_STARTS_WITH)
                && defaultValue.endsWith(WebConst.SELECT_ELEMENT_ENDS_WITH)) {
            // 下拉选项类型
            List<ContentEntity> contentEntityList = new ArrayList<>();
            List<Integer> classIdList = new ArrayList<>();
            String titleString = "title";
            String valueString = "title";
            String multipleSize = "";
            if (StringUtils.isNotBlank(defaultValue)) {
                String[] defaultValueArray =
                        defaultValue
                                .replace(WebConst.SELECT_ELEMENT_STARTS_WITH, "")
                                .replace(WebConst.SELECT_ELEMENT_ENDS_WITH, "")
                                .split("_");
                // 动态获取子栏目id 20200313 根据唯一值动态获取当前栏目的子栏目
                // 根据父ID和唯一值动态获取子栏目
                // {$101_child_HjQs1ZcVaF9K27bh_5$}
                if (defaultValueArray.length > 3 && defaultValueArray[1].equals("child")) {
                    classIdList =
                            menuService.getClassIdListByParentIdAndIdentifies(
                                    Integer.valueOf(defaultValueArray[0]), defaultValueArray[2]);
                    multipleSize = defaultValueArray[3];
                }
                // 根据父ID和唯一值动态获取子栏目
                // {$101_child_HjQs1ZcVaF9K27bh$}
                else if (defaultValueArray.length > 2 && defaultValueArray[1].equals("child")) {
                    classIdList =
                            menuService.getClassIdListByParentIdAndIdentifies(
                                    Integer.valueOf(defaultValueArray[0]), defaultValueArray[2]);
                }
                // {$child_HjQs1ZcVaF9K27bh$}
                else if (defaultValueArray.length > 1 && defaultValue.contains("child_")) {
                    classIdList =
                            menuService.getClassIdListByParentIdAndIdentifies(
                                    fieldHtmlDto.getClassId(), defaultValueArray[1]);
                    //                    classIDString = getClassIDString(defaultValue,
                    // fieldHtmlDto.getClassId());
                }
                // 自定义健和值字段
                // {$6_KeyField_ValueField_8$}
                else if (defaultValueArray.length > 3) {
                    classIdList.add(Integer.valueOf(defaultValueArray[0]));
                    //                    classIDString = defaultValueArray[0];
                    titleString = defaultValueArray[1];
                    valueString = defaultValueArray[2];
                    multipleSize = defaultValueArray[3];
                }
                // {$6_KeyField_ValueField$}
                else if (defaultValueArray.length > 2) {
                    classIdList.add(Integer.valueOf(defaultValueArray[0]));
                    //                    classIDString = defaultValueArray[0];
                    titleString = defaultValueArray[1];
                    valueString = defaultValueArray[2];
                }
                // 自定义健字段
                // {$6_Title1$}
                else if (defaultValueArray.length > 1) {
                    //                    classIDString = defaultValueArray[0];
                    classIdList.add(Integer.valueOf(defaultValueArray[0]));
                    titleString = defaultValueArray[1];
                } else {
                    // {$6$}
                    classIdList.add(Integer.valueOf(defaultValueArray[0]));
                    //                    classIDString =
                    // defaultValue.replace(WebConst.SELECT_ELEMENT_STARTS_WITH,
                    // "").replace(WebConst.SELECT_ELEMENT_ENDS_WITH, "");
                }
                contentEntityList = contentService.getArticleByClassIdForSelect(classIdList);
            }
            stringBuilder.append(
                    "                       <div class=\"input-group-addon\">\n"
                            + "                           <i class=\"fa fa-caret-down\""
                            + " aria-hidden=\"true\"></i>\n"
                            + "                       </div>\n");

            // 增加可以选择栏目集合
            // {$109_identifies_3e009qHcGXD8Rcag$}
            if (titleString.equals("identifies")) {
                contentEntityList = new ArrayList<>();
                List<OneshotEntity> oneshotEntityList =
                        menuService.getOneshotListByParentIdAndIdentifies(
                                classIdList.get(0), valueString);
                if (CollectionUtils.isNotEmpty(oneshotEntityList)) {
                    ContentEntity contentEntity;
                    for (OneshotEntity oneshotEntity : oneshotEntityList) {
                        contentEntity = new ContentEntity();
                        contentEntity
                                .setClassid(oneshotEntity.getId())
                                .setId(oneshotEntity.getId())
                                .setTitle(oneshotEntity.getClassName());
                        contentEntityList.add(contentEntity);
                    }
                }
            }
            if (StringUtils.isNotBlank(multipleSize)) {
                // 多选news
                return getMultiselectHtmlNews(contentEntityList, fieldHtmlDto, multipleSize);
            }
            getSelectHtml(fieldHtmlDto, stringBuilder, contentEntityList, multipleSize);
        } else {
            // 输入框类型
            stringBuilder.append(
                    "                       <div class=\"input-group-addon\">\n"
                            + "                           <i class=\"fa fa-text-width\"></i>\n"
                            + "                       </div>\n"
                            + "                       <input name=\""
                            + fieldHtmlDto.getFieldName()
                            + "\" type=\"text\"");
            getCustomSetting(fieldHtmlDto, stringBuilder);
            stringBuilder.append(
                    " placeholder=\"请输入"
                            + fieldHtmlDto.getTitle()
                            + "\""
                            + " value=\""
                            + (!fieldHtmlDto.getFieldValue().equals("null")
                                    ? fieldHtmlDto.getFieldValue()
                                    : "")
                            + "\">\n");
        }

        stringBuilder.append(
                "                   </div>\n" + "               </div>\n" + "           </div>\n");
        return stringBuilder.toString();
    }

    /**
     * 获取复制属性的字符串
     *
     * @param fieldHtmlDto
     * @return
     */
    @NotNull
    private static String getCopyFieldNameString(FieldHtmlDto fieldHtmlDto) {
        String copyContent = fieldHtmlDto.getFieldName();
        String itemPrefix;
        if (fieldHtmlDto.getOneshotEntity() != null
                && fieldHtmlDto.getOneshotEntity().getPageType() == 0) {
            itemPrefix = "itemMenu.";
        } else {
            itemPrefix = "itemEach.";
        }
        if ("Image".equals(fieldHtmlDto.getFieldType())) {
            if (fieldHtmlDto.getTitle().contains(WebConst.VIDEO_STRING)) {
                copyContent = " th:poster=\"${" + itemPrefix + fieldHtmlDto.getFieldName() + "}\"";
            } else {
                copyContent = " th:src=\"${" + itemPrefix + fieldHtmlDto.getFieldName() + "}\"";
                if ("itemEach.".equals(itemPrefix)) {
                    copyContent += " th:alt=\"${itemEach.title}\"";
                } else {
                    copyContent += " th:alt=\"${itemMenu.className}\"";
                }
            }
        } else if ("File".equals(fieldHtmlDto.getFieldType())
                || ("SingleLine".equals(fieldHtmlDto.getFieldType())
                        && fieldHtmlDto.getTitle().contains("链接"))) {
            copyContent =
                    " th:if=\"${!#strings.isEmpty("
                            + itemPrefix
                            + fieldHtmlDto.getFieldName()
                            + ")}\" th:attr=\"href=${#strings.isEmpty("
                            + itemPrefix
                            + fieldHtmlDto.getFieldName()
                            + ")?'':"
                            + itemPrefix
                            + fieldHtmlDto.getFieldName()
                            + "},target=${#strings.isEmpty("
                            + itemPrefix
                            + fieldHtmlDto.getFieldName()
                            + ")?'':'_blank'}\"";
        } else if ("MultiLine".equals(fieldHtmlDto.getFieldType())) {
            copyContent =
                    " th:utext=\"${@CommonUtils.lineToBr("
                            + itemPrefix
                            + fieldHtmlDto.getFieldName()
                            + ")}\"";
        } else if ("Editor".equals(fieldHtmlDto.getFieldType())) {
            copyContent = " th:utext=\"${" + itemPrefix + fieldHtmlDto.getFieldName() + "}\"";
        } else if ("DateTime".equals(fieldHtmlDto.getFieldType())) {
            copyContent =
                    " th:text=\"${@CommonUtils.getDateString("
                            + itemPrefix
                            + fieldHtmlDto.getFieldName()
                            + ")}\"";
        } else if ("Video".equals(fieldHtmlDto.getFieldType())) {
            copyContent =
                    " th:video=\"${"
                            + itemPrefix
                            + fieldHtmlDto.getFieldName()
                            + "}\" th:data-video=\"${"
                            + itemPrefix
                            + fieldHtmlDto.getFieldName()
                            + "}\" th:data-src=\"${"
                            + itemPrefix
                            + fieldHtmlDto.getFieldName()
                            + "}\"";
        } else if ("SingleLine".equals(fieldHtmlDto.getFieldType())) {
            if (StringUtils.isNotBlank(fieldHtmlDto.getDefaultValue())
                    && fieldHtmlDto.getDefaultValue().contains(WebConst.SELECT_ELEMENT_STARTS_WITH)
                    && fieldHtmlDto.getDefaultValue().contains(WebConst.SELECT_ELEMENT_ENDS_WITH)) {
                copyContent =
                        " th:each=\"itemEach,status:${@CommonUtils.getFrontInfoListByIds(info."
                                + fieldHtmlDto.getFieldName()
                                + ")}\"";
            } else if (fieldHtmlDto.getTitle().contains(WebConst.TEL_STRING)) {
                // 电话类型特殊处理
                copyContent =
                        " th:if=\"${!#strings.isEmpty("
                                + itemPrefix
                                + fieldHtmlDto.getFieldName()
                                + ")}\" th:href=\"${'tel:'+"
                                + itemPrefix
                                + fieldHtmlDto.getFieldName()
                                + "}\" th:text=\"${"
                                + itemPrefix
                                + fieldHtmlDto.getFieldName()
                                + "}\"";
            } else if (fieldHtmlDto.getTitle().contains(WebConst.E_MAIL_STRING)) {
                // 邮箱类型特殊处理
                copyContent =
                        " th:if=\"${!#strings.isEmpty("
                                + itemPrefix
                                + fieldHtmlDto.getFieldName()
                                + ")}\" th:href=\"${'mailto:'+"
                                + itemPrefix
                                + fieldHtmlDto.getFieldName()
                                + "}\" th:text=\"${"
                                + itemPrefix
                                + fieldHtmlDto.getFieldName()
                                + "}\"";
            } else {
                // 普通单行文本
                copyContent = " th:text=\"${" + itemPrefix + fieldHtmlDto.getFieldName() + "}\"";
            }
        }
        return fieldHtmlDto.getIsSuperAdmin()
                ? " - <span title=\"点击复制字段名\" style=\"color:blue;cursor:pointer;\""
                        + " class=\"copy-item\" data-copy-content=\""
                        + StringEscapeUtils.escapeHtml4(copyContent)
                        + "\">"
                        + fieldHtmlDto.getFieldName()
                        + "</span>"
                : "";
    }

    /**
     * 多选下拉新
     *
     * @param itemList
     * @param fieldHtmlDto
     * @param multipleSize
     * @return
     */
    private static String getMultiselectHtmlNews(
            List<ContentEntity> itemList, FieldHtmlDto fieldHtmlDto, String multipleSize) {
        StringBuilder stringBuilder = new StringBuilder();
        // 已选择选项
        List<ContentEntity> selected = new ArrayList<>();
        List<String> selectedIds = null;
        Map<String, ContentEntity> itemMap = new HashMap<>();
        if (StringUtils.isNotBlank(fieldHtmlDto.getFieldValue())) {
            selectedIds = Arrays.asList(fieldHtmlDto.getFieldValue().split(","));
        }
        stringBuilder.append("<div class=\"col-md-12\">");
        stringBuilder.append("<div class=\"formselect\">");
        stringBuilder.append(
                "<div class=\"form-group multiple-select\" id=\""
                        + fieldHtmlDto.getFieldName()
                        + "Select\">");
        stringBuilder.append(
                "<label>"
                        + getRedAsterisk(fieldHtmlDto)
                        + fieldHtmlDto.getTitle()
                        + getRedBak(fieldHtmlDto)
                        + "</label>\n");
        stringBuilder.append("<div class=\"input-group\">");

        stringBuilder.append("<div class=\"input-group-addon\">");
        stringBuilder.append("<i class=\"fa fa-caret-down\" aria-hidden=\"true\"></i>");
        stringBuilder.append("</div>");

        stringBuilder.append("<div class=\"col-md-5\">");
        stringBuilder.append(
                "<input type=\"text\" class=\"form-control search-input\" placeholder=\"搜索可选选项\">");
        stringBuilder.append("<div class=\"select-box\">");
        stringBuilder.append("<ul class=\"list-group select-list\">");
        if (CollectionUtils.isNotEmpty(itemList)) {
            for (ContentEntity contentEntity : itemList) {
                itemMap.put(contentEntity.getId().toString(), contentEntity);
                boolean isExist = false;
                // 按序获取已选项
                if (CollectionUtils.isNotEmpty(selectedIds)
                        && selectedIds.contains(contentEntity.getId().toString())) {
                    isExist = true;
                }
                stringBuilder.append(
                        "<li class=\"list-group-item "
                                + (isExist ? "added" : "")
                                + " \" data-id=\""
                                + contentEntity.getId()
                                + "\">"
                                + contentEntity.getTitle()
                                + "</li>");
            }
        } else {
            stringBuilder.append(
                    "<span class=\"list-group-item\" value=\"\">尚未配置"
                            + fieldHtmlDto.getTitle()
                            + "</span>");
        }
        stringBuilder.append("</ul>");
        stringBuilder.append("</div>");
        stringBuilder.append("</div>");

        stringBuilder.append("<div class=\"col-md-2\">");
        stringBuilder.append(
                "<button type=\"button\" class=\"btn btn-primary move-left\"> < </button>");
        stringBuilder.append(
                "<button type=\"button\" class=\"btn btn-primary move-right\"> > </button>");
        stringBuilder.append("</div>");

        stringBuilder.append("<div class=\"col-md-4\">");
        stringBuilder.append("<label>已选选项</label>");

        stringBuilder.append("<div class=\"selected-box\">");
        stringBuilder.append(
                "<input type=\"hidden\" name=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" value=\""
                        + fieldHtmlDto.getFieldValue()
                        + "\" >");
        stringBuilder.append("<ul class=\"list-group selected-list\">");

        // 已选

        // 按序获取已选项
        if (CollectionUtils.isNotEmpty(selectedIds) && CollectionUtils.isNotEmpty(itemList)) {
            for (String id : selectedIds) {
                if (itemMap.get(id) != null) {
                    selected.add(itemMap.get(id));
                }
            }
        }

        if (CollectionUtils.isNotEmpty(selected)) {
            for (ContentEntity dto : selected) {
                stringBuilder.append(
                        "<li class=\"list-group-item\" data-id=\""
                                + dto.getId()
                                + "\">"
                                + dto.getTitle()
                                + "</li>");
            }
        }
        stringBuilder.append("</ul>");
        stringBuilder.append("</div>");
        stringBuilder.append("</div>");

        stringBuilder.append("</div>");
        stringBuilder.append("</div>");
        stringBuilder.append("</div>");
        stringBuilder.append("</div>\n");

        return stringBuilder.toString();
    }

    /** 渲染「多个父栏目的全部直接子栏目并集」单选下拉（{$<父栏目id,...>_children$}）；只读时渲染只读文本框。 */
    private static String getColumnOptionsSelectHtml(
            FieldHtmlDto fieldHtmlDto, List<Integer> parentIds) {
        List<OneshotEntity> children = getColumnChildren(parentIds);
        String current = fieldHtmlDto.getFieldValue();
        Set<String> selected = new HashSet<>();
        if (StringUtils.isNotBlank(current) && !"null".equals(current.trim())) {
            for (String part : current.split(WebConst.COMMA)) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    selected.add(trimmed);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(fieldHtmlDto.getIsReadOnly())) {
            // 只读：渲染只读文本框展示栏目名（与 getSelectHtml 只读分支一致）
            String selectText = "尚未配置" + fieldHtmlDto.getFieldName();
            List<String> names = new ArrayList<>();
            for (OneshotEntity child : children) {
                if (selected.contains(String.valueOf(child.getId()))) {
                    names.add(
                            child.getClassName() != null
                                    ? child.getClassName()
                                    : String.valueOf(child.getId()));
                }
            }
            if (!names.isEmpty()) {
                selectText = String.join(WebConst.COMMA, names);
            }
            sb.append("<input name=\"" + fieldHtmlDto.getFieldName() + "\" type=\"text\"");
            getCustomSetting(fieldHtmlDto, sb);
            sb.append(
                    " placeholder=\"请输入"
                            + fieldHtmlDto.getTitle()
                            + "\" value=\""
                            + selectText
                            + "\">\n");
            return sb.toString();
        }

        sb.append(
                "                       <div class=\"input-group-addon\">\n"
                        + "                           <i class=\"fa fa-caret-down\""
                        + " aria-hidden=\"true\"></i>\n"
                        + "                       </div>\n");
        sb.append(
                "                       <select style=\"width: 100%;\" name=\""
                        + fieldHtmlDto.getFieldName()
                        + "\" id=\""
                        + fieldHtmlDto.getFieldName()
                        + "\"");
        getCustomSetting(fieldHtmlDto, sb);
        sb.append(" placeholder=\"请选择" + fieldHtmlDto.getTitle() + "\"");
        sb.append(">\n");
        if (CollectionUtils.isEmpty(children)) {
            sb.append(
                    "                          <option value=\"\">尚未配置"
                            + fieldHtmlDto.getTitle()
                            + "</option>\n");
        } else {
            sb.append(
                    "                          <option value=\"\">请选择"
                            + fieldHtmlDto.getTitle()
                            + "</option>\n");
            for (OneshotEntity child : children) {
                String id = String.valueOf(child.getId());
                String selectedAttr = selected.contains(id) ? " selected=\"selected\"" : "";
                String label = child.getClassName() != null ? child.getClassName() : id;
                sb.append(
                        "                          <option value=\""
                                + id
                                + "\""
                                + selectedAttr
                                + ">"
                                + label
                                + "</option>\n");
            }
        }
        sb.append("                       </select>\n");
        return sb.toString();
    }

    /** 多个父栏目的全部直接子栏目（按出现顺序去重）。 */
    private static List<OneshotEntity> getColumnChildren(List<Integer> parentIds) {
        List<OneshotEntity> children = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Integer parentId : parentIds) {
            for (OneshotEntity child : menuService.getOneshotListByParentId(parentId)) {
                if (child.getId() != null && seen.add(child.getId())) {
                    children.add(child);
                }
            }
        }
        return children;
    }

    /** 父栏目的全部直接子栏目合成 ContentEntity 选项（id=栏目id，title=栏目名），供多选渲染复用。 */
    private static List<ContentEntity> getColumnOptionEntities(List<Integer> parentIds) {
        List<ContentEntity> options = new ArrayList<>();
        for (OneshotEntity child : getColumnChildren(parentIds)) {
            ContentEntity entity = new ContentEntity();
            entity.setClassid(child.getId())
                    .setId(child.getId())
                    .setTitle(
                            child.getClassName() != null
                                    ? child.getClassName()
                                    : String.valueOf(child.getId()));
            options.add(entity);
        }
        return options;
    }

    /**
     * 获取下拉选项的html代码
     *
     * @param fieldSettingEntity
     * @param stringBuilder
     * @param contentEntityList
     * @param MultipleSize
     */
    private static void getSelectHtml(
            ContentFieldDefinition fieldSettingEntity,
            StringBuilder stringBuilder,
            List<ContentEntity> contentEntityList,
            String MultipleSize) {
        if (StringUtils.isNotBlank(fieldSettingEntity.getIsReadOnly())) {
            //            stringBuilder.append("                       <select style=\"width:
            // 100%;background-color:#eee;cursor:no-drop;\" name=\"" +
            // fieldSettingEntity.getFieldName() + "\" id=\"" + fieldSettingEntity.getFieldName() +
            // "\"" + (StringUtils.isNotBlank(MultipleSize) ? " multiple=\"multiple\" size=\"" +
            // MultipleSize + "\" title=\"支持多选\"" : "") + "");
            String selectText = "尚未配置" + fieldSettingEntity.getFieldName();
            if (CollectionUtils.isNotEmpty(contentEntityList)) {
                for (ContentEntity contentEntity : contentEntityList) {
                    if (String.valueOf(contentEntity.getId())
                            .equals(fieldSettingEntity.getValue())) {
                        selectText = contentEntity.getTitle();
                    }
                }
            }

            stringBuilder.append(
                    "<input name=\"" + fieldSettingEntity.getFieldName() + "\" type=\"text\"");
            getCustomSetting(fieldSettingEntity, stringBuilder);
            stringBuilder.append(
                    " placeholder=\"请输入"
                            + fieldSettingEntity.getTitle()
                            + "\" value=\""
                            + selectText
                            + "\">\n");
            return;
        } else {
            stringBuilder.append(
                    "                       <select style=\"width: 100%;\" name=\""
                            + fieldSettingEntity.getFieldName()
                            + "\" id=\""
                            + fieldSettingEntity.getFieldName()
                            + "\""
                            + (StringUtils.isNotBlank(MultipleSize)
                                    ? " multiple=\"multiple\" size=\""
                                            + MultipleSize
                                            + "\" title=\"支持多选\""
                                    : "")
                            + "");
        }
        getCustomSetting(fieldSettingEntity, stringBuilder);
        stringBuilder.append(" placeholder=\"请选择" + fieldSettingEntity.getTitle() + "\"");
        stringBuilder.append(">\n");
        if (CollectionUtils.isNotEmpty(contentEntityList)) {
            stringBuilder.append(
                    "                          <option value=\"\">请选择"
                            + fieldSettingEntity.getTitle()
                            + "</option>\n");
            // 校验是否有二级栏目
            OneshotEntity firstModelByParentId =
                    menuService.getFirstModelByParentId(contentEntityList.get(0).getClassid());
            if (firstModelByParentId != null && firstModelByParentId.getIsSubMenu()) {
                // 读取所有子集数据
                List<ContentEntity> articleByClassId =
                        contentService.getArticleByClassIdForSelect(
                                Collections.singletonList(firstModelByParentId.getId()));
                List<ContentEntity> filterByRelationId;
                // 两级下拉选项
                for (ContentEntity contentEntity : contentEntityList) {
                    stringBuilder.append("<optgroup label=\"" + contentEntity.getTitle() + "\">");
                    filterByRelationId =
                            articleByClassId.stream()
                                    .filter(item -> item.getXgid().equals(contentEntity.getId()))
                                    .collect(Collectors.toList());
                    if (CollectionUtils.isNotEmpty(filterByRelationId)) {
                        for (ContentEntity entity : filterByRelationId) {
                            stringBuilder.append(
                                    "                          <option value=\""
                                            + entity.getId()
                                            + "\">"
                                            + entity.getTitle()
                                            + "</option>\n");
                        }
                    }
                    stringBuilder.append("</optgroup>");
                }
            } else {
                // 默认 一级的下拉选项
                for (ContentEntity contentEntity : contentEntityList) {
                    stringBuilder.append(
                            "                          <option value=\""
                                    + contentEntity.getId()
                                    + "\">"
                                    + contentEntity.getTitle()
                                    + "</option>\n");
                }
            }
        } else {
            stringBuilder.append(
                    "                          <option value=\"\">尚未配置"
                            + fieldSettingEntity.getTitle()
                            + "</option>\n");
        }
        stringBuilder.append("                       </select>\n");
    }

    /**
     * 获取默认的字段提示
     *
     * @param fieldHtmlDto
     * @return
     */
    private static String getRedBak(FieldHtmlDto fieldHtmlDto) {
        return StringUtils.isNotBlank(fieldHtmlDto.getBak())
                ? "<span style=\"color:red;\"> - "
                        + fieldHtmlDto.getBak().replace("<", "&lt;").replace(">", "&gt;")
                        + "</span>"
                : "";
    }

    /**
     * 必填项红色星号标记
     *
     * @param fieldHtmlDto
     * @return
     */
    private static String getRedAsterisk(FieldHtmlDto fieldHtmlDto) {
        return StringUtils.isNotBlank(fieldHtmlDto.getIsRequired())
                ? "<span style=\"color:red;\">*</span>"
                : "";
    }

    /**
     * 拼接自定义条件
     *
     * @param fieldSettingEntity
     * @param stringBuilder
     */
    private static void getCustomSetting(
            ContentFieldDefinition fieldSettingEntity, StringBuilder stringBuilder) {
        if (fieldSettingEntity.getMaxLength() > 0) {
            stringBuilder.append(" maxlength=\"" + fieldSettingEntity.getMaxLength() + "\"");
        }
        if (StringUtils.isNotBlank(fieldSettingEntity.getIsReadOnly())) {
            stringBuilder.append(" readonly disabled");
        }
        // 拼接样式现在
        stringBuilder.append(" class=\"form-control get");
        if (StringUtils.isNotBlank(fieldSettingEntity.getIsEmail())) {
            stringBuilder.append(" email");
        }
        if (StringUtils.isNotBlank(fieldSettingEntity.getIsIdCard())) {
            stringBuilder.append(" card");
        }
        if (StringUtils.isNotBlank(fieldSettingEntity.getIsRequired())) {
            stringBuilder.append(" required");
        }
        if (StringUtils.isNotBlank(fieldSettingEntity.getIsTel())) {
            stringBuilder.append(" tel");
        }
        if (StringUtils.isNotBlank(fieldSettingEntity.getIsNumber())) {
            stringBuilder.append(" number");
        }
        if (StringUtils.isNotBlank(fieldSettingEntity.getIsWebUrl())) {
            stringBuilder.append(" url");
        }
        if (StringUtils.isNotBlank(fieldSettingEntity.getDefaultValue())
                && fieldSettingEntity
                        .getDefaultValue()
                        .startsWith(WebConst.SELECT_ELEMENT_STARTS_WITH)
                && fieldSettingEntity
                        .getDefaultValue()
                        .endsWith(WebConst.SELECT_ELEMENT_ENDS_WITH)) {
            stringBuilder.append(" select" + fieldSettingEntity.getFieldName() + "");
        }
        stringBuilder.append("\"");
    }
}
