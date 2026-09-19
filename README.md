# MDT DO 服务器插件脚本

本仓库是 **MDT DO Mindustry 专服**使用的服务端插件脚本集合，基于 [ScriptAgent4MindustryExt](https://github.com/way-zer/ScriptAgent4MindustryExt)（ScriptAgent 3.4.0）。
只收录**脚本与维护文档**，不含任何运行数据（数据库、外部内容包、缓存、日志、密钥、服务器 JAR）。

## 目录

| 路径 | 内容 |
| --- | --- |
| `scripts/` | 插件脚本树，部署到服务端 `config/scripts/` |
| `docs/` | 维护总览、领域设计文档与排查经验 |

`scripts/` 主要分区：`coreMindustry/`（菜单、积分板与基础框架）、`coreLibrary/`（命令、数据库等公共库）、
`wayzer/`（账号、权限、商店、社区、Wiki/帖子、地图与保护逻辑）、`mapScript/`（地图玩法）。

## 快速开始

1. **服务端**：Mindustry **v160.x** + **MindustryX** 构建。
   脚本硬依赖 MindustryX 接口（例如 `mindustryX.events.SendPacketEvent`），**不要用官方 Mindustry 的 JAR 顶替**。
2. **运行环境**：安装 [ScriptAgent4MindustryExt](https://github.com/way-zer/ScriptAgent4MindustryExt) 3.4.0（放到 `config/mods/`），
   并把依赖库（Kotlin 编译器等）放到服务端 `libs/`。
3. **部署脚本**：把 `scripts/` 覆盖到服务端 `config/scripts/`，但**不要覆盖**这三处运行数据：
   `scripts/data/`（数据库与脚本配置）、`scripts/external-cp/`（内容补丁包）、`scripts/cache/`（编译缓存，建议清空后重启）。
4. **验证**：启动后日志应给出无错误的加载汇总（`共找到N脚本,加载成功…,出错0`），
   详细清单与回滚口径见 [生产同步清单](docs/deploy-sync-checklist.md)。

> 本仓库按"服务器脚本 + 文档"维护；完整的服务器端配置、JAR 基线与回滚线属于**部署侧**信息，随部署文档提供，不在此重复。

## 文档索引

- [项目长期记忆与当前状态](docs/project-memory.md) — 接手本项目先读这个
- [脚本维护总览](docs/scripts-maintenance.md) — 逐次改动、验证与未覆盖边界
- [Agent 调试经验与常见坑](docs/agent-debug-experience.md) — 启动器、编码、脚本编译、数据库与推送陷阱
- [生产同步清单](docs/deploy-sync-checklist.md) — 同步范围、绝不能覆盖的运行数据、声明惯例
- [自定义菜单（160 服务端下发菜单）](docs/custom-menu.md) — 菜单版式与跨菜单导航口径
- 其他专项：[兼容层](docs/official-v159-compat.md)、[网络同步](docs/v159-network-sync.md)、
  [性能保护](docs/performance-guard.md)、[Data Assets / 外部 CP](docs/v159-data-assets-hot-reload.md)、
  [服务器状态统计](docs/server-status-stats.md)

## 版本与上游

- 游戏侧基线（Mindustry / MindustryX 版本、构建物与回滚线）以
  [兼容层说明](docs/official-v159-compat.md) 与 [项目长期记忆](docs/project-memory.md) 为准，按"常态化跟进上游最新版本"维护。
- 本仓库是 ScriptAgent4MindustryExt 的衍生项目，许可与声明见 `LICENSE.md`、`THIRD_PARTY_NOTICES.md`（如有）。
- 欢迎把问题反馈到 Issues；与上游 ScriptAgent 本身相关的问题请优先反馈上游。
