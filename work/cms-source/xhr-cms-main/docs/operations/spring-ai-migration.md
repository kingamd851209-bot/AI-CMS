# Spring AI + OpenCode 混合执行

## 2026-11-08：本地开发者工作台的交付边界

工作台与客户 CMS 共用源码中的模型、认证和 UI 组件，但生成不同的前端产物，并使用独立运行配置、数据库、Redis 和凭据。它还不是完全独立的后端产品。普通 `npm --prefix admin-ui run build` 不包含工作台页面；`build:workbench` 才编译开发者入口 `/admin/v2/site-build`，仅平台管理员可进入。两种产物不可混用同一个交付目录。

新 `/api/admin/v2/sites/{siteId}/template-seed/**` 端点只在 `cms.workbench.enabled=true` 时注册，默认关闭；后端再次校验平台管理员、站点和栏目权限。客户部署保持关闭，且不得携带内部网关密钥、开发数据库或工作目录配置。该开关不替代旧 CMS 全部 AI 接口的权限策略。

已实现：建站入口、UI ZIP 上传、静态页面候选提取、只读 AI 分析、字段显式映射后单条草稿导入。写入复用 `ContentRecordService`；校验模型启用字段、页面摘要、站点栏目归属，在栏目行锁内拒绝同标题重复导入。图片等站内资源转为 `ui:/`。导入不发布内容、不覆盖模板。

工作台只读分析使用 `WORKBENCH_ANALYSIS` 经 `AiTextGenerator` 调用，不从此入口触发旧识别流程的自动模板写入。最多取 100 个候选，输入不超过 2 万字符，输出最多 2 万字符和 4096 completion tokens；单实例单并发、60 秒总超时、零重试。失败/超限取消本地订阅，但已发生的供应商推理费用不能保证撤销。仅审计请求号、站点、操作者、时间、结果与字符数，不记录密钥、完整提示或输出。当前接口未暴露供应商 token 元数据，返回 `tokens=null`，不能将字符数当作计费用量。

金额预算、跨实例调用配额和持续费用审计尚未实现。内部使用前应在网关为工作台签发独立限模型、限额度的 Key；客户自己的 AI 能力使用客户的独立配置。普通生成仍从 `cms.ai.generation` 环境配置读取凭据；旧数据库 Provider 配置页面不等同于此配置入口。

本地验证：JDK 21 Maven 全模块打包测试、两种前端构建均通过；真实本地登录、ZIP 上传、模型配置、候选提取和单条草稿入库通过；新分析接口通过网关返回只读建议。临时测试覆盖路径、ZIP、映射、重复、异常脱敏、并发与取消，验证后已从源码移除。详见工作目录 `work/cms-local` 的测试日志及输出目录的开发记录。真实多页面交互推断、批量初始化、关系确认和整站 Thymeleaf 渲染验收尚未完成。

项目在 Spring Boot 4.1 / Java 21 上使用 Spring AI 2.0.1。普通模型调用与复杂任务执行分开部署、分开限流，前端只使用 CMS 任务协议。

## 业务路由和代码边界

| 用途 | 执行方式 |
| --- | --- |
| `COLUMN_RECOGNITION` | Spring AI：栏目 JSON |
| `MENU_TRANSLATION` | Spring AI：翻译文本 |
| `CONTENT_TRANSLATION` | Spring AI：多语言站点同步中的内容、单页及字段业务名称批量翻译 |
| `TEMPLATE_GENERATION` | Spring AI：公共模板、内容模板生成 |
| `AI_PAGE_GENERATION` | Spring AI：HTML 页面生成 |
| `TEMPLATE_REPAIR` | OpenCode：读取上下文、编辑模板副本、返回修复后的完整 HTML |

`AiTaskRouter` 按业务用途路由；`AiTextGenerator` 和 `AgentExecutor` 是两个独立接口。OpenCode 的认证、会话和结果格式只存在于 `ai/agent/opencode` 适配器内。控制器、任务注册表、前端页面不依赖其原始事件或 session ID。

模板修复的业务指令、上下文文件和可编辑文件清单由 `TemplateRepairRequest` 组装。`AgentExecutor.Request` 仅传递通用任务描述，OpenCode 适配器负责把文件清单转换为运行目录和工具权限。

不再从 Java 启停进程、按端口杀进程，也不再使用 CLI 子进程或转发 OpenCode 全局 SSE。Spring AI 不依赖 OpenCode 的可用性；复杂任务未启用或不可用时明确失败，不偷偷降级为普通模型调用。

此版本的复杂任务入口是模板修复。它使用文件工具处理草稿，不包含浏览器自动化、Shell、自动执行 CMS 渲染或自动发布。用户仍在原界面预览并应用结果。后续可在 `AgentExecutor` 之外增加受控的业务验证编排，无须改前端的任务协议。

## 配置入口

只编辑当前环境的 `cms-boot/src/main/resources/application-{dev|test|uat|prod}.yml`，每项含用途注释：

| 配置组 | 管理内容 |
| --- | --- |
| `cms.ai.generation` | Spring AI 的开关、模型地址、API Key、模型名称、超时、重试和并发 |
| `cms.ai.agent.opencode` | OpenCode 的开关、服务地址和认证、供应商及模型 ID、工作目录及执行限制 |
| `cms.ai.secret-master-key` | 原有 AI 工作台数据库凭据的加解密密钥，与上述两个 API Key 不同 |

通用 `application.yml` 保留公共框架设置、站点独立进程参数和默认环境 `dev`。AI、SSO 等业务配置在各环境 YAML 中使用直接值，不再使用自定义 CMS 环境变量占位。`${user.dir}`、`${user.home}`、`${java.io.tmpdir}`、`${server.port}` 继续用于路径和端口引用。IDEA 的 Working directory 应选择当前 main 仓库根目录。

切换环境使用 `--spring.profiles.active=test`（也支持 `uat`、`prod`）。需要外部文件部署时，仍可用 Spring 标准的 `--spring.config.additional-location=file:/etc/cms/` 加载相同结构的环境 YAML；没有额外的自定义优先级或配置中心。按实际环境配置并启用所需服务。

### Spring AI

在 `cms.ai.generation` 填写 `base-url`、`api-key`、`model-name`，然后将 `enabled` 设为 `true`。所有普通生成用途共用这一组模型设置，只按用途选择提示词。代码不再查询数据库模型配置，也不再做用途回退。

支持流式 OpenAI 兼容 Chat Completions，地址支持 API 域名、`/v1` 根地址或完整 `/chat/completions` 地址。其他原生协议需要独立适配。Spring AI 不注册文件、Shell 或浏览器工具，目前只支持文本输入。

后台「AI 模型配置」仅管理原有 AI 站点工作台的站点识别、字段识别和诊断摘要，原有数据与加解密方式保留。页面已移除本次迁移添加的普通生成用途，避免出现两处看似都可配置的入口。若之前已在数据库配置了普通生成模型，请把地址、密钥和模型填写到当前环境 YAML；已有数据库记录不会自动迁移或删除。

## 手动部署 OpenCode 后接入

OpenCode 由运维手动部署和管理。CMS 不提供 Docker 配置、部署脚本或自动配置导出入口，也不启停 OpenCode 进程。

1. 在 OpenCode 服务端配置模型供应商地址、API Key、模型以及模板修复执行器；执行器名称与 CMS 的 `agent` 一致，默认 `cms-template`。
2. 在当前环境的 `cms.ai.agent.opencode` 设置 `enabled`、`base-url`、`username`、`password`、`provider-id`、`model-id` 和 `agent`。CMS 只保存调用服务所需的配置，模型供应商凭据由 OpenCode 管理。
3. 配置 `workspace-root` 为 CMS 可写的绝对目录。OpenCode 在同机访问相同目录时，`remote-workspace-root` 留空；两端路径不同时配置远端对应的绝对目录，并保证两端访问同一份文件。
4. OpenCode 服务需要支持指定文件的读取和模板副本编辑。CMS 为每个任务下发文件权限范围，禁止其他工具，并检查健康状态和执行器是否存在。

只使用 Spring AI 普通生成与翻译时，无须启动 OpenCode。真实模型能力、服务认证、目录映射和文件权限由部署后验收确认。

### API 和模型兼容性

适配器使用官方 headless server 的会话 API：

- `GET /global/health`：只有成功响应且 `healthy=true` 才通过。
- `GET /agent`：必须包含 `cms.ai.agent.opencode.agent` 指定的执行器，默认 `cms-template`。
- `POST /session`：创建独立会话并设置本任务工具权限。
- `POST /session/{id}/message`：显式指定 agent、provider 和 model；等待该会话的最终响应。
- `POST /session/{id}/abort`、`DELETE /session/{id}`、`POST /instance/dispose`：取消和清理。
- 上述工作空间请求通过 `directory` 查询参数指定远端绝对路径。

最终响应必须属于所创建的会话、角色为 assistant、无 error，且 `finish=stop`。只提取 text parts，随后执行 CMS 的 HTML 输出校验。适配器不订阅远端全局事件；前端看到的是 CMS 进度和最终输出，不是 Agent 的逐 token 或工具执行日志。

接口依据[官方 Server 文档](https://opencode.ai/docs/server/)核对，并使用[官方 1.18.28 版本](https://github.com/anomalyco/opencode/releases/tag/v1.18.28)实际验证。Java 客户端固定 HTTP/1.1，避免 JDK 默认 h2c 升级导致创建会话后收不到响应；文件权限同时匹配绝对路径、项目相对路径和实例相对路径，仍只放行指定文件。不同大版本可能改变协议，镜像升级前需要验证上述契约，不要直接使用 `latest`。

模型必须支持执行器需要的工具调用。供应商协议、依赖准备和状态目录持久化均在手动部署的 OpenCode 服务端配置。

## 任务协议、限制和清理

前端协议统一为：

1. `POST /api/admin/v2/ai-generation/tasks`，传 `siteId` 和 `usage`，获得 CMS task ID。
2. `POST /tasks/{id}/message` 提交 prompt，返回 HTTP 202 和任务快照，不挂住请求等待整个修复结束。
3. `GET /tasks/{id}/events` 订阅 `delta / progress / done / failed / cancelled`。
4. `GET /tasks/{id}` 查询 `state / text / error / timeoutMs`。只有 completed 的 text 是最终结果；进度断开仍可查询结果。
5. `POST /tasks/{id}/cancel` 取消；浏览器主动取消会向 CMS 发送取消请求。

每个接口按当前登录用户校验任务归属，并重新验证站点访问权限。浏览器不能指定服务地址、模型、agent、工作目录或远端 session ID。

- Spring AI 默认每实例 4 个并发调用，模型总超时最多 300 秒。使用 Spring AI 2 的 OpenAI SDK 适配，CMS 统一控制重试；仅在尚未输出正文时重试临时故障。完成、失败、取消和超时均关闭客户端并释放并发额度。
- OpenCode 默认 1 个并发任务，总超时默认 600 秒，可配置范围为 10–1800 秒。执行器忙时失败，不在 Java 中无限排队。
- 任务注册表最多保留 200 个任务，每用户最多 8 个未终结任务。
- prompt 最多 200000 字符，输出最多 2000000 字符。每任务最多重放 256 条进度事件。
- 终结结果保留十分钟；尚未提交的任务十分钟后清理。运行时间较长的任务从终结时开始计算保留时间。
- 每个复杂任务创建 UUID 目录，只暂存 context.txt 和 template.html。只允许读取这两个文件及编辑模板副本，其他工具默认拒绝。此默认目录不是多租户强隔离容器；若以后允许 Shell、任意文件或浏览器，需要重新设计隔离边界。
- 正常结束删除远端会话、释放远端工作空间实例，再删除本地副本。
- 取消、超时、异常先请求远端 abort，再清理。创建会话期间取消，也会等待已发出的创建请求完成，再清理该会话。
- 若无法确认 abort 或清理成功，适配器停止接收新复杂任务并保留工作目录，避免在旧任务可能还运行时直接复用环境。管理员确认并重启 OpenCode 和 CMS 后恢复；常规模型任务不受影响。

结果应用沿用站点目录约束、原子替换和五份历史记录。HTML 校验只检查基本输出形态，不能证明 Thymeleaf 渲染正确，仍需预览。

## 部署边界

当前任务注册表仍在 CMS 内存中，适用于单实例部署。没有自动断点续跑或分布式任务调度；重启 CMS 会丢失任务查询记录。正常关闭会发出取消请求；强制终止 CMS 时，运维应同时停止 OpenCode，确认没有遗留执行后再清理任务目录和恢复服务。

多实例部署前需要持久化任务归属、执行节点、结果和事件游标，并增加共享并发控制。不能简单把 CMS 多副本指向同一个工作目录就当作已经支持分布式执行。

Spring AI 模型和 OpenCode 服务认证由 CMS 环境 YAML 管理；OpenCode 模型供应商凭据由其服务端管理。本版本没有新增硬性 token/费用预算，需结合模型网关配额、执行步数和超时控制成本。

## 构建、验证和回退

使用 JDK 21，并先构建管理端再打包：

```sh
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 21.0.12-tem
npm --prefix admin-ui run build
mvn -T 2 clean package
```

前端构建包含 Vue/TypeScript 类型检查，固定将 standalone 资源写入 `admin-ui/dist/`，由 Web 服务器独立部署，不收入 Maven JAR。现有主包超过 500 kB 的 Vite 提示不阻止构建。

Maven 保留 main 的 Playwright 归档裁剪优化、Ant launcher 依赖及 `forceCreation` 设置，最终 JAR 面向 Linux x64。内嵌依赖保留 STORED 存储方式；打包末尾执行 `java -Djarmode=tools -jar ... list-layers` 检查最终启动器与依赖加载。

合并 main 时保留了多语言站点生成、翻译结果编辑、内容与单页同步、字段业务名称翻译、栏目树模板路径、bizcode/contentCode 提示词、站点端口校验、Headless 认证、表单服务和 SSO。原多语言批量翻译改由 `AiTranslationService` 调用 Spring AI，保留换行、HTML/模板变量、序号映射和缺失译文回退。补齐了 main 引用但未入库的表单服务配置面板，接入现有密钥管理与接口文档 API。

本机验证使用 JDK 21.0.12-tem：干净编译、8 个现有测试、管理端类型检查及 standalone 构建、最终 JAR 加载检查。仓库外的临时回归程序验证 Spring AI 对本机模拟模型的真实 HTTP 流式调用、批量翻译、任务取消与归属检查、Agent 关闭时普通生成可用、模板路径限制及五份历史记录。

完整应用启动验证使用临时 H2 数据库和模拟 Redis，检查 Spring Bean/路由装配、健康检查、管理端登录页与静态资源，以及未登录 AI API 请求的拒绝。该验证不连接实际业务数据库，不代表真实模型输出质量、外部 OpenCode 部署或所有站点页面已通过验收；这些仍需在 main 上按实际配置验证。

设置 `cms.ai.agent.opencode.enabled: false` 并重启 CMS 可停用复杂任务，Spring AI 普通生成与翻译继续运行。回退应用版本时同时检查执行器和遗留任务，保留上一版应用、配置和模板历史。
