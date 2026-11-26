# xhr-cms 管理端

本目录保留 Vue 业务源码、页面静态资源和构建配置。依赖目录及构建结果由交接方按需生成。

## 安装与检查

从仓库根目录执行。`package-lock.json` 仅保留在本地，不提交 Git；首次安装或 `package.json` 变化后执行：

```bash
npm --prefix admin-ui install
npm --prefix admin-ui run typecheck
```

本地已有与 `package.json` 一致的锁文件时，可使用 `npm --prefix admin-ui ci` 按本地锁定版本重装依赖。

## 构建

### 开发者建站工作台

客户版默认构建不启用整站建站工作台。构建模式固定工作台开关：只有 `workbench` 模式才注册工作台路由和显示入口，环境变量不能将普通客户构建切换为内部版；普通构建会移除工作台页面模块。现有 CMS 的栏目、模板和运营功能保持原有入口。

开发者版本使用 `npm --prefix admin-ui run dev:workbench` 启动，使用 `npm --prefix admin-ui run build:workbench` 构建。对应 `.env.workbench` 只包含功能开关，不保存凭据。客户版输出到 `admin-ui/dist/`，内部版输出到 `admin-ui/dist-workbench/`，分别部署至各自静态站点。

开发者版本复用 CMS 登录、站点上下文、组件和主题。工作台入口仅向平台管理员显示，路由额外校验平台管理员身份；普通系统菜单授权不会放行该开发者路由。此构建隔离不替代后端接口权限校验。

后端开发接口默认由 `cms.workbench.enabled=false` 关闭；内部环境须显式开启。开启后仍要求现有超级管理员或平台组全站管理员身份，并校验站点访问权限，普通客户的站点管理员不可调用。当前开发人员暂复用内部平台管理员身份，尚无独立开发人员角色；客户部署保持后端开关关闭。不在前端构建中配置内部凭据或经验服务地址。

工作台的页面结构 AI 分析由用户选页后明确触发，调用 `template-seed/analyze`，仅将服务端提取的候选内容发送到已配置网关；返回建议以纯文本展示，不自动保存栏目、模板或内容。工作台不自动打开旧 CMS 的 AI 识别入口；现有 CMS 页面与 API 仍按各自既有权限和行为运行。

在领域 Agent 方案、内容单元草稿和单页字段映射计划中，可点击“预览并审核整页”。预览使用 1440 或 1920 像素的桌面宽度，并可按窗口缩放。选中单元后，点击页面内“位置”跳到静态原文；同一元素的多个区间共用一个标记，定位失败时仍显示可读取的原文。页面包或来源修订不匹配时停止审核，隔离预览不运行模板脚本。

Agent 建议需先采纳到本地草稿。审核草稿时可统一应用整页修正，并通过“保存整页确认”保存内容单元；有目标栏目时可继续在整页审核中调整、生成和确认字段计划。已保存内容单元、已生成待确认计划与已确认字段计划是不同状态。内容初始化和发布仍须单独操作。

管理端只使用 standalone 方式，不提供 embedded 构建，也不会把产物写入后端模块。

- `npm run dev`：启动开发服务器，页面资源基路径默认为 `/`。
- `npm run build`：先执行 Vue/TypeScript 检查，再输出到本目录的 `dist/`，供 Web 服务器独立部署。

Linux、macOS 和 Windows 均使用同一条 `npm run build` 命令，无需设置构建目标环境变量。

生产 API 默认使用当前页面的协议、域名和端口。Vue 发起的后端接口统一使用 `/api/` 前缀，包括验证码 `/api/image-code/get`、登录 `/api/admin/login`、登录公钥 `/api/admin/login/public-key` 、富文本编辑器 `/api/admin/config` 和微文导入 `/api/admin/wechat/wechatUpload`。已有业务接口继续使用 `/api/admin/...`。

standalone 部署使用项目配置 [xhr-cms-admin.conf](../deploy/nginx/xhr-cms-admin.conf)。它仅配置以下三个 location：

| 路径 | 处理方式 |
| --- | --- |
| `/` | 托管 `dist/`，通过 SPA fallback 支持 Vue 页面刷新 |
| `/api/` | 代理 Spring 管理节点，包含登录、验证码、上传及 SSE 流式接口 |
| `/assets/global/` | 代理后端全局素材 |

配置中的 `proxy_pass` 不追加 `/`，保留后端所需的原请求路径。`/admin/...` 页面路由及 `/static/`、`/media/`、普通 `/assets/` 静态文件均由前端根路径提供；不要将整个 `/assets/` 转发到后端。当前配置不需要 WebSocket `/ws` 代理，SSE 已由 `/api/` 的关闭缓冲和读取超时设置支持。

后台静态资源统一存放在 `public/static/`，构建时原样输出到 `dist/static/`，不再包含中间的 `admin/` 路径：登录加密脚本为 `/static/js/jsencrypt.min.js`，富文本编辑器为 `/static/ecj27zyqedit/`。两者遵循 `VITE_ADMIN_ASSET_BASE`，由前端静态服务提供，不依赖后端资源映射或 `VITE_API_BASE_URL`。编辑器配置、上传等业务操作继续调用 `/api/`。

后端 `cms-boot/src/main/resources/static/` 已迁出并删除，前端 `public/static/` 只保留登录加密脚本、编辑器运行资源和附件类型图标；后端不再注册 `/static/` 资源映射，JAR 排除遗留 static 构建产物。开发环境不再将 `/static` 代理给后端。

管理端 Nginx 保持根路径托管 `dist/`，后端只代理 `/api/` 和 `/assets/global/`。不要增加 `/static/` 或 `/upload/` 代理：编辑器 `/static/` 文件已包含在前端 `dist/` 中，由根路径直接提供。

上传文件属于运行时数据，不能打包进前端。素材沿用 `assets:/` 引用和统一资源解析：全局素材走 `/assets/global/`，站点上传素材走已配置的资源域名或站点服务。历史硬编码 `/upload/` 地址需要在资源配置/内容引用侧调整为可访问的地址，前端不会将其自动改写到 `/api/`。不要将整个 `/assets/` 转发到后端，前端构建资源也使用这个路径。

检查静态资源时，除 HTTP 状态码外还要确认 Content-Type 和响应正文：SPA fallback 可能返回状态 200 的 `index.html`，仍无法执行为 JS。部署必须完整更新 `dist/`，登录页应请求 `/static/js/jsencrypt.min.js`，公钥请求继续使用 `/api/admin/login/public-key`。

管理端由 standalone 静态服务托管，登录页为 `/admin/login`，账号登录通过验证码、公钥与后台 Session 接口完成，不依赖集团身份认证服务。

使用以上同源部署方式时，将 `VITE_API_BASE_URL` 留空。跨域配置该变量时只填写后端源地址，不要额外追加 `/api`，接口地址已包含该前缀。

环境配置见 `.env.production`、`.env.development` 和 `.env.*.example`。本地开发运行 `npm run dev`，默认代理后端 `127.0.0.1:7088`。

当前 `package.json` 未配置测试脚本、Vitest 或 Vue Test Utils 依赖。类型检查和构建只验证现有前端源码，不执行单元测试。`node_modules`、`dist` 和工具缓存不属于版本化源码。

接口驱动的表格通过 `src/utils/tableLoader.ts` 管理加载：请求开始时清空记录、总数和勾选状态；结果写入和错误提示前检查 `request.isCurrent()`，在 `finally` 中调用 `request.finish()`。切换站点、栏目或关闭弹窗时，应使旧请求失效，避免慢请求覆盖当前列表。已有请求序号保护的页面沿用原机制，并在加载前清空数据。总数清零的分页表格在加载期间通过 `v-if` 隐藏分页组件，避免组件将当前页自动改回第一页。

## 公共界面配置

公共设计变量统一在 `src/styles/variables.css` 的 `:root` 中维护，由 `src/main.ts` 全局加载。页面通过 `var(...)` 引用，桌面和窄屏共用；调整下面的变量即可修改对应样式，不必逐页替换数值。

| 变量 | 用途 | 默认值 |
| --- | --- | --- |
| `--admin-content-padding` | 仅由 `.admin-content` 提供一次的内容区外围留白 | `8px` |
| `--admin-card-gap` | 卡片之间的横向、纵向间隔 | `5px` |
| `--admin-card-padding` | 普通卡片内边距 | `--admin-space-5`（20px） |
| `--admin-card-padding-compact` | 紧凑卡片内边距 | `--admin-space-4`（16px） |
| `--admin-card-padding-roomy` | 宽松卡片内边距 | `--admin-space-6`（24px） |
| `--admin-card-radius` | 公共卡片圆角 | `--admin-radius-lg`（8px） |
| `--admin-card-border` | 公共卡片边框 | `1px solid var(--admin-border-subtle)` |
| `--admin-tree-min-width` | 栏目导航最小宽度 | `240px` |
| `--admin-tree-width` / `--admin-content-tree-width` | 栏目管理／内容管理导航展开宽度 | `300px` / `320px` |
| `--admin-tree-toggle-rail-width` | 导航切换控件所在间隙宽度 | `16px` |
| `--admin-tree-toggle-width` | 导航切换按钮宽度 | `14px` |
| `--admin-tree-toggle-padding` / `--admin-tree-toggle-gap` | 切换按钮纵向内边距／图标与文字间隔 | `10px` / `10px` |
| `--admin-tree-toggle-font-size` | 切换按钮竖排文字大小 | `10px` |

背景、文字颜色、字号和阴影沿用同文件已有的 `--admin-bg-*`、`--admin-text-*`、`--admin-font-*` 和 `--admin-shadow-*`。公共布局及导航切换按钮样式位于 `src/styles/admin-layout.css`；页面只保留各自的结构和响应式排布。不要通过修改基础 `--admin-space-*` 来仅调整内容区，否则会同时影响菜单栏、顶部栏和控件内部间距。外围留白只允许由 `.admin-content` 设置，页面根容器及中间布局容器不得重复使用 `--admin-content-padding`，响应式规则也遵循这一约定。页面根容器的 `padding: 0` 用于避免叠加；卡片内部仍使用 `--admin-card-padding*`，卡片之间使用 `--admin-card-gap`。有业务用途的特殊卡片和独立全屏预览样式不强制统一。

表格顶部工具栏按钮、配套筛选控件、行操作按钮及操作下拉菜单统一使用 `size="small"`。筛选容器使用 `admin-table-toolbar` 公共类，其控件高度通过 `variables.css` 中的 `--admin-table-control-height: 24px` 与小号按钮对齐；全局 `--el-component-size-small` 保持原值，其他表单及非表格按钮不随之调整。

## 上传资源与真实 IP 修复

后台按 `application.yml` 的 `uploadFile.format` 校验允许的扩展名；`all` 使用 image/video/audio/file 的并集。常见图片检查文件签名，不在配置白名单中的类型拒绝上传。素材接口使用 UUID 文件名，仍返回原有 `assets:/...` 路径，不改变内容引用。

SVG 保留上传，但与普通附件一样使用 `application/octet-stream`、`Content-Disposition: attachment`、`X-Content-Type-Options: nosniff`，不能作为官网页面执行。SVG 不再内联预览；需要图片展示时改用 PNG/WebP。已删除的历史探测文件不再增加类型拦截逻辑。正式模板、UI 包资源不应用此规则。不新增依赖、审计切面、格式查询接口或自定义 Range 处理。

管理端部署配置见 [xhr-cms-admin.conf](../deploy/nginx/xhr-cms-admin.conf)，该文件只配置管理端域名，不包含官网或 UI Package 预览服务。部署步骤：

1. 在仓库根目录执行 `npm --prefix admin-ui run build`，将完整 `admin-ui/dist/` 内容部署到服务器静态目录。
2. 将配置中的 `admin.example.com`、`/opt/xhr-cms/admin-ui/dist` 和两处后端 `127.0.0.1:7088` 改为实际值。同源部署的 `VITE_API_BASE_URL` 留空，资源基路径默认 `/`。
3. 将文件复制到 `/etc/nginx/conf.d/xhr-cms-admin.conf`，由 `nginx.conf` 的 `http {}` 加载，不要放到现有 `server {}` 内 include。移除同域名重复配置，以及旧的 `/static/`、`/upload/` 后端代理规则。
4. 确认 `http {}` 已加载 `mime.types`；按实际业务上传限制配置 `client_max_body_size`，并与后端上传限制保持一致。
5. 在服务器执行 `nginx -t`，通过后执行 `nginx -s reload`。本配置默认 HTTP 80；启用 HTTPS 时在该 server 配置证书及 443，并同步管理端公开地址配置。
6. 验证 `/admin/login` 刷新正常，`/static/js/jsencrypt.min.js` 返回 JS，编辑器 `/static/ecj27zyqedit/` 资源正常；再验证登录、全局素材和 AI 流式响应。

官网与 UI Package 预览服务使用各自域名和实际服务端口独立部署。UI Package 预览端口默认 7187，后台持久化的端口配置优先；端口修改后需同步该服务的代理上游地址。访问时完整保留 `/{siteCode}/` 路径。管理端配置不接管这些站点服务或资源域名。

管理端配置在 server 层统一覆盖 `X-Real-IP`、`X-Forwarded-For`。后端记录 IP 时依次读取 `X-Real-IP`、`X-Forwarded-For` 的第一个地址，均缺失时使用连接地址，不校验代理 IP 名单，也不增加访问 IP 限制。无需为日志 IP 配置额外 Java 启动参数。若前面还有 WAF/LB/CDN，应由 Nginx 按现有部署方式取得真实客户端地址后再传给后端。

部署时更新 JAR（保留现有 profile 和工作目录），执行 `nginx -t` 后重载。素材请求交给 Java 服务处理下载响应，清除旧素材 CDN/代理缓存。历史探测文件已由人工删除，无需额外清理或数据库变更；历史日志 IP 不回填，新登录后检查最后登录 IP。

服务器复测：不在白名单中的类型和伪装图片上传返回 400 且无正式落盘；合法图片、视频、附件正常；SVG 按附件下载；从不同来源重新登录，确认记录的 IP 与 Nginx 传入的客户端地址一致。本轮按要求不在本机执行构建、测试或浏览器验证，由服务器确认结果。

资源字段保存 `ui:/`、`assets:/upload/` 或 `assets:/global/`，通过后端统一资源上下文解析预览。`cms.resources.domain` 是所有前缀共用的域名，可带路径前缀；留空使用站点/UI 服务及本地全局素材入口。开发代理 `/assets/global/` 到 CMS。生产独立静态域名按根 README 的路径映射提供文件。管理端自带的默认 Logo、登录背景和封面继续存放在 `public/media/`，由前端静态服务提供，使用管理端资源基路径，不代理到后端。

后端不再打包或解析内置 templates，管理页面和错误提示由 Vue 承接；后端 `/error` 统一返回 JSON。旧页面入口只作 HTTP 重定向，`/admin/v2/**` 保留路径和查询参数。站点服务的 runtime 模板渲染独立保留。编辑器使用 `ueditor.all.js` 源文件，附件图标由 `fileIconPath` 指向前端静态服务的绝对地址，避免在官网展示内容时请求错误的相对路径。

静态资源清理按实际加载链执行：旧 Bootstrap、front、error 目录，以及编辑器未引用的重复压缩文件、上传器替代构建和图表扩展已删除。编辑器弹窗、语言包、表情、字体及脚本动态加载的依赖继续保留，不能只按 Vue 源码中的直接引用判断。视频预览样式使用实际存在的 `third-party/video-js/video-js.css`。
