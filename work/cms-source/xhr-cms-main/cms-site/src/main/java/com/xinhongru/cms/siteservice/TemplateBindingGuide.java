package com.xinhongru.cms.siteservice;

import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.site.entity.SiteProjectEntity;
import com.xinhongru.web.resonseVo.FooterEntity;
import com.xinhongru.web.resonseVo.HeaderEntity;
import com.xinhongru.web.resonseVo.IdEntity;
import com.xinhongru.web.resonseVo.SeoEntity;
import com.xinhongru.web.utils.CommonUtils;

/**
 * <h1>AI 模板数据绑定指南（机器可读）</h1>
 *
 * <p>本文件是站点服务模板系统的<strong>数据绑定规范</strong>， 供 AI 工具（如 Claude、Copilot）读取，用于：
 *
 * <ol>
 *   <li><strong>自动识别</strong>模板中可用的变量及其 Java 类型
 *   <li><strong>自动生成</strong>静态 HTML 模板文件，并正确绑定数据
 *   <li><strong>自动补全</strong>模板中的变量引用和表达式
 *   <li><strong>跳转导航</strong>：IDEA 中从模板变量跳转到 Java 源码
 * </ol>
 *
 * <p>IDEA 不会自动读取本指南推导动态 Context。可通过 Spring Bean 解析工具对象， 或按需在模板中使用 {@code @thymesVar}
 * 标明页面变量的真实类型。{@link TemplateVariables} 仅作变量参考；完整步骤见 docs/AI前端动态数据绑定手册.md 的「IDEA 全站点类型导航与渲染排查」。
 *
 * <p><hr/>
 *
 * <h2>一、两种渲染路径</h2>
 *
 * <h3>路径 A：默认路由（SiteHttpServer 自动调用）</h3>
 *
 * <p>URL 匹配栏目路由后，自动构建 Context 并渲染模板。
 *
 * <pre>{@code
 * // 调用链：
 * //   SiteHttpServer.handleRequest()
 * //     → tryServeListPage() / tryServeDetailPage()
 * //       → TemplatePreviewService.renderTemplatePreviewWithIdEntity(siteId, templatePath, idEntity)
 * //         → createPreviewContextWithIdEntity() 自动注入下方所有变量
 * }</pre>
 *
 * <h3>路径 B：自定义路由（CustomRouteHandler 编码调用）</h3>
 *
 * <p>通过编码方式注册自定义路由，完全控制数据组装逻辑。
 *
 * <pre>{@code
 * // 调用链：
 * //   SiteHttpServer.handleRequest()
 * //     → tryServeCustomRoute()  ← 优先于默认路由执行
 * //       → CustomRouteHandler.handle(path, exchange, ctx)
 * //         → ctx.render("templateName", context -> {
 * //               // ★ 这里注入自定义数据（可跨多表查询）
 *               context.setVariable("myData", complexQueryResult);
 *           })
 * }</pre>
 *
 * <p><hr/>
 *
 * <h2>二、模板可用变量完整清单</h2>
 *
 * <h3>2.1 基础变量（两种路径都自动注入）</h3>
 *
 * <table border="1">
 *   <tr><th>变量名</th><th>Java 类型</th><th>说明</th><th>模板示例</th></tr>
 *   <tr><td>siteId</td><td>Long</td><td>站点 ID</td><td>${siteId}</td></tr>
 *   <tr><td>site</td><td>{@link SiteProjectEntity}</td><td>站点配置</td><td>${site.projectName}</td></tr>
 *   <tr><td>siteConfig</td><td>{@link SiteProjectEntity}</td><td>站点配置（别名）</td><td>${siteConfig.projectCode}</td></tr>
 *   <tr><td>projectCode</td><td>String</td><td>站点编码</td><td>${projectCode}</td></tr>
 *   <tr><td>commonUtils</td><td>{@link CommonUtils}</td><td>工具方法集合（变量方式）</td><td>${commonUtils.getDateString(info.createTime)}</td></tr>
 *   <tr><td>@CommonUtils</td><td>{@link CommonUtils}</td><td>工具方法集合（Bean 方式，IDEA 可跳转）</td><td>${@CommonUtils.getDateString(info.createTime)}</td></tr>
 * </table>
 *
 * <h3>2.2 工具变量与 Spring Bean 调用</h3>
 *
 * <p>模板中可通过 {@code @beanName} 语法直接调用 Spring Bean 方法， IDEA + Thymeleaf 插件支持 Ctrl+Click 跳转到 Java 源码。
 *
 * <p>{@code ${CommonUtils.method()}} 与 {@code ${commonUtils.method()}} 从 Context 读取工具对象， {@code
 * ${@CommonUtils.method()}} 通过 Spring Bean 解析。当前 Thymeleaf 限制在 th:src/th:href 中直接访问 Bean， 可在根元素用
 * {@code th:with="ui=${@CommonUtils}"} 声明别名，再调用 {@code ${ui.method(...)}}。 已有普通变量写法仍可继续使用。页面模型不是
 * Bean，不能直接加 @；其值由 contributor 或自定义路由显式注入。
 *
 * <table border="1">
 *   <tr><th>Bean 名称</th><th>Java 类型</th><th>常用方法</th><th>模板示例</th></tr>
 *   <tr><td>@CommonUtils</td><td>{@link CommonUtils}</td><td>数据查询、URL 生成、格式化</td><td>${@CommonUtils.getContentListByClassId(100, 10)}</td></tr>
 * </table>
 *
 * <h3>2.3 默认路由额外注入的变量（路径 A 专属）</h3>
 *
 * <table border="1">
 *   <tr><th>变量名</th><th>Java 类型</th><th>说明</th><th>模板示例</th></tr>
 *   <tr><td>view</td><td>Object（匿名对象）</td><td>页面视图聚合对象</td><td>${view.pageTitle}</td></tr>
 *   <tr><td>view.pageTitle</td><td>String</td><td>页面标题</td><td>${view.pageTitle}</td></tr>
 *   <tr><td>view.keywords</td><td>String</td><td>页面关键词</td><td>${view.keywords}</td></tr>
 *   <tr><td>view.description</td><td>String</td><td>页面描述</td><td>${view.description}</td></tr>
 *   <tr><td>view.idEntity</td><td>{@link IdEntity}</td><td>当前请求 ID 参数</td><td>${view.idEntity.classId}</td></tr>
 *   <tr><td>view.seoEntity</td><td>{@link SeoEntity}</td><td>SEO 信息</td><td>${view.seoEntity.title}</td></tr>
 *   <tr><td>view.headerEntity</td><td>{@link HeaderEntity}</td><td>头部信息</td><td>${view.headerEntity.logo1}</td></tr>
 *   <tr><td>view.footerEntity</td><td>{@link FooterEntity}</td><td>底部信息</td><td>${view.footerEntity.common}</td></tr>
 *   <tr><td>title</td><td>String</td><td>SEO 标题（顶级）</td><td>${title}</td></tr>
 *   <tr><td>keywords</td><td>String</td><td>SEO 关键词（顶级）</td><td>${keywords}</td></tr>
 *   <tr><td>description</td><td>String</td><td>SEO 描述（顶级）</td><td>${description}</td></tr>
 *   <tr><td>bannerClassId</td><td>Long</td><td>横幅栏目 ID</td><td>${bannerClassId}</td></tr>
 *   <tr><td>itemMenu</td><td>{@link OneshotEntity}</td><td>当前栏目</td><td>${itemMenu.className}</td></tr>
 *   <tr><td>currentMenu</td><td>{@link OneshotEntity}</td><td>当前栏目（别名）</td><td>${currentMenu.className}</td></tr>
 *   <tr><td>info</td><td>{@link ContentEntity}</td><td>当前内容（详情页）</td><td>${info.title}</td></tr>
 *   <tr><td>content</td><td>{@link ContentEntity}</td><td>当前内容（别名）</td><td>${content.title}</td></tr>
 * </table>
 *
 * <h3>2.4 自定义路由注入的变量（路径 B 专属）</h3>
 *
 * <p>由 {@code ctx.render("templateName", context -> {...})} 中的 contextBuilder 自由定义， 可注入任意 Java
 * 对象。AI 生成模板时应参考自定义控制器的 context.setVariable() 调用。 <hr/>
 *
 * <h2>三、CommonUtils 常用方法（模板中可直接调用）</h2>
 *
 * <h3>3.1 数据查询方法</h3>
 *
 * <pre>
 * ${@CommonUtils.getOneshotEntityById(5)}            → OneshotEntity（栏目）
 * ${@CommonUtils.getContentEntityById(100, 123)}     → ContentEntity（内容）
 * ${@CommonUtils.getContentListByClassId(100, 10)}   → List&lt;ContentEntity&gt;
 * ${@CommonUtils.getContentPagerByClassId(100, 1, 10)} → Pager&lt;ContentEntity&gt;
 * </pre>
 *
 * <h3>3.2 URL 生成方法</h3>
 *
 * <pre>
 * ${@CommonUtils.getDetailUrl(content)}   → String（详情页 URL）
 * ${@CommonUtils.getListUrl(column)}       → String（列表页 URL）
 * ${@CommonUtils.resolveUiUrl('path')}     → String（UI 资源 URL）
 * </pre>
 *
 * <h3>3.3 格式化方法</h3>
 *
 * <pre>
 * ${@CommonUtils.getDateString(createTime)}  → String（格式化日期）
 * ${@CommonUtils.lineToBr(text)}             → String（换行转 &lt;br&gt;）
 * </pre>
 *
 * <h3>3.4 导航方法</h3>
 *
 * <pre>
 * ${@CommonUtils.getBreadcrumb(idEntity)}           → List（面包屑导航）
 * ${@CommonUtils.getPrevAndNextByIdEntity(idEntity)} → Object（上一篇/下一篇）
 * </pre>
 *
 * <p><hr/>
 *
 * <h2>四、AI 生成模板文件指南</h2>
 *
 * <h3>4.1 模板文件位置</h3>
 *
 * <p>每个站点在各自的 {@code runtime/template/{projectCode}/} 目录下维护模板。
 *
 * <pre>
 * runtime/template/{projectCode}/{templateName}.html           ← 默认路由模板
 *
 * 示例：
 *   runtime/template/SITE_A/index.html             ← 网站首页
 *   runtime/template/SITE_A/news/custom_news_list.html     ← 新闻列表页
 *   runtime/template/SITE_A/news/custom_news_detail.html  ← 新闻详情页
 *   runtime/template/SITE_A/inc/header.html                     ← 公共头部（th:replace 引用）
 *
 * 多站点场景（同一套代码服务多个站点）：
 *   runtime/template/SITE_A/about-news/custom_news_list.html     ← SITE_A 站点的模板
 *   runtime/template/SITE_B/about-news/custom_news_list.html   ← SITE_B 站点的模板
 *   ctx.render("about-news/custom_news_list") 会根据 ctx.projectCode() 自动解析到对应目录
 * </pre>
 *
 * <h3>4.2 模板基本结构</h3>
 *
 * <pre>{@code
 * <!DOCTYPE html>
 * <html xmlns:th="http://www.thymeleaf.org" th:with="ui=${@CommonUtils}">
 * <head>
 *     <meta charset="UTF-8"/>
 *     <title th:text="${title}">默认标题</title>
 *     <meta name="keywords" th:content="${keywords}"/>
 *     <meta name="description" th:content="${description}"/>
 * </head>
 * <body>
 *     <!-- 引用公共头部 -->
 *     <div th:replace="inc/header :: content"></div>
 *
 *     <!-- 遍历数据列表 -->
 *     <div th:each="item : ${newsList}">
 *         <a th:href="${ui.getDetailUrl(item)}" th:text="${item.title}">标题</a>
 *         <span th:text="${@CommonUtils.getDateString(item.createTime)}">日期</span>
 *     </div>
 *
 *     <!-- 引用公共底部 -->
 *     <div th:replace="inc/footer :: content"></div>
 * </body>
 * </html>
 * }</pre>
 *
 * <h3>4.3 AI 识别数据绑定规则</h3>
 *
 * <p>当 AI 工具需要根据"静态页 + 数据"自动生成模板时，应遵循以下规则：
 *
 * <ol>
 *   <li><strong>识别数据来源</strong>：查看自定义控制器的 {@code context.setVariable(name, value)} 调用， 每个 name
 *       对应模板中的 {@code ${name}} 变量
 *   <li><strong>推断类型</strong>：根据 value 的 Java 类型推断模板中可访问的属性
 *   <li><strong>生成表达式</strong>：
 *       <ul>
 *         <li>简单字段：{@code ${obj.fieldName}}
 *         <li>集合遍历：{@code <div th:each="item : ${list}">${item.field}</div>}
 *         <li>条件判断：{@code <div th:if="${obj.flag}">...</div>}
 *         <li>Bean 调用：<strong>始终使用 {@code ${@CommonUtils.method(args)}} 语法</strong> （而非 {@code
 *             ${CommonUtils.method()}}），前者支持 IDE 跳转和重构
 *       </ul>
 *   <li><strong>模板引用</strong>：公共组件用 {@code th:replace="inc/name :: fragment"}
 * </ol>
 *
 * <h3>4.4 语法规范：@beanName vs 变量名</h3>
 *
 * <p>Thymeleaf 中调用 Spring Bean 有两种写法，AI 生成模板时<strong>必须统一使用 @ 语法</strong>：
 *
 * <table border="1">
 *   <tr><th>写法</th><th>示例</th><th>IDE 跳转</th><th>是否推荐</th></tr>
 *   <tr><td>@beanName（Bean 语法）</td><td>${@CommonUtils.getDateString(date)}</td><td>✅ 支持</td><td>✅ 推荐</td></tr>
 *   <tr><td>变量名（Context 变量）</td><td>${commonUtils.getDateString(date)}</td><td>❌ 不支持</td><td>❌ 不推荐</td></tr>
 * </table>
 *
 * <p>其他 Spring Bean 同理，如 {@code ${@someService.method()}}，只要类上标注了 {@code @Component} 并指定了 bean
 * name，即可用 {@code @beanName} 语法在模板中调用。 <hr/>
 *
 * <h2>五、自定义路由注册方式</h2>
 *
 * <h3>5.1 方式一：@PostConstruct 注册（推荐）</h3>
 *
 * <pre>{@code
 * @Component
 * public class MyController {
 *     @Resource
 *     private SiteRouteRegistry routeRegistry;
 *
 *     @PostConstruct
 *     public void init() {
 *         routeRegistry.registerGlobal("/my-page/{id}", (path, exchange, ctx) -> {
 *             String html = ctx.render("my_template", context -> {
 *                 context.setVariable("data", myService.getData());
 *             });
 *             ctx.sendHtml(exchange, html);
 *             return true;
 *         });
 *     }
 * }
 * }</pre>
 *
 * <h3>5.2 方式二：Lambda 简单注册</h3>
 *
 * <pre>{@code
 * routeRegistry.registerGlobal("/api/status", (path, exchange, ctx) -> {
 *     ctx.sendJson(exchange, Map.of("status", "ok"));
 *     return true;
 * });
 * }</pre>
 *
 * <h3>5.3 方式三：指定站点注册</h3>
 *
 * <pre>{@code
 * // 仅对 projectCode="SITE_A" 的站点生效
 * routeRegistry.register("SITE_A", "/news/{pageIndex}", handler);
 * }</pre>
 *
 * @see TemplateVariables 变量名常量定义
 * @see SiteRouteContext 站点路由上下文
 * @see CustomRouteHandler 自定义路由处理器接口
 * @see SiteRouteRegistry 路由注册表
 * @see com.xinhongru.web.utils.CommonUtils 模板工具方法集合
 */
@SuppressWarnings("unused")
public final class TemplateBindingGuide {
    private TemplateBindingGuide() {
        // 纯文档类，禁止实例化
    }
}
