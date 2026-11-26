# 52 — 会员口令迁移与验证码一次性消费

**审查编号** A4、A6
**优先级** A4 P1，A6 P2
**状态** 源码已实施，本机隔离入口验收通过

## 2026-11-27 隔离入口验收

使用 JDK 21、独立 MySQL 8.4 的模拟旧库和隔离 Redis 7.4。普通会员登录入口签发图形验证码，按页面显示输入字符，以现有 RSA 协议提交合成账号。口令错误后无专用凭据、旧字段仍有值且未建立会话。用一次性库触发器中断同一事务内的登录统计更新，入口返回 HTTP 500，专用凭据及旧字段恢复到提交前的状态，无会员会话。移除触发器，正常重试后专用表有且仅有一条凭据，旧字段为空，登录次数为 1。重登后专用凭据仍为一条、次数为 2，旧会话回读 HTTP 401，当前会话可读，注销后回读 HTTP 401。

连续三次错密触发阻断，随后使用正确口令和正确验证码仍提示等待 10 分钟，期间没有新增访问次数或会员会话。Redis 的阻断键原先设置为 600 秒，检查时剩余 589 秒。仅在一次性容器内将剩余有效期缩至 2 秒，确认键实际过期，再从正常入口登录成功。既有 `MemberLoginLimitTest` 同时断言阻断分支未调用凭据校验、登录记录或会话服务。先前隔离 Redis 用 12 路并发验证只发出三次验证资格，且一次性验证码并发消费只有一次成功。

新库和模拟旧库的管理员均通过正常登录 API 输入图形验证码并获会话，认证后成功回读后台表单。会员使用专用取码入口；公开表单在 Chrome 输入页面显示的验证码并点击发送，两个站点各成功两次，错误验证码返回 HTTP 422。用途隔离、过期和一次性消费由既有 Redis 证据覆盖。详见 [58 号表单工单](58-ticket.md)。

脱敏回执位于 `D:/CodexBuilds/cms-workbench/member52-form58/` 的 `member-rollback.json`、`acceptance.json`、`limit.json`、`admin-readback-*.json` 和 `form-validation.json`。会员目录的后台编辑仍以先前的服务测试为证据，外置会员模板的取码路径仍按具体部署核对。

## 2026-11-27 实施记录

已更新本机源码。会员凭据表配有独立 DDL，旧会员成功登录时在同一个事务写入 PBKDF2-SHA256 加盐哈希、清理当前记录的旧字段并写入登录统计。会员目录的现有内容保存路径将凭据写往专用表。未执行数据库迁移。上线前须先应用数据库结构变更，并在隔离库核对历史会员、常规编辑入口和事务回滚。已有历史快照、既有日志、备份和此前发布产物的清理另需确认范围。

一次性 MySQL 8.4 容器已以合成记录执行迁移 DDL 和从 Mapper 提取的 SQL，验证回滚后旧字段及凭据状态恢复，提交后旧字段已清理、专用记录已写入，硬删除时清理对应专用记录。运行结果见同一验收目录的 `mysql-result.txt`。业务数据库未应用迁移。

验证码签发与核对按管理员登录、会员登录和公共表单各自绑定用途。核对成功与消费由 Redis 同一操作完成。旧会员登录页在同源完整 Referer 路径为 `/member/login.html` 时可继续使用旧取码路由，支持非根上下文。对于抑制完整 Referer 或使用其他历史路径的外置模板，发布前更新取码地址为 `/member/captcha`，在隔离环境实测登录。部署切换时重新获取已有页面的验证码。

以隔离 Redis 7.4 一次性容器运行从源码提取的两段脚本。验证三用途隔离、错码保留、有效期、12 路验证码并行仅一次成功，以及 12 路登录尝试仅三次获得验证资格。容器只通过本地 Unix socket 操作合成键。脚本及结果位于 `D:/CodexBuilds/cms-workbench/security-review-20261127/auth-evidence/`，运行结果见 `redis-result.txt`。待隔离 CMS 通过三类正常入口验收。

干净快照 Maven 测试通过 `MemberCredentialsTest` 1 项、`MemberLoginLimitTest` 1 项、`ImageCodePurposeTest` 2 项、`LegacyMemberCaptchaPurposeTest` 2 项。新增测试原件已移存到上述验收目录，工程源码树已清理。

另核对会员内容的编辑、列表、历史和 Excel。当前编辑器只显示写入口，留空保留原密码，设置新值时写专用表；模型的必填校验根据专用存储状态及待迁移记录判断。列表、搜索、分组和 Excel 排除凭据字段。新版历史仅展示有可确认字段标识的非凭据差异，旧版未标字段的差异与原始快照不再对会员目录回显。最终快照中 `MemberContentRedactionTest` 两项通过，新增测试原件已移存验收目录。待正常页面演示。

## 范围

启用会员登录站点的登录和口令存储，以及管理员登录、会员登录、公共表单等现有图形验证码调用方。

## 源码定位

- [MemberController.java](../../../cms-site/src/main/java/com/xinhongru/web/controller/MemberController.java) 第 45 行为会员验证码校验。[MemberServiceImpl.java](../../../cms-core/src/main/java/com/xinhongru/service/member/impl/MemberServiceImpl.java) 第 64、120 行为会员登录及既有口令比对链路。
- [ImageCodeServiceImpl.java](../../../cms-core/src/main/java/com/xinhongru/service/imageCode/impl/ImageCodeServiceImpl.java) 第 33、44 行为签发与校验。[AuthController.java](../../../cms-admin/src/main/java/com/xinhongru/controller/admin/AuthController.java) 第 75 行及 [FormSiteApiServiceImpl.java](../../../cms-core/src/main/java/com/xinhongru/cms/formservice/impl/FormSiteApiServiceImpl.java) 第 412 行为另两个调用方。
- [审查记录](../../../../../implementation/CMS系统审查-2026-11-27.md) 的 A4、A6。

## 交付内容

- 会员凭据改存专用的自适应加盐哈希。支持旧记录成功登录时迁移，以一致写入的方式保存新凭据并清除原字段旧值。覆盖会员口令的新增、改密和重置入口，处理并行迁移及写入失败。将失败次数及阻断检查放在口令验证之前。
- 验证码由服务端用受控的用途和键空间签发及查验。签发与消费两侧绑定同一用途，保留有效期，并用缓存端原子操作完成正确值的核对与一次性消费。

## 验收

- [x] 合成旧会员首次成功登录后，专用凭据可用于后续登录，原字段旧值已清理；迁移失败及重试可恢复，错密与失败迁移无凭据写入。
- [x] 达到失败阈值时借助测试观测确认口令比较或哈希验证未执行。到期后的正常登录及会员会话轮换符合现有规则。
- [x] 管理员登录、会员登录及公共表单分别走正常入口完成验证码校验。不同用途隔离，错误和过期值被拒绝，同一验证码并行校验最多一次成功。
- [x] 留存脱敏迁移状态、失败阈值断言、缓存并发结论和正常入口回执。

## 实施依赖

本地隔离数据库、Redis、旧版合成会员记录和验证码现有调用方。两个改动可分别实施，会员入口合并验收需包含两者。
