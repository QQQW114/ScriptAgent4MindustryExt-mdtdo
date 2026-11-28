# 服务器门户 Web（`stats-web`）设计与规范

> 后端：`stats-web/server/**` + `stats-web/start-web.ps1`（Windows PowerShell 5.1+ / HttpListener，零外部依赖）；
> 前端：`stats-web/index.html`、`upload.html`、`admin.html`、`assets/**`（纯本地资源，无外网 CDN，静态资源版本 `20261101a`）；
> 游戏侧数据源：`wayzer/ext/serverStats.kts`（[server-status-stats.md](server-status-stats.md)）与 `wayzer/ext/liveEventFeed.kts`（[live-events-feed.md](live-events-feed.md)）。

---

## 1. 总览与目录

门户是独立于游戏服务端的 HTTP 进程，可随时独立启停，不阻塞游戏主线程：

| 能力 | 入口 | 数据来源 |
|---|---|---|
| 欢迎页 + 服务器状态卡 + 14 天/24h 折线图 + 实时事件流 + 上行带宽 | `/` | `/api/status`、`/api/events` |
| 状态统计旧入口兼容 | `/server-status.json` | `config/stats/server-status.json` 原文 |
| DP / 地图公开投稿（免登录） | `/upload.html` → `POST /api/upload` | 暂存 `stats-web/web-data/uploads/<id>/` |
| 管理员登录与审核后台 | `/admin.html` → `/api/login`、`/api/review/*`、`DELETE /api/item` | 审核通过后原子落盘至 `external-cp/` 或 `config/maps/` |
| 现有 DP / 地图目录 | `GET /api/catalog` | 扫描 `external-cp/`、`config/maps/` + `catalog-meta.json` |
| 跨轮转历史事件分页 | `GET /api/events/history` | `live-events.jsonl` + `live-events.1..N.jsonl` |
| 服务器日志列表 / 下载 / 尾部预览 | `/api/logs`、`/api/logs/download`、`/api/logs/tail` | `mdtserver/config/logs/` 白名单文件（默认需管理员登录） |

```text
stats-web/
├─ index.html / upload.html / admin.html
├─ assets/                    # 纯本地前端 css/js/mock/quotes.json
├─ start-web.ps1 / .bat       # 启动入口（兼容 -Bind/-Port/-StatsJson/-InstallAcl/-GenPasswordHash）
├─ web-config.json            # 主配置（生产敏感，部署后必改默认密码）
├─ web-config.ps1             # 旧部署自动迁移兜底
├─ server/
│  ├─ portal-core.ps1         # 配置合并/迁移、PBKDF2、会话、限频、日志与审计
│  ├─ portal-http.ps1         # 请求体超时/限速读、零拷贝 multipart、结构魔数校验、安全头、静态白名单
│  ├─ portal-store.ps1        # 提交记录、暂存隔离、目录清单、原子落盘/替换/删除
│  ├─ portal-console.ps1      # 非阻塞可选游戏控制台 Socket 桥
│  ├─ portal-api.ps1          # 全量 API 路由与校验
│  ├─ portal-logs.ps1         # 白名单日志、Range/异步分块下载泵、并发/IP 限频
│  ├─ portal-history.ps1      # 跨轮转历史事件倒序分页与行缓存
│  ├─ portal-metrics.ps1      # 宿主机网卡上行差分采样（B/s）
│  └─ portal-server.ps1       # BeginGetContext 主循环、异步下载写泵与安全告警横幅
└─ web-data/                  # 运行数据（不入库、不分发：submissions/catalog-meta/uploads/audit/portal.log）
```

---

## 2. 启动、公网部署与配置

### 2.1 启动与常用命令

```powershell
cd stats-web
.\start-web.ps1                                       # 默认 http://127.0.0.1:8090/
.\start-web.ps1 -Port 8091                            # 临时覆盖端口
.\start-web.ps1 -InstallAcl -Port 8090                # 管理员权限执行一次，注册非本机 URLACL
.\start-web.ps1 -Bind 0.0.0.0 -Port 8090              # 开放局域网/公网监听（先改默认密码！）
.\start-web.ps1 -GenPasswordHash "你的新密码"          # 生成 PBKDF2 哈希填入 admin.passwordHash
```

- **反代与 `X-Forwarded-For`**：默认只监听 `127.0.0.1`，公网强烈建议由 Nginx/Caddy 反代提供 HTTPS。`security.trustProxyHeaders=true` **仅在 `security.trustedProxies` 非空且直连对端命中白名单时生效**，并取**最右侧非受信段**作为真实客户端 IP（防止客户端伪造最左段）；未配置 `trustedProxies` 时一律忽略 XFF：
  ```json
  "security": { "trustProxyHeaders": true, "trustedProxies": ["127.0.0.1"] }
  ```
- **旧配置自动迁移**：缺少 `web-config.json` 但存在旧 `web-config.ps1` 时，启动自动迁移 `$WebBind/$WebPort/$WebStatsJson` 生成 `web-config.json` 并将旧文件重命名为 `web-config.ps1.bak-<时间>`（幂等、失败自动回退）。生产升级时切勿用仓库默认 `web-config.json` 覆盖生产文件。

### 2.2 核心配置字段（`web-config.json`，相对路径以 `stats-web/` 为基准）

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `bind` / `port` | `127.0.0.1` / `8090` | 监听地址与端口（CLI 参数优先级最高） |
| `statsJson` / `eventsJsonl` | `../mdtserver/config/stats/server-status.json` / `live-events.jsonl` | 游戏侧状态与事件流路径 |
| `dirs.dp` / `dirs.maps` / `dataDir` | `../mdtserver/config/scripts/external-cp` / `../mdtserver/config/maps` / `web-data` | DP、地图与运行数据目录 |
| `admin.username` / `password` / `passwordHash` / `sessionHours` | `admin` / `ChangeMe_123456` / `""` / `12` | `passwordHash` 非空时优先于明文；上线必须清空 `admin.password` |
| `upload.maxDpFileBytes` / `maxFileBytes` / `maxRequestBytes` | `31457280`（30MB）/ `10485760`（10MB）/ `33554432`（32MB） | DP 与地图分别限额；请求体超限立即 413 |
| `upload.queueLimitPerKind` / `historyLimit` / `deleteRejectedPayloads` | `10` / `200` / `true` | 每类待审上限（满返回 `QUEUE_FULL`）、历史裁剪与拒审删暂存 |
| `upload.allowedDpExtensions` / `allowedMapExtensions` | `.zip/.json/.hjson/.json5` / `.msav` | 扩展名白名单 + 结构级文件头校验 |
| `rateLimit.loginBackoffBaseSeconds` / `MaxSeconds` / `ResetMinutes` | `1` / `300` / `30` | per-account 错密指数退避（`0/2/4/8…300s`，不锁正确密码） |
| `rateLimit.globalFailuresPerHour` / `globalSlowModeRetrySeconds` / `loginMinIntervalMs` | `40` / `5` / `120` | 全局失败慢速令牌桶与常态 PBKDF2 节流 |
| `rateLimit.ipHardLockFailures` / `uploadsPerWindow` / `uploadWindowMinutes` | `0`（关）/ `5` / `10` | 默认关闭同 IP 硬锁防 NAT 连坐；同 IP 上传限频 |
| `console.enabled` / `host` / `port` / `waitSeconds` | `true` / `127.0.0.1` / `6859` / `30` | 可选游戏控制台桥（不可达属正常，不影响审核） |
| `reload.dpOnApprove` / `mapListOnApprove` / `mapListOnRemove` / `unloadOnRemove` | `false` / `true` / `true` / `false` | 地图默认刷新列表，DP 热重载/卸载默认关闭 |
| `security.trustProxyHeaders` / `trustedProxies` | `false` / `[]` | 反代 XFF 白名单（IP/CIDR） |
| `security.requestReadTimeoutSeconds` / `minReadBytesPerSecond` / `maxPendingConsoleCommands` | `30` / `2048` / `8` | 慢速请求总超时、最低速率与控制台排队上限 |
| `logs.enabled` / `requireLogin` / `dir` / `maxConcurrentDownloads` | `true` / `true` / `../mdtserver/config/logs` / `2` | 服务器日志默认需管理员登录；并发下载上限 2 |
| `logs.rateLimitPerWindow` / `rateWindowMinutes` / `maxFileBytes` / `tailMaxLines` | `5` / `5` / `52428800`（50MB）/ `2000` | 单 IP 下载+tail 共用限频与文件大小上限 |
| `bandwidth.enabled` / `interface` / `intervalSeconds` / `windowMinutes` / `seriesPoints` | `true` / `auto` / `10` / `5` / `30` | 宿主机网卡总上行采样（B/s）；不同机部署无意义 |
| `events.historyMaxFiles` / `historyCacheMaxBytes` / `historyRateLimitPerMinute` | `13` / `8388608` / `60` | 历史查询最多扫描当前+12 份轮转；单 IP 每分钟限频 |

---

## 3. HTTP API 契约

- 统一响应信封：成功 `{"ok":true,"data":...}`；失败 `{"ok":false,"error":{"code":"...","message":"..."}}`。
- 认证方式：`Authorization: Bearer <64-hex token>`（内存会话，**不使用 Cookie**，天然无浏览器 CSRF 面；允许同一管理员多 IP/多会话并存，改密重启即失效）。
- 安全响应头：`X-Content-Type-Options: nosniff`、`X-Frame-Options: SAMEORIGIN`、`Referrer-Policy: no-referrer`、`Content-Security-Policy: default-src 'self'; connect-src 'self'; ...`；API 响应带 `Cache-Control: no-store`。

### 3.1 公开接口与认证

| 方法 / 路径 | 说明 |
|---|---|
| `GET /api/status` | 规范化状态 + `stats` 原始对象 + `net` 宿主机上行带宽（`unit: "bytes_per_second"`）；文件缺失返回 200 + `available:false` |
| `GET /server-status.json` | 直接透传 `server-status.json` 原文 |
| `GET /api/events?afterTs=<ms>&limit=<1..300>` | 实时增量事件（`afterTs` 严格大于；已过滤含旧 `playerId` 与 `/login` 等指令聊天） |
| `GET /api/events/history` | 跨轮转历史倒序分页：`fromTs`/`toTs`（闭区间）、`type`、`keyword`（仅查公开字段）、`limit`（最大 200）、`cursor`（`ts < cursor`） |
| `GET /api/catalog` | 扫描 `external-cp/` 与 `config/maps/` 列表（含作者/标题/简介元数据） |
| `GET /api/health` | **按登录态脱敏**：未登录仅返回 `ok/serverTime/version`；带有效 Bearer 才返回路径、队列数、会话数、控制台探测状态、日志与带宽状态 |
| `POST /api/login` / `POST /api/logout` | 登录获取 Bearer token / 注销当前会话（`logout` 空 body 需带 `Content-Length: 0`） |
| `POST /api/upload` | `multipart/form-data` 公开投稿（`kind=dp\|map`、`title`、`author`、`desc`、`replaceOf`、`file`）；超额 `409 QUEUE_FULL`、超体积 `413` |

### 3.2 管理员接口（需 Bearer）

| 方法 / 路径 | 说明 |
|---|---|
| `GET /api/review/list?kind=dp\|map` | 待审队列、已处理历史与队列配额 |
| `POST /api/review/approve` | `{id, reload?:bool}`：原子落盘至 `external-cp/` 或 `config/maps/`；同扩展名 `replaceOf` 原地替换，换扩展名则落新删旧，未指定撞名自动加 ` (2)` |
| `POST /api/review/reject` | `{id, reason}`（reason 必填）：标记拒绝并按配置删除暂存文件 |
| `DELETE /api/item` | `{kind:"dp"\|"map", name, unload?:bool}`：删除磁盘文件与元数据，可选刷新地图列表或卸载 DP |
| `GET /api/logs` / `tail` / `download` | 白名单日志（`log-<N>.txt`、`console-*.log`、`last.log`、`restart-supervisor.log`，禁止递归与穿越）；`download` 支持单段 `Range` 与浏览器直链 `?token=`（勿分享链接），流式异步写泵，满并发 `429 BUSY`、超频 `429 RATE_LIMITED`、超 50MB `413` |

---

## 4. 安全加固规范与设计要点

| 防护面 | 现行实现 |
|---|---|
| **无阻塞登录防爆破** | 每次登录**先校验密码**，密码正确立即放行（防止攻击者持续错登把管理员锁在门外）；仅对错误密码施加 per-account 指数退避（`0/2/4…300s`），配合全局失败慢速模式（60 分钟 ≥40 次 → 每 5 秒放行 1 次）与常态 `loginMinIntervalMs=120ms` 控制 PBKDF2 CPU 成本。所有 429 使用统一文案，不暴露内部限频原因 |
| **慢速连接与内存防护** | `Content-Length` 超 `maxRequestBytes` 在头部阶段立即 `413` 拒收；读取启用「总超时 30s + 单次读停滞 200ms + 最低速率 2KB/s + 全局停滞预算（每秒最多 400ms 等早期未满 64KB 慢连接）」四重保护；按 `Content-Length` 精确分配单缓冲 + 零拷贝 `(offset, count)` multipart 切片（上限 40 段）+ 分块流式落盘 |
| **文件类型与文件名校验** | 除扩展名白名单外执行**结构级校验**：`.zip` 校验 `PK` 头及尾部 64KB EOCD；`.json` 必须可完整解析；`.hjson/.json5` 校验首非空为 `{`/`[`；`.msav` 必须可解压且明文为 `MSAV` + 合法版本号。拒绝路径分隔符、控制字符、`"\|<>?*: `（含 NTFS ADS `:`）及 Windows 保留名（`CON/PRN/AUX/NUL/COM1-9/LPT1-9`） |
| **公开事件流脱敏** | 游戏侧 `liveEventFeed.kts` 不再写 `playerId`；Web API 自动过滤旧事件文件中残留 `playerId` 的记录，并隐藏 `/login`、`/register` 等指令聊天；`keyword` 检索仅匹配公开字段 |
| **路径穿越与敏感文件隔离** | 静态路由屏蔽 `server/`、`web-data/`、`web-config.*`、`start-web.*`，拒绝 `..`/隐藏段/非白名单扩展名及尾斜杠（`/index.html/` → 404）；配额只对真正受理的上传/下载计数，避免垃圾 4xx 耗尽正常用户额度 |
| **噪声与可选控制台降级** | 客户端提前断开（`IOException/HttpListenerException/ObjectDisposedException`）静默计数到 `/api/health.clientDisconnects`；真异常按同签名 60s 限频写 `portal.log`；游戏控制台（6859）为**可选增强**，不可达时审核照常成功，返回中性 `reloadHint` 与 `optional:true`，不记 WARN/ERROR |
| **CSP 与第三方请求** | `connect-src 'self'` 会拦截 `quote.js` 对远程一言 API 的请求并静默降级至本地 1484 条语料，默认不向第三方发起请求 |

---

## 5. 审核与游戏侧联动

- **地图审核（默认触发刷新）**：落盘至 `mdtserver/config/maps/` 后默认向控制台发送 `maps` 触发 `maps.reload()`，无需重启、不影响当前对局。
- **DP 审核（默认不自动热重载）**：DP 热重载（`externalcp load "<文件名>"`）会重建 Data Assets 并触发全服世界重同步，故 `reload.dpOnApprove` 默认为 `false`；建议低人数时手动在后台勾选或游戏内执行 `/cp load`。
- **实时事件流轮转**：`liveEventFeed.kts` 单文件约 2MB 轮转并保留 12 份历史（`live-events.1..12.jsonl`），建议保持 `events.historyMaxFiles = keepRotatedFiles + 1`。

---

## 6. 后端维护注意与运行数据边界

- **编码铁律**：所有 `.ps1` 必须保存为 **UTF-8 with BOM**（Windows PowerShell 5.1 要求）；`.bat` 保持纯 ASCII + CRLF。
- **PowerShell 陷阱**：返回集合的函数使用 `return ,$list` 防止空集合被管道展开为 `$null`；`afterTs` 必须按 `[long]` 解析；查询参数含中文需从 `RawUrl` + `[Uri]::UnescapeDataString` 解码（避免 `QueryString` 按 ANSI 乱码）；大文件下载必须走 `portal-server.ps1` 的异步 `BeginWrite/EndWrite` 写泵，禁止在主循环同步阻塞写。
- **运行数据不入库**：`stats-web/web-data/` 与生产 `web-config.json` 均不得提交到仓库或打包分发（已列入 `.gitignore` 与 `updater` 保护清单）；修改 `assets/**` 后需手动递增 HTML 中的 `?v=` 版本号。

---

## 7. 已知限制与排障速查

**已知限制**：
- 会话与登录节流均为内存态，重启 Web 进程后重置；
- 单线程请求循环下，日志下载已异步化，但单次 25MB DP 上传仍会在接收阶段串行占用主循环；
- 公开投稿无验证码，恶意占满待审队列需管理员拒绝清理；默认密码仅在横幅红色告警、不阻断启动；门户自身无内置 TLS（公网由前置反代提供 HTTPS，且反代可覆写 http.sys 内核注入的 `Server: Microsoft-HTTPAPI/2.0` 头）。

| 现象 | 排查处理 |
|---|---|
| 启动报端口占用或 URL 注册冲突 | `netstat -ano \| findstr :8090` 查占用；绑定 `0.0.0.0` 前需管理员执行 `start-web.ps1 -InstallAcl` |
| `/api/status` 返回 `available:false` | 检查游戏服 `serverStats.kts` 是否启用及 `web-config.json` 的 `statsJson` 路径 |
| `/api/events` 为空或历史查不到 | 检查 `wayzer/ext/liveEventFeed` 状态与 `eventsJsonl` 路径；主文件轮转瞬间暂缺属正常 |
| `/api/health.console.reachable=false` | **正常现象**：生产不开 6859 控制台时自动降级，审核文件照常落盘 |
| 管理员登录失败 | 检查 `admin.passwordHash`（非空优先于明文）并重启 Web；或按 429 `Retry-After` 等待 |
| `/api/logs` 返回 401 / 429 / 413 | 401 需管理员登录；429 `BUSY`/`RATE_LIMITED` 对应并发上限（2）或频控（5 次/5 分钟）；413 超 50MB |
| `/api/status.data.net.available=false` | 宿主机网卡采样失败，可显式配置 `bandwidth.interface` 或设 `bandwidth.enabled=false` |
