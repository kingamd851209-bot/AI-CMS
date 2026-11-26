# 50 — 管理员账号的目标层级与授组授权

**审查编号** A1
**优先级** P0
**状态** 新旧账号正常写入通过，权限拒绝后已完成隔离数据库回读

## 2026-11-27 实施记录

已实施源码防护，进入隔离验收。新版管理员接口、系统管理兼容接口以及旧管理写入口共用目标层级和授组判定。当前新版界面的重置密码请求仍由兼容接口处理。站点管理员可以修改有权管理的下级组。目标同时具有外站或无权管理的组时，只准维护授权归属，涉及账号本身的改密、资料更新、启停与删除均拒绝。平台管理员可管理站点账号及平台下级组。受保护账号和当前账号使用独立个人资料入口。

写操作在事务内锁定目标账号记录后检查归属。保存中保留操作人无权维护的现有归属；删除账号及所属组关联共用事务。干净快照的 Maven 测试中，`AdminAccountAccessTest` 4 项、`AdminUsersMultiGroupRegressionTest` 1 项通过。验证覆盖兼容改密与旧删除拒绝、站点及平台层级、禁用组和既有多组归属保留。新增测试已从工程源码树移至 `D:/CodexBuilds/cms-workbench/security-review-20261127/auth-evidence/AdminAccountAccessTest.java`，原有多组回归测试保留。待隔离 CMS 账号管理入口、数据库回读及并发实测。未执行实际账号写入。

后续核对发现三套组管理入口也需保护账号授权所依据的父子关系。管理员组详情、保存、启停和删除已复用组范围检查。平台组维护保持超级管理员权限，普通站点账号可在自己的组或获授权的下级组下创建子组。候选父组须同站、可用并在授权范围内，保存拒绝循环关系。删除前同时检查默认组和站点组关联。最终快照中 `AdminGroupBoundaryTest` 两项通过，新增测试原件已移存验收目录。

## 2026-11-27 隔离后台验收

使用最终验收快照 `cms-repair-final-f3702dc7`、Windows Temurin 21.0.11、仅绑定回环地址的一次性 MySQL 8.0 和 Redis 7.4。MySQL 导入 61 张表的 DDL、菜单配置和两站四账号合成夹具。Chrome 通过正常登录表单输入验证码，分别建立两个角色的管理会话。后端就绪检查 HTTP 200。后端 JAR SHA-256 为 `a0eed7ff1b03185c88218fddfec3afcb50bed5eedb5e31f6f82997bbf1b8349d`，工作台版前端入口 SHA-256 为 `62ffa5f1a25b84b120c13f632f5dc56b1e004e8b56bdd7348d9e549c4c8eb144`。

| 操作角色与目标 | 浏览器及隔离数据库结果 |
|---|---|
| 站点管理员维护本站下级账号 | 管理页可见本人及授权目标。新建下拉只显示本站和两个启用的下级组。选中两个组后，新账号与两组关联均写入，展开行同时显示两组。随后修改姓名、重置密码、停用、启用与删除均成功。重置走系统管理兼容接口，通过密码校验状态核对。启停回读对应状态，删除后账号与关联均为零条。 |
| 站点管理员维护含外站归属的账号 | 在编辑界面追加本站下级组，回读时已有本站组、外站组及主组均保留。两个浏览器上下文同时对该账号提交不同的合法本站组修改，两次均返回 HTTP 200，最终主组与留存的一套完整归属一致，外站组保留。 |
| 平台管理员维护另一站点账号 | 管理页显示外站账号。通过编辑抽屉修改姓名及邮箱，数据库回读匹配且原组保留。随后通过同一抽屉提交重复邮箱，返回 HTTP 400，账号字段、密码校验状态及组归属比对一致。 |
| 旧后台入口与本人操作 | 旧账号管理 GET 跳转到新版权限管理入口。从用户菜单打开「修改密码」，提交新密码成功并回读校验。 |

最终快照的 Surefire 报告显示 `AdminAccountAccessTest` 4 项、`AdminGroupBoundaryTest` 2 项及 `AdminUsersMultiGroupRegressionTest` 1 项均通过，零失败、错误与跳过。回归覆盖下级及平台范围、受保护账号、停用组、旧删除和兼容重置判定、组父级及多组保留。此次实测的写入均从正常界面发起。旧版账号写入 POST 和权限拒绝后的数据库状态未做隔离实点，相关行为保留在回归核验范围。未切换 15190。

### 旧版写入补验

Windows Temurin 21.0.11 启动回环隔离后端，使用真实图形验证码及 Redis 会话。平台管理员从旧版账号编辑 POST 修改普通站点账号的姓名和邮箱，HTTP 200，MySQL 回读匹配。密码校验所用摘要和原主组保持一致。这补齐了前次浏览器验收中缺少的旧版写入。另在隔离构建复跑账号目标授权 4 项、组边界 2 项及多组回归 1 项，均通过。拒绝侧以控制器及授权守卫调用核对，样本数据库实写覆盖正常保存、重复邮箱失败和并发归属维护。

[旧入口与数据库回执](<D:/CodexBuilds/cms-workbench/auth51-53-acceptance/receipt.json>) · [回归构建](<D:/CodexBuilds/cms-workbench/auth51-53-acceptance/final-verify.log>)

## 范围

后台新旧管理员账号入口覆盖新建、编辑、改密、授组、启停和删除，同步校验账号详情及组候选的可见范围。保留本人修改资料和密码的正常入口及保护规则。

## 源码定位

- [AdminUsersV2ApiController.java](../../../cms-admin/src/main/java/com/xinhongru/api/admin/v2/AdminUsersV2ApiController.java) 第 142、222 行为保存及写入分支，第 246、274 行分别为启停和删除，第 375 行起为归属校验。
- [UserController.java](../../../cms-admin/src/main/java/com/xinhongru/controller/admin/UserController.java) 第 108、136、193 行对应旧删除、编辑和保存路径。
- [AdminAccountAccess.java](../../../cms-admin/src/main/java/com/xinhongru/api/admin/AdminAccountAccess.java) 为本轮共用权限检查。[AdminUserGroupV2ApiController.java](../../../cms-admin/src/main/java/com/xinhongru/api/admin/v2/AdminUserGroupV2ApiController.java)、[SystemAdminV2ApiController.java](../../../cms-admin/src/main/java/com/xinhongru/api/admin/v2/SystemAdminV2ApiController.java) 和 [UserGroupController.java](../../../cms-admin/src/main/java/com/xinhongru/controller/admin/UserGroupController.java) 使用同一组范围和父级校验。
- [审查记录](../../../../../implementation/CMS系统审查-2026-11-27.md) 的 A1。

## 交付内容

- 使用服务端登录身份和数据库中的现行组归属，按实际操作类型校验目标层级及站点范围。按同一规则校验现有目标和拟授予的新组，平台组须显式获得平台授权。
- 新旧正常入口共用授权判定，盘点全部账号管理路由和兼容映射。复用现有保护账号及账号组模型。站点管理员维护获准的本站范围时，保留不在其管理范围内且允许继续共存的归属。
- 授权须在首次写入前完成。保存账号和归属保持原子性，失败使用现有错误响应和审计约定。

## 验收

- [x] 本地隔离环境中，获授权的站点管理员可维护允许的本站账号，平台管理员可按既有平台规则维护对应账号。合法的旧入口及本人改密仍可使用。
- [x] 新旧入口共用目标、站点和组的许可矩阵，覆盖改密、授组、启停和删除。受保护账号的既有约束由共享守卫和回归核对。
- [x] 拒绝及保存失败后回读账号、密码校验状态和组归属，并从账号管理正常回读成功更新后的多组归属。权限拒绝采用应用内真实库测试，保存失败采用正常管理入口验证。
- [x] 留存角色与目标许可矩阵、脱敏响应、隔离数据读回和回归测试结果。

## 实施依赖

复用现有 ADMIN 会话、组层级、站点归属和受保护账号规则。使用本机隔离数据库中的合成账号验证，无其他工单前置。

## 最终回读

应用内防御集成测试以真实 MySQL 中的站点管理员触发目标账号和授组拒绝。与认证及守卫共用的 JDBC 事务内，对比拒绝前后的账号表、组表和用户站点组关联表，全部相同。新旧正常写入与重复邮箱保存失败有独立 HTTP 或 Chrome 回执。本人的个人资料改密沿用上文既有 Chrome 验收记录，记录包括用户菜单入口、提交成功和数据库密码校验，未找到单独留存的截图。

[最终防御集成测试](<D:/CodexBuilds/cms-workbench/final-dev-integration-437f76a5/final-dev-integration.log>)
