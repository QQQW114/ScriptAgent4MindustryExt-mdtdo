# QQ 邮箱绑定 / 注册验证（仅 `release/account-email-binding` 分支）

> 本功能**只用于准备纳入邮箱验证体系的那一个服**；另一个服继续用 `main`（不含本功能）。
> 分支：`release/account-email-binding`；邮件服务：`email-service/`（本机 `127.0.0.1:40150`，只负责发信）。
> 2026-11-02 返工：入服弹窗、只限信任等级、去掉旧 4 位注册验证码、注册在线时长管理员可配。

## 1. 行为口径

### 老玩家（已登录、未绑定邮箱）

- 进入服务器 **10 秒后**，若当时**没有任何已有弹窗/输入框**，弹出提醒；
- 未绑定期间**只限制信任等级最高 2 级**（封顶+锁定；资历等级**完全不限制**），仍可正常游玩；
- 绑定后恢复原信任等级，并奖励 **1000 MDC**；
- 按钮：立即绑定 QQ 邮箱（邮箱验证码流程）、**不再提示**（持久保存）、关闭；
- 弹窗介绍与绑定输入框（`askEmail`）均提示 QQ 邮箱技巧：**有 QQ 账号时输入「QQ号@qq.com」即为你的 QQ 邮箱**（如 `12345678@qq.com`）；
  验证码发送后打开 QQ，在消息列表等待验证码邮件即可（发送成功后的聊天提示也会再说一次）。

### 新玩家（游客/未注册）

- 进入服务器 **10 秒后**，若无已有弹窗，弹出注册引导：
  - 注册后可解锁完整功能：等级提升、技能系统、MDC 经济、成就等；
  - 注册必须绑定 QQ 邮箱（邮箱验证码）；
  - 流程：`/register` → 输入密码、QQ 邮箱、邮箱验证码（**已移除旧的 `/captcha` 4 位注册验证码**）；
  - 管理员可设置注册前在线时长要求（0~72 小时，0=关闭）；
  - 按钮：注册/登录账号（打开账号菜单）、**不再提示**、关闭。
- 也可随时 `/help`、`/register`、`/account` 进入。
- 注册成功后（`finishLogin` 完成自动登录）额外显示一页**注册欢迎页**（`MenuV3`，点「关闭」或 ESC 可关，不影响后续）：
  - 恭喜你已经完成账号注册；
  - 请尽快加入服务器交流群，群号可输入 `/wiki` 查看；
  - 请常关注 `/wiki` 和 `/post`；
  - 双击你或他人的核心机可以快速打开玩家面板；
  - 欢迎来到 MDTDO！
- 注册失败/取消（含邮箱验证失败）不显示欢迎页。

### 等级/奖励

- 未绑定：信任等级封顶 2 级并锁定（低等级不会被抬高；3++/4 级工作人员除外）；资历等级不限制；
- 绑定成功：恢复锁定前信任等级；
- 奖励：老账号补绑 **1000 MDC**；新账号注册绑定默认 **300 MDC**（`bindRewardNew` 可调）；只发一次，失败不影响绑定。

## 2. 指令与帮助菜单

| 指令 | 说明 |
|---|---|
| `/register` | 注册（QQ 邮箱验证码；已移除旧 4 位 `/captcha`） |
| `/bindemail` | 绑定/补绑 QQ 邮箱（验证码；别名 `绑定邮箱`/`emailbindme`） |
| `/emailbind <accountId> [reset]` | 管理员查询/清除邮箱绑定 |
| `/migrateaccount <accountId> <newQq> [--clear-email]` | 管理员迁移账号用户名/QQ binding |
| `/registerrequirement <0~72|on|off>` | 管理员设置注册前在线时长（0=关闭，默认 1 小时） |

帮助菜单：玩家指令 → 注册账号/绑定QQ邮箱；管理指令 → 注册在线时长要求/邮箱绑定管理/迁移账号QQ。**已移除 `/captcha` 帮助条目。**

## 3. 游戏服配置

`config/scripts/data/config.conf`：

```json
{
  "wayzer": {
    "user": {
      "emailBind": {
        "emailServiceUrl": "http://127.0.0.1:40150/send",
        "emailServiceToken": "<email-service internalToken>",
        "emailCodeTtlMinutes": 10,
        "emailInputTimeoutSeconds": 180,
        "ipDailyRegisterLimit": 3,
        "bindRewardOld": 1000,
        "bindRewardNew": 300,
        "codeSendCooldownSeconds": 60,
        "codeSendDailyLimit": 10,
        "registerEmailHourlyLimit": 30
      },
      "emailOnboarding": {
        "promptDelaySeconds": 10
      }
    }
  }
}
```

注册在线时长存在 MdtSettings：`serverFeatures.registrationOnlineRequirementHours`（0~72，默认 1；旧布尔开关自动迁移为 0/1）。

## 4. 邮件服务额度（2026-11-02 新口径）

- 服务器邮件服务**每天最多 100 封**（`email-service/config.json` → `limits.globalDailyLimit = 100`）；
- 超过后邮件服务返回 `429 SERVICE_DAILY_LIMIT`，插件统一显示：
  **「注册邮件已满，请隔天再尝试」**；
- 其它错误：邮箱冷却/当日次数/服务配置异常分别有中文提示；
- 阿里云免费额度仍为每天 200 封，服务端留余量（100）。

### 注册验证码防刷（2026-11-02 追加）

- 插件侧增加**全服全局滚动 1 小时窗口**的注册验证码限流：只统计 `purpose=register` 的**成功发送**，
  全服所有玩家合计最多 **30 封/小时**（`emailBind.registerEmailHourlyLimit` 可配，默认 30）；
- 达到上限后拒绝发送并提示：**「注册邮件发送过于频繁，请稍后再试」**，不消耗邮件服务额度；
- 绑定/补绑（`purpose=bind`）**不受**该限制，仍受邮箱 60s/5 封每日、服务全局 100 封/日、账号/IP 每日 10 次等限制；
- 窗口时间戳存 MdtSettings `email.register.send.window`（逗号分隔，只保留最近 1 小时）。

## 5. 数据存储（MdtSettings）

```text
email.bind.<accountId>          邮箱
email.index.<sha256(email)>     accountId
qq.index.<qq>                   accountId
email.reward.<accountId>        done（奖励只发一次）
email.lock.<accountId>          原信任等级（旧 "信任|资历" 快照会在绑定时清理资历锁定）
email.ip.<sha256(ip)>           日期|注册次数
email.send.account:<id>         日期|发送次数|最后发送毫秒（账号限流）
email.send.ip:<sha256(ip)>      同上（IP 限流）
email.register.send.window      最近 1 小时新注册验证码成功发送时间戳（逗号分隔，滚动窗口）
email.prompt.bind.dismissed.<accountId>   不再提示（绑定）
email.prompt.register.dismissed.<uuid>    不再提示（注册）
serverFeatures.registrationOnlineRequirementHours  注册前在线小时数（0~72）
```

## 6. 可靠性/兜底设计

- 验证码：6 位、10 分钟有效、最多 5 次尝试；邮箱/验证码输入超时 **180 秒**（密码输入也 180 秒）；
- 发送限流：服务端按收件邮箱 60s / 5 次每日 / 全局 **100** 每日；插件按账号/IP 60s + 每日 10 次；**注册**另加全服全局滚动 1 小时 30 封成功发送上限；
- 邮件服务请求的 `purpose` 按流程传 `register` / `bind`（原硬编码 `bind` 已修复），便于区分注册/绑定用途；
- 邮件服务每日上限/错误均映射成玩家可读提示，不阻塞游玩/登录；
- 入服弹窗检测 `MenuV3` / 旧式 `MenuBuilder` / `textInput`，有弹窗则不打扰；弹窗失败回退聊天提示；
- 注册欢迎页只在注册成功后展示（`MenuV3`，可关闭）；渲染失败只记日志，不影响已完成的注册/自动登录；
- 点击按钮/取消/玩家退出会关闭 MenuV3 会话，避免状态残留；
- “不再提示”写库失败只记日志；信任等级/奖励 DB 操作失败不阻断登录/绑定；
- 注册在线时长由管理员命令修改，旧数据自动迁移。

## 7. 部署顺序（仅目标服）

1. 部署 `email-service`（`limits.globalDailyLimit=100`），`/health` 正常；
2. 同步本分支 `scripts/` 到该服 `config/scripts/`（保留 data/、external-cp/、cache/）；
3. `config.conf` 填 `emailServiceToken`；
4. 清 `config/scripts/cache/` 冷启动，确认 banner `168 脚本 / 164 加载 / 159 启用 / 出错 0`、`scriptdiag` 失败 0、`sa listFailed` 空；
5. 测试 `/register` 与 `/bindemail`、弹窗、`/registerrequirement 0|10`；
6. 另一个服保持 `main`。

## 8. 验证记录（2026-11-02，开发服 B509）

- 去 captcha、在线时长改小时、100 封/天与“注册邮件已满”提示后：
  - 冷启动：**168 脚本 / 164 加载 / 159 启用 / 出错 0**；`scriptdiag` 失败 0；`sa listFailed` 空；根指令 **163~164**（`/captcha` 已移除；当前开发服 `scriptdiag` 显示 164）；
  - `/captcha` 已变成“无效指令”；`/bindemail`、`/register` 命令存在；
  - `/registerrequirement` 查询/设置实测：默认 1 小时 → `10` 小时 → `0`（关闭）→ 回到 `1` 小时均生效；
  - `email-service` 临时 mock 限额测试：全局 1 封时第一封 mock 成功，第二封不同邮箱返回 `429 SERVICE_DAILY_LIMIT`，文案 **「注册邮件已满，请隔天再尝试」**；正式配置 `globalDailyLimit=100`、health 正常。
- 未验证（需实机玩家）：真实收码/完整注册、弹窗按钮交互、不同客户端版本。

### 2026-11-02 追加校验（提示/欢迎页/邮件模板 + 注册 1 小时全服 30 封限流）

- 代码：绑定弹窗/`askEmail` 加 QQ 邮箱提示；注册成功后 `MenuV3` 欢迎页（可关闭，失败/取消不显示）；
  `sendCode` 的 `purpose` 按流程传 `register`/`bind`；新增 `registerEmailHourlyLimit=30`（滚动 1 小时**全服全局**成功发送上限，仅 `purpose=register`）。
- 冷启动（B509 开发服二次）：**168 脚本 / 164 加载 / 159 启用 / 出错 0**；`sa listFailed` 空；
  `emailBind` / `emailOnboarding` / `accountAuth` 均 `Enabled`；KDoc 嵌套块注释扫描 **0 命中**（215 文件）。
- email-service：本地 `config.json` 发件人显示名改 `MDTDO服务器`（不入库）、正文改「本邮件 N 分钟内有效」；
  停旧进程重启后 `GET /health` 返回 `ok:true`（未真实发信）。
- 未实机验证：欢迎页实际弹出/关闭、QQ 提示展示效果、30 封/小时限流触发（需实机玩家与真实邮件发送）。

## 9. 已知限制

- 入服弹窗检测覆盖 `MenuV3`、`MenuBuilder`、`textInput`；极旧 `MenuV2` 菜单未纳入；
- 弹窗只在进入 10 秒检查一次，有弹窗则本轮不弹；
- 新账号默认奖励 300 MDC（老账号 1000），如需统一改 `bindRewardNew`；
- 旧账号用户名非 QQ 号需管理员 `/migrateaccount`。
