package com.xinhongru.responseVo.fieldHtmlVo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * @Description @ClassName FieldHtmlVo @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/3/30 20:54
 */
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
@Data
@Accessors(chain = true)
public class FieldHtmlVo implements Serializable {
    /** 栏目ID或者文章ID */
    private Integer id;

    /** 栏目名称 */
    private String className;

    /** 页面HTML代码 */
    private String html;

    /** 页面JS代码 */
    private String script;

    /** 列表页面标题HTML代码 */
    private String titleHtml;

    /** 列表页底部推荐项HTML代码 */
    private String recommendHtml;

    /** 是否有审核权限 */
    private Boolean isStatus;

    /** 是否有删除权限 */
    private Boolean isDelete;

    /** 是否有添加权限 */
    private Boolean isAdd;

    /** 是否有导出数据的权限 */
    private Boolean isOutput;

    /** 是否有导入数据的权限 */
    private Boolean isImport;

    /** 是否有审核的权限 */
    private Boolean isCheck;

    /** 列表页搜索动态生成HTML代码 */
    private String searchHtml;

    /** 列表页搜索动态生成JS代码 */
    private String searchJsHtml;

    /** 列表页搜索发布状态、首页推荐、首页热门、首页置顶等 */
    private String searchOptionsHtml;

    /** 列表页分页动态生成HTML代码 */
    private String pageHtml;
}
