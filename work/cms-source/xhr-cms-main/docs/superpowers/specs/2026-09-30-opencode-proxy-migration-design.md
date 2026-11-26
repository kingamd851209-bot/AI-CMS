# AI 识别与翻译改用 OpenCode 代理调用方式 — 设计文档

- 日期：2026-09-30
- 状态：待评审
- 参考实现：`E:\xhr\ai2workspace\Hongru-CMS`（`OpenCodeProxyController` / `OpenCodeService` / `admin-ui/src/api/opencodeClient.ts`）

## 1. 背景与问题

当前 `xhr-cms` 的 AI 能力（栏目识别、模板生成/修复、栏目翻译）统一走「任务体系」：

- 前端 `admin-ui/src/api/aiGenerationClient.ts` → `/api/admin/v2/ai-generation/tasks/*`
- 后端 `AiGenerationController` + `AiGenerationTasks` + `AiTaskRouter` + `OpenCodeAgentExecutor` + `OpenCodeClient`
- 服务端持有会话、注入工作目录与权限、按任务聚合 SSE

在 `AI 栏目识别` 对话框中点击「启动服务」时出现 `进程已退出`，功能不可用。

已定位根因：`OpenCodeProcessManager.start()` 按配置端口（4096）新起 `opencode serve`，但该端口已被一个残留的 opencode 进程占用，子进程立即崩溃（`ServeError`）；而 `drainOutput()` 丢弃了子进程输出，真实原因不可见，只留下含糊的「进程已退出」。

同时，参考项目 `Hongru-CMS` 采用另一套调用方式：**前端经 CMS 的 HTTP/SSE 代理直连 opencode 会话**，进程管理会「先探测已存在的健康服务并复用」，且把子进程输出写入日志。

## 2. 目标与非目标

### 目标

1. 三个识别对话框（栏目 / 模板 / 通用模板）与**栏目翻译**，统一改为参考项目 `OpenCodeProxyController` 的调用方式。
2. **Prompt 构建逻辑与内容保持不变**（`ColumnRecognitionPrompt`、`buildTranslationPrompt`、各前端 prompt 构造等一律不动）。
3. 新增代理层；**现有 task 体系保留**（不删除 `AiGenerationController` 等）。
4. 根治「进程已退出」这一类黑盒失败：进程管理复用已存在的健康服务，并将子进程输出写入日志。

### 非目标

- 不重构 prompt 文案、不改变识别/翻译结果的解析规则。
- 不移除、不改写 Spring AI（`SPRING_AI`）路径。
- 不做与本次无关的目录/模块整理。

## 3. 现状对比

| 维度 | 参考项目 Hongru-CMS | 当前 xhr-cms |
| --- | --- | --- |
| 前端识别调用 | `opencodeClient.ts` → `/api/admin/v2/opencode-proxy/*` | `aiGenerationClient.ts` → `/api/admin/v2/ai-generation/tasks/*` |
| 会话归属 | 前端驱动（createSession/sendMessage/SSE） | 服务端（task 抽象） |
| 注入 | 裸转发（不注入 agent/system/权限/模型） | 服务端注入 workspace + 全 deny 权限 + agent + system 指令 + 模型 |
| 进程管理 | `OpenCodeService`：先探测健康则复用、输出写日志、按端口兜底清理 | `OpenCodeProcessManager`：只认自有进程、丢弃输出 |
| 栏目翻译 | 服务端 `/translate-menus-preview`（非代理） | 任务体系 `/translate-menus-preview/task` + SSE |

## 4. 设计

### 4.1 总体

新增「代理层」，作为前端与 opencode 之间唯一的会话通道；进程管理仍是**同一个** opencode 实例（沿用现有 `OpenCodeProcessManager` 与其 4096 端口），**不引入第二个进程管理器**，从而避免再次端口冲突。

```
浏览器
  │  /api/admin/v2/opencode-proxy/*   (session / message / abort / event-SSE)
  ▼
OpenCodeProxyController  ── 注入 agent/system/权限/模型 ──►  opencode serve (127.0.0.1:4096, Basic 鉴权)
  ▲
  │  /api/admin/v2/ai-generation/opencode/*  (status / start / stop)   沿用现有
OpenCodeProcessManager（增强：复用健康服务 + 输出写日志）
```

### 4.2 后端

#### 4.2.1 新增 `OpenCodeProxyController`

位置：`cms-admin`（与现有 `OpenCodeProcessController` 同包 `com.xinhongru.ai.agent.opencode`）。
鉴权：要求已登录（`AdminAuthenticationService.requireAuthenticatedUser`）。会话创建请求体只有 `{title}`、不含站点信息，故不在此做站点级校验；站点级校验仍在取 prompt 的端点（`/generate-prompt`、`/translate-menus-preview/prompt`）内完成。不额外要求全局管理员——识别/翻译是普通业务能力（与 `OpenCodeProcessController` 的「仅全局管理员可管理进程」区分开）。

| 方法 | 路径 | 请求体 | 行为 |
| --- | --- | --- | --- |
| POST | `/session` | `{ title? }` | 转发 `POST {baseUrl}/session`，**强制注入** `permission=[{"*","*","deny"},{"external_directory","*","deny"}]` |
| POST | `/session/{id}/message` | `{ usage, parts }` | **注入** `agent`、`system=<用途指令>`、`model?`，与前端 `parts` 合并后转发 `POST {baseUrl}/session/{id}/message` |
| GET | `/session/{id}` | — | 透传 |
| GET | `/session/{id}/messages` | — | 透传 |
| GET | `/session/{id}/abort` | — | 透传 |
| GET | `/event?sessionId=...` | — | SSE 透传，并在服务端**按 `sessionId` 过滤**：仅转发 `type=message.part.delta` 且 `field=text` 的 `delta` 帧（复用 `OpenCodeEventParser` 的帧解析思路） |

- 全部请求由服务端附加 `Authorization: Basic`（口令取自 `OpenCodeProperties`，前端不可见）。
- `usage` 为 `AiUsage` 枚举名（`COLUMN_RECOGNITION` / `TEMPLATE_GENERATION` / `TEMPLATE_REPAIR` / `MENU_TRANSLATION`），后端据此取系统指令。

#### 4.2.2 会话参数注入（方案 1）

为保持与原 task 路径一致的行为与安全性，代理层统一注入而非前端提供：

- `agent`：`OpenCodeProperties.getAgent()`（当前 `build`）。
- `system`：由 `usage` 映射的**执行器指令**。为避免两处文案漂移，把 `AiTaskRouter.execute` 中现有的三段指令抽为 `AiUsage.executorInstruction()`，`AiTaskRouter` 与新代理共用。
- `model`：`OpenCodeProperties.hasExplicitModel()` 为真时注入 `{providerID, modelID}`，否则继承。
- `permission`：会话创建时强制全 deny（与原 `OpenCodeClient.createSession` 的拒绝策略等价）。

> 与原实现的差异（需保留说明）：原实现还会为每任务创建隔离工作目录并写入 `prompt.txt`。代理方式下 prompt 直接经 `parts` 传入，不再需要工作目录，故不创建；安全边界由「全 deny 权限」保证，等价或更严格。

#### 4.2.3 增强 `OpenCodeProcessManager.start()`

- 启动前先 `isReady()`：若 `baseUrl` 已有健康且协议兼容的 opencode，则**复用**，不新起进程（消除「残留进程占端口 → 新进程秒退」）。
- `drainOutput()` 由「丢弃」改为写入日志（`LOG.info("[OpenCode] {}", line)`），并在启动失败时把最近若干行附到 `Status.error`，供前端提示。
- 失败分支保留现有语义，仅补充可见原因。

#### 4.2.4 prompt 端点

- 识别：继续用现有 `/api/admin/v2/generate-prompt`，内容不变。
- 翻译：新增只读端点 `POST /api/admin/v2/sites/{siteId}/translate-menus-preview/prompt`，复用现有 `buildTranslationPrompt(menus, targetLanguage)`，仅返回 prompt，不创建任务。现有 `/translate-menus-preview/task` 保留。

### 4.3 前端

#### 4.3.1 新增 `admin-ui/src/api/opencodeClient.ts`

自参考项目移植并按本设计调整：

```ts
createSession(title?, signal?): Promise<{ id: string }>          // POST /opencode-proxy/session
sendMessage(sessionId, usage, parts, signal?): Promise<MessageResponse> // POST /session/{id}/message
abortSession(sessionId): Promise<void>
getGlobalEvents(sessionId, callbacks): EventSource                    // GET /opencode-proxy/event?sessionId=...
```

- `usage` 默认 `'COLUMN_RECOGNITION'`；模板/翻译传入各自用途。
- **「AI 流式响应日志」的驱动**：`getGlobalEvents(sessionId, { onDelta })` 连接 `/opencode-proxy/event?sessionId=...`，服务端已按会话过滤，前端只需把收到的 `delta` 追加进现有的 `aiResponseStream` / `translateStreamLog` 面板。它**替代**现有的按任务 SSE（`getTaskEvents` / `/tasks/{id}/events`）。
  - 现有三处面板（`ColumnRecognitionDialog` 的 `ai-response-stream`、`TemplateRecognitionDialog` / `CommonTemplateRecognitionDialog` 的流式区、`SiteManagementV2View` 的 `translateStreamLog`）**展示位置与样式不变**，只改数据来源。
  - 关键：必须按 `sessionID` 隔离，避免并发时日志串到别的会话（参考项目未做过滤，本设计在服务端补上）。
- 结果解析（`parseColumnResult` / `normalizeColumns` / `convertAspxToHtml`）沿用现有 `aiGenerationClient.ts` 的实现，保证解析规则不变。

#### 4.3.2 调用点改造

| 文件 | 现状 | 改为 |
| --- | --- | --- |
| `views/cms/ColumnRecognitionDialog.vue` | `recognizeColumnsWithPrompt`（aiGenerationClient） | `opencodeClient.recognizeColumnsWithPrompt` |
| `views/cms/TemplateRecognitionDialog.vue` | `createGenerationTask('TEMPLATE_GENERATION'/'TEMPLATE_REPAIR')` + `sendMessage` + `getTaskEvents` | 代理：createSession → getGlobalEvents → sendMessage(usage) |
| `views/cms/CommonTemplateRecognitionDialog.vue` | 同上（3 处） | 同上 |
| `views/sites/SiteManagementV2View.vue` `handleTranslatePreview` | `createTranslateMenusTask` + 任务 SSE | 先取 prompt（新端点）→ 代理会话执行；SSE 用 `getGlobalEvents` |

- 服务状态栏与「启动/停止服务」按钮**继续**使用 `/api/admin/v2/ai-generation/opencode/*`，不改。
- prompt 的取得方式：识别用现有 `generatePrompt`，翻译用新 prompt 端点；prompt 文案完全沿用现有服务端构造。

## 5. 数据流（以栏目识别为例）

1. 对话框打开 → `generatePrompt(siteId, filePath, ...)` 取 prompt（内容不变）。
2. `checkServiceStatus()` → `/ai-generation/opencode/status`；必要时 `startOpenCode()`。
3. 「开始识别」：
   1. `createSession(title)` → 得到 `sessionId`（后端已注入全 deny 权限）。
   2. `getGlobalEvents(sessionId, ...)` 建立 `/opencode-proxy/event?sessionId=...` SSE，「AI 流式响应日志」实时追加 delta。
   3. `sendMessage(sessionId, 'COLUMN_RECOGNITION', [{type:'text', text: prompt}])` → 后端注入 agent/system/model 后转发；等待最终响应。
   4. 用现有解析规则从 `parts` 提取文本并解析栏目 JSON。
   5. 关闭 SSE；失败/中止时 `abortSession(sessionId)`。

## 6. 错误处理

- opencode 未就绪：代理返回 503（对齐参考），前端提示「请先启动 OpenCode 服务」。
- 代理转发失败 / 会话无效：透传 HTTP 状态与 `{error}`，前端 `ElMessage` 呈现。
- 进程启动失败：`start()` 返回带真实原因的 `Status.error`（含输出尾部），前端提示可见。
- 流式断开：前端已具备「断开后继续等待最终结果」的兜底（沿用现有逻辑）。

## 7. 测试

- 后端
  - 单元：`AiUsage.executorInstruction()` 映射；代理注入后请求体包含 `agent/system/permission`。
  - 集成（`cms-ai/src/test` 已有目录结构）：对 `/opencode-proxy/session|message` 的注入与鉴权头做断言（可用本地桩/HTTP mock）。
  - SSE：代理 `/event?sessionId=` 仅转发本会话的 `message.part.delta` 文本帧，过滤其它会话与字段。
  - 进程管理：`start()` 在「端口已有健康服务」时复用、不新起；失败时 `Status.error` 含输出片段。
- 前端
  - 类型检查 + 现有 vitest。
  - 手动：三个对话框 + 翻译各跑一次，核对 prompt 与服务端返回一致、流式日志出现、结果解析正常。

## 8. 风险与回滚

- 风险：代理为裸转发，若注入遗漏会导致行为差异 → 由「注入参数」与测试双重兜底。
- 风险：全局 `/event` 流在并发下可能混入他人事件 → 已由服务端按 `sessionId` 过滤消除（较参考实现更严格）。
- 回滚：代理层与前端调用点相互独立；回滚只需把调用点切回 `aiGenerationClient`，后端代理可保留不影响。

## 9. 未决/需确认

- 无（第 4.2.2 的注入范围已按「方案 1」确定）。
