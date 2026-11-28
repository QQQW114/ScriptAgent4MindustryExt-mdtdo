# MDT DO 服务器插件与运维套件

本仓库是 **MDT DO（主服 `main`）** 与 **MDC DO（分服 `mdc-do`）** Mindustry 专服的服务端插件、门户 Web、邮件发信服务、生产更新器与开发工具集合，基于 [ScriptAgent4MindustryExt](https://github.com/way-zer/ScriptAgent4MindustryExt)（ScriptAgent 3.4.0）。仓库**只收录源码、工具与文档**，不含数据库、外部内容包、缓存、日志、密钥或服务端 JAR。

```text
warning：本仓库包含大量 AI 辅助生成与维护的代码与文档，稳定运行得益于 ScriptAgent 架构与自动化冷启动验收工具链。
协作前请先阅读 AGENTS.md 与 docs/workflow.md，遵守单模块短会话与机器门禁规范。
```

## 仓库目录

| 路径 | 内容 |
| --- | --- |
| `scripts/` | ScriptAgent 服务端插件树，部署到服务端 `config/scripts/` |
| `stats-web/` | 独立服务器门户 Web（状态看板、实时事件流、DP/地图投稿审核、日志面板） |
| `email-service/` | 主服本地 QQ 邮箱验证码发信服务（`127.0.0.1:40150`，仅 `main` 分支） |
| `updater/` | 生产自动更新器（主服 `main` / 分服 `mdc-do`、支持运行中热更新与回滚） |
| `tools/` | 面向协作者与 Agent 的本地静态体检、冷启动与热重载调试工具（`mdt-dev.ps1`） |
| `docs/` | 架构路由、开发工作流、调试手册、部署规范与各系统现行文档 |
| `.scratch/` | 本地临时草稿箱（`.gitignore` 忽略，存放测试计划与排查笔记，不入库） |

## 运行环境

1. **操作系统**：**仅支持 Windows**（`trafficMonitor.kts` 通过 `netstat -e` 采集网卡上行；门户 Web、邮件服务与更新器均为 PowerShell 脚本）。
2. **游戏服务端**：当前基线为 Mindustry **v160.7** / MindustryX `prerelease-2026.10.10.B509`（脚本已移除对 MindustryX 包事件的硬依赖，仅 `limitLogicPacket.kts` 可选反射兼容）。
3. **脚本引擎**：[ScriptAgent4MindustryExt](https://github.com/way-zer/ScriptAgent4MindustryExt) 3.4.0（放到 `config/mods/`）及对应依赖库（放到服务端 `libs/`）。
4. **部署与更新**：生产环境推荐使用 `updater/` 自动更新（保护 `scripts/data/`、`scripts/external-cp/`、`scripts/cache/` 与各服务配置），详见 [docs/deployment.md](docs/deployment.md)。

## 协作与文档入口

- **AI Agent 必读**：[AGENTS.md](AGENTS.md)
- **人类贡献者指南**：[CONTRIBUTING.md](CONTRIBUTING.md)
- **系统架构与模块路由**：[docs/architecture.md](docs/architecture.md)
- **统一工作流与文档纪律**：[docs/workflow.md](docs/workflow.md)
- **本地调试与排障手册**：[docs/dev-and-debug.md](docs/dev-and-debug.md)
- **生产部署与双服同步**：[docs/deployment.md](docs/deployment.md) · [docs/mdc-do-sync.md](docs/mdc-do-sync.md)
- **未决事项与变更日志**：[docs/backlog.md](docs/backlog.md) · [CHANGELOG.md](CHANGELOG.md)

## 快速验证（本地开发 / 提 PR 前）

```powershell
# 1. 静态体检（无需本地测试服，秒级完成）
pwsh -NoProfile -File tools\mdt-dev.ps1 check

# 2. 如有本地测试服：复制 tools/dev-local.example.json 为 tools/dev-local.json 填 serverDir 后运行冷启动验收
pwsh -NoProfile -File tools\mdt-dev.ps1 verify -Mode cold -SyncToServer
```

## 许可与上游

本仓库是 [way-zer/ScriptAgent4MindustryExt](https://github.com/way-zer/ScriptAgent4MindustryExt) 的衍生项目，许可见 [LICENSE.md](LICENSE.md)。与 ScriptAgent 框架本体相关的问题请优先反馈上游。
