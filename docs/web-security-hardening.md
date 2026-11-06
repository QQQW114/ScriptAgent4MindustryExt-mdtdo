# 门户 Web 安全加固记录（2026-11）

> 适用：`stats-web/`（门户 Web 后端 + 前端）与 `scripts/wayzer/ext/liveEventFeed.kts`
> 状态：**已实现并在本地副本验证；尚未在生产部署**
> 相关文档：[web-portal.md](web-portal.md)（功能与配置）、[server-status-stats.md](server-status-stats.md)

---

## 1. 背景与威胁模型

门户对外暴露在公网（`http://<host>:<port>/`），后端是**单线程 HttpListener**。
本轮按以下威胁模型做了一轮代码审计与加固：

| 维度 | 假设 |
|---|---|
| 攻击者掌握 | 全部源码（仓库开源）、目标域名与端口 |
| 攻击者不掌握 | 管理员密码、服务器登录凭据 |
| 攻击目标 | 无凭据读取「本应需要授权」的数据（日志、投稿、配置）、或拿下管理员 |
| 不追求 | 纯 DoS（不计分，除非能借此拿到数据） |

「公开 / 应授权」的判定口径（贯穿全文）：

- **公开**：首页状态卡、实时事件流、历史消息、已上架目录 —— 抓到不算泄露；
- **应授权**：服务器日志文件、待审投稿、`/api/health` 的路径/端口/账号、配置文件。

## 2. 本轮修了什么

### 2.1 可用性 / 抗阻塞

| 问题 | 改前 | 改后 |
|---|---|---|
| 慢 body 阻塞全站 | 单连接即可让整站排队，4 并发下首个请求 3.3s+ | 单次读停滞 **200ms**；小 JSON（登录/审核）走**每请求独立**预算；大 body 共用「每 1 秒最多空等 400ms」的全局预算，耗尽回 **503 + Retry-After**；4 并发下正常请求 **~400ms** |
| 超限请求 | — | `Content-Length` 超 `maxRequestBytes` **立即 413**，不进读取循环 |
| 内存放大 | 10MB 上传工作集 +84MB 且不回落 | 按 `Content-Length` 一次分配 + 零复制切片 + 分块流式落盘，**+19MB 且 2s 回落** |

> ⚠ 复现口径：测慢 body 必须用 **≤10MB** 的 `Content-Length`；用了 >10MB 会在头部阶段直接 413，看起来「已修复」其实是假阳性。

### 2.2 登录与凭据

| 问题 | 改前 | 改后 |
|---|---|---|
| 撞库后无法自救 | per-username **固定锁定** 5 分钟，锁定期正确密码也被 429 | **先验密码**：正确密码无条件放行；指数退避 `0/2/4/8/…/300s` 只作用于错密码 |
| 锁定可被第三方维持 | 否 | 否（比改前更强）：攻击者持续错登不会挡住管理员 |
| 全局爆破 | 无全局维度 | 60 分钟滑动窗口失败预算（默认 40）→ 全局令牌桶 **1 次/5 秒**（只延迟不封禁） |
| XFF 伪造绕过限频 | `trustProxyHeaders=true` 时取最左段，可伪造 | 未配置 `security.trustedProxies` 时**完全忽略** XFF；配置后只在直连对端命中白名单时采信，且取**最右侧非受信段** |
| 密码成本 | PBKDF2-SHA256 150k | 保留；新增 `loginMinIntervalMs=120` 把 CPU 放大压到约 8 次/秒 |
| 改密不踢会话 | 旧 token 仍有效满 12h | 会话记录**管理员凭据指纹**，改密/换哈希后旧会话立即失效 |
| 429 泄密 | body 带 `reason=account_backoff/global_slow` | 统一文案，时间信息只在 `Retry-After` 头与 `retryAfter` 字段 |

### 2.3 未认证信息暴露

| 端点 | 改前 | 改后 |
|---|---|---|
| `/api/health` | 28 字段：`webRoot`/`dataDir`/`dpDir`/`mapsDir`/`logsDir` 绝对路径、`console.port`、`adminUser`、会话数 | 未认证只回 `ok`/`serverTime`/`version`；认证后路径改为**相对名** + `pathsRedacted:true` |
| `/api/status` | 含网卡名 + 30 点带宽序列 | 未认证只回聚合带宽（`avgUpBps`/`windowMinutes`/`sampledAt`） |
| `/api/logs*` | 需登录 | 保持需登录（`logsRequireLogin=true`），白名单 + 50MB/文件 + 每 IP 5 次/5 分钟 + 并发 2 |

### 2.4 路径与类型

| 问题 | 改后 |
|---|---|
| 静态文件越界 | `GetFullPath` + 补分隔符 + `OrdinalIgnoreCase` 的 `PathInside` 三重校验 |
| **目录拒绝只比对名字** | **改为按解析后的真实路径**：`dataDir`（可配置）与 `server` 目录命中真实路径即拒绝，改名不再绕过 |
| 静态路径细节 | 拒绝 `:`（NTFS ADS）、`.` 开头段、尾斜杠、`web-config.*` / `start-web.*` 等叶子名 |
| 上传类型伪造 | `.zip` 需 `PK\x03\x04`/`PK\x05\x06` 开头**且**尾部 64KB 有 EOCD；`.json` 必须能解析；`.msav` 必须能解压且以 `MSAV`+版本号开头；`.hjson/.json5` 弱校验 |
| 文件名 | 拒绝路径分隔符、控制字符、`" \| < > ? * :`、Windows 保留名 → `400 BAD_FILENAME` |
| 非法路径异常 | `GetFullPath` 抛异常时返回 **404**，不再 500 + 堆栈 |

### 2.5 公开事件流脱敏（UUID + 指令聊天 + 旧文件）

`/api/events` 与 `/api/events/history` **无需登录**（首页实时流与历史抽屉的数据源），
而旧事件 JSON 里的 `playerId` 就是玩家 UUID（16 字节 base64）。

| 项 | 改前 | 改后 |
|---|---|---|
| `playerId` | 每条 join/leave/chat 事件都带 | 新事件由 `liveEventFeed.kts` 不再生成；**旧事件文件里带 `playerId` 的整条记录由 Web API 过滤，不返回**（不依赖服务器侧删除日志） |
| 指令聊天 | `/login`、`/register`、`/bindemail` 等原文公开 | Web API 去颜色标记后以 `/` 或 `／` 开头的事件整条过滤（实时流和历史查询一致） |
| `keyword` 检索 | 匹配原始 JSON 行（可命中 playerId） | 只匹配公开字段 `type/text/player`，不能探测 UUID/隐藏聊天 |
| 可拉取历史 | 当前文件 + 12 份旋转文件（`historyMaxFiles=13`） | 默认 **3** 份 |
| 昵称与普通聊天 | 公开 | **保持公开**（设计如此，前端只渲染这两个字段） |

改动理由：UUID 是玩家的**稳定身份标识**，可跨会话关联；而本接口匿名可访问、可翻页批量拉取。
旧 `live-events*.jsonl` 即使保留在服务器上也不会再向匿名 Web 返回带 UUID 的记录；仍建议按需轮转历史文件。

> 管理侧不受影响：游戏日志 `config/logs/*.log` 仍含 UUID 与 IP，且下载需要登录。

## 3. 验证方式

> 全部验证在**本地副本**上完成（独立端口、独立 `dataDir`、独立日志目录，`console.enabled=false`），未在生产环境执行。

| 项 | 方法 | 结果 |
|---|---|---|
| 冷启动 | `start-web.ps1` 起服务，`/api/health` 200 | 通过 |
| 鉴权矩阵 | 26 组路径变体（大小写、双斜杠、尾斜杠、`./`、`%2f`）× GET/POST/HEAD/OPTIONS | 全部 401/404/405 |
| 静态穿越 | 24 组（`..`、`%2e`、`%252e`、`..%5c`、UNC、8.3、尾点空格、ADS） | 全部 403/400/404 |
| 目录改名 | 副本 `dataDir` 改为非默认名 | 改前 200（泄漏）、**改后 404** |
| 上传校验 | 假 zip（仅 `PK`）、坏 JSON、假 msav、`a:b.zip`、`NUL.zip` | 全部 400；真 zip/msav/json 通过 |
| 慢 body | 2/4 条 5MB 慢连接 + 并发 `/api/health`、`POST /api/login` | 慢连接 408/503，登录仍 200 |
| 登录节流 | 连续错登后正确密码 | 正确密码 200（不被退避拦），429 文案统一 |
| 事件流 | 真实玩家 join/chat/leave 后抓 `/api/events`、`/api/events/history` | 三条事件均**无 `playerId`**，昵称/聊天正常 |
| 内存 | 10MB 合法上传，采样进程工作集 | +19MB，2s 回落 |

## 4. 已知限制（未修，需评估）

| # | 项 | 说明 | 建议 |
|---|---|---|---|
| L1 | **`Host` 头未校验** | 不校验 `Host`，也接受绝对形式请求 URI（`GET http://evil/api/... HTTP/1.1`）。当前无共享缓存、无 Host 相关逻辑，**无实际影响**；但接入 CDN/反代后存在缓存投毒风险 | 若上 CDN/反代，需加 Host 白名单（代码位置已预留思路） |
| L2 | **大 JSON 解析阻塞** | 10MB 合法 JSON 解析占用主线程 **2~3 秒**（单线程固有） | 队列上限 10 + 每 IP 5 次/10 分钟已限制频率；如需根治需异步化解析或进一步下调上传上限 |
| L3 | **待审队列可被填满** | 匿名可提交，每类上限 10 件，满后所有人 409 | 需要时加「同 IP 待审数上限」 |
| L4 | **默认凭据不阻断启动** | 空配置会回填默认密码，仅打印红色警告 | 建议公网绑定时拒绝启动（未做，属策略选择） |
| L5 | **无 HTTPS** | `HttpListener` 只出 `http://` | 公网部署建议反代 TLS + 门户保持 127.0.0.1 |
| L6 | `session.secret` 是**死配置** | 全仓只读不用，不参与任何签名 | 可从配置移除或用于签下载票据 |
| L7 | 静态归一化接受尾点/空格变体 | 允许的文件仍可带尾点/空格访问（denylist 变体均被拒） | 低危，可按需规范化 |
| L8 | `Server: Microsoft-HTTPAPI/2.0` | http.sys 内核写入，应用层去不掉 | 反代覆写 |
| L9 | 事件历史保留 | 默认 3 份旋转文件，仍可翻出较大范围历史 | 按运营需要调整 `events.historyMaxFiles` |

## 5. 部署清单

1. 更新 `stats-web/server/*.ps1`、`stats-web/assets/js/admin.js`、`stats-web/README.md`、`docs/web-portal.md`；
2. 更新 `scripts/wayzer/ext/liveEventFeed.kts`；
3. **确认 `web-config.json` 的 `admin.passwordHash` 已设置且 `admin.password` 为空**（部署时必须换掉默认密码）；
4. 若显式配置过 `events.historyMaxFiles`，改为 3（或按需要）；
5. 旧 `config/stats/live-events*.jsonl` **不再要求停服删除**：新版 Web API 会跳过带 `playerId` 的旧记录；
   如仍希望缩小磁盘/检索范围，可自行轮转，但不依赖它来防止 UUID 泄漏；
6. 重启门户；
7. 复验：
   - 未登录 `/api/health` 只回 3 字段；
   - `/api/events`、`/api/events/history?limit=5` 无 `playerId`；
   - 含 `/register` 等指令的聊天不出现在公开事件流/历史；
   - 旧密码登录应失败、新密码可登录。

## 6. 未覆盖 / 后续

- 已认证面（登录后的审核、删除、日志下载全链路）在本地副本上已做基本验证，但**未做对抗性测试**；
- 反代场景下 `trustedProxies` 的实际取值需按部署环境确认；
- 服务端控制台（6859）与 MindustryX API（10099）不在门户范围内，未纳入本轮加固；
- 事件历史去 UUID 后，若运营侧需要「网页查 UUID」，应另开**仅认证可读**的数据源（例如 `live-events-admin.jsonl`），而不是恢复公开字段。
