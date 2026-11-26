# Hongru CMS

Spring Boot 4.1.1、JDK 21 和 Vue 3。后端采用 Maven 多模块结构，`cms-boot` 是唯一应用入口，可执行产物为 `cms-boot/target/xhr-cms-1.0-SNAPSHOT.jar`。

## 工程结构

| 目录 | 职责 |
| --- | --- |
| `cms-contracts` | 共享实体基类、DTO、内容字段定义、业务接口与模块间端口 |
| `cms-security` | 管理员／会员 Session 身份、鉴权、角色校验、CSRF 与密码摘要 |
| `cms-ai` | Spring AI 文本生成、任务管理和外部 OpenCode Agent 适配 |
| `cms-core` | 栏目、内容、站点主数据、业务服务、Mapper/XML、数据库与缓存实现 |
| `cms-site` | 模板渲染、站点 HTTP 服务、发布、表单接口和站点路由扩展 |
| `cms-admin` | 管理 API、后台控制器、基础账号会话与请求拦截 |
| `cms-boot` | `CmsApplication`、环境配置、Web 装配、模板／静态资源与可执行打包 |
| `admin-ui` | Vue 管理端源码和 Node 构建配置 |
| `database` | 初始化与人工执行的增量 SQL |
| `runtime` | 保留在 Git 的真实站点模板、UI 包和业务资源 |
| `docs` | 架构、运行说明与验收记录 |

各模块使用标准 `src/main/java`、`src/main/resources`、`src/test/java` 目录。父 POM 管理版本和构建规则，业务模块只产出普通 JAR。`target/`、`node_modules/`、前端生成文件、上传目录、日志、IDE 设置和本地备份均不进入 Git。

## 构建与测试

所有命令在仓库根目录执行。每次编译、测试或启动 Java 前，在同一个 shell 通过 SDKMAN 选择 JDK 21：

```sh
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 21.0.12-tem
```

管理端的 `admin-ui/package-lock.json` 仅保留在本地，不提交 Git。首次拉取项目或 `admin-ui/package.json` 变化后安装前端依赖：

```sh
npm --prefix admin-ui install
```

本地已有与 `package.json` 一致的锁文件时，可使用 `npm --prefix admin-ui ci` 按本地锁定版本重装依赖。

### 多模块并行构建

Maven Reactor 会根据模块依赖图调度构建。使用 `-T` 可以让当前已满足依赖的模块并行执行；例如 `cms-security` 与 `cms-ai` 可以并行，`cms-core` 会等待它们完成，最终 `cms-boot` 会等待全部依赖模块完成。

本项目虽然有 7 个后端模块，但受依赖链约束，主要可并行分支是 `cms-security` 和 `cms-ai`。实测使用 2 个 worker 能覆盖当前有效并行度，推荐固定为 `-T 2`，避免高核机器上 `-T 1C` 创建过多编译器和测试进程。

日常迭代不要执行 `clean`，让 Maven 编译器复用 `target/` 中未变化的输出：

```sh
# 后端增量测试/打包
mvn -T 2 test
mvn -T 2 package
```

发布或完整交付验收时，分别生成 standalone 管理端和后端可执行 JAR：

```sh
npm --prefix admin-ui run build
mvn -T 2 clean package
```

`admin-ui/dist/` 由 Web 服务器独立部署，不拷贝到 `cms-boot`，也不进入后端 JAR。后台静态资源统一迁至 `admin-ui/public/static/`，Maven 显式排除遗留的 `static/**`，防止本地旧产物进入包体。

Maven 也支持 `-T 1C`（每个可用 CPU 核心一个 worker）和 `-T 0.5C`，但本项目的依赖图无法利用大量 worker。线程数远高于可并行模块数通常没有收益，反而会增加内存占用、日志交错和测试时序风险。

`-T` 只并行当前已满足依赖的 Reactor 模块，不会绕过依赖，也不会让同一模块的生命周期阶段乱序执行。如果出现难以定位的测试冲突、共享文件竞争或内存压力，使用不带 `-T` 的 `mvn clean package` 串行复现；不要直接用 `-DskipTests` 掩盖失败。

修改单个模块时可只构建该模块及其上游依赖：

```sh
# -pl 选择模块，-am 自动加入它依赖的 reactor 模块
mvn -T 2 -pl cms-core -am test

# cms-boot 依赖全部业务模块；该命令会构建完整后端依赖链
mvn -T 2 -pl cms-boot -am package
```

修改 `cms-contracts` 等底层公共契约时，应执行完整的 `mvn -T 2 test`，覆盖所有下游模块，而不是只测试契约模块。

### 验证范围

`mvn -T 2 test` 执行不依赖外部服务的全部后端回归测试；`npm --prefix admin-ui run typecheck` 单独检查前端类型。管理端当前没有测试脚本或 Vitest 依赖，前端构建只验证类型和打包。

真实 dev 环境集成验证需要配置的数据库与 Redis 可访问，并遵循正常应用启动生命周期：

```sh
mvn -T 2 -Dcms.dev.integration=true test
```

该测试启动随机 HTTP 端口，验证数据库、MVC 路由、管理端登录入口和 Redis Session Cookie，临时会话使用独立命名空间并在结束时删除。standalone 管理端通过 `npm --prefix admin-ui run build` 独立验证。基础会话回归覆盖登录轮换、身份域隔离和注销。

### 构建性能与包体积

- 父 POM 显式启用增量编译，并把注解处理器限制为 Lombok 与 Spring Boot Configuration Processor，避免 javac 扫描整个编译类路径寻找处理器。
- 日常构建保留各模块 `target/`；只有发布验收、缓存异常或需要验证完全可复现构建时才使用 `clean`。
- `cms-boot` 是唯一执行 Spring Boot repackage 的模块，其他业务模块只生成普通 JAR。
- Playwright 是包内最大的单项依赖。package 阶段会把官方多平台 driver bundle 裁剪为 Linux x64，并校验 Boot layers；不能删除这一步或把未裁剪的多平台驱动直接交付。
- 默认四个应用环境当前都使用 MySQL，因此默认包只携带 MySQL 驱动。需要达梦或华为 DWS JDBC 支持时显式启用 `vendor-database-drivers` profile：

  ```sh
  mvn -T 2 -Pvendor-database-drivers clean package
  ```

- standalone 管理端固定输出到 `admin-ui/dist/`，不随 Spring Boot JAR 打包，避免同一份前端资源在 Web 服务器和后端包中重复存储。
- 可用以下命令查看最终包及最大的嵌套依赖，体积优化必须以实际 JAR 为准：

  ```sh
  ls -lh cms-boot/target/xhr-cms-1.0-SNAPSHOT.jar
  unzip -l cms-boot/target/xhr-cms-1.0-SNAPSHOT.jar 'BOOT-INF/lib/*' \
    | sort -nr -k1 | head -20
  ```

## 公开留言表单与 HTTPS 代理

公开表单默认按站点服务实际连接的 scheme、host 和规范化端口校验 Origin，本地直连无需额外配置。HTTPS 在反向代理终止、站点 Undertow 使用 HTTP 时，在外部 YAML 中显式配置该站点的页面 Origin：

```yaml
cms:
  form:
    public-origins:
      "4": "https://www.example.com"
```

键是本站 siteId，值只包含 HTTP(S) 协议、主机和可选端口，不带路径、用户信息、query 或 fragment。配置存在时仅匹配该 Origin，不同时放行后端 HTTP 地址；默认端口80/443按协议归一化。空白或非法配置会拒绝公开提交，删除该站点配置才恢复实际连接校验。配置由服务端外部文件维护，变更按既有应用配置生效方式部署；独立站点进程也须使用相同外部配置。

不要用资源根地址 siteServiceIp 代替页面 Origin，它可能指向 CDN；公开表单不信任任意 X-Forwarded-Proto/Host。此配置只处理公开留言入口，不改变旧 key 认证接口和其他服务的代理策略。页面接入需移除原表单发送逻辑；适配器不自动清除任意旧脚本，工作台隔离预览继续依赖 sandbox/CSP。

## 本站客服和统计接入

“站点设置”的独立接入面板保存本站客服/统计的公开自初始化脚本URL和最多8个data-*文本参数；每项完整序列化JSON最多1000字，超限会明确拒绝且不截断。仅存公开参数，不填后端密码、密钥、Token或任意内联JS。不支持复杂供应商初始化代码，保存和脚本load事件都不代表业务功能已验通。需要跨域脚本支持匿名CORS。

外部配置 `cms.external-integrations.mode` 默认 `disabled`，工作台本机公开站点也不会新增真实外部加载。`loopback-test`仅允许URL中的字面127.0.0.1或[::1]，不解析localhost或其他域名，不做DNS/后台远程探测；显式 `production` 才按本站开关加载正式公开URL。非法模式与预发布认证站点均抑制加载。独立站点进程也需使用相同外部配置。真实外部调用另行取得明确授权，本次只用本地替身。

仅在公开站点静态、动态和标准自定义路由的完整HTML输出处追加固定本站runtime.js；运行脚本按服务端当前站点、环境和开关再次筛选，源DOM或query参数不能改变配置。管理/分析/草稿渲染不注入；源模板自带任意旧脚本不会因此被删除或阻断，预览安全仍依赖导入预览与草稿预览的sandbox/CSP。自定义处理器直接写Undertow exchange绕开标准sendHtml时须自行核对接入，不在自动接管范围。

loopback-test由服务端给条目标记固定测试模式，使用fetch的redirect:error、credentials:omit和no-referrer读取可信本地替身，再以临时blob脚本执行，加载完成/失败/超时时回收。它不跟随本地地址到外网的302；生产模式继续匿名script加载。严格CSP需允许本地connect-src和blob脚本才能运行替身，功能不会放松CSP。替身脚本本身必须可信；这不是任意脚本网络副作用的沙箱，旧JS隔离仍由sandbox/CSP承担。

管理面板显示未配置、关闭、无效配置和环境抑制诊断；实际加载页显示正在加载、失败、8秒超时或脚本已加载待业务核验。加载器不向外发送管理Cookie/页面referrer，不新增后端健康探测或统计上报。

## 时间与时区

系统业务时间统一使用北京时间（`Asia/Shanghai`）。应用入口在 Spring 初始化前设置 JVM 默认时区，JSON 日期时间和日志也使用北京时间。各 MySQL 环境通过 `connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true` 将每个新连接的会话时区固定为 `+08:00`，使 SQL `NOW()` 与业务 `DATETIME` 字段保持一致。

部署时同步更新外部覆盖的 JDBC URL，并重启应用以重建连接池；仅修改数据库全局时区不会修正已有会话。可通过应用数据源执行 `SELECT @@session.time_zone, NOW(), UTC_TIMESTAMP()`，确认会话为 `+08:00` 且当前时间比 UTC 快 8 小时。历史数据不会自动调整；其他 Java 启动入口应在初始化前设置 `-Duser.timezone=Asia/Shanghai`。

## IDEA 多站点模板开发

从根 `pom.xml` 导入 Maven 多模块工程，确认 IDEA 已索引 `cms-site` 及其依赖，并具备 Thymeleaf/Spring 表达式支持。站点模板仍位于 `runtime/template/{projectCode}/`，页面数据通过既有 `TemplateModelContributor.supports/contribute` 或自定义路由注入。

站点模板使用原生 `@thymesVar` 指向实际页面模型；客户专用模板和 Controller 不再随基础版分发。

公共工具可使用 `th:with="ui=${@CommonUtils}"` 声明局部别名，再调用 `${ui.resolveUiUrl('assets/images/logo.png', siteId)}`。当前 Thymeleaf 对 `th:src`、`th:href` 中的直接 Bean 访问有限制，这些属性继续使用普通变量或局部别名；页面模型不能直接加 `@`。

IDEA 导航和真实渲染排查见 [动态绑定手册](docs/AI前端动态数据绑定手册.md#16-idea-全站点类型导航与渲染排查)。类型提示按需维护，不自动生成声明、不监听模板文件；静态 HTML 预览不能验证 Thymeleaf 动态数据。

## Java 格式约定

Java 主源码与测试统一使用 [google-java-format 1.36.0](https://github.com/google/google-java-format/releases/tag/v1.36.0) 的 AOSP 风格：4 空格缩进、统一括号与换行、排序并清理未使用的 import。`.editorconfig` 约定 UTF-8、LF 和文件末尾换行，`.gitattributes` 固定 Java 文件为 LF。

需要批量格式化时，从上述官方版本下载 `google-java-format-1.36.0-all-deps.jar` 到工程外，在同一个 shell 中执行：

```sh
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 21.0.12-tem
GJF_JAR=/path/to/google-java-format-1.36.0-all-deps.jar
find cms-* -path '*/src/*' -name '*.java' -print0 \
  | xargs -0 java -jar "$GJF_JAR" --aosp --replace
```

将 `--replace` 改为 `--dry-run --set-exit-if-changed` 可检查格式。格式化工具和临时文件不放入源码工程。

## 开发运行

在 IDE 导入根 `pom.xml`，运行 `cms-boot` 的 `com.xinhongru.CmsApplication`，工作目录设为仓库根目录，使用 `dev` profile。开发配置位于 `cms-boot/src/main/resources/application-dev.yml`，默认管理端口 `7088`。数据库和 Redis 都是必需服务；会话不回退为本地内存。

`application.yml` 保留公共框架与默认关闭的 AI/工作台开关。dev、local、test、uat、prod 文件仅包含本机地址和空凭据，不能直接用于生产。通过 `--spring.config.additional-location` 指定外部配置，填写数据库、Redis、存储路径和密钥。后端到独立前端的跳转使用 `cms.admin.frontend-base-url`。

站点与 UI Package 服务端口统一限制为 `1024–49151`（含边界），新增/编辑站点、站点端口设置、启动及重启恢复均校验此范围。历史越界站点端口需要手动修改后再启动。UI Package 预览默认端口由 `cms.ui-package.port` 配置，各环境统一为 `7187`；后台/API 修改的端口保存到现有系统选项，并优先于配置文件，重启后仍生效。不再使用 `port-end` 或自动查找端口。启动时直接绑定生效端口，占用冲突会明确报错；修改预览端口后需同步 Nginx 上游端口。

基础版默认关闭 `cms.ui-package.auto-start`，必要时由部署配置明确开启 UI Package 服务。该开关不等同于关闭下文的已运行站点恢复机制。

站点服务在管理进程重启后按数据库中的 `RUNNING` 状态恢复，统一进程与独立进程模式均适用。恢复使用原端口（支持 1024–49151 的合法自定义端口），不会启用手动停止的站点或抢占已有监听端口。独立进程仍存活时通过原控制接口确认，否则重新拉起；独立子进程不会重复启动共享 UI Package 服务。缺失的 UI 包 active 链接从站点当前版本恢复。单站点恢复失败会记录日志并继续其他站点，保留原 RUNNING 状态以便下次重启重试；因此数据库状态不代表恢复成功，需结合日志与实际访问检查。

通过 Maven 启动时，先将模块安装到本机仓库，再只运行启动模块：

```sh
mvn -T 2 -DskipTests install
mvn -pl cms-boot spring-boot:run -Dspring-boot.run.profiles=dev
```

管理端不由 Spring Boot 托管。开发时在另一个终端启动 standalone Vite 服务，默认将 API 代理到 `127.0.0.1:7088`：

```sh
npm --prefix admin-ui run dev
```

生产必须显式指定 profile，并从包含 `runtime/` 的工作目录运行：

```sh
java -jar cms-boot/target/xhr-cms-1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

当前发布包保持 Linux x64 Playwright 裁剪策略；其他平台涉及浏览器渲染的开发请用 IDE 或 Maven classpath 运行。外部 OpenCode 由部署方自行提供，本仓库不提供其 Docker 配置或启动脚本。

管理员与会员登录最终均使用 Spring Session Redis 的 `SESSION` Cookie；管理员与会员身份隔离。旧会员 JWT 不再接受，升级后会员需重新登录。多节点需使用相同 Redis 数据库、会话命名空间和应用版本；浏览器跨域部署同时配置可信来源及携带 Cookie。

- [模块边界与重构约定](docs/architecture/maven-modules.md)
- [管理端构建与代理](admin-ui/README.md)
- [数据库初始化](database/README.md)
- [Spring AI 与 OpenCode 接入](docs/operations/spring-ai-migration.md)
- [模板动态数据绑定](docs/AI前端动态数据绑定手册.md)

## 预发布访问保护

预发布访问保护采用环境总开关和站点开关两级控制：仅当 `cms.preview-access.enabled=true` 且数据库中该站点开启认证时才生效。站点认证开关和密码存储在 `cms_ai_site_project` 中。在“站点服务 → 站点服务设置”配置访问域名、服务端口、是否认证和认证密码。开启认证时密码必填；密码按业务要求以明文存储，并在该设置弹窗回显，支持查看和修改。

仅 HTML 页面受保护，包括目录首页、无后缀栏目／详情页及通过 `SiteRouteContext.sendHtml` 输出的自定义页面。图片、JS、CSS、字体、下载文件和 JSON 接口不经过此门禁；上传目录中的 HTML 文件仍按页面认证。管理端 admin 资源不经过此门禁，管理 API 继续使用原有认证。

- 既有数据库部署前先备份并审核执行 `database/20261014_站点访问认证配置.sql` 和 `database/20261014_清理站点无用字段.sql`，不要执行初始化 SQL。字段删除清单见 `database/README.md`。新增列默认关闭，升级后需要在管理端为目标站点重新开启认证。
- 环境总开关在各环境 YAML 中独立配置：test 为 `true`，dev、local、uat、prod 为 `false`，公共 `application.yml` 不设置。多个环境共用数据库时，关闭总开关的环境不会执行站点门禁，也不会修改数据库中的站点设置。环境总开关变更后需重启管理进程及所有独立站点进程；自动拉起的子进程继承该开关，不要另用子进程 JVM／程序参数覆盖它。
- UI 静态服务与站点服务（含独立进程）在环境总开关开启时读取同一站点设置，下一次 HTML 请求即生效。修改认证设置无需重启；修改访问域名或端口会重启正在运行的站点服务。升级代码时需重启所有运行旧版本的独立进程。
- 开启认证时密码不得为空、包含控制字符或超过 255 个字符。修改密码后原通行凭据失效。站点之间及服务端口之间分别验证。
- 站点服务设置和站点管理均可维护“资源域名”（复用 `site_service_ip`）：填写完整 HTTP(S) 资源根地址，可带端口及路径前缀，不自动追加站点编码；留空使用 `cms.resources.domain`，再回退到站点/UI 服务地址。旧裸域名/IP 保留原行为，不自动转换。资源域名单独修改不重启服务；站点访问域名和服务端口在站点服务设置中维护。详见动态绑定手册 2.3 节。
- `__internal/routes` 和 `__internal/reload-routes` 仍由原有控制 Token 认证，预发布访问凭据不能代替控制 Token。
- 开启时受保护的 HTML 响应设置 `Cache-Control: no-store`；已有 CDN 缓存或其他直接托管入口需要部署方同步处理。公网访问应通过 HTTPS。验证成功后通过独立的 HttpOnly、SameSite=Lax Cookie 保持访问 12 小时；Cookie 不含密码，服务端使用进程随机密钥签名并校验有效期。不同服务端口分别验证，进程重启后需要重新输入密码；不提供应用内退出按钮。
- iframe 或外部截图客户端访问受保护资源时也需要认证；不会向任意远程 URL 自动发送预发布凭据。

## 资源存储与引用

公开资源按逻辑引用定位，数据库与内容字段不保存环境域名：

| 引用 | 磁盘位置 | 未配置域名时的访问方式 |
| --- | --- | --- |
| `ui:/images/a.png` | `runtime/ui-packages/{siteCode}/{激活版本}/images/a.png` | 当前站点服务或共享 UI 服务地址 + `images/a.png` |
| `assets:/upload/images/a.png` | `runtime/ui-packages/{siteCode}/assets/upload/images/a.png` | 当前站点/UI 服务地址 + `assets/upload/images/a.png` |
| `assets:/global/images/a.png` | `runtime/assets/images/a.png` | `/assets/global/images/a.png` |

各环境只需一个对外访问地址配置，可包含 Nginx 路径前缀，留空使用本地服务：

```yaml
cms:
  resources:
    domain: ""  # 例如 https://static.example.com/cms
```

配置为 `https://static.example.com/cms` 时，站点 SITE_A 的 `ui:/images/a.png` 解析为 `https://static.example.com/cms/SITE_A/images/a.png`，`assets:/upload/images/a.png` 解析为 `https://static.example.com/cms/SITE_A/assets/upload/images/a.png`，全局引用解析为 `https://static.example.com/cms/assets/global/images/a.png`。域名不改变文件写入位置，也不会自动上传文件到 OSS；对象同步由部署侧负责。

Nginx/OSS 访问目录须与上述路径对应：`/{siteCode}/` 对应站点激活的 UI 包，`/{siteCode}/assets/upload/` 对应站点素材目录，`/assets/global/` 对应 `cms.static-asset.root`（默认 `${user.dir}/runtime/assets`）。全局根目录直接保存分类目录，不再增加 global/sites 子层级。后端不提供 `/media/`、`/upload`、`/Upload`、`/UploadFile` 或私有 DAM 接口。UI 包代码与普通上传素材使用各自的响应策略，代理需保留 Range、HEAD、Content-Type 和安全响应头。

上传 API 返回 `reference` 和 `url`，内容保存前者，预览使用后者。没有可用本地站点/UI 服务且未配置域名时，站点引用的 URL 为空，管理界面显示资源未就绪；解析本身不隐式启动服务。模板继续使用 `CommonUtils.resolveUiUrl(reference, siteId)`；最终页面输出会解析富文本中的逻辑引用。编辑器显示实际 URL，保存时转换回引用，切换环境域名无需更新内容。使用完整 HTTP(S) 外部链接时保留原地址。

资源管理不按管理员角色或站点归属划分权限，但继续遵循管理端登录和写请求来源校验。站点编码只负责目录定位；文件路径、扩展名、大小、符号链接、引用检查与重名保护仍然生效。公开素材上限 20MB，SVG 上限 2MB并进行安全校验。资源同步进入公开目录时也执行相同校验；多语言站点的整站资源复制保留站点 assets/upload。全局素材引用可跨站点直接复用。

multipart 临时文件由 Spring Boot 和 Servlet 容器使用系统默认临时目录，不再注册项目自定义 MultipartConfig。私有 DAM 服务、目录配置和初始化表定义已移除，不自动执行数据库清理。生产多节点共享磁盘检查单独使用 `cms.storage.shared-required/shared-root`，不再与 DAM 耦合。

管理端随包附带的默认 Logo、登录背景和封面保留在 `admin-ui/public/media`，由前端静态服务通过 `/media/` 提供，并随管理端资源基路径调整。这些文件不使用业务资源解析或 `cms.resources.domain`。自定义品牌素材保存在全局库的 `system/branding/`，以 `assets:/global/system/branding/...` 保存。

管理节点不再包含 `cms-boot/src/main/resources/templates` 或 Spring MVC Thymeleaf 视图装配：管理页面由 standalone `admin-ui` 提供，旧页面入口仅 HTTP 重定向，错误入口返回 JSON。`cms-site` 直接依赖 Thymeleaf 引擎处理 `runtime` 站点模板及预览，不依赖 classpath 内置页面。

## 项目内人工修正经验

经验记录与参考由 `cms.workbench.experience.enabled` 控制，默认false。关闭时界面与分析明确“经验暂停记录与参考”：人工保存仍保存当前项目方案供绑定，不追加修正经验；新页分析不检索或发送旧经验，既有历史保留可查。同页已确认决定仍保留，不能把零模型重跑当作重新识别。当前先以经验关闭模式验证真实页面判断效果，后续经验机制暂停推进。以下经验记录与参考行为仅在明确开启后生效。

内部工作台的内容单元保存会记录可信服务端前后方案、操作者、来源原文/定位、包版本及基线修订；普通CMS内容运营更新不会生成经验。同版本同页来源摘要不变时，重新识别保留已确认决定且不调用模型；新包同路径可参考旧版经验，但仍保留自己的原文并等待确认。新相似页只将项目内、业务原文及区域条件相符的首批最多8条候选作为有界模型上下文，模型仅获得匹配的改变单元及必要来源；完整候选按20k字符上限的剩余提示预算选择，放不下整条跳过并警告，显示实际传入的相关单元/来源及经验ID/版本，完整历史不裁剪；不复制旧值，不用相同数字推断相同业务。冲突保留待人工确认，停用按经验版本校验；未进入本次模型分析批次的来源不计入实际传入范围。

元数据位于配置UI包根目录下私有的 .workbench-content-units，仍不写业务内容表或修改来源包。历史来源证据只保存前后完整方案实际引用的来源，原值不截断。每页活跃历史最多50条、当前文档最多1MB；启用记录后达到阈值自动归档已有历史，并同次原子提交新方案和归档引用，无需额外操作，显式归档仍幂等可用。单条新证据及新方案本身仍超1MB则明确拒绝且不部分保存，不截断原值。归档先写不可变私有批次，再原子提交当前文档中的sha256引用；提交失败的孤立副本不算已保存历史。历史按页读取，每次最多50条；界面每页10条。归档及经验启停不改变内容单元revision，因此不使字段计划无意义失效。经验候选扫描最多200页/8MB，超限明确显示候选不可用告警，历史分页、归档及启停仍可管理；新页继续仅基于当前页面识别并标明本次未加载项目经验，坏档或越界仍明确失败；当前功能只在项目内使用，不包含公司共享或同步发布。归档文件须与当前元数据一并备份，不能单独删除或编辑。旧存档缺少的基线来源保持未知，不补造模型或审核记录。



## 当前站点公开搜索

站点独立HTTP服务提供 `GET /__cms/search`（搜索表单）与 `GET /__cms/search.json`。关键词 `q` 为1至100字，空词只返回初始表单/空列表，不扫描内容；`page` 为1至100000，`size` 为1至50。JSON沿用 `code=0` 与 `data.list/total/pageNum/pageSize/pages`；结果明确区分 `content` 与 `singlePage`，包含真实记录ID、栏目ID、标题、本站链接和文本片段。片段的 `text/hit` 只用于文本节点，不当HTML执行。

可选 `kind=content` 只查内容记录，默认 `all` 同时查单页；`menuId` 限定本站栏目，`year/month` 按实际创建时间筛选（不是模型自定义日期字段）。分页及再次提交保留这些条件。非法参数返回400，非GET返回405。查询站点从当前HTTP服务确定；传入其他 `siteId` 被拒绝，不能用查询参数跨站。预发布密码门禁在查询之前复用，未解锁时沿用站点门禁响应。

标题属于固定公开显示信息；摘要、正文及扩展文本只有模型字段已启用且显式勾选“公开检索”才进入同表投影，默认关闭，独立于“后台筛选”isSearch。资源URL和附件正文不参与。富文本去除脚本/样式，结果使用安全文本高亮。草稿、撤回、删除、过期、他站及没有可确认公开路由的内容在查询时排除；路由冲突或未知自定义覆盖保守排除整个栏目。

模板可以用当前服务器提供的 `view.idEntity/idEntity` 设置既有 `conditionVo`，通过 `CommonUtils.getPublicSearchResults(idEntity)` 获取包含单页的统一PageResult，或继续 `getContentPagerByKey(idEntity)` 获取仅内容记录的旧Pager形态。旧Pager在可信本站上下文保留空词枚举语义，其默认分页链接使用 `kind=content&legacy=1` 明确保留此行为；普通固定搜索页空词仍不扫描。旧链升级正文和获准扩展字段召回，不把单页伪造成ContentEntity；返回实际ID、栏目、标题、创建时间，不把snippet塞进note槽位冒充原业务值。classIds继续限定栏目，year/month在该新查询中按创建时间明确生效（旧searchByKey两条SQL此前没有使用它们）；旧yearString/type/area不再静默丢弃，提示改用显式模型过滤。

服务器身份绑定不来自请求JSON或调用者随意填写的siteProjectId；现有copyIdEntity派生helper会复制已有可信绑定，旧11参构造仍保留。自行构造的、没有服务器上下文的搜索对象不能全站兜底。旧通用管理预览暂未为搜索绑定服务器身份，未在本片修改严格草稿预览；若有真实模板依赖需另按授权站点上下文适配。已有明确pagePath沿旧Pager生成规则，默认分页指向实际固定搜索页并保留仅内容筛选。

兼容历史NULL站点记录时只按其真实所属栏目推导本站归属，显式其他站点记录始终排除；投影重建与查询使用同一规则。配置保存与投影重建共事务/栏目锁，内容写与投影同SQL，不依赖异步回填。当前仍须完成所有遗留写链及实际MySQL、公开站链接和50k量测后才能标整票完成；编译/单元测试不代表这些运行验收已通过。
