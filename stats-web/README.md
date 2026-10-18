# MDT DO 服务器门户 Web（stats-web）

> 由原「服务器状态页」升级为**服务器门户**（2026-10-17）：欢迎页 + 状态/图表 + 游戏内实时消息事件流 +
> DP/地图投稿审核 + 管理员后台。后端是独立于游戏服务器的 PowerShell HttpListener 进程，零外部依赖、无需联网。
> **2026-10-18 第二期**：服务器日志面板 + 历史消息查询 + 上行带宽卡 + 玩家名上限放宽 + 生产日志噪声修复。
>
> **权威文档：[`../docs/web-portal.md`](../docs/web-portal.md)**（架构/API 契约/完整配置表/安全设计/审核流程/事件流/自测记录/排障）。
>
> **开源仓库**：[`QQQW114/ScriptAgent4MindustryExt-mdtdo`](https://github.com/QQQW114/ScriptAgent4MindustryExt-mdtdo) 的 `stats-web/` 目录（public，含全部前端/后端/文档；运行时数据 `web-data/` 不入库；默认管理员凭据只是占位，部署后必须修改）。

## 快速启动

```powershell
cd mdtdo\stats-web

# 最简单：双击 start-web.bat，或命令行启动（默认 http://127.0.0.1:8090/）
.\start-web.ps1
.\start-web.ps1 -Port 8091                                 # 临时换端口

# 公网/局域网访问（两步）：
.\start-web.ps1 -InstallAcl -Port 8090                     # 管理员 PowerShell 执行一次，注册 URL 保留
.\start-web.ps1 -Bind 0.0.0.0 -Port 8090                   # 再普通权限启动 + 防火墙放行端口

# 生成管理员密码哈希（填 web-config.json 的 admin.passwordHash，并清空 admin.password）：
.\start-web.ps1 -GenPasswordHash "你的新密码"
```

停止：运行窗口 `Ctrl+C`。环境要求：Windows + Windows PowerShell 5.1（系统自带）或 PowerShell 7；不需要安装 Node/Python/任何包。

## 默认配置与安全（部署前必读）

- **主配置**：`web-config.json`（监听地址/端口、管理员账号密码、上传限制、DP/地图目录、热重载开关等；相对路径以本目录为基准，改完重启生效）；
  **第二期新增 `logs`/`bandwidth`/`events` 三段**（日志列表/下载限额、带宽采样、历史查询上限）——**旧配置缺这些键会自动用默认值**，
  升级时仍不要用仓库默认 `web-config.json` 覆盖生产配置；
  `web-config.ps1` 仅用于**旧部署自动迁移**：没有 `web-config.json` 时启动会读取它的 `$WebBind/$WebPort/$WebStatsJson`
  自动生成 `web-config.json`，并把旧文件改名 `web-config.ps1.bak-<时间>` 备份（幂等；失败回退旧配置启动）。
  **生产旧部署升级**：替换新版文件后直接启动，确认日志 `[升级] 已把旧配置迁移到…`、监听地址/端口与旧站一致，
  然后**立即修改默认管理员密码**即可（详见 [`../docs/web-portal.md`](../docs/web-portal.md) §3.4）。
- **默认管理员：`admin / ChangeMe_123456` —— 部署后第一件事就是修改它！** 启动横幅会持续红色警告；
  推荐改用 `admin.passwordHash`（见上面的 `-GenPasswordHash`）。
- `session.secret` 是占位串，也建议改成随机字符串。
- 默认只监听 `127.0.0.1`，避免无意暴露；公网访问注意先改密码，必要时用反代 HTTPS。
- `web-data/` 是运行数据（提交记录/暂存文件/审计与运行日志），**已在 `.gitignore` 中忽略、分发时不要打包**。

## 功能一览

- **页面**：`/` 门户首页（欢迎页 + 服务器状态卡 + 折线图 + 实时消息事件流）、`/upload.html` 公开投稿、
  `/admin.html` 管理员登录与审核后台；全部本地静态资源，无外网 CDN。
- **每日一言**：首页欢迎区显示随机句子，**45 秒自动轮换、点击换一条**；内置本地语料
  `assets/data/quotes.json`（**1484 条**，六类：游戏 / 文学 / 诗词 / 哲学 / 影视 / 动画，来源
  [hitokoto-osc/sentences-bundle](https://github.com/hitokoto-osc/sentences-bundle) 开源句子库），离线可用；
  浏览器能联网时会低频率（≥10 分钟）尝试从一言 API 取一条新句，失败静默降级到本地，不影响页面。
  轮换间隔与联网开关见 `assets/js/site-config.js` 的 `quote.rotateSeconds` / `quote.remoteEnabled`。
- **投稿与审核**：DP / 服务器地图投稿；单文件 **≤10MB**（扩展名 + 文件头双重校验）；**每种类型待审队列上限 10**
  （满返回 `QUEUE_FULL`）；**管理员审核通过后**才落盘到服务器目录——DP → `mdtserver\config\scripts\external-cp\`，
  地图 → `mdtserver\config\maps\`（通过后自动调用游戏控制台 `maps` 刷新服务器地图列表，免重启）；
  支持「更新现有 DP/地图」的替换语义、拒绝留原因、移除现有 DP/地图。
- **管理员**：配置文件决定账号密码；同一账号允许**多 IP/多会话**同时登录；会话 Bearer 校验。
- **游戏内事件流**：读取游戏侧 `wayzer/ext/liveEventFeed.kts` 写入的
  `mdtserver\config\stats\live-events.jsonl`（聊天/加入离开/投票/换图/系统事件，保留 Mindustry 颜色标记原文；
  文件不存在时接口返回空列表）；**历史消息抽屉**（第二期）可跨「当前 + 12 份旋转历史」按 1h/6h/今天/全部 + 类型/关键词
  筛选并分页（`/api/events/history`）。
- **服务器日志（第二期）**：管理后台「服务器日志」面板——文件列表 / 尾部预览 / 直链下载；**默认需管理员登录**
  （Bearer 或 `?token=` 直链），同时下载 **2** 个为上限、每 IP **5 次/5 分钟**、单文件 **50MB** 上限
  （超出分别返回 `429 BUSY` / `429 RATE_LIMITED` / `413`）。**日志含玩家 IP/聊天等敏感信息，下载 token 会出现在 URL，勿复制分享。**
- **上行带宽（第二期）**：首页「上行带宽（最近 5 分钟平均）」卡 + sparkline——宿主机网卡发送字节差分（单位 **B/s**），
  是**宿主机网卡总上行**（含游戏服/Web/系统流量），**游戏服与 Web 不同机时无意义**；采样失败自动降级不影响其它功能。
- **控制台桥 = 可选增强（第二期明确）**：生产不开 6859 调试端口是常态；控制台不可达时审核/删除仍成功（文件已落盘，
  游戏服重启或手动 `/externalcp load`/`maps` 后生效），后台以中性「可选/未开启」展示，**不报错**。
- **玩家名显示（第二期）**：前端显示上限放宽到 **512**（上游 `maxPlayerChars` 256 的 2 倍），残缺颜色标记自动收尾，
  长名换行 + 悬停/点击查看详情。
- **状态统计**：`/api/status` 规范化输出 + 兼容旧入口 `/server-status.json`；数据源仍是
  `wayzer/ext/serverStats.kts` 输出的 `mdtserver\config\stats\server-status.json`（口径见
  [`../docs/server-status-stats.md`](../docs/server-status-stats.md)）。
- **DP 语义**：外部 CP/数据资产包格式与热重载机制见 [`../docs/v159-data-assets-hot-reload.md`](../docs/v159-data-assets-hot-reload.md)。
- 前端资源版本 **`20261018b`**（改 `assets/**` 后需手工 bump `?v=`）。

## 文件说明

| 文件/目录 | 说明 |
| --- | --- |
| `index.html` | 门户首页（欢迎页 + 状态 + 图表 + 实时事件流） |
| `upload.html` | 公开上传页（DP / 地图投稿） |
| `admin.html` | 管理员登录 + 审核后台 |
| `assets/` | 前端 css/js/mock 资源（纯本地、无构建）；`assets/data/quotes.json` 为每日一言本地语料 |
| `server/` | 后端模块（`portal-core/http/store/console/api/server.ps1` + 第二期 `portal-logs/history/metrics.ps1`） |
| `start-web.ps1` / `start-web.bat` | 启动入口（bat 双击，参数透传） |
| `web-config.json` | ★ 主配置（含默认管理员凭据，务必修改） |
| `web-config.ps1` | 旧版配置兜底（仅在没有 web-config.json 时生效） |
| `web-data/` | 运行数据目录（自动创建、不入库、不打包） |

## 分发 / 复制到其它机器

- **⚠ 旧分发包 `复制插件用\stats-web.zip` 已过期，请勿再使用**：它只包含旧的「单文件静态状态页」，
  缺少门户后端（`server/`）、前端资源（`assets/`、`upload.html`、`admin.html`）与新配置，复制过去无法运行。
- 现在请**整目录复制**以下内容到目标机器的同一目录（缺一不可）：

  ```
  stats-web\
  ├─ index.html  upload.html  admin.html
  ├─ assets\                 （整个目录）
  ├─ server\                 （整个目录）
  ├─ start-web.ps1  start-web.bat
  └─ web-config.json  web-config.ps1
  ```

  不要在分发内容里包含 `web-data\`（运行数据）。
- **第二期起**分发内容还包含 `server/portal-logs.ps1`/`portal-history.ps1`/`portal-metrics.ps1` 与 `web-config.json` 的
  `logs`/`bandwidth`/`events` 三段；**旧配置文件缺这些段会自动用默认值**（升级后重启 Web 生效），但升级仍不要用仓库
  默认 `web-config.json` 覆盖生产配置。
- PowerShell 打包示例（在 `mdtdo` 目录执行；先暂存并排除运行数据，产物解压后顶层就是 `stats-web\`）：

  ```powershell
  cd mdtdo
  # 1) 暂存一份干净副本（robocopy 排除 web-data；退出码 1 表示"已复制"属正常）
  $stage = Join-Path $env:TEMP 'stats-web-pkg'
  Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
  robocopy .\stats-web (Join-Path $stage 'stats-web') /E /XD web-data /NFL /NDL /NJH /NJS /NP | Out-Null
  # 2) 打包（传 staging 目录的绝对路径，压缩包内顶层为 stats-web\）
  Compress-Archive -Force -DestinationPath .\stats-web-portal.zip -Path (Join-Path $stage 'stats-web')
  # 3) 清理暂存
  Remove-Item $stage -Recurse -Force
  ```

  等价替代（.NET，`$stage` 同上，压缩包内顶层同样为 `stats-web\`）：

  ```powershell
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [System.IO.Compression.ZipFile]::CreateFromDirectory(
    (Join-Path $stage 'stats-web'), (Join-Path (Get-Location) 'stats-web-portal.zip'),
    [System.IO.Compression.CompressionLevel]::Optimal, $true)
  ```

- 到目标机器把压缩包解压到部署目录（压缩包顶层即 `stats-web\`），先改 `web-config.json`（管理员密码、监听地址/端口、
  DP/地图/统计/事件文件路径），再双击 `start-web.bat`。路径默认按 `stats-web` 相对 `mdtserver` 同级目录组织
  （如与默认不同请改 `statsJson`/`eventsJsonl`/`dirs.*`）。
- **旧部署升级（已有旧版 `web-config.ps1`、没有 `web-config.json`）**：解压后请**删除/不要复制**压缩包里的默认
  `web-config.json`，保留线上旧 `web-config.ps1`，首次启动会自动迁移（生成 json、旧文件改名 `.bak-<时间>`、
  控制台打印 `[升级]` 日志）；否则默认 json 会优先生效而**不再迁移**，公网监听地址/端口会退回默认。
  详见 [`../docs/web-portal.md`](../docs/web-portal.md) §3.4 生产升级步骤。

## 与游戏服务器的关系

- Web 是**独立进程**，可随时启动/停止，不占用游戏主线程，也不要求游戏在线（离线时显示最后数据/空事件）；
- 游戏侧只做两件事：`serverStats.kts` 定时写状态 JSON、`liveEventFeed.kts` 用 IO 协程批量 append 事件 JSONL
  （第二期起保留 **12 份**旋转历史，`.1` 最新；玩家名上限默认 256 字符）；日志面板只读 `mdtserver\config\logs\` 白名单文件，
  带宽卡读宿主机网卡计数器，二者都不需要游戏脚本参与；
- 审核通过后的可选游戏侧动作通过**控制台 Socket（127.0.0.1:6859，需 `server.properties` 的 `socketInput=true`）**发送：
  DP 热重载 `externalcp load <文件名>`（默认关闭，后台勾选或配置开启）、地图列表刷新 `maps`（默认开启）；
  命令为异步、尽力而为，失败只记录到 `web-data/console-actions.log`，不影响审核结果。**控制台是可选增强：不开端口属正常状态。**

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 启动报监听失败 / conflicts with an existing registration | 端口被占用（`netstat -ano | findstr :8090`）或 0.0.0.0 未执行 `-InstallAcl` |
| `/api/status` 返回 `available:false` | 游戏服未运行或 `serverStats.kts` 未输出；游戏内 `/serverstats status` 检查 |
| `/api/events` 为空 / `available:false` | `liveEventFeed.kts` 未加载或还没产生事件；控制台 `scriptdiag state wayzer/ext/liveEventFeed` |
| 审核通过地图后服务器 `/maps` 看不到 | 检查地图目录配置、`console.enabled`、6859 是否监听；看 `web-data/console-actions.log`；也可游戏内手动 `/maps` |
| 管理员登录失败 | `admin.password` 与 `admin.passwordHash` 只保留一个（hash 非空时优先）；改完需重启 Web |
| `/api/logs` 返回 401 | 日志默认需管理员登录：先登录拿 token，列表/下载用 `Authorization: Bearer`；浏览器直链可拼 `?token=<token>` |
| 日志下载 429 BUSY / RATE_LIMITED / 413 | 分别为并发下载达上限（默认 2）、同一 IP 过频（默认 5 次/5 分钟）、单文件超 50MB；按 `Retry-After` 等待或改 `logs.*` 配置 |
| `/api/health.console.reachable=false` | **正常现象**：控制台是可选增强，生产通常不开调试端口；不影响审核/删除，文件落盘后游戏服重启/手动操作生效 |
| `/api/status.data.net.available=false` | 宿主机网卡采样失败（网卡名/计数器不可用）；可设 `bandwidth.interface` 指定网卡或 `bandwidth.enabled=false`，不影响其它功能 |
| 后端脚本中文乱码/语法错误 | 维护者注意：`.ps1` 必须保存为 **UTF-8 with BOM**（Windows PowerShell 5.1 要求） |

更多（API 契约、错误码、完整配置字段、安全措施、自测记录、已知限制）见 **[`../docs/web-portal.md`](../docs/web-portal.md)**。
