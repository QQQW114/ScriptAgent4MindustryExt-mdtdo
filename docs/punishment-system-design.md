# MDT 惩罚与检举系统规范

> 对应脚本：
> - 规则、事件与持久化：`scripts/wayzer/ext/playerMute.kts`
> - 检举流程与管理命令：`scripts/wayzer/user/punishmentReport.kts`
> - 强制观战与投票观战：`scripts/wayzer/cmds/voteOb.kts`
> - 投票禁言与禁言自救：`scripts/wayzer/cmds/voteMute.kts`
> - 禁止建造：`scripts/wayzer/ext/playerBuildBan.kts`
> - 玩家信息面板展示：`scripts/wayzer/ext/playerInfoTripleTap.kts`

---

## 1. 权限层级边界

系统将玩家实时等级分为两层（按 `wayzer/user/trustLevel.kts` 判定，未登录会话一律按 0 级游客处理）：

- **管理员层级**：`3++` 级与 `4` 级（含已登录的原生 admin）。
  - 施加限制不受 24 小时上限与重复冷却约束；
  - 施加惩罚**不触发检举弹窗**，不计入检举封禁。
- **非管理员层级**：`0`、`1`、`2`、`3`、`3+` 级。
  - 所有限制性惩罚受 24 小时上限、24 小时重复冷却与检举保护约束。

---

## 2. 非管理员惩罚上限与冷却规则

| 惩罚类型 | 非管理员直罚（面板/命令） | 玩家投票惩罚（非管理员发起） | 管理员层级（`3++` / `4`） |
|---|---|---|---|
| **禁言** | 临时最长 **1440 分钟**（超限自动钳制并提示）；永久请求**直接拒绝** | `/vote mute` 发起前与通过后双重校验，最长 **1440 分钟** | 永久或任意时长（`/vote mute` 最高 72 小时） |
| **禁建** | 临时最长 **1440 分钟**（超限自动钳制）；永久请求**直接拒绝** | — | 永久或任意时长 |
| **强制观战** | 自动附带 **1440 分钟**到期自动解除（每 30 秒扫描与入服检查） | `/vote ob` 属社区决议，**无 24 小时到期限制**（需 `/vote quitOb` 或管理员/检举解除） | 无期限 |

### 跨类型共用 24 小时重复冷却
- 按 `(施罚者主体, 目标主体)` 配对记录，**跨禁言、强制观战、禁建三类惩罚共用**同一个 24 小时窗口（默认 `punishRepeatCooldownHours=24`）。
- 窗口内非管理员不得对同一目标再次施加限制（投票类在发起前与通过后均校验）。
- 同一设备 UUID 结合 `PlayerData.ids` 聚合判定，跨重启持久化保存在 `MdtSettings`。

---

## 3. 检举系统规则

### 3.1 触发条件
当 **已登录（非游客）玩家** 被 **非管理员层级（`< 3++`）** 施加禁言、强制观战或禁建时触发（系统自动清理、风控、`3++`/`4` 级施罚、目标为游客均不触发）：

- **直接限制（面板/命令）**：满足在线人数 ≥ `minOnlinePlayers`（默认 3）且距上次弹窗 ≥ `popupMinIntervalMillis`（默认 60 秒）时，延迟 `popupDelayMillis`（默认 3 秒）向被罚者弹出检举菜单（显示施罚者名字与惩罚原因）；关闭后可在 `contextValidMinutes`（默认 30 分钟）内用 `/punishreport` 重开。
- **投票限制（`/vote ob`、`/vote mute` 通过）**：采用两段式弹窗，先选检举理由（默认：`对方恶意投票` / `对方投错了` / `对方判断错了` / 自定义输入），再发起检举投票。

### 3.2 检举投票口径
- **发起与投票资格**：**仅已绑定（已登录账号）玩家**可发起和参与投票，游客不弹窗也不计入分母；被检举者本人（同账号）不可投票。
- **通过阈值**：赞成率默认 **`passRatePercent = 75`**（`ceil(可投人数 × 75%)`，至少 1 票）。
- **自救放行**：被罚者即使正处于强制观战或被禁止发起投票状态，也可发起并参与自己的检举投票（`bypassDenyVote` + `ignoreStartBan=true`）。

### 3.3 检举通过后的效果与双封禁阶梯
检举投票通过后（按 `recordId` 幂等执行，支持目标离线）：
1. **定向解罚**：发射 `PunishmentLiftedEvent`，仅解除被检举的那一条惩罚记录，不影响其他限制。
2. **封禁施罚者权限并累计次数**：
   - 每次被成功检举：禁止施罚者**直接限制权限 1 天**（禁言/观战/禁建）；
   - 若该次惩罚来源为**投票限制**：**额外**禁止其**发起限制类投票（`/vote ob`、`/vote mute`）1 天**；
   - 累计被成功检举 **≥ 3 次**：**永久禁止直接限制权限**（`permanentDirectBanStrikes = 3`）；
   - 累计被成功检举 **≥ 6 次**：**附加永久禁止发起限制类投票**（`permanentVoteStartBanStrikes = 6`）。
3. **豁免与界面**：`3++`/`4` 级实时豁免；被封禁者在玩家信息面板（`playerInfoTripleTap.kts`）中对应的限制按钮会被替换为封禁提示，面板同步展示目标的累计被成功检举次数与双封禁状态。

> 注：`/vote kick`、清场类与账号/IP 封禁目前未接入检举封禁拦截（规则层已预留查询 API）。

---

## 4. 禁言拦截边界与自救投票

- **禁言拦截范围**（`playerMute.kts` `shouldBlockMutedInput`）：拦截普通聊天及 `/t`、`/a`、`/vote`、`/投票`、`/votekick`；**不拦截 `/r` 与私聊命令**（已明确维持现状）。
- **自救投票白名单**（防止“禁言 + 强制观战”死锁）：`SELF_RESCUE_VOTE_SUBCOMMANDS` 放行 `/vote quitOb`（别名 `quob`、`解除观战`、`退出观战`）与 `/vote quitMute`（别名 `解除禁言`、`解禁`），大小写不敏感。
- **`/vote quitMute`**：仅被禁言者本人可发起，默认 50% 通过，**只解除临时禁言**；永久禁言会提示联系管理员或通过 `/punishreport` 检举；被禁投票/被观战/未完成投票教程时均可自救。

---

## 5. 指令与配置

### `/punishreport`（别名 `检举`、`jubao`、`reportpunish`）
无顶层权限节点，按子命令内等级校验：

| 子命令 | 权限 | 说明 |
|---|---|---|
| 无参 / `report` / `检举` / `发起` | 已登录玩家（游戏内） | 检举 30 分钟内最近一次受非管理员施加的限制 |
| `bans` / `list` / `列表` / `封禁列表` | `3++` 及以上 | 查看当前检举封禁名单、双封禁剩余时间与累计次数 |
| `status` / `状态` `<玩家>` | `3++` 及以上 | 查询指定玩家的被成功检举次数与封禁状态 |
| `reset` / `清除` / `unban` `<玩家>` | 仅 `4` 级 / admin | 清除该玩家的检举封禁记录与累计次数（不清 24h 冷却） |

### 核心配置项（`config/scripts/data/config.conf`）

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `wayzer.ext.playerMute.nonAdminMaxPunishMinutes` | `1440` | 非管理员单次带时长惩罚上限（分钟） |
| `wayzer.ext.playerMute.punishRepeatCooldownHours` | `24` | 同 `(施罚者, 目标)` 跨类型冷却（小时） |
| `wayzer.ext.playerMute.reportBanHours` | `24` | 检举成功后禁直接限制时长（小时） |
| `wayzer.ext.playerMute.reportVoteStartBanHours` | `24` | 投票来源检举成功额外禁发起限制类投票时长（小时） |
| `wayzer.ext.playerMute.permanentDirectBanStrikes` | `3` | 累计成功检举永久禁直接限制阈值 |
| `wayzer.ext.playerMute.permanentVoteStartBanStrikes` | `6` | 累计成功检举永久禁限制类投票阈值 |
| `wayzer.user.punishmentReport.enabled` | `true` | 是否启用检举系统 |
| `wayzer.user.punishmentReport.passRatePercent` | `75` | 检举投票赞成率阈值（%） |
| `wayzer.user.punishmentReport.popupDelayMillis` | `3000` | 惩罚生效后延迟弹窗（ms） |
| `wayzer.user.punishmentReport.popupMinIntervalMillis` | `60000` | 同一玩家两次检举弹窗最小间隔（ms） |
| `wayzer.user.punishmentReport.minOnlinePlayers` | `3` | 触发弹窗所需最低在线人数 |
| `wayzer.user.punishmentReport.contextValidMinutes` | `30` | `/punishreport` 重开检举有效期（分钟） |
| `wayzer.user.punishmentReport.voteReportReasonOptions` | 3 项预设 | 投票限制两段式检举的默认理由列表 |

---

## 6. 架构、持久化与维护注意

- **单向依赖架构**：规则、嵌套事件类（`PunishmentAppliedEvent` / `PunishmentLiftedEvent`）与持久化均收口在 `playerMute.kts`，不改动 `wayzer/lib/*.kt`；`voteMute.kts`、`voteOb.kts`、`playerBuildBan.kts`、`punishmentReport.kts` 均单向依赖 `playerMute.kts`，解罚通过 `listenTo<PunishmentLiftedEvent>` 异步解耦。
- **持久化（`MdtSettings` 两键，异步预加载与 IO 协程串行落盘）**：
  - `punishment.report.bans`：每行 `uid|untilMillis|strikeCount|voteStartUntilMillis|name`（`Long.MAX_VALUE` 表示永久，兼容旧 4 字段格式读取）；
  - `punishment.report.cooldowns`：每行 `operatorUid|targetUid|untilMillis`。
  - 临时禁言/临时禁建仍为内存态，强制观战到期时间 `forceObExpireAt` 为 `@Savable` 会话态（重启后不跨重启保留）。
- **重载联动注意**：`playerInfoTripleTap.kts` 依赖 `punishmentReport.kts`；单独热重载惩罚脚本后需同步重载面板脚本（`sa load wayzer/ext/playerInfoTripleTap`）。
