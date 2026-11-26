# 55 — 明确并落实普通单页的草稿发布语义

- 审查编号 B3
- 优先级 P1
- 状态：已通过正常后台按钮、隔离站接口及独立静态验收
- [审查记录](../../../../../implementation/CMS系统审查-2026-11-27.md)

## 代码证据

[存草稿更新栏目](../../../cms-admin/src/main/java/com/xinhongru/api/admin/v2/CmsCoreV2ApiController.java#L1016)、[公开路由](../../../cms-site/src/main/java/com/xinhongru/cms/siteservice/SiteHttpServer.java#L587)、[公开渲染](../../../cms-site/src/main/java/com/xinhongru/cms/siteservice/SiteHttpServer.java#L1297)、[绑定模板状态保护](../../../cms-site/src/main/java/com/xinhongru/cms/template/BoundTemplateModelContributor.java#L164)。

## 实施边界

先依据现有产品交互、工作台绑定行为、需求基线及历史版本查明已发布单页再存草稿的语义，并把决策写入需求基线。有歧义时核准后再改。若存草稿即撤回，则同步约束动态公开读取和静态清理；若继续公开上一版，则明确发布快照和草稿的存取，公开路径只读发布快照。保留授权预览和后续发布入口，核对已有栏目历史数据及回退后的公开状态。独立站按新导出、替换部署、访客访问的顺序验收。

## 验收

- [x] 留存已发布再存草稿的产品语义、决策依据和旧数据兼容方案。
- [x] 普通模板与绑定模板分别测试初稿、发布、再存草稿、授权预览、重发及回退，保留栏目状态、动态地址及静态产物的结果。
- [x] 按已确认语义重新导出并替换独立站，核对正式访客地址和实际内容。

若选定撤回语义，复用 54 号工单的安全清理方式。56 号工单可独立实施。

## 本轮实施与验证

[单页编辑器的存为草稿入口](../../../admin-ui/src/views/cms/content/SinglePageEditor.vue#L451)调用取消发布。沿用原 `t_oneshot.status` 发布状态及原字段，无需迁移。静态撤回复用发布产物归属校验，并兼容旧有无生成凭据的同路由产物。授权预览沿用模板预览入口，受控首页沿用该首页的状态、别名和撤回入口。产品语义已写入[需求基线](../需求基线.md#31-基础-cms)。

2026-11-27 在 Windows Temurin 21.0.11、一次性 MySQL 8.0 和 Redis 7.4 上实测。使用修复包 SHA-256 `a0eed7ff1b03185c88218fddfec3afcb50bed5eedb5e31f6f82997bbf1b8349d`、合成站 #55、普通单页 #5501 与预置受控绑定单页 #5502。后台经正常图形验证码登录、选站，调用单页编辑器对应的编辑、发布和存草稿接口，静态生成经 `/api/admin/v2/static-publish/create-task` 和 `/api/admin/v2/static-publish/publish`。匿名动态服务使用 18555，Python 独立静态服务使用 18556，两个端口均仅绑定本机回环。

| 阶段 | 普通 `/ordinary.html` | 绑定 `/bound.html` |
|---|---|---|
| 初稿 | 动态 404，后台可读写 | 动态 404，授权预览可读初始值 |
| 发布并生成 | 动态 200 和静态文件读取同一首版值 | 动态 200 和静态文件读取同一首版值 |
| 已发布再存草稿 | 状态 `DRAFT`，动态 404，CMS 静态文件撤下 | 状态 `DRAFT`，动态 404，CMS 静态文件撤下 |
| 草稿编辑及预览 | 编辑接口保存新版值，授权预览 200 并渲染新版，匿名预览 401 | 编辑接口保存新版值，授权预览 200 并渲染新版，匿名预览 401 |
| 重发并生成 | 状态 `PUBLISHED`，动态 200 和静态文件读取新版值 | 状态 `PUBLISHED`，动态 200 和静态文件读取新版值 |
| 撤回失败回滚 | 锁定次语言的合成产物，撤回 409。数据库仍为状态 3，主静态文件 SHA-256 一致，动态仍为 200，无暂存文件残留 | 同样演练得到 409，数据库状态、主静态文件摘要和访客结果均恢复 |

对首页和两张单页，用 `work/cms-local/export-static.py` 在发布、撤回、重发后分别重新导出，再依次替换 18556 的服务目录。导出产物为 3 → 1 → 3 页，均为 0 条待核项。两张单页的独立正式地址均为 200 → 404 → 200，首页三个阶段均为 200，正文与对应 CMS 静态文件一致。静态交付和回滚回执保存在隔离目录 `D:/CodexBuilds/cms-workbench/single55-acceptance` 下的 `both-delivery-observed.json`、`ordinary-rollback.json` 和 `bound-rollback.json`。

本轮实测范围为后台操作接口、访客 HTTP 和真实静态产物。运行策略拒绝启动隔离管理前端，浏览器按钮点击未验收。绑定由合成站预置，绑定生成和工作台受控首页流程另行验收。

## 浏览器补验

本机 Vite 工作台经真实登录会话从「内容管理」选择栏目。在普通单页与已绑定单页分别点击「存为草稿」，编辑字段、保存并刷新确认值，再点「存为发布」。两页均返回 DRAFT 和 PUBLISHED，刷新后编辑值一致。两种模板的三态访客响应与失败回滚另见上一节。[Chrome 按钮回执](<D:/CodexBuilds/cms-workbench/remaining-437f76a5/55-browser.json>)。
