# 54 — 统一内容删除与静态撤下

本轮整合回归使用 Windows Temurin 21 执行 Maven 离线 `verify`。包含三份样本站及默认分页定向用例，共 199 项，198 项通过，1 项需要显式启用开发环境的集成测试跳过，构建成功。默认分页的无筛选回归在修复前出现空指针；修复后与空筛选对象输出一致，使用筛选条件时编码正确且只追加一次页码。见[整合构建日志](<D:/CodexBuilds/cms-workbench/withdraw-acceptance-8d3a669cb/build/final-verify.log>)。

- 审查编号 B1、B2
- 优先级 P1
- 状态：已实施，正常入口、恢复及双端验收完成，站点与栏目权限通过防御回归
- [审查记录](../../../../../implementation/CMS系统审查-2026-11-27.md)

## 代码证据

- [内容管理抽屉与删除入口](../../../admin-ui/src/views/cms/ContentManagementView.vue#L1578)、[单条删除](../../../cms-admin/src/main/java/com/xinhongru/api/admin/v2/CmsCoreV2ApiController.java#L1624)、[批量删除](../../../cms-admin/src/main/java/com/xinhongru/api/admin/v2/CmsCoreV2ApiController.java#L1735)、[物理删除 SQL](../../../cms-core/src/main/resources/mapper/ContentMapper.xml#L262)。
- [服务清理单个详情](../../../cms-core/src/main/java/com/xinhongru/service/content/impl/ContentServiceImpl.java#L880)、[栏目分页生成](../../../cms-core/src/main/java/com/xinhongru/service/oneshot/impl/OneShotServiceImpl.java#L2992)、[正文分页生成](../../../cms-core/src/main/java/com/xinhongru/service/oneshot/impl/OneShotServiceImpl.java#L3153)、[独立导出遍历静态目录](../../../../../cms-local/export-static.py#L504)。

## 实施边界

单条和批量的常规删除通过同一业务服务。保留站点、栏目权限和原生内容状态，明确软删与永久删除各自的入口。数据库失败时验证状态回滚和文件保全。文件清理失败须可重试，重试时避免影响并发重发、仍在使用的同名路径及其他栏目产物。

核对实际产物或生成器的归属规则，清理已撤下内容的全部详情页，并在列表再生成缩页时清理旧尾页。核对旧版本路径、命名和已有静态目录，验证新产物留存。CMS 撤下以后，独立托管需重新导出并替换部署，旧归档作为历史产物单独管理。

## 验收

- [x] 用正常入口分别单删、批删和撤下已发布记录，核对保存状态、删除时间、后台列表以及 CMS 旧地址的实际访客响应。
- [x] 发布多页正文和会缩页的栏目，操作后核对旧分页全部撤下，其余正式页面及共享资源仍可读；重发后核对新分页。
- [x] 验证数据库回滚、静态清理失败与重试，保留状态、异常回执和产物路径。
- [x] 验证跨站及无栏目权限。拒绝侧由共用删除服务防御回归覆盖，正常单删及批删已在修复后重新实测。
- [x] 重新导出并打开替换后的独立站，保留新旧包清单差异和旧地址访问结果。

B1 与 B2 共用删除服务和静态清理边界。独立站测试用新导出和部署结果留证；56 号工单增强独立包的签收校验。

## 本轮实施与验证

单条、批量软删共用内容删除服务。事务内复核权限、站点栏目和关联记录，更新时间及版本并暂存撤下的文件。列表再次生成后清理旧页，按当前站点、路由、渲染回执或旧分页链接核对归属；同一文件重新生成后保留。已有历史产物如果缺失回执及可追溯分页链接，保留待核。

Windows JDK 21 上通过 CMS 站点及管理端依赖编译。定向 Maven 组合共通过 11 项，覆盖临时分页路由和相邻文件、旧链接归属以及现有详情发布契约。隔离站实测记录见下节。

## 2026-11-27 隔离运行验收

Windows Temurin 21.0.11，管理端 15454，CMS 访客端 15455，独立 MySQL 8.4 和 Redis 7.4。通过图形验证码和正常登录建立合成管理员会话。用管理 API 创建站点 CONTENT54、原生列表 #2、首页 #3 和六条合成内容。列表模板根据真实记录分页，每页两个条目。六篇详情各有两页正文。CMS 正常静态发布入口生成首页、三页列表和十二页详情，共十六页，共享 CSS 来自同一站点原包。

- 单删 #2、批删 #3–4、撤回 #5 均成功。数据库保留六行，前三个删除目标的 `deleted=1`，有删除时间且版本递增。撤回记录 `status=0`、`deleted=0`；管理列表保留该条草稿和两条已发布记录。
- 使用临时数据库触发器让单删在暂存详情后写操作日志失败。HTTP 500 后数据库状态和两页详情 SHA-256 与操作前一致。占用第二页文件阻止其移动，HTTP 409 后两页静态详情和数据库均恢复，释放文件句柄后从相同入口重试成功。两次失败时两页访客地址均为 200。
- 撤下后 CMS 实际清除八个详情文件；再次发布收缩列表，清除第 2、3 页文件。首页、列表第 1 页、保留两篇的四页详情和共享 CSS 继续可读。CMS 动态列表仍接受原分页路径，第 2、3 页返回空列表 HTTP 200，无被撤记录和旧分页链接。
- 将 CMS 生成目录与原包冻结为两版，重新导出并通过同一个独立访客源换版。核验文件数分别为 17 和 7，均无问题。换版后原十六个页面地址中，十个旧地址返回 404，六个保留地址返回 200。已记录 Chrome 回读及两版清单。

数据及文件回执见 [内容操作](<D:/CodexBuilds/cms-workbench/content54-acceptance/content54-actions.json>)、[撤下前 CMS 回读](<D:/CodexBuilds/cms-workbench/content54-acceptance/before-visitor.json>)、[撤下后初轮 CMS 回读](<D:/CodexBuilds/cms-workbench/content54-acceptance/after-visitor.json>)、[独立托管换版回读](<D:/CodexBuilds/cms-workbench/withdraw-acceptance-8d3a669cb/actual-cms-delivery/visitor.json>)。本轮未进行跨站和无栏目权限的运行验证。

### CMS 访客详情修正及重发

初次撤下后八个旧详情路径无静态文件，动态渲染将空记录传给模板，返回 HTTP 500。已在渲染前复用有效期内已发布详情的栏目查询，旧地址返回 404。相同路由还验证了站点字段为空、栏目站点归属明确的旧数据继续返回 200。

Windows Temurin 21 执行 `mvn -T 2 -pl cms-boot -am verify`，后端回归 168 项通过，4 项跳过，无失败或错误。启动新包后，实测八个已撤详情地址全部返回 404，四个保留详情地址仍为 200。通过后台发布操作恢复 #5，随后原生静态重发生成九页。该条记录恢复为 `status=1`，两页正文重新生成，原地址均返回 200，且每页内容与对应正文一致。列表第 2 页重新生成，第 3 页仍无静态文件。

原生分页工具在无筛选且有多页时曾因空筛选对象导致 500。修正后恢复使用模板的原生分页入口，两个列表页均返回 200，分别显示两条、一条不同记录。从第一页访问第二页及三个详情链接均为 200，再次生成九页，其中两个列表文件均存在。独立验收结束后已退出临时管理员会话、停止合成站并清除隔离数据库、Redis 和会话文件。

[重发、访客复核与旧数据兼容回执](<D:/CodexBuilds/cms-workbench/content54-acceptance/content54-fixed.json>) · [原生分页复验回执](<D:/CodexBuilds/cms-workbench/content54-acceptance/content54-native-pager.json>) · [最终后端构建日志](<D:/CodexBuilds/cms-workbench/content54-acceptance/withdraw-final-pager-build.log>)

### 当前站点与栏目权限补验

新单删和批删先核对目标栏目所属站点。共用删除服务与旧版批量删除、撤回在事务内按当前授权站点锁定栏目，再按站点和栏目锁定内容。没有栏目操作权限时，服务在访问栏目及内容存储前返回拒绝。批量删除关联检查基于当前站点已锁定的目标记录。

`ContentWithdrawalScopeTest` 三项全部通过。跨站单删和旧批删即使有全站操作权限，也只能按当前站点尝试锁定栏目，无法进入内容读取及写入。普通账号缺少栏目权限时，栏目和内容 Mapper 均未调用。拒绝侧是守卫回归，修复包在隔离后端另用正常入口做正向销项，选中本站后单删一条、批删两条均 HTTP 200，MySQL 三条记录均已软删。完整 Maven 离线构建成功，184 项中 180 项通过、4 项跳过，无失败或错误。

[修复后正常入口与数据库回执](<D:/CodexBuilds/cms-workbench/auth51-53-acceptance/receipt.json>) · [权限回归构建](<D:/CodexBuilds/cms-workbench/auth51-53-acceptance/final-verify.log>)

关联记录沿用已有保全规则。若历史数据中另一个站点的未删记录仍引用目标，全局关联检查会阻止删除；应先核对真实引用的归属和用途。此次未放宽检查或自动清理历史引用。
