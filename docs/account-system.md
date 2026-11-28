# MDT 账号系统说明

> 相关脚本：`scripts/wayzer/user/accountAuth.kts`、`accountPassword.kts`、`serverFeatureSettings.kts`、`accountGuestControl.kts`、`accountIpGuard.kts`、`wayzer/lib/MdtStorage.kt`。  
> 主服（`main`）QQ 邮箱绑定细节另见 [account-email-binding.md](account-email-binding.md)。

---

## 1. 核心规则与主分服差异

- **账号唯一标识**：QQ 号作为唯一账号名；登录成功后业务主体由设备 UUID 切换为 `account:<id>`。
- **主服 vs 分服注册校验差异**：
  - **主服 MDT DO（`main` 分支）**：已移除旧 4 位 `/captcha` 注册验证码；注册与老号补绑均通过 QQ 邮箱验证码完成（详见 [account-email-binding.md](account-email-binding.md)），入服 10 秒后若无其他弹窗会显示注册/绑定引导；管理员可用 `/registerrequirement <0~72|on|off>` 配置注册前累计在线小时数（默认 1 小时，0=关闭）。
  - **分服 MDC DO（`mdc-do` 分支）**：不启用邮箱绑定与 `email-service`，保留原有 `/captcha` 4 位验证码注册流程，入服不弹绑定引导。
- **自动登录**：仅校验游戏 UUID + USID，不校验 IP，避免玩家切换网络后频繁重新登录。
- **密码安全**：使用 JDK `PBKDF2WithHmacSHA256` + 随机盐（120000 迭代）哈希存储，不保存明文；手动 `/login` 无需验证码。
- **已绑定默认等级**：注册并登录后进入已绑定玩家默认等级下限（默认 `1`，同时作用于信任与资历等级，4 级管理员可通过 `/defaultboundlevel 1|2|3` 调整，只升不降）。
- **游客 MDC 迁移与封存**：
  - 新注册成功时，该游客 UUID 名下已落库的当前/累计 MDC 自动并入新账号并清零来源游客数据；
  - 若设备 UUID 已绑定账号但当前会话未登录（游客态），其 MDC 会被封存禁止消费，在连接或 `/login` 时自动并回所属账号。

---

## 2. 玩家指令

| 指令 | 别名 | 说明 |
|---|---|---|
| `/account` | `/账号` | 打开账号菜单 |
| `/register` | `/注册` | 注册 QQ 账号并自动登录（主服走 QQ 邮箱验证码；分服需先 `/captcha`） |
| `/login` | `/登录` | 登录已有 QQ 账号 |
| `/logout` | `/退出登录`、`/登出` | 结束当前登录会话并解除本机自动登录（不影响账号数据） |
| `/changepassword` | `/passwd`、`/改密`、`/修改密码` | 修改当前登录账号密码 |
| `/deleteownaccount` | `/注销账号`、`/账号注销` | 校验当前密码并二次确认后永久注销当前账号与关联业务数据 |
| `/bindemail`（仅主服） | `/绑定邮箱`、`/emailbindme` | 绑定或补绑 QQ 邮箱 |
| `/captcha`（仅分服） | `/验证码` | 获取 4 位注册验证码 |

---

## 3. 管理指令

账号与风控管理指令除声明权限节点外，指令体内均会二次校验已登录玩家等级（控制台始终可用）：

| 指令 | 权限 | 说明 |
|---|---|---|
| `/setpassword <QQ账号>` | `wayzer.admin.account`（`@admin`） | 重置密码；游戏内弹窗输入避免明文进聊天日志；控制台用 `setpassword <QQ> <新密码> confirm` |
| `/deleteaccount <QQ/3位ID/UUID/account:id> [confirm]` | `wayzer.admin.account` | 删除账号、解除设备绑定并清空关联业务数据（不可逆） |
| `/accountqq <目标>` | `wayzer.admin.account` | 查询 QQ 号、账号 ID、状态与主体 UID（别名 `/qqof`、`/查qq`、`/queryqq` 等） |
| `/emailbind <accountId> [reset]`（仅主服） | `wayzer.admin.account` | 查询或清除账号邮箱绑定 |
| `/migrateaccount <accountId> <newQq> [--clear-email]`（仅主服） | `wayzer.admin.account` | 迁移旧账号用户名/QQ 绑定 |
| `/banX <3位ID> <分钟> <原因>` / `/unbanX <目标>` / `/banlist` | `wayzer.admin.ban` / `unban` | 封禁、解封与统一封禁列表（含账号封禁与 IP 封禁） |
| `/guestob [on\|off\|status\|release]` | `wayzer.admin.account` | 管理“今日未登录玩家强制观战”；`release` 尝试释放观战残留 |
| `/ipguard status\|check\|risk\|unrisk\|release\|unbind` | `wayzer.admin.ipguard` | 管理风险 IP 与同 IP 记录（`unblock` 为 `unrisk` 别名） |
| `/serverfeatures [status\|menu]` | `wayzer.admin.serverFeatures`（4 级） | 服务器功能设置总览菜单 |
| `/mdcmultiplier [0~20]` | 4 级 | 贡献结算 MDC 倍率（默认 `1`，最多两位小数） |
| `/forumtoggle [on\|off]` | 4 级 | 帖子系统总开关 |
| `/registerrequirement [0~72\|on\|off]` | 4 级 | 注册前在线时长要求（主服支持 0~72 小时） |
| `/socialactions [on\|off]` | 4 级 | 点赞、点踩与认可开关 |
| `/defaultboundlevel [1\|2\|3]` | 4 级 | 已绑定玩家默认信任与资历等级下限 |

---

## 4. 游客强制观战投票与密码限制

- **今日游客强制观战投票**：`/vote guestOb`（别名 `游客观战`、`未登录观战`）仅限已登录玩家发起与投票，通过后当天未登录玩家会被强制观战，次日自动失效；`/vote guestObOff`（别名 `解除游客观战`）可提前解除（仅释放本脚本记录的观战）。
- **密码与防爆破**：密码长度 `6~64` 位、不含空格换行；同 UUID 或同 IP 连续登录失败 `5` 次冷却 `60` 秒；一个 QQ 仅对应一个账号；已绑定账号的设备 UUID 不可直接登录/注册其他账号；同一账号不可多端同时在线。

---

## 5. IP 防熊（`accountIpGuard.kts`）与已知边界

- 允许单 IP 登录多个已有账号（不再强制 `1IP=1账号`）。
- **同 IP 小号提示**：同 IP 在默认 24 小时窗口内换号/换 UUID 入服，延迟 3 秒检查并公开广播「可能为上次同 IP 玩家的小号」，**仅显示名字与短 ID，不公开完整游戏 UUID**（服务端日志仍留完整记录供管理排查）；同一组合 60 秒内去重。
- **风险 IP 规则**：某 IP 触发 `/banX` 或已有封禁账号从该 IP 尝试进入时立即标记风险 IP（默认 24 小时）；同 IP 24 小时内累计 2 次投票踢出触发风险标记。风险期内禁止注册新账号、未登录玩家强制观战并轻度禁言，已登录或手动 `/login` 账号不受影响；本机/内网 IP 默认忽略。
- **已知设计局限（维持现状，不做行为规则扩展）**：风险期会被封禁账号重连续期；`/login` 入口本身不单独二次校验封禁状态（登录态仍受封禁系统拦截）；按维护者决策不引入复杂行为语义风控规则。

---

## 6. 数据库表与权限隔离

- 主要数据表：`MdtAccounts`、`MdtAccountBindings`、`MdtPlayerSubjects`、`MdtIpAccountBindings`（旧兼容清理用）、`MdtSettings`（保存今日游客观战日期、风险 IP、服务器功能设置与邮箱绑定索引）。
- **会话权限隔离**：未登录在线会话始终按 `0` 级游客处理，不会继承数据库中该 UUID 的历史等级或 Mindustry 原生 `@admin` 标记；管理、Wiki/帖子编辑与管理员技能均要求当前会话完成登录或自动登录。
