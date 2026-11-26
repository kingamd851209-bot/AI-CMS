# 51 — 平台配置授权与定时发布站点归属

**审查编号** A2、A3
**优先级** P1
**状态** 新旧配置与两站定时任务隔离验收通过，拒绝后已完成隔离数据库回读

## 范围

后台全局配置的新旧读取和保存入口，以及定时发布任务的读取、配置变更、启停和历史查询。

## 源码定位

- [SystemAdminV2ApiController.java](../../../cms-admin/src/main/java/com/xinhongru/api/admin/v2/SystemAdminV2ApiController.java) 第 820、837 行起为设置读取及保存。[SettingController.java](../../../cms-admin/src/main/java/com/xinhongru/controller/admin/SettingController.java) 第 39、52 行起为旧设置页面及保存。
- [SchedulePublishController.java](../../../cms-admin/src/main/java/com/xinhongru/api/admin/v2/SchedulePublishController.java) 第 22–67 行为任务列表、详情、创建、更新、删除、启停和历史入口。[SchedulePublishServiceImpl.java](../../../cms-core/src/main/java/com/xinhongru/service/cmsPlatform/impl/SchedulePublishServiceImpl.java) 第 63、69、77、87 行为任务变更和历史查询。
- [审查记录](../../../../../implementation/CMS系统审查-2026-11-27.md) 的 A2、A3。

## 交付内容

- **全局配置** 两套入口执行相同的平台级读写授权、字段校验和敏感变更确认。敏感值在响应及页面模型只提供是否已配置；明确区分保留、更新与清除的现有语义，更新前端调用方。
- **定时发布** 根据服务端授权的站点范围定位任务，创建时从已授权站点绑定归属。详情、历史和全部变更按任务及站点校验；变更以任务和站点双条件执行，仅接受允许编辑的业务字段。

## 验收

- [x] 平台账号通过新旧正常入口读取并保存。站点账号在两套入口受共用平台守卫限制，拒绝后配置表保持原值。
- [x] 新旧入口只显示敏感配置状态。未确认变更均返回 HTTP 400，MySQL 原值不变。确认后写入并回读配置标记。非法配置由共享校验拒绝，应用内真实库测试核对原值。
- [x] 两个隔离站由各自账号完成任务列表、详情、创建、编辑、启停、删除和历史查询。越界拒绝采用应用内授权守卫及站点限定服务测试，回读任务与历史的原状态。
- [x] 编辑使用路径任务 ID 和会话站点，正常请求可完成任务配置。
- [x] 留存脱敏回执、任务与历史数据库回读、守卫回归和构建结果。历史由测试夹具预置，调度执行未验。

## 实施依赖

复用平台授权、站点上下文、敏感配置确认及定时任务存储；以两个合成站点验证。可与 50 并行实施，合并时核对共享控制器的变更。

## 本轮执行记录

新版设置读写、旧版保存以及品牌与集成写入接受平台授权检查，受保护配置返回占位标记，保存标记时保留原值。定时任务由当前有效站点绑定，查询、变更及历史校验归属，修改仅映射业务字段。干净副本运行 SettingsAndScheduleScopeTest 两项通过。仍需通过正常界面核对两站任务的提交回读与旧版操作。

复核后补齐专用集成写入口的密钥确认。替换密钥须提交 `confirmedSensitiveKeys` 并包含 `ai.apiKey`，提交掩码、空值或原值时跳过该字段写入。未找到生产管理前端调用专用写入口。定时任务新增及保存所提交的栏目范围采用正常前端的整数 JSON 数组。拒绝空范围、重复或非法编号、已删除栏目和他站栏目。两项补充改动进入统一 Maven 验证。

## 2026-11-27 隔离补验

Windows JDK 21、回环 MySQL 与 Redis。平台账号从图形验证码登录后，通过新旧系统配置保存名称，真实 MySQL 回读与页面接口一致。合成 `ai.apiKey` 未确认保存时，旧控制器原先被日志切面转成空 HTTP 200。修复后旧入口直接给出校验回执，与新版均返回 HTTP 400，数据库摘要不变。两入口完成兼容配置确认后均返回 200，读取只显示配置标记。新旧设置的授权拒绝和定时配置的站点拒绝映射为 HTTP 403。

各站管理员切入其站点，走正常定时配置接口完成新增、列表、详情、更新、启用、停用、历史读取和软删。按任务回读所属站点、栏目 JSON、备注、启停状态和删除状态。每站历史均单独预置一条并读回，软删任务后历史仍存。跨站拒绝侧以 `SettingsAndScheduleScopeTest` 和站点限定查询核对，测试确认错误站点在任务服务之前被拒绝，以及详情、历史、删除使用会话站点。定时调度执行未接入本轮验收。

[脱敏 HTTP 与数据库回执](<D:/CodexBuilds/cms-workbench/auth51-53-acceptance/receipt.json>) · [回归构建](<D:/CodexBuilds/cms-workbench/auth51-53-acceptance/final-verify.log>)

最终补验使用真实 MySQL 的两站合成账号。站点账号被拒绝读取和保存平台配置，平台账号提交不支持的品牌资源格式也被拒绝。当前站点之外的定时任务拒绝更新，站点范围查询无详情和历史，删除与启停返回失败。应用内调用前后在同一 JDBC 事务比对配置、定时任务及历史表，记录相同。正常历史读回验证的是预置回执，调度执行未验。

[最终防御集成测试](<D:/CodexBuilds/cms-workbench/final-dev-integration-437f76a5/final-dev-integration.log>)
