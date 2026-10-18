# 生产同步清单（经验清单，不是流程规定）

> 用途：把开发工作区（`mdtdo\mdtserver`）的改动安全地搬到生产服。
> **结论先行：只复制 `config\scripts` + JAR 是不够的，而且直接镜像 `config\scripts` 会覆盖生产数据库。**
> 适用边界：Agent 不能访问/停启/替换生产，本清单只作为用户或运维的执行依据（见 `project-memory.md`「生产与证据边界」）。

## 1. 同步范围（这几项缺一不可）

| 项 | 来源 | 说明 |
|---|---|---|
| 游戏服务端 JAR | `mdtserver\server-2026.10.10.B509.jar`（MindustryX `prerelease-2026.10.10.B509` / Mindustry **v160.7**，SHA-256 `FC1B01AB…AC74`） | **必须是 MindustryX 构建**，不能用官方 Mindustry 的 jar（`trafficMonitor.kts` 直接依赖 `mindustryX.events.SendPacketEvent`）；回滚线 B495/X37/B491/B485 都保留 |
| `jar=` 指向 | `server.properties` | 只改这一个键；`jar=` 指向的文件不存在时启动脚本会直接报错（不会静默换 jar） |
| 脚本代码 | `mdtserver\config\scripts\`（**排除** `data`、`external-cp`、`cache`） | 等价于插件仓库 `ScriptAgent4MindustryExt-mdtdo` 里被 git 跟踪的那套文件；插件仓库的 `.gitignore` 已经把 `*.db`、`logs`、`external-cp/*` 排除掉 |
| ScriptAgent 插件本体 | `mdtserver\config\mods\ScriptAgent4MindustryExt-3.4.0-allInOne.jar` | 文件名不变但内容已是维护者提供的**未发行竞态修复构建**（运行时 `ScriptAgent c2823c1`）。生产若在用更旧的包必须替换（并保留旧包备份） |
| 依赖库 | `mdtserver\libs\` | SA 脚本的依赖集合：kotlin 编译器/stdlib、exposed + h2、mongo/redis 客户端、jackson、jansi/jline 等。**同步策略：补齐/更新缺失项，不要删除生产上多出来的未知 jar**；`libs\maven`、`libs\poms` 目录也要一起带 |
| 启动器（仅当生产也用本仓库启动器） | `start-server.ps1` / `启动服务器.bat` / `start-server.sh` | 这些是仓库自带的守护启动器（崩溃重启/日志轮转/H2 预热），生产若用自建启动脚本则跳过 |

## 2. 绝对不能覆盖（生产运行数据）

- `config\scripts\data\` —— **含 H2 数据库 `h2DB.db.mv.db`**、KV 存储 `kvStore.mv`、`ip2region_v4.xdb`（IP 风控库）、
  `lang.ini`（文案）与 `config.conf`（**脚本配置，生产调好的值都在这里**）。
  其中只有 `config.conf` 会被插件仓库跟踪；其余全是运行数据。
- `config\scripts\external-cp\` —— 生产内容补丁包（`*.zip` / `*.json` / `*.hjson`）。
- `config\scripts\cache\` —— 编译缓存；**复制完新代码后应当清空**（不清理会拿到陈旧字节码，历史上误导过验证结果）。
- 其余 `config\` 运行数据：`saves\`、`maps\`、`stats\`、`logs\`、`music-jukebox\`、`assetCache\`、`assets\`、
  `settings.bin`、`settings_backup.bin`、`settings_backups\`、`rules.hjson`、`tmp\`；以及顶层的 `sa-cache-backups\`。
- `server.properties` —— **是合并而不是覆盖**：生产自己的 `description` / `motd` / `port` / `playerLimit` /
  `javaOptions` 等要保留（本机开发那份还带着"这是开发服务端"的标记与 `socketInput=true`）。

## 3. 执行顺序（建议）

1. **备份**：`config\scripts\data\`（尤其 `h2DB.db.mv.db`）、`config\scripts\external-cp\`、`server.properties`、旧 JAR 与旧 SA 插件包。
2. **先干跑**：`robocopy "<dev>\mdtserver\config\scripts" "<prod>\config\scripts" /MIR /XD cache data external-cp /L`
   确认要动的文件清单（`/L` 只列不删）。本仓库隔离测试副本用的就是同一口径：
   `.agents\test160.ps1` 里的 `robocopy ... /MIR /XD cache data external-cp`。
3. 正式同步时把 `/L` 去掉；**若生产 `config\scripts` 下还有本仓库没有的自有文件，改用 `/E`（只增改不删）**。
4. 放入新 JAR、替换 SA 插件包（旧包改名 `*.bak`）、同步 `libs\`。
5. 清空 `config\scripts\cache\`，启动，检查：
   - 控制台/日志 `共找到N脚本,加载成功N,启用成功N,出错0`（当前工作区在 **B509/v160.7** 上冷启动实测
     `159/155/150/出错0`，2026-10-17 17:38；开发中未提交脚本会让脚本数变化，以实际 banner 为准）；
   - `config\logs\script-load\last-error.log` 为空；
   - 6567 开服正常，`status` 可用。
6. 客户端侧：生产若仍在 v159.7/B485，同步到 **v160.7/B509** 是**跨大版本跳跃**，玩家需要兼容的客户端（`allowCustomClients=true` 已开）；
   首次进服会重新协商资产（DP/CP 与音频会被重新下载）属正常现象；B509 的真实客户端观感/内容同步**尚未验证**（见 current-issues.md）。

### 3.1 用自动更新器代替手工复制脚本（推荐）

仓库里已带**独立更新器项目** `updater/`（插件仓库根目录同名目录，可整目录复制到生产任意位置）：

| 脚本 | 作用 | 要点 |
|---|---|---|
| `update-plugins.bat` | 严格镜像 `scripts/**` → 生产 `config\scripts` | **必须先关闭服务器**；默认保护 `data\`、`external-cp\`、`cache\`；仓库已删除的脚本会同步删除；更新前自动备份，可 `-Rollback` |
| `update-web.bat` | 严格镜像 `stats-web/**` → 生产 Web 目录 | 不用停游戏服；保护 `web-data\`、`web-config.json`、`web-config.ps1`；更新后**重启 Web 进程** |
| `update-plugins-clear-cache.bat` | 先**显式清空**脚本编译缓存，再严格镜像 `scripts/**` | 同样必须停服；需要强制重编译时用 |

- 部署：复制整个 `updater\` 到生产（如 `E:\mdt-updater\`），改 `config.json` 里的
  `serverScriptsPath` / `statsWebPath` / `backupPath` 三个绝对路径即可；零 git、无需改服务器文件；
- 推荐流程：`-DryRun` 预览变更清单 → 关闭服务器 → 正式更新 → 启动服务器检查脚本加载；
- 支持 `-Proxy http://127.0.0.1:7897` / `-ProxyPort 7897`（也读环境变量 `HTTPS_PROXY`）；
- Web 与插件用同一个仓库 zip，更新器只取 `scripts/` 与 `stats-web/`，忽略 `docs/` 等；
  更新器自身也在插件仓库根 `updater/`，可整目录复制新版升级；
- **生产可用 updater 代替本清单第 1 节「脚本代码」+ 第 3 节 robocopy 的手工复制流程**
  （首次仍建议对照 `-DryRun` 输出核对删除清单；自定义脚本要加入 `protectedServerFiles`）。
- 完整用法/参数/配置/FAQ 见 [`updater/README.md`](../updater/README.md)。

---
## 4. 「例程集」与变更声明惯例（2026-09-13 与用户约定）

**例程集**（日常同步只需这些，无需额外声明）：

- `config\scripts\**`（排除 `data`、`external-cp`、`cache`）
- `server-*.jar` + `server.properties` 的 `jar=` 一键

**经验（2026-09-19）：改动落在下面任一项时，在提交信息里写一句"生产同步范围 +"最省事**——config\mods\** 与 libs\** 都在 .gitignore 里，**git 看不到它们的变化**，事后想判断"某次改动要不要跟着换文件"只能靠这句话（配哈希核对）。

- `mdtserver\start-server.ps1` / `start-server.sh` / `启动服务器.bat`（启动器）
- `config\mods\**`（ScriptAgent 插件包）
- `libs\**`（脚本依赖库）
- `config\` 目录结构或新增键、`server.properties` 里 `jar=` 之外的键
- 客户端兼容性要求（游戏协议/版本跳跃）

> 为什么必须靠人肉声明：`config\mods\**` 与 `libs\**` 都在 `.gitignore` 里，**git 看不到它们的变化**；
> 一旦它们变了而生产没同步，表现是脚本加载失败或行为异常，且从提交记录里查不出来。

### 最后一次变动（2026-09-13 核对；之后若再改请更新本表）

| 项 | 最后一次变动 | 凭据 | 159 → 160.x 是否需要动 |
|---|---|---|---|
| 启动器 | **2026-10-17（提交 `7364043`：控制台输出逐行落盘，修掉 PS5.1 `Tee-Object` 不实时刷盘 + 顶部 `$ErrorActionPreference=Stop` 中断两个坑）** | `start-server.ps1` 2026-10-17 17:38、SHA-256 前缀 `AB5240E81EA2F17F`；`start-server.sh` 仍 2026-07-22 `0456FAAB…`；`启动服务器.bat` 仍 2026-05-19 `36BF0D20…` | **需要**（若生产用本仓库启动器：新启动器会多生成 `config\logs\console-<时间>.log` 控制台全量日志；旧版缺该日志，但启动功能不受影响） |
| ScriptAgent 插件包 | 2026-07-29 23:05（维护者竞态修复构建，运行时 `ScriptAgent c2823c1`；2026-10-17 复核仍是该包） | `config\mods\ScriptAgent4MindustryExt-3.4.0-allInOne.jar`，SHA-256 `9D51FCDCB65D21C16564F544B83E2EDB4AAD5765427A791A577FB1F6DDDC2214` | **不需要**（160 适配全部在脚本侧，插件包未动；上游官方 3.4.0 release 包只在 `.agents\SA-3.4.0-release.jar` 留档，**未替换运行包**） |
| `libs\` | 2026-07-17（随 SA 3.4.0 迁移的 kotlin 2.3.20 一批，最新文件 mtime 2026-07-17 16:02，2026-10-17 复核未变） | 目录内无 7-17 之后的文件 | **不需要**（生产若已含 7-17 那批即可） |
| `server.properties` 非 `jar=` 键 | 启动器功能键未变（`java`/`javaOptions`/`autoRestartOnCrash`/`crashRestart*` 等自 7-22 起）；`jar=` 已切换为 B509 | 仅在使用本仓库启动器时需要这些键 | 只改 `jar=`（指向 `server-2026.10.10.B509.jar`） |
| 脚本代码 | 持续变动（最近：2026-10-17 DP 兼容改造最终版 `729dff3`、调试设施 `cdc8b17`（含 `wayzer/cmds/scriptDiag.kts`、`tools/`）；惩罚体系改造进行中、未提交） | `config\scripts\**` | **需要同步** |

> 2026-10-17 核对口径：`git log --name-only --since=2026-09-13` 里落在例程集之外的改动是 `mdtserver/start-server.ps1`、
> `tools/scan-nested-block-comment.ps1`、`README.md`、`mdtserver/启动说明.md`——**启动器已变（`7364043`）**，插件包与 `libs\` 未变
> （`config/mods`、`libs` 在 `.gitignore` 里，靠哈希与 mtime 核对）；插件仓库 `ScriptAgent4MindustryExt-mdtdo` 仍停在
> 2026-10-16 的 `5f76efb`，2026-10-17 的 scripts/docs 变更尚未同步（由 Lead 统一安排）。

### 4.1 Web 端（`stats-web\`）：第二类同步目标

**Web 改动 = 同步 `stats-web\` 整个目录**（**三页门户 + 后端 API**：无构建、无 npm、无外网依赖，Windows PowerShell HttpListener；
第二期起含日志下载/历史消息/上行带宽模块；权威说明见 `docs/web-portal.md`、`stats-web/README.md`）：

| 文件/目录 | 是否要同步 | 说明 |
|---|---|---|
| `index.html` / `upload.html` / `admin.html` | ✅ 要 | 门户首页 / 公开投稿 / 管理后台；静态文件改动后**刷新页面**即可 |
| `assets/`（整个目录） | ✅ 要 | 前端 css/js/mock/自测页；全部本地、无外网 CDN；资源版本 `?v=` 改了要同步（当前 `20261018b`） |
| `server/`（整个目录） | ✅ 要 | 后端模块（API/上传审核/会话/控制台桥 + 第二期 `portal-logs`/`portal-history`/`portal-metrics`，共 9 个）；改动后必须**重启 Web 服务** |
| `start-web.ps1` / `start-web.bat` | ✅ 要 | 启动入口（默认 `127.0.0.1:8090`；兼容旧参数，新增 `-Config`/`-GenPasswordHash`） |
| `web-config.json` | ⚠️ **不要用仓库默认值覆盖** | **主配置**：监听/管理员凭据/上传限制/目录/热重载；第二期新增 `logs`/`bandwidth`/`events` 段（**旧配置缺键自动用默认值**，但生产仍按目标机器实际值维护，仓库这份含默认凭据） |
| `web-config.ps1` | ⚠️ 旧部署兼容/迁移用 | 旧部署（只有 ps1）首次启动会自动迁移成 `web-config.json` 并改名备份；**升级时保留线上旧文件、不要覆盖** |
| `README.md` | 可带 | 门户版说明（分发/升级/默认凭据必改） |

容易踩的连带关系（都来自实际踩坑）：

1. **数据契约（三路）**：状态页读 `mdtserver\config\stats\server-status.json`（`serverStats.kts`，60 秒刷新）；
   实时/历史事件读 `mdtserver\config\stats\live-events*.jsonl`（`liveEventFeed.kts` 异步写，**当前+12 份旋转历史**）；
   日志面板只读 `mdtserver\config\logs\` 白名单文件（`log-<数字>.txt`/`console-<时间>.log`/`last.log`/`restart-supervisor.log`）。
   凡涉及**新增/改名 JSON/事件字段或日志命名**的 Web 改动，必须**同时同步对应服务器脚本/命名**。
2. **生效方式**：静态页刷新即可；`server/`、`web-config.json`、端口或绑定变更需**重启 Web**。
   公网访问的前置条件：管理员执行一次 `.\start-web.ps1 -InstallAcl -Port <端口>`（换端口/换机器要重做）、
   `-Bind 0.0.0.0`（或改 `bind`）、VPS 防火墙放行；**默认监听仅 127.0.0.1:8090（旧静态站默认 8081 已变）**，
   公网前务必先改默认管理员密码（`admin / ChangeMe_123456`）并建议反代 HTTPS。
3. **旧部署升级（重点）**：替换新版目录时**不要把仓库默认 `web-config.json` 一起带过去**，否则门户会优先用它
   （`127.0.0.1:8090`）而**不会自动迁移**旧 `web-config.ps1`，公网访问会失效；保留线上旧 `web-config.ps1` + 旧 `web-data/`，
   首次启动确认日志 `[升级] 已把旧配置迁移到…` 后**立即修改默认密码**。完整 6 步见 `docs/web-portal.md` §3.4。
   **第二期新增配置段缺键会自动用默认值**（无需手工补全），但升级后请重启 Web 让新模块加载。
4. **日志下载与带宽（第二期，生产注意）**：日志默认 `logs.requireLogin=true`（**含玩家 IP/聊天等敏感信息，需管理员登录**），
   下载直链的 `?token=` 会出现在 URL/浏览器历史、勿分享；带宽是**宿主机网卡总上行**（B/s，含游戏服/Web/系统），
   游戏服与 Web 不同机时无意义。**控制台桥是可选增强**：生产不开 6859 调试端口是常态，审核/删除仍成功，仅不热重载/不刷新列表。
5. **备份缺口**：`stats-web\` 只存在于本地 `mdtdo` 仓库（该仓库**没有远程**），插件仓库
   `ScriptAgent4MindustryExt-mdtdo` 只镜像 `docs/` 与 `scripts/`，**不包含 stats-web**。
   即"推送一轮"不会把它推到任何远程，异地备份需要另行安排；`stats-web/web-data/` 是运行数据（已 gitignore，不打包）。

> 因此 Web 改动同样属于"需要显式声明生产同步范围"的一类：提交信息里写清是同步 `stats-web\`
> 还是脚本、还是两者都要（字段/事件契约变更时两者都要）。
> **旧分发包 `复制插件用\stats-web.zip` 已过期**（只含旧的单文件静态页）：请改为整目录复制，或按
> `stats-web/README.md` 的打包示例（排除 `web-data/`）重新打包。

## 5. 已知风险 / 未验证项

- 当前脚本集已在 **v160.7/B509** 冷启动验证（`159/155/150/出错0`，2026-10-17）并完成 DP 实测加载（`赛克兰特高阶科技.zip`）；
  但整体仍只在 160.x（B491/X37/B495/B509）上验证过。"只把新脚本推到仍在 159 的生产、不动 JAR"属于**未验证组合**
  （需要时可在隔离副本用 B485 跑一次冷启动确认）。
- ScriptAgent 使用的是**未发行的修复构建**，正式发行版尚未提供，生产是否采用需用户/运维确认（`project-memory.md` 风险项）。
- 真实多人 / 高上行 / 运行中卸载 DP / 生产大库的观察项见
  [当前问题与待办](current-issues.md) 第 6 节；跑数据量更大的生产库前务必先备份。
- 相关文档：[官方 v159 / MindustryX 兼容层](official-v159-compat.md)、[网络同步与完整重同步](v159-network-sync.md)、
  [脚本维护总览](scripts-maintenance.md)、[Agent 调试经验](agent-debug-experience.md)。
