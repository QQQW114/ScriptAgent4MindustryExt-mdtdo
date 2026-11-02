# MDT 邮箱验证码服务（email-service）

给服务器插件/协作者提供一个**本机 HTTP 接口**：调用方传入「QQ 邮箱 + 验证码」，服务负责通过阿里云邮件推送（DirectMail）把验证码邮件发出去。

> **职责边界（重要）**：本服务**只负责发信**，不生成、不保存、不校验验证码；验证码的生成、有效期、尝试次数、账号绑定关系等逻辑由**调用方（绑定插件）**自己实现。
> 只允许 QQ 邮箱（`@qq.com` / `@foxmail.com`，可配置），避免被当开放中继滥用。

---

## 1. 目录

| 文件 | 说明 |
|---|---|
| `server.ps1` | HTTP 服务入口（PowerShell + HttpListener，Windows 自带，无额外依赖） |
| `Send-Email.ps1` | 阿里云 DirectMail API 签名与发信封装 |
| `config.example.json` | 配置模板；复制为 `config.json` 后填写 |
| `config.json` | **本机实际配置（含 AccessKey，已被 .gitignore，禁止提交）** |
| `start-email-service.bat` | 启动入口：已安装计划任务时优先启动任务；否则前台调试运行 |
| `install-scheduled-task.ps1` | **生产推荐**：开机自启 + 崩溃自动重启（SYSTEM 计划任务），支持 -Status/-Restart/-Remove |
| `restart-email-service.ps1/.bat` | 重启计划任务，让新 `server.ps1` 立即生效（更新器也会调用） |
| `logs/` | 运行日志（每日一个文件，邮箱打码，不记录验证码/密钥；含阿里云调用耗时） |

---

## 2. 部署（Windows 服务器）

1. 把整个 `email-service` 目录放到服务器（例如 `E:\mdt-email-service`）；
2. 复制 `config.example.json` 为 `config.json`，填写：
   - `internalToken`：随机长字符串（调用方需要带同一个值）；
   - `aliyun.accessKeyId` / `accessKeySecret`：阿里云 RAM 子账号密钥（当前账号 `mdt-email-api`，已授权 DirectMail）；
   - `aliyun.senderAddress`：`noreply@dm.mdtdomindustry.cn`；
   - `aliyun.fromAlias`：发件人显示名，默认 `MDTDO服务器`；
   - `mockSend=true` 可先做联调（不真实发信，不消耗额度）。
3. 启动：
   - 调试：双击 `start-email-service.bat`（未安装任务时前台运行并显示日志）；
   - 生产（推荐）：**管理员** PowerShell 执行 `.\install-scheduled-task.ps1` —— 创建开机自启任务「MDT Email Service」（SYSTEM），
     配置了失败每分钟自动重试、无运行时长限制，并自动做 URLACL/健康检查；
   - 查看/重启/卸载任务：
     ```powershell
     .\install-scheduled-task.ps1 -Status
     .\install-scheduled-task.ps1 -Restart
     .\install-scheduled-task.ps1 -Remove
     ```
     更新 `server.ps1` 后执行 `-Restart`（或更新器自动重启）即可生效；
   - 也可手动用「任务计划程序」运行：
     `powershell.exe -NoProfile -ExecutionPolicy Bypass -File "E:\email-service\server.ps1" -Config "E:\email-service\config.json"`，勾选「不管用户是否登录都运行」；
   - 服务器部署的完整步骤见同目录 [`DEPLOY-服务器.md`](DEPLOY-服务器.md)；
4. 默认监听 `127.0.0.1:40150`（**只本机可访问**，不要对公网开放）；若需其他机器访问，改 `listen.host` 并自行加防火墙+只允许可信 IP；
5. 首次若提示"拒绝访问"，用**管理员**执行一次：
   `netsh http add urlacl url=http://127.0.0.1:40150/ user=Everyone`

---

## 3. API 契约

### 3.1 健康检查（免鉴权）

```http
GET http://127.0.0.1:40150/health
```

```json
{
  "ok": true,
  "service": "mdt-email-service",
  "version": "1.0.0",
  "time": "2026-11-02 18:30:00",
  "startedAt": "2026-11-02 09:00:00",
  "uptimeSeconds": 34200,
  "totalSends": 12,
  "lastSendAt": "2026-11-02 18:29:50",
  "lastSendLatencyMs": 320,
  "lastSendRequestId": "01A1B2C3..."
}
```

> `lastSendLatencyMs` 是**调用阿里云 DirectMail API 的耗时**，不是 QQ 邮箱实际投递耗时；玩家反馈“邮件延迟高”时先看
> `logs/email-service-*.log` 的 `发送成功 ... elapsedMs=`：若 API 很快但收件慢，延迟在阿里云/QQ 邮箱投递侧；若 elapsedMs 很大，
> 再看网络/代理/阿里云 API。

### 3.2 发送验证码邮件

```http
POST http://127.0.0.1:40150/send
Content-Type: application/json
X-Internal-Token: <config.internalToken>
```

请求体：

```json
{
  "email": "123456789@qq.com",
  "code": "123456",
  "expireMinutes": 10,
  "purpose": "bind"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `email` | 是 | 收件邮箱；只允许 QQ 邮箱（`qq.com`/`foxmail.com`），自动 trim + 小写 |
| `code` | 是 | 4–8 位数字；服务不校验内容，只拼进邮件正文 |
| `expireMinutes` | 否 | 1–60，默认 10，只用于邮件正文提示 |
| `purpose` | 否 | 预留字段（bind 等），当前仅记录日志 |

成功响应（HTTP 200）：

```json
{ "ok": true, "requestId": "01A19741-...", "envId": "600000367760214944", "message": "验证码邮件已发送" }
```

失败响应（HTTP 4xx/5xx）：

```json
{ "ok": false, "error": "EMAIL_COOLDOWN", "message": "发送太频繁，请稍后再试", "retryAfterSeconds": 42 }
```

### 3.3 错误码

| HTTP | error | 含义 |
|---|---|---|
| 400 | `INVALID_EMAIL` | 邮箱格式错误 |
| 400 | `DOMAIN_NOT_ALLOWED` | 不是允许的邮箱域名（默认只允许 QQ 邮箱） |
| 400 | `INVALID_CODE` | 验证码不是 4–8 位数字 |
| 401 | `UNAUTHORIZED` | 缺少/错误 `X-Internal-Token` |
| 429 | `EMAIL_COOLDOWN` | 同一邮箱发送太频繁（默认 60 秒） |
| 429 | `EMAIL_DAILY_LIMIT` | 同一邮箱今日次数超限（默认 5 次） |
| 429 | `SERVICE_DAILY_LIMIT` | 服务全局每日额度用完（默认 100，低于阿里云免费 200/天） |
| 429 | `QUOTA_EXCEEDED` | 阿里云额度不足 |
| 502 | `EMAIL_SERVICE_CONFIG` | 阿里云鉴权/发信域名/发信地址配置异常（查服务日志） |
| 502 | `SEND_FAILED` | 其他发送失败 |
| 500 | `INTERNAL_ERROR` | 服务内部错误 |

调用方处理建议：`429` 按 `retryAfterSeconds` 冷却；`502` 提示"邮件服务暂不可用，请稍后再试"，不要不断重试。

---

## 4. 调用示例

### PowerShell

```powershell
$body = @{ email = '123456789@qq.com'; code = '123456'; expireMinutes = 10 } | ConvertTo-Json -Compress
$r = Invoke-RestMethod -Uri 'http://127.0.0.1:40150/send' -Method Post -ContentType 'application/json; charset=utf-8' `
    -Headers @{ 'X-Internal-Token' = '<token>' } -Body ([Text.Encoding]::UTF8.GetBytes($body))
$r | ConvertTo-Json
```

### curl

```bash
curl -s -X POST http://127.0.0.1:40150/send \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: <token>" \
  -d '{"email":"123456789@qq.com","code":"123456","expireMinutes":10}'
```

### Kotlin / Java（插件侧）

```kotlin
val body = """{"email":"$email","code":"$code","expireMinutes":10}"""
val conn = (URL("http://127.0.0.1:40150/send").openConnection() as java.net.HttpURLConnection).apply {
    requestMethod = "POST"
    doOutput = true
    setRequestProperty("Content-Type", "application/json; charset=utf-8")
    setRequestProperty("X-Internal-Token", token)
    connectTimeout = 5000
    readTimeout = 10000
}
conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
val ok = conn.responseCode == 200
val respText = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
```

> 插件侧不需要也不应该持有阿里云 AccessKey；只需要这里的 URL + `X-Internal-Token`。

---

## 5. 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `listen.host` / `listen.port` | `127.0.0.1` / `40150` | 监听地址（生产保持本机） |
| `internalToken` | 空 | 内部鉴权 token；为空会 WARN 且本机任意进程可调用 |
| `aliyun.endpoint` | `https://dm.aliyuncs.com/` | DirectMail 接入点 |
| `aliyun.accessKeyId/Secret` | — | RAM 子账号密钥（只在本机 config.json） |
| `aliyun.senderAddress` | `noreply@dm.mdtdomindustry.cn` | 已通过验证的发信地址 |
| `aliyun.fromAlias` | `MDTDO服务器` | 发件人显示名 |
| `allowDomains` | `["qq.com","foxmail.com"]` | 允许收件的域名；空数组=任意 |
| `limits.perEmailCooldownSeconds` | 60 | 同邮箱重发冷却 |
| `limits.perEmailDailyLimit` | 5 | 同邮箱每日上限 |
| `limits.globalDailyLimit` | 100 | 服务每日总上限（默认 100；留阿里云 200/天余量） |
| `mockSend` | false | true=不真实发信，返回 mock 成功（联调用） |
| `logDir` | `logs` | 日志目录 |

---

## 6. 邮件内容

- 发件人：`noreply@dm.mdtdomindustry.cn`（显示名 `MDTDO服务器`）
- 主题：`【MDT】邮箱绑定验证码`
- 正文：验证码 + “本邮件 N 分钟内有效” + 防泄露提醒（纯文本，UTF-8）
- 域名已配置 SPF / DKIM / DMARC / MX，阿里云侧验证通过，已实测可送达 QQ 邮箱。

---

## 7. 运维 / 安全

- 服务只监听 `127.0.0.1`，不要开公网；公网端口 40150 不需要也不要在防火墙放行；
- `config.json` 含 AccessKey，**已被 .gitignore 排除，禁止提交仓库/发群**；
- 日志中的邮箱做打码（如 `35***@qq.com`），不记录验证码与密钥；
- 阿里云额度：免费共 2000 封、每天 200 封；本服务默认全局 100/天留余量；
- 停服：关闭对应 `powershell.exe` 进程 / 任务计划程序里停止任务；日志在 `logs/`。

---

## 8. 自测记录

- 2026-11-01：本机启动 → `/health` 正常；`/send` 真实发往 QQ 邮箱（HTTP 200，`EnvId` 返回，收件箱已收到）；错误 token / 非 QQ 域名 / 非法验证码 / 超频均按契约返回。
- 2026-11-02：发件人显示名改 `MDTDO服务器`（`aliyun.fromAlias`），验证码正文改「本邮件 N 分钟内有效」；停旧进程重启后 `/health` 正常（`ok:true`，mockSend=false），未再真实发信。
