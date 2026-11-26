# AI-CMS

AI 建站工作台与 CMS 源码。后端使用 JDK 21、Maven 和 Spring Boot；Vue 管理端分别提供客户版与内部工作台版。

## 从新检出构建

准备 JDK 21、Maven 和支持当前 Vite 工具链的 Node.js。本轮使用 Windows Temurin 21 和 Node.js 24。首次构建需要能够获取 Maven 与 npm 的依赖。

```sh
cd work/cms-source/xhr-cms-main
java -version
mvn verify
npm --prefix admin-ui install
npm --prefix admin-ui run typecheck
npm --prefix admin-ui run build
npm --prefix admin-ui run build:workbench
```

后端产物在 `cms-boot/target/xhr-cms-1.0-SNAPSHOT.jar`，两个前端产物分别在 `admin-ui/dist/` 和 `admin-ui/dist-workbench/`。启动应用还需要自行配置 MySQL、Redis、上传目录和服务端密钥，并初始化本地空库。已有业务库按对应版本审核增量迁移。[工程说明](work/cms-source/xhr-cms-main/README.md) · [新库与升级说明](work/cms-source/xhr-cms-main/database/README.md) · [管理端部署](work/cms-source/xhr-cms-main/admin-ui/README.md)

## 本轮工单

2026-11-27 的源码审查包含 20 项问题，按改动范围编为 50–63 号实施工单。64 号超大站续核已暂缓。[工单清单与验收结果](交付/源码审查工单.md) · [逐票记录](work/cms-source/xhr-cms-main/.scratch/cms-ai-workbench/issues)

仓库提供应用源码、通用数据库结构和管理端静态资源。客户样本站、业务数据库、运行目录及本机验收凭据未纳入交付。需要外部样本或数据库环境的验收用例通过显式参数开启；工单中的本机回执路径用于在原验收环境追溯。现行服务部署独立于此仓库。
