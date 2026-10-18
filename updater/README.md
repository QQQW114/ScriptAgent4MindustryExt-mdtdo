# MDT 更新器（mdtdo updater）

一个**独立的小项目**：放在 Windows 主机任意目录（例如 `E:\mdt-updater\`）即可使用，通过 `config.json`
里配置的**绝对路径**，一条命令把 GitHub 插件仓库里的最新内容**严格镜像**同步到生产环境：

- 服务器插件脚本：仓库 `scripts/**` → `serverScriptsPath`（要求先关闭服务器）；
- 服务器门户 Web：仓库 `stats-web/**` → `statsWebPath`（更新后需重启 Web 进程）；
- 清缓存变体：先清空脚本编译缓存，再严格镜像更新插件。

零 git 依赖、自带代理支持、出问题可一键回滚，下载/校验失败**绝不改动目标目录**。

```
updater/
├─ config.json                          # 唯一需要改的文件（绝对路径 + 仓库/代理/保护规则）
├─ UpdateCommon.ps1                     # 公共实现（三个入口脚本共用；一般不用改）
├─ update-plugins.ps1 / .bat            # ① 严格镜像更新服务器插件脚本（须停服）
├─ update-web.ps1 / .bat                # ② 严格镜像更新 Web 门户（不碰游戏服）
├─ update-plugins-clear-cache.ps1 / .bat# ③ 清空编译缓存 + 严格镜像更新插件（须停服）
├─ README.md                            # 本文件
└─ logs/                                # 运行时自动创建：update-<plugins|web>-<时间>.log
```

> 适用环境：Windows + Windows PowerShell 5.1 及以上（`powershell.exe`）；不需要管理员权限
> （除非目标目录/备份目录本身需要提权才能写）。`.ps1` 文件必须保持 **UTF-8 with BOM** 编码。

---

## 1. 快速开始（生产部署）

1. **整目录复制**：把整个 `updater\` 目录复制到生产主机任意位置，例如 `E:\mdt-updater\`。
   （可以复制到任意盘符/目录，脚本内部全部使用 `config.json` 里的绝对路径，不依赖相对布局。）
2. **改 `config.json`**：至少填好这三个绝对路径：
   - `serverScriptsPath`：生产服务器脚本目录，例如 `E:\\mdtserver\\config\\scripts`；
   - `statsWebPath`：生产 Web 门户目录，例如 `E:\\stats-web`；
   - `backupPath`：备份根目录，例如 `E:\\mdt-updater-backup`（会自动创建 `plugins\` / `web\` 子目录）。
3. **关闭服务器**：插件更新要求服务器已关闭（更新器会检测游戏端口和游戏 java 进程，运行中会直接拒绝执行）。
4. **更新插件**：双击 `update-plugins.bat`，或在命令行运行 `.\update-plugins.bat`。
   建议第一次先跑 `.\update-plugins.bat -DryRun` 看会新增/修改/删除哪些文件。
5. **启动服务器验证**：运行 `mdtserver\启动服务器.bat`，检查 `config\logs\script-load\last-error.log`
   为空、脚本加载数量正常。
6. **只想更新 Web**：随时运行 `.\update-web.bat`（**不需要**关闭游戏服），更新完**重启 Web 进程**
   （关闭 `start-web` 窗口后重新运行 `start-web.bat`）。

> 生产升级推荐顺序：`-DryRun` 预览 → 关闭服务器 → `update-plugins.bat` → 启动服务器验证；
> Web 可以独立更新，`update-web.bat` 后重启 Web 即可。

---

## 2. 三个脚本

| 脚本 | 作用 | 需要停游戏服 | 受保护内容（默认） | 更新后要做什么 |
|---|---|---|---|---|
| `update-plugins.bat` | 严格镜像 `scripts/**` → `serverScriptsPath` | **是** | `data\`、`external-cp\`、`cache\`（不碰不备份不删） | 手动启动服务器 |
| `update-web.bat` | 严格镜像 `stats-web/**` → `statsWebPath` | 否 | `web-data\`、`web-config.json`、`web-config.ps1` | **重启 Web 进程** |
| `update-plugins-clear-cache.bat` | **显式清空**编译缓存（默认 `<serverScriptsPath>\cache`）后再严格镜像 `scripts/**` | **是** | 同 `update-plugins`（但会先清空 cache 内容） | 手动启动服务器（首次会全量重新编译，较慢属正常） |

三个 `.bat` 只是 `powershell.exe -File <同名>.ps1 %*` 的包装（已 `chcp 65001`，透传全部参数）。
命令行运行 `.ps1` 也可以，例如：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\update-plugins.ps1 -DryRun
```

> 双击运行时窗口会暂停等待回车，方便看结果；带参数运行或设置环境变量 `MDT_UPDATER_NO_PAUSE=1`
> 时不暂停（便于计划任务/自动化调用）。

---

## 3. 参数表（三个脚本通用）

| 参数 | 说明 |
|---|---|
| `-DryRun` | 只下载、校验并显示将「新增/修改/删除」的文件清单，**不备份、不改目标、不清缓存、不写版本标记**。插件类脚本的 `-DryRun` 不检查停服（因为不会动文件）。 |
| `-Force` | 忽略「已是最新」判断，强制重新下载并执行严格镜像（用于本地被手改、版本标记异常等）。 |
| `-Proxy <url>` | 下载代理，如 `http://127.0.0.1:7897`。为空时若设置了环境变量 `HTTPS_PROXY` 会自动使用。 |
| `-ProxyPort <port>` | 本机代理端口快捷方式，等价于 `-Proxy http://127.0.0.1:<port>`。 |
| `-Config <path>` | 指定配置文件，默认用更新器目录下的 `config.json`。 |
| `-NoBackup` | 跳过备份（**不建议**）：更新失败时无法自动恢复。 |
| `-Rollback` | 回滚到最近一次更新前的备份。 |
| `-RollbackTo <ts>` | 回滚到指定备份（备份目录名，如 `20261018-173000`；也可传备份目录完整路径）。 |
| `-KeepBackups <n>` | 覆盖配置里的备份保留份数（默认 5）。 |
| `-Branch <name>` | 覆盖仓库分支（默认 `main`）。 |
| `-Repo <owner/name>` | 覆盖仓库（默认 `QQQW114/ScriptAgent4MindustryExt-mdtdo`）。 |
| `-SourceZip <path>` | 离线/测试用：直接使用本地仓库 zip（结构同 GitHub zip，根目录含 `scripts/`、`stats-web/`），跳过下载和远端版本检查。 |
| `-KeepTemp` | 保留本次下载解压的临时目录（排障用；默认无论成功失败都会清理）。 |

`-Rollback` 与 `-RollbackTo` 可以和 `-DryRun` 组合：只预览会恢复/删除哪些文件，不实际修改。
`-Rollback` 也可以和 `-Force` 组合：允许回滚到空备份（会把目标目录清空，危险）。

---

## 4. `config.json` 字段

| 字段 | 默认值 | 说明 |
|---|---|---|
| `repo` | `QQQW114/ScriptAgent4MindustryExt-mdtdo` | 仓库（owner/name）。仓库根目录含 `scripts/`、`stats-web/`；`docs/`、`README.md` 等会被忽略。 |
| `branch` | `main` | 分支。 |
| `proxy` | `""` | 下载代理，如 `http://127.0.0.1:7897`；空 = 直连（仍会读取环境变量 `HTTPS_PROXY`）。 |
| `proxyPort` | `0` | 本机代理端口快捷方式；与 `proxy` 同时存在时以 `proxy` 为准。 |
| `proxyFallbackDirect` | `true` | 代理失败后是否自动回退直连。 |
| `serverScriptsPath` | `""`（示例 `E:\mdtserver\config\scripts`） | **必填**：生产服务器脚本目录绝对路径。 |
| `statsWebPath` | `""`（示例 `E:\stats-web`） | **必填**（用 update-web 时）：生产 Web 目录绝对路径。 |
| `backupPath` | `""`（示例 `E:\mdt-updater-backup`） | **必填**：备份根目录，自动创建 `<backupPath>\plugins\`、`<backupPath>\web\`。 |
| `keepBackups` | `5` | 每类备份保留份数，超出删最旧（默认 1–50）。 |
| `protectedServerDirs` | `["data","external-cp","cache"]` | 服务器脚本目录中受保护的子目录（相对路径）：不复制、不备份、不删除。默认含 `cache`（编译缓存），普通更新不会动它。 |
| `protectedServerFiles` | `[]` | 服务器脚本目录中受保护的文件（相对路径）。 |
| `protectedWebDirs` | `["web-data"]` | Web 目录中受保护的子目录。 |
| `protectedWebFiles` | `["web-config.json","web-config.ps1"]` | Web 目录中受保护的文件（仓库里的默认配置绝不能覆盖生产配置）。 |
| `cachePath` | `""` | 编译缓存目录；空 = 自动 `<serverScriptsPath>\cache`。仅 `update-plugins-clear-cache` 会**显式清空**它（cache 默认在受保护列表里，普通更新不碰）。 |
| `requireServerStopped` | `true` | 插件更新前是否强制检查服务器已关闭。 |
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

受保护规则（默认值见上表，可在 `config.json` 修改）：

| 类别 | 不复制（仓库里有也不拷） | 不删除（目标里有也保留） | 不备份 |
|---|---|---|---|
| 插件 | `data/**`、`external-cp/**`、`cache/**` | `data/**`、`external-cp/**`、`cache/**` | 同左 |
| Web | `web-data/**`、`web-config.json`、`web-config.ps1` | 同左 | 同左 |

因此：

- 生产 `data\config.conf`、`data\h2DB.db.mv.db`、`external-cp\*.zip`、`web-data\**` 永远不会被更新器覆盖或删除；
- **编译缓存 `cache\` 默认也受保护**：普通 `update-plugins` 的严格镜像不会删除 `cache\` 及其中的缓存文件；
  只有 `update-plugins-clear-cache.bat` 会**显式清空** `<cachePath>` 的内容后再镜像（适合缓存损坏/需要强制重编译，
  即使版本没变化也会执行），清空后服务器下次启动会重新编译全部脚本；
- 仓库里的 `scripts\data\config.conf`（模板）、`scripts\external-cp\.gitkeep`、`stats-web\web-config.json`
  （默认配置）只会被识别为「受保护、跳过」，不会写进生产；
- 仓库 zip 里的 `docs/`、`README.md`、`LICENSE.md`、`.gitignore`、`updater/` 一律忽略。

---

## 6. 备份与回滚

- 每次更新前备份到：`<backupPath>\plugins\<yyyyMMdd-HHmmss>\` 或 `<backupPath>\web\<yyyyMMdd-HHmmss>\`；
  元数据写在同级的 `.meta-<时间>.json`（记录新增/修改/删除清单、更新前版本等）。
- 备份范围：目标目录中**参与镜像的文件**（受保护内容不会被更新改动，因此不备份——也避免每次复制
  GB 级的 `web-data`/数据库/编译缓存）；保留最近 `keepBackups` 份（默认 5），超出删除最旧。
  注意：`cache\` 默认受保护、不在备份里，所以清缓存脚本 `-Rollback` 只恢复脚本本身，缓存需要服务器重新编译。
- 回滚：`.\update-plugins.bat -Rollback`（最近一份）或 `-RollbackTo 20261018-173000`（指定一份）。
  回滚会：把备份文件恢复回目标目录 + 删除「那次更新新增、备份里不存在」的文件；
  受保护内容不动。回滚后插件需手动启动服务器、Web 需重启 Web 进程。
- 若某次更新失败（复制/校验异常），更新器会自动用刚创建的备份恢复目标目录，并在日志里说明。
- 本地版本标记：`<backupPath>\plugins\.last-version` / `<backupPath>\web\.last-version`（JSON，记录部署的
  commit SHA、时间、变更统计）。删掉它会让下一次运行重新下载部署；加 `-Force` 可强制重部署。

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

## 9. 停服要求（为什么必须关闭服务器）

- 更新会删除/覆盖脚本文件，服务器运行中可能出现「改了一半」的脚本状态、编译缓存与文件不一致、
  甚至 Windows 文件占用导致复制/删除失败；
- 更新器执行前会检测 `gamePort`（默认 6567）是否有监听、是否有 `server-*.jar` / `mdtserver` 的游戏 java
  进程；**检测到运行中就直接中止（退出码 2）**，并打印 PID/命令行供核对；
- `-DryRun` 不检查停服（不会改动任何文件）；
- 确需在运行中操作时可在 `config.json` 设 `"requireServerStopped": false`，但**不推荐**。
- Web 更新不需要关闭游戏服：`update-web.bat` 只动 Web 目录，更新后**重启 Web 进程**即可。

---

## 10. 离线 / 测试用法

- `-SourceZip <仓库zip>`：直接用本地 zip（结构同 GitHub zip：根目录含 `scripts/`、`stats-web/`），
  跳过下载和远端版本检查，适合内网/断网生产、以及自动化测试。
- `-KeepTemp`：保留解压后的临时目录，便于排障（默认成功后/失败后都会清理系统临时目录）。
- 先 `-DryRun` 是零风险步骤：只下载+比对+打印清单。

---

## 11. 更新器自身升级 / 同步范围

- 更新器是**独立小项目**：升级时整目录复制新版本即可（保留自己的 `config.json` 与 `logs/`）。
- **同步范围说明**：`updater/` 已纳入插件仓库的推送范围（与 `docs/`、`scripts/`、`stats-web/` 同一个仓库），
  后续可从仓库根目录的 `updater/` 获取新版更新器（`scripts/`、`stats-web/` 仍由本更新器负责部署）。
  生产机器可以直接从仓库 zip 的 `updater/` 目录复制新版过来。
- 更新器自身不依赖仓库里的其他内容；它只认 `scripts/` 与 `stats-web/` 两个子目录。

---

## 12. 退出码

| 退出码 | 含义 |
|---|---|
| `0` | 成功 / 已是最新 / `-DryRun` 预览完成 / 按要求未做修改 |
| `1` | 失败（下载、校验、备份、复制、清理等出错；目标目录未被破坏或已自动恢复） |
| `2` | 拒绝执行（服务器仍在运行、配置缺失、另一个更新进程在跑、备份不存在等；目标目录未改动） |

---

## 13. FAQ

**Q：提示「检测到服务器仍在运行，已中止更新」怎么办？**
A：关掉服务器的启动窗口（`启动服务器.bat` / `start-server.cmd` 那个 PowerShell 窗口），确认 6567 端口无人监听
后重试。只是想预览变更可以加 `-DryRun`。

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
A：`.\update-plugins.bat -Rollback`（或 `-RollbackTo <备份目录名>`）。回滚后插件需手动启动服务器，
Web 需重启 Web 进程。

**Q：双击 bat 窗口一闪而过？**
A：带参数运行时默认不暂停。双击（无参数）运行时会 `pause` 等回车；也可以在命令行里运行看完整输出。

**Q：中文乱码？**
A：`.ps1` 必须保存为 **UTF-8 with BOM**，三个 `.bat` 已使用 `chcp 65001` 切到 UTF-8 控制台；
不要用 ANSI 重新保存脚本。

**Q：可以放到计划任务里自动跑吗？**
A：可以（设置环境变量 `MDT_UPDATER_NO_PAUSE=1` 避免 pause 卡住）。但插件更新要求服务器已关闭，
建议只在维护窗口人工执行；`-DryRun` 可以安全地自动跑。

---

## 14. 已验证（开发机）

- **自动化测试 92 项断言全部通过**（2026-10-18，Windows 11 + Windows PowerShell 5.1，用临时副本作目标、不改开发服文件）：
  `-DryRun` 预览、严格镜像更新（新增/覆盖/删除）、保护目录/文件不被改动、备份生成与保留份数、
  `-Rollback`/`-RollbackTo`、`-NoBackup`、`-SourceZip` 离线模式、代理下载、非法仓库/分支失败不破坏目标、
  运行中服务器被正确拒绝执行；
- Web 侧：`web-config.json`/`web-config.ps1`/`web-data/` 保护、严格镜像、备份与回滚；
- 清缓存脚本：显式清空 `cache` 内容后完成严格镜像；普通更新不删缓存（受保护）；缓存不备份、回滚只恢复脚本。

> 生产首次使用前，建议先用 `-DryRun` 对照确认删除清单里没有你手工放进 `config\scripts\` 的自定义文件
> （严格镜像会删除仓库中没有的文件；如有需要请把它们加入 `protectedServerFiles`）。
