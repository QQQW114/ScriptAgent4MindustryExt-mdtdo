# QQ 邮箱绑定与注册验证（主服 `main`）

> **适用范围**：仅**主服 MDT DO（`main` 分支）**启用；分服 MDC DO（`mdc-do` 分支）不部署本功能与 `email-service/`（详见 [mdc-do-sync.md](mdc-do-sync.md)）。  
> **对应脚本**：`scripts/wayzer/user/emailBind.kts`、`emailOnboarding.kts`、`accountMigrate.kts`、`accountAuth.kts`；发信服务见 [email-service.md](email-service.md)。

---

## 1. 行为规则

### 老玩家（已登录、未绑定邮箱）
- 入服 **10 秒后**，若当前无其他菜单或输入框（检测 `MenuV3` / `MenuBuilder` / `textInput`），弹出绑定提醒（提供「立即绑定 QQ 邮箱」「不再提示」「关闭」）；弹窗失败时回退聊天提示。
- 未绑定期间**仅限制信任等级封顶为 2 级并锁定**（`3++`/`4` 级工作人员豁免；资历等级**不受限制**），不影响正常游玩。
- 绑定成功后自动恢复原信任等级，并发放一次 **1000 MDC** 补绑奖励。

### 新玩家（游客/未注册）
- 入服 **10 秒后**无其他弹窗时，显示注册引导菜单（提供「注册/登录账号」「不再提示」「关闭」）。
- `/register` 流程：输入密码 → 输入 QQ 邮箱（提示直接填 `QQ号@qq.com`）→ 输入 6 位邮箱验证码（主服已移除旧 `/captcha` 4 位验证码）。
- 管理员可通过 `/registerrequirement <0~72|on|off>` 配置注册前累计在线小时数（默认 1 小时，0=关闭）。
- 注册并自动登录成功后弹出 `MenuV3` 注册欢迎页（提示加群、`/wiki`、`/post` 与双击核心机面板），并发放默认 **300 MDC** 新账号奖励（`bindRewardNew` 可配）。

---

## 2. 相关指令

| 指令 | 别名 | 说明 |
|---|---|---|
| `/register` | `/注册` | 注册账号（QQ 邮箱 6 位验证码） |
| `/bindemail` | `/绑定邮箱`、`/emailbindme` | 绑定或补绑 QQ 邮箱 |
| `/emailbind <accountId> [reset]` | — | 管理员查询或清除指定账号的邮箱绑定 |
| `/migrateaccount <accountId> <newQq> [--clear-email]` | — | 管理员迁移旧账号用户名与 QQ 绑定 |
| `/registerrequirement <0~72\|on\|off>` | — | 管理员设置注册前在线时长要求（0~72 小时） |

---

## 3. 游戏服配置（`config/scripts/data/config.conf`）

```json
{
  "wayzer": {
    "user": {
      "emailBind": {
        "emailServiceUrl": "http://127.0.0.1:40150/send",
        "emailServiceToken": "<与 email-service/config.json 一致的 internalToken>",
        "emailCodeTtlMinutes": 20,
        "emailInputTimeoutSeconds": 300,
        "ipDailyRegisterLimit": 3,
        "bindRewardOld": 1000,
        "bindRewardNew": 300,
        "codeSendCooldownSeconds": 60,
        "codeSendDailyLimit": 10,
        "ipHourlySendLimit": 5,
        "accountHourlySendLimit": 3,
        "registerEmailHourlyLimit": 30
      },
      "emailOnboarding": {
        "promptDelaySeconds": 10
      }
    }
  }
}
```

> 为兼容 QQ 邮箱/阿里云延迟投递，代码层对验证码有效期与输入框等待强制执行下限：**最低 20 分钟 / 300 秒**，低于该值的旧配置会被自动抬高。

---

## 4. 多层防刷限流口径

| 层级 | 维度 | 默认上限 | 说明 |
|---|---|---|---|
| 邮件服务（`email-service`） | 同邮箱冷却 / 每日 | 60 秒 / 5 封 | 防单邮箱轰炸 |
| 邮件服务（`email-service`） | 全服每日总额度 | **100 封/天** | 低于阿里云免费 200 封留余量；超限提示「注册邮件已满，请隔天再尝试」 |
| 插件侧（`emailBind.kts`） | 账号 / IP 滚动 1 小时 | 账号 3 封 / IP 5 封 | 未登录注册按客户端 UUID 充当账号身份，防切换邮箱滥发 |
| 插件侧（`emailBind.kts`） | 账号 / IP 每日 + 冷却 | 60 秒 / 10 封 | 日内总控 |
| 插件侧（`emailBind.kts`） | 全服注册滚动 1 小时 | **30 封/小时** | 仅统计 `purpose=register` 成功发送；老号补绑（`purpose=bind`）不受此项限制 |

- 验证码最多 5 次尝试；发送日志输出 `[emailBind] 邮件服务发送成功/失败 purpose=... elapsedMs=...`，便于区分 API 延迟与 QQ 邮箱投递延迟。

---

## 5. 数据存储（`MdtSettings` 键）

```text
email.bind.<accountId>                    已绑定邮箱
email.index.<sha256(email)>               邮箱到 accountId 索引
qq.index.<qq>                             QQ 到 accountId 索引
email.reward.<accountId>                  done（防重复发放奖励）
email.lock.<accountId>                    绑定前原信任等级快照
email.ip.<sha256(ip)>                     日期|注册次数
email.send.account:<id> / ip:<hash>       日期|发送次数|最后发送毫秒
email.send.hour.account:<id> / uuid:<id>  最近 1 小时成功发送时间戳
email.send.hour.ip:<sha256(ip)>           同 IP 最近 1 小时成功发送时间戳
email.register.send.window                全服注册 1 小时滚动窗口时间戳
email.prompt.bind.dismissed.<accountId>   不再提示（补绑）
email.prompt.register.dismissed.<uuid>    不再提示（注册）
serverFeatures.registrationOnlineRequirementHours  注册前在线小时数（0~72）
```

---

## 6. 已知限制

- 入服弹窗检测覆盖 `MenuV3`、`MenuBuilder`、`textInput`，极旧 `MenuV2` 菜单未纳入；
- 入服仅在第 10 秒检查一次，若此时已有弹窗则该次进服不再补弹；
- 用户名非 QQ 号的历史旧账号需由管理员使用 `/migrateaccount` 迁移。
