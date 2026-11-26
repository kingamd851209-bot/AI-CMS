package com.xinhongru.dto.vo.list;

import com.xinhongru.utils.QueryUtils;
import com.xinhongru.web.resonseVo.ConditionVo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

@Data
@AllArgsConstructor
@Accessors(chain = true)
public class Pager<T> {
    /** 当前页码 */
    private Integer page;

    /** 每页显示数量 */
    private Integer size;

    /** 记录集合 */
    private List<T> rows;

    /** 总记录数 */
    private Integer total;

    /** 页面路径 */
    private String pagePath;

    /** 分页HTML代码 */
    private String pageHtml;

    /** ajax分页代码 */
    private String ajaxPageHtml;

    /** ajax方法名称 */
    private String ajaxName;

    /** 搜索关键词 */
    private String searchKey;

    /** 搜索条件 */
    private ConditionVo conditionVo;

    /** 总页数 */
    private Integer pageCount;

    private String firstUrl = "javascript:void(0);";
    private String prevUrl = "javascript:void(0);";
    private String nextUrl = "javascript:void(0);";
    private String lastUrl = "javascript:void(0);";

    /**
     * 构建分页HTML代码
     *
     * @return
     */
    private String builderPageHtml() {
        String pagePath = this.getPagePath();
        StringBuilder stringBuffer = new StringBuilder();
        int recordCount = this.getTotal();
        int pageSize = this.getSize();
        int pageCount = 1;
        int pageIndex = this.getPage();
        if (recordCount % pageSize == 0) {
            pageCount = recordCount / pageSize;
        } else {
            pageCount = (recordCount / pageSize) + 1;
        }
        this.pageCount = pageCount;

        if (pageCount <= 1) {
            return stringBuffer.toString();
        }
        int fromPage, toPage;
        int pages = pageCount;
        if (pages <= 5) {
            fromPage = 1;
            toPage = pages;
        } else {
            /** 如果当前页码小于等于3 而且总页数-当前页数大于8 */
            if (pageIndex <= 2 && pages - pageIndex >= 3) {
                fromPage = 1;
                toPage = 5;
            }
            /** 如果当前页数大于3,而且总页数-当前页数小于3 */
            else if (pageIndex > 3 && pages - pageIndex < 3) {
                fromPage = pages - 3;
                toPage = pages;
            } else {
                fromPage = pageIndex - 2;
                toPage = pageIndex + 2;
            }
        }
        /** 搜索条件组装 */
        ConditionVo filters = conditionVo == null ? new ConditionVo() : conditionVo;
        String condition = QueryUtils.getQueryString(filters);
        String pageIndexCondition = "";
        stringBuffer.append("<div class=\"pageout\">");
        stringBuffer.append("<div class=\"page\">");
        if (pageIndex == 1) {
            prevUrl = "javascript:void(0);";
            stringBuffer.append(
                    "<a href=\"javascript:void(0);\" title=\"Prev page\"  class=\"prev"
                            + " no\">上一页</a>");
        } else {
            prevUrl = pagePath + ".html";
            if (StringUtils.isNotBlank(condition)) {
                pageIndexCondition = "&pageIndex=" + (pageIndex - 1);
            } else {
                pageIndexCondition = "?pageIndex=" + (pageIndex - 1);
            }
            if (pageIndex == 2) {
                pageIndexCondition = "";
            }
            stringBuffer.append(
                    "<a href=\""
                            + prevUrl
                            + condition
                            + pageIndexCondition
                            + "\" title=\"Prev page\"  class=\"prev\">上一页</a>");
            pageIndexCondition = "";
        }
        // 页数循环
        for (int i = fromPage; i <= toPage; i++) {
            if (i != pageIndex) {
                if (StringUtils.isNotBlank(condition)) {
                    pageIndexCondition += "&pageIndex=" + i;
                } else {
                    pageIndexCondition = "?pageIndex=" + i;
                }
                stringBuffer.append(
                        "        <a href=\""
                                + pagePath
                                + ".html"
                                + condition
                                + pageIndexCondition
                                + "\" title=\""
                                + i
                                + "\" class=\"num\">"
                                + i
                                + "</a>");
                pageIndexCondition = "";
            } else {
                stringBuffer.append(
                        "        <a href=\"javascript:void(0);\" title=\""
                                + i
                                + "\" class=\"num on\">"
                                + i
                                + "</a>");
            }
        }
        // 下一页
        if (pageIndex == pageCount) {
            stringBuffer.append(
                    "        <a href=\"javascript:void(0);\" title=\"下一页\" class=\"next"
                            + " no\">下一页</a>");
        } else {
            if (StringUtils.isNotBlank(condition)) {
                pageIndexCondition += "&pageIndex=" + (pageIndex + 1);
            } else {
                pageIndexCondition = "?pageIndex=" + (pageIndex + 1);
            }
            stringBuffer.append(
                    "        <a href=\""
                            + pagePath
                            + ".html"
                            + condition
                            + pageIndexCondition
                            + "\" title=\"下一页\" class=\"next\">下一页</a>");
            pageIndexCondition = "";
        }

        stringBuffer.append("<span class=\"txtpage\">" + pageIndex + "/" + pageCount + "</span>");
        stringBuffer.append(
                "<span class=\"txtnum\"><input type=\"text\" name=\"jump\" value=\""
                        + pageIndex
                        + "\" data-page-index=\""
                        + pageIndex
                        + "\" data-page-max=\""
                        + pageCount
                        + "\" data-page-url=\""
                        + pagePath
                        + "\" data-page-key=\""
                        + HtmlUtils.htmlEscape(StringUtils.defaultString(filters.getKey()))
                        + "\"></span>");
        stringBuffer.append("<input type=\"button\" title=\"GO\" class=\"go jump\" value=\"GO\">");

        stringBuffer.append("</div>");
        stringBuffer.append("</div>");
        return stringBuffer.toString();
    }

    private String builderAjaxPageHtml() {
        StringBuilder stringBuffer = new StringBuilder();
        Integer RecordCount = this.getTotal();
        Integer PageSize = this.getSize();
        Integer PageCount = 1;
        Integer PageIndex = this.getPage();
        if (RecordCount % PageSize == 0) {
            PageCount = RecordCount / PageSize;
        } else {
            PageCount = (RecordCount / PageSize) + 1;
        }
        if (PageCount <= 1) {
            return stringBuffer.toString();
        }
        int fromPage, toPage;
        int pages = PageCount;
        if (pages <= 10) {
            fromPage = 1;
            toPage = pages;
        } else {
            if (PageIndex <= 3 && pages - PageIndex >= 8) {
                fromPage = 1;
                toPage = 10;
            } else if (PageIndex > 3 && pages - PageIndex < 8) {
                fromPage = pages - 9;
                toPage = pages;
            } else {
                fromPage = PageIndex - 2;
                toPage = PageIndex + 7;
            }
        }
        stringBuffer.append("<div class=\"pageout fnt_16\">");
        stringBuffer.append("<div class=\"page\">");

        // 上一页
        if (PageIndex.equals(1)) {
            stringBuffer.append(
                    "<a href=\"javascript:;\" class=\"prev disabled\"><i"
                            + " class=\"icon-yqzjt\"></i></a>");
        } else {
            stringBuffer.append(
                    "<a href=\"javascript:;\" class=\"prev ajax-page-btn\" data-page=\""
                            + (PageIndex - 1)
                            + "\"><i class=\"icon-yqzjt\"></i></a>");
        }
        // 页数循环
        for (int i = fromPage; i <= toPage; i++) {
            if (i != PageIndex) {
                stringBuffer.append(
                        "<a href=\"javascript:;\" data-page=\""
                                + i
                                + "\" class=\"num ajax-page-btn\">"
                                + i
                                + "</a>");
            } else {
                stringBuffer.append(
                        "<a href=\"javascript:;\" data-page=\""
                                + i
                                + "\" class=\"num  ajax-page-btn on\">"
                                + i
                                + "</a>");
            }
        }
        // 下一页
        if (PageIndex.equals(PageCount)) {
            stringBuffer.append(
                    "<a href=\"javascript:;\" class=\"next disabled\" ><i"
                            + " class=\"icon-yqyjt\"></i></a>");
        } else {
            stringBuffer.append(
                    "<a href=\"javascript:;\" class=\"next ajax-page-btn\" data-page=\""
                            + (PageIndex + 1)
                            + "\"><i class=\"icon-yqyjt\"></i></a>");
        }

        stringBuffer.append("</div>");
        stringBuffer.append("</div>");
        return stringBuffer.toString();
    }

    /** 系统会自动虎丘相关的参数并生成分页代码 */
    public void setPageHtml() {
        this.pageHtml = this.builderPageHtml();
        this.ajaxPageHtml = this.builderAjaxPageHtml();
    }

    public Pager() {}
}
