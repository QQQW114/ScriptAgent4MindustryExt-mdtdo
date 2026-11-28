# 生产部署与同步清单

> 适用边界：生产服不在本机，Agent 仅负责准备源码、更新器与验收记录；实际生产停启、更新、回滚与实测由维护者/运维执行。

---

## 1. 两套远端服与推荐更新方式

生产同步**推荐使用仓库自带更新器 `updater/`**（零 Git、支持预览 `-DryRun`、自动备份与 `-Rollback`、严格保护运行数据，完整手册见 [../updater/README.md](../updater/README.md)）：

| 目标服 | 跟踪分支 | 推荐一键脚本 | 更新范围 |
|---|---|---|---|
| **主服 MDT DO** | `main` | `updater\update-mainserver.bat` | 插件 `scripts/**` + 邮件服务 `email-service/**`（自动重启邮件计划任务） |
| **主服 MDT DO（仅插件）** | `main` | `updater\update-mainserver-scripts.bat` | 仅更新插件 `scripts/**`（不触碰邮件服务） |
| **分服 MDC DO** | `mdc-do` | `updater\update-subserver.bat` | 仅更新插件 `scripts/**`（不含邮件服务与邮箱绑定） |
| **门户 Web** | `main` / `mdc-do` | `updater\update-web.bat` | `stats-web/**`（无需停游戏服，更新后重启 Web 进程） |
| **仅邮件服务（主服）** | `main` | `updater\update-email-service.bat` | `email-service/**`（默认只增改不删） |

常用命令：

```bat
:: 1. 每次先干跑预览（零改动，核对新增/变更/删除清单）
update-mainserver.bat -DryRun
update-subserver.bat -DryRun

:: 2. 正式更新
update-mainserver.bat
update-subserver.bat

:: 3. 出错快速回滚
update-plugins.bat -Rollback
update-web.bat -Rollback
update-email-service.bat -Rollback
```

> 代理下载支持：`-Proxy http://127.0.0.1:7897` 或 `-ProxyPort 7897`（也自动读取 `HTTPS_PROXY`）。

---

## 2. 运行中热更新 vs 必须停服清缓存

### 可运行中免停服更新（纯 `.kts` 变更）
前提：生产 `server.properties` 已开启本机控制台 Socket（**严禁暴露公网**）：

```properties
socketInput=true
socketInputPort=6859
socketInputAddress=localhost
```

更新器执行顺序：全服 `say` 预告 → 镜像前先 `sa unload` 待删除脚本 → 自动备份与镜像 → `sa scan` → 逐个 `sa load` 并监听 `script-load/current.log` 完成标记 → `sa listFailed` 校验 → 全服 `say` 通知完成（可通过 `config.json` 的 `hotReloadAnnounce=false` 关闭广播）。

- 退出码 `0`：更新与热重载全部成功；
- 退出码 `2`：预检阶段拒绝（如含 `.kt` 改动或 Socket 不可用），**目标文件零改动**；
- 退出码 `3`：文件已落盘但部分脚本热重载失败，建议重启游戏服恢复干净状态。

### 必须停服并清空编译缓存的场景
以下任一改动**禁止热重载**，必须停服后执行 `update-plugins-clear-cache.bat`（或手工清空 `config/scripts/cache/`）再冷启动：

1. 修改了 `scripts/wayzer/vote.lib.kt`、`scripts/wayzer/lib/*.kt` 或其他 `.kt` 模块库；
2. 更换服务端 JAR（`server-*.jar` + `server.properties` 的 `jar=`）、ScriptAgent 插件本体（`config/mods/ScriptAgent4MindustryExt-3.4.0-allInOne.jar`）或 `libs/` 依赖库；
3. 首次启用或大幅跨版本升级。

---

## 3. 绝对不能覆盖的生产运行数据与凭据

同步或手工维护时，下列路径属于**各服本地运行资产**，绝不能被仓库或开发机文件覆盖：

| 类别 | 绝不可覆盖路径 | 说明 |
|---|---|---|
| 游戏数据库与脚本配置 | `config\scripts\data\` | 含 H2 数据库 `h2DB.db.mv.db`、KV 存储 `kvStore.mv`、`ip2region_v4.xdb`、`lang.ini`、`config.conf`（含各服邮箱 Token 与参数） |
| 外部内容包 | `config\scripts\external-cp\` | 生产已上架 DP / CP 包 |
| 编译缓存 | `config\scripts\cache\` | 运行中勿动；停服清缓存时清空内容让服务端重建 |
| 游戏服运行数据 | `config\saves\`、`maps\`、`stats\`、`logs\`、`music-jukebox\`、`assetCache\`、`assets\`、`settings*.bin`、`rules.hjson` | 存档、地图、统计、日志与游戏设置 |
| 服务端配置 | `mdtserver\server.properties` | 仅按需合并单个键（如切换 `jar=`），不要整文件覆盖 |
| 门户 Web 配置与数据 | `stats-web\web-config.json`、`web-config.ps1`、`stats-web\web-data\` | 含生产管理员密码哈希、监听配置、待审投稿与审计日志 |
| 邮件服务配置与日志 | `email-service\config.json`、`email-service\logs\` | 含阿里云 RAM AccessKey 与 `internalToken` |

---

## 4. 非脚本资产（JAR / SA 插件 / libs / 启动器）变更声明

`config/mods/`、`libs/` 与 `server-*.jar` 均在 `.gitignore` 内，Git 提交本身不会体现它们的变化。日常同步仅涉及例程集（`scripts/`、`stats-web/`、`email-service/`）；若改动波及以下任一项，必须在 PR 与 `CHANGELOG.md` 中**显式注明生产需同步**：

- **游戏服务端 JAR**：当前基线 `server-2026.10.10.B509.jar`（MindustryX `prerelease-2026.10.10.B509` / Mindustry `v160.7`），切换时修改 `server.properties` 的 `jar=`；
- **ScriptAgent 插件本体**：当前运行 `config/mods/ScriptAgent4MindustryExt-3.4.0-allInOne.jar`（维护者竞态修复构建 `ScriptAgent c2823c1`）；
- **依赖库 `libs/`**：随 SA 3.4.0 配套的 Kotlin 编译器、Exposed、H2 等依赖（同步策略为补齐更新，不删生产多余 jar）；
- **启动器**：`mdtserver/start-server.ps1`（最新包含控制台原始字节转发、彩色显示、Socket 竞态修复与 `-Once`/`-Headless`）。

---

## 5. Web 门户与邮件服务部署要点

- **门户 Web（`stats-web/`）**：首次部署必须立即修改默认管理员密码（`.\start-web.ps1 -GenPasswordHash "新密码"` 写入 `admin.passwordHash` 并清空 `admin.password`）；默认仅监听 `127.0.0.1:8090`，公网访问需前置反代 HTTPS 并配置 `security.trustedProxies`（详见 [web-portal.md](web-portal.md) 与 [../stats-web/README.md](../stats-web/README.md)）。
- **邮件服务（仅主服 `email-service/`）**：通过管理员 PowerShell 运行 `.\install-scheduled-task.ps1` 安装为开机自启 + 崩溃自动重启计划任务 `MDT Email Service`；仅监听 `127.0.0.1:40150`，严禁防火墙放行公网（详见 [email-service.md](email-service.md) 与 [../email-service/README.md](../email-service/README.md)）。
