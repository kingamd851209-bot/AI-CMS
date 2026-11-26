# Java CMS 前端动态数据绑定手册（供 AI 自动化实施）

> 用途：把已经完成布局的前端模板，绑定到既有 CMS 栏目和内容数据库。 AI 在阅读代码、核对数据库后执行，不是脱离项目即可运行的通用框架。
>
> 本文中的源码路径均相对于项目根目录；所有栏目 ID、字段业务含义、数据库连接、模板路径及截图数量都必须在目标项目重新核实。不要把本项目 ID 当作跨项目常量。

## 1. 先读：执行原则与交付标准

### 1.1 一次绑定必须贯通的链路

```text
用户指定的页面区域
  → 确认栏目、内容、关联 ID 与扩展字段含义
  → 核对数据库样例、状态、有效期和排序
  → 选择已有查询或补充轻量查询
  → 将结果加入 Model 或通过现有工具类读取
  → Thymeleaf 正确渲染文本、图片、列表、空状态
  → 链接进入正确路由与详情模板
  → 浏览器交互正常，缓存和部署版本一致
```

只把 `th:text` 写进去，不代表绑定完成。至少还要确认：取对数据、空数据不报错、链接可到达、列表与详情使用相同身份、不会把所有富文本正文加载到列表页。

### 1.2 不得直接照搬的内容

- 本项目中的 站点id、栏目id、数据id 等 ID。
- `singleLine1`、`singleLine3`、`singleLine4`、`note1` 等字段在某一栏目中的含义。
- 一个页面“最近”的排序方式；默认 CMS 排序可能先按手工排序号。
- 一个工具方法的名字或注释所暗示的行为；必须查看其实现与 Mapper。
- 本机数据库地址、口令、端口、JDK 路径、开发环境设置。
- 从另一个模板复制的 `info`、`itemMenu`、`itemEach` 等变量名；先检查当前作用域。

### 1.3 修改范围

修改前执行 `git status --short` 和目标文件的 `git diff`。用户可能刚刚修改了模板、控制器或字段名称。保留已有改动；
不要覆盖用户删除或注释掉的区域，不要因补一个链接就重新生成整页。

行号只是定位线索。前一次替换静态列表后，后续行号会变化。应结合 CSS 类名、标题、周边 DOM 和业务用途定位。

普通数据绑定任务默认不包含：修改数据库业务内容、改变后台字段定义、恢复用户注释的功能、安装新框架、发布到服务器。需要这些动作时按实际任务授权范围处理，不擅自扩展。

### 1.4 对结果的表述

- 查询和模板验证都通过，才说明相应验证通过。
- Java 编译成功不能证明 Thymeleaf 表达式正确，也不能证明网页已经部署。
- 仅做了代码修复时，明确“本地已修复，服务器尚需更新”。
- 未取得服务器日志时，不把可能原因写成已确认根因。
- 不因用户要求“有数据时显示”就假设数据库只有空字符串；还要处理 `null` 和空白字符。

### 1.5 复杂页面的数据组装（SYG 产品页）

SYG 产品页通过现有数据库栏目路由进入 `TemplatePreviewService`，渲染前由 `TemplateModelContributor` 注入页面模型。文件预览、传入 `IdEntity` 的正式站点渲染、编辑器字符串预览和自定义 Context 渲染共用此扩展点。

- `cms-site/src/main/java/com/xinhongru/web/controller/SYG/ProductsController.java`：产品页专用处理器，通过 `supports` 限定 SYG 站点、模板及 marker，并在 `contribute` 中用 `context.setVariable("productsPage", loadPage(siteId))` 注入页面模型。栏目编码、排除规则、查询条数及组装逻辑集中在此文件，直接调用 `MenuService` / `InfoService` 完成批量查询。页面模型 `SygProductsPage`、分类和子项以嵌套 record 定义，包含预计算的 slug、首推内容及标题。不注册固定 URL，不重新实现栏目路由、SEO 和访问统计。
- `runtime/template/SYG/products/index.html`：读取 `${productsPage.xxx}`，保留展示、链接和格式化表达式，不执行页面列表查询。共享页头、页尾仍沿用自己的绑定。

模板顶部的 `<!-- cms-page:syg-products -->` 用于没有模板路径的编辑器字符串预览，请保留；匹配仍受 SYG 站点范围限制。新增复杂页面时，使用业务 Controller 实现 contributor，显式完成页面匹配和模型注入，避免向 `CommonUtils` 或通用渲染服务中堆积站点专属查询。页面组装每次渲染重新执行，不跨请求或站点缓存页面模型。

### 1.6 IDEA 全站点类型导航与渲染排查

本节适用于 `runtime/template/{projectCode}/...` 下的各站点页面及公共片段。运行时数据仍由 `TemplatePreviewService`、页面 contributor 和自定义路由显式注入；`TemplateVariables` 仅是变量参考，不会从 Javadoc 或常量自动生成 HTML 类型提示。

从根 `pom.xml` 导入 Maven 工程，确认 IDEA 已索引 `cms-site` 及其依赖，并启用 Thymeleaf/Spring 表达式支持。外部模板目录需处于 IDE 可索引且能解析这些 Java 类型的模块范围内，不能标记为 Excluded。导航能力取决于 IDE 的实际支持，不代表运行时已注入对应数据。

`${CommonUtils.method(...)}` 读取 Context 变量；`${@CommonUtils.method(...)}` 按名称解析 Spring Bean。Bean 名称区分大小写。工具对象可以通过 Bean 解析减少手写类型提示，但 `siteId`、`indexPage`、`productsPage` 等页面数据不是 Bean，不能直接加 `@`。

当前 Thymeleaf 在 `th:src`、`th:href` 中限制直接访问 Bean。需要使用 Bean 时，先声明局部别名，再在 URL 属性中调用：

```html
<html xmlns:th="http://www.thymeleaf.org" th:with="ui=${@CommonUtils}">
  <img th:src="${ui.resolveUiUrl('assets/images/logo.png', siteId)}" alt="Logo">
</html>
```

已有 `${CommonUtils.resolveUiUrl(...)}` 写法仍可继续使用。`resolveUiUrl` 需要路径参数，站点页面优先传入 `siteId`。`th:with` 作用于当前元素及其子树；公共片段应在片段根节点声明别名或通过片段参数接收，不能依赖被 `th:replace` 替换的元素上的局部声明。

需要普通变量的 IDEA 类型导航时，可按需在当前模板中使用原生 `@thymesVar` 注释，例如：

```html
<!--/*@thymesVar id="CommonUtils" type="com.xinhongru.web.utils.CommonUtils"*/-->
<!--/*@thymesVar id="indexPage" type="com.xinhongru.web.controller.SYG.IndexController.SygIndexPage"*/-->
```

这些注释只提供静态类型信息，不会创建数据，也不保证变量非 null。公共片段中的注释不会自动为所有引用页面声明类型；当前项目不自动生成声明，也不使用模板监听进程。默认 `view` 是匿名聚合对象，应按 `TemplatePreviewService` 的实际 getter 检查属性，不能用 `Object` 类型提示获得完整属性导航。

`runtime/template/SYG` 的全部 HTML（包括 `inc/` 片段）已按实际使用的变量添加原生类型注释。`cms-site/pom.xml` 将 `runtime/template` 关联为所有站点共用的外部资源目录，使 IDEA 可在 `cms-site` 的依赖范围内解析 Java 类型；通过资源排除规则避免把模板复制进构建输出。修改后在 IDEA 执行 Reload All Maven Projects，不需要运行声明生成命令。

页面专属类型与 `cms-site/src/main/java/com/xinhongru/web/controller/SYG/` 的实际注入对应：

| 模板 | 数据来源 | 变量及类型 |
|---|---|---|
| `index.html` | `IndexController.contribute` | `indexPage` → `IndexController.SygIndexPage` |
| `products/index.html` | `ProductsController.contribute` | `productsPage` → `ProductsController.SygProductsPage` |
| `products/product-category.html` | `ProductCategoryController.contribute` | `categoryPage` → `ProductCategoryController.CategoryPage` |
| `products/product-detail.html` | `ProductDetailController.contribute` | `productDetail` → `ProductDetailController.ProductDetailPage`；分组图片、地区、询价选项按实际集合泛型声明；栏目为 `OneshotEntity`，`modelCode` 为 `String` |
| `products/product-compare.html` | `ComparisonController.contribute` | `comparePage` → `OneshotEntity` |

其余 SYG 页面和公共片段使用 `CommonUtils` 与 `siteId` 提示。`th:with` 的局部变量和 `th:each` 的元素类型从方法返回类型、页面模型属性及集合泛型推导，不在文件顶部统一声明 `item`、`itemEach` 等会在不同区块复用的名称。修改 Controller 的变量名、返回类型或集合元素类型时，同步维护对应模板的注释。`view` 仍是匿名对象，未为它填写不匹配的具名类型。

定位问题时依次核对：

1. 当前站点编码、模板相对路径及栏目路由配置；不同站点的同名文件不能混用。
2. 在 `TemplatePreviewService.contributePageModel`、目标 contributor 的 `supports/contribute/loadPage` 或 `CommonUtils` 方法设置断点，检查当前栏目、内容 ID 和 Context 中的实际变量。
3. 在 IDEA 使用 Navigate → Declaration or Usages、Type Declaration 和 Find Usages 检查调用与类型；动态 Context 无法解析时，核对变量名、真实类型及模板所属模块。
4. 通过 CMS 站点路由或已有编辑器预览验证实际渲染，覆盖公共片段、空数据及失效内容。直接打开 HTML、IDEA 导航成功或 Java 编译成功都不能替代真实渲染验证。

### 1.7 工作台隔离预览的公共区域和样式

静态预览展开当前 UI 包内的 SSI 公共片段。页面及片段中的相对资源路径按预览页面的位置解析，CSS 文件及其 `@import` 中的相对引用按各自样式文件的位置解析。可读取的包内样式、图片和字体会内联，源文件及绑定来源摘要保持原样。包中缺少的文件会产生预览警告，应补齐同一 UI 包中的文件或修正引用路径。

外部 CSS 和字体由浏览器通过 HTTPS 加载，允许域由 Spring 配置 `cms.ui-package.preview-style-hosts` 指定，使用逗号分隔的完整主机名。默认允许 `at.alicdn.com,g.alicdn.com`，空值关闭外部样式和字体加载。第三方字体仍须满足浏览器跨域要求。隔离预览禁用脚本、表单提交、嵌套页面和媒体播放。

公共标志若由包内脚本把固定 SVG 字面量写入空容器，隔离预览可静态补齐由 `viewBox` 和简单 `path` 组成的图形，使原样式仍能控制图形填色。依赖脚本计算、接口数据或交互状态的内容需要在正常站点渲染中核验。

## 2. 项目架构与阅读顺序

### 2.1 目录定位表

| 位置 | 职责 | 绑定时重点查看 |
|---|---|---|
| `runtime/template/{projectCode}/` 及其 `inc/` | 站点模板和公共片段 | 页面类型声明、Model 变量、片段参数 |
| `cms-site: web/controller/` | 自定义路由和页面 Model 组装 | `supports`、`contribute`、`loadPage` |
| `cms-site: cms/template/TemplatePreviewService.java` | 统一渲染与公共视图 | Context 注入、站点模板 resolver、错误定位 |
| `cms-site: cms/siteservice/TemplateVariables.java` | 公共变量参考文档 | 变量名称及类型说明；实际值以渲染入口为准 |
| `cms-site: web/utils/CommonUtils.java` | 模板业务工具 | 栏目、列表、关联、链接、分页地址 |
| `cms-contracts: web/service/InfoService.java`；`cms-core: web/service/impl/InfoServiceImpl.java` | 内容业务查询契约与实现 | 分页、关联、轻量列表、详情校验 |
| `cms-contracts: web/service/MenuService.java`；`cms-core: web/service/impl/MenuServiceImpl.java` | 前台栏目读取契约与实现 | 显示条件、缓存、栏目地址转换 |
| `cms-contracts: service/oneshot/OneShotService.java` | 栏目实体服务契约 | 与前台 MenuService 的过滤差异 |
| `cms-core: web/mapper/InfoMapper.java`、`MenuMapper.java` | MyBatis 接口 | 方法名与 `@Param` |
| `cms-core/src/main/resources/mapper/` | 内容与栏目 SQL | 字段集合、状态、有效期、排序、关联与分页 |
| `cms-contracts: entity/ContentEntity.java`、`OneshotEntity.java` | 数据实体 | 属性名、继承字段、Java 类型、大小写 |
| `cms-contracts: web/resonseVo/ConditionVo.java`、`dto/vo/list/Pager.java` | 查询条件及既有分页对象 | 核对具体调用链；新分页按架构约定使用 MyBatis-Plus |
| `runtime/ui-packages/` | 站点 UI 资源 | 结合 UI 包服务和 `resolveUiUrl` 核对访问地址 |
| `cms-boot/src/main/resources/application*.yml` | 环境配置 | 激活 profile、数据库、Thymeleaf、缓存、静态资源 |
| `pom.xml` | 编译与依赖 | Java/框架版本、测试跳过设置 |

表中 `模块: 包路径` 表示 `模块/src/main/java/com/xinhongru/包路径`。后续历史示例中的旧根 `src/...` 路径需按此表映射，不应恢复旧源码目录。

### 2.2 推荐阅读顺序

1. 用户指定模板和 `git diff`。
2. 当前页面 contributor、自定义路由以及 `SiteHttpServer` / `TemplatePreviewService` 对应入口。
3. 所用工具方法的实现。
4. Service 实现与最终执行的 Mapper SQL。
5. 实体类、栏目字段配置、真实数据库样例。
6. 公共片段、相关 CSS/JS、运行环境配置。

不要只搜索到第一个同名方法就停止。这个项目既有前台查询，也有后台管理查询；同样叫“按 ID 获取”的方法，过滤规则可能不同。

### 2.3 模板和资源不是同一类文件

模板需要通过 Controller 和 Thymeleaf 处理；直接用文件浏览器打开 HTML 不会执行 `th:*`。

站点素材保存 `assets:/upload/...`，实际位于站点 `assets/upload`；全局素材保存 `assets:/global/...`，实际位于 `cms.static-asset.root`（默认 `runtime/assets`）。UI包资源保存 `ui:/...`。模板通过 `${ui.resolveUiUrl(value, siteId)}` 解析，站点字段 `site_service_ip` 填写完整 HTTP(S) 资源根地址时优先使用，直接追加资源相对路径，不自动追加站点编码；例如根地址 `https://cdn.example.com/syg` 对应 `ui:/css/main.css` 为 `https://cdn.example.com/syg/css/main.css`，站点上传及全局素材分别追加 `assets/upload/`、`assets/global/`。

本站资源根地址为空时使用环境配置 `cms.resources.domain`，站点 UI/上传资源追加站点编码，全局素材不追加编码。两者都未配置时优先使用运行中的站点服务地址，再尝试共享 UI 服务；无站点上下文的全局素材保留 `/assets/global/` 相对地址。旧裸域名/IP 仅保留原共享 UI 主机兜底语义，不自动转换或提升优先级，修改时填写完整 HTTP(S) 地址。配置只影响访问 URL，不上传文件至 OSS，也不改写历史绝对 URL；静态入口必须映射对应目录，包含全局素材目录。

## 3. 数据库模型与 ID 关系

### 3.1 两张核心表

本项目实际表名是 **`t_oneshot`** 和 **`t_contents`**。用户口头提到的 `one_shot`、`t_content` 是业务指代，写 SQL 前必须核对实际名称。

#### `t_oneshot`：栏目及单页配置

| 数据库字段 | Java 属性 | 用途 |
|---|---|---|
| `id` | `id` | 栏目 ID |
| `parent_id` | `parentId` | 上级栏目 ID，形成栏目树 |
| `class_name` | `className` | 栏目名称 |
| `list_page_url` | `listPageUrl` | 栏目/列表页面地址或地址模式 |
| `detail_page_url` | `detailPageUrl` | 内容详情地址模式 |
| `list_page_template` / `detail_page_template` | `listPageTemplate` / `detailPageTemplate` | 模板配置；是否自动使用取决于路由实现 |
| `menu_field` | `menuField` | 单篇栏目字段配置 |
| `model_field` | `modelField` | 该栏目下内容模型的字段配置 |
| `model_list` / `model_search` | `modelList` / `modelSearch` | 后台列表/搜索配置 |
| `identifies` | `identifies` | 自定义唯一标识，可用于跨环境定位栏目 |
| `setting` | `setting` | 后台功能开关等配置；不能据此直接推断字段文案 |
| `has_front_channel`、`is_loop`、`is_system`、`deleted` | 相应驼峰属性 | 前台可见性、参与循环、系统标识、删除标识 |
| `sort` | `sort` | 栏目排序号 |
| `single_lineN`、`noteN`、`photoN`、`contentN` 等 | `singleLineN`、`noteN`、`photoN`、`contentN` | 栏目页面配置内容 |

#### `t_contents`：栏目下的列表内容记录

| 数据库字段 | Java 属性 | 用途 |
|---|---|---|
| `id` | `id` | 内容记录 ID |
| `class_id` | **`classid`** | 该内容所属栏目；实体不是 `classId` |
| `xgid` | `xgid` | 关联内容 ID；在本手册示例中指向所属院校/机构/社群的内容记录 |
| `title` | `title` | 内容标题/名称 |
| `tags` | `tags` | 标签文字；是否为普通文本需查模型 |
| `single_line1` … | `singleLine1` … | 栏目自定义单行字段 |
| `note1` … | `note1` … | 多行字段，可能是简介，也可能是嵌入视频代码 |
| `photo1` … | `photo1` … | 图片地址 |
| `media1` … | `media1` … | 媒体地址；并非所有视频功能都使用它 |
| `file1` … | `file1` … | 附件地址 |
| `content1` … `content6` | `content1` … `content6` | 富文本正文，可能非常大 |
| `create_time` | `createTime` | 发布时间，实体通常为 `LocalDateTime` |
| `entry_into_force_time` | `entryIntoForceTime` | 生效时间 |
| `expiration_time` | `expirationTime` | 失效时间 |
| `status`、`deleted` | `status`、`deleted` | 发布和删除状态 |
| `sort`、`hits` | `sort`、`hits` | 排序号、访问量 |

实体属性是模板表达式的依据。数据库列叫 `class_id`，Controller 参数可叫 `classId`，但 `ContentEntity` 在模板中读取的是 **`${info.classid}`**。

### 3.2 四种容易混淆的 ID

```text
栏目树：t_oneshot.parent_id → t_oneshot.id
内容归属：t_contents.class_id → t_oneshot.id
内容关联：子记录.xgid → 主记录.id（具体业务必须核对）
内容自身：t_contents.id → 当前这一条记录
```

## 4. 如何查明每个扩展字段的业务含义

### 4.1 证据优先级

1. 用户明确指定的字段与业务要求。
2. 当前栏目后台模型配置，例如 `menu_field`、`model_field`。
3. 数据库真实记录，至少检查有值、空值和有关联的数据。
4. 同一栏目已经工作的模板、Controller 和工具方法。
5. 静态稿中区域的业务语义。

这些证据不一致时，先找出差异，不能只凭字段编号猜测。

### 4.3 栏目模型配置的读取

`OneshotEntity` 中：`menuField` 表示单篇栏目字段设置，`modelField` 表示内容模型字段设置。项目还有 `FieldOptionEntity`，可记录 `fieldName`、`fromName`、`title`、`isOpen`、`isEmail`、`isRequired` 等配置。

先读取少量配置并确定其实际序列化格式，再解析。不要看到 `setting` 是一串数字就把它误认成模型字段名。`t_fieldsetting` 的列结构也要先 `SHOW COLUMNS`；本项目该表没有 `class_id`，不能直接用它按栏目过滤。

### 4.4 先形成绑定对照表

每个页面建议保留如下工作记录：

| 区域/选择器 | 来源 | 查询条件 | 字段 | 排序/数量 | 跳转 | 空状态 |
|---|---|---|---|---|---|---|
| 横幅 | 栏目配置 | 配置栏目 ID | `photo1` | 单条 | 无 | 默认图或隐藏 |
| 标题 | 栏目配置 | 同上 | 经核实的单行字段 | 单条 | 无 | 由设计决定 |
| 关联图片 | 内容表 | 子栏目 + `xgid=info.id` | `photo1,title` | CMS 排序 | 无 | 暂无数据 |
| 新闻列表 | 内容表 | 子栏目 + 当前内容 ID | `title,tags,note1,createTime` | 按需求 | `getDetailUrl` | 暂无数据 |

没有核实的字段应标注“待确认”，不要填成看起来合理的假映射。

## 5. 数据库核对 SQL

以下示例以 MySQL 和本项目表名为例。先确认目标项目数据库类型。示例中的 `@menu_id` 等仅用于人工只读核对；应用代码必须使用 MyBatis 参数绑定。

### 5.1 连接与环境

1. 查看 `application.yml` 的激活 profile，再读取对应环境配置。
2. 确认实际数据库，不把开发库、测试库、生产库混为一谈。
3. 若配置启用了 Druid `config.decrypt`，配置中的密码可能是密文，应使用项目已有配置解密方式，不把密文直接当明文连接。
4. 不将口令、连接密文、私钥、生产主机信息复制进手册、日志、最终答复或提交文件。
5. 核对任务尽量使用只读连接和 `SELECT`，不改业务记录来“让页面有数据”。
6. 使用 Java 临时工具前确认 `java -version`：Java 8 不能直接运行 `.java` 文件，也没有 `Path.of`、`Files.readString`；应使用 `javac` 和 Java 8 兼容 API。

### 5.2 确认结构

```sql
SHOW TABLES LIKE 't_content%';
SHOW TABLES LIKE 't_oneshot';
SHOW COLUMNS FROM t_contents;
SHOW COLUMNS FROM t_oneshot;
SHOW COLUMNS FROM t_fieldsetting;
```

本项目已核实：`t_contents.content1` 为 `longtext`，`note1` 为 `mediumtext`，`photo1` 为 `varchar`，`class_id/xgid` 为 `int`，有效期字段为 `datetime`。因此 `note1` 也不能天然当作很短的摘要。

### 5.3 查栏目树及路由配置

```sql
SET @menu_id = 102; -- 本项目示例，下一项目替换
SELECT id, parent_id, class_name, sort,
       has_front_channel, is_loop, is_system, deleted,
       list_page_url, detail_page_url
FROM t_oneshot
WHERE id = @menu_id OR parent_id = @menu_id
ORDER BY parent_id, sort, id;
```

先不加前台过滤，观察为什么某个栏目没有显示；随后再用与 Mapper 完全相同的条件核对最终可见结果。

### 5.4 查字段模型，而不是猜字段

```sql
SET @content_class_id = 194;
SELECT id, class_name, menu_field, model_field
FROM t_oneshot
WHERE id = @content_class_id;
```

本项目核实的 `model_field` 是 JSON 数组。例如结构简化后：

```json
[
  {"fieldName":"title","title":"姓名","fieldType":"SingleLine","isOpen":"on"},
  {"fieldName":"singleLine2","title":"邮箱","fieldType":"SingleLine","isEmail":"on"},
  {"fieldName":"singleLine3","title":"职称","fieldType":"SingleLine"},
  {"fieldName":"photo1","title":"图片","fieldType":"Image"}
]
```

`fieldName` 用于实体/模板映射，`title` 是该栏目设置的业务标签。不能只看 `fromName`：它可能仍是扩展字段的原始名称。`defaultValue` 在某些栏目中可能带有 `{$139$}` 这类关联选择配置，必须结合后台解析代码确认，不直接把它显示到前台。

### 5.5 限量读取真实内容样例

```sql
SET @content_class_id = 196;
SET @owner_content_id = 210;
SELECT id, class_id, xgid, title, tags, photo1,
       single_line1, single_line2, single_line3, single_line4,
       LEFT(note1, 200) AS note1_preview,
       create_time, sort, status, deleted,
       entry_into_force_time, expiration_time
FROM t_contents
WHERE class_id = @content_class_id AND xgid = @owner_content_id
ORDER BY sort DESC, create_time DESC, id DESC
LIMIT 10;
```

避免初次检查就 `SELECT *`。如果只想确认是否有正文，可查询 `OCTET_LENGTH(content1)`，不要把完整正文传回工具输出。

### 5.6 查关联分布和孤立记录

```sql
SELECT class_id, xgid, COUNT(*) AS row_count
FROM t_contents
WHERE class_id IN (195, 196) AND deleted = 0
GROUP BY class_id, xgid;

SELECT child.id, child.class_id, child.xgid
FROM t_contents child
LEFT JOIN t_contents owner ON owner.id = child.xgid
WHERE child.class_id IN (195, 196)
  AND child.deleted = 0
  AND (owner.id IS NULL OR owner.class_id <> 150 OR owner.deleted <> 0);
```

孤立数据是否应该清理是另一项任务；绑定时不要擅自删除。

### 5.7 与前台完全一致的可见性核对

本项目普通内容前台查询一般使用：

```sql
WHERE class_id = @content_class_id
  AND xgid = @owner_content_id
  AND status = 1
  AND deleted = 0
  AND entry_into_force_time <= NOW()
  AND expiration_time >= NOW()
```

`NULL` 与 `NOW()` 比较不会返回真。因此有效期为 `NULL` 的内容可能被当前 SQL 排除。先检查数据和业务约定，不为显示记录随意放宽全站有效期条件。

### 5.8 统计内存风险，只返回数值

```sql
SELECT class_id, COUNT(*) AS row_count,
       SUM(COALESCE(OCTET_LENGTH(content1), 0)
         + COALESCE(OCTET_LENGTH(content2), 0)
         + COALESCE(OCTET_LENGTH(content3), 0)
         + COALESCE(OCTET_LENGTH(content4), 0)
         + COALESCE(OCTET_LENGTH(content5), 0)
         + COALESCE(OCTET_LENGTH(content6), 0)) AS rich_text_bytes,
       SUM(COALESCE(OCTET_LENGTH(note1), 0)) AS note1_bytes
FROM t_contents
WHERE class_id IN (141, 142) AND deleted = 0
GROUP BY class_id;
```

统计结果是数据库存储字节，不等于 JVM 最终占用。驱动缓冲、字符串、实体、排序、序列化和并发请求会带来额外开销。

## 6. 工具类方法与实际限制

### 6.1 模板调用规则

`CommonUtils` 注册为 `@Component("CommonUtils")`，所以调用形式为：

```html
<span th:text="${@CommonUtils.getTitleById(item.singleLine5)}"></span>
```

方法名大小写敏感：正确的是 **`getDetailUrl`**，不是 `getDetailurl`。模板工具不能凭空调用 Service 新加的方法；要么在 Controller 组装 Model，要么确实在工具类中暴露相应方法。

### 6.2 常用方法速查

| 方法 | 用途 | 必须知道的限制 |
|---|---|---|
| `getOneshotEntityById(id)` | 读取前台栏目实体 | 经 MenuService；前台可见性过滤可能返回 null |
| `getOneshotListByParentId(parentId)` | 下级栏目集合 | 含前台过滤与 `is_loop=1`；不是任意后台栏目集合 |
| `getOneshotListByParentId(parentId, limit)` | 有数量限制的下级栏目 | 核对限制发生在 SQL 还是 Java 层 |
| `getContentListByClassId(classId)` | 某栏目内容列表 | 本项目默认最多 **100 条**，不是“全部” |
| `getContentListByClassId(classId, limit)` | 指定数量 | 继续检查最终 SQL 是否读取大字段 |
| 四参数 `getContentListByClassId(classId, limit, setting, pageIndex)` | 指定推荐设置和页码 | 经过 Service 调用 `getArticlePageByClassId`；不要与同名 Mapper 误认 |
| `getContentListByClassIdAndRelationId(classId, relationId)` | 栏目与关联 ID 的内容列表 | 当前无 LIMIT 且读取 `fieldCommon`；可能很重 |
| `getContentListByRelationId(relationId)` | 只按关联 ID | 跨栏目读取，容易混入其他类型数据 |
| `getContentEntityById(id)` | 获取单条内容 | 存在发布/有效期过滤及点击量更新等行为，不能当纯数据库只读操作 |
| `getContentEntityById(classId, id)` | 校验栏目归属后取内容 | 比只按 ID 更适用于详情路由 |
| `getContentListByIds(ids)` | 按一组 ID 读取 | 查实现的解析、过滤和排序；不要假设保持传入顺序 |
| `getTitleById(String id)` | 单个关联内容的标题 | 内部整数转换；非数字/多选字符串可能报错 |
| `getDetailUrl(content)` | 内容详情/外链 | 必须读取下节实际分支行为 |
| `getListUrl(oneshot)` | 栏目地址 | 空栏目或空地址时返回占位链接，支持替换地址参数 |
| `lineToBr(...)` | 文本换行、段落包装 | 属于 CommonUtils，返回 HTML，不负责 HTML 消毒 |
| `getPaginationUrl(...)` | 组装分页地址 | 配合 `paginationParams` 保存筛选条件 |

### 6.3 `getDetailUrl` 的真实分支

截至本手册整理时，当前实现为：

1. 内容实体不为 null。
2. `singleLine4` 非空时，直接返回该外链。
3. `file1` 非空时进入附件分支，但该分支中的 `return file1` **被注释了**，最终可能返回 `javascript:void(0);`。
4. 其余情况读取内容所属栏目，取得 `detailPageUrl`。
5. 栏目存在且详情模式为空时，使用 `/content/details{classId}_{id}.html`。
6. 替换 `{classId}`、`{id}`、`{pageIndex}`，返回地址。
7. 无法得到有效地址时返回 `javascript:void(0);`。

这是本项目实现现状，不是推荐将附件功能设计成不可点击。**不能只依据方法注释声称“有附件自动下载”。** 若新项目需要附件下载，要先验证工具的具体实现；本项目另有 `getDetailCommonUrl`，其行为也要独立阅读，不能未经业务确认随意互换。

轻量查询如果要使用 `getDetailUrl`，至少要保留 `id`、`class_id`、`single_line4`，并考虑 `file1` 对现有分支的影响。只查询 `id,title` 可能使地址生成失败或改变既有外链/附件语义。

### 6.4 前台栏目服务和通用栏目服务不同

- `CommonUtils.getOneshotEntityById` → `MenuService.getModelById` → `MenuMapper`。
- 页面 Controller 常用 `oneShotService.getModelById` → `OneshotMapper`。

本项目前者包含 `deleted=0`、`is_system=0`、`has_front_channel=1` 等前台过滤；后者的对应 SQL 仅按 `id`、`deleted=0` 查询。查询不到栏目时必须看调用链，而不是认为“数据库有这个 ID 就一定能取到”。

### 6.5 本项目新增的轻量查询

这些方法属于 `InfoService` 和 `InfoMapper`，**不是当前 CommonUtils 的方法**：

| 方法 | 查询范围和字段 |
|---|---|
| `getCooperationSchools()` | 本项目院校 141；`id,class_id,title,note1,single_line1,single_line4` |
| `getCooperationCourses()` | 本项目课程 142、`xgid>0`；只取链接/关联/日期字段，按发布时间倒序 |
| `getRelatedContentSummaries(classId, relationId)` | 精确栏目与关联；`id,class_id,xgid,title,photo1,tags,note1,single_line4,file1` |

最后一个方法不返回正文，也不返回所有扩展字段。若模板新增邮箱、职务、日期等展示，应明确补充对应列或另建适合的查询，不能让字段保持 null 后误以为数据库没内容。

## 7. Controller、Model 与详情路由

### 7.1 常用模型属性

| 属性 | 本项目通常含义 | 注意 |
|---|---|---|
| `view` | 默认渲染/预览使用匿名聚合对象，含 SEO、header/footer 等；自定义路由以真实注入类型为准 | 公共模板依赖它 |
| `idDTO` | 页面身份信息 | 由 BaseController 加入 Model |
| `itemMenu` | 当前页面的单篇/栏目配置 | 不是当前内容记录，也不必与列表 classId 相同 |
| `info` | 当前详情内容 `ContentEntity` | 子列表的“当前 ID”通常是 `info.id` |
| `bannerClassId` | 详情相关栏目标识 | 按实际公共片段使用情况设置 |
| `researchPager`、`eventsPager` 等 | 当前列表分页对象 | 必须与模板变量一致 |
| `schools`、`coursesBySchool` | 院校列表与按院校分组课程 | 由 ResearchController 组装 |
| `communityPhotos`、`communityActivities` | 社群附属图片/活动列表 | 由 classId=150 的详情分支组装 |

同一变量名在不同模板中可有不同来源。实施前列出当前模板引用的全部模型属性并逐一核对。

### 7.2 简单页面与复杂页面的分工

- 单次、小数据量、已存在合适工具方法：可以在模板调用工具类。
- 分页、多筛选、多层分组、轻量字段投影、需要查询共享：优先 Controller → Service → Mapper → Model。
- 不在模板内对同一个方法重复调用取列表、判空、计数；先 `th:with` 保存一次，或在 Controller 保存。
- 不为了少改 Java 而把复杂 SQL 或大量数据处理塞进模板。

### 7.3 详情路由闭环

1. 使用 `infoService.getArticleById(classId, id)` 获取内容并校验栏目归属。
2. 不存在或当前不可见时抛出 404。
3. 加入 `info`、`view`、`bannerClassId`。
4. 根据 `classId` 分支返回模板，并按需加入附属列表/栏目配置。

新增一种内容时，必须一起检查：

- 数据库中的 `detail_page_url` 能否生成目标 URL。
- 模板是否通过 `getDetailUrl` 使用该模式或外链。
- Controller 是否有对应 `classId` 分支。
- 详情模板所需的属性是否齐全。
- 面包屑、返回链接、相关内容是否属于该业务。

### 7.4 面包屑与返回链接

若没有有效主记录 ID，使用经过确认的栏目入口作为回退，不生成 `details191_null.html`。对于未明确要求修改的其他业务标题，不擅自批量更名。

### 7.5 公共页面方法并非没有副作用

`BaseController.getCommonVo` 会组装 SEO、页头页尾，并更新栏目访问量；内容读取还可能异步更新内容点击量。需要严格只读核对时直接使用只读数据库查询。用 HTTP 访问页面做功能验证时，应知道正常业务访问计数可能变化。

## 8. 查询、排序、分页与性能

### 8.1 默认过滤与排序

| 查询 | 本项目规则 |
|---|---|
| 前台子栏目 | `parent_id` + `is_loop=1` + 未删除、非系统、前台显示 |
| 子栏目排序 | `sort ASC, id ASC` |
| 普通内容 | 已发布 `status=1`、未删除 `deleted=0`、当前有效期 |
| 普通内容排序 | `sort DESC, create_time DESC, id DESC` |
| 合作院校的最近课程 | 专用 SQL：`create_time DESC, id DESC` |

栏目排序与内容排序的 `sort` 方向不同，不得凭经验统一改成同一个方向。

“最近 7 条”通常首先按发布时间，而 `sort DESC` 会让一条旧的置顶内容排在前面。若用户指定“最近”，应明确该需求与后台排序号的关系，按已确定的规则实现。

### 8.2 关联查询必须使用参数绑定

```xml
<select id="getRelatedContentSummaries" resultMap="customMap">
    select id, class_id, xgid, title, photo1, tags, note1, single_line4, file1
    from t_contents
    where class_id = #{classId} and xgid = #{relationId}
    <include refid="whereCommon"/>
    <include refid="validityPeriod"/>
    <include refid="orderCommon"/>
</select>
```

对应 Mapper 参数必须与 SQL 一致：

```java
List<ContentEntity> getRelatedContentSummaries(
    @Param("classId") Integer classId,
    @Param("relationId") Integer relationId);
```

`#{...}` 是绑定参数；`${...}` 是 SQL 文本拼接，不应替代它用于用户输入。新增动态排序字段必须做明确的字段白名单，不直接拼请求参数。

上述 `<include>` 是本项目片段名，下一项目要确认存在。`orderCommon` 当前同时包含状态条件和 `ORDER BY`，不仅仅是排序，复制时要检查拼接后的完整 SQL。

### 8.3 空 relationId 的危险

旧的 `getContentListByClassIdAndRelationId` 对 `relationId != null` 才加入 `xgid` 条件。传 null 意味着“不按关联过滤”，不是“查询无关联的数据”。

- 查询当前院校课程却传入 null，会查出该栏目全部课程。
- 两个参数都传 null 可能扩大为全表范围，甚至因动态 WHERE 与 SQL 片段拼接产生语法问题；不得用于正常页面。
- 前端筛选的空值是否应忽略，与主从数据的关联 ID 是否必填，是两个问题。
- 必需的关联查询优先写成精确条件，确保缺失 ID 时不会意外返回全部记录。

### 8.4 列表字段投影

列表只选真正需要的列。典型字段集合：

- 图片列表：`id,class_id,xgid,title,photo1`。
- 普通新闻卡片：上述身份字段 + `title,tags,note1,create_time,single_line4,file1`。
- 联系人：身份字段 + 实际姓名、照片、职务、英文名、邮箱、链接字段。
- 完整详情：按单个 ID 获取正文和详情所需字段。

使用 `ContentEntity` 承载投影时，没有选取的属性会为 null 或实体初始化默认值。例如 `createTime` 在本实体上有初始化值，若 SQL 没选日期却模板使用它，可能显示错误的当前时间。**必须选出模板使用的日期，不能依靠实体默认值。**

### 8.7 分页和筛选

优先参考 `ResearchController.index/insights` 及 `ContactController.community`。

- 使用 `InfoService.getContentPager(...)` 得到 Pager。
- 传递 `pager.rows` 给模板，而不是把 Pager 当 List。
- 本项目分页 Service 会处理空页码、负数页码和超出末页的情况；传回模型时使用归一化后的页码。
- 统一片段 `web/inc/pagination` 依赖 `paginationParams`；无筛选时也应初始化为空 Map，或确认片段可处理缺失值。
- 总数 SQL 和列表 SQL 必须使用相同过滤条件。
- 多选筛选项在翻页和跳页表单中都应保留。
- 若过滤由逗号列表等构成，使用已有安全解析逻辑，不能自行用字符串拼 SQL。

```html
<th:block th:replace="~{web/inc/pagination :: pagination(${researchPager}, '/research/index.html')}"></th:block>
```

## 9. Thymeleaf 模板绑定规则

### 9.1 基础指令

| 指令 | 作用 | 常见错误 |
|---|---|---|
| `th:text` | 输出转义文本，替换节点内文本 | 把应渲染的 HTML 当普通文本输出 |
| `th:utext` | 输出未经转义的 HTML | 把未知来源文字直接作为 HTML 输出 |
| `th:each` | 对当前元素循环 | 未确认数据类型或循环变量作用域 |
| `th:if` / `th:unless` | 决定是否生成节点 | 只清空文字却保留空标签背景 |
| `th:with` | 定义局部变量 | 在兄弟节点或父节点引用局部变量 |
| `th:href` / `th:src` | 动态链接或图片 | 仍保留失效 `.aspx` 路径作为实际跳转 |
| `th:style` | 动态样式 | 只改 img，不改 CSS 背景图 |
| `th:classappend` | 在原 class 后追加 | 用 `th:class` 意外覆盖轮播必需类 |
| `th:attr` | 动态 data-* 等属性 | 在表达式内拼接引号造成解析错误 |
| `th:replace` | 用片段替换当前节点 | 把片段参数当全局变量，或放错 DOM 位置 |
| `th:insert` | 插入片段 | 忘记公共片段还依赖 Model |
| `th:block` | 逻辑容器，不输出额外 DOM 元素 | 误认为最终页面也会保留这个节点 |

同一元素上的处理顺序由 Thymeleaf 决定，不取决于属性书写顺序。本项目已验证可在循环项同一元素上结合 `th:each` 与引用循环变量的 `th:with`/`th:if`；作用域复杂时分层 `th:block` 更容易核对。

### 9.2 单行和多行文本

```html
<h3 th:text="${info.title}">标题</h3>
<p th:utext="${@CommonUtils.lineToBr(info.note1)}"></p>
```

`CommonUtils.lineToBr(str)` 当前实现：将 CRLF、CR、Tab 统一为换行，再将换行替换为 `<br/>`；空白内容返回空字符串。它不会转义或净化 HTML。

对于普通不可信文本，需要在保持换行的同时转义，可考虑 `th:text` 配合局部 `white-space: pre-line`，或明确的转义后格式化方法。后台可信富文本使用 `th:utext`，但不要据此认为任何请求参数都可用 `th:utext` 输出。

### 9.3 按行包装段落或列表项

```html
<div th:utext="${@CommonUtils.lineToBr(info.note2, '&lt;p&gt;', '&lt;/p&gt;')}"></div>

<!-- 本项目社群简介的最终要求：每段后额外换行 -->
<div th:utext="${@CommonUtils.lineToBr(info.note2, '&lt;p&gt;', '&lt;/p&gt;&lt;br&gt;')}"></div>

<ul th:utext="${@CommonUtils.lineToBr(info.note3, '&lt;li&gt;', '&lt;/li&gt;')}"></ul>
```

`lineToBr(str, startHtml, endHtml)` 对每行包上前后缀，还支持把前缀里的 `{index}` 替换为从 1 开始的序号。其空行和末尾换行行为取决于 Java `split("\n")`，不要擅自假设会跳过全部空行。

现有两参数重载 `lineToBr(str, "p")` 也可包成 `<p>...</p>`；若需要 `</p><br>` 或带样式前缀，应使用三参数版本。

HTML 属性内的 `<`、`>`、嵌套引号建议用 `&lt;`、`&gt;`、`&quot;`，与项目现有复杂模板保持一致。此写法用于清晰和兼容性，不应笼统声称所有字面 `<p>` 都会让 Thymeleaf 解析失败。

段落外层应为 `<div>`，不要在 `<p>` 内生成另一组 `<p>`。如 DOM 中已经有正确的 `<p>` 但看起来没有段间距，检查 CSS 的 `margin`、`display`、`line-height`；不能把样式问题当作数据没绑定。

### 9.4 富文本正文

```html
<div class="edit_con_original" th:utext="${info.content1}"></div>
```

正文容器的原有排版类可能负责图片、表格、段落样式，应保留。无需先经过 `lineToBr`，否则已有 HTML 排版可能被意外插入换行。

### 9.5 空字段不输出整个节点

```html
<span class="research_inx_2_time fnt_16"
      th:if="${!#strings.isEmpty(#strings.trim(forum.singleLine6))}"
      th:text="${forum.singleLine6}"></span>
```

这样会同时处理 null、空字符串、普通空白，并移除时长标签背景等装饰。只使用 `th:text` 会留下一个空的 `span`。

若字段可能包含全角空格、`&nbsp;` 或只有 `<p><br></p>` 等编辑器占位 HTML，需要单独定义“业务上为空”的判断规则。`trim` 并不等于识别所有形式的空内容。

### 9.6 空列表必须在循环外处理

正确模式：

```html
<div th:with="ui=${@CommonUtils}, items=${ui.getContentListByClassIdAndRelationId(192, info.id)}">
    <th:block th:replace="~{web/inc/empty-state :: emptyState(${items})}"></th:block>
    <ul th:if="${!#lists.isEmpty(items)}">
        <li th:each="item:${items}">
            <a th:href="${ui.getDetailUrl(item)}" th:text="${item.title}"></a>
        </li>
    </ul>
</div>
```

上例演示模板结构；大数据列表应换为轻量查询得到的模型变量。

错误模式：

```html
<li th:each="item:${items}">
    <th:block th:replace="~{web/inc/empty-state :: emptyState(${item})}"></th:block>
</li>
```

原因有两个：列表为空时循环根本不会执行；片段期待 List，却传入单条实体。

`empty-state` 输出的是 `<div>`，应放在 `<ul>` 外面，避免非法的列表子节点结构。轮播为空时也应隐藏对应轮播容器，避免 JS 初始化一个没有内容的组件。

### 9.7 日期、序号和第一项

```html
<li class="swiper-slide" th:each="photo,status:${communityPhotos}"
    th:classappend="${status.first ? 'on' : ''}">
    <em th:text="${#numbers.formatInteger(status.count, 2)}">01</em>
    <img th:src="${photo.photo1}" th:alt="${photo.title}">
</li>

<span th:if="${news.createTime != null}"
      th:text="${#temporals.format(news.createTime, 'dd')}"></span>
<em th:if="${news.createTime != null}"
    th:text="${#temporals.format(news.createTime, 'yyyy.MM')}"></em>
```

- `status.index` 从 0 开始，`status.count` 从 1 开始。
- 两位格式表示至少两位，100 不应被截成 00。
- `LocalDateTime` 使用 `#temporals`；不要随意换成针对 `Date` 的 `#dates`。
- 保留 `swiper-slide` 等静态 class，第一项用 `th:classappend` 追加。

## 10. 图片、视频与前端行为

### 10.1 图片不是只改 `src`

同一横幅可能同时包含：

```html
<div class="site_banner bg-cover" style="background-image: url(静态图);">
    <img class="ratio-img" src="静态图">
</div>
```

其中 `<img>` 可能承担高度/比例占位，真正可见的是背景图。只绑定 `th:src`，会出现“后台换图但页面仍显示旧图”。

本项目采用的写法：

```html
<div class="site_banner bg-cover"
     th:with="bannerPhoto=${#strings.isEmpty(itemMenu.photo1) ? ui.resolveUiUrl('assets:/global/images/cop_banner.jpg', siteId) : itemMenu.photo1}"
     th:style="${'background-image: url(' + bannerPhoto + ');'}">
    <img class="ratio-img" th:src="${bannerPhoto}" th:alt="${itemMenu.className}">
</div>
```

前提是 `itemMenu` 已被控制器正确提供。字段为空的兜底与整个对象为空是两回事，不能用上例解决缺失 Model 的问题。

其他要求：

- 栏目图用 `itemMenu.photoN`，内容图用 `info.photoN` 或循环项的 `photoN`，按真实数据源选择。
- 保留 `ratio-img`、`data-ratio`、`bg-cover` 等已有布局钩子。
- 添加正确的 alt；纯装饰图按设计使用空 alt。
- 默认图是明确的显示策略，不能把不相关业务图当作永久兜底。
- 图片地址如可能包含空格、引号或特殊字符，必须验证实际 CSS URL 渲染及地址编码，不要把输入直接拼成任意 CSS。

### 10.2 视频是否存在必须按业务字段判断

本项目研究院列表的最终要求：**只看 `forum.note1`，不判断 `media1`**。

```html
<span class="research_inx_2_play"
      th:if="${!#strings.isEmpty(#strings.trim(forum.note1))}">
    <i class="icon-yousj fnt_50"></i>
</span>
```

下一项目可能使用 `media1`、视频地址、嵌入代码或外部播放器 ID，应从字段模型和播放脚本确认，不沿用本项目的 note1 约定。

“有播放图标”与“点击直接弹出播放器”不是同一需求。本项目此处位于详情链接内部，改变显示条件不会自动增加视频弹窗行为。除非任务明确要求，不修改原有点击流程，也不恢复用户注释掉的详情播放器区块。

### 10.3 始终显示与悬停显示

原 CSS 中可能同时存在：

```css
.research_inx_2_play { display: flex; /* ... */ display: none; }
.research_inx_2_list li:hover .research_inx_2_play { display: flex; }
```

后一个 `display:none` 覆盖前面的 flex。要实现“有视频一直显示、没视频不显示”：

1. 用 `th:if` 决定是否生成按钮。
2. CSS 保留正常 `display:flex`，移除默认隐藏和仅 hover 显示的规则。
3. 搜索移动端 media query 是否还有覆盖。
4. 必要时更新该页面 CSS 版本参数，避免浏览器缓存旧规则。

不要仅靠 `:hover` 让服务器根本不应该输出的按钮临时消失。

### 10.4 属性绑定与脚本接口

视频、弹窗、轮播脚本可能读取 `data-video-html`、`data-src`、`data-course-list` 等属性。必须按实际 JS 的读取方式提供值：

```html
<a href="javascript:void(0);"
   th:attr="data-course-list=${'school-courses-' + school.id},data-course-title=${school.title + ' · 课程总览图文'}">
    课程回顾链接
</a>
```

不要直接把后台字符串拼进内联 JavaScript。能通过正常 HTML 属性传递就用属性传递；确实需要 JS 数据时使用 Thymeleaf 的 JavaScript 内联机制和正确序列化。

### 10.5 替换静态列表时的结构保护

- 保留轮播容器层级、按钮结构和插件约定的 class。
- 删除同一块中多余的示例项，让一份项模板参与循环。
- 不在动态项后保留一个“看起来完整”的静态示例，避免混入假数据。
- 单条、零条、多条都要检查。脚本可能按数量切换滑动和箭头显示。
- 未知是否仍使用的 `.aspx`、日期备份 HTML 先查全项目引用和 Controller，不凭后缀直接删除。
- 删除源资源后，旧 `target/classes` 或服务器外置模板仍可能残留；清理对应产物或重新构建，不能因源码已删就断言运行时已删。


## 维护说明

本手册记录的是整理时的代码实现与已核实数据。项目后续若修改字段模型、路由、查询工具、缓存或模板引擎版本，应同步更新相关章节。维护时应优先写清“实际行为”和“证据来源”，避免把一次项目中的字段用法变成错误的全局规则。

本次关键证据来自：上述源码路径、实际 `t_oneshot.model_field` JSON、`information_schema.COLUMNS` 字段类型核对，以及本项目历次页面绑定和轻量查询验证。
手册没有携带数据库连接凭据；在新项目使用时，AI 必须按重新核对环境和数据。


### 新增文本业务字段（2026-11）

在字段配置中新增单行、多行纯文本或富文本时，业务显示名与稳定的`ext_`字段标识分开保存。配置继续使用栏目`modelField`（内容记录）或`menuField`（栏目单页），值分别存入既有`t_contents`或`t_oneshot`的`extension_fields` JSON列；旧槽位字段与旧模板保持原读取方式。升级前须备份数据库，再执行`database/20261108_新增单行业务扩展字段.sql`，不要对既有库执行初始化脚本。

模板通过实体的`extensionValues`访问新增字段，例如`th:text="${content.extensionValues['ext_0123456789abcdef0123456789abcdef']}"`；单页栏目实体同样具有`extensionValues`。实际变量名沿用目标路由注入的Model，不要直接打开HTML替代真实渲染。可用`?: ''`处理旧记录的空值。新增文本字段仅支持手动录入与必填校验；单行上限500字、多行纯文本上限10000字、富文本HTML源码上限200000字符。新增字段筛选、排序、搜索和生命周期操作由后续功能提供。停用定义保留既有JSON值，不能通过停用删除历史数据。


多行简介使用`th:text`输出，并在目标正文容器设置`white-space: pre-wrap`保留换行；纯文本中的HTML标签按文字展示。富文本正文使用`th:utext`输出服务器保存后的值，仅替换目标正文容器，不能替换页面头、导航或整页模板。例如：

```html
<p style="white-space: pre-wrap" th:text="${content.extensionValues['ext_1123456789abcdef0123456789abcdef']}"></p>
<article th:utext="${content.extensionValues['ext_0123456789abcdef0123456789abcdef']}"></article>
```

扩展正文保存与字段默认正文均经过HTML白名单清洗：允许段落、常用标题、强调、列表、引用、代码、表格、链接和图片，保留文本对齐、受限字号/颜色/强调等内部排版；移除脚本、事件属性、iframe、表单、SVG、整页样式及定位/尺寸等布局CSS。空正文仍为空。逻辑资源`assets:/upload/...`、`assets:/global/...`、`ui:/...`保持可用；保存时沿现有资源规则规范化，最终页面输出仍由`ResourceHtml.render`解析当前站点地址。旧固定槽位控件与读取方式保持兼容，清洗仅作用于新增Editor正文值及其默认HTML。


资源解析的实际动态输出边界为`SiteHttpServer`栏目/详情路由 → `TemplatePreviewService.renderTemplatePreviewWithIdEntity` → Thymeleaf处理整页 → `ResourceHtml.render(HTML, projectCode)`；自定义路由`SiteRouteContext.render`也通过`renderWithCustomContext`进入同一资源解析。因此上述动态路由模板可直接`th:utext`输出已清洗的Editor值，整页输出时会解析逻辑引用。实体`extensionValues`本身仅读取JSON，不自动反向调用资源服务；独立创建模板引擎或原样响应静态文件不会自动获得这条解析链，不能据此宣称逻辑引用已经变成HTTP地址。


### 新增图片、视频和附件业务字段

新增业务字段可选择CMS既有`Image/Video/File`，复用原有上传与资源选择控件；每字段维护一个资源字符串，不增加新资源大类、数组存储或业务表。控件原有多资源兼容读取（JSON数组/换行/逗号/分号）继续保留在旧字段路径，本次新增三类型不开放多资源。资源字段长度上限2048，默认值留空，在内容编辑器选择和替换。

新值只允许当前站点上传目录、当前UI包或全局素材库中实际存在且扩展名符合配置类型的文件。保存为`assets:/upload/...`、`assets:/global/...`或`ui:/...`逻辑引用；拒绝无法核验归属的外链、跨站点URL、路径逃逸、缺失或不匹配的文件，不发起外网HEAD请求。空值保存为空字符串；原有固定资源槽位保持兼容。

公开动态路由仍经整页`ResourceHtml.render`解析资源地址；模板对空值添加条件，避免输出无效链接。例如（标识替换为实际字段）：

```html
<img th:if="${!#strings.isEmpty(content.extensionValues['ext_0123456789abcdef0123456789abcdef'])}"
     th:src="${content.extensionValues['ext_0123456789abcdef0123456789abcdef']}" alt="图片">
```

视频使用同样的空值条件与`<video controls th:src="...">`，附件使用`<a th:if="..." th:href="...">下载附件</a>`。编辑器继续沿用已有图片/视频加载失败提示，保存资源时缺文件会返回明确错误；文件被后续删除不会自动删除已存业务值，需重新选择或清空。上传/浏览资源和显式资源上下文均按真实站点校验账号访问权限，全局素材沿用现行访问规则。


## 扩展字段的公开结构化筛选（可信模板显式允许）

后台`isSearch`和匿名全文`publicSearch`都不代表公开字段可探测。模板必须固定声明allowedFilterKeys/allowedSortKeys，再与当前栏目模型启用及列表/筛选许可交叉；不得把请求参数名或请求提供的列表当权限依据。privateNote即使允许后台查询，未在模板允许列表中声明也不能等于、为空或排序探测。

旧四参数bizcode+filters列表方法、旧Pager的pagePath stem语义不变。新结构化重载要求服务器已bindServerSite的IdEntity：分页参数为bizcode、当前站点ID、受信IdEntity、固定键条件Map、允许filter列表、允许sort列表；列表参数为bizcode、受信IdEntity、limit、条件Map及两列表。仅传合法siteId、new未绑定分页或setter改站点都不产生服务器身份。

栏目列表路由沿真实listPageUrl生成，不猜list_parent_class或无条件加.html；html、aspx、目录及{pageIndex}均保持原路由，链接以/开头避免嵌套目录重复。新重载忽略旧pagePath stem，first/prev/next/last和pageHtml保留编码的筛选/排序和既有ConditionVo值；动态页码走路径，静态路由用pageIndex查询参数。

新交互筛选通路显式携带`__cms_filter=1`，其他通用请求不触发此参数绑定。服务器仅在该列表分支接收最多64键、键128字符、单值1000字符、合计16KB UTF-8数据；重复参数一律拒绝，客户端不能重绑站点/栏目等路由身份。动态路径页码优先且冲突拒绝。现有key/keywords/type/area/year/month/yearString/classIds承接到原ConditionVo，pageIndex用现有分页属性；其他值由只读queryValues提供，没有通用Request对象或方法。queryValues无setter、JSON禁写且需服务器身份才能绑定。

以下示例中siteId和idEntity由既有站点服务器提供，不能从请求新建。技术key须换成本栏目真实字段；表单按固定键取值，不整体传queryValues。GET提交用真实第一页URL重置页码，避免在第二页筛新条件后得到空页：

```html
<section th:with="ui=${@CommonUtils},
  products=${ui.getContentPagerByClassBizcode('products', siteId, idEntity,
    {'ext_00000000000000000000000000000001': idEntity.queryValues['ext_00000000000000000000000000000001']},
    {'ext_00000000000000000000000000000001'},
    {'ext_00000000000000000000000000000001'})}">
  <th:block th:if="${products != null}">
    <form method="get" th:action="${products.firstUrl}">
      <input type="hidden" name="__cms_filter" value="1">
      <input type="hidden" name="pageIndex" value="1">
      <input name="ext_00000000000000000000000000000001"
        th:value="${idEntity.queryValues['ext_00000000000000000000000000000001']}">
      <button type="submit">筛选</button>
    </form>
    <article th:each="product : ${products.rows}">
      <h3 th:text="${product.title}"></h3>
      <p th:text="${product.extensionValues['ext_00000000000000000000000000000001']}"></p>
    </article>
    <p th:text="${products.total}"></p>
    <div th:utext="${products.pageHtml}"></div>
  </th:block>
</section>
```

同一字段操作：普通文本默认字面contains，可op_<key>=eq；单选按完整值等于，多选传`,a,b,`或文本集合，any/all按完整成员且区分大小写。日期用startTime_<key>/endTime_<key>精确到秒；empty/notEmpty区分缺key、JSON null、空串，完整空格和文本null均不是空。排序必须同时在模板sort允许列表和模型列表许可内，asc/desc空值最后，同值id倒序。普通文本仍按字典序，不隐式转数字。

公开读取固定已发布、未删除、有效期内、当前站点真实所属且开放前台的内容栏目。旧NULL site仅按所属栏目兼容，显式异站拒绝。返回摘要不加载旧content1至content6富文本列；没有新增HTTP端点。未知bizcode沿原工具约定返回null，模板先判断；真实路由缺失或外链不能用猜测URL替代。


### SingleLine的isNumber数值查询

现有手动SingleLine字段启用isNumber时，可在同一后台模型双授权和可信模板固定允许列表内使用数值条件；不新增字段类别，不改变保存、展示或历史数据。0和前导零保持原值。数字查询参数须为完整ASCII非负整数，去前导零后最多65位；拒绝符号、小数、指数、单位、空白和超界查询值，支持eq/gt/ge/lt/le，默认eq。min_<key>/max_<key>为含端点范围，可组合文本、枚举、日期条件；模板须把该稳定key本身放入固定filter允许列表，不是把min_/max_前缀作为额外授权。

数值排序先列出范围内数字，再列超65有效位旧值、其它旧脏值，最后是真正空值；范围内按数值asc/desc，同值id倒序。缺key、JSON null和空串为空；空白、JSON数字/对象、带单位旧值不转成0也不冒充空值。旧超界/脏值仍可保存、显示及通过notEmpty看到，数值比较不命中这些值。未启用isNumber的文本和固定枚举保留各自原有语义。

交互表单按固定键读取paging.queryValues['min_<稳定key>']、paging.queryValues['max_<稳定key>']，与已有__cms_filter=1、pageIndex=1及固定允许列表组合。数字输入使用文本值，避免浏览器浮点精度损失；不能用JavaScript Number转换长整数。

当前数值条件由JSON字段逐行校验和安全DECIMAL转换，不自动创建索引。仅在真实站点数据分布、查询频率和EXPLAIN证明需要后，按具体稳定字段另行评估受控生成列/索引迁移；通用JSON索引不能保证覆盖该安全转换表达式，不把隔离合成数据计划当线上性能承诺。
