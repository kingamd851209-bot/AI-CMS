# 53 — 后台路径认证与开发代理来源保护

**审查编号** A5、A7
**优先级** P1
**状态** 根路径、非根路径和开发代理正常操作通过，拒绝后真实库回读一致

## 范围

A5 适用于 Servlet 非根上下文部署。A7 涉及 Vite 开发代理，受代理可达性和浏览器会话影响。以本机隔离部署核对两类条件。

## 源码定位

- [BaseInterceptor.java](../../../cms-admin/src/main/java/com/xinhongru/interceptor/BaseInterceptor.java) 第 44、124、133、145 行为入口路径判定及相关分支。[SessionCsrfService.java](../../../cms-security/src/main/java/com/xinhongru/security/SessionCsrfService.java) 第 33、51、68 行涉及写入来源校验和路径分类。
- [vite.config.ts](../../../admin-ui/vite.config.ts) 第 24、60 行为转发来源处理及开发监听。
- [审查记录](../../../../../implementation/CMS系统审查-2026-11-27.md) 的 A5、A7。

## 交付内容

- 在后台拦截器按 Servlet 应用内路径识别受保护入口，核对来源校验内部的路径分类及路由排除。保留登录、静态资源和其他明确公开入口的原有行为。
- Vite 在转发前核对明确配置的开发前端来源，拒绝不可信浏览器来源。开发服务默认监听环回地址。合法代理请求与非代理部署继续执行后端来源校验。

## 验收

- [x] 在根和非根上下文验证正常图形验证码登录、身份读取、同源写入和公开入口。未登录的新旧后台读取与写入由应用内拦截器测试核对拒绝结果。
- [x] 后端无效来源写入由应用内拦截器拒绝，根和非根的新旧入口均验证，实际数据库配置回读无变更。
- [x] Vite 开发模式从 Chrome 登录表单进入，经真实代理保存品牌配置，刷新回读匹配。开发服务实测监听 127.0.0.1，不可信来源拒绝沿用独立代理验证。
- [x] 留存两种上下文结果、脱敏代理与后端回执及管理前端双模式构建结果。

## 实施依赖

本地可配置 Servlet 上下文的隔离后端和 Vite 开发环境。浏览器访问本机地址时绕过本地代理。无其他工单前置。

## 本轮执行记录

后台拦截器和写请求校验共用应用内路径。开发服务默认绑定 127.0.0.1，同源检查先于 API 代理的来源改写；开发 npm 命令沿用 Vite 配置。AdminContextPathTest 通过；临时 Vite 进程实测绑定环回、同源转发正确，来源判定拒绝异源样本。普通与工作台前端构建通过。非根上下文的整套真实登录与保存待集成验收。

开发环境集成测试使用新建的回环 MySQL 和 Redis 运行。按独立前端契约验证登录入口 302 及目标地址，管理身份读取、同源退出及旧会话失效通过。非根上下文已做定向拦截器回归，整套浏览器流程仍待验证。

本轮将同一 CMS 分别启动在根路径和 `/isolated`，两端均通过正常验证码及登录公钥建立会话，读后台身份并分别从新旧设置入口同源保存，MySQL 值与回读一致。非根上下文的旧设置重定向保留 `/isolated`。Vite Chrome 单独验证登录表单、品牌配置保存提示与刷新后回读。非根正常流程由真实 HTTP 会话验证，Chrome 操作覆盖根上下文的开发代理。

[两种上下文的 HTTP 回执](<D:/CodexBuilds/cms-workbench/auth51-53-acceptance/receipt.json>) · [Chrome 开发代理回执](<D:/CodexBuilds/cms-workbench/auth51-53-acceptance/vite-browser.json>) · [拦截器回归构建](<D:/CodexBuilds/cms-workbench/auth51-53-acceptance/final-verify.log>)

最终测试在实际启动的隔离 Spring 应用内直接检查新旧拦截器。无有效来源的模拟写请求均由拦截器返回 HTTP 403。匿名新入口读写返回 401，匿名旧入口读写返回 302。根路径与非根路径均验证，MySQL 配置表在同一 JDBC 事务前后相同。原开发环境集成测试也显式启用通过，复核应用 readiness、登录跳转、Redis 会话读取和退出失效。

[最终拦截器与开发环境集成测试](<D:/CodexBuilds/cms-workbench/final-dev-integration-437f76a5/final-dev-integration.log>)
