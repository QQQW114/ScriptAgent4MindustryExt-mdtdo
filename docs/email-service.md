# 邮箱验证码服务（email-service）

给服务器绑定插件/协作者提供的**本地发信服务**：插件把「QQ 邮箱 + 验证码」POST 给本机 HTTP 接口，服务通过阿里云邮件推送 DirectMail 发出验证码邮件。

- 完整实现、配置、部署与调用示例见 [`../email-service/README.md`](../email-service/README.md)
- 职责边界：**只发信**，不生成/不校验验证码；绑定与验证码逻辑由调用方插件实现
- 只允许 QQ 邮箱（默认 `@qq.com` / `@foxmail.com`）
- 监听 `127.0.0.1:40150`，只本机访问；阿里云 AccessKey 只在服务器本地 `email-service/config.json`（不入库）

## 调用契约（给协作者）

```http
POST http://127.0.0.1:40150/send
Content-Type: application/json
X-Internal-Token: <内部 token>

{"email":"123456789@qq.com","code":"123456","expireMinutes":10}
```

成功：`200 {"ok":true,"requestId":"...","envId":"..."}`；
失败：`400/401/429/502 + {"ok":false,"error":"...","message":"..."}`（错误码见 README）。

幂等/限流由服务兜底：同邮箱 60 秒冷却、每邮箱 5 次/天、全局 100 次/天（可配置）；调用方仍需按 `429 retryAfterSeconds` 冷却处理。

## 基础设施（已配置完成）

- 域名：`mdtdomindustry.cn`（根域已有阿里企业邮箱，发信用子域避免冲突）
- 发信域名：`dm.mdtdomindustry.cn`，SPF/DKIM/DMARC/MX 已验证通过
- 发信地址：`noreply@dm.mdtdomindustry.cn`（触发类邮件）
- 发件人显示名：`MDTDO服务器`（`email-service/config.json` → `aliyun.fromAlias`）；验证码邮件正文含「本邮件 N 分钟内有效」
- 阿里云 DirectMail：已开通，免费额度 2000 封 / 每天 200 封
- RAM 子账号：`mdt-email-api`，仅 DirectMail 权限

## 生产部署

1. 把 `email-service/` 目录（交接包内含已填好的 `config.json`）复制到服务器，如 `E:\mdt-email-service`；
2. 若从模板重建配置：复制 `config.example.json` → `config.json`，填 `internalToken` 与阿里云 AccessKey（生产建议直接使用交接包里的 config，避免密钥在聊天传递）；
3. **管理员** PowerShell 执行 `.\install-scheduled-task.ps1`：创建开机自启任务「MDT Email Service」（SYSTEM，
   失败每分钟自动重试、无运行时长限制），并自动做 URLACL/健康检查；更新文件后用 `-Restart` 或
   `restart-email-service.bat` 重启任务；
4. 服务只监听 `127.0.0.1:40150`，**不要**在防火墙放行该端口；
5. `/health` 会返回 `uptimeSeconds/totalSends/lastSendLatencyMs/lastSendRequestId`，日志中每次发送记录阿里云 API
   耗时（`elapsedMs`）；玩家反馈邮件慢时先对照这个耗时判断是服务/API 慢还是 QQ 邮箱投递慢。

## 安全

- `config.json` 已被 `.gitignore` 排除，禁止提交或发群；
- 日志邮箱打码，不记录验证码/密钥；
- 若需远程调用（插件不在同一台机器），不要直接把端口暴露公网，先改为内网/隧道 + 更强 token。
