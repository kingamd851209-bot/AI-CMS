package com.xinhongru.cms.siteservice;

import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.web.resonseVo.*;
import com.xinhongru.web.utils.CommonUtils;

/**
 * 模板变量参考：文档类，不参与运行时数据注入。
 *
 * <p>IDEA 不会根据本类的 Javadoc 或常量自动推导动态 Context。需要时可在模板中使用 {@code @thymesVar} 标明真实类型，或通过 Spring Bean
 * 解析工具对象。变量是否存在仍取决于渲染入口及当前请求。
 *
 * <h3>模板中可用的变量一览</h3>
 *
 * <h4>1. 站点信息</h4>
 *
 * <pre>
 * ${siteId}                          — {@link Long}，站点 ID
 * ${site}                            — {@link SiteProjectEntity}，站点配置
 * ${siteConfig}                      — 同上（别名）
 * ${projectCode}                     — {@link String}，站点编码
 * </pre>
 *
 * <h4>2. 工具对象</h4>
 *
 * <pre>
 * ${commonUtils}                     — {@link CommonUtils}，工具方法集合
 * ${@CommonUtils}                    — Spring Bean 方式调用（IDEA 可 Ctrl+Click 跳转到 Java 方法）
 *                                      如: ${@CommonUtils.getContentListByClassId(100, 10)}
 * </pre>
 *
 * <h4>3. view 对象（默认路由自动注入）</h4>
 *
 * <pre>
 * ${view.pageTitle}                  — {@link String}，页面标题
 * ${view.pageKeyword}                — {@link String}，页面关键词（别名）
 * ${view.keywords}                   — {@link String}，页面关键词
 * ${view.description}                — {@link String}，页面描述
 * ${view.title}                      — {@link String}，SEO 标题
 * ${view.siteName}                   — {@link String}，站点名称
 * ${view.idEntity}                   — {@link IdEntity}，当前请求 ID 参数
 * ${view.idEntity.classId}           — {@link Integer}，栏目 ID
 * ${view.idEntity.pageIndex}         — {@link Integer}，当前页码
 * ${view.idEntity.pageSize}          — {@link Integer}，每页条数
 * ${view.idEntity.infoId}            — {@link Integer}，内容 ID（详情页）
 * ${view.idEntity.parentId}          — {@link Integer}，父栏目 ID
 * ${view.seoEntity}                  — {@link SeoEntity}，SEO 信息
 * ${view.headerEntity}               — {@link HeaderEntity}，头部信息
 * ${view.headerEntity.logo1}         — {@link String}，Logo
 * ${view.footerEntity}               — {@link FooterEntity}，底部信息
 * </pre>
 *
 * <h4>4. 默认路由自动注入的顶级变量</h4>
 *
 * <pre>
 * ${title}                           — {@link String}，SEO 标题
 * ${keywords}                        — {@link String}，SEO 关键词
 * ${description}                     — {@link String}，SEO 描述
 * ${bannerClassId}                   — {@link Long}，横幅栏目 ID
 * ${itemMenu}                        — {@link OneshotEntity}，当前栏目
 * ${currentMenu}                     — 同上（别名）
 * ${info}                            — {@link ContentEntity}，当前内容（详情页）
 * ${content}                         — 同上（别名）
 * </pre>
 *
 * <h4>5. 自定义路由中可注入的任意变量（通过 SiteRouteContext.render()）</h4>
 *
 * <p>在自定义处理器的 {@code ctx.render("templateName", context -> {...})} 中， 可调用 {@code
 * context.setVariable(name, value)} 注入任意 Java 对象， 模板中通过 {@code ${name}} 或 {@code ${name.property}}
 * 访问。
 */
@SuppressWarnings("unused")
public final class TemplateVariables {
    private TemplateVariables() {
        // 纯文档类，禁止实例化
    }

    // 变量名参考；不会自动生成模板声明。

    /** ${siteId} */
    public static final String VAR_SITE_ID = "siteId";

    /** ${site} */
    public static final String VAR_SITE = "site";

    /** ${siteConfig} */
    public static final String VAR_SITE_CONFIG = "siteConfig";

    /** ${projectCode} */
    public static final String VAR_PROJECT_CODE = "projectCode";

    /** ${commonUtils} */
    public static final String VAR_COMMON_UTILS = "commonUtils";

    /** ${view} */
    public static final String VAR_VIEW = "view";

    /** ${title} */
    public static final String VAR_TITLE = "title";

    /** ${keywords} */
    public static final String VAR_KEYWORDS = "keywords";

    /** ${description} */
    public static final String VAR_DESCRIPTION = "description";

    /** ${bannerClassId} */
    public static final String VAR_BANNER_CLASS_ID = "bannerClassId";

    /** ${itemMenu} */
    public static final String VAR_ITEM_MENU = "itemMenu";

    /** ${currentMenu} */
    public static final String VAR_CURRENT_MENU = "currentMenu";

    /** ${info} */
    public static final String VAR_INFO = "info";

    /** ${content} */
    public static final String VAR_CONTENT = "content";
}
