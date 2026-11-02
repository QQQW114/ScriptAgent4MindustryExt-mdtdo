# MDT 账号 QQ 邮箱绑定/注册流程

> 状态：脚本已编译加载，等待 Windows 服务器端实机验证
> 脚本：`scripts/wayzer/user/emailBind.kts`、`scripts/wayzer/user/accountAuth.kts`
> 邮件服务：`email-service`（本机 `127.0.0.1:40150`，只负责发信）

## 1. 目标

- 新账号注册必须验证 QQ 邮箱；
- 老账号必须补绑 QQ 邮箱；
- 只允许 `数字@qq.com`，邮箱前缀即 QQ 号；
- 一个邮箱/QQ 只能绑一个账号；
- 每 IP 每天最多 3 次成功注册；
- 老账号未补绑：信任/资历锁到最高 2 级，3++/4 排除；
- 补绑后恢复原等级；
- 新账号 300 MDC，老账号补绑 1000 MDC。

## 2. 部署前检查

### 邮件服务

在游戏服务器 PowerShell 中：

```powershell
Invoke-RestMethod http://127.0.0.1:40150/health
```

返回 `ok:true` 即正常。

`config.json` 里确认：

- `listen` 为 `127.0.0.1:40150`；
- `internalToken` 与游戏服配置一致；
- `aliyun.senderAddress` 为 `noreply@dm.mdtdomindustry.cn`；
- `allowDomains` 建议只保留 `["qq.com"]`；
- `mockSend=false`。

接口（插件调用）：

```http
POST http://127.0.0.1:40150/send
X-Internal-Token: <config.json 的 internalToken>
{"email":"123456@qq.com","code":"123456","expireMinutes":10,"purpose":"bind"}
```

### 游戏服配置

`config/scripts/data/config.conf`：

```json
"wayzer": {
  "user": {
    "emailBind": {
      "emailServiceUrl": "http://127.0.0.1:40150/send",
      "emailServiceToken": "<email-service 的 internalToken>",
      "emailCodeTtlMinutes": 10,
      "ipDailyRegisterLimit": 3,
      "bindRewardOld": 1000,
      "bindRewardNew": 300
    }
  }
}
```

`emailServiceToken` 不填，游戏内会提示“邮件服务 token 未配置”。

### 冷启动

```text
共找到166脚本,加载成功162,启用成功157,出错0
wayzer/user/emailBind  Enabled
wayzer/user/accountAuth Enabled
```

## 3. 新玩家注册

```text
玩家进服
  → /register
  → 输入密码（两次）
  → 输入 QQ 邮箱：123456@qq.com
  → 系统发送 6 位验证码到该邮箱
  → 游戏内输入验证码
  → 校验通过
  → 创建账号（username=123456）
  → 绑定邮箱
  → 发 300 MDC
  → 自动登录
```

限制：

- 只允许 `^[1-9][0-9]{4,11}@qq\.com$`；
- 同一邮箱、同一 QQ 只能一个账号；
- 每 IP 每天最多 3 次成功注册；
- 验证码 10 分钟有效、最多尝试 5 次；
- 邮件服务自身限流：同邮箱 60 秒、每邮箱 5 次/天、全局 150 次/天。

## 4. 老账号补绑

```text
玩家登录老账号
  → /bindemail
  → 输入 QQ 邮箱
  → 收验证码
  → 输入验证码
  → 校验通过
  → 绑定邮箱 + QQ 号
  → 发 1000 MDC
  → 恢复等级/解锁
```

如果账号用户名是合法 QQ 号：

- 邮箱前缀必须与用户名一致；
- 不一致会提示“邮箱前缀必须与当前账号 QQ 号一致”。

如果账号用户名是乱填的：

- 当前脚本会绑定邮箱/QQ 号，但**不会自动改 username**；
- 需要管理员迁移/改名（见第 6 节）。

## 5. 未补绑老账号

登录/进服后：

```text
保存原信任等级、资历等级
信任等级设为 2 并锁定
资历等级设为 2 并锁定
```

- 3++/4 全部排除；
- 没有补绑截止日期，未绑期间一直锁 2 级；
- 补绑成功后恢复原等级并解锁。

## 6. 老账号迁移

### 6.1 乱填用户名 → QQ 号

目标：保留 `account:<id>` 和所有数据，只把用户名改成 QQ 号。

当前缺口：脚本暂未自动改 `MdtAccounts.username`。  
临时处理：

1. 管理员确认旧账号控制权；
2. 验证新 QQ 邮箱；
3. 在数据库/管理工具中把 `MdtAccounts.username` 改为新 QQ 号；
4. 更新 `MdtAccountBindings` 的 QQ identifier；
5. 再执行 `/bindemail`。

### 6.2 用户名是别人的 QQ

```text
验证旧账号密码
  → 验证新 QQ 邮箱
  → 管理员审核
  → 改 username 为新 QQ
  → 保留 account:<id> 和数据
```

## 7. 管理员命令

```text
/bindemail                 玩家自助补绑
/emailbind <accountId>     查询绑定邮箱
/emailbind <accountId> reset  清除邮箱绑定
```

## 8. 数据存储

使用 `MdtSettings`，不改表结构：

```text
email.bind.<accountId>      = 邮箱
email.index.<sha256(email)> = accountId
qq.index.<qq>               = accountId
email.reward.<accountId>    = done
email.lock.<accountId>      = 原信任等级|原资历等级
email.ip.<sha256(ip)>       = 日期|次数
```

## 9. Windows 端实机测试清单

1. 确认 `/health` 正常；
2. 冷启动，确认 banner 出错 0、两个脚本 Enabled；
3. 用测试账号执行 `/register`：
   - 输入 `数字@qq.com`；
   - 确认 QQ 邮箱收到验证码；
   - 输入验证码；
   - 确认账号创建、300 MDC、自动登录；
4. 用老账号执行 `/bindemail`：
   - 确认收码、绑定成功、1000 MDC；
   - 确认等级恢复；
5. 用一个未补绑老账号登录：
   - 确认信任/资历被锁到 2；
   - 补绑后恢复；
6. 用非 `@qq.com` 邮箱测试：应拒绝；
7. 用同一邮箱注册第二个账号：应拒绝；
8. 同一 IP 第 4 次注册：应拒绝；
9. 错误验证码 5 次后：应要求重新发送；
10. 关闭邮件服务测试：应提示邮件发送失败，不应卡死。

## 10. 已知限制

- 本机 macOS 没有 40150 服务，当前只验证了编译/加载，未验证真实发信；
- 乱填用户名自动改 QQ 号尚未实现，需管理员迁移；
- 账号 QQ 号与邮箱前缀不一致时，历史账号需管理员处理；
- 邮件验证码在游戏文本输入框输入，需确认不会被聊天日志记录；
- 邮件服务 150 封/天，注册高峰需关注额度。
