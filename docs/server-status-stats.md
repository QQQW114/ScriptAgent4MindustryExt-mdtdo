# 服务器状态统计与 Web 展示

> 更新记录：2026-08-22 首次实现；**2026-09-11 改版为 schema 2**（移除 MDC 发放统计、新增 14 天/24 小时曲线与社区互动、游玩时长、峰值在线；对应脚本维护记录见 `scripts-maintenance.md` 同日条目）；
> **2026-10-17 `stats-web/` 升级为服务器门户**（状态/事件流/上传审核/管理后台，见 [web-portal.md](web-portal.md)），统计 JSON（schema 2）本身未变。

## 概述

服务器侧统计脚本（`wayzer/ext/serverStats.kts`）增量维护一组统计计数，定期写入一个
JSON 文件；独立 Web（`stats-web/`）只读该文件做展示。两者**可分离运行**：

- 服务器侧统计可开关（默认开），不与 Web 是否运行相关；
- Web 可随时启动/停止，服务器统计关闭后 Web 显示停止前的最后一次数据（并标记 `enabled: false`）。

门户首页（`index.html`）的「数据看板」区域提供两个视图（右上角切换）：

- **履历视图**（默认）：面向对外展示/个人履历——运营总览 KPI、近 14 天活跃折线图、
  今日 24 小时折线图、近 14 天社区互动、等级构成、以及"可直接引用的服务器数据"摘要块；
- **运维视图**：原有仪表盘——实时服务器信息、今日/累计 KPI、社区累计、近 14 天每日明细表。

## 展示指标

| 指标 | 口径 |
| --- | --- |
| 今日/总游玩人数 | 当日去重主体数 / 历史累计去重主体数（登录玩家 `account:<id>`；游客为设备 UUID） |
| 今日/总人流量 | 当日 / 累计 `PlayerJoin` 次数（含重复进入/退出重进） |
| 今日/总聊天条数 | 当日 / 累计 `PlayerChatEvent` 次数（被限速/拦截、未提交到服务端的消息不计） |
| 今日/累计游玩时长 | 统计启用期间每名玩家的在线会话时长之和（`PlayerJoin` → `PlayerLeave`） |
| 今日/历史峰值在线 | 当天 / 历史最高的同时在线人数（取自 5 秒快照） |
| 社区累计 | 帖子、评论、收到点赞、收到踩、玩家认可（含全部历史，从现有业务表读取） |
| 近 14 天社区互动 | 帖子/评论/点赞/认可在最近 14 天区间内的增量 |
| 0/1/2/3/3+ 玩家数 | 累计等级分布（首次进入按当时等级入桶，此后随等级变化迁移）；3+ 桶包含 3+/3++/4（含 4+admin） |

> **已移除**：MDC 发放统计（今日/总发放 MDC）及其事件链路——脚本不再监听 `MdcGrantedEvent`、
> JSON 不再输出 `mdcGranted`。数据库中历史遗留的 `serverStats.todayMdcGranted` /
> `serverStats.totalMdcGranted` 键**保留原值但不再更新**（不做破坏性删库）。

### 游玩时长口径

- 只统计**统计系统启用期间**的在线会话：`PlayerJoin` 记录进入时刻，`PlayerLeave` 结算时长；
- 统计关闭期间、脚本未加载期间、服务器停机期间的时长不计入；
- 累计值是跨重启的只增计数（落在 `MdtSettings`），今日值落在每日明细表；
- 不读取/汇总 `SeniorityProfiles.playMillis`（那是资历系统的独立口径，只用于一次性历史回填）。

### 社区数据口径

帖子/评论/点赞/认可的**累计值含全部历史**（首次启用时一次性回填），**近 14 天值只统计区间增量**。
它们不额外维护一份增量计数，而是直接按日期区间聚合现有业务表：

- `MdtReputationDaily` / `MdtRecognitionDaily` 本身有日期列，按 `date >= cutoff` 筛选；
- `MdtForumPosts` / `MdtForumComments` 用数据库侧 `COUNT + GROUP BY` 按创建时间聚合；
- 该聚合默认每 5 分钟刷新一次（`communityIntervalMillis`），并缓存在内存中供 JSON 输出使用。

### 等级分布口径

- 等级代码规范化：`0/1/2/3` 独立；`3+`、`3++`、`4`、`4+admin` 全部归入 **3+** 桶；
- 首次进入按当时等级入桶；此后通过 `TrustLevelChangedEvent` 迁移（旧桶 -1、新桶 +1）；
- 玩家从未被统计过时，等级迁移事件会被忽略（该玩家下次进入按当时等级入桶，端到端自愈）。

## 线程与缓存设计（不阻塞主线程）

- **游戏线程（事件钩子）只投递**：`PlayerJoin` / `PlayerLeave` / `PlayerChatEvent` /
  `TrustLevelChangedEvent` 监听器只往 `ConcurrentLinkedQueue` 放事件，不做数据库/文件操作；
- **IO 协程（`Dispatchers.IO`）单写者**：定时（默认 60 秒）从队列取事件、更新内存计数，
  **只有计数变化才落盘**，一次事务内批量写（每日行 + 每小时行 + 累计总量 + 主体登记），
  并写 JSON 文件（先写临时文件再替换，避免半文件）；
- **快照线程（`Dispatchers.game`）**：每 5 秒在游戏线程刷新服务器快照（地图/模式/波次/TPS/在线人数与等级分布），
  写入 `@Volatile` 快照，由 IO 协程读取写入 JSON；峰值在线也以该快照为准；
- **内存计数（`StatsState`）只由 IO 协程读写**，避免跨线程竞争；计数变化才刷盘；
- `onDisable`（脚本停用/关服）会补一次收尾落盘，避免退出前最后不到一个周期的增量丢失。

## 存储（严禁全库暴力统计）

所有计数**增量维护**，时间维度只有两级且行数完全有界：

- `MdtStatsPlayers`（主体注册表，`subject_uid` 主键）：判断是否新主体、等级分布迁移自愈；
- `MdtStatsActivePlayers`（`date + subject_uid` 复合主键）：每个主体每天最多一行，
  因此"某日游玩人数 = 该日行数"、"近 N 天曲线 = 日期区间扫描"，**不需要全表 COUNT**；
- `MdtStatsDaily`（`date` 主键）：每天 1 行，存人流量、聊天条数、游玩时长、峰值在线；
- `MdtStatsHourly`（`date + hour` 复合主键）：每天最多 24 行，存每小时人流量与峰值在线；
- `MdtSettings` 键（全部为增量累计值/字符串）：
  - `serverStats.enabled`：统计开关（`true`/`false`，缺失用脚本配置默认值）；
  - `serverStats.date`、`serverStats.totalPlayers`、`serverStats.totalFlow`、
    `serverStats.todayPlayers`、`serverStats.todayFlow`、`serverStats.rank0..rank3plus`；
  - `serverStats.totalChatMessages`：累计聊天条数；
  - `serverStats.totalPlayMillis`：累计游玩时长（毫秒）；
  - `serverStats.peakOnline`：历史峰值在线；
  - `serverStats.totalForumPosts` / `totalForumComments` / `totalLikes` /
    `totalDislikes` / `totalRecognitions`：社区累计总量；
  - `serverStats.lifetimeBackfilled`：一次性历史回填完成标记。

### 保留窗口与裁剪

| 数据 | 保留 | 说明 |
| --- | --- | --- |
| 每日汇总 `MdtStatsDaily` | 400 天（`dailyKeepDays`） | 超出按日期前缀删除 |
| 活跃主体 `MdtStatsActivePlayers` | 与每日汇总一致 | 行数 ≈ 保留窗口内每日活跃人数之和 |
| 每小时明细 `MdtStatsHourly` | 14 天（`MdtStorage.STATS_HOURLY_KEEP_DAYS`） | 超出自动裁剪为日汇总 |

裁剪只做"日期小于 cutoff"的主键区间删除，不是全表扫描；调用时机为脚本启动一次 + 跨天滚动。

### 一次性历史回填

首次启用（`serverStats.lifetimeBackfilled` 不存在）时执行一次历史总量回填：帖子数、评论数、
点赞/踩、认可数、`SeniorityProfiles.playMillis` 总和，作为**累计总量**的基线；完成后写标记。

- 这是整条统计链路中唯一的重查询，运行在 IO 协程、只在首次启用时执行；
- **不按日期拆分历史**（那需要按时间戳扫描全表，属于明确避免的做法），因此
  14 天/24 小时曲线从统计启用当天开始累积，页面以 `notes.dailyHistorySince` 标注起始日；
- **自愈**：若标记已存在但社区总量仍为 0（早期版本写过标记、却没有社区统计键的老库），
  `needsStatsLifetimeBackfill()` 会判定需要重做，脚本再次执行回填并覆盖写入。
  回填是按业务表全量重算的幂等操作，可安全重入。

跨天滚动：`rolloverTo` 只重置"今日"的内存值（逐日明细本来就按日期键存放），
总量与等级分布不动；跨天瞬间仍在队列里的事件按各自携带的日期归入正确的那一天。

## 配置（config key，见 `serverStats.kts`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| 统计启用默认值（config key） | `true` | 仅在 `MdtSettings serverStats.enabled` 缺失时生效 |
| `updateIntervalMillis` 刷新/持久化/写文件间隔 | `60_000` ms | 最小 5000ms；只有计数变化才写库 |
| `snapshotIntervalMillis` 状态快照刷新间隔 | `5_000` ms | 最小 1000ms |
| `communityIntervalMillis` 社区互动聚合刷新间隔 | `300_000` ms | 最小 60000ms |
| `dailySeriesDays` 近 N 天曲线天数 | `14` | 合法范围 2~90 |
| `hourlySeriesHours` 一天内小时桶数 | `24` | 合法范围 1~24 |
| `dailyKeepDays` 每日汇总保留天数 | `400` | 最小 2 |
| `statsOutputPath` JSON 输出路径 | `stats/server-status.json` | 相对 `config/` 目录 |

## 命令与权限

`/serverstats [status|on|off]`（别名 `统计`/`状态统计`/`statusstats`）：

- `status`：所有人可查看（数据与 Web 一致，无敏感信息）；
- `on`/`off`：仅 4 级/admin（`isTrustAdmin`）或控制台；
- 开关状态持久化到 `MdtSettings serverStats.enabled`；关闭时下个周期写一次 `enabled: false` 的最终文件。

## JSON 文件格式（schema 2）

```json
{
  "schema": 2,
  "enabled": true,
  "updatedAt": "2026-09-11 22:40:00",
  "serverInfo": { "map": "...", "mode": "survival", "wave": 1, "tps": 60, "uptimeSeconds": 600, "onlinePlayers": 2,
                  "onlineByRank": { "0": 0, "1": 2, "2": 0, "3": 0, "3+": 0 } },
  "today": { "date": "2026-09-11", "players": 5, "flow": 9, "chatMessages": 132, "playMillis": 7380000,
             "online": 2, "peakOnline": 4, "peakOnlineHistory": 11 },
  "total": { "players": 321, "flow": 1840, "chatMessages": 45210, "playMillis": 903600000,
             "forumPosts": 128, "forumComments": 640, "likes": 1520, "dislikes": 37, "recognitions": 88,
             "peakOnline": 11 },
  "last14Days": { "days": 14, "startDate": "2026-08-29", "endDate": "2026-09-11", "activeDays": 12,
                  "players": 63, "dailyAvgPlayers": "4.5", "flow": 210, "chatMessages": 1810,
                  "playMillis": 86000000, "forumPosts": 9, "forumComments": 41, "likes": 96,
                  "dislikes": 2, "recognitions": 5 },
  "daily":    [ { "date": "08-29", "players": 3, "flow": 5, "chatMessages": 44, "playMillis": 3600000 } ],
  "hourly":   [ { "hour": 0, "flow": 0, "peakOnline": 0 } ],
  "totalByRank": { "0": 12, "1": 180, "2": 96, "3": 21, "3+": 12 },
  "notes": { "dailySeriesDays": 14, "hourlySeriesHours": 24, "dailyKeepDays": 400, "hourlyKeepDays": 14,
             "communityWindowDays": 14, "peakOnline": 11, "playMillisTotal": 903600000,
             "communityTotalsAreLifetime": true, "dailyHistorySince": "2026-09-11" }
}
```

- `daily` 固定输出 N 个点（缺失日期补 0，不会断线），`date` 为 `MM-dd`；
- `hourly` 固定输出 24 个点（今天），`hour` 为 0~23；
- Web 对缺失字段做了兜底，旧版 JSON 也不会导致页面报错。

## Web（`stats-web/`，2026-10-17 升级为服务器门户）

> 2026-10-17 起 `stats-web/` 由「单文件静态状态站」升级为**服务器门户**：状态与图表之外新增游戏内实时事件流、
> DP/地图上传审核与管理员后台。完整架构/API/配置/安全/审核流程见 [web-portal.md](web-portal.md)；
> 本文件只保留与「状态统计 JSON」直接相关的 Web 说明。**统计 JSON（schema 2）与上面两个数据视图的口径未变**，
> 下面的 `/api/status`、`/server-status.json` 即旧页面的数据入口。

- **页面**：`index.html` 门户首页（欢迎页 + 状态卡 + 折线图 + 实时事件流，前端实现见 `assets/**`）、
  `upload.html` 上传页、`admin.html` 管理后台；全部本地资源、无外网 CDN，移动端可用；
- **后端**：`start-web.ps1`（入口）+ `server/**`（PowerShell HttpListener，零外部依赖，兼容 Windows PowerShell 5.1）；
  默认监听 `127.0.0.1:8090`；
- **状态数据路由**：
  - `GET /api/status`：规范化状态对象（`online/map/mode/wave/tps/uptimeSeconds/updatedAt/available` + `stats` 完整原始 JSON），
    前端图表直接用 `data.stats` 里的 `daily/hourly/last14Days/today/total/totalByRank/notes`；
  - `GET /server-status.json`：**旧入口兼容**，直接回传 `mdtserver\config\stats\server-status.json` 原文
    （脚本 config key `statsOutputPath` 可改路径）；
  - `/api/status` 在 JSON 文件缺失时返回 200 + `available:false`（不 500），页面提示统计未启用；
- **配置**：`web-config.json`（主配置：端口、管理员账号密码、上传/目录/队列限制、安全项；相对路径以 stats-web 目录为基准，改完重启生效）；
  `web-config.ps1` 仅在缺少 `web-config.json` 时作为旧部署回退；命令行 `-Bind/-Port/-StatsJson` 优先级最高；
- **公网访问**：管理员执行一次 `start-web.ps1 -InstallAcl` → 配置/命令行 `-Bind 0.0.0.0` → 防火墙放行端口；
  默认只监听本机。**默认管理员密码必须在首次部署后立即修改**（启动横幅会警告；也支持 `-GenPasswordHash` 生成哈希）；
- **事件流**：`GET /api/events?afterTs=&limit=` 读取游戏侧 `wayzer/ext/liveEventFeed.kts` 写入的
  `mdtserver\config\stats\live-events.jsonl`（聊天/加入离开/投票/换图/系统事件，`text` 保留颜色标记原文；文件缺失返回空列表）；
- **投稿审核**：`upload.html` 公开投稿 DP/地图（单文件 ≤10MB、每种待审队列 ≤10，满返回 `QUEUE_FULL`；审核通过才落盘：
  DP → `external-cp/`、地图 → `config/maps/`）；`admin.html` 登录后审核/拒绝/移除，地图审核通过默认控制台 `maps` 刷新列表、
  DP 热重载默认关（详见 web-portal §6）；上传/审核/删除动作记 `web-data/audit.log`；
- **API 一览**：公开 `GET /api/status|events|catalog|health`；认证 `POST /api/login|logout`；公开上传 `POST /api/upload`；
  Bearer 管理 `GET /api/review/list`、`POST /api/review/approve|reject`、`DELETE /api/item`；统一信封与错误码见 web-portal §4；
- **第二期（2026-10-18，提交 `1831084`）新增能力**：
  - **服务器日志**：`GET /api/logs|/api/logs/tail|/api/logs/download`（管理后台有「服务器日志」面板：列表/尾部预览/直链下载）。
    白名单 `log-<数字>.txt`/`console-<时间>.log`/`last.log`/`restart-supervisor.log`；**默认需管理员登录**（Bearer 或
    `?token=` 直链）；单文件 50MB→413、并发默认 2→429 `BUSY`+`Retry-After`、每 IP 5 次/5 分钟→429 `RATE_LIMITED`；
    支持 Range 206/416、异步流式输出；审计 `web-data/audit.log`；
  - **历史消息**：`GET /api/events/history` 跨「当前 + 旋转历史」倒序分页（`cursor=ts` 只返回更早、`fromTs/toTs` 闭区间、
    `type`/`keyword`（匹配原始 JSON 行，中文需 percent-encoding）、`limit` 100/最大 200、`nextCursor` 仅 hasMore）；
    每 IP 60 次/分钟；主文件轮转暂缺时 `/api/events` 给 `mainFileAvailable/historyAvailable/hint` 中性提示；
  - **上行带宽**：`/api/status` 的 `data.net` = 宿主机网卡 `SentBytes` 差分（默认 10s 采样、5 分钟平均、30 点），
    单位 **B/s**（`unit=bytes_per_second`；`avgUpBps`/`avgUpBytesPerSec`/`series`/`interface`）；口径为**宿主机网卡总上行**
    （含游戏服/Web/系统），**游戏服与 Web 不同机时无意义**；失败降级 `available:false`；
  - **控制台可选**：`/api/health.console`（`optional:true`，`ok/not-reachable/disabled`，500ms 探测/10s 缓存）；
    **生产不开调试端口是常态**——控制台不可达不影响审核/删除（仍 `ok:true` + 中性 `reloadHint`/`warnings`，无 WARN/ERROR）；
  - **健康与降噪**：`/api/health` 新增 `version=portal-1.1.0`、`console/logs/activeDownloads/bandwidth/clientDisconnects/
    eventHistoryFiles`；客户端断开写失败静默计数（`clientDisconnects`），真异常 ERROR 限频（同签名 60s、堆栈 3 帧）；
  - **配置**：`web-config.json` 新增 `logs`/`bandwidth`/`events` 三段（旧配置缺键自动用默认值）；前端资源版本 `20261018b`；
- **演示模式（mock）**：URL 加 `?mock=1` 用内置假后端（`assets/mock/`，演示账号 admin/demo123，不产生真实请求），`?mock=0` 关闭；
  前端无构建、无外网 CDN，静态资源版本号 `?v=` 需手工 bump；
- **旧配置自动迁移**：旧部署只有 `web-config.ps1`（`$WebBind/$WebPort/$WebStatsJson`）时，首次启动自动生成 `web-config.json`、
  旧文件改名 `web-config.ps1.bak-<时间>` 备份（幂等/失败回退/CLI 覆盖）；**生产升级不要把仓库默认 `web-config.json` 整目录覆盖**
  （否则不会迁移、监听退回默认 `127.0.0.1:8090`），详见 web-portal §3.4；
- **分发复制**：保持 `stats-web/` 整个目录一起复制（`index.html`/`upload.html`/`admin.html`/`assets/`/`server/`/`start-web.ps1`/
  `start-web.bat`/`web-config.json`；`web-data/` 为运行数据不复制）；旧的「四件套」单独复制方式不再适用，
  旧分发包 `复制插件用\stats-web.zip` 已过期。

## 已知边界与后续注意

- **曲线从启用当天开始累积**：历史每日/每小时人数在数据库里不存在，为避免全表扫描不做回溯拆分；
  运行满 14 天后 14 天曲线才完整，24 小时曲线从当天 0 点起逐步填充；
- 统计从**启用时刻**开始累计；启用前历史不回溯（社区累计值除外，见上）；
- 游客主体按设备 UUID 去重；换设备/清数据会作为新主体计数；登录后 `account:<id>` 与游客
  UUID 视为不同主体（属于不同统计口径，不跨主体合并）；
- 服务器停机期间的数据不自动补计（重启后继续累计）；停机瞬间未落盘的会话时长不计入；
- 聊天量以服务端收到的 `PlayerChatEvent` 为准，因此被限速丢弃/未进入服务端的消息不计；
- 等级分布在服务器停机期间被直接改库（非脚本途径）会出现漂移；玩家下次进入时按当时
  等级登记自愈（已登记的玩家在等级事件时会按行内 `cur_level` 迁移）；
- 社区聚合是 5 分钟粒度的缓存，页面上的"近 14 天社区数据"最多滞后 5 分钟；
- **数据完整性提示**：页面页脚与"可直接引用的服务器数据"下方固定展示
  "统计信息不代表全部准确数据，受限于插件的更新，会出现数值少于实际总信息的情况"——
  统计只在插件启用期间累计，历史与停机期间的量不回溯补计，因此实际值可能高于页面显示值。
