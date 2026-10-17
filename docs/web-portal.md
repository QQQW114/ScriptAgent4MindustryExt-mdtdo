# 服务器门户 Web（stats-web）设计与使用说明

> 更新记录：2026-10-17 首次实现（服务器状态网站升级为「服务器门户」：欢迎页/状态页 + 游戏内实时事件流 + DP/地图上传审核 + 管理员后台）。
> 后端实现：`stats-web/server/**` + `stats-web/start-web.ps1`；前端实现：`stats-web/index.html`、`upload.html`、`admin.html`、`assets/**`；
> 游戏侧事件流：`wayzer/ext/liveEventFeed.kts`（输出 `config/stats/live-events.jsonl`）；
> 状态统计仍由 `wayzer/ext/serverStats.kts` 输出 `config/stats/server-status.json`（口径见 [server-status-stats.md](server-status-stats.md)）。

## 1. 总览

门户是一个**独立于游戏服务器的进程**（Windows PowerShell HttpListener，零外部依赖），提供：

| 能力 | 入口 | 数据来源 |
| --- | --- | --- |
| 欢迎页 + 服务器状态卡 + 折线图 + 实时消息流 | `/` | `/api/status`、`/api/events` |
| 状态统计旧数据入口（兼容旧页面/脚本） | `/server-status.json` | `config/stats/server-status.json` 原文 |
| DP / 地图上传（公开，无需登录） | `/upload.html` → `POST /api/upload` | 写入 `stats-web/web-data/` 暂存 |
| 管理员登录与审核后台 | `/admin.html` → `/api/login`、`/api/review/*`、`/api/item` | 审核通过后落盘到服务器 DP/地图目录 |
| 现有 DP / 地图列表 | `/api/catalog` | 直接扫描 `external-cp/`、`config/maps/` |
| 游戏内事件流 | `/api/events` | `config/stats/live-events.jsonl`（游戏侧脚本异步写入） |

关键边界：

- **原状态页能力保留**：原单文件状态页的服务器状态卡、近 14 天/24 小时折线图等已并入门户首页（`index.html` + `assets/js/*`），
  `/api/status` 的 `stats` 字段与 `/server-status.json` 旧入口都保持可用；旧单文件页面不再单独存在；
- **不占用游戏服务器主线程**：Web 是独立进程；游戏侧只做「事件入队 + IO 协程批量写 JSONL」；
- **Web 可独立启停**：服务器统计/事件流关闭时，页面显示最后一次数据（`available:false` 时前端给出提示）；
- **审核通过前上传文件只存在于隔离目录**，不会进入服务器可加载目录；审核通过后做同卷「临时文件 + 原子替换」落位。

## 2. 组成与目录

```
stats-web/
├─ index.html              # 门户首页（欢迎页 + 状态 + 事件流 + 图表）
├─ upload.html             # 公开上传页（DP / 地图申请）
├─ admin.html              # 管理员登录 + 审核后台
├─ assets/                 # 前端 css/js/mock（全部本地，无外网 CDN）
├─ start-web.ps1           # 启动入口（兼容旧参数 -Bind/-Port/-StatsJson/-InstallAcl）
├─ start-web.bat           # Windows 双击入口（参数透传）
├─ web-config.json         # ★ 主配置（端口 / 管理员账号密码 / 目录 / 队列与大小限制 / 安全项）
├─ web-config.ps1          # 旧版配置（仅在没有 web-config.json 时兜底，兼容老部署）
├─ server/                 # 后端（PowerShell 5.1 兼容，零外部依赖）
│  ├─ portal-core.ps1      # 配置合并/校验、日志、时间、密码哈希、会话、限频
│  ├─ portal-http.ps1      # 响应/请求体/multipart/上传魔数校验/静态文件白名单
│  ├─ portal-store.ps1     # 提交记录、暂存文件、目录清单、审核/删除动作
│  ├─ portal-console.ps1   # 游戏控制台 Socket 桥（非阻塞，异步收集回显）
│  ├─ portal-api.ps1       # 全部 API 路由与处理
│  └─ portal-server.ps1    # 监听循环、路由分发、启动横幅
└─ web-data/               # ★ 运行数据（自动创建；不应提交到仓库，见 §9）
   ├─ submissions.json     # 审核提交记录（含状态/原因/审核人）
   ├─ catalog-meta.json    # 已入库文件的作者/介绍等展示元数据
   ├─ uploads/<id>/…       # 待审文件的隔离暂存目录（审核通过/拒绝后清理）
   ├─ audit.log            # 审计日志（上传/登录/审核/删除等动作 JSONL）
   ├─ console-actions.log  # 游戏控制台桥命令与回显 JSONL（热重载/地图刷新结果）
   └─ portal.log           # 运行日志（>5MB 轮转为 portal.1.log）
```

相关游戏侧文件：

- `mdtserver/config/scripts/external-cp/`：服务器「DP 列表」= 可加载的 v159 数据资产包（`.zip/.json/.hjson/.json5`），语义见 [v159-data-assets-hot-reload.md](v159-data-assets-hot-reload.md)；
- `mdtserver/config/maps/`：服务器自定义地图目录（`.msav`，`maps.customMaps()` 来源）；
- `mdtserver/config/stats/server-status.json`：状态统计输出；
- `mdtserver/config/stats/live-events.jsonl`：实时事件流输出（由 `wayzer/ext/liveEventFeed.kts` 写入）。

## 3. 启动 / 停止 / 公网监听

### 3.1 启动

```powershell
# 最简单：双击 stats-web\start-web.bat
# 或命令行（默认读取 stats-web\web-config.json，监听 127.0.0.1:8090）：
cd mdtdo\stats-web
.\start-web.ps1

# 临时换端口 / 换统计文件 / 指定配置文件（命令行优先级最高）：
.\start-web.ps1 -Port 8091
.\start-web.ps1 -Bind 0.0.0.0 -Port 8090
.\start-web.ps1 -StatsJson "D:\mdt\mdtserver\config\stats\server-status.json"
.\start-web.ps1 -Config ".\web-config-local.json"
```

- 启动横幅会打印实际监听地址、配置路径、数据/统计/事件/DP/地图目录、管理员账号、上传限制；
  使用默认密码或默认 `session.secret` 时会打印**安全警告**；
- 停止：运行窗口 `Ctrl+C`（或在后台任务中结束该 powershell 进程）；
- `start-web.bat` 只做参数透传与出错暂停，保持 ASCII，避免 cmd 代码页问题。

### 3.2 公网 / 局域网访问（`bind=0.0.0.0`）

HttpListener 监听非 localhost 地址需要先注册 URL 保留（管理员执行一次）：

```powershell
# 管理员 PowerShell：注册 URL 保留（换端口后需重新执行）
.\start-web.ps1 -InstallAcl -Port 8090
# 然后普通权限启动
.\start-web.ps1 -Bind 0.0.0.0 -Port 8090
```

最后在 Windows 防火墙 / 云安全组放行端口。**默认只监听 `127.0.0.1`，不要在不改默认密码的情况下直接暴露公网。**
若前面有 Nginx/反代，建议保持门户监听 127.0.0.1，由反代提供 HTTPS（反代场景可设 `security.trustProxyHeaders=true` 以取 `X-Forwarded-For` 作为限频维度）。

### 3.3 配置文件（`web-config.json`，主配置）

相对路径统一以 **stats-web 目录**为基准；改完重启生效。字段与默认值：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `bind` / `port` | `127.0.0.1` / `8090` | 监听地址/端口（`-Bind`/`-Port` 可临时覆盖） |
| `statsJson` | `../mdtserver/config/stats/server-status.json` | 状态统计 JSON 路径 |
| `eventsJsonl` | `../mdtserver/config/stats/live-events.jsonl` | 实时事件流 JSONL 路径（缺失时返回空列表） |
| `dataDir` | `web-data` | 运行数据目录（提交记录/暂存/日志） |
| `admin.username` | `admin` | 管理员账号 |
| `admin.password` | `ChangeMe_123456` | 明文管理员密码（**必须立即修改**） |
| `admin.passwordHash` | `""` | 非空时**优先于** `admin.password`；用 `start-web.ps1 -GenPasswordHash "新密码"` 生成（PBKDF2） |
| `admin.sessionHours` | `12` | 登录会话有效期（小时） |
| `session.secret` | 占位串 | 会话/未来扩展用密钥，**建议改随机串** |
| `upload.maxFileBytes` | `10485760` | 单文件上限（10MB） |
| `upload.maxRequestBytes` | `12582912` | 整个请求体上限（10MB + multipart 开销），超限 413 |
| `upload.queueLimitPerKind` | `10` | **每种类型**（DP/地图各自）待审队列上限，满 → `QUEUE_FULL` |
| `upload.historyLimit` | `200` | 已处理记录元数据保留条数（pending 永远保留） |
| `upload.allowedDpExtensions` | `.zip/.json/.hjson/.json5` | DP 允许扩展名（另做文件头校验） |
| `upload.allowedMapExtensions` | `.msav` | 地图允许扩展名（另做 zlib/gzip 头校验） |
| `upload.titleMaxChars`/`authorMaxChars`/`descMaxChars` | `200/100/4000` | 表单字段长度上限 |
| `upload.deleteRejectedPayloads` | `true` | 拒绝时删除暂存文件 |
| `rateLimit.loginFailuresPerWindow`/`loginWindowMinutes` | `5` / `5` | 同 IP 登录失败次数窗口，超限 429 |
| `rateLimit.uploadsPerWindow`/`uploadWindowMinutes` | `5` / `10` | 同 IP 上传频率限制 |
| `dirs.dp` | `../mdtserver/config/scripts/external-cp` | DP 目录 |
| `dirs.maps` | `../mdtserver/config/maps` | 地图目录 |
| `console.enabled` | `true` | 是否允许 Web 向游戏控制台发送命令（审核后热重载/刷新地图列表） |
| `console.host`/`port`/`waitSeconds` | `127.0.0.1` / `6859` / `30` | 控制台 Socket（`server.properties` 的 `socketInput=true`） |
| `reload.dpOnApprove` | `false` | 审核通过 DP 后默认是否 `externalcp load`（**默认关**：热重载会重建内容并同步世界，风险见 §6.3） |
| `reload.mapListOnApprove` | `true` | 审核通过地图后默认是否执行控制台 `maps`（触发 `maps.reload()`，安全） |
| `reload.mapListOnRemove` | `true` | 删除地图文件后是否刷新服务器地图列表 |
| `reload.unloadOnRemove` | `false` | 删除 DP 文件后是否尝试游戏侧 `externalcp unload` |
| `security.trustProxyHeaders` | `false` | 是否信任 `X-Forwarded-For`（仅反代后开启） |
| `security.maxPendingConsoleCommands` | `8` | 同时排队等待回显的控制台命令上限（防堆积） |

> 兼容与升级：旧部署只有 `web-config.ps1`（`$WebBind/$WebPort/$WebStatsJson`）时，`start-web.ps1` 会
> **自动迁移**成 `web-config.json` 并把旧文件改名备份，见 §3.4；老部署的 4 个文件
> （index/start-web.ps1/start-web.bat/web-config.ps1）替换为新版文件后可以直接启动。

### 3.4 从旧版部署升级（自动迁移 `web-config.ps1` → `web-config.json`）

生产旧部署的 Web 可能已经配置好（`web-config.ps1` 里 `$WebBind=0.0.0.0`、自定义端口，公网可访问），
升级到门户版时**不需要手工改配置**：把新版 `stats-web/` 文件替换上去直接启动即可，启动时会自动迁移。

**行为（`server/portal-core.ps1` 的 `Invoke-PortalLegacyConfigMigration`）：**

1. 若 `web-config.json` **不存在**、但发现旧版 `web-config.ps1`：
   - 读取 `$WebBind` / `$WebPort` / `$WebStatsJson`（缺失变量用旧版默认 `127.0.0.1:8081`），
     生成 `web-config.json`（其余项用默认值），启动日志打印
     `[升级] 已把旧配置迁移到 web-config.json：bind=… port=… statsJson=…`；
   - 旧文件改名为 **`web-config.ps1.bak-<yyyyMMdd-HHmmss>`**（同目录备份）；
   - 打印红色警告：管理员账号/密码为默认值（`admin / ChangeMe_123456`），**立即修改**；
2. **幂等**：迁移后 `web-config.ps1` 已改名，后续启动只读 `web-config.json`，不会重复迁移/重复备份；
3. 若 `web-config.json` **已存在**：以新配置为准，忽略旧文件并打印提示（旧文件不生效，可删除）；
4. **失败回退**：迁移过程（读取/解析/写文件）出错时打印警告，并**回退用旧配置的值继续启动**，不影响生产可用性；
   旧文件解析异常时会尝试按文本提取 `$WebBind/$WebPort/$WebStatsJson` 继续迁移（尽量保住生产监听地址/端口）；
5. **CLI 参数仍最终覆盖**：`-Bind` / `-Port` / `-StatsJson` 优先级高于配置文件；`-InstallAcl` 行为不变；
6. **只在默认配置路径上迁移**：用 `-Config` 指定了自定义配置路径时跳过自动迁移（避免误改仓库内旧配置），
   但启动仍会回退读取旧 `web-config.ps1` 的值。

**生产升级步骤建议：**

1. 备份线上 `stats-web/`（至少 `web-config.ps1`、统计数据与 `web-data/` 若存在）；
2. 用新版 `stats-web/` 整个目录替换，但**注意配置共存规则**：
   - 旧部署没有 `web-config.json` → **不要把新版包里的默认 `web-config.json` 一起放进去**（若整目录覆盖时已带入，
     请先删除它），保留线上旧 `web-config.ps1`，首次启动会自动迁移；
   - 若旧部署已有自己的 `web-config.json`（曾手工迁移过）→ 保留它，不要用新版默认配置覆盖；
   - `web-data/` 是运行数据，替换时保留（旧版没有则忽略）；
3. 启动 `start-web.ps1`（如果旧版 `.bat` 带参数如 `-Bind 0.0.0.0 -Port XXXX`，参数仍会生效并最终覆盖配置——
   迁移日志里的 bind/port 应与之一致；两者不一致时以 `.bat` 参数为准）；
4. 确认启动日志出现 `[升级] 已把旧配置迁移到…` 且监听地址/端口与旧站一致，访问 `/`、`/server-status.json` 正常；
   迁移动作还会写入 `web-data/audit.log`（`config_migrated`），可事后追溯；
5. **立即修改默认管理员密码**（`web-config.json` 的 `admin.password`，或 `-GenPasswordHash` 后用 `admin.passwordHash`），
   并建议修改 `session.secret`；之后按 §3.2 确认公网访问/URL 保留仍正常；
6. 确认无误后可删除 `web-config.ps1.bak-*` 备份（建议保留一段时间）。

> ⚠ **重要**：若升级时把新版目录里的**默认 `web-config.json`** 也复制到旧部署，门户会优先使用该默认配置
> （`127.0.0.1:8090`）而**不会自动迁移**旧 `web-config.ps1`，公网访问会失效。旧部署升级请按第 2 步删除/不要复制
> 该默认文件；启动后务必确认日志出现迁移记录（或显式保留旧部署自己的 `web-config.json`）。

### 3.5 生成密码哈希（推荐替代明文密码）

```powershell
.\start-web.ps1 -GenPasswordHash "你的新密码"
# 输出示例： pbkdf2$sha256$150000$<saltB64>$<hashB64>
```

把输出填入 `web-config.json` 的 `admin.passwordHash` 并把 `admin.password` 置空即可。
哈希格式 `pbkdf2$<算法>$<迭代>$<salt>$<hash>`，默认 PBKDF2-SHA256/150000 次；旧 .NET 环境自动回退 PBKDF2-SHA1/200000 次并标注算法。

## 4. HTTP API 契约

统一约定：

- 成功：`{ "ok": true, "data": … }`；失败：`{ "ok": false, "error": { "code": "…", "message": "…" } }`，HTTP 状态码同步反映错误类别；
- 鉴权：请求头 `Authorization: Bearer <token>`（token 来自 `POST /api/login`）；**不使用 Cookie**，因此不存在浏览器自动携带凭据的 CSRF 面；
- 除 `POST /api/upload`、`/api/login` 外的管理接口都需要 Bearer；
- 响应均带 `X-Content-Type-Options: nosniff`、`X-Frame-Options: SAMEORIGIN`、`Referrer-Policy: no-referrer`；API 响应 `Cache-Control: no-store`。

### 4.1 公开只读

| 方法/路径 | 说明 |
| --- | --- |
| `GET /api/status` | 状态（规范化）：`available,updatedAt,enabled,online,maxPlayers,map,mode,wave,tps,uptimeSeconds,onlineByRank,stats`。`maxPlayers` 当前无可靠来源固定 `null`；`stats` 为 `server-status.json` 完整原始对象（图表直接用）。文件缺失时 200 + `available:false`，不报 500 |
| `GET /api/events?afterTs=<epochMillis>&limit=<1..300>` | 实时事件：`{events:[{ts,type,text,player,playerId?}],lastTs,available,total}`。`afterTs` 语义为**严格大于**；不传 `afterTs` 时返回最近 `limit` 条（默认 100）；坏行/半行/文件缺失均容错 |
| `GET /api/catalog` | 现有列表：`{dp:[{name,file,size,updatedAt,author,desc,title}],maps:[...]}`；`name`=不含扩展名显示名，`file`=真实文件名；历史文件无审核元数据时 `author/desc/title` 为 `null` |
| `GET /api/health` | 运维自检：数据/事件/目录可用性、`dpCount/mapCount`、`pendingDp/pendingMap/queueLimit`、会话数、待收尾控制台命令数、版本 |
| `GET /server-status.json` | **旧入口兼容**：直接回传 `server-status.json` 原文（旧页面/脚本可继续使用） |

### 4.2 认证

| 方法/路径 | 请求 | 响应 |
| --- | --- | --- |
| `POST /api/login` | `{"username":"admin","password":"..."}` | `{token,expiresAt,username}`；失败 401 `BAD_CREDENTIALS`；同 IP 失败超限 429 `RATE_LIMITED` |
| `POST /api/logout` | （空 body，浏览器/客户端需带 `Content-Length: 0`） | `{loggedOut:true}` |

- 会话保存在**内存**（web 进程重启即全部失效，前端遇 401 会自动回登录页）；
- **同一管理员账号允许多 IP / 多会话同时登录**（token 不绑定 IP，也不会互相踢下线）；
- token 为 32 字节随机数的 hex，到期时间见 `expiresAt`（epoch 毫秒）。

### 4.3 上传与审核（管理接口需 Bearer）

| 方法/路径 | 请求 | 成功响应 / 主要错误 |
| --- | --- | --- |
| `POST /api/upload` | `multipart/form-data`：`kind=dp\|map`、`title`（介绍/标题，必填）、`author`（必填）、`desc`（可选）、`replaceOf`（可选，更新现有文件名/显示名）、`file`（必填，单个） | `{id,kind,status:"pending",title,author,fileName,size,queue:{limit,pending},message}`；`QUEUE_FULL`(409)、`FILE_TOO_LARGE`(413)、`BAD_TYPE`(400)、`BAD_REQUEST`(400)、`RATE_LIMITED`(429) |
| `GET /api/review/list?kind=dp\|map` | — | `{kind,items:[{id,kind,title,author,desc,replaceOf,fileName,size,createdAt,createdAtText,status}],history:[最近已处理],queue:{limit,pending}}` |
| `POST /api/review/approve` | `{id, reload?:bool}` | `{id,kind,status:"approved",finalName,replaced,reload:{dispatched,requested,command?,note?}}`；`reload` 省略时按配置默认（DP 默认 false、地图默认 true） |
| `POST /api/review/reject` | `{id, reason}`（reason 必填） | `{id,status:"rejected",reason}`；拒绝的暂存文件按配置删除 |
| `DELETE /api/item` | `{kind:"dp"\|"map", name, unload?:bool}` | `{kind,name,file,deleted,reload:{…}}`；`name` 可为完整文件名或去掉扩展名的显示名 |

错误码与 HTTP 状态：`NOT_FOUND` 404、`ALREADY_REVIEWED` 409、`BAD_REQUEST` 400、`PAYLOAD_MISSING` 410、
`RATE_LIMITED` 429、`UNAUTHORIZED`/`SESSION_EXPIRED` 401、`INTERNAL` 500。

## 5. 认证、会话与安全设计

| 风险 | 措施 |
| --- | --- |
| 默认口令 | 配置文件内置默认账号+密码用于首次启动；启动横幅**红色警告**要求立即修改；支持改用 PBKDF2 `passwordHash` |
| 会话 | 内存会话表 + 32 字节随机 token；`expiresAt` 过期即失效；多 IP/多会话允许（需求如此）；日志记录登录来源 IP |
| 暴力破解 | 同 IP 登录失败限频（默认 5 次/5 分钟 → 429）；成功登录清零 |
| CSRF | 不使用 Cookie，管理 API 只认 `Authorization: Bearer`，跨站表单无法伪造 |
| 上传体积 | `Content-Length` 预检 + 流式读取二次上限（`maxRequestBytes`），超限 413；单文件再校验 10MB |
| 类型伪造 | 扩展名白名单 + **文件头校验**：`.zip` 必须 `PK` 头；`.json/.hjson/.json5` 必须是 UTF-8 对象/数组（允许 BOM）；`.msav` 必须 zlib（CMF/FLG 校验）或 gzip 头 |
| 路径穿越 | 上传文件名只取末段并清洗控制字符；落盘路径做「必须位于目标目录内」校验；静态文件按 URL 段拒绝 `..`、`.`、隐藏段与白名单扩展名，并再次校验绝对路径在 web 根内 |
| 敏感文件泄漏 | 静态路由屏蔽 `server/`、`web-data/`、`web-config.json`、`web-config.ps1`、`start-web.*`；仅服务白名单扩展名（html/css/js/svg/png/jpg/ico/字体/音视频等） |
| 暂存隔离 | 上传先写 `web-data/uploads/<提交id>/<安全文件名>`，审核通过才复制到服务器目录；拒绝即删除 |
| 落盘原子性 | 先写目标目录内 `.portal-tmp-*`，再 `File.Replace`（同卷原子替换，失败回退 Move）；替换旧文件失败只记日志 |
| 队列滥用 | 每种类型待审上限 10（满 → `QUEUE_FULL`）且同 IP 上传频率限制（默认 5 次/10 分钟） |
| XSS | 后端只透传游戏颜色标记原文并做 JSON 编码；前端先 HTML 转义再把 `[red]/[#RRGGBB]/[]` 白名单标记转成 `<span style>`，未知标记按文本显示（详见前端实现与自测） |
| 日志 | `audit.log` 记录动作/操作者/IP/明细；`portal.log` 运行日志 5MB 轮转；敏感信息（密码/token 明文）不落日志（token 只记尾 8 位） |

> 部署建议：门户默认只监听 127.0.0.1；公网请先改默认密码、再考虑反代 HTTPS。上传目录与 `web-data/` 属于运行数据，不进入版本库。

## 6. 审核流程

### 6.1 提交（公开）

1. 玩家打开 `/upload.html`，选择 DP 或地图，填写介绍/标题、作者、可选「是否为现有某个 DP/地图的更新」（`replaceOf`），选择文件；
2. 前端先做 10MB 与扩展名预检；后端再按 §5 做完整校验；
3. 通过后写入 `web-data/uploads/<id>/` 并在 `submissions.json` 记 `pending`；返回受理号与队列进度；
4. 任一类型待审满 10 个时新上传返回 `QUEUE_FULL`（提示稍后再试）。

### 6.2 审核通过（管理员）

1. `POST /api/review/approve {id}`；
2. 目标目录 = `dirs.dp` 或 `dirs.maps`；文件复制到目标目录临时文件后原子落位；
3. **替换语义**：
   - `replaceOf` 命中的现有文件且扩展名一致 → 用原文件名原地替换（服务器加载名保持稳定）；
   - 扩展名变化（如 `.zip` → `.json`）→ 新文件用上传文件名落位，并删除旧文件（扩展名决定加载方式，无法同名替换）；
   - 未指定 `replaceOf` 但同名文件已存在 → 自动加 ` (2)`、` (3)` 后缀避免覆盖；
4. 记录作者/介绍到 `catalog-meta.json`，`/api/catalog` 与后台列表展示；
5. 可选的游戏侧重载（见 §6.3/§6.4），**失败只写日志，不影响审核状态**。

### 6.3 DP 审核通过后的热重载（可选，默认关）

- 触发方式：`reload=true` 或配置 `reload.dpOnApprove=true`；
- 动作：向游戏控制台 Socket（`127.0.0.1:6859`）发送 `externalcp load "<文件名>"`；
- 为什么默认关：DP 热重载会重建当前全部 Data Assets 并触发世界重同步（画面/内容会刷新），**建议低人数时**由管理员在后台勾选执行，
  或审核后到游戏内用 `/cp load <文件名>` 手动执行；控制台输出写入 `web-data/console-actions.log`；
- 失败处理：连接失败/超时只记录日志（`reload.dispatched=false` 或回显为空），审核结果仍为 `approved`。

### 6.4 地图审核通过后的列表重载（默认开）

- 地图文件必须落在 `mdtserver/config/maps/`（`maps.customMaps()` 来源）；
- Web 向控制台发送根指令 **`maps`**（不带 `@internal` 参数）：`wayzer/cmds/mapsCmd.kts` 的 `maps` 命令会走 `MapRegistry.searchMaps()`，
  触发 `wayzer/maps.kts` 里的 `maps.reload()`，服务器内存地图列表随即重载，**无需重启、无需换图**；
- 控制台输出同样写入 `console-actions.log`；失败只记录，不影响审核结果；
- 若审核时传入 `reload:false` 则不触发，需管理员稍后在游戏内执行 `/maps`（或控制台 `maps`）让新地图出现；
- 删除地图文件后默认也会执行一次 `maps` 让列表移除该图（`reload.mapListOnRemove=true`）；
  注意「当前正在运行的地图」已加载在内存中，删文件不影响本局，下次换图时生效。

### 6.5 移除现有 DP / 地图

- 后台「现有列表」→ 移除，或 `DELETE /api/item`：删除服务器目录中的文件并清理 `catalog-meta.json`；
- DP 删除默认**不**自动卸载（避免误踢正在使用该 DP 内容的玩家），可显式传 `unload:true` 或在游戏内 `/externalcp unload <文件名>`；
  注意：删文件后该 DP 不会再被新加载，但若当前局已加载，运行态内容仍保留直到卸载/换图；
- 地图删除同 §6.4：文件立即消失，服务器内存列表由 `maps` 刷新，当前局不受影响。

### 6.6 控制台桥的可靠性说明

游戏控制台 Socket 是 Mindustry `ServerControl` 的顺序单客户端实现（一次只服务一个连接，输出写到当前连接）。
因此 Web 的触发是**尽力而为**：

- 发送命令是毫秒级非阻塞操作，Web 请求立即返回 `reload.dispatched`；
- 回显在后续请求循环中非阻塞收集（空闲 3 秒或超时后收尾），结果只写 `console-actions.log`；
- 若同时有其它工具/人占用控制台连接，命令可能排队到本项目连接被接受之后才执行、回显可能为空或混入其它客户端的输出；
  **这只影响日志可读性，不影响审核结果**；
- 队列上限 `security.maxPendingConsoleCommands`（默认 8），超出直接拒绝触发并记录原因。

## 7. 游戏内实时事件流

### 7.1 数据契约（游戏侧写入 → Web 只读）

- 文件：`mdtserver/config/stats/live-events.jsonl`（UTF-8，append-only，每行一个 JSON 对象，无 BOM）；
- 字段：`{"ts":<epochMillis>,"type":"chat|join|leave|vote_start|vote_pass|vote_fail|map_change|system","text":"<Mindustry 原始颜色标记文本>","player":"<显示名>","playerId":"<可选 uuid>"}`；
- `ts` **严格递增**（同毫秒自动 +1），Web 端 `afterTs` 为「严格大于」，保证增量拉取不丢事件；
- `text` 保留 `[red]` / `[#RRGGBB]` / `[]` 等颜色标记原文，脚本只做 JSON 转义；渲染与 HTML 转义在前端完成；
- 单文件约 2MB 轮转到 `live-events.1.jsonl`（只保留一份旧文件）；每行 `text` 截断 500 字符。

### 7.2 读取与容错（Web 侧）

- `GET /api/events?afterTs=&limit=`：按 ts 升序返回；文件缺失 → `{events:[],lastTs:afterTs|0,available:false}`；
- 读取使用 `FileShare.ReadWrite|Delete`，允许游戏进程同时写入；
- 写入中途的半行、非法行、非 UTF-8 行全部跳过；读文件失败重试 3 次（间隔 40ms）后按空结果返回，不返回 500；
- 一次返回超过 `limit` 时会连同与第 limit 条同 ts 的事件一起返回，避免前端游标停在同一毫秒时丢事件。

### 7.3 事件来源（游戏侧 `wayzer/ext/liveEventFeed.kts`）

| type | 来源 | 说明 |
| --- | --- | --- |
| `chat` | `EventType.PlayerChatEvent` | `text` 为原始颜色标记聊天内容，`player` 发言人 |
| `join` / `leave` | `EventType.PlayerJoin` / `PlayerLeave` | 含 `playerId` |
| `vote_start` / `vote_pass` / `vote_fail` | `VoteEvent` 状态轮询（内存读取） | 不改 `vote.lib.kt` |
| `map_change` | 地图加载完成事件（`MapLoadFinishedEvent`） | 地图名/模式 |
| `system` | 脚本启停等 | 如「实时事件流已启动」 |

线程模型：事件监听只入内存队列；单一 IO 协程每 ~400ms 批量 append 一次（每批 ≤200 条），
写文件/轮转异常只限频记日志，不占游戏主线程。详见游戏侧脚本头注释与后续 `docs/live-events-feed.md`。

## 8. 后端模块要点（维护者视角）

| 模块 | 关键点 |
| --- | --- |
| `portal-core.ps1` | 默认配置 JSON 常量 + 深度合并（用户配置覆盖默认）；相对路径解析；生成/校验 PBKDF2；会话与限频内存表；原子写文件；日志/审计；**所有 .ps1 必须存为 UTF-8 with BOM**（Windows PowerShell 5.1 对无 BOM 中文脚本会按 ANSI 解析导致语法错误） |
| `portal-http.ps1` | HttpListener 响应封装；请求体上限读取；multipart 解析（Latin1 1:1 字节切片 + 文件名 UTF-8 还原）；上传魔数校验；静态文件白名单/穿越防护 |
| `portal-store.ps1` | `submissions.json` 原子读写；暂存文件；`catalog-meta.json`；审核/替换/删除；`File.Replace` 原子落位；历史记录裁剪 |
| `portal-console.ps1` | 非阻塞控制台桥：`BeginConnect`（600ms 上限）+ 写命令入队；每轮循环 `DataAvailable` 收集回显，空闲/超时收尾写 `console-actions.log` |
| `portal-api.ps1` | 路由 + 每个 API 的校验/错误码映射；上传处理器（限频→解析→类型/魔数→字段→队列→落暂存→记录） |
| `portal-server.ps1` | `BeginGetContext` + `WaitOne(500)` 主循环（顺带轮询控制台回显）；静态/API/旧入口路由；启动横幅与安全警告；Ctrl+C 收尾 |

实现注意（已踩坑）：

- PowerShell 返回 `ArrayList` 会被管道展开（空集合变 `$null`）→ 返回集合的函数用 `return ,$list`，
  调用方直接取 `.Count`，**不要**再套 `@(...)`（会变成单元素数组）；`$config`/`$Config` 变量名不要与
  `[string]` 类型参数同名（类型约束会把后续赋值强制转字符串）；
- `afterTs` 是 13 位毫秒时间戳，必须用 `long` 解析（`[int]` 会溢出回落默认 0 导致增量失效）；
- 静态文件路由用白名单扩展名 + 段检查，不直接拼接用户路径；
- 单进程单请求循环处理（小规模足够）；大型上传/慢客户端会串行占用，规模上来后需改成多线程/异步处理。

## 9. 运行数据与版本库

- `stats-web/web-data/` 是**运行数据**（提交记录、暂存上传、审计/运行日志），**不应提交到仓库**；
  建议在 `mdtdo/.gitignore` 增加 `stats-web/web-data/`（当前仓库尚未包含该条，见遗留 TODO）；
- `web-config.json` 含管理员凭据，属于本地运行配置：若仓库需要示例，请复制为 `web-config.example.json` 后再提交；
- `mdtserver/config/stats/`、`mdtserver/config/scripts/external-cp/`、`mdtserver/config/maps/` 按现有
  `.gitignore`/部署口径本就不入库或属运行数据，门户不改变这一点。

## 10. 自测记录（2026-10-17，开发机）

测试方式：Windows PowerShell 5.1 启动后端到临时端口（复用 `web-config.json` + 测试用覆盖配置），用 curl 走真实 HTTP；
游戏服务器全程未重启。

| # | 用例 | 结果 |
| --- | --- | --- |
| 1 | 启动：`start-web.ps1 -Port 8091` | ✅ 横幅/监听正常；`/`、`/upload.html`、`/admin.html`、`/assets/**` 200（MIME 正确） |
| 2 | `GET /api/status` | ✅ 200，`available=true, online/map/wave/tps` 与 server-status.json 一致，`stats` 完整 |
| 3 | 错误密码 | ✅ 401 `BAD_CREDENTIALS`；连续 5 次后第 6 次 429 `RATE_LIMITED`（限频生效，正确密码在窗口内也被拒，符合预期） |
| 4 | 正确登录 / 多会话 | ✅ 200 返回 token；同一账号连续两次登录拿到两个不同 token，均可调用管理 API（多 IP/多会话允许） |
| 5 | 登出 | ✅ `POST /api/logout` 后原 token 401 `SESSION_EXPIRED`，第二会话仍有效 |
| 6 | 未授权管理 API | ✅ 无 token / 伪造 token → 401 `UNAUTHORIZED`/`SESSION_EXPIRED`（approve/reject/delete 均验证） |
| 7 | 上传非法类型 | ✅ `.exe` → 400 `BAD_TYPE`；伪 zip（文件头错）→ 400 `BAD_TYPE`；非 JSON 内容 → 400 `BAD_TYPE` |
| 8 | 上传超 10MB | ✅ 11.5MB `/api/upload` → 413 `FILE_TOO_LARGE`；13MB 请求体（超过 maxRequestBytes）→ 413 |
| 9 | 合法上传 | ✅ DP zip、地图 `.msav`（S9100 副本改名）上传均 200 pending；中文文件名正常还原 |
| 10 | 队列上限 | ✅ 依次上传至 10 个 DP 待审，第 11 个 → 409 `QUEUE_FULL`（`queue:{limit:10,pending:10}`） |
| 11 | 审核通过（DP） | ✅ 文件落在 `external-cp/`，`/api/catalog` 出现该文件且带提交时作者/介绍；`reload:false` 不触发游戏侧动作 |
| 12 | 替换语义 | ✅ 同扩展名 `replaceOf` → 原文件名原地替换（大小变为新文件）；扩展名变化 → 新文件落位且旧文件被删除 |
| 13 | 拒绝 | ✅ `reason` 必填；拒绝后状态 `rejected`、暂存文件删除、`review/list.history` 可见原因 |
| 14 | 重复审核 | ✅ 已处理提交再次 approve → 409 `ALREADY_REVIEWED`；不存在的 id → 404 `NOT_FOUND` |
| 15 | 审核通过（地图） | ✅ 文件落在 `config/maps/`；默认触发控制台 `maps`（`reload.dispatched=true`），`console-actions.log` 收到地图列表回显；删除后同样触发刷新 |
| 16 | 移除现有文件 | ✅ `DELETE /api/item` 删除 DP/地图并清理 catalog 元数据；再次删除 404；DP 可带 `unload:true` 触发 `externalcp unload`（回显记录在 console-actions.log） |
| 17 | 事件流 | ✅ 读取真实 `live-events.jsonl`（含 system 事件）成功；用构造数据验证：颜色标记原文透传、坏行/半行跳过、`afterTs` 严格大于、无 afterTs 取最近 limit 条、limit 截断补同 ts、文件缺失 `available:false` |
| 18 | 安全：静态/穿越 | ✅ `/web-config.json`、`/server/portal-core.ps1` 404；编码穿越（`%2e%2e`、`..%2f`、`/assets/../web-config.json`）403/404 均被拦 |
| 19 | 方法限制 | ✅ `POST /api/status` → 405 `METHOD_NOT_ALLOWED` |
| 20 | 密码哈希配置 | ✅ `-GenPasswordHash` 生成哈希；纯 `passwordHash` 配置下新密码登录成功、默认密码/错误密码均 401 |
| 21 | 游戏健康（全程） | ✅ 未重启游戏服；测试后 `scriptdiag count`：共 161 脚本 / 加载 157 / 启用 152 / **失败 0**；6567、6859 正常监听；`sa listFailed` 无脚本失败；DP/地图目录恢复为原始 16 个 DP + 1 张 S9100.msav |

测试产生的提交记录与暂存文件已清理（`web-data/` 为运行数据，交付前删除；门户服务已停止）。

## 11. 已知限制 / 未验证项 / TODO

**未验证 / 无法在本机验证：**

- **真实多 IP 登录**：本机只能从 127.0.0.1 发起，已用「同账号双会话同时有效 + 代码不绑定 IP」验证语义，未做真实不同公网 IP 的联调；
- **DP 热重载端到端**：为避免影响运行中的游戏服，未在运行服实际执行「审核通过 + `externalcp load`」加载新 DP
  （控制台桥本身已用安全的 `maps` / `externalcp unload` 命令验证连通与回显；热重载失败回滚由游戏侧既有机制保证）；
- **地图审核后的实际出现在 `/maps`**：本机通过 `maps` 命令回显确认列表被重载（含临时测试地图条目出现/消失），
  但未做玩家客户端侧换图验收；
- 事件流的 `chat/join/leave/vote/map_change` 真实事件：测试期间服务器无人，只验证了读取/容错与 system 事件；
  真实事件由游戏侧脚本在有人活动时产生（契约已对齐）。

**已知限制：**

- 会话为内存态：重启 Web 后所有管理员需重新登录；
- 后端为单线程请求循环：大文件上传或慢客户端会短暂串行占用（社区小站规模足够，量大需改造）；
- 控制台桥是尽力而为（见 §6.6），且依赖 `server.properties` 的 `socketInput=true`（本机开发服已开启）；
- `/api/status` 的 `maxPlayers` 无可靠服务端来源，固定 `null`；
- 门户自身无 HTTPS（如需公网建议反代 + 保持 127.0.0.1 监听）。

**遗留 TODO：**

- `mdtdo/.gitignore` 增加 `stats-web/web-data/`（运行数据不入库）；如需提交配置示例，另建 `web-config.example.json`；
- `stats-web/README.md` 里仍是旧「单文件静态站」说明，需要按本文件重写（本次未改，避免与前端拆分冲突）；
- 可考虑：管理员登录双因素/来源 IP 白名单、审核结果站内通知、上传文件 SHA-256 去重与病毒扫描钩子、
  `maxPlayers` 从游戏侧配置读取、控制台桥改为持久单连接（减少竞争）、把 `submissions` 从 JSON 迁移到 SQLite（记录量大时）。

## 12. 故障排查

| 现象 | 处理 |
| --- | --- |
| 启动报「监听失败 / conflicts with an existing registration」 | 端口被占用：`netstat -ano | findstr :8090`，结束占用进程或 `-Port` 换端口；0.0.0.0 监听需先 `-InstallAcl` |
| 所有 API 正常但页面 404 | 确认前端文件在 `stats-web/` 根目录（`index.html`/`upload.html`/`admin.html`/`assets/`），扩展名在白名单内 |
| `/api/status` 返回 `available:false` | 游戏服未运行或 `serverStats.kts` 未输出：游戏内 `/serverstats status` 检查；文件路径见 `web-config.json statsJson` |
| `/api/events` 返回 `available:false` | `liveEventFeed.kts` 未加载或事件文件未生成：控制台 `scriptdiag state wayzer/ext/liveEventFeed`，`sa load wayzer/ext/liveEventFeed` |
| 审核后服务器 `/maps` 看不到新图 | 地图目录配置是否正确；`console.enabled` 是否开启、6859 是否监听；查看 `web-data/console-actions.log` 回显；也可游戏内手动 `/maps` |
| 审核通过 DP 后游戏内容没变化 | DP 热重载默认关闭（安全考虑）：后台勾选 reload / 配置 `reload.dpOnApprove=true`，或游戏内 `/cp load <文件名>`；查看 `console-actions.log` |
| 管理员登录一直失败 | 确认 `web-config.json` 只改了 `admin.password` 或 `admin.passwordHash` 其中一个（hash 非空时优先）；改完需重启 Web |
| 中文乱码/脚本语法报错 | 后端 `.ps1` 必须保存为 **UTF-8 with BOM**；编辑后可用 PowerShell 解析器检查语法；日志文件本身为 UTF-8 |
