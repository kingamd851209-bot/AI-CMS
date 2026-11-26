package com.xinhongru.utils;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

/**
 * @Description @ClassName PageUtils @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/6/4 11:09
 */
@Slf4j
public class PageUtils {
    public static String getStringBuilderPageHtml(
            int page, int limit, String urlSearch, int count) {
        StringBuilder stringBuilderPageHtml = new StringBuilder();
        double ceil = Math.ceil(count * 1.0 / limit);
        int pageCount = (int) ceil;
        //        if (pageCount > 1) {
        int fromPage = 0;
        int toPage = 0;
        if (pageCount <= 10) {
            fromPage = 1;
            toPage = pageCount;
        } else {
            if (page <= 3 && pageCount - page >= 8) {
                fromPage = 1;
                toPage = 10;
            } else if (page > 3 && pageCount - page < 8) {
                fromPage = pageCount - 9;
                toPage = pageCount;
            } else {
                fromPage = page - 2;
                toPage = page + 7;
            }
        }
        // 搜索条件
        if (StringUtils.isNotBlank(urlSearch)) {
            urlSearch = "?" + urlSearch + "&";
        } else {
            urlSearch = "?";
        }
        stringBuilderPageHtml.append(
                "<div class=\"col-md-12\" style=\"text-align: center;\">\n"
                        + "                        <ul class=\"pagination form-inline\">\n"
                        + "                            <li>\n"
                        + "                                <span>共有 "
                        + count
                        + " 条数据 当前第 "
                        + page
                        + "/"
                        + pageCount
                        + " 页 每页显示 "
                        + limit
                        + " 条</span>\n"
                        + "                            </li>\n");
        stringBuilderPageHtml.append(
                "<li>\n"
                        + "                         <a href=\""
                        + urlSearch
                        + "page=1\" aria-label=\"Previous\">\n"
                        + "                             <i class=\"fa fa-step-backward\""
                        + " aria-hidden=\"true\"></i>&nbsp;首页\n"
                        + "                         </a>\n"
                        + "                     </li>\n");
        int prevPage = page - 1;
        if (page == 1) {
            prevPage = 1;
        }
        stringBuilderPageHtml.append(
                "<li>\n"
                        + "                         <a href=\""
                        + urlSearch
                        + "page="
                        + prevPage
                        + "\" aria-label=\"Previous\">\n"
                        + "                             <i class=\"fa"
                        + " fa-angle-left\"></i>&nbsp;上一页\n"
                        + "                         </a>\n"
                        + "                     </li>\n");

        for (int i = fromPage; i <= toPage; i++) {
            stringBuilderPageHtml.append(
                    "<li "
                            + (page == i ? "class=\"active\"" : "")
                            + ">\n"
                            + "                        <a href=\""
                            + urlSearch
                            + "page="
                            + i
                            + "\">"
                            + i
                            + "</a>\n"
                            + "                    </li>\n");
        }
        int nextPage = page + 1;
        if (page == pageCount) {
            nextPage = pageCount;
        }
        stringBuilderPageHtml.append(
                "<li>\n"
                        + "                         <a href=\""
                        + urlSearch
                        + "page="
                        + nextPage
                        + "\" aria-label=\"Next\">\n"
                        + "                             下一页&nbsp;<i class=\"fa"
                        + " fa-angle-right\"></i>\n"
                        + "                         </a>\n"
                        + "                     </li>\n");
        stringBuilderPageHtml.append(
                "<li>\n"
                        + "                         <a href=\""
                        + urlSearch
                        + "page="
                        + pageCount
                        + "\" aria-label=\"Next\">\n"
                        + "                             末页&nbsp;<i class=\"fa fa-step-forward\""
                        + " aria-hidden=\"true\"></i>\n"
                        + "                         </a>\n"
                        + "                     </li>\n");
        stringBuilderPageHtml.append("</ul>\n" + "                    </div>");
        //        }
        return stringBuilderPageHtml.toString();
    }
}
