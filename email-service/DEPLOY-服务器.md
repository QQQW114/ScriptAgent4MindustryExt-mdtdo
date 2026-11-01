# MDT 邮箱验证码服务 —— 服务器部署说明（给运维/协作者）

本目录就是最终要跑在**游戏服务器本体**上的发信服务：插件调用本机 `127.0.0.1:40150`，服务通过阿里云 DirectMail 发 QQ 邮箱验证码。

## 1. 解压到服务器

把整个目录解压到服务器固定位置，例如：

```
E:\mdt-email-service\
  server.ps1
  Send-Email.ps1
  config.json          ← 已填好（含阿里云 AccessKey，注意保密）
  config.example.json
  start-email-service.bat
  install-scheduled-task.ps1
  README.md
  logs\                ← 首次启动自动创建
```

> 路径不要带奇怪字符；建议 `E:\mdt-email-service`。

## 2. 检查配置

打开 `config.json`，确认：

- `internalToken`：给绑定插件用的内部 token（当前已填，协作者需要同一个值）；
- `aliyun.accessKeyId` / `accessKeySecret`：已填好（阿里云 RAM 子账号 `mdt-email-api`）；
- `aliyun.senderAddress`：`noreply@dm.mdtdomindustry.cn`；
- `listen`：`127.0.0.1:40150`（**只本机**，不要改成公网）；
- `mockSend=false`（真实发送）。

## 3. 安装为开机自启（推荐，管理员 PowerShell）

在服务器上以管理员打开 PowerShell：

```powershell
cd E:\mdt-email-service
powershell -NoProfile -ExecutionPolicy Bypass -File .\install-scheduled-task.ps1
```

- 成功会创建任务「MDT Email Service」（SYSTEM、开机启动），并显示健康检查结果；
- 若提示 HttpListener 拒绝访问，先执行一次：
  `netsh http add urlacl url=http://127.0.0.1:40150/ user=Everyone`，再重跑安装脚本。
- 也可手动双击 `start-email-service.bat` 前台运行（调试用）。

## 4. 验证

在服务器上执行：

```powershell
Invoke-RestMethod http://127.0.0.1:40150/health
```

然后发一封测试信（把 QQ 邮箱换成你的）：

```powershell
$t = (Get-Content .\config.json -Raw -Encoding UTF8 | ConvertFrom-Json).internalToken
Invoke-RestMethod -Uri 'http://127.0.0.1:40150/send' -Method Post -ContentType 'application/json; charset=utf-8' `
  -Headers @{ 'X-Internal-Token' = $t } `
  -Body ([Text.Encoding]::UTF8.GetBytes('{"email":"3571850781@qq.com","code":"123456"}'))
```

返回 `ok:true` + `envId` 即成功；QQ 邮箱应能收到。

## 5. 把接口交给绑定插件（协作者）

| 项 | 值 |
|---|---|
| 地址 | `http://127.0.0.1:40150/send`（同机） |
| 方法 | `POST`，`Content-Type: application/json` |
| 鉴权 | 请求头 `X-Internal-Token: <config.json 里的 internalToken>` |
| 请求体 | `{"email":"xxx@qq.com","code":"123456","expireMinutes":10}` |
| 成功 | `200 {"ok":true,"requestId":"...","envId":"..."}` |
| 失败 | `400/401/429/502 + {"ok":false,"error":"...","message":"..."}` |

只允许 `@qq.com` / `@foxmail.com`；服务只发信，不生成/不校验验证码（绑定逻辑由插件实现）。
完整契约见同目录 `README.md`。

## 6. 日常运维

- 日志：`logs\email-service-YYYYMMDD.log`（邮箱打码，无验证码/密钥）；
- 停止/卸载：管理员执行 `.\install-scheduled-task.ps1 -Remove`；
- 更新服务文件：先停止任务 → 覆盖 `server.ps1` / `Send-Email.ps1` → 再启动；
- **不要**在防火墙放行 40150；不要改 `listen.host` 为 `0.0.0.0`（除非明确要内网其他机器调用，并已加防火墙仅允许内网 IP）；
- 阿里云额度免费 200 封/天；服务默认全局 150/天，保留余量。
