# 生产同步清单（159 → 160.x 升级 / 日常脚本同步）

> 用途：把开发工作区（`mdtdo\mdtserver`）的改动安全地搬到生产服。
> **结论先行：只复制 `config\scripts` + JAR 是不够的，而且直接镜像 `config\scripts` 会覆盖生产数据库。**
> 适用边界：Agent 不能访问/停启/替换生产，本清单只作为用户或运维的执行依据（见 `project-memory.md`「生产与证据边界」）。

## 1. 必须同步（缺一不可）

| 项 | 来源 | 说明 |
|---|---|---|
| 游戏服务端 JAR | `mdtserver\server-2026.09.13.B495.jar` | **必须是 MindustryX 构建**，不能用官方 Mindustry 的 jar（`trafficMonitor.kts` 直接依赖 `mindustryX.events.SendPacketEvent`） |
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
   - 控制台/日志 `共找到N脚本,加载成功N,启用成功N,出错0`（当前脚本集在 160.3/B495 上为 `157/153/148/出错0`）；
   - `config\logs\script-load\last-error.log` 为空；
   - 6567 开服正常，`status` 可用。
6. 客户端侧：159 → 160.3 是**游戏版本跳跃**，玩家需要兼容的客户端（`allowCustomClients=true` 已开）；
   首次进服会重新协商资产（DP/CP 与音频会被重新下载）属正常现象。

## 4. 「例程集」与变更声明惯例（2026-09-13 与用户约定）

**例程集**（日常同步只需这些，无需额外声明）：

- `config\scripts\**`（排除 `data`、`external-cp`、`cache`）
- `server-*.jar` + `server.properties` 的 `jar=` 一键

**只要改动落在下面任一项，就必须在提交信息里显式声明"生产同步范围 +"，并更新本节的"最后一次变动"表**：

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
| 启动器 | 2026-07-22（提交 `e3fe7fc`） | `start-server.ps1` 2026-07-22 01:17、SHA-256 前缀 `80638876C379E205`；`start-server.sh` `0456FAAB24F3E4EE`；`启动服务器.bat` 2026-05-19 `36BF0D2076DAC394` | **不需要**（除非生产在用的启动器比 7-22 更旧） |
| ScriptAgent 插件包 | 2026-07-29 23:05（维护者竞态修复构建，运行时 `ScriptAgent c2823c1`） | `config\mods\ScriptAgent4MindustryExt-3.4.0-allInOne.jar`，SHA-256 `9D51FCDCB65D21C16564F544B83E2EDB4AAD5765427A791A577FB1F6DDDC2214` | **不需要**（160 适配全部在脚本侧，插件包未动） |
| `libs\` | 2026-07-17（随 SA 3.4.0 迁移的 kotlin 2.3.20 一批，最新文件 mtime 2026-07-17 15:23） | 目录内无 7-17 之后的文件 | **不需要**（生产若已含 7-17 那批即可） |
| `server.properties` 非 `jar=` 键 | 未变（`java`/`javaOptions`/`autoRestartOnCrash`/`crashRestart*` 等启动器功能键自 7-22 起） | 仅在使用本仓库启动器时需要这些键 | 只改 `jar=` |
| 脚本代码 | 持续变动（最近：2026-09-13 菜单/缩放/版本跟进） | `config\scripts\**` | **需要同步** |

> 2026-09-13 核对口径：`git log --name-only` 最近 22 个提交里，落在例程集之外的改动只有 `README.md`、
> `mdtserver\启动说明.md`、`.gitignore` 与 `docs\**`，**没有一次涉及启动器、插件包或 `libs\`**。

## 5. 已知风险 / 未验证项

- 当前脚本集**只在 160.x（B491/X37/B495）上验证过**；"只把新脚本推到仍在 159 的生产、不动 JAR"属于**未验证组合**
  （需要时可在隔离副本用 B485 跑一次冷启动确认）。
- ScriptAgent 使用的是**未发行的修复构建**，正式发行版尚未提供，生产是否采用需用户/运维确认（`project-memory.md` 风险项）。
- 真实多人 / 高上行 / 运行中卸载 DP / 生产大库的观察项见
  [当前问题与待办](current-issues.md) 第 6 节；跑数据量更大的生产库前务必先备份。
- 相关文档：[官方 v159 / MindustryX 兼容层](official-v159-compat.md)、[网络同步与完整重同步](v159-network-sync.md)、
  [脚本维护总览](scripts-maintenance.md)、[Agent 调试经验](agent-debug-experience.md)。
