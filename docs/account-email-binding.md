# QQ 邮箱绑定 / 注册验证（仅 `release/account-email-binding` 分支）

> 本功能**只用于准备纳入邮箱验证体系的那一个服**；另一个服继续用 `main`（不含本功能）。
> 分支：`release/account-email-binding`；邮件服务：`email-service/`（本机 `127.0.0.1:40150`，只负责发信，部署见 `email-service/DEPLOY-服务器.md`）。
> 2026-11-02 返工：按用户口径重做入服弹窗、信任等级限制、注册引导与验证码可靠性。

## 1. 行为口径

### 老玩家（已登录、未绑定邮箱）

- 进入服务器 **10 秒后**，若当时**没有任何已有弹窗/输入框**，弹出提醒：
  - 说明邮箱未绑定；
  - **只限制信任等级最高 2 级**（实现为封顶+锁定；资历等级**完全不限制**）；
  - 仍可正常游玩，不强制绑定；
  - 绑定后恢复原信任等级，并奖励 **1000 MDC**；
  - 按钮：**立即绑定 QQ 邮箱**（进入绑定流程，邮箱验证码）、**不再提示**（持久保存，不再弹）、关闭。
- 未绑定期间：`/bindemail` 可随时绑定；管理员可用 `/emailbind <accountId> [reset]` 查询/清除。

### 新玩家（游客/未注册）

- 进入服务器 **10 秒后**，若无已有弹窗，弹出注册引导：
  - 介绍注册后可解锁完整功能：等级提升、技能系统、MDC 经济、成就与更多玩法；
  - 说明注册**必须绑定 QQ 邮箱（邮箱验证码）**，否则无法注册；
  - 说明基本流程：`/captcha` 获取注册验证码 → `/register` → 输入密码、QQ 邮箱、邮箱验证码；
  - 按钮：**注册 / 登录账号**（打开账号菜单，内含注册入口）、**不再提示**、关闭。
- 玩家随时可通过 `/help` 玩家指令、`/register`、`/account` 进入注册/登录。

### 等级/奖励

- 未绑定：信任等级封顶 2 级并锁定（低等级不会被抬高；3++/4 级工作人员除外），**资历等级不限制**；
- 绑定成功：恢复锁定前的信任等级，资历等级不动；
- 奖励：老账号补绑 **1000 MDC**；新账号注册绑定默认 **300 MDC**（可在配置 `bindRewardNew` 调整）；
- 奖励只发一次（`email.reward.<accountId> = done`）；发奖励失败不影响绑定，只记日志。

## 2. 指令与帮助菜单

| 指令 | 说明 |
|---|---|
| `/bindemail` | 绑定/补绑 QQ 邮箱（验证码；别名 `绑定邮箱`/`emailbindme`） |
| `/emailbind <accountId> [reset]` | 管理员查询/清除邮箱绑定 |
| `/migrateaccount <accountId> <newQq> [--clear-email]` | 管理员迁移账号用户名/QQ binding（保留 account ID、MDC、称号、资历） |
| `/register`、`/account`、`/captcha`、`/login` | 账号注册/登录流程（注册强依赖邮箱验证码） |

帮助菜单已补充分区：玩家指令 → 注册账号/绑定QQ邮箱；管理指令 → 邮箱绑定管理/迁移账号QQ（`coreMindustry/menu.kts`）。

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
        "codeSendDailyLimit": 10
      },
      "emailOnboarding": {
        "promptDelaySeconds": 10
      }
    }
  }
}
```

`emailServiceToken` 为空时游戏内会提示"邮件服务 token 未配置"，不会误发信。

## 4. 数据存储（MdtSettings）

```text
email.bind.<accountId>          邮箱
email.index.<sha256(email)>     accountId
qq.index.<qq>                   accountId
email.reward.<accountId>        done（奖励只发一次）
email.lock.<accountId>          原信任等级（新格式只存信任；旧 "信任|资历" 会在绑定后顺手清理资历锁定）
email.ip.<sha256(ip)>           日期|注册次数
email.send.account:<id>         日期|发送次数|最后发送毫秒（账号限流）
email.send.ip:<sha256(ip)>      同上（IP 限流）
email.prompt.bind.dismissed.<accountId>       不再提示（绑定）
email.prompt.register.dismissed.<uuid>        不再提示（注册）
```

## 5. 可靠性/兜底设计

- 验证码：6 位、10 分钟有效、最多 5 次尝试；
- 邮箱/验证码输入超时 **180 秒**（`emailInputTimeoutSeconds`），密码输入也放宽到 180 秒，减少玩家在输入框思考/等邮件时被 60 秒超时打断；
- 发送限流：服务端按收件邮箱 60s/5 次每日/全局 150 日；插件按账号/IP 60s + 每日 10 次，防换邮箱刷额度；
- 注册 IP 限制：每 IP 每天 3 次成功注册；
- 邮件服务不可用/错误：游戏内提示重试，不阻塞游玩/登录；
- 入服弹窗：只在 10 秒时检查一次；检测 `MenuV3`、旧式 `MenuBuilder`、`textInput` 是否已打开，有则本轮不打扰；
- 弹窗打开失败（客户端不支持等）：回退为聊天提示，不崩；
- 弹窗点击后：先关闭菜单再进入输入流程；`onCancel`/玩家退出会关闭 MenuV3 会话，避免状态残留；
- “不再提示”写 DB 失败只记日志，不阻断；
- 信任等级/奖励 DB 操作有 `runCatching`，失败不阻断登录/绑定。

## 6. 部署顺序（仅目标服）

1. 先部署 `email-service`（见其 DEPLOY 文档），`/health` 正常；
2. 把本分支 `scripts/` 同步到该服 `config/scripts/`（**保留 data/、external-cp/、cache/**）；
3. 在 `config.conf` 填 `emailServiceToken`（及可选参数）；
4. 清理 `config/scripts/cache/` 后冷启动，确认 banner `168 脚本 / 164 加载 / 159 启用 / 出错 0`、`scriptdiag count` 失败 0、`sa listFailed` 空；
5. 用测试账号走 `/register` 与 `/bindemail`，确认收码、绑定、奖励、等级恢复、弹窗；
6. 另一个服继续 `main`，不要合并本分支。

## 7. 验证记录（2026-11-02，开发服 B509）

- 协作者原分支命令注册在 `onEnable` 内导致运行时不生效 → 已移到顶层；
- 早期“未绑定无条件设 2 级”会抬高低等级 → 改为封顶 2 级；资历等级不再限制；兼容清理旧 `信任|资历` 快照；
- 新增账号/IP 发送限流；新增入服弹窗脚本 `emailOnboarding.kts`；菜单库补充“是否有弹窗打开”检测；help 菜单分区补充；
- 冷启动（返工后）：**共找到 168 脚本，加载成功 164，启用成功 159，出错 0**；`scriptdiag` 失败 0；`sa listFailed` 空；
- **未验证（需实机玩家）**：弹窗实际显示/按钮点击、`/register` 全流程真实收码、弹窗与已有菜单交互、不同客户端版本表现。

## 8. 已知限制

- 入服弹窗检测覆盖 `MenuV3`、旧式 `MenuBuilder`、`textInput`；极少数仍使用 `MenuV2` 的脚本未纳入检测（目前主要是地图菜单），可能被弹窗覆盖；
- 弹窗只在进入 10 秒检查一次；若此时有菜单/输入框，本轮不弹（下次加入再试）；
- 新账号默认奖励 300 MDC（老账号 1000），如需要统一为 1000 改 `bindRewardNew`；
- 旧账号用户名不是 QQ 号的，需管理员 `/migrateaccount` 改名后再 `/bindemail`。
