# MDT 更新器（mdtdo updater）

一个**独立的小项目**：放在 Windows 主机任意目录（例如 `E:\mdt-updater\`）即可使用，通过 `config.json`
里配置的**绝对路径**，一条命令把 GitHub 插件仓库里的最新内容**严格镜像**同步到生产环境：

- 服务器插件脚本：仓库 `scripts/**` → `serverScriptsPath`（服务器运行中时，严格镜像后通过
  **console socket 逐个 `sa load`/`sa unload` 热重载**，并尾随脚本加载日志确认完成；首次部署 socket 需停服一次）；
- 服务器门户 Web：仓库 `stats-web/**` → `statsWebPath`（更新后需重启 Web 进程）；
- 邮件服务（主服 MDT DO）：仓库 `email-service/**` → `emailServicePath`（只新增/更新，
  **保护生产 `config.json` 与 `logs\`/`dist\`**，更新成功后自动重启计划任务 `MDT Email Service` 使新代码生效）；
- 清缓存变体：先清空脚本编译缓存，再严格镜像更新插件（必须停服）；
- **主服快速同步**：`update-mainserver` = 插件 `scripts`（分支 `main`，可运行中热重载）+ `email-service`；
- **分服快速同步**：`update-subserver` = 只同步插件 `scripts`（默认分支 `mdc-do`，
  **不部署 email-service / 账号系统**，保持分服原有基线）。

> 两个服的分支约定（分支由 Lead 统一维护，更新器只按分支名下载 zip，**不会切/改任何 git 分支**）：
> **主服 MDT DO = `main`**（跟进账号/邮箱验证系统）；**分服 MDC DO = `mdc-do`**（不含账号系统，保持原有）。
> 详见 [§2.1](#21-主服--分服分支策略与快速同步2026-11-02-新增)。

零 git 依赖、自带代理支持、出问题可一键回滚，下载/校验失败**绝不改动目标目录**。

```
updater/
├─ config.json                          # 唯一需要改的文件（绝对路径 + 仓库/代理/保护规则）
├─ UpdateCommon.ps1                     # 公共实现（各入口脚本共用；一般不用改）
├─ update-mainserver.ps1 / .bat         # ★ 主服（MDT DO）快速同步：scripts(main) + email-service
├─ update-mainserver-scripts.bat         # ★ 主服只更新 scripts(main)，不检查/不更新 email-service
├─ update-subserver.ps1 / .bat          # ★ 分服（MDC DO）快速同步：只 scripts(默认 mdc-do)
├─ update-email-service.ps1 / .bat      # ★ 只同步 email-service（保护 config.json / logs）
├─ update-plugins.ps1 / .bat            # ① 严格镜像更新服务器插件脚本（运行中可 console socket 逐个热重载）
├─ update-web.ps1 / .bat                # ② 严格镜像更新 Web 门户（不碰游戏服）
├─ update-plugins-clear-cache.ps1 / .bat# ③ 清空编译缓存 + 严格镜像更新插件（须停服）
├─ README.md                            # 本文件
└─ logs/                                # 运行时自动创建：update-<plugins|web|email>-<时间>.log
```

> 适用环境：Windows + Windows PowerShell 5.1 及以上（`powershell.exe`）；不需要管理员权限
> （除非目标目录/备份目录本身需要提权才能写）。`.ps1` 文件必须保持 **UTF-8 with BOM** 编码。

---

## 1. 快速开始（生产部署）

1. **整目录复制**：把整个 `updater\` 目录复制到生产主机任意位置，例如 `E:\mdt-updater\`。
   （可以复制到任意盘符/目录，脚本内部全部使用 `config.json` 里的绝对路径，不依赖相对布局。）
2. **改 `config.json`**：至少填好这三个绝对路径（主服还要填第 4 个）：
   - `serverScriptsPath`：生产服务器脚本目录，例如 `E:\\mdtserver\\config\\scripts`；
   - `statsWebPath`：生产 Web 门户目录，例如 `E:\\stats-web`；
   - `backupPath`：备份根目录，例如 `E:\\mdt-updater-backup`（会自动创建 `plugins\` / `web\` / `email\` 子目录）；
   - `emailServicePath`（**仅主服 MDT DO**）：邮件服务目录，例如 `E:\\mdt-email-service`；
     分服 MDC DO 不部署邮件服务，可留空（分服脚本也不会用到它）。
3. **（可选）开启运行中热更新**：生产 `mdtserver\config\server.properties` 增加三行
   `socketInput=true`、`socketInputPort=6859`、`socketInputAddress=localhost`（**只绑本机**），重启服务器生效；
   之后 `update-plugins` 就能在服务器运行中逐个 `sa load`/`sa unload` 热重载变更脚本。
   首次启用需要**停服更新一次**（换新 updater + 改 server.properties），详见第 10 章。
4. **更新插件**：双击 `update-plugins.bat`，或在命令行运行 `.\update-plugins.bat`。
   建议第一次先跑 `.\update-plugins.bat -DryRun` 看会新增/修改/删除哪些文件、将逐个执行哪些 sa 命令。
   服务器运行中且 socket 可用 → 自动逐个热重载（无需停服）；服务器未运行 → 按停服流程更新，下次启动加载。
5. **启动服务器验证**：运行 `mdtserver\启动服务器.bat`，检查 `config\logs\script-load\last-error.log`
   为空、脚本加载数量正常。
6. **只想更新 Web**：随时运行 `.\update-web.bat`（**不需要**关闭游戏服），更新完**重启 Web 进程**
   （关闭 `start-web` 窗口后重新运行 `start-web.bat`）。

> 生产升级推荐顺序：`-DryRun` 预览 → 服务器运行中直接 `update-plugins.bat`（socket 热重载）
> 或停服后 `update-plugins.bat` → 启动服务器验证；Web 可以独立更新，`update-web.bat` 后重启 Web 即可。

### 1.1 主服 / 分服一条命令（2026-11-02 新增，推荐）

两个服各用一条命令即可，不需要手工 robocopy、也不需要在服务器上装 git：

| 服务器 | 命令 | 同步内容 | 分支 |
|---|---|---|---|
| **主服 MDT DO** | `update-mainserver.bat` | 插件 `scripts/**`（严格镜像 + 可选热重载）+ `email-service/**`（只增改，保护 `config.json`/`logs`） | `main` |
| **主服仅插件** | `update-mainserver-scripts.bat` | 只同步 `scripts/**`（严格镜像 + 热重载），完全不检查/不更新 email-service | `main` |
| **分服 MDC DO** | `update-subserver.bat` | 只同步插件 `scripts/**`（严格镜像 + 可选热重载），不部署 email-service/账号系统 | `mdc-do`（`-Branch` 可覆盖） |

```bat
:: 第一次使用先预览（零风险，不改任何文件）
update-mainserver.bat -DryRun
update-mainserver-scripts.bat -DryRun
update-subserver.bat  -DryRun
:: 正式更新
update-mainserver.bat
update-mainserver-scripts.bat
update-subserver.bat
```

> 分支名、`main` / `mdc-do` 的用途与完整注意事项见 [§2.1](#21-主服--分服分支策略与快速同步2026-11-02-新增)。
> 只想同步其中一项时：主服用 `-NoEmailService`（只更新插件）/ `-NoScripts`（只更新邮件服务）。

---

## 2. 脚本一览

| 脚本 | 作用 | 需要停游戏服 | 受保护内容（默认） | 更新后要做什么 |
|---|---|---|---|---|
| `update-mainserver.bat` | **主服一键**：`scripts/**`（严格镜像 + 运行中逐个热重载）+ `email-service/**`（只新增/更新） | 插件部分同 `update-plugins`；email-service 不需要停游戏服 | `data\`、`external-cp\`、`cache\`；`email-service` 的 `config.json`、`logs\`、`dist\` | 邮件服务如正在运行需**重启**；游戏服无需重启 |
| `update-mainserver-scripts.bat` | **主服仅插件**：只同步 `scripts/**`（`main`，严格镜像 + 运行中逐个热重载），不检查/不更新 email-service | 同 `update-plugins`（socket 可用时逐个热重载） | `data\`、`external-cp\`、`cache\` | 无（服务器未运行则手动启动验证） |
| `update-subserver.bat` | **分服一键**：只严格镜像 `scripts/**`（默认分支 `mdc-do`，不含账号系统） | 同 `update-plugins`（socket 可用时热重载） | `data\`、`external-cp\`、`cache\` | 无（服务器未运行则手动启动验证） |
| `update-email-service.bat` | 只同步 `email-service/**`（默认只新增/更新、不删除本地文件） | 否 | `config.json`、`logs\`、`dist\` | 更新后自动重启计划任务（`-NoRestartEmailService` 可关闭） |
| `update-plugins.bat` | 严格镜像 `scripts/**` → `serverScriptsPath`；服务器运行中时通过 console socket **逐个**热重载变更脚本 | **否**（socket 可用时逐个热重载；首次部署 socket 需停服一次） | `data\`、`external-cp\`、`cache\`（不碰不备份不删） | 无（服务器未运行则手动启动验证） |
| `update-web.bat` | 严格镜像 `stats-web/**` → `statsWebPath` | 否 | `web-data\`、`web-config.json`、`web-config.ps1` | **重启 Web 进程** |
| `update-plugins-clear-cache.bat` | **显式清空**编译缓存（默认 `<serverScriptsPath>\cache`）后再严格镜像 `scripts/**` | **是** | 同 `update-plugins`（但会先清空 cache 内容） | 手动启动服务器（首次会全量重新编译，较慢属正常） |

每个 `.bat` 只是 `powershell.exe -File <同名>.ps1 %*` 的包装（已 `chcp 65001`，透传全部参数）。
命令行运行 `.ps1` 也可以，例如：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\update-plugins.ps1 -DryRun
```

> 双击运行时窗口会暂停等待回车，方便看结果；带参数运行或设置环境变量 `MDT_UPDATER_NO_PAUSE=1`
> 时不暂停（便于计划任务/自动化调用）。

### 2.1 主服 / 分服分支策略与快速同步（2026-11-02 新增）

#### 分支用途

| 服务器 | 用途 | Git 分支 | 同步脚本 | 部署内容 |
|---|---|---|---|---|
| **主服 MDT DO** | 功能最全的正式服，**跟进账号/邮箱验证系统** | `main`（Lead 把账号/邮箱系统合并进 `main` 后） | `update-mainserver.bat` | 插件 `scripts/**` + `email-service/**` |
| **分服 MDC DO** | 保持原有玩法基线，**不跟进账号系统** | `mdc-do`（当前 main 基线，不含账号系统） | `update-subserver.bat` | 只部署插件 `scripts/**`（无 email-service、无账号系统） |

要点：

- 更新器**只按分支名下载 zip**，**不会**在服务器上 `git checkout`/`merge`/切换任何分支；
  分支的创建/合并/推送由 Lead 统一负责（当前约定：主服 `main`、分服 `mdc-do`）。
- 分服默认分支 `mdc-do` 可通过 `-Branch <分支名>` 覆盖（例如临时演练另一个基线的分支）；
  主服默认 `main`，同样可覆盖。**分服不要用 `main`**，否则会把账号/邮箱系统带过去。
- 两个服的 updater 各自独立部署、各自复制一份 `updater\`，`config.json` 填本机路径；
  分支的默认值在 `.bat`/`.ps1` 里（主服 `main`、分服 `mdc-do`），一般不用改 `config.json` 的 `branch`。

#### 主服（MDT DO）用法

```bat
:: 1) 预览：两个阶段（插件 scripts、email-service）会新增/修改/删除哪些文件
update-mainserver.bat -DryRun
:: 2) 正式同步：插件 scripts（main 分支，运行中经 socket 逐个热重载）+ email-service
update-mainserver.bat
:: 3) 只同步其中一项
update-mainserver.bat -NoEmailService    :: 只更新插件 scripts
update-mainserver.bat -NoScripts         :: 只更新 email-service（不需要停游戏服）
:: 4) email-service 的备份/回滚（独立入口，主脚本的 -Rollback 只回滚插件）
update-email-service.bat -Rollback
```

- **插件部分**：与 `update-plugins` 完全相同的严格镜像/保护/热重载逻辑（见 §5、§9、§10）。
- **email-service 部分**：
  - 从仓库 `email-service/**` **只新增/更新**服务文件，**默认不删除**目标目录里仓库没有的本地文件
    （如需严格镜像，在 `config.json` 设 `emailServicePruneDeleted=true`）；
  - 默认**保护** `config.json`（生产密钥/发件配置）、`logs\`（运行日志）、`dist\`（本地打包产物）：
    不复制、不备份、不删除——**仓库里的默认配置绝不会覆盖生产密钥**；
  - 仓库 `email-service/` 里没有 `config.json`（已被 `.gitignore` 排除）；若哪天误提交，更新器只警告、不会复制到生产；
  - 更新器会**自动重启计划任务** `MDT Email Service`，让新 `server.ps1` 立即生效；可用 `-NoRestartEmailService`
    或配置 `restartEmailServiceAfterUpdate=false` 关闭。未安装任务时只提示手动重启；输出会列出检测到的邮件服务 PID。
  - 仓库新增了配置字段时，更新器不会改生产 `config.json`：请对照 `config.example.json` 手工补字段。
- 主服脚本在**改动任何文件之前**会预检 email-service：`config.json` 的 `emailServicePath` 必须已配置且目录存在，
  否则直接退出码 2（插件阶段也不会动）——避免「插件更新完才发现邮件服务没配置」的半更新状态。
- 两阶段各自备份（`<backupPath>\plugins\` 与 `<backupPath>\email\`）并各自写 `.last-version`；
  阶段 1 失败（退出码 1/2）时会**跳过**阶段 2，避免脚本没更新好就继续改生产。

#### 分服（MDC DO）用法

```bat
:: 1) 预览（默认分支 mdc-do）
update-subserver.bat -DryRun
:: 2) 正式同步：只更新插件 scripts（严格镜像，保护 data/external-cp/cache）
update-subserver.bat
:: 3) 明确指定分支（一般不需要；默认就是 mdc-do）
update-subserver.bat -Branch mdc-do
:: 4) 回滚分服插件到上一次更新前
update-subserver.bat -Rollback
```

- 分服脚本**不同步** `email-service`、`stats-web`，也不部署任何账号/邮箱系统服务；
  分服要更新 Web 仍可用独立的 `update-web.bat`（如果分服也跑 Web）。
- 其余保护规则、热重载、停服要求、退出码与 `update-plugins` 一致。

#### 首次部署（两个服一样）

1. 把整个 `updater\` 目录复制到服务器任意位置（例如 `E:\mdt-updater\`）；
2. 改 `config.json`：`serverScriptsPath`、`backupPath`；主服再填 `emailServicePath`（例如 `E:\mdt-email-service`）；
3. 先跑 `-DryRun` 核对「新增/修改/删除」清单（尤其删除清单里不要有手工放进 `config\scripts\` 的自定义文件，
   如有请加到 `protectedServerFiles`）；
4. 正式运行：服务器运行中且已开 console socket → 脚本逐个热重载（详见 §10）；
   首次部署 socket / 含 `.kt` 库改动 → 停服更新（见 §9）。

> ⚠️ 生产路径示例：主服插件 `E:\mdtserver\config\scripts`、Web `E:\stats-web`、
> 邮件服务 `E:\mdt-email-service`、备份 `E:\mdt-updater-backup`；分服插件一般是
> `E:\mdtserver\config\scripts`（按分服机器实际路径填写），不需要 `emailServicePath`。

---

## 3. 参数表（各入口通用；标★的仅主服脚本支持）

| 参数 | 说明 |
|---|---|
| `-DryRun` | 只下载、校验并显示将「新增/修改/删除」的文件清单与运行中热重载计划（含「将逐个执行哪些 sa 命令 / 将拒绝」），**不备份、不改目标、不清缓存、不发任何 sa 命令、不写版本标记**。 |
| `-NoEmailService` ★ | （仅 `update-mainserver`）只同步插件 `scripts`，跳过 email-service 阶段。 |
| `-NoScripts` ★ | （仅 `update-mainserver`）只同步 email-service，跳过插件 `scripts` 阶段。 |
| `-NoRestartEmailService` ★ | （`update-mainserver` / `update-email-service`）同步邮件服务后不自动重启计划任务。 |
| `-NoHotReload` | 关闭运行中热重载（等同 `-HotReload off`）：服务器运行中时按 `requireServerStopped` 处理（默认拒绝，退出码 2）。首次部署 console socket 或只想停服更新时使用。 |
| `-HotReload <mode>` | 覆盖 `config.json` 的 `hotReloadMode`：`socket`（默认，socket 可用则逐个热重载）\| `auto`（同 socket，不可用时按 `requireServerStopped` 回退）\| `off`（等同 `-NoHotReload`）。 |
| `-Force` | 忽略「已是最新」判断，强制重新下载并执行严格镜像（用于本地被手改、版本标记异常等）。 |
| `-Proxy <url>` | 下载代理，如 `http://127.0.0.1:7897`。为空时若设置了环境变量 `HTTPS_PROXY` 会自动使用。 |
| `-ProxyPort <port>` | 本机代理端口快捷方式，等价于 `-Proxy http://127.0.0.1:<port>`。 |
| `-Config <path>` | 指定配置文件，默认用更新器目录下的 `config.json`。 |
| `-NoBackup` | 跳过备份（**不建议**）：更新失败时无法自动恢复。 |
| `-Rollback` | 回滚到最近一次更新前的备份（主服脚本只回滚插件脚本；email-service 回滚用 `update-email-service.bat -Rollback`）。 |
| `-RollbackTo <ts>` | 回滚到指定备份（备份目录名，如 `20261018-173000`；也可传备份目录完整路径）。 |
| `-KeepBackups <n>` | 覆盖配置里的备份保留份数（默认 5）。 |
| `-Branch <name>` | 覆盖仓库分支。默认值：主服 `update-mainserver`= `main`；分服 `update-subserver`= `mdc-do`；其余脚本用 `config.json` 的 `branch`（`main`）。 |
| `-Repo <owner/name>` | 覆盖仓库（默认 `QQQW114/ScriptAgent4MindustryExt-mdtdo`）。 |
| `-SourceZip <path>` | 离线/测试用：直接使用本地仓库 zip（结构同 GitHub zip，根目录含 `scripts/`、`stats-web/`、`email-service/`），跳过下载和远端版本检查。 |
| `-KeepTemp` | 保留本次下载解压的临时目录（排障用；默认无论成功失败都会清理）。 |

`-Rollback` 与 `-RollbackTo` 可以和 `-DryRun` 组合：只预览会恢复/删除哪些文件，不实际修改。
`-Rollback` 也可以和 `-Force` 组合：允许回滚到空备份（会把目标目录清空，危险）。
`update-mainserver` 的退出码是两阶段聚合：**优先级 `1`（失败）> `2`（拒绝执行）> `3`（文件已更新但热重载失败）> `0`（成功）**。

---

## 4. `config.json` 字段

| 字段 | 默认值 | 说明 |
|---|---|---|
| `repo` | `QQQW114/ScriptAgent4MindustryExt-mdtdo` | 仓库（owner/name）。仓库根目录含 `scripts/`、`stats-web/`、`email-service/`；`docs/`、`README.md` 等会被忽略。 |
| `branch` | `main` | 分支（按脚本下载 zip 用，更新器不会切换 git 分支）。主服 `main`；分服脚本默认用 `mdc-do` 覆盖本项。 |
| `proxy` | `""` | 下载代理，如 `http://127.0.0.1:7897`；空 = 直连（仍会读取环境变量 `HTTPS_PROXY`）。 |
| `proxyPort` | `0` | 本机代理端口快捷方式；与 `proxy` 同时存在时以 `proxy` 为准。 |
| `proxyFallbackDirect` | `true` | 代理失败后是否自动回退直连。 |
| `serverScriptsPath` | `""`（示例 `E:\mdtserver\config\scripts`） | **必填**：生产服务器脚本目录绝对路径。 |
| `statsWebPath` | `""`（示例 `E:\stats-web`） | **必填**（用 update-web 时）：生产 Web 目录绝对路径。 |
| `backupPath` | `""`（示例 `E:\mdt-updater-backup`） | **必填**：备份根目录，自动创建 `<backupPath>\plugins\`、`<backupPath>\web\`、`<backupPath>\email\`。 |
| `keepBackups` | `5` | 每类备份保留份数，超出删最旧（默认 1–50）。 |
| `protectedServerDirs` | `["data","external-cp","cache"]` | 服务器脚本目录中受保护的子目录（相对路径）：不复制、不备份、不删除。默认含 `cache`（编译缓存），普通更新不会动它。 |
| `protectedServerFiles` | `[]` | 服务器脚本目录中受保护的文件（相对路径）。 |
| `protectedWebDirs` | `["web-data"]` | Web 目录中受保护的子目录。 |
| `protectedWebFiles` | `["web-config.json","web-config.ps1"]` | Web 目录中受保护的文件（仓库里的默认配置绝不能覆盖生产配置）。 |
| `emailServicePath` | `""`（示例 `E:\mdt-email-service`） | **主服必填**（用 update-mainserver / update-email-service 时）：邮件服务目录绝对路径；分服可留空。 |
| `protectedEmailDirs` | `["logs","dist"]` | 邮件服务目录中受保护的子目录：不复制、不备份、不删除（`logs` 运行日志、`dist` 本地打包产物）。 |
| `protectedEmailFiles` | `["config.json"]` | 邮件服务目录中受保护的文件：**生产密钥/发件配置绝不能被仓库里的默认配置覆盖**。 |
| `emailServicePruneDeleted` | `false` | 邮件服务是否严格镜像：`false`（默认）= 只新增/更新、保留目标目录里仓库没有的本地文件；`true` = 与插件 `scripts/` 相同的严格镜像（会删除多余文件）。 |
| `emailServiceTaskName` | `MDT Email Service` | 邮件服务计划任务名；email-service 更新成功后自动重启它。 |
| `restartEmailServiceAfterUpdate` | `true` | email-service 同步成功后是否自动重启上述计划任务；`false` = 只落盘不重启。 |
| `cachePath` | `""` | 编译缓存目录；空 = 自动 `<serverScriptsPath>\cache`。仅 `update-plugins-clear-cache` 会**显式清空**它（cache 默认在受保护列表里，普通更新不碰）。 |
| `hotReloadMode` | `"socket"` | 运行中热更新模式：`socket`（默认，socket 可用则逐个热重载；不可用时按 `requireServerStopped` 回退）\| `auto`（语义同 socket，保留兼容）\| `off`（关闭热重载，服务器运行中按 `requireServerStopped` 处理即拒绝）。 |
| `consoleSocketHost` | `"127.0.0.1"` | SA console socket 地址，需与 `server.properties` 的 `socketInputAddress` 一致；生产建议只绑 localhost。 |
| `consoleSocketPort` | `6859` | SA console socket 端口，需与 `server.properties` 的 `socketInputPort` 一致，且 `socketInput=true`。 |
| `reloadLogPath` | `""` | 脚本加载日志（热重载完成判定依据）。空 = `<serverScriptsPath>\..\logs\script-load\current.log`（即 `mdtserver\logs\script-load\current.log`）。 |
| `perScriptTimeoutSeconds` | `120` | 单个脚本热重载最长等待秒数（钳制 10–600）；超时视为该脚本失败，停止后续并退出码 3。 |
| `reloadSettleMillis` | `3000` | 出现完成标记后再等多久没有新的 `[ScriptAgent]` 日志行才算完成（钳制 500–15000），用于等依赖方级联重启写完。 |
| `socketConnectTimeoutSeconds` | `5` | console socket 连接超时秒数（钳制 2–60）。 |
| `requireServerStopped` | `true` | **回退语义**：服务器运行中但热重载不可用/关闭时是否拒绝更新（true 推荐；false = 旧行为在线更新，不推荐）。热重载可用时本项不生效；服务器未运行时也不生效。 |
| `gamePort` | `6567` | 游戏端口，用于检测服务器是否在运行（与 `server.properties` 的 `port` 一致）。 |
| `webPort` | `0` | Web 端口（可选），用于提示 Web 是否在运行、更新后需要重启；`0` = 不检查。 |
| `logDir` | `""` | 日志目录；空 = `<更新器目录>\logs`。 |

> 以 `_comment` 开头的字段只是说明文字，脚本会忽略，可以随意增删。
> JSON 是严格语法：Windows 路径必须写成双反斜杠（`E:\\mdtserver\\config\\scripts`）。
> 相对路径会按更新器目录解析，但生产环境**强烈建议全部写绝对路径**。

---

## 5. 严格镜像与保护规则

**严格镜像** = 目标目录要与仓库子目录完全一致：

- 仓库有、目标没有 → **新增**；
- 两边都有但内容不同 → **覆盖（修改）**；
- 目标有、仓库没有 → **删除**（更新前会备份，报告里会列出删除清单）；
- 内容相同 → 跳过（未变）。

> **例外：email-service 默认不是严格镜像**。邮件服务目录按「只新增/更新、不删除」处理（`emailServicePruneDeleted=false`）：
> 目标目录里仓库没有的本地文件会**保留**（报告里单独列出），避免误删人工放置的脚本/备份/密钥。
> 需要与插件一样严格镜像时，把 `config.json` 的 `emailServicePruneDeleted` 设为 `true`。

受保护规则（默认值见上表，可在 `config.json` 修改）：

| 类别 | 不复制（仓库里有也不拷） | 不删除（目标里有也保留） | 不备份 |
|---|---|---|---|
| 插件 | `data/**`、`external-cp/**`、`cache/**` | `data/**`、`external-cp/**`、`cache/**` | 同左 |
| Web | `web-data/**`、`web-config.json`、`web-config.ps1` | 同左 | 同左 |
| 邮件服务 | `config.json`、`logs/**`、`dist/**` | 同左 | 同左 |

因此：

- 生产 `data\config.conf`、`data\h2DB.db.mv.db`、`external-cp\*.zip`、`web-data\**` 永远不会被更新器覆盖或删除；
- 生产邮件服务的 `email-service\config.json`（密钥/发件配置）、`logs\`、`dist\` 永远不会被更新器覆盖或删除；
- **编译缓存 `cache\` 默认也受保护**：普通 `update-plugins` 的严格镜像不会删除 `cache\` 及其中的缓存文件；
  只有 `update-plugins-clear-cache.bat` 会**显式清空** `<cachePath>` 的内容后再镜像（适合缓存损坏/需要强制重编译，
  即使版本没变化也会执行），清空后服务器下次启动会重新编译全部脚本；
- 仓库里的 `scripts\data\config.conf`（模板）、`scripts\external-cp\.gitkeep`、`stats-web\web-config.json`
  （默认配置）、`email-service\config.example.json`（仓库里没有 `config.json`）只会被识别为「受保护、跳过」，
  不会写进生产；
- 仓库 zip 里的 `docs/`、`README.md`、`LICENSE.md`、`.gitignore`、`updater/` 一律忽略。

---

## 6. 备份与回滚

- 每次更新前备份到：`<backupPath>\plugins\<yyyyMMdd-HHmmss>\`、`<backupPath>\web\<yyyyMMdd-HHmmss>\`
  或 `<backupPath>\email\<yyyyMMdd-HHmmss>\`；元数据写在同级的 `.meta-<时间>.json`
  （记录新增/修改/删除清单、更新前版本等）。每个子目录各有自己的 `.last-version`。
- 备份范围：目标目录中**参与镜像的文件**（受保护内容不会被更新改动，因此不备份——也避免每次复制
  GB 级的 `web-data`/数据库/编译缓存）；保留最近 `keepBackups` 份（默认 5），超出删除最旧。
  注意：`cache\` 默认受保护、不在备份里，所以清缓存脚本 `-Rollback` 只恢复脚本本身，缓存需要服务器重新编译。
- 回滚：`.\update-plugins.bat -Rollback`（最近一份）或 `-RollbackTo 20261018-173000`（指定一份）。
  回滚会：把备份文件恢复回目标目录 + 删除「那次更新新增、备份里不存在」的文件；
  受保护内容不动。服务器运行中且热重载可用时，回滚后也会按同规则逐个热重载被恢复/删除的脚本；
  否则回滚后插件需手动启动服务器、Web 需重启 Web 进程。
  邮件服务回滚用 `update-email-service.bat -Rollback`（回滚成功后会自动重启计划任务，可用 `-NoRestartEmailService` 关闭）；
  `update-mainserver.bat -Rollback` 只回滚插件 scripts（不回滚 email-service）。
- 若某次更新失败（复制/校验异常），更新器会自动用刚创建的备份恢复目标目录，并在日志里说明。
- 本地版本标记：`<backupPath>\plugins\.last-version` / `<backupPath>\web\.last-version` /
  `<backupPath>\email\.last-version`（JSON，记录部署的 commit SHA、时间、变更统计）。
  删掉它会让下一次运行重新下载部署；加 `-Force` 可强制重部署。

> 手工恢复：直接把 `<backupPath>\plugins\<时间>\` 下的内容复制回 `serverScriptsPath` 即可（等价于一次回滚）。

---

## 7. 版本检查

- 每次更新前调用 GitHub API：`https://api.github.com/repos/<repo>/commits/<branch>` 取最新 commit SHA，
  与本地 `.last-version` 比较：相同则提示「已是最新」并退出（`-Force` 可跳过）。
- **版本检查失败不阻塞**：API 限流/无外网/代理没配时会打印告警，继续下载 zip 做内容比对；
  下载失败则安全中止（目标目录零改动）。
- 下载地址：`https://codeload.github.com/<repo>/zip/refs/heads/<branch>`。

---

## 8. 代理

- 命令行：`.\update-plugins.bat -ProxyPort 7897` 或 `-Proxy http://127.0.0.1:7897`；
- 配置：`config.json` 里 `"proxy": "http://127.0.0.1:7897"`；
- 环境变量：不填时自动读取 `HTTPS_PROXY` / `HTTP_PROXY`；
- 代理失败后默认自动回退直连（`proxyFallbackDirect=false` 可关闭）。

---

## 9. 停服要求（什么时候需要停服）

- **不需要停服**：服务器运行中、`server.properties` 已开启 console socket 三行配置、热重载模式非 `off`，
  且本次变更**全部是 `.kts` 脚本** → 更新器严格镜像后**逐个**热重载变更脚本（详见第 10 章）；
- **需要停服**：
  - 变更包含**非 `.kts` 文件**（`.kt` 模块库/依赖等）——库改动必须停服并重启服务器；
  - console socket 连不上 / `reloadLogPath` 脚本加载日志不存在（socket 未开启或配置不对）；
  - 显式使用 `-NoHotReload` / `hotReloadMode=off` 停服更新；
  - `update-plugins-clear-cache.bat` 必须停服（清空编译缓存后需要重启重新编译）；
- 运行中热重载不可用/关闭时回退 `requireServerStopped`：`true` = 拒绝执行（退出码 2，打印 PID/命令行与修复提示）；
  `false` = 按旧行为在线更新（不推荐，更新后需重启服务器生效）；
- 检测到服务器运行中时不再直接拒绝，而是先检查热重载是否可用（1.1 起的行为变化）；
- `-DryRun` 不改文件、不发任何 sa 命令（只做 socket 只读探测与日志路径检查）；
- Web 更新不需要关闭游戏服：`update-web.bat` 只动 Web 目录，更新后**重启 Web 进程**即可；
- **email-service 不需要停游戏服**：它不依赖游戏进程；文件更新后更新器会自动重启计划任务
  `MDT Email Service`（`-NoRestartEmailService` 可关闭；未安装任务时提示手动重启）；
- **主服/分服快速同步脚本**的停服规则与上面完全一致：只有插件 `scripts` 阶段涉及游戏服；
  `update-mainserver.bat` 里 email-service 阶段不要求停服，分服 `update-subserver.bat` 只有插件阶段。

---

## 10. 运行中热更新（console socket + 脚本加载日志 + say 广播）

### 10.1 原理

- 服务器运行中不能直接覆盖脚本文件让它立刻生效。SA 支持 **console socket 输入**
  （生产 `mdtserver\config\server.properties` 三行：`socketInput=true`、`socketInputPort=6859`、
  `socketInputAddress=localhost`），因此更新器通过 socket 发送官方 `sa` 命令
  （`sa unload <id>` / `sa load <id>` / `sa scan`），让运行中的服务器自己重载脚本。
- **顺序很重要**：被严格镜像删除的脚本，必须**先在文件还存在时 `sa unload`，再让镜像删文件**；
  实测对「源文件已不存在」的脚本发 `sa unload` 会让 SA 事务卡住 console socket
  （日志写了停止/卸载，但后续命令不再被处理，游戏线程仍活着）。所以更新器分两阶段：
  镜像前逐个 unload 将被删除的脚本 → 严格镜像 → 镜像后逐个 load 变更/新增脚本。
- `sa` 命令在 console socket 上**通常没有回显**，不能靠 socket 输出判断完成。可靠信号是 SA 的脚本加载日志
  `<serverScriptsPath>\..\logs\script-load\current.log`（可用 `reloadLogPath` 覆盖）：
  SA 每步都会追加带时间戳的 `[ScriptAgent]` 行——
  `编译脚本 <id>` → `编译脚本完成 <id> (耗时 x.x s)` → `停止脚本 <id>` → `卸载脚本 <id>` →
  `加载脚本 <id>` → `启用脚本 <id>`；级联重启的依赖方也会继续写这些行。
  （真实日志里 unload 行形如 `卸载脚本 <id>: `，id 后面带冒号；匹配已兼容中英文冒号/标点。）
- **完成判定** = 日志里出现目标 id 的 `启用脚本`（load）或 `卸载脚本`（unload）之后，再等
  `reloadSettleMillis`（默认 3000ms）内**没有新的 `[ScriptAgent]` 行**（等依赖方级联重启写完）。
- **必须一个一个来**：每发一条命令，都要等上一个脚本「重编译 + 级联重启」全部完成，才发下一条。
  一口气全发会造成级联重启互相踩踏，出现奇怪的加载顺序问题（用户实测）。
- **服务端广播（say）**：通过同一个 console socket 发 `say <文本>`（游戏内显示 `Server: ...`）。
  仅在「服务器运行中 + 确实发生热重载 + 非 DryRun + `hotReloadAnnounce=true`」时，开始前发一条提醒，
  结束后发「成功/失败摘要」；服务器未运行 / `-NoHotReload` / 无变更时不发；
  （有 removed 时开始广播复用阶段一的 socket 会话；没有 removed 时在镜像前单独连接一次发开始广播；
  结束广播复用阶段二的会话——都是同一种 console socket 通道。）
  best-effort，发送失败只记 WARN，不影响更新退出码。
- **socket 写法（关键）**：命令一律以**原始 UTF-8 字节 + `\n`** 写入 NetworkStream（等价于
  `.agents/sa-cmd.ps1` 的验证写法），**不用 StreamWriter/WriteLine**（`\r\n`/BOM 会破坏 SA 的行解析，
  实测会导致 server console socket 卡死、后续命令不再执行）。探测/单独 say 等短会话用完立即在 finally 中关闭。

### 10.2 执行流程（`update-plugins`，服务器运行中）

0. **开始广播**（`hotReloadAnnounce=true` 默认）：确认本次确实会热重载（服务器运行中、模式非 off、
   确有 `.kts` 变更、非 `-DryRun`）后，在发任何 `sa unload`/镜像之前先发
   `say 服务器插件即将热重载更新，请暂时避免使用菜单、技能等功能。`；
1. 严格镜像分析（已有流程，`-DryRun` 到此打印计划后结束，不发任何命令）；
2. 若 planned 变更包含**非 `.kts`** 文件（`.kt` 模块库等）→ **改动文件前中止**，退出码 2（库改动必须停服）；
3. 热重载模式为 `off` → 中止退出码 2（提示停服或去掉 `-NoHotReload`）；
4. console socket 连不上 / `reloadLogPath` 不存在 → **改动文件前中止**，退出码 2
   （提示检查 `server.properties` 三行配置，或先 `-NoHotReload` 停服更新）；
5. **阶段一（镜像前，文件还在时）**：对将被严格镜像删除的 removed 脚本逐个 `sa unload <id>`：
   - 每条命令前记录脚本加载日志当前长度，命令发出后轮询日志新增内容；
   - 出现完成标记（`卸载脚本 <id>`）后等 `reloadSettleMillis` 静默；单脚本超时
     `perScriptTimeoutSeconds`（默认 120 秒）→ 该脚本失败，**停止后续**，**中止更新、退出码 2、目标目录零改动**；
   - 日志出现该 id 的失败行（`编译脚本失败` / `出错` / `Exception` 等）→ 同上退出码 2；
   - 兜底：命令发出后 8 秒内完全没有 `[ScriptAgent]` 行 → 打印警告并按 no-op 继续；
6. **备份 + 应用严格镜像**（新增/覆盖 + 删除仓库中已不存在的文件，已有流程，复制后哈希校验）；
   若这一步失败：自动用备份恢复目标目录；若阶段一已 unload 过脚本，会 best-effort 重新 load 它们，避免
   「文件在但脚本未加载」的状态；
7. **阶段二（镜像后）**：只要本次有 **added 或 removed**，先发**一次** `sa scan`、等 1.5 秒
   （发现新脚本 + 清理已删除脚本的注册表；两者都有时也只发一次）；再按 changed → added 逐个 `sa load <id>`，
   同样逐脚本等日志完成标记 + 静默；单脚本超时/失败 → **停止后续，退出码 3（文件已更新）**；
8. 全部完成后发 `sa listFailed`，读取 2~3 秒回显；若出现失败脚本行（形如 `[Failed] <id>`）→ 退出码 3；
   否则退出码 0（`sa listFailed` 无失败时通常只有 autosave 等无关回显，会被忽略）；
9. **结束广播**（本次有广播时）：成功发 `say 插件更新完成，可以正常使用了。`；
   失败/部分失败发 `say 插件更新完成，但有 N 个脚本重载失败：<id1>, <id2>, …；相关功能可能暂时不可用，
   管理员会尽快处理。`（失败 id 取「失败脚本 + listFailed 失败 id」去重、最多列 5 个，剩余用「等 N 个」，
   总长控制在 ~200 字以内）；若在阶段一就失败中止（退出码 2、尚未镜像），发
   `say 插件更新中止，请稍后再试；部分功能可能暂时不可用。`。
- **服务器未运行**：不连 socket、不发 say，按停服流程更新，退出码 0，日志提示「下次启动服务器时加载」。
- **为什么必须「先 unload 再删文件」**：见 10.1；工具不会制造「源文件已删再 unload」的状态。

### 10.3 前置条件与首次部署（按实际情况，不一定需要重启）

**先判断 console socket 是否已经在用**：如果生产 `server.properties` 已配置 `socketInput=true` /
`socketInputPort=6859` / `socketInputAddress=localhost`，且 **6859 端口已在监听、`current.log` 在持续写入**
→ 首次部署**不需要关闭或重启服务器**：

1. 把新版 `updater\` 整目录复制到生产主机（updater 是独立 PowerShell 进程，与游戏服无关，可随时替换；
   保留自己的 `config.json` 与 `logs\`）；
2. （建议）`.\update-plugins.bat -DryRun` 预览将逐个执行哪些 sa 命令（不发命令、不改文件）；
3. 直接运行 `.\update-plugins.bat`：更新器会严格镜像后通过 socket 逐个热重载，无需停服/重启。

**如果 socketInput 还没开启**（6859 不在监听 / `current.log` 不存在）→ 需要启用端口并**重启一次**：

1. 复制新版 `updater\`；
2. 编辑 `mdtserver\config\server.properties` 增加三行（**只绑本机，不要暴露公网**；端口与
   `config.json` 的 `consoleSocketPort` 一致，默认 6859）：

   ```properties
   socketInput=true
   socketInputPort=6859
   socketInputAddress=localhost
   ```

3. 关闭服务器 → 用新版 updater 跑一次 `.\update-plugins.bat`（或 `-NoHotReload` 走停服更新）→ 启动服务器；
   之后 `current.log` 会持续写入，后续即可**运行中更新**（直接 `.\update-plugins.bat`）；
4. 暂时不想开端口：用 `-NoHotReload`（要求服务器已关闭）也能正常更新，只是每次都要停服。

### 10.4 相关配置与参数（新增）

| 字段/参数 | 默认 | 说明 |
|---|---|---|
| `hotReloadMode` / `-HotReload <mode>` | `"socket"` | `socket`：socket 可用则逐个热重载；`auto`：语义同 socket；`off`：关闭热重载（运行中按 `requireServerStopped` 拒绝） |
| `consoleSocketHost` | `"127.0.0.1"` | 与 `server.properties` 的 `socketInputAddress` 一致 |
| `consoleSocketPort` | `6859` | 与 `socketInputPort` 一致，且 `socketInput=true` |
| `reloadLogPath` | `""` | 空 = `<serverScriptsPath>\..\logs\script-load\current.log` |
| `perScriptTimeoutSeconds` | `120` | 单脚本最长等待（10–600）；超时退出码 3 |
| `reloadSettleMillis` | `3000` | 完成标记后的静默窗口（500–15000），等级联重启 |
| `socketConnectTimeoutSeconds` | `5` | socket 连接超时（2–60） |
| `hotReloadAnnounce` | `true` | 运行中热重载前后通过 `say` 广播提醒玩家（开始/成功/失败摘要）；`false` 完全不发。best-effort，发送失败只记 WARN 不影响退出码 |
| `-NoHotReload` | — | 关闭热重载（等同 `-HotReload off`） |
| `-DryRun` | — | 只打印计划（含将逐个执行的 sa 命令与是否会 say 广播），不发命令、不改文件 |

### 10.5 退出码 2 / 3：阶段一卸载失败 / 阶段二热重载失败

- **退出码 2（阶段一：镜像前 unload 失败/超时）**：还没开始严格镜像，**目标目录零改动**；
  但已执行的 `sa unload` 可能已生效（对应脚本在运行中的服务器里被卸载），若开启广播会 best-effort 发
  `say 插件更新中止，请稍后再试；部分功能可能暂时不可用。`。处理：
  查看 `script-load\current.log` 与更新器日志；直接重跑本更新器（会重新比对并继续），或重启服务器后再更新。
- **退出码 3（阶段二：镜像后 load 失败/超时/listFailed 非空）**：**目标目录已经是新版文件**，
  但运行中的服务器没有成功加载全部脚本（编译失败/超时/listFailed 非空）；
  - 不需要重跑更新器：下一次运行内容未变时不会重复改文件；
  - 处理：看 `mdtserver\logs\script-load\current.log` 的 `[ScriptAgent]` 失败行；在服务器控制台执行
    `sa listFailed` / `sa load <id>`；修复后仍不行的重启服务器；
  - 退出码 3 时更新器**不再发送后续脚本命令**（避免踩踏），已完成的脚本会列在控制台/日志里；
    若开启广播，会 best-effort 发失败摘要 `say`（最多列 5 个失败 id）。

### 10.6 限制与注意事项

- **非 `.kts` 文件（`.kt` 模块库等）改动必须停服**：更新器会在改动文件前拒绝（退出码 2）；
  运行中的服务器无法热加载库文件，只能重启；
- **运行中删除脚本必须「先 unload 再删文件」**：更新器已内置该顺序（镜像前 unload → 镜像删除）；
  请勿手工先删 `config\scripts\` 里的 `.kts` 文件、再用控制台/工具去 unload（会让 SA 事务卡住 console socket）；
- **删除脚本后必须发一次 `sa scan` 清理注册表**：否则 `scriptdiag count` / 脚本注册项可能残留被删脚本的旧条目；
  更新器在 removed>0 或 added>0 时会在镜像后自动发一次（两者都有也只发一次）；
- 单个脚本 `sa load` 的**级联依赖由 SA 自动处理**（依赖方会重启），更新器会等日志静默确认级联完成；
  但**涉及 lib / 模块库的改动仍必须重启服务器**，热重载不覆盖；
- 热重载成功 = 日志确认重载完成 + `sa listFailed` 无失败；不代表业务逻辑无 bug，仍建议看
  `script-load\last-error.log`；
- 服务器运行中更新仍有少量风险（脚本编译失败、级联重启期间玩家短暂体验波动）；重要改动建议维护窗口停服更新；
- `requireServerStopped` 仅在热重载不可用/关闭时作为回退，优先级见第 9 章。

### 10.7 安全

- console socket 等价于服务器控制台：**只应绑定 localhost**（`socketInputAddress=localhost`），
  不要设 `0.0.0.0`、不要端口转发到公网；
- 更新器只发送 `sa load` / `sa unload` / `sa scan` / `sa listFailed` / `say <固定提示文本>` 这几类命令，
  不做任意命令执行；
- 更新器只读取本机 `reloadLogPath` 日志，不额外联网（除原有的 GitHub 下载/版本检查外）。

---

## 11. 离线 / 测试用法

- `-SourceZip <仓库zip>`：直接用本地 zip（结构同 GitHub zip：根目录含 `scripts/`、`stats-web/`、`email-service/`），
  跳过下载和远端版本检查，适合内网/断网生产、以及自动化测试。
- `-KeepTemp`：保留解压后的临时目录，便于排障（默认成功后/失败后都会清理系统临时目录）。
- 先 `-DryRun` 是零风险步骤：只下载+比对+打印清单。

---

## 12. 更新器自身升级 / 同步范围

- 更新器是**独立小项目**：升级时整目录复制新版本即可（保留自己的 `config.json` 与 `logs/`）。
  新增的主服/分服/邮件服务入口（`update-mainserver.*`、`update-subserver.*`、`update-email-service.*`）
  都在 `updater/` 目录内，整目录复制即一起升级。
- **同步范围说明**：`updater/` 已纳入插件仓库的推送范围（与 `docs/`、`scripts/`、`stats-web/` 同一个仓库），
  后续可从仓库根目录的 `updater/` 获取新版更新器（`scripts/`、`stats-web/`、`email-service/` 仍由本更新器负责部署）。
  生产机器可以直接从仓库 zip 的 `updater/` 目录复制新版过来。
- 更新器自身不依赖仓库里的其他内容；它只认 `scripts/`、`stats-web/` 与 `email-service/` 三个子目录。

---

## 13. 退出码

| 退出码 | 含义 |
|---|---|
| `0` | 成功 / 已是最新 / `-DryRun` 预览完成 / 按要求未做修改 |
| `1` | 失败（下载、校验、备份、复制、清理等出错；目标目录未被破坏或已自动恢复） |
| `2` | 拒绝执行（服务器运行中且热重载不可用/关闭、含 `.kt`/非 `.kts` 变更、console socket 或脚本加载日志不可用、**镜像前 unload 将被删除脚本失败/超时**、配置缺失、另一个更新进程在跑、备份不存在等；目标目录未改动） |
| `3` | **文件已更新，但运行中热重载失败/超时**（编译失败、单脚本超时、`sa listFailed` 非空）；可 `sa load` 或重启恢复，无需重跑更新器 |

> `update-mainserver` 是两阶段（插件 + email-service），退出码按 **`1` > `2` > `3` > `0`** 聚合：
> 阶段 1 失败（1/2）时会跳过阶段 2；阶段 1 为 3（文件已更新但热重载失败）时仍会继续 email-service 阶段。

---

## 14. FAQ

**Q：服务器运行中可以直接更新吗？**
A：可以（1.1 起）：先在 `server.properties` 开启 `socketInput=true` / `socketInputPort=6859` /
`socketInputAddress=localhost`，重启一次服务器后，直接跑 `.\update-plugins.bat` 即可严格镜像 + 逐个热重载。
首次部署需要停服更新一次（详见第 10 章）。含 `.kt` 模块库改动仍必须停服。

**Q：提示「运行中热重载不可用：console socket 不可达 / 脚本加载日志不存在」怎么办？**
A：检查 `server.properties` 三行 socket 配置是否已改、是否重启过服务器；确认 `config.json` 的
`consoleSocketHost/consoleSocketPort/reloadLogPath` 与实际一致；临时可用 `.\update-plugins.bat -NoHotReload`
先停服更新。只预览不改文件可用 `-DryRun`。

**Q：更新完退出码 3（热重载失败/超时）怎么办？**
A：文件已经是最新版，只是运行中的服务器没加载成功。看 `mdtserver\logs\script-load\current.log` 的
`[ScriptAgent]` 失败行和更新器打印的失败脚本；在服务器控制台执行 `sa listFailed` / `sa load <id>`，
修复后仍不行就重启服务器。不需要重跑更新器。

**Q：为什么更新器要一个一个热重载？**
A：`sa load` 会触发该脚本依赖方级联重启；一口气发多条命令会让级联重启互相踩踏，出现奇怪的加载顺序问题。
更新器每条命令都会等脚本加载日志出现完成标记并静默 `reloadSettleMillis` 后才发下一条。

**Q：为什么删除脚本要先 `sa unload` 再删文件？顺序反了会怎样？**
A：实测对「源文件已不存在」的脚本发 `sa unload` 会让 SA 事务卡住 console socket（日志写了停止/卸载，
但后续命令不再被处理）。所以更新器在**严格镜像之前**、文件还在时就逐个 unload 将被删除的脚本，再镜像删文件；
不会制造「先删文件后 unload」的状态。如果阶段一 unload 失败，会中止（退出码 2，目标零改动）。

**Q：服务器明明关了还是报运行中？**
A：检查 `config.json` 的 `gamePort` 是否与生产 `server.properties` 的 `port` 一致；另外确认没有别的程序占用该端口。

**Q：更新后编译缓存会没吗？什么时候需要清缓存？**
A：普通 `update-plugins` **不会动** `cache\`（它默认在 `protectedServerDirs` 里）。需要强制重新编译
（缓存损坏/历史旧字节码/版本没变化也想重编）时用 `update-plugins-clear-cache.bat`，它会先清空缓存再严格镜像。

**Q：Web 更新完没变化？**
A：需要重启 Web 进程（关闭 `start-web` 窗口 → 重新运行 `start-web.bat`）。静态页面刷新即可，但
`server/`、`web-config.json` 等改动必须重启 Web。

**Q：只想更新 Web，不想动插件？**
A：直接运行 `update-web.bat`，它只处理 `statsWebPath`，不碰游戏服脚本。

**Q：版本检查报错/被 GitHub 限流？**
A：不影响更新：更新器会告警后继续下载 zip 做内容比对。若下载也失败，目标目录不会改动。

**Q：更新器把生产的 `data\config.conf` 覆盖了吗？**
A：不会。`data` 在 `protectedServerDirs` 里，既不复制也不删除；仓库里的模板 `scripts\data\config.conf` 会被跳过。

**Q：`web-config.json` 会被仓库里的默认配置覆盖吗？**
A：不会，它在 `protectedWebFiles` 里。生产的管理员密码/端口等配置不会被更新器改动。

**Q：想回滚到上一次更新前？**
A：`.\update-plugins.bat -Rollback`（或 `-RollbackTo <备份目录名>`）。服务器运行中且热重载可用时，
回滚恢复文件后也会逐个热重载对应脚本；否则回滚后需手动启动服务器（Web 需重启 Web 进程）。

**Q：主服和分服分别用哪个脚本、哪个分支？**
A：主服 MDT DO 用 `update-mainserver.bat`（默认分支 `main`，同步 plugins + email-service）；
分服 MDC DO 用 `update-subserver.bat`（默认分支 `mdc-do`，只同步 plugins）。
分支由 Lead 维护，更新器只下载 zip、不切分支；分服用 `-Branch` 可临时覆盖，但**不要**长期指向 `main`
（会把账号/邮箱系统带过去）。详见 §2.1。

**Q：分服会不会被带上账号/账号系统？**
A：不会。分服脚本 `update-subserver.bat` 只部署仓库 `scripts/**`，且默认从 `mdc-do` 分支下载
（该分支保持原基线、不含账号系统）；它也不同步 `email-service/`，不部署任何邮件/账号服务。

**Q：email-service 更新后要做什么？`config.json` 会被覆盖吗？**
A：`config.json` 在 `protectedEmailFiles` 里（默认），**永远不会**被仓库内容覆盖/删除，
`logs\`、`dist\` 同样受保护。更新成功后更新器会尝试**自动重启计划任务** `MDT Email Service`
（`-NoRestartEmailService` 可关闭）；未安装任务时需手动重启 `email-service\start-email-service.bat`。

**Q：email-service 目录里我自己放的文件会被删吗？**
A：默认不会：邮件服务是「只新增/更新、不删除」（`emailServicePruneDeleted=false`），
目标目录里仓库没有的文件会保留并在报告里列出。想让它像插件一样严格镜像时，把该配置设为 `true`。

**Q：主服脚本报「阶段 1/2 未成功，跳过 email-service 阶段」？**
A：说明插件 `scripts` 阶段失败/被拒绝（退出码 1/2），属于安全保护：脚本没更新好时不继续改邮件服务。
先按日志修复插件阶段（例如退出码 2 多为服务器运行中但热重载不可用 / 含 `.kt` 变更需停服；
退出码 1 多为下载/校验/复制失败），再重跑即可。

**Q：双击 bat 窗口一闪而过？**
A：带参数运行时默认不暂停。双击（无参数）运行时会 `pause` 等回车；也可以在命令行里运行看完整输出。

**Q：中文乱码？**
A：`.ps1` 必须保存为 **UTF-8 with BOM**，所有 `.bat` 已使用 `chcp 65001` 切到 UTF-8 控制台；
不要用 ANSI 重新保存脚本。

**Q：可以放到计划任务里自动跑吗？**
A：可以（设置环境变量 `MDT_UPDATER_NO_PAUSE=1` 避免 pause 卡住）。服务器未被占用/未运行时的更新最安全；
服务器运行中也可用 socket 热重载，但建议先人工 `-DryRun` 确认计划。`-DryRun` 可以安全地自动跑。

---

## 15. 已验证（开发机）

- **主服/分服/email-service 快速同步（2026-11-02，开发机临时目录演练 95 项断言全部通过）**：
  Windows PowerShell 5.1 执行、PowerShell 7 编排；临时伪造仓库 zip + 临时目标目录（不碰生产、不碰开发服
  `config\scripts`）。覆盖：
  - 主服 `update-mainserver`：`-DryRun` 零改动；正式更新两个阶段；插件严格镜像（新增/覆盖/删除）；
    `data/`、`external-cp/`、`cache/` 保护（哈希不变、模板 `scripts\data\config.conf` 不覆盖生产）；
    email-service 更新 `server.ps1`、新增仓库文件；**生产 `config.json` 哈希不变、`logs`/`dist` 保留**；
    默认「只增改不删」保留本地额外文件；`emailServicePruneDeleted=true` 时严格镜像删除；两阶段各自
    备份（`backup\plugins`、`backup\email`）与 `.last-version`；备份不含受保护内容；`-NoEmailService` /
    `-NoScripts` 只跑单阶段；`-RollbackTo` 恢复被删脚本/文件并删除该次新增文件、受保护内容不动；
  - 分服 `update-subserver`：`-DryRun` 零改动；默认分支 `mdc-do`（日志核对）、`-Branch main` 可覆盖；
    严格镜像/保护目录同插件；不同步 email-service；
  - 合并退出码：配置缺失/目标目录不存在/热重载不可用且服务器运行中 → 退出码 2 且目标零改动；
    `-NoScripts + -Rollback` → 退出码 2；`-NoHotReload`/热重载参数正确透传给插件阶段；
  - `.bat` 包装（cmd 调用）三个新入口退出码 0；`.ps1` 全部 UTF-8 BOM、`.bat` 纯 ASCII；
    现有 `update-plugins.ps1` 回归可用（含 `-NoHotReload`/`-DryRun`/`-SourceZip`）。
  - **真实 GitHub DryRun（2026-11-02）**：`update-mainserver.ps1 -DryRun` 从 `main` 真实下载成功（远端
    `b85a07c`，scripts 220 文件/212 个 .kt·.kts、email-service 9 文件），两阶段预览退出码 0；
    `update-subserver.ps1 -DryRun` 从 `mdc-do` 真实下载成功、日志记录分支 `mdc-do`（当前两分支同一提交）。
  - **未实测**：真实生产路径 `E:\...` 的正式更新、代理下载、真实邮件服务进程重启、真实运行中
    socket 热重载（本次只验证参数透传与拒绝路径；热重载机制本身由 2026-11-01 的 125/79/60/18 项断言覆盖）。
    备注：主服 `main` 当前尚未合并账号/邮箱系统，Lead 合并后**无需改脚本**，直接 `update-mainserver.bat` 即同步新分支内容。
- **旧功能回归**（2026-10-18）：`-DryRun` 预览、严格镜像更新（新增/覆盖/删除）、保护目录/文件不被改动、
  备份生成与保留份数、`-Rollback`/`-RollbackTo`、`-NoBackup`、`-SourceZip` 离线模式、代理下载、
  非法仓库/分支失败不破坏目标、运行中服务器被正确拒绝执行；
- **运行中热重载（console socket + 脚本加载日志）自动化测试 125 项断言全部通过**（2026-11-01，Windows 11 +
  Windows PowerShell 5.1 + PowerShell 7 mock，临时目录 + 假 TCP console socket + 假 `script-load/current.log`，
  不改开发服文件、不对真实服务器发命令）：
  - 成功路径（含 v2 回归修复）：`sa unload`（镜像前，removed）→ 严格镜像 → `sa scan` → `sa load`
    （changed/added）→ `sa listFailed` 的**逐个**顺序与等待间隔（scan 后 1.5s、完成标记后静默
    `reloadSettleMillis` 才发下一条）、级联依赖日志等待、退出码 0；用 witness 断言了
    **删除脚本 unload 时文件还在**、**changed/added load 时文件已是新内容**；卸载完成标记兼容真实日志的
    `卸载脚本 <id>: `（带冒号）；`-Rollback` 也按「先 unload 将删脚本 → 恢复 → 再 load 恢复脚本」逐个热重载；
  - socket 协议 v2 回归（79 项断言通过）：命令字节严格等于 UTF-8 文本 + `\n`（无 `\r`、无 BOM）；
    成功/失败/中止/DryRun/removed-only/changed-only 各场景连接 open/close 全部配平（3/3、2/2、1/1，
    含「无 removed 时单独 say 会话」），无残留连接；say 位置/三种文案/去重截断/开关等既有断言全部回归。
  - say 服务端广播回归（60 项断言通过，含核心热重载路径）：`say`(开始) 在第一个 unload/scan/load 之前、
    `say`(成功/失败/中止) 在 listFailed 之后；成功/失败（含失败 id）/中止三种文案；失败 id 去重 +
    最多列 5 个 + 「等 N 个」截断（≤200 字）；`hotReloadAnnounce=false` 完全不发；DryRun/服务器未运行/
    含 `.kt` 拒绝/无变更时不发；say 发送失败只 WARN 且不影响退出码；removed-only/changed-only 命令顺序回归。
  - 删除脚本后的 `sa scan` 回归（18 项断言通过）：removed-only 时镜像后会发且只发一次 `sa scan`、
    both(removed+added) 时也只发一次、changed-only 时完全不发；命令顺序为 unload → scan → loads → listFailed；
  - 拒绝路径（退出码 2、目标零改动、零命令）：含 `.kt` 模块库变更、console socket 不可达、
    `reloadLogPath` 不存在、`hotReloadMode=off`、`-NoHotReload`；
  - 阶段一失败（退出码 2、目标零改动、未生成备份、只发过 unload）：卸载将被删除脚本失败/超时；
  - 阶段二失败（退出码 3、文件已更新、停止后续）：日志出现编译失败行、单脚本等待超时、`sa listFailed` 非空；
  - 其他：`-DryRun` 发命令=0 且打印新顺序计划；无任何 `[ScriptAgent]` 行时 8 秒兜底按 no-op 继续（退出码 0）；
    `auto` 不可用 + `requireServerStopped=false` 回退旧行为；CLI `-HotReload socket` 覆盖 config；
    服务器未运行时不连 socket、正常镜像退出码 0；
- Web 侧：`web-config.json`/`web-config.ps1`/`web-data/` 保护、严格镜像、备份与回滚；
- 清缓存脚本：显式清空 `cache` 内容后完成严格镜像；普通更新不删缓存（受保护）；缓存不备份、回滚只恢复脚本。

> 生产首次使用前，建议先用 `-DryRun` 对照确认删除清单里没有你手工放进 `config\scripts\` 的自定义文件
> （严格镜像会删除仓库中没有的文件；如有需要请把它们加入 `protectedServerFiles`）。
