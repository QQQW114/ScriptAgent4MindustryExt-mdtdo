# MDT DO 服务器开发项目（mdtdo）

本目录是 MDT DO Mindustry 专服服务器端的独立开发项目，与 `mdt保留` 下的其他项目（`mod-dp-bridge`、`参考项目` 等）并列。它是开发/测试工作区，不等同于生产服务器。

## 目录结构

- mdtserver：服务端运行目录（候选 JAR、`server.properties`、`config/scripts` 插件树等）；
- stats-web：服务器状态统计独立 Web（`index.html` + `start-web.ps1`，读取服务器脚本输出的 `config/stats/server-status.json`；详见 `docs/server-status-stats.md`）；
- docs：维护总览与领域设计文档；
- ScriptAgent4MindustryExt-mdtdo：插件脚本开源仓库（独立 Git 仓库，见其自身 README）；
- ../mod-dp-bridge：同级 Mod→v159.7 DP 转换工具项目（独立 Git 仓库）；
- ../参考项目：公用参考源码（官方 Mindustry、MindustryX、ScriptAgent），位置不随本项目移动。

## 远程推送流程

- mdtdo 服务器仓库只做本地维护，不直接推送；
- 每次推送前，**先同步 docs 与 scripts 的改动**到 `ScriptAgent4MindustryExt-mdtdo` 仓库，再从该仓库提交并推送；**推送默认包含 scripts 与 Agent 开发文档（docs）**；
- 推送仓库为 `ScriptAgent4MindustryExt-mdtdo`（origin 为 `github.com/QQQW114/ScriptAgent4MindustryExt-mdtdo`，upstream 为 `way-zer/ScriptAgent4MindustryExt`）。

## 新会话 / Agent 入口

建议先阅读：

1. [项目长期记忆与当前状态](docs/project-memory.md)
2. [脚本维护总览](docs/scripts-maintenance.md)
3. [Agent 调试经验与常见坑](docs/agent-debug-experience.md)（启动器/编码/编译/H2/提交推送陷阱，调试前先读）
4. 与当前任务相关的专项文档，例如：
   [兼容层](docs/official-v159-compat.md)、
   [网络同步](docs/v159-network-sync.md)、
   [性能保护](docs/performance-guard.md)、
   [Data Assets / 外部 CP](docs/v159-data-assets-hot-reload.md)

README 只提供入口和简要基线；长期决策、用户偏好、重大历史和未验证风险以 docs/project-memory.md 为导航，具体实现以专项文档和当前代码为准。

## 主要目录

- mdtserver/config/scripts：插件总目录；
- mdtserver/config/scripts/mapScript：地图玩法；
- mdtserver/config/scripts/coreMindustry：菜单、积分板和基础框架；
- mdtserver/config/scripts/wayzer：主要业务、账号、权限、数据库和保护逻辑；
- docs：维护总览和领域文档；
- ../参考项目/Mindustry-master：官方 Mindustry 参考源码；
- ../参考项目/MindustryX-main：MindustryX 参考源码；
- ../参考项目/ScriptAgent4MindustryExt-3.4.0：ScriptAgent 3.4.0 参考源码；
- ScriptAgent4MindustryExt-mdtdo：插件脚本开源仓库（独立 Git，`origin` 为 `github.com/QQQW114/ScriptAgent4MindustryExt-mdtdo`，`upstream` 为 `way-zer/ScriptAgent4MindustryExt`）。

## 当前候选基线（以实际文件复核为准）

- **当前基线：Mindustry v160.1 / MindustryX 正式发行版 X37（`v2026.09.X37`，2026-09-13 起启用）**；
- 基线 JAR：`mdtserver/server-2026.09.X37.jar`（**MindustryX 发行版**，`version.properties` 内 `build=160.1`），
  已在 `server.properties` 的 `jar=` 中显式指定为启动目标；
- 获取方式：直接从 GitHub 发行版下载（`TinyLake/MindustryX` releases 的 `server-*.jar`），不再自建/打补丁；
  `v2026.09.X37` 与 prerelease `2026.09.12.B493` 是同一提交 `dc388e9`，游戏版本仍是 v160.1；
- 基线 JAR SHA-256：`F1CD2B5AFB9ED5395707F228F0848FCE4C0872E14171D4C80941C53226F7B575`；
- 回滚：`server-2026.09.11.B491.jar`（同 v160.1 的上一构建）与 `server-2026.08.12.B485.jar`
  （v159.7，最后一个稳定发行线）都保留在 `mdtserver/` 内，需要回滚时把 `server.properties` 的
  `jar=` 改回对应文件名即可。

脚本硬依赖 MindustryX 端（`trafficMonitor.kts` 直接 `import mindustryX.events.SendPacketEvent`），
**不能用官方 Mindustry 的 jar 顶替**。

B485 起不再需要 MDT 自定义补丁：B480 时代的 `0075`（批量 SendPacketEvent）与 `0076`（可靠自定义实体快照）所针对的 API 已被上游移除/重构；上行统计改为 Windows 网卡计数器（`netstat -e`），核心机恢复改为 `checkSpawn()` + 原版快照。
**160 适配**（2026-09-12，2026-09-13 修订）：160 移除了生成的 `Groups.fire` / `Groups.puddle` 实体分组（火焰与液体洼地改为按 tile 存储）。
09-12 曾把 5 处引用统一改为遍历 `Groups.all` 按类型筛选，**该口径已于 09-13 按用户要求撤回**：
`wayzer/reGrief/limitFire.kts` 整脚本删除（它每 tick 全量扫实体数火焰，也是 160 上 `NoSuchFieldError` 每 tick 炸服的现场，
上游 issue 见 [way-zer/ScriptAgent4MindustryExt#49](https://github.com/way-zer/ScriptAgent4MindustryExt/issues/49)），
`performanceGuard.kts` / `serverPressureActions.kts` 的 `clearFires()` 随之删除（保留 O(1) 的 `state.rules.fire=false` 规则开关），
灭火技能改为纯 tile 实现；现在只有 `contentsTweaker.kts` 的 DP 卸载冷路径还允许 `Groups.all`。
冷启动 `157/153/148/0`（脚本数 158 → 157 即删除 `limitFire.kts`），B491 与 X37 结果一致，详见 [脚本维护总览](docs/scripts-maintenance.md) 2026-09-13 条目。
**版本跟进总体原则**：跟进新的稳定版本；无论 JAR 文件还是 SA 插件，均由用户决定跟进哪个版本，并跟进用户所要求的对应版本。以上基线只是当前候选快照而非永久目标。
**日常跟进口径**（2026-09-12 用户明确）：**常态化跟进上游最新版本**（Mindustry / MindustryX / 参考项目）；
**ScriptAgent 插件以本项目自行维护为主**，只在出现**较大变动的发行版更新**时再评估是否跟进。
当前脚本工作区按 ScriptAgent 3.4.0 维护，但实际运行版本、JAR 是否已替换以及生产状态必须现场核对。生产服不存在于本机，Agent 无法访问、推送或部署生产；生产部署只能由用户/运维执行。外部 CP、缓存、数据库、日志和备份属于运行数据，不应默认纳入提交。

## 运行保护入口

- **平台限制**：上行流量统计读取 Windows 网卡累计字节（`netstat -e`），**本系统仅支持 Windows（生产与开发均为 Windows），不支持 Linux**；
- Windows/Linux 启动脚本包含 JVM 异常退出后的自动重启与退避；
- 上行流量统计（`trafficMonitor`）以网卡真实出口为口径，总上行/同步上行/世界流同源，性能优化系统按该总上行触发网络保护与清理；
- 外部 CP 支持 JSON/HJSON/JSON5 和 v159 ZIP Data Assets；`/cp dp` 可查看完整资产，`/cp load` 可由管理员快速加载服务器CP，详见
  [Data Assets / 外部 CP 文档](docs/v159-data-assets-hot-reload.md)；
- `/serverfeatures` 保留服务器功能总览/菜单；实际设置已拆为 `/mdcmultiplier`、`/forumtoggle`、`/registerrequirement`、`/socialactions`、`/defaultboundlevel` 五个管理根指令，默认绑定等级同时作为已绑定玩家的信任与资历下限；
- `/diskwarmup` 可运行中启停或立即执行 H2 磁盘预热/保活，用于缓解部分云服磁盘/块存储休眠后的首次数据库读写卡顿；
- Agent 可以准备源码、构建物、测试记录和回滚建议，但不能代替用户/运维访问、停启、替换或确认生产服务。
