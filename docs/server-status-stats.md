# 服务器状态统计与 Web 展示

> 更新记录：2026-08-22 首次实现（对应脚本维护记录条目见 `scripts-maintenance.md` 2026-08-22）。

## 概述

服务器侧统计脚本（`wayzer/ext/serverStats.kts`）增量维护一组统计计数，定期写入一个
JSON 文件；独立 Web（`stats-web/`）只读该文件做展示。两者**可分离运行**：

- 服务器侧统计可开关（默认开），不与 Web 是否运行相关；
- Web 可随时启动/停止，服务器统计关闭后 Web 显示停止前的最后一次数据（并标记 `enabled: false`）。

## 展示指标

| 指标 | 口径 |
| --- | --- |
| 今日游玩人数 | 当日去重的主体数（登录玩家 `account:<id>`；游客为设备 UUID） |
| 总游玩人数 | 历史累计去重主体数 |
| 今日人流量 | 当日 `PlayerJoin` 次数（含重复进入/退出重进） |
| 总人流量 | `PlayerJoin` 累计次数 |
| 今日发放 MDC | 当日向**已登录账号**的**新发放**MDC 之和 |
| 总发放 MDC | 历史累计新发放 MDC 之和 |
| 0/1/2/3/3+ 玩家数 | 累计等级分布（首次进入按当时等级入桶，此后随等级变化迁移）；3+ 桶包含 3+/3++/4（含 4+admin） |

### MDC 发放口径（排除游客）

只统计**新发放**：`addTrustPoints` / `addTrustPointsBatch` 的正向金额，以及
`setTrustPoints` 的管理员上调差额，且只对 `account:<id>` 主体统计（游客主体不统计）。

**不计入**的存量流转（不新增 MDC）：

- 转账（`transferTrustPoints`）；
- MDC 红包领取/退回/发送（在 `MdtStorage` 内流转）；
- 面对面读博结算（`addCurrentTrustPoints`，本金回流）。

实现：`wayzer/lib/TrustSystemEvents.kt` 新增 `MdcGrantedEvent(uid, amount, reason)`，
由 `wayzer/user/trustPoint.kts` 在上述发放点触发；`serverStats.kts` 监听该事件统计。

### 等级分布口径

- 等级代码规范化：`0/1/2/3` 独立；`3+`、`3++`、`4`、`4+admin` 全部归入 **3+** 桶；
- 首次进入按当时等级入桶；此后通过 `TrustLevelChangedEvent` 迁移（旧桶 -1、新桶 +1）；
- 玩家从未被统计过时，等级迁移事件会被忽略（该玩家下次进入按当时等级入桶，端到端自愈）。

## 线程与缓存设计（不阻塞主线程）

- **游戏线程（事件钩子）只投递**：`PlayerJoin` / `TrustLevelChangedEvent` / `MdcGrantedEvent`
  监听器只往 `ConcurrentLinkedQueue` 放事件，不做数据库/文件操作；
- **IO 协程（`Dispatchers.IO`）单写者**：定时（默认 60 秒）从队列取事件，更新内存计数，
  有变化才持久化（一次事务批量写 `MdtSettings`），并写 JSON 文件（先写临时文件再替换，避免半文件）；
- **快照线程（`Dispatchers.game`）**：每 5 秒在游戏线程刷新服务器快照（地图/模式/波次/TPS/在线人数与在线等级分布），
  写入 `@Volatile` 快照，由 IO 协程读取写入 JSON；
- **内存计数（`StatsState`）只由 IO 协程读写**，避免跨线程竞争；计数变化才刷盘。

## 存储（严禁全库暴力统计）

新存储项**增量维护**，绝不做 `COUNT(*)` 全表统计：

- 新表 `MdtStatsPlayers`（`subject_uid` 主键 = 统计主体；`cur_level`、`last_join_date`、`first_seen_date`）：
  每次进入用**主键查询/插入**判断“是否新主体”“今日是否已计数”“等级是否变化”；
  `last_join_date` 同时用于跨重启的“今日去重”（重启后已计数的玩家不会重复计数）。
- `MdtSettings` 键（全部为增量累计值/字符串）：
  - `serverStats.enabled`：统计开关（`true`/`false`，缺失用脚本配置默认值）；
  - `serverStats.date`：当前统计日期（`yyyy-MM-dd`，跨天重置今日三项）；
  - `serverStats.totalPlayers` / `serverStats.totalFlow` / `serverStats.totalMdcGranted`：总量三项；
  - `serverStats.todayPlayers` / `serverStats.todayFlow` / `serverStats.todayMdcGranted`：今日三项；
  - `serverStats.rank0` / `serverStats.rank1` / `serverStats.rank2` / `serverStats.rank3` / `serverStats.rank3plus`：累计等级分布。

跨天滚动：`rolloverTo` 只重置今日三项（日期取自事件发生时的服务器本地日期）；总量与等级分布不动。

## 配置（config key，见 `serverStats.kts`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| 统计启用默认值（config key） | `true` | 仅在 `MdtSettings serverStats.enabled` 缺失时生效 |
| 刷新/持久化/写文件间隔 | `60_000` ms | 最小 5000ms |
| 状态快照刷新间隔 | `5_000` ms | 最小 1000ms |
| JSON 输出路径 | `stats/server-status.json` | 相对 `config/` 目录（即 `mdtserver/config/stats/server-status.json`） |

## 命令与权限

`/serverstats [status|on|off]`（别名 `统计`/`状态统计`/`statusstats`）：

- `status`：所有人可查看（数据与 Web 一致，无敏感信息）；
- `on`/`off`：仅 4 级/admin（`isTrustAdmin`）或控制台；
- 开关状态持久化到 `MdtSettings serverStats.enabled`；关闭时下个周期写一次 `enabled: false` 的最终文件。

## JSON 文件格式（schema 1）

```json
{
  "schema": 1,
  "enabled": true,
  "updatedAt": "2026-08-22 12:00:00",
  "serverInfo": { "map": "...", "mode": "survival", "wave": 1, "tps": 60, "uptimeSeconds": 60, "onlinePlayers": 1, "onlineByRank": { "0": 0, "1": 1, "2": 0, "3": 0, "3+": 0 } },
  "today": { "date": "2026-08-22", "players": 1, "flow": 2, "mdcGranted": 10 },
  "total": { "players": 10, "flow": 20, "mdcGranted": 100 },
  "totalByRank": { "0": 1, "1": 8, "2": 1, "3": 0, "3+": 0 }
}
```

## Web（`stats-web/`）

- `index.html`：单文件页面（无构建）；自动刷新 30 秒 + 手动刷新；
  显示服务器信息、今日/累计指标、在线等级分布、累计等级分布；
  顶部状态标签区分“统计已启用 / 已关闭（展示停止前数据）”。
- `start-web.ps1`：PowerShell HttpListener 静态服务；`/` 提供页面，`/server-status.json`
  映射到 JSON 文件；**默认存储位置：`mdtserver\config\stats\server-status.json`**
  （即服务器 `config/` 下的 `stats/server-status.json`，脚本 config key `statsOutputPath` 可改）。
- `web-config.ps1`：常用配置（`$WebBind`/`$WebPort`/`$WebStatsJson`），改完重启即生效；
  命令行参数（`-Bind`/`-Port`/`-StatsJson`）优先级更高。
- `start-web.bat`：双击启动入口，参数透传（如 `start-web.bat -Bind 0.0.0.0 -Port 8081`）。
- 公网访问：`-InstallAcl`（管理员执行一次，注册 URL 保留）→ `-Bind 0.0.0.0` → 防火墙放行端口；
  默认 `127.0.0.1:8081`。点击 `Ctrl+C` 停止；与服务器统计开关互不依赖。
- 分发复制：保持 `index.html`/`start-web.ps1`/`start-web.bat`/`web-config.ps1` 四件套同目录
  （`复制插件用\stats-web\` 为准）。

## 已知边界与后续注意

- 统计从**启用时刻**开始累计；启用前历史不回溯（避免全库统计）；
- 游客主体按设备 UUID 去重；换设备/清数据会作为新主体计数；登录后 `account:<id>` 与游客
  UUID 视为不同主体（属于不同统计口径，不跨主体合并）；
- 服务器停机期间的数据不自动补计（重启后继续累计）；
- 等级分布在服务器停机期间被直接改库（非脚本途径）会出现漂移；玩家下次进入时按当时
  等级登记自愈（已登记的玩家在等级事件时会按行内 `cur_level` 迁移）。
