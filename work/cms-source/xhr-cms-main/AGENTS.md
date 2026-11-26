# Hongru CMS Agent 开发指南

本文件是仓库根级 Agent 默认说明，适用于整个项目。目标是让 AI 在动手前理解真实工程边界、运行约束和验证方式。若子目录以后增加更具体的 `AGENTS.md`，以离目标文件最近的说明为准；用户当前指令始终优先。

## 1. 开始工作前

- 先阅读本文件，再按任务阅读相关文档和代码。不要只依据类名、注释或旧文档推断行为。
- 先执行 `git status --short`，检查目标文件的现有 diff。工作区可能包含用户尚未提交的改动，必须保留并避免覆盖。
- 优先用 `rg` / `rg --files` 查找实现、调用者、Spring 装配、Mapper XML、前端 API 和模板引用；修改公共契约前必须检查全部调用方。
- 本仓库采用 Maven 多模块结构。架构边界以 `docs/architecture/maven-modules.md`、各模块 `pom.xml` 和当前代码为准。
- `docs/AI前端动态数据绑定手册.md` 包含有价值的业务规则，但部分 `src/...` 路径来自模块化前结构。使用时要映射到当前模块，不能照抄旧路径。
- 配置文件可能含环境地址或凭据。不要在日志、回复、测试快照或新文档中泄露实际值，也不要擅自替换现有环境配置。

## 2. 项目定位

- 技术栈、模块职责、构建和运行命令统一查阅 `README.md`，不要在本文件复制维护第二份版本。
- 根 `pom.xml` 是聚合父 POM；`cms-boot` 是唯一应用入口和可执行 JAR 模块。
- 所有命令从仓库根目录执行。运行路径依赖 `${user.dir}/runtime`，不要从子模块目录启动应用。

## 3. JDK 强制约束

本项目只使用 JDK 21。执行任何 Java 编译、测试、打包或启动命令前，必须在**同一个 shell** 中通过 SDKMAN 选择本机已安装的 JDK：

```sh
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 21.0.12-tem
java -version
```

- 不使用系统默认 JDK，不修改机器的全局默认 JDK。
- 若 `21.0.12-tem` 不可用，先用 `sdk list java` 或检查 SDKMAN 本地版本，只选择已安装的 JDK 21；不要擅自安装或切换为其他主版本。
- 每个新的 shell 命令会话都要重新执行 SDKMAN 初始化和 `sdk use`。

## 4. 模块边界

完整结构与依赖图见 `README.md` 和 `docs/architecture/maven-modules.md`。Agent 修改代码时重点遵守：

- 共享类型或跨模块端口放在 `cms-contracts`，实现放在拥有业务能力的模块。
- `cms-core` 不得依赖 `cms-site`、`cms-admin` 或 `cms-boot`。
- `cms-admin` 是 HTTP 适配层，不得被业务模块反向引用。
- `cms-boot` 只做装配和可执行打包，不在其中堆积业务逻辑。
- 所有 profile 都关闭循环引用。禁止用 `@Lazy` 或开启 `allow-circular-references` 掩盖设计问题。
- Java 包名有历史兼容性，不能只按包名前缀判断物理模块；先看文件所在模块和 POM。

## 5. 代码与资源位置

- Mapper XML 只放在 `cms-core/src/main/resources/mapper`；环境 YAML、内置模板和静态资源在 `cms-boot/src/main/resources`。
- Vue 源码在 `admin-ui/src`，standalone 构建结果在 `admin-ui/dist/`。管理端不写入 `cms-boot` 资源，也不进入后端 JAR。
- 真实站点模板和 UI 包位于 `runtime/`，它们是受 Git 管理的业务资源，不是构建缓存。
- `target/`、`node_modules/`、`admin-ui/dist/`、上传目录和日志是未版本化产物，不要纳入业务修改。

改模板前检查同站点片段、Controller/Service/Mapper 数据链、CSS/JS 依赖和已有 diff。

## 6. 后端核心约定

### 实体、Mapper 与分页

- 持久化实体按实际主键类型继承 `IntegerEntity` 或 `LongEntity`；不要给表强加不存在的审计、租户或软删除字段。
- MyBatis-Plus Mapper 继承 `CmsMapper<T>`；定制业务 SQL 保留在对应 Mapper XML。
- MyBatis XML 的参数名、返回字段和实体属性必须一起核对，例如历史字段 `class_id` 映射为 `classid`，不能想当然改名。
- 分页只使用 MyBatis-Plus：显式 `IPage`、`Pages.query` 和 `PageResult`。禁止重新引入 PageHelper 或线程局部分页状态。
- 反射、导出、模板与 JSON 必须继续识别实体继承字段；相关公共逻辑见 `EntityFields`。

### 内容模型和写入

- `ContentFieldDefinition` 是字段目录、栏目 `modelField` JSON、编辑器、模板和校验的统一定义。
- `ContentModelCodec` 必须兼容已有 JSON、字段顺序、开关值和校验属性；修改时运行对应契约测试。
- 内容唯一存储为 `t_contents`，审计写内容历史表。不要引入第二套内容表或同步中间层。
- 内容更新必须同时校验站点、栏目和记录归属；不存在或越权记录不能转成新增。
- 只接受模型启用的业务字段，不能让请求修改 ID、站点、栏目、状态等系统属性。

### Session、安全和 API

- 管理员、会员和 SSO 最终都使用 Spring Session Redis 的 `SESSION` Cookie，身份域分为 `ADMIN` 与 `MEMBER`。
- Redis Session 只保存稳定的 `SessionPrincipal(id, realm)`；账户状态和角色从数据库重新读取。
- 登录需要轮换 Session ID，退出使整个会话失效。不要恢复旧会员 JWT 或明文用户 ID Cookie 认证。
- 写接口必须遵守现有同源/可信来源、权限和站点访问校验，不能为了测试方便绕过拦截器。
- 新管理 API 优先沿用 `/api/admin/v2/...` 风格和现有统一响应/异常处理；兼容旧路由时先检查前端及外部调用者。

### AI 边界

- 普通生成经 `AiTextGenerator` / Spring AI；AI 建站工作台以自研领域 Agent 为主流程。现有 `AgentExecutor` / OpenCode 只按原功能边界保留，不作为建站识别失败后的隐式回退。
- 建站顺序为整包 header/footer 交叉识别栏目、人工审核结构、跨列表与详情汇总并增补字段、审核初值、生成与绑定整站 Thymeleaf 模板、静态生成及实际浏览验证。共享模板不能合并栏目或推断记录相同，导航高亮不承担内容归属。
- 领域 Agent 只处理常规网站的分析、模型、字段、初值与模板绑定，调用已有 CMS 功能。专用接口、定制业务或复杂定制交互只保留证据并标记人工开发，不自动编写定制实现；独立的常规栏目可继续。
- 业务模型按各项目页面与维护需求自主分析，新闻、产品等只作示例，不是固定模型白名单。跨项目复用判断方法，不自动套用模型或字段名称；已有确认结果允许经审核增补和修正，不能静默覆盖人工内容。
- 页面级字段识别只面向主体内容，排除站点公共头尾和控件动作地址；正文内部的局部header/footer不能误排。整站阶段保留并分析公共导航、版权/备案、二维码等，按用途归入公共配置或有独立页面的辅助栏目，并分别核对公共模板与页面模板绑定。不能为二维码或版权文字机械地创建独立栏目。
- 动态页面 HTTP 200、已配置模板路径、识别完成均不等于静态发布完成；必须分别记录实际字段、内容、模板、路由和静态产物状态。
- 整站续分析需沿用同站同包、来源与实际栏目一致的已应用导航关联；新入口单列审核，不因新计划标识而重新猜测已确认栏目或重复创建。继承来源与修订须明确展示。
- 模板重新应用须保留已确认的公共字段、导航和业务按钮动作，组合校验不得退化为放行任意 HTML 差异。表单参数预填须分别验证动态页面和实际生成的静态 HTML；静态表单提交仍须保留同源 CMS 接口及验证码。
- OpenCode 协议、认证、会话和远端路径只应存在于 `cms-ai` 的适配器中；Controller 和前端只使用 CMS task ID 与 CMS 事件协议。
- Agent 任务只能访问明确授权的上下文和模板副本。不要新增任意 Shell、浏览器或文件系统权限。
- 不调用真实收费模型、不启停外部 OpenCode，也不修改其部署，除非用户明确要求并提供可用环境。
- 详细路由、限制和清理语义见 `docs/operations/spring-ai-migration.md`。

## 7. 前端和模板约定

- 管理端所有后端接口统一使用 `/api/` 前缀；独立部署的 API base 只填后端 origin，不重复追加 `/api`。
- 修改后端请求/响应时，同步检查 `admin-ui/src/api`、相关 view/store/component 和兼容旧接口。
- `admin-ui` 只使用 standalone 方式；`npm run build` 先执行 Vue/TypeScript 检查，再将资源生成到 `admin-ui/dist/`。
- 当前没有 Vitest 依赖和 `test` 脚本；不能声称 `npm run build` 已执行前端单元测试。
- Thymeleaf 模板不能通过直接打开 HTML 验证。动态绑定至少要贯通：栏目/内容数据 -> Service/Mapper -> Model 或模板工具 -> 模板 -> 路由/详情页。
- 模板中的栏目 ID、内容 ID、`singleLineN`、`noteN` 等扩展字段语义不是全局常量，必须结合目标站点字段配置和实际数据核实。
- 列表查询避免加载富文本大字段；复杂分组、分页和共享查询放到 Controller/Service/Mapper，不要在模板中重复调用或做复杂数据处理。
- 模板改动要处理 `null`、空字符串、空白文本、空列表、失效内容和正确链接，不能只验证有数据的理想路径。
- 页面数据沿用 `TemplateModelContributor.supports/contribute` 或自定义路由显式注入，URL 继续由栏目路由选择模板。公共工具可通过 `th:with="ui=${@CommonUtils}"` 声明局部别名；当前 Thymeleaf 限制在 `th:src`/`th:href` 中直接访问 Bean，这些属性使用普通变量或局部别名。页面模型不能直接加 `@`，也不应改为单例 Bean。IDEA 类型提示按需维护，不自动生成声明或监听模板文件；静态导航不能替代真实渲染验证，具体流程见动态绑定手册 1.6 节。

## 8. 数据库与配置变更

- `database/init_database.sql` 和 `database/init_site_sany.sql` 用于新环境初始化，其中包含重建表操作，禁止用于既有业务库升级。
- 既有环境的结构变化新增经审核的增量 SQL，命名为 `yyyyMMdd_说明.sql`，例如 `20260921_增加栏目编码.sql`。
- 不自动执行初始化或增量 SQL，不删除/重写业务数据；数据库写操作需要用户明确授权和备份意识。
- 修改表结构时同步核对实体、Mapper/XML、服务校验、初始化 SQL、增量 SQL 和相关测试。
- 环境 YAML 使用明确配置值。不要自行引入一套新的 `${CMS_...}` 变量体系；外部部署配置可使用 Spring 标准 `--spring.config.additional-location`。
- 不把密钥、密码、Token 或真实个人数据新增到源码、测试、日志和答复中。

## 9. 格式和编辑原则

- Java 使用 google-java-format 1.36.0 的 AOSP 风格：4 空格、UTF-8、LF、文件末尾换行、整理 import。
- 优先做范围最小、职责清晰的修改；不要在修复局部问题时顺手重写大文件、批量改名或格式化无关代码。
- 不编辑生成物、压缩第三方资源或依赖目录。新增源码放入正确模块，不恢复已删除的旧根 `src/`。
- 最终交付和拟提交变更只保留正式功能实际使用的有效代码、必要配置，以及用户明确要求维护的项目规则。不得保留本次任务新增的临时测试、调试脚本、验证报告、实验代码、未使用文件或其他非交付产物；必要的使用说明优先更新项目已有文档，不额外生成总结文件。清理仅针对本次新增内容，不擅自删除仓库原有文件或用户已有改动。
- 不通过新增兼容分支、空 catch、关闭校验或放宽安全边界来隐藏错误。
- 若文档、测试和代码冲突，先以可运行代码和明确架构约定定位事实，再同步修正文档或指出差异。
- 具体格式化命令见 `README.md`；工具 JAR 和临时文件放在仓库外。

## 10. 构建、测试与运行

- 具体命令及并行构建、增量构建、包体积说明以 `README.md` 为准。
- 当前依赖图推荐 `mvn -T 2 ...`；不要在高核机器上盲目使用 `-T 1C`。日常迭代不加 `clean`，完整交付才执行干净构建。
- Java 改动至少运行目标模块及上游依赖测试；公共契约改动运行完整后端测试。前端改动至少运行 `npm --prefix admin-ui run build`。
- 完整交付分别生成 `admin-ui/dist/` 和后端 JAR；两者独立部署，不得把前端产物复制进 `cms-boot`，也不得跳过测试来掩盖并行或依赖问题。
- 保留 `cms-boot` 的 Linux x64 Playwright 裁剪与 Boot layers 检查。默认包只含 MySQL 驱动，达梦/DWS 部署使用 `vendor-database-drivers` profile。
- 真实 dev 集成测试只在任务明确需要、数据库和 Redis 可访问且确认风险后运行；普通回归不得依赖外部服务。

### AI 新增测试文件的清理规则（项目级长期约定）

- 本规则适用于在本仓库工作的所有 AI Agent。除非用户明确要求保留，本次任务新增的测试源文件、测试脚本、fixture 和测试专用临时资源仅用于验证；每次代码编译、测试执行结束后必须清理，不作为最终交付内容留在仓库中。
- 清理前记录实际执行的命令、结果及失败原因。测试失败时仍须如实报告并修复问题，不能通过删除测试、跳过测试或只重跑剩余测试宣称验证通过；需要再次验证时重新创建必要的临时测试，运行后再次清理。
- 只清理本次任务新增的测试文件及资源；仓库原有测试、用户已有改动和业务资源必须保留，除非用户明确要求删除。不得以清理测试为由批量删除整个测试目录。
- 收尾时检查新增文件和最终 diff，确认没有遗留本次新增的测试文件或测试专用临时资源，并在交付说明中报告验证结果及清理情况。

## 11. 任务完成标准

- 说明实际修改了什么，以及为什么修改位于该模块。
- 报告真正执行过的验证命令和结果；未执行的测试、浏览器验收、真实数据库/Redis、SSO、模型或 OpenCode 验证必须明确说明。
- Java 编译成功不等于模板渲染、浏览器交互或部署成功；前端构建成功也不等于已执行前端单元测试。
- 检查 `git diff --check` 和 `git status --short`，只归纳本次修改，不把用户原有改动算作自己的成果。
- 不自动提交、推送、部署、执行数据库脚本或调用收费服务，除非用户明确要求。

## 12. 文档路由

- 构建、测试、运行和格式化：`README.md`。
- 模块、实体、Session 与内容模型边界：`docs/architecture/maven-modules.md`。
- AI/OpenCode 任务：`docs/operations/spring-ai-migration.md`。
- 模板绑定任务：`docs/AI前端动态数据绑定手册.md`；其中旧 `src/...` 路径必须映射到当前模块。
- 前端或数据库专项任务：分别读取 `admin-ui/README.md`、`database/README.md`。


## Agent skills

### Issue tracker

使用本地Markdown任务，不发布远端工单。约定见[任务跟踪](docs/agents/issue-tracker.md)。当前任务入口为[建站工作台任务索引](.scratch/cms-ai-workbench/任务索引.md)。

### Triage labels

采用默认分类词汇，见[任务标签](docs/agents/triage-labels.md)。ready-for-agent不等于已获得启动开发授权。

### Domain docs

复用已有项目上下文和架构文档，见[领域文档约定](docs/agents/domain.md)。

### 独立源码副本的本地集成

- 从独立副本合入时逐文件核对基线与结果SHA，禁止整目录覆盖或复制target产物。写入主源码后刷新合入文件时间戳；复制保留的旧时间戳会使Maven增量编译误判最新，必须确认目标模块实际重新编译后再验证/打包。
- 每阶段冻结源码、测试源和辅助测试类及各自SHA，后续阶段不得覆写此前验证所对应的测试快照。


### 本地前端构建快照

- 客户版输出admin-ui/dist，内部工作台输出admin-ui/dist-workbench；制作内部工作台运行快照必须复制后者。运行前核对构建模式、快照index哈希并以真实浏览器打开工作台路由，不能只凭构建成功判断模式正确。已运行快照保持不变，修正模式使用新快照名。
