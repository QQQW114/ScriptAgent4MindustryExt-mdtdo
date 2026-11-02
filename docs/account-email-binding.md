# QQ 邮箱绑定 / 注册验证（仅 `release/account-email-binding` 分支）

> 本功能**只用于准备纳入邮箱验证体系的那一个服**；另一个服继续用 `main`（不含本功能）。
> 分支：`release/account-email-binding`（由协作者 `feat/account-email-binding` 合并 + Lead 审阅修正）。
> 邮件服务：`email-service/`（本机 `127.0.0.1:40150`，只负责发信；部署见 `email-service/DEPLOY-服务器.md`）。

## 1. 功能行为

- **新账号注册**：`/register` → 两次密码 → 输入 `数字@qq.com` → 收 6 位验证码 → 输入验证码 → 创建账号（username = QQ 号）→ 绑定邮箱 → 发 **300 MDC** → 自动登录；
- **老账号补绑**：`/bindemail` → 输入与账号 QQ 一致的 `数字@qq.com` → 收码 → 验证 → 绑定 → 发 **1000 MDC** → 恢复被锁等级；
- **未补绑老账号**：登录后信任/资历等级**封顶到 2 级并锁定**（3++/4 级管理员排除；低等级不会被抬高），补绑后恢复原等级；
- **唯一性**：一个账号一个邮箱；一个 QQ/邮箱只能绑一个账号；
- **验证码**：6 位、10 分钟有效、最多尝试 5 次；发送前有账号/IP 冷却与每日上限（防换邮箱刷额度）；
- **注册 IP 限制**：每 IP 每天最多 3 次成功注册；
- **管理员**：
  - `/emailbind <accountId>` 查询绑定邮箱；`/emailbind <accountId> reset` 清除邮箱绑定；
  - `/migrateaccount <accountId> <newQq> [--clear-email]` 改名（只改 username/QQ binding，保留 account:<id>、MDC、称号、资历）；
  - `/migrateaccount` 也支持 `config/stats/account-migrate-requests.jsonl` 文件队列（结果写 `account-migrate-results.jsonl`）。

## 2. 游戏服配置

`config/scripts/data/config.conf` 的 `wayzer.user.emailBind`：

```json
{
  "emailServiceUrl": "http://127.0.0.1:40150/send",
  "emailServiceToken": "<email-service 的 internalToken>",
  "emailCodeTtlMinutes": 10,
  "ipDailyRegisterLimit": 3,
  "bindRewardOld": 1000,
  "bindRewardNew": 300,
  "codeSendCooldownSeconds": 60,
  "codeSendDailyLimit": 10
}
```

`emailServiceToken` 不填时游戏内会提示"邮件服务 token 未配置"；不会误发信。

## 3. 数据存储（MdtSettings，不改表结构）

```text
email.bind.<accountId>         邮箱
email.index.<sha256(email)>    accountId
qq.index.<qq>                  accountId
email.reward.<accountId>       done（奖励只发一次）
email.lock.<accountId>         原信任|原资历（补绑后恢复）
email.ip.<sha256(ip)>          日期|注册次数
email.send.account:<id>        日期|发送次数|最后发送毫秒（账号限流）
email.send.ip:<sha256(ip)>     同上（IP 限流）
```

## 4. 部署顺序（仅目标服）

1. 先部署 `email-service`（见其 DEPLOY 文档），`/health` 正常；
2. 把本分支 `scripts/` 同步到该服 `config/scripts/`（**保留 data/、external-cp/、cache/**）；
3. 在 `config.conf` 填 `emailServiceToken`；
4. 冷启动，确认 `共找到167脚本,加载成功163,启用成功158,出错0`、`scriptdiag count` 失败 0、`sa listFailed` 空；
5. 用测试账号走一遍 `/register` 和 `/bindemail`，确认收码/绑定/奖励/等级恢复；
6. 另一个服用 `main`，不要合并本分支。

## 5. 验证记录（2026-11-02，开发服 B509）

- 协作者原分支：banner 正常但 `/bindemail`、`/emailbind`、`/migrateaccount` 均"无效指令"——
  原因：命令注册写在 `onEnable {}` 内，运行时未生效；已移到脚本顶层；
- 未补绑锁定原实现无条件设 2 级（低等级会被抬高），已改为"封顶 2 级"并补上资历等级锁定；
- 新增账号/IP 发送限流（原只有邮件服务侧按收件邮箱限流，可换邮箱刷额度）；
- 修正后：`167/163/158 出错0`、失败 0、`sa listFailed` 空、根指令 164；
  `/bindemail`（控制台显示仅游戏内）、`/emailbind 1`（查询）、`/migrateaccount`（改名，测试副作用已回滚）均正常；
- **未验证**：真实玩家完整的注册/收码/绑定流程、QQ 邮箱实际收信（邮件服务侧已单独实测可发）、并发注册、真实旧账号等级恢复。
  上线前请用测试账号在目标服完整跑一遍。

## 6. 安全与已知限制

- 邮件服务 token 存服务器本地配置，不进仓库；
- 只允许 `数字@qq.com`（`@foxmail.com` 会被拒绝；邮件服务侧白名单更宽但插件侧更严格）；
- 旧账号用户名不是 QQ 号的，需管理员用 `/migrateaccount` 改名后再 `/bindemail`；
- `/migrateaccount` 会改数据库绑定，务必先确认账号控制权；
- 邮件服务免费额度 150/天（服务端全局上限），注册高峰需关注。
