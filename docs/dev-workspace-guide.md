# 服务器开发工作区指南

> 本文件由原 README 内容整理而来（2026-09-19）：开源仓库的 README 只保留面向本仓库的介绍，
> 工作区结构、推送流程、基线快照与运行保护等**服务器侧**信息移到这里，避免 README 膨胀与信息过期。

## 目录结构

- mdtserver：服务端运行目录（候选 JAR、`server.properties`、`config/scripts` 插件树等）；
- stats-web：服务器状态统计独立 Web（`index.html` + `start-web.ps1`，读取服务器脚本输出的 `config/stats/server-status.json`；详见 `server-status-stats.md`）；
- docs：维护总览与领域设计文档；
- ScriptAgent4MindustryExt-mdtdo：插件脚本开源仓库（独立 Git 仓库）；
- ../mod-dp-bridge：同级 Mod→v159.7 DP 转换工具项目（独立 Git 仓库）；
- ../参考项目：公用参考源码（官方 Mindustry、MindustryX、ScriptAgent），位置不随本项目移动。

## 远程推送流程

- mdtdo 服务器仓库只做本地维护，不直接推送；
- 每次推送前，**先同步 docs 与 scripts 的改动**到 `ScriptAgent4MindustryExt-mdtdo`，再从该仓库提交并推送；
  **推送默认包含 scripts 与 Agent 开发文档（docs）**；
- 推送仓库 `ScriptAgent4MindustryExt-mdtdo`：origin 为 `github.com/QQQW114/ScriptAgent4MindustryExt-mdtdo`，
  upstream 为 `way-zer/ScriptAgent4MindustryExt`。

## 新会话 / Agent 入口

建议先阅读：

1. `project-memory.md`（项目长期记忆与当前状态）
2. `scripts-maintenance.md`（脚本维护总览）
3. `agent-debug-experience.md`（启动器/编码/编译/H2/提交推送陷阱，调试前先读）
4. 与当前任务相关的专项文档：`official-v159-compat.md`、`v159-network-sync.md`、`performance-guard.md`、
   `v159-data-assets-hot-reload.md`、`custom-menu.md`、`deploy-sync-checklist.md`

长期决策、用户偏好、重大历史和未验证风险以 `project-memory.md` 为导航；具体实现以专项文档和当前代码为准。

## 主要目录

- `mdtserver/config/scripts`：插件总目录；
- `mdtserver/config/scripts/mapScript`：地图玩法；
- `mdtserver/config/scripts/coreMindustry`：菜单、积分板和基础框架；
- `mdtserver/config/scripts/wayzer`：主要业务、账号、权限、数据库和保护逻辑；
- `../参考项目/Mindustry-master`：官方 Mindustry 参考源码；
- `../参考项目/MindustryX-main`：MindustryX 参考源码；
- `../参考项目/ScriptAgent4MindustryExt-3.4.0`：ScriptAgent 3.4.0 参考源码。

## 当前候选基线（以实际文件复核为准）

- **当前基线：Mindustry v160.3 / MindustryX 预览版 `prerelease-2026.09.13.B495`**（提交 `92471dd`，
  其 `work` 子模块即 Mindustry `v160.3` 提交 `254fd3e`）；上一基线为正式发行版 X37 / v160.1；
- 基线 JAR：`mdtserver/server-2026.09.13.B495.jar`（`version.properties` 内 `build=160.3`），
  已在 `server.properties` 的 `jar=` 中显式指定；SHA-256 `D2872D30…4C09`；
- 获取方式：直接从 GitHub 发行版下载（`TinyLake/MindustryX` releases 的 `server-*.jar`），不再自建/打补丁；
- 回滚：`server-2026.09.X37.jar`（v160.1）、`server-2026.09.11.B491.jar`（同 v160.1）、
  `server-2026.08.12.B485.jar`（v159.7）都保留在 `mdtserver/`，回滚只改 `server.properties` 的 `jar=`；
- **脚本硬依赖 MindustryX 端**（`trafficMonitor.kts` 直接 `import mindustryX.events.SendPacketEvent`），
  **不能用官方 Mindustry 的 jar 顶替**；
- **版本跟进总体原则**：常态化跟进上游最新版本，具体跟到哪个版本由用户拍板；ScriptAgent 插件以本项目自行维护为主，
  只在出现较大变动的发行版更新时评估跟进。以上基线只是当前快照而非永久目标。

## 运行保护入口

- **平台限制**：上行流量统计读取 Windows 网卡累计字节（`netstat -e`），**本系统仅支持 Windows（生产与开发均为 Windows）**；
- Windows/Linux 启动脚本包含 JVM 异常退出后的自动重启与退避；
- 上行流量统计（`trafficMonitor`）以网卡真实出口为口径，总上行/同步上行/世界流同源，性能优化系统按该总上行触发网络保护与清理；
  （2026-09-19 起压力措施的 unitCap 条目已移除，见 `performance-guard.md`）
- 外部 CP 支持 JSON/HJSON/JSON5 与 v159 ZIP Data Assets；`/cp dp` 查看完整资产，`/cp load` 由管理员快速加载，
  详见 `v159-data-assets-hot-reload.md`；
- `/serverfeatures` 保留功能总览/菜单；实际设置已拆为 `/mdcmultiplier`、`/forumtoggle`、`/registerrequirement`、
  `/socialactions`、`/defaultboundlevel` 五个管理根指令；
- `/diskwarmup` 可运行中启停或立即执行 H2 磁盘预热/保活，缓解云盘休眠后的首次数据库读写卡顿；
- Agent 可以准备源码、构建物、测试记录和回滚建议，但不能代替用户/运维访问、停启、替换或确认生产服务。

## 生产与数据边界

- 生产服不存在于本机，Agent 无法访问、推送或部署生产；生产部署只能由用户/运维执行；
- 外部 CP、缓存、数据库、日志和备份属于**运行数据**，不应默认纳入提交；
- 往生产同步改动**只复制 `scripts/` + JAR**，并且必须排除 `scripts/data`、`scripts/external-cp`、`scripts/cache`——
  完整口径见 `deploy-sync-checklist.md`。