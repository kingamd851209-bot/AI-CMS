# 63 — 为管理端缺失分包返回 404

**审查编号** C7

**优先级** P2

**状态** 已实施，隔离验收通过

## 问题与依据

Vite 默认将分包写入 `assets`，示例 Nginx 仅为 `/static/` 设置缺件返回 404。旧标签请求缺失的哈希分包时会收到 SPA 首页。见 `admin-ui/vite.config.ts:79`、`deploy/nginx/xhr-cms-admin.conf:20`。

## 修改范围

- 为构建资产路径设置静态文件匹配，缺件返回 404。保留 `/assets/global/` 的较长前缀代理，以及后台深链的 SPA 回退。

## 回归验收

- [x] 在隔离 Nginx 实例运行 `nginx -t`，使用实际分包或代表性 JS/CSS 文件核对现存资产返回 200 及相应内容类型，未知哈希分包返回 404。
- [x] 后台深链仍返回 SPA 首页，全局素材路径仍到达替身后端。核对现有 `/static/` 的缺件行为。

保存 Nginx 校验结果和本机 HTTP 状态、内容类型及路由命中证据。依赖隔离 Nginx 和本地构建产物，可独立实施。

## 2026-11-27 验证记录

使用本机临时 Docker 容器 `nginx:1.27-alpine`，原样加载更新后的示例配置。通过 `docker exec <容器> nginx -t` 校验语法，使用 `curl.exe --noproxy '*'` 回环直连。真实构建产物由最小 JS/CSS 文件替代。

| 请求 | 状态与内容 |
| --- | --- |
| `/assets/index-a1.js` | 200，`application/javascript`，返回预置 JS |
| `/assets/index-a1.css` | 200，`text/css`，返回预置 CSS |
| `/assets/expired.js` | 404，缺件响应 |
| `/assets/expired.css` | 404，缺件响应 |
| `/admin/v2/site-build` | 200，返回预置 SPA 首页 |
| `/assets/global/test.txt` | 200，命中替身后端内容 |
| `/static/expired.js` | 404，缺件响应 |

同日另起一次性 Nginx 容器，挂载客户版前端真实构建目录及原样的部署配置。`nginx -t` 通过，`AdminGroupsView-BFunhJlU.js` 和 `AdminGroupsView-B40qfcCP.css` 分别返回 200 及正确 MIME。伪造的旧分包和 `/static/` 缺件仍返回 404；深链返回构建入口且文件摘要相同，全局素材到达替身后端。容器已移除。
