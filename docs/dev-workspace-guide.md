# 服务器开发工作区指南

> 本文件由原 README 内容整理而来（2026-09-19）：开源仓库的 README 只保留面向本仓库的介绍，
> 工作区结构、推送流程、基线快照与运行保护等**服务器侧**信息移到这里，避免 README 膨胀与信息过期。

## 目录结构

- mdtserver：服务端运行目录（候选 JAR、`server.properties`、`config/scripts` 插件树等）；
- stats-web：**服务器门户 Web**（三页 `index.html`/`upload.html`/`admin.html` + 后端 `server/**`；独立进程，默认 `127.0.0.1:8090`；
  详见 `web-portal.md` 与 `server-status-stats.md`）；
- docs：维护总览与领域设计文档；
- tools：本机维护/诊断脚本（如块注释嵌套扫描器 `scan-nested-block-comment.ps1`）；
- ScriptAgent4MindustryExt-mdtdo：插件脚本开源仓库（独立 Git 仓库）；
- ../mod-dp-bridge：同级 Mod→v159.7 DP 转换工具项目（独立 Git 仓库）；
- ../参考项目：公用参考源码（官方 Mindustry、MindustryX、ScriptAgent），位置不随本项目移动。

## 远程推送流程

- mdtdo 服务器仓库只做本地维护，不直接推送；
- 每次推送前，**先同步 docs 与 scripts 的改动**到 `ScriptAgent4MindustryExt-mdtdo`，再从该仓库提交并推送；
  **推送默认包含 scripts 与 Agent 开发文档（docs）**；
- 推送仓库 `ScriptAgent4MindustryExt-mdtdo`：origin 为 `github.com/QQQW114/ScriptAgent4MindustryExt-mdtdo`，
  upstream 为 `way-zer/ScriptAgent4MindustryExt`。
- **直连超时/被重置时挂本地代理**：本机混合代理端口是 `127.0.0.1:7897`，一次性推送（不写进仓库 config）：
  `git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push origin main`；
  典型报错是 `curl 28 Recv failure: Connection was reset` / `send-pack: unexpected disconnect while reading sideband packet`。
- 同步口径（2026-09-27 复核）：插件仓库跟踪 `docs/**` 与 `scripts/**`（排除 `scripts/data`、`scripts/external-cp`、`scripts/cache`；
  其中 `scripts/data/config.conf` 是被跟踪的脚本配置，同步时**跳过 `data` 整个目录**、保留仓库里那份）；
  仓库里有而本地没有的文件不要删（用只增改不删的方式同步）。

## 新会话 / Agent 入口

建议先阅读：

1. `project-memory.md`（项目长期记忆与当前状态）
2. `scripts-maintenance.md`（脚本维护总览）
3. `agent-debug-experience.md`（启动器/编码/编译/KDoc 截断/H2/提交推送陷阱，调试前先读）
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

## 调试工具（2026-10-17 新增）

- **`scriptdiag` 控制台指令**（`mdtserver/config/scripts/wayzer/cmds/scriptDiag.kts`，权限 `wayzer.admin.scriptDiag`）：
  排查"指令/菜单入口突然消失"。用法：
  - `scriptdiag commands [过滤词]`：列出根指令、确认目标是否注册
    （例 `scriptdiag commands externalcp` → `根指令匹配 'externalcp'：1` + `externalcp(ecp/外部cp/热重载cp)`）；
  - `scriptdiag failed`：列出加载/启用失败脚本及 failReason（**注意**：脚本被静默截断时这里也可能是空的）；
  - `scriptdiag state <脚本id>`：看单个脚本状态（如 `wayzer/map/externalCpHotReload`）；
  - `scriptdiag count`：脚本总数/已加载/已启用/失败 + 根指令注册数。
- **`/punishreport` 管理/自查命令**（`mdtserver/config/scripts/wayzer/user/punishmentReport.kts`，无 permission attr、子命令内校验等级）：
  - `bans`（检举封禁名单）/ `status <玩家>`（累计成功检举次数与封禁状态，需 3++）/ `reset <玩家>`（清除封禁记录与计数，仅 4 级）；
  - 玩家无参 `/punishreport` = 检举自己最近一次被非管理员施加的限制性惩罚（弹窗关闭后的重开入口，30 分钟内有效；控制台执行会提示需在游戏内）；
  - 惩罚规则/冷却/持久化的真源在 `wayzer/ext/playerMute.kts`；完整口径见 `punishment-system-design.md`。
  用途：排查"某玩家为什么不能禁言/观战/禁建（被检举封权/24h 冷却）"、查看检举封禁名单。
- **控制台输出落盘**：`mdtserver/start-server.ps1` 自 2026-10-17 起把 java 的 stdout/stderr **逐行写入**
  `mdtserver/config/logs/console-<yyyyMMdd-HHmmss>.log`（`StreamWriter` + `AutoFlush`；提交 `7364043` 修掉了 Windows PowerShell 5.1
  `Tee-Object` 不实时刷盘与 `$ErrorActionPreference=Stop` 中断两个坑；每次启动一个文件，**改动需重启启动器才生效**）。
  SA 的部分编译/初始化信息只出现在控制台、不进 `config/logs/log-0.txt`，排查时优先看这里。
- **块注释嵌套扫描器**：`tools/scan-nested-block-comment.ps1`：状态机扫描 `mdtserver/config/scripts` 下全部
  `.kts/.kt` 的块注释内是否再出现 `/*`（SA 3.4.0 会因此**静默截断脚本**，见
  [Agent 调试经验与常见坑](agent-debug-experience.md) 第 9 节）。在 `mdtdo` 目录执行
  `pwsh -File tools\scan-nested-block-comment.ps1`，全仓 206 个文件、秒级；命中输出
  `文件 : 第 N 行 块注释内出现 '/*'`。改完大脚本的 KDoc 后先扫一遍再 `sa load`。

## 服务器门户 Web（`stats-web/`，2026-10-17 升级）

- **三页**：`/` 门户首页（欢迎页 + 服务器状态卡 + SVG 趋势图 + 实时消息流 + 原「履历/运维」数据看板）、
  `/upload.html` 公开投稿（DP/地图）、`/admin.html` 管理员登录 + 审核后台；前端纯静态、无构建、无外网 CDN。
- **后端**：独立 PowerShell HttpListener 进程（`stats-web/server/*.ps1`，零外部依赖），默认监听 **127.0.0.1:8090**；
  启动 `cd stats-web; .\start-web.ps1`（`-Port/-Bind/-StatsJson/-Config` 覆盖；`-InstallAcl` 注册公网 URL 保留；
  `-GenPasswordHash` 生成密码哈希）；公网访问先 `-InstallAcl` → `-Bind 0.0.0.0` → 防火墙放行，建议前置反代 HTTPS。
- **默认管理员 `admin / ChangeMe_123456` 必须在首次部署后立即修改**（启动横幅红色警告）；推荐
  `.\start-web.ps1 -GenPasswordHash "新密码"` 后填 `web-config.json` 的 `admin.passwordHash` 并清空 `admin.password`。
- **演示模式**：URL 加 `?mock=1` 使用本地假后端（演示账号 `admin/demo123`，不产生真实请求），`?mock=0` 关闭；
  前端资源版本号 `?v=` 需手工 bump。
- **数据来源**：状态 `mdtserver/config/stats/server-status.json`（`serverStats.kts` 写）、事件流 `live-events.jsonl`
  （`liveEventFeed.kts` 写）；`stats-web/web-data/` 是运行数据（已 gitignore，不提交、不打包）。
- **分发/升级**：整目录复制 `stats-web/`（含三页、`assets/`、`server/`、`start-web.*`、`web-config*`；不含 `web-data/`），
  旧分发包 `复制插件用\stats-web.zip` 已过期；旧部署首次启动自动迁移 `web-config.ps1` → `web-config.json`，
  **生产升级不要把仓库默认 `web-config.json` 带过去**（详见 [web-portal.md](web-portal.md) §3.4 与 `deploy-sync-checklist.md` §4.1）。

## 当前候选基线（以实际文件复核为准）

- **当前基线：Mindustry v160.7 / MindustryX 预览版 `prerelease-2026.10.10.B509`**（上游 `origin/main` = `703f65aa`，
  其 `work` 子模块即 Mindustry v160.7 提交 `17012d61`）；上一基线为 v160.3 / B495，再上为正式发行版 X37 / v160.1；
- 基线 JAR：`mdtserver/server-2026.10.10.B509.jar`（`version.properties` 内 `build=160.7`），
  已在 `server.properties` 的 `jar=` 中显式指定；SHA-256 `FC1B01AB9F90B3F761AFB0045547FE2C0347B645BB050C648CB230698676AC74`；
  切换后冷启动 `158/154/149/出错0`、`sa listFailed` 为空；
- 获取方式：直接从 GitHub 发行版下载（`TinyLake/MindustryX` releases 的 `server-*.jar`，含 `prerelease-*` 预览版），
  不再自建/打补丁；网络走本地代理（`Invoke-WebRequest -Proxy http://127.0.0.1:7897`）；
- 回滚：`server-2026.09.13.B495.jar`（v160.3）、`server-2026.09.X37.jar`（v160.1）、`server-2026.09.11.B491.jar`（同 v160.1）、
  `server-2026.08.12.B485.jar`（v159.7）都保留在 `mdtserver/`，回滚只改 `server.properties` 的 `jar=`（该文件不入库、按机器各自维护）；
- **上游 DP 侧核对（2026-10-16，v160.3→v160.7）**：`mod/`+`io/` 仅 9 个提交，涉及 `DataImagePacker`(+5)、`DataManager`(+30/-14)、
  `DataPatcher`(±4)、`ModClassLoader/ModListing/Mods`；**`DataManager.reloadPatches(Seq<PatchAsset>)`、
  `DataPatcher.apply(Seq<PatchAsset>, Seq<ContentAsset>)`、`PatchAsset(String)`、`DataAsset.updateData(byte[])` 签名全在（javap 复核）**，
  DP 字段未动 → 属"小版本兼容"；`DataAssetType` 只有 6 类（`patch/content/bundle/image/sound/music`），与脚本里写死的目录清单一致；
  MindustryX 另有 `0075-FIX.G-DataPatcher-refresh-icons-of-all-content-after.patch`（DP 打补丁后刷新内容图标，与"贴图为空"症状相关）；
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