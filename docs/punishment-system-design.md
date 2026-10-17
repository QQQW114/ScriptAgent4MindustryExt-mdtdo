# MDT 惩罚体系改造设计（检举系统 + 非管理员惩罚时限/冷却）

> 状态：**最终实现记录（第二阶段已回填）**，2026-10-17。实现提交 `f845d19`（punish-system），运行态已重载、服务器未重启；Lead 验证 `sa listFailed` 空、`scriptdiag count`=161/157/152/失败0、根指令 158（含 `punishreport`）。
> **接口以 §3 为准**：§3 已按最终实现回填（事件/规则/持久化/命令/配置/差异对照）；§1 是改造前基线盘点（历史参考），§2 是需求与口径，§5 是测试用例（按最终实现校正），§7 是决策表 + 否决项（§7.1）+ 批准项（§7.2）。
> **用户明确否决项（2026-10-17，❌ 勿再提议）**：3 条"不要加"的建议记入 **§7.1**（禁言不扩展私聊命令拦截、`/vote text|sc` 不加冷却/敏感词、不做名称规范化/冒充检测/UUID 后缀/等级显示）。
> **用户已批准项（2026-10-17，N4/D5）**：禁言拦截**豁免自救投票**已实现（`SELF_RESCUE_VOTE_SUBCOMMANDS`，`vote quitOb` 新增 `quob`/`退出观战` 别名），见 **§7.2** / §3.4 / §5 T60。
> 代码路径约定：`文件:行号` 均相对 `mdtserver/config/scripts/`。相关既有文档：`scripts-maintenance.md` 第十二批（投票禁言/游客闸门/quitOb 自救，:341-392）、`trust-system.md`（:153-160 持久化）、`security-guard.md`（:22、:76）、`database-system.md`、`agent-debug-experience.md` §8/§9（SA 静默吞异常）。

## 0. TL;DR

- **改造前现状（基线，历史参考）**：`3+` 级可通过玩家信息面板直接禁言/禁建/强制观战（目标限 `<3`）；`3++` 可处理 `<3++` 并新增账号/IP 封禁、禁止发起投票、一票否决；`4`/admin 全局。**低于 3+ 的登录玩家（以及更高级别人用投票时）只能通过投票间接施罚**：`/vote kick`、`/vote ob`、`/vote mute`。
- **改造前时长（基线）**：投票禁言 ≤3 天（4320 分钟）；面板禁言/禁建**留空=永久、无上限**；强制观战**无期限**；3++ 的账号封禁面板也不设上限（`pluginAdminMaxBanMinutes=7天` 配置未被使用）。
- **改造后（最终实现）**：非管理员带时长惩罚 ≤1440 分钟（超限自动钳制、永久选项拒绝）；强制观战无时长参数 → 自动补 1440 分钟到期解除；`(施罚者,目标)` 24h 冷却跨类型共用且跨重启；检举系统（弹窗→70% 投票→解罚 + 封施罚者限制权限 1 天 → 累计成功检举达阈值 4 次永久封）。详见 §3。
- **改造前关键缺口（基线）**：没有检举/申述通道；非管理员时长不受 24h 约束；无 `(施罚者,目标)` 冷却；无施罚者权限惩罚；关键状态未落库；投票类惩罚无统一"施罚者"口径。
- **关键落地约束/最终文件**：改 `playerMute.kts`（事件+规则+持久化）、`punishmentReport.kts`（新增）、`voteMute.kts`、`voteOb.kts`、`playerBuildBan.kts`（Q7 纳入）；**未新增 `PunishmentEvents.kt`**（并入 playerMute，原因见 §3.1）。`/vote kick`、清场类、账户封禁仍不在本轮（API 已留 TODO）。
- **口径已由 Lead 拍板（2026-10-17，详见 §2.3 / §7）**：管理员层级=仅 3++/4；成功检举 `>3` 次（默认阈值 4）永久封禁；24h 冷却按 `(施罚者 uid, 目标 uid)` **跨惩罚类型共用**；非管理员带时长惩罚**钳制到 1440 分钟**、禁言/禁建永久选项**直接拒绝**；投票类惩罚同样纳入钳制与冷却（发起前+通过后双重检查）；永久封禁范围=观战/禁言/禁建 + 发起 mute/ob 投票（kick/清场/账户封禁本轮未接）；弹窗与公开投票文案**显示施罚者名字**。Q6/Q8/Q10 按默认建议执行。
- **用户明确否决（2026-10-17，❌ 勿再提议）**：①禁言不扩展拦截 `r`/私聊/聊天类命令（`shouldBlockMutedInput` 一级命令仍只拦 `t/a/vote/投票/votekick`）；②`/vote text|sc` 不做冷却/敏感词过滤；③不做名称规范化/同名冒充检测/投票文案 UUID 后缀或等级显示。详见 §7.1。
- **用户已批准（2026-10-17，N4/D5，已实现）**：禁言拦截**豁免自救投票**——`SELF_RESCUE_VOTE_SUBCOMMANDS = {quitob, quob, 解除观战, 退出观战}`（大小写不敏感），其它 vote 子命令仍拦；理由=禁言+强制观战死锁。详见 §7.2 / §3.4 / §5 T60。

## 1. 现状盘点（改造前基线 commit `7364043`；**改造后最终形态见 §3**）

> 本节保留改造前的代码盘点，作为设计依据与历史对照；其中提到的"内存态/Savable/无上限"等现状**已被 2026-10-17 的改造部分改变**（最终行为以 §3 为准）。

### 1.1 等级体系与"管理员层级"

等级代码/排序（`wayzer/user/trustLevel.kts:13-24`、`:67-76`）：

| 等级 | 名称 | order | 说明 |
|---|---|---|---|
| 0 | 游客 | 0 | 未登录会话**一律按 0 处理**（`:90-98`，即使库里有历史等级） |
| 1 | 已绑定 | 10 | 登录后的默认等级（`defaultBoundTrustLevelCode`，`:47-50`） |
| 2 | 可信 | 20 | |
| 3 | 高可信 | 30 | |
| 3+ | 高可信+ | 35 | **可对 0/1/2 级直接施加限制**（`canDirectRestrictTrustTarget`，`:140-154`） |
| 3++ | 插件协管 | 38 | 可对 `<3++` 直接施加限制；白名单权限组 `@pluginAdmin`（`:267-284`） |
| 4 | 管理员 | 40 | 全局；在线原生 admin 也视为 4（`:94-98`），权限组 `@admin`（`:257-264`） |

- **本需求口径**（task-4，**Lead 已定**）："管理员层级 = 仅 `3++` 与 `4` 级"，即**非管理员 = 0/1/2/3/3+**；所有钳制/冷却/检举保护均以该边界判定。
- 直接限制的目标边界统一函数 `canDirectRestrictTrustTarget`（`:140-154`）：`4` 全局；`3++` 只能 `<3++`；`3+` 只能 `<3`；其它一律不行；自己不能处理自己。
- 投票/封禁类用更宽的 `canModerateTrustTarget`（`:120-131`）：`3+` 只能 `<3+`（与直接限制不同，多出对 3 级的处理权）。

### 1.2 惩罚类型 × 入口 × 谁能施罚 × 时长 × 持久化（总表）

| # | 惩罚 | 入口（代码） | 谁能施罚 | 现状时长上限 | 持久化（跨重启？） |
|---|---|---|---|---|---|
| 1 | **禁言** | 玩家信息面板按钮 `playerInfoTripleTap.kts:753-776` → `playerMute.kts:75/98`；命令 `/playermute` `playerMute.kts:177` | 面板：`3+~4`（目标按 `canDirectRestrict` 分层）；命令：`wayzer.admin.playerMute` 仅 `@admin`（4/admin） | **永久=留空；临时=任意正整数分钟，无上限**（`playerInfoTripleTap.kts:519-541`） | 永久→DB `MdtMutedPlayers`（**是**）；临时→内存 map（`playerMute.kts:17-18`，**否**） |
| 2 | **强制观战** | 玩家信息面板按钮 `playerInfoTripleTap.kts:686-752` / 命令 `/forceOB` `voteOb.kts:216-233` | 面板：`canDirectForceOb`（`3+` 可对未登录游客，或符合 `canDirectRestrict`；`4` 全局，`:606-609`）；命令：`wayzer.admin.forceOb`（`@admin` + `@pluginAdmin` → 3++/4） | **无期限**，只能手动解除/被自动清理踢出 | `@Savable(false)+customLoad` 会话态（`voteOb.kts:16-26`），**按现有文档口径不跨重启** |
| 3 | **禁建** | 玩家信息面板按钮 `playerInfoTripleTap.kts:777-800` / 命令 `/buildban` `playerBuildBan.kts:185-204` | `3+~4`（`canManageBuildBan` = `canDirectRestrict`，`playerBuildBan.kts:60-62`；命令体再查 `hasTrustLevel("3+")`） | 永久=留空；临时=任意正整数分钟，无上限 | 永久→`@Savable` map（`playerBuildBan.kts:17-19`，会话态）；临时→内存（`:22`） |
| 4 | 账号封禁 | `/banX` `ban.kts:255-273`；面板 ban `playerInfoTripleTap.kts:801-808` | `@admin` + `@pluginAdmin` 白名单（`trustLevel.kts:273-274`）→ 3++/4；面板 `canAdminBan`=3++ 且 `canModerate` | 任意分钟；**3++ 面板无上限**（`staffBanMaxMinutes` 返回 `null`，`playerInfoTripleTap.kts:550-551`）；`pluginAdminMaxBanMinutes`（默认 7 天，`trustLevel.kts:156-157`）**定义了但没人用** | DB `PlayerBanV2`（`banStore.kts:22-29`，**是**） |
| 5 | IP 封禁 | `/banip` `securityGuard.kts:1317` / 面板 `playerInfoTripleTap.kts:806-808` | 3++/4（`wayzer.admin.banIp`，`trustLevel.kts:275`） | 任意分钟 | `MdtSettings`（`security-guard.md:22`，**是**） |
| 6 | 投票踢出 | `/vote kick` `voteKick.kts:70-82` | 所有已登录玩家（权限 `wayzer.vote.*` 默认放开，`vote.kts:76`；游客被 `guestForbidden` 拦，`voteKick.kts:58`） | 通过后 ban **60 分钟**（`voteKick.kts:66`） | DB（同 4，**是**） |
| 7 | 投票强制观战 | `/vote ob` `voteOb.kts:157-169` | 所有已登录玩家（游客拦） | **无期限** | 同 2（会话态） |
| 8 | 投票禁言 | `/vote mute` `voteMute.kts:92-109` | 所有已登录玩家（游客拦，`:98`） | **1 分钟 ~ 3 天（4320 分钟）**（`MAX_MUTE_MINUTES`，`voteMute.kts:14/22-33`） | 走 `mutePlayerTemporary(operator=null)`（`:78`）→ **内存，否** |
| 9 | 禁止发起投票（管理限制） | `/votestartban` `vote.kts:112-130`；面板按钮 `playerInfoTripleTap.kts:810-831` | 3++/4 | 无期限 | 脚本 config 键（`vote.lib.kt:399-421`，**是**） |
| 10 | 一票否决 | `/veto` `trustVoteVeto.kts:14-32` | 3++/4 | 单次投票 | — |
| 11 | 自动清理踢出 | `forceObAutoCleaner.kts:133-173` | 系统（按在线人数阈值自动/手动 `/forceobclean`） | 即时 | 不适用；**不是惩罚，不应触发检举** |
| 12 | 安全风控/今日游客观战 | `securityGuard.kts`、`accountGuestControl.kts` | 系统/管理 | 有到期 | 各自持久化；**与本次检举范围无关** |

补充：

- 面板入口本身不校验权限，只做 500ms 打开限频（`playerInfoTripleTap.kts:168-175`）；所有按钮按实时等级推导布尔值（`:595-624`）。
- 命令入口与面板入口的权限**并不对称**：`/playermute` 只有 4/admin 能用（`playerMute.kts:211` 仅注册 `@admin`），但 3+/3++ 能用面板禁言；`/forceOB` 3++ 能用（`@pluginAdmin` 白名单），3+ 不能（面板可以）。设计时不能只看命令权限。
- 强制观战有"直接模式"120 秒操作者冷却（实现 `voteOb.kts:28-31/95-109`，面板调用点 `playerInfoTripleTap.kts:715-749`），这是唯一现存的"施罚者冷却"，不跨重启。
- 强制观战会禁止目标参与普通投票（`VoteEvent.registerDenyVotePredicate`，`voteOb.kts:155`）；目标自救入口 `/vote quitOb` 豁免"禁止发起投票"（`:154/170-189`，`vote.lib.kt:47/389-393`）。

### 1.3 关键持久化机制（设计要选对）

| 机制 | 位置 | 跨重启 | 说明 |
|---|---|---|---|
| Exposed 业务表（H2 JDBC） | `coreLibrary/db` `DBApi`；表注册见 `MdtStorage.kt:623-657`、`banStore.kts:22-29`、`mdtDatabase.kts:8` | ✅ | 当初建议用它；**最终惩罚体系改选 `MdtSettings` 两键**（原因见 §3.5：热重载下新表注册/建表不可靠） |
| `MdtStorage.Settings` 键值表 | `MdtStorage.kt:546-551`，读写 `:977/:1075` | ✅ | 安全风控 IP 封禁就用它（`security-guard.md:22`）；适合轻量 JSON，不适合高频大对象 |
| 脚本 config 键 `script.config.key` | 落 `config/scripts/data/config.conf`；示例 `vote.lib.kt:399-421` | ✅ | 适合"名单型"状态（禁止发起投票就这么做） |
| `@Savable` + `customLoad` | `playerBuildBan.kts:17-19`、`voteOb.kts:16-31` | ❌（按文档结论，未实测） | 既有结论：**只是会话内热重载机制，重启即丢**（`scripts-maintenance.md:1036`）；不要用它承载 24h 冷却。若实现时想顺手验证：加限制→重启→看是否还在 |
| 纯内存 map | `playerMute.kts:17-18`、`playerBuildBan.kts:22`、`voteOb.kts:29-31` | ❌ | 临时限制/冷却现状 |

### 1.4 现状缺口（对照新需求）

- **G1 没有检举/申述通道**：被罚者只能找管理人工处理；唯一自助是观战 `/vote quitOb`（`voteOb.kts:170-189`），禁言/禁建没有自救入口。
- **G2 非管理员时长不受 24h 约束**：面板禁言/禁建可永久或任意长；3+ 直接观战无期限；投票禁言可 3 天。
- **G3 没有 `(施罚者,目标)` 重复施加冷却**：只有"直接观战 120s"和"投票发起失败冷却"（与目标无关）。
- **G4 没有施罚者信誉/权限惩罚**：3+/普通玩家滥用只能人工 `/setlevel` 降级（需 4 级）。
- **G5 关键状态未落库**：临时禁言/禁建/观战状态/直接观战冷却都是会话态，新需求明确要求冷却跨重启。
- **G6 投票类惩罚没有统一的"施罚者"口径**：`voteMute` 应用时 `operator=null`、发起者名字只拼进理由字符串（`voteMute.kts:78`）；`voteOb` 把发起者 uid 记为 operator（`voteOb.kts:141`）；`voteKick` 把发起者记为 ban 的 operator（`voteKick.kts:66`）。检举需要统一的"施罚者 uid + 记录 id"。

## 2. 用户新需求拆解

### 2.1 原文要点（Lead 在 task-4 中转述，本设计与实现以此为准）

> 【任务1 检举系统】当一个**非游客**玩家被**管理员层级以下**（管理员层级 = 3++ 与 4 级）的玩家施加观战/禁言等限制性惩罚时，被惩罚者收到弹窗提示（"你被 xx 观战了，你可以投票检举 ta，是否发起投票？"）。若检举投票通过：①解除对该玩家的惩罚；②禁止发起惩罚的玩家 1 天内使用相关权限；③同一玩家被检举（成功）超过 3 次 → 永久禁止其观战/禁言等限制性权利。
>
> 【任务2 时限与冷却】对管理员层级以下的玩家：他们施加的**带时间性的惩罚**最多 1 天（24h/1440 分钟），且 **1 天内不能对同一玩家重复施加**（冷却要跨重启持久化）。

> 备注：更早的用户原话全文未直接下发到本文档；如与上文有出入，以 Lead/用户确认版本为准。**Lead 已按 §2.3 拍板执行口径（2026-10-17），§2.3 与 §7 的"Lead 已定"条目优先于上文原文的歧义表述。**

### 2.2 拆解为可验收条目

| ID | 需求条目 | 验收标准 | 落点（可写文件内） |
|---|---|---|---|
| R1 | 只有**非游客**被罚者受保护 | 目标 `PlayerData[target].authed == false` → 不弹窗、不可检举；**不要求目标等级**（3++ 被普通玩家投票禁言同样可检举） | `punishmentReport.kts` |
| R2 | 只有被**非管理员层级**（`<3++`）施罚才触发 | 施罚者实时等级 ≥3++（或在线 admin）→ 不弹窗、不计成功检举次数 | `punishmentReport.kts` + 入口事件里带 operatorUid |
| R3 | 被罚者收到弹窗 | 惩罚生效后（建议延迟 3~5s）在线的非游客被罚者收到交互弹窗："你被 xx 观战/禁言了，是否发起检举投票？" | `punishmentReport.kts`（`coreMindustry.MenuBuilder`，`menu.lib.kt:34/116`） |
| R4 | 检举以投票形式进行 | 被罚者点击即发起 `VoteEvent`；建议 70% 同意通过；同一时间只能有一个投票 → 提示稍后 | `punishmentReport.kts`（`VoteEvent`） |
| R5 | 投票通过 → 解罚 | 仅解除**被检举的那一条惩罚记录**（kind 定向）；目标离线也能解（各脚本按 uid 清理） | `punishmentReport.kts` 发 `PunishmentLiftedEvent`；`playerMute`/`voteOb`/`playerBuildBan` 各自监听执行 |
| R6 | 投票通过 → 施罚者权限封禁 1 天 | 被检举者 1 天内不能施加限制性惩罚：禁言/观战/禁建必拦（Q7 已纳入禁建）；**惩罚类投票发起（ob/mute）同样拦截**（Q5）；kick/清场/账户封禁本轮未接（API 已留 TODO）；跨重启有效；到期自动恢复 | 规则/持久化在 `playerMute.kts`；入口 `playerMute`/`playerBuildBan`/`voteMute`/`voteOb` |
| R7 | 成功检举累计超阈值 → 永久禁止限制性权利 | 每次成功检举给施罚者 +1；**默认阈值 4（即"超过 3 次"）永久封禁**，配置项 `permanentReportStrikeThreshold`；永久范围见 §2.3/Q5；重启仍生效；4 级可 `/punishreport reset` 手动解除（Q6 默认） | 同上 |
| R8 | 非管理员"带时间性惩罚"≤24h | 非管理员施罚：请求时长**一律钳制到 1440 分钟**（超限自动钳制+提示）；禁言/禁建"永久"**直接拒绝**；强制观战无时长 → **补 1440 到期自动解除**（§2.3/Q3） | `playerMute`/`voteMute`/`voteOb`/`playerBuildBan` |
| R9 | 同一 `(施罚者,目标)` 24h 内不得重复施加 | 按 `(施罚者 uid, 目标 uid)` 配对、**跨惩罚类型共用**一个 24h 窗口；窗口内再次施加 → 拒绝并提示剩余时间；跨重启有效（§2.3/Q2） | 规则/持久化在 `playerMute.kts`；各入口调用 `preparePunishment` |
| R10 | 不破坏既有口径 | 游客不能发起限制他人投票（`vote.lib.kt:40/377-379`）；3++/4 惩罚不触发检举；`quitOb` 自救豁免；一票否决/自动清理/同IP清理等行为不变 | 全部改动保持向后兼容 |

### 2.3 最终执行口径（Lead 已拍板 + 用户批准/否决，2026-10-17）

> 本节是需求歧义项的最终执行口径；与 §2.1 原文表述冲突时以本节为准。状态：Q1-Q5、Q7、Q9 **✅ 已定**；N4 **✅ 用户已批准（已实现）**；Q6/Q8/Q10 **📌 按默认建议执行**。最终实现见 §3。

| ID | 最终口径 | 落地（最终实现） |
|---|---|---|
| Q1 | 计数=成功通过的检举票数；**`>3`（第 4 次起）永久封禁**限制性权利；阈值做成配置项 | `permanentReportStrikeThreshold` 默认 **4**，`strikeCount >= 阈值` → 永久（`playerMute.kts:102`, `:377-400`） |
| Q2 | 24h 冷却按 **`(施罚者 uid, 目标 uid)` 配对、跨惩罚类型共用** | `punishment.report.cooldowns` 键 `operatorUid|targetUid`；`preparePunishment` 先查（§3.5） |
| Q3 | 非管理员带时长惩罚**钳制到 1440**；禁言/禁建**永久选项直接拒绝**；**强制观战无时长 → 补 1440 到期自动解除** | `preparePunishment` 永久拒绝/钳制（`playerMute.kts:344-361`）；`voteOb.kts:100-103/267-270` 补 1440 到期 |
| Q4 | 投票类惩罚（voteMute/voteOb）纳入钳制与冷却，**发起前 + 通过后双重检查** | `voteMute.kts:81` + `mutePlayerTemporaryByVote`；`voteOb.kts:247-253` + `:285` |
| Q5 | 永久封禁范围 = **观战/禁言/禁建 + 发起 mute/ob 投票**；`/vote kick`、清场类、账户封禁**本轮未接**（API 已留 `restrictionBanLeftMillis`/`restrictionBanBlockMessage` + TODO） | 各入口 `preparePunishment`；`voteKick.kts` 未改 |
| Q6 | 📌 按默认：4 级可 `/punishreport reset <玩家>` 手动解除（清封禁记录+成功检举计数，不清冷却）；申诉入口本轮不做 | `punishmentReport.kts:351-355` |
| Q7 | **纳入禁建** `playerBuildBan.kts`（非管理员临时禁建钳制/永久拒绝/24h 冷却/检举封禁拦截/检举通过可离线解除）；`voteKick.kts` 不纳入 | `playerBuildBan.kts:69/109/178/196` |
| Q8 | 📌 按默认：解罚只解"被检举的那一条"（按 `kind` 定向），同目标其他限制不动 | `PunishmentLiftedEvent(kind)` + 各脚本只处理自己的 kind |
| Q9 | 弹窗与公开投票文案**显示施罚者名字**，不做脱敏 | `punishmentReport.kts:147`（弹窗）/ `:188-190`（投票描述） |
| Q10 | 📌 按默认：同一设备 UUID 视为同一人（结合 `PlayerData.ids` 聚合）；换号恶意绕过归账号/IP 风控，本轮不追 | `affectedKeys = ids+id+uuid`（`playerMute.kts:516-517`） |
| **N4（D5）** | ✅ 用户已批准：禁言拦截**豁免自救投票**，`quitob`/`quob`/`解除观战`/`退出观战`（不区分大小写）放行，其它 vote 子命令仍拦 | `SELF_RESCUE_VOTE_SUBCOMMANDS`（`playerMute.kts:745`、判断 `:747-764`）；`quitOb` 别名 `voteOb.kts:321-323` |
| — | **管理员层级 = 仅 3++ 与 4 级**（0/1/2/3/3+ 均属"非管理员"） | `adminTierOrder = trustLevelOrder("3++")`（`playerMute.kts:105`）；在线 admin 视为 4（现状不变） |

## 3. 最终实现接口（以提交 `f845d19` 为准，2026-10-17）

> 本节是惩罚体系改造的**最终接口记录**（第二阶段回填）。实现代码以 `mdtserver/config/scripts/` 为准；原"草案/实现偏离"内容已按最终实现替换，与早期草案的差异只在 **§3.10** 保留一张对照表（避免后续维护者误读旧稿）。
> 脚本文件规模（最终）：`playerMute.kts` 822 行、`punishmentReport.kts` 366 行、`voteOb.kts` 402 行、`playerBuildBan.kts` 294 行、`voteMute.kts` 150 行。

### 3.1 架构与依赖方向（最终）

| 文件 | 状态 | 职责 |
|---|---|---|
| `wayzer/ext/playerMute.kts` | 改造 | 禁言实现；**事件（`PunishmentAppliedEvent`/`PunishmentLiftedEvent`，脚本内嵌套 class）+ 规则（钳制/冷却/检举封禁）+ 持久化（MdtSettings 两键）+ 规则 API 都在本文件** |
| `wayzer/user/punishmentReport.kts` | 新增 | 检举弹窗 / 检举投票 / 解罚通知 / `/punishreport` 管理命令；**单向依赖 playerMute**（`by lazy { contextScript<PlayerMute>() }`，`punishmentReport.kts:34`） |
| `wayzer/cmds/voteMute.kts` | 改造 | 投票禁言：发起前 `preparePunishment` + 通过后 `mutePlayerTemporaryByVote`（双重检查，施罚者=发起者） |
| `wayzer/cmds/voteOb.kts` | 改造 | 强制观战：非管理员补 24h 到期；`forceObExpireAt` + 30s 清扫/入服检查；投票路径同样双重检查；`quitOb` 新增别名 |
| `wayzer/ext/playerBuildBan.kts` | 改造（Q7） | 禁建：与禁言同口径（钳制/永久拒绝/冷却/封禁拦截/离线解禁）；监听解罚事件 |
| `wayzer/lib/PunishmentEvents.kt` | ❌ **未创建** | 原因：新增 `wayzer/lib/*.kt` 会触发 wayzer 模块重编 → `PlayerData`/`MdtStorage` 等静态单例双实例风险（本会话不能重启）；事件/规则/持久化并入 `playerMute.kts`（源码注释 `playerMute.kts:27-41` 记录该权衡） |

依赖方向（单向、无循环）：

```
voteMute.kts / voteOb.kts / playerBuildBan.kts ──→ playerMute.kts（规则/事件/持久化/解罚）
punishmentReport.kts ──────────────────────────→ playerMute.kts（同上）
```

- `punishmentReport.kts` 不反向依赖任何惩罚实现脚本；解罚通过 `PunishmentLiftedEvent` 由各脚本监听、各自清理自己的状态（目标离线也可解）。
- 系统/自动清理（`operator=null`）不触发检举；管理员层级（3++/4）的惩罚也不触发检举。

### 3.2 事件（`wayzer/ext/playerMute.kts` 内嵌套 class）

引用方式：`import wayzer.ext.PlayerMute.PunishmentAppliedEvent` / `wayzer.ext.PlayerMute.PunishmentLiftedEvent`（`punishmentReport.kts:13-15`）。

**`PunishmentAppliedEvent`**（`playerMute.kts:51-77`，限制性惩罚真正生效后由各应用入口发射）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `kind` | String | `"mute"` / `"ob"` / `"buildban"` |
| `recordId` | Long | 进程内递增 id（防重复检举/日志） |
| `source` | String | `"direct"`=面板/命令直罚；`"vote"`=投票通过（施罚者=发起者）；`"system"`=系统/脚本自动（不触发检举） |
| `operatorUid` / `operatorName` | String? | 施罚者主体 uid / 显示名；system 为空 |
| `operatorLevelOrder` | Int | 施罚者实时等级序（3++=38、4=40 为管理员层级 → 不触发检举） |
| `operatorUids` | Set<String> | 施罚者全部主体标识（uuid/账号 uid），封禁跨主体生效 |
| `targetUuid` | String | 目标会话 UUID（定位在线玩家） |
| `targetUids` | Set<String> | 目标全部主体标识（解罚按 uid 清理） |
| `targetName` | String | 目标显示名 |
| `targetAuthed` | Boolean | 目标是否已登录（游客=false，游客不受检举保护） |
| `durationMinutes` | Int? | 带时间惩罚分钟数；null=无期限（仅管理员层会产生） |
| `reason` | String | 惩罚原因 |

**`PunishmentLiftedEvent`**（`playerMute.kts:83-95`）：`kind` / `recordId` / `targetUuid` / `targetUids` / `targetName` / `reason`。**由 `punishmentReport` 在检举投票通过后发射**；`playerMute`（禁言，`:494-501`）、`voteOb`（观战，`:220-227`）、`playerBuildBan`（禁建，`:196`）各自 `listenTo` 只处理自己负责的 `kind`。

- 统一发射函数 `emitPunishmentApplied(...)`（`playerMute.kts:443-475`）：构造事件并 `emitAsync`，失败只 warning、不影响惩罚本身；返回 `recordId`。
- 事件不带 `Player` 对象（离线/跨重启可处理）；草案里的 `PunishmentKind`/`PunishmentSource` 枚举、`PunishmentLiftRequestedEvent` **未实现**（改用字符串 + 单向事件）。

### 3.3 规则层公开 API（`playerMute.kts`）

| 函数（行号） | 语义 |
|---|---|
| `preparePunishment(operatorUids, operatorLevelOrder, targetUids, requestedMinutes, permanentRequest): PunishDecision`（`:318-362`） | 应用前统一检查，顺序固定：**检举封禁 → 24h 冷却 → 永久拒绝/时长钳制**；返回 `blockReason`（拒绝文案）/ `allowedMinutes`（钳制后时长）/ `note`（给操作者的提示）。系统/无归因、管理员层级直接放行 |
| `markPunishmentAppliedIfNeeded(operatorUids, operatorLevelOrder, targetUids)`（`:290-297`） | 应用成功后写 `(施罚者,目标)` 24h 冷却；管理员/系统不写 |
| `punishCooldownLeftMillis(operatorUids, targetUids)`（`:253-270`） | 剩余冷却毫秒（跨禁言/观战/禁建共用） |
| `restrictionBanLeftMillis(uids)` / `restrictionBanInfo(uids)` / `restrictionBanBlockMessage(...)`（`:216-250`） | 检举封禁查询/提示；供各入口拦截，也是 Q5 中 `/vote kick`、清场类、账户封禁后续接入的预留 API（本轮未接，TODO） |
| `applyReportStrike(operatorUid, operatorName, operatorUids): ReportStrikeResult?`（`:377-400`） | 检举成功：累计次数 +1；达到阈值（默认 4，即"超过 3 次"）→ **永久封禁**，否则封 `reportBanHours`（默认 24h） |
| `clearRestrictionBan(uids)` / `activeRestrictionBans()`（`:403-425`） | 4 级 reset / 管理命令展示用 |
| `emitPunishmentApplied(...)` / `liftMuteByUids(uids, targetUuid)`（`:443-492`） | 事件发射 / 检举通过后按 uid 离线解禁 |
| `formatPunishLeft` / `isAdminTierLevel` / `applierLevelOrder[ByUid]` / `findOnlinePlayerByUids` / `nonAdminMaxPunishMinutesValue` 等（`:108-111`, `:192-250`, `:428-434`） | 公共工具/配置读取 |
| `mutePlayerTemporaryByVote(...)`（`:681-722`） | 投票禁言应用入口（施罚者=发起者，规则双重检查；不改旧函数签名以兼容 `playerInfoTripleTap` 已编译调用点） |

配置项清单见 **§3.6**。

### 3.4 各入口最终实现

**`playerMute.kts`（禁言）**

- `mutePlayer`（`:558-608`）：永久禁言 → `permanentRequest=true` 送规则层；非管理员**直接拒绝**；管理员照旧。成功后写冷却、发 `PunishmentAppliedEvent(kind="mute", source=direct|system)`。
- `mutePlayerTemporary`（`:635-672`）：临时禁言；非管理员请求超 1440 分钟 → **自动钳制到 1440** 并给操作者 `note` 提示（不是拒绝）；管理员保持原时长。
- `mutePlayerTemporaryByVote`（`:681-722`）：给 `voteMute` 用的投票应用入口，施罚者归因为发起者，同样受钳制/冷却/封禁约束。
- 解禁：`unmutePlayer`（`:724-741`）保持原逻辑；`liftMuteByUids`（`:478-492`）支持检举通过后离线解禁并提示在线目标。
- 禁言拦截 `shouldBlockMutedInput`（`:747-764`）：一级命令仍拦 `t`/`a`/`vote`/`投票`/`votekick`；**N4 豁免**：`vote`/`投票` 的子命令命中 `SELF_RESCUE_VOTE_SUBCOMMANDS = {"quitob","quob","解除观战","退出观战"}`（`:745`，大小写不敏感）时放行，其它 vote 子命令仍拦。

**`voteMute.kts`（投票禁言）**

- 保留 `MAX_MUTE_MINUTES = 72*60`（3 天上限，管理员层发起时生效）。
- `startMuteVote`（`:67`）：**投票发起前**对发起者 `preparePunishment`（`voteMute.kts:81`），被拒直接回复并终止（不白跑投票）。
- 通过后调用 `mutePlayerTemporaryByVote`（`:110`）：实际时长/冷却/封禁/归因落到规则层（**通过后再检查一次**，Q4 双重）。
- 游客闸门 `guestForbidden=true`（`:100`）等既有口径不变。

**`voteOb.kts`（强制观战）**

- `forceObPlayer`（`:72-137`）/ `startObVote`（`:234-300`）：非管理员（operator / directOwner / 投票发起者 `<3++`）→ `untilMillis = now + cappedMinutes*60_000`（`cappedMinutes = nonAdminMaxPunishMinutesValue()`，默认 1440），管理员层 `untilMillis=null`（无期限）。
- 到期自动解除：`forceObExpireAt`（`:42-43`，`@Savable`/customLoad 会话态）+ `releaseExpiredForceOb()`（`:203-218`）每 30s 清扫（`:344-346`）+ 入服检查（`:354-357`）；到期只广播解除，不产生检举。
- 应用成功写冷却 + 发事件（直罚 `:119-136`、投票 `:285-298`）；`listenTo<PunishmentLiftedEvent>`（`:220-227`）解罚（`releaseForceObByUids`，离线可解）。
- `quitOb`（`:321-323`）别名：`quob` / `解除观战` / `退出观战`（与 N4 白名单对齐）。

**`playerBuildBan.kts`（禁建，Q7 最终纳入）**

- `disableBuild`（`:69`）：永久禁建对非管理员**直接拒绝**；成功后写冷却 + 发 `PunishmentAppliedEvent(kind="buildban")`。
- `disableBuildTemporary`（`:109`）：非管理员超 1440 分钟 → 自动钳制 + 提示。
- `enableBuildByUids`（`:178`）：检举通过后按 uid **离线解禁**；`listenTo<PunishmentLiftedEvent>`（`:196`）。
- 放置/拆除拦截与解禁原逻辑不变。

**`punishmentReport.kts`（检举流程，最终）**

- 监听 `PunishmentAppliedEvent`（`:92-139`）：过滤 system / 管理员层级 / 游客；记录 `lastPunishments`；**在线人数 < `minOnlinePlayers`（默认 3）→ 只发文字提示不弹窗**；同一目标检举进行中、或弹窗间隔 < `popupMinIntervalMillis`（默认 60s）→ 跳过；延迟 `popupDelayMillis`（默认 3s）弹窗。
- 弹窗（`:141-158`）：`MenuBuilder`，显示施罚者名字/惩罚类型/原因/通过率/封禁与阈值说明，按钮「发起检举投票」「暂不检举」，90s 超时。
- 检举投票（`:162-216`）：`VoteEvent`，`requireNum` 按 `passRatePercent`（默认 70%）；`canVote` 排除被检举者；`bypassDenyVote` 允许被罚者本人（可能正被强制观战）；`guestForbidden=true`；`ignoreStartBan=true`（自救）；已有投票进行中则提示稍后。
- 投票通过（`:218-259`）：`handledRecords` 幂等 → 发 `PunishmentLiftedEvent`（解罚）→ `applyReportStrike`（+1 / 封 1 天 / 永久）→ 全服广播 + 私聊被检举者；失败只提示，无独立失败冷却（沿用投票框架自身冷却）。
- `/punishreport` 无参/`report` = 玩家检举最近一次惩罚（`contextValidMinutes` 默认 30 分钟内有效，`:315-327`）。
- 玩家离开时清理 `lastPunishments`/`lastPopupAt`（`:362-366`）。

### 3.5 持久化（最终：MdtSettings 两键，不用 Exposed 表）

| MdtSettings 键 | 行格式 | 语义 |
|---|---|---|
| `punishment.report.bans` | 每行 `uid\|untilMillis\|strikeCount\|name`（其中 name 的 `\|`/换行已清洗为 `/`/空格） | 检举封禁记录；`untilMillis=Long.MAX_VALUE` 表示永久；`strikeCount`=累计成功检举次数（**过期封禁保留计数**） |
| `punishment.report.cooldowns` | 每行 `operatorUid\|targetUid\|untilMillis` | `(施罚者,目标)` 24h 冷却（跨禁言/观战/禁建共用）；加载时清理已过期项 |

- 读写：`ensurePunishStateLoaded()` 懒加载（`playerMute.kts:145-176`），加载失败只 warning 并稍后重试（不阻塞惩罚入口）；`persistPunishStateLocked()` 全量写回（`:178-187`），`synchronized(PUNISH_LOCK)` 串行化。
- 为何不用 Exposed 新表：新表需启动期注册/建表，热重载场景不可靠；MdtSettings 与安全风控 IP 封禁同口径（`docs/security-guard.md:22`）。
- 重启行为（最终）：**冷却 / 封禁 / 成功检举计数跨重启保留**；临时禁言、临时禁建仍为内存态（重启丢失，未改）；强制观战到期时间 `forceObExpireAt` 是 `@Savable` 会话态（重启丢失 → 回到旧行为，不自动到期）。
- `reset`（仅 4 级）：清除封禁记录 + 成功检举计数，**不清冷却**。

### 3.6 配置项（最终，共 10 项，全部有默认值）

| 配置键 | 默认 | 说明 |
|---|---|---|
| `wayzer.ext.playerMute.nonAdminMaxPunishMinutes` | 1440 | 非管理员层级单次带时间限制性惩罚最大时长（分钟，=1 天） |
| `wayzer.ext.playerMute.punishRepeatCooldownHours` | 24 | 非管理员对同一玩家重复施加限制的冷却（小时，跨类型共用） |
| `wayzer.ext.playerMute.reportBanHours` | 24 | 检举成功后封禁施罚者限制权限的时长（小时） |
| `wayzer.ext.playerMute.permanentReportStrikeThreshold` | 4 | 累计成功检举达到该次数 → 永久封限制权（默认 4 = "超过 3 次"） |
| `wayzer.user.punishmentReport.enabled` | true | 是否启用检举系统（弹窗/投票/封禁） |
| `wayzer.user.punishmentReport.passRatePercent` | 70 | 检举投票通过所需赞成率（%） |
| `wayzer.user.punishmentReport.popupDelayMillis` | 3000 | 惩罚生效后延迟弹窗（ms） |
| `wayzer.user.punishmentReport.popupMinIntervalMillis` | 60000 | 同一玩家两次检举弹窗最小间隔（ms） |
| `wayzer.user.punishmentReport.minOnlinePlayers` | 3 | 在线人数低于该值不弹窗/不组投票 |
| `wayzer.user.punishmentReport.contextValidMinutes` | 30 | `/punishreport` 重开检举的有效期（分钟） |

（SA 配置落盘在 `config/scripts/data/config.conf`，通常无需手改。）

### 3.7 检举投票流程（最终）

```
惩罚生效
  ├─ source=system / 施罚者≥3++ / 目标为游客 → 静默跳过（不弹窗、不计次数）
  └─ 在线人数≥3 且 同目标无进行中检举 且 弹窗间隔≥60s
        → 延迟 3s 弹窗（MenuBuilder，显示施罚者名字）
        → 玩家点「发起检举投票」
              → VoteEvent(通过率 70%；排除被检举者；guestForbidden；ignoreStartBan；bypassDenyVote=被罚者本人)
              → 通过：handledRecords 幂等 → 发 PunishmentLiftedEvent（解罚，离线可解）
                      → applyReportStrike：计数+1；>=4 → 永久封限制权；否则封 1 天
                      → 全服广播 + 私聊被检举者
              → 未通过/超时：惩罚继续，提示玩家；无独立"检举失败冷却"（沿用投票框架冷却）
```

### 3.8 弹窗交互（最终）

- `MenuBuilder("[gold]惩罚检举")`（`punishmentReport.kts:141-158`）：标题、施罚者名字（Q9 显示真名）、惩罚类型中文、原因、通过率、通过后效果与"累计被成功检举 N 次永久"说明；按钮「发起检举投票」「暂不检举」；90s 超时关闭；关闭后可用 `/punishreport` 在 30 分钟内重新打开。
- 游客、管理员层级施罚、系统惩罚均不弹窗；在线人数 <3 时只给一条文字提示。
- 同一玩家弹窗有 60s 最小间隔（防刷屏）。

### 3.9 命令与权限（最终）

**`/punishreport`**（`punishmentReport.kts:329-360`）

- 别名：`检举` / `jubao` / `reportpunish`；**无 permission attr**，由子命令内校验等级。

| 子命令 | 执行者 | 行为 |
|---|---|---|
| 无参 / `report` / `检举` / `发起` | 玩家（控制台会提示需游戏内执行） | 检举**最近一次**惩罚记录（`contextValidMinutes` 内有效；无记录/过期给提示） |
| `bans` / `list` / `列表` / `封禁列表` | 3++ 及以上（`isPluginAdminOrHigher`） | 列出当前生效的检举封禁（永久/剩余时间、累计次数） |
| `status` / `状态` / `info` `<玩家>` | 3++ 及以上 | 查询玩家累计成功检举次数/封禁状态 |
| `reset` / `清除` / `unban` `<玩家>` | **仅 4 级/admin**（`isTrustAdmin`） | 清除检举封禁记录与成功检举次数（不清冷却） |

**`/vote quitOb`**：主名 `quitOb`，别名 `quob` / `解除观战` / `退出观战`（`voteOb.kts:321-323`）；被禁言者可用（N4 自救）。

- 未新增 `wayzer.admin.punishment` 权限（草案废弃）；管理命令靠子命令内等级校验。

### 3.10 与早期草案的差异（最终对照，历史参考）

| 草案内容 | 最终实现 | 原因/备注 |
|---|---|---|
| 新增 `wayzer/lib/PunishmentEvents.kt` | **未创建**；事件/规则/持久化并入 `playerMute.kts` 嵌套 class | 新增 lib 触发 wayzer 模块重编 → `PlayerData`/`MdtStorage` 静态单例双实例风险（本会话不能重启），见 `playerMute.kts:27-41` |
| `PunishmentKind` / `PunishmentSource` 枚举 | 用 `kind`/`source` 字符串（mute/ob/buildban；direct/vote/system） | 简化并避免额外文件 |
| `PunishmentLiftRequestedEvent` | 未实现；改为 `PunishmentLiftedEvent`（由 punishmentReport 发射，各脚本监听） | 单向依赖 + 事件通知，避免循环 |
| Exposed 三表（Records/Cooldowns/Bans） | MdtSettings 两键 `punishment.report.bans` / `punishment.report.cooldowns` | 新表启动期注册/建表在热重载下不可靠 |
| `/punishlog` `/punishbanlist` `/punishunban` `/punishreset` `/appeal` | 统一为 `/punishreport` + 子命令 | 减少入口、权限靠子命令内校验 |
| 新权限 `wayzer.admin.punishment` | 未新增 | 同上 |
| 完整审计记录（Exposed 历史） | 未实现（运行态 `lastPunishments` + 持久化封禁/计数） | 同上，避免新表 |
| 检举失败独立冷却 | 未实现 | 范围收敛，沿用投票框架冷却 |
| voteKick / 清场类 / 账户封禁纳入封权拦截 | 未实现；API 已留 `restrictionBanLeftMillis` / `restrictionBanBlockMessage` + TODO | 各自脚本不在本轮写作用域（Q5/Q7） |
| 非管理员带时长惩罚"钳制或拒绝" | 最终：**钳制 1440**；永久（禁言/禁建留空）**直接拒绝**；强制观战无时长 → **补 1440 到期自动解除** | Q3 最终口径 |
| 禁言拦截豁免（N4） | `SELF_RESCUE_VOTE_SUBCOMMANDS`（`:745`），`vote quitOb` 新增 `quob`/`退出观战` 别名 | 用户 2026-10-17 批准 |

## 4. 边界条件清单

| ID | 边界 | 期望行为 |
|---|---|---|
| B1 | 被罚者是游客（未登录） | 不弹窗、不可检举（R1）；惩罚照常生效 |
| B2 | 施罚者是 3++/4/在线 admin | 不弹窗、不计成功检举（R2）；但 4 级仍可人工处理 |
| B3 | 施罚者/被罚者等级实时变化（中途登录、升/降级） | 每次操作前用实时等级重新判定（不要缓存判定结果）；面板已有"操作者权限或目标等级已变化"模式可复用（`playerInfoTripleTap.kts:712/732/758`） |
| B4 | 被罚者中途掉线 | 弹窗只对在线目标发；投票期间掉线由框架移票（`vote.kts:25-29`）；**解罚由 `PunishmentLiftedEvent` 通知各脚本按 uid 清理，支持目标离线**（`playerMute.liftMuteByUids` / `voteOb.releaseForceObByUids` / `playerBuildBan.enableBuildByUids`） |
| B5 | 主体切换（uuid ↔ `account:<id>`） | 事件/持久化都按 `targetUids`/`affectedKeys = ids+id+uuid` 处理，保证冷却/strikes 不因换登录态而绕过或丢失 |
| B6 | 自检举/自罚 | 自己不能惩罚自己（`canDirectRestrictTrustTarget:142` 已拦）；检举入口校验 `reporter.uuid() == ev.targetUuid`，且事件侧 `isSameAccount(target, operatorUid)` 防御 |
| B7 | 同一记录重复检举 / 已有投票 | 运行态 `handledRecords`（recordId，TTL 6h）幂等 + `activeReportTargets` 防重复开票 + `VoteEvent.current()` 单例检查；提示"已经处理过/已在处理中/当前已有投票" |
| B8 | 24h 边界/时钟回拨/跨重启 | 冷却按 `System.currentTimeMillis()` 的 `untilMillis` 判定；加载时清理过期项；重启后从 `MdtSettings` 恢复 |
| B9 | 非管理员请求永久禁言/禁建（留空） | **直接拒绝**并提示"最长 1440 分钟"（Q3）；**强制观战无时长参数 → 非管理员补 1440 分钟到期自动解除，不整类拒绝**（`voteOb.kts` 到期清扫） |
| B10 | 冷却是否跨惩罚类型 | **Q2 已定：按 `(施罚者 uid, 目标 uid)` 跨类型共用**（`punishment.report.cooldowns` 键 `operator|target`，不含 kind） |
| B11 | 被封权者后来升到 3++ | 实时等级豁免：`preparePunishment` 先判 `isAdminTierLevel` 直接放行；历史封禁记录保留，`/punishreport status` 仍可查 |
| B12 | 系统/自动清理/风控产生的限制 | `operatorUid` 为空或 `source=system` → 不弹窗、不计数 |
| B13 | 事件重复/幂等 | 同 `recordId` 只处理一次（`handledRecords`）；监听端全部 `runCatching`，异常只 warning 不冒泡（SA 会静默吞顶层异常，见 K9） |
| B14 | 数据库慢事务/游戏线程 | 规则状态懒加载到内存缓存、`synchronized` 串行、全量写回 `MdtSettings`；MdtStorage 慢事务阈值 200ms/1000ms 会打警告（`MdtStorage.kt:52-53`），写操作不在高频路径 |
| B15 | 记录增长与隐私 | 运行态 `lastPunishments`/`handledRecords` 有 TTL（6h/离开清理）；持久化只有封禁+冷却两键（行数受玩家数约束）；**Q9 已定：弹窗与公开投票文案显示施罚者名字** |
| B16 | 被罚者等级高于施罚者（如 3++ 被 1 级玩家投票禁言） | 只要目标非游客就弹窗/可检举（R1 只看目标游客状态、施罚者是否 `<3++`，不看两者相对等级） |

## 5. 测试用例清单

> 本节已按**最终实现（`f845d19`）**校正：`[自动]` = 可用控制台/脚本断言验证；`[人工]` = 需真实玩家交互或自然重启（完整未验证清单见 §8.2）。测试环境：本机 mdtserver 测试服（`scripts-maintenance.md:15` 口径），**不重启生产**。

### A. 弹窗触发（谁能/不能触发）

| ID | 前置/操作 | 期望 |
|---|---|---|
| T01 [人工] | 1 级 A 发起 `/vote mute` 对 1 级 B，投票通过 | B（在线、非游客）收到检举弹窗 |
| T02 [人工] | 3+ A 通过玩家信息面板禁言 2 级 B | B 收到弹窗 |
| T03 [人工] | 3+ A 面板强制观战 2 级 B | B 收到弹窗；观战**非管理员 24h 到期自动解除**（Q3 细化，见 T53） |
| T04 [人工] | 3++ A 面板禁言 2 级 B / 4 级 admin 强制观战 | **不弹窗** |
| T05 [人工] | 3+ A 对**游客** B 禁言 | **不弹窗**（游客不受保护） |
| T06 [自动] | 系统自动清理踢出（`forceObAutoCleaner`）/风控 | **不弹窗** |
| T07 [人工] | 目标在惩罚生效前离线 | 不弹窗；上线后是否有补弹按实现口径（记录为"未触发"） |

### B. 检举投票

| ID | 操作 | 期望 |
|---|---|---|
| T08 [人工] | B 点"发起检举投票" → 全服 70% 同意 | 解罚（禁言/观战立即解除）+ A 权限封禁 1 天 + A 成功检举次数 +1 |
| T09 [人工] | 同上但赞成率不足 | 惩罚保留；**无独立"检举失败冷却"**（实现未做，仅提示"检举投票未通过"；投票框架自身失败冷却照旧）；不计数 |
| T10 [人工] | A（被检举者）在线 → 投票菜单/计票 | A 不能投（`canVote` 排除被检举者）；B 可投（`bypassDenyVote` 放行，即使 B 正被强制观战） |
| T11 [人工] | 已有其他 `/vote` 进行中时点检举 | 提示"当前已有其他投票进行中，请稍后再发起检举"，不重复创建 |
| T12 [人工] | 同一记录第二次点检举 | 被拦（"该惩罚已被处理过"或"检举投票已在处理中"），无副作用 |
| T13 [自动] | B 是游客（未登录） | 弹窗不出现（事件层 `targetAuthed=false` 直接跳过）；即便伪造命令也不能发起（只能被罚者本人 + guestForbidden） |
| T14 [人工] | 被罚者被"禁止发起投票"时检举 | 允许（`ignoreStartBan=true` 自救豁免） |
| T15 [人工] | 投票通过后再次检举同一记录 | 拒绝（`handledRecords` 幂等） |

### C. 24h 时长钳制

| ID | 操作 | 期望 |
|---|---|---|
| T16 [人工] | 3+ 面板禁言输入 2000 分钟 | 实际 1440 分钟；操作者回执说明"已钳制为 24h" |
| T17 [人工] | 3+ 面板禁言留空（永久） | **直接拒绝**并提示"非管理员最长 24 小时"（Q3 已定） |
| T18 [人工] | 1 级发起 `/vote mute` 填 `3d` | 发起时即钳制到 1440 分钟并提示（Q3/Q4 已定） |
| T19 [人工] | 3++ 发起 `/vote mute` 填 `3d` | 保持 3 天（管理员层豁免，Q4） |
| T20 [人工] | 3+ 面板强制观战 B | 记录到期时间 = +24h；**到点自动解除**（Q3） |
| T21 [人工] | 4 级 admin 强制观战 | 无期限（保持现状） |
| T22 [自动] | 非管理员 `/forceOB` 走命令（3++ 有命令、3+ 无） | 3++/4 属管理员层不受钳制；3+ 无法用命令（权限）→ 只有面板路径（面板观战补 24h 到期） |

### D. 24h 重复施加冷却

| ID | 操作 | 期望 |
|---|---|---|
| T23 [人工] | 3+ A 临时禁言 B 10 分钟 → 解除 → 24h 内再次禁言 B | 第二次被拒，提示剩余冷却 |
| T24 [人工] | A 禁言 B 后 24h 内 A 想强制观战 B | **拒绝**（Q2 已定：跨类型共用冷却） |
| T25 [人工] | C 在同一 24h 内禁言 B | 允许（冷却只约束同一施罚者） |
| T26 [人工] | 首次施加后等待 24h+1s | 允许再次施罚 |
| T27 [自动] | 施加后**重启服务器** → 再看冷却 | 冷却仍生效（`punishment.report.cooldowns` 恢复）；**该跨重启写入路径本轮未做自然重启回归，需补测** |
| T28 [人工] | 投票类：1 级 A 发起 `/vote mute` 成功 → 24h 内 A 再发起对 B 的 `/vote ob` | **发起即拒绝**并提示剩余冷却（Q4 已定：投票类同样纳入） |
| T29 [自动] | 时钟回拨（把系统时间调回 1 小时）后再判定 | 不会出现冷却永不结束/永久生效异常（可容忍"提前恢复"） |

### E. 权限封禁与永久封禁

| ID | 操作 | 期望 |
|---|---|---|
| T30 [人工] | A 被成功检举 → 1 天内 A 面板禁言/强制观战/禁建、发起 `/vote mute|ob` | 全部被拒并提示剩余时间（`restrictionBanBlockMessage`） |
| T31 [自动] | 封禁状态跨重启 | 仍生效（`punishment.report.bans` 恢复）；**本轮未做自然重启回归，需补测** |
| T32 [人工] | 1 天后再试 | 自动恢复（封禁到期） |
| T33 [人工] | A 被成功检举**第 4 次**（Q1：阈值默认 4，`strikeCount >= 4`） | 永久封限制权（范围见 Q5）：重启后仍生效；4 级可 `/punishreport reset <玩家>` 手动解除 |
| T34 [自动] | 3++/4 的惩罚（管理员层） | 不弹窗、不产生检举封禁/计数（但会正常发 `PunishmentAppliedEvent`，被 report 过滤） |
| T35 [自动] | 管理重置（`/punishreport reset <玩家>`，仅 4 级） | 清除封禁记录 + 成功检举计数；**不清 24h 冷却**；3++ 执行被拒 |

### F. 游客/身份/异常边界

| ID | 操作 | 期望 |
|---|---|---|
| T36 [自动] | 未登录会话即使库里有 3 级 | 按 0 游客处理（`trustLevel.kts:90-98`），不能施罚、检举行为按游客口径 |
| T37 [人工] | 被罚者中途从游客登录为账号 | 惩罚生效时若为游客 → 不弹窗；**实现无"登录后补弹"逻辑**（`lastPunishments` 只在弹窗处理时记录） |
| T38 [人工] | 3+ A 禁言 B 后 A 掉线 | 冷却/记录照常（按 uid 持久化），A 重连后依然受冷却/封禁 |
| T39 [自动] | `punishmentReport` 未加载/加载失败 | 不影响惩罚入口（规则在 `playerMute`，各入口只依赖它）；只影响弹窗/检举投票/`/punishreport`；日志应有 warning 而非崩溃 |
| T40 [自动] | 脚本编译/加载验证（已由 Lead 完成基线验证） | Lead 验证：`sa listFailed` 空、`scriptdiag count`=161/157/152/失败0、根指令 158（含 `punishreport`）、`punishreport bans/status` 控制台可用；本阶段新增菜单改动后再跑 `sa load coreMindustry/menu` + `scriptdiag count` + `sa listFailed` + 嵌套注释扫描 |

### G. 兼容回归（不能被新逻辑破坏）

| ID | 操作 | 期望 |
|---|---|---|
| T41 [人工] | 游客发起 `/vote kick/ob/mute` | 仍被拒（`guestForbidden`），文案不变 |
| T42 [人工] | `/vote quitOb` 自救 | 仍可用（不受"禁止发起投票"影响） |
| T43 [人工] | 3++ `/veto` 一票否决检举投票 | 可否决（3++/4 权限不变） |
| T44 [人工] | `/forceobclean run` 高人数清理 | 行为不变，3++/4 保护不变 |
| T45 [自动] | 临时禁言/临时禁建/观战状态在重启后的行为 | 按现有口径（临时状态丢失）；但冷却/记录仍在 → 不能出现"冷却拦人但惩罚已丢"引发的玩家投诉，需在提示文案里说明 |
| T46 [自动] | `menu.kts` 修改后（本阶段） | `/help` 管理区能打开；3++/4 可见"检举封禁管理"条目；玩家可见"检举惩罚"条目；无"该功能暂未开放"错位（菜单条目按命令存在性过滤） |
| T60 [人工] | 被禁言玩家依次执行 `/vote quitOb`、`/vote 解除观战`、`/vote quob`、`/vote 退出观战`（含大小写混用）；再执行 `/vote ob`、`/vote kick`、`/vote mute`、`/votekick`，并在普通聊天发言 | 4 种自救写法**均可执行**（能发起退出观战投票）；其它惩罚类投票、`/votekick`、普通聊天**仍被拦**（N4/D5 用户已批准，见 §7.2） |

### H. 等级错位补充

| ID | 操作 | 期望 |
|---|---|---|
| T47 [人工] | 3++ 玩家 B 被 1 级玩家 A 发起的 `/vote mute` 禁言（投票不禁 @pluginAdmin） | B 收到检举弹窗（目标非游客、施罚者非管理员层）；投票通过后 A 被 1 天封权 |
| T48 [人工] | 4 级/admin 被普通玩家投票惩罚 | 保持现状（`skipKick` 保护，投票通过也不生效） |

### I. Lead 拍板口径补充用例（2026-10-17）

| ID | 操作 | 期望 |
|---|---|---|
| T49 [人工] | 3+ 玩家 A 面板强制观战 B → 24h 内 A 再面板禁言 B | **拒绝**（Q2 跨类型冷却，与 T24 互为镜像） |
| T50 [人工] | 3+ 玩家 A 面板禁建 B 输入 2000 分钟；以及 24h 内再次禁建 B | **已纳入（Q7 实现决策）**：第一次钳制 1440，第二次拒绝（24h 共用冷却）；被检举封权后禁建被拦 |
| T51 [人工] | A 被永久封限制权后发起 `/vote ob`、`/vote mute`、`/vote kick` | ob/mute 发起即被拒（Q5）；**`/vote kick` 本轮不纳入（Q7 实现决策）→ 记录 TODO，验证不因未接入而报错** |
| T52 [人工] | 任意一次触发检举的惩罚 | 弹窗文案显示施罚者名字；检举投票描述同样显示施罚者名字（Q9 已定，不脱敏） |
| T53 [人工] | 非管理员（3+）面板强制观战 B，B 在线挂到 24h 后 | 到点自动解除并提示；管理员层（3++/4）观战仍无期限（Q3） |
| T54 [人工] | `/punishreport` 子命令：无参/`report`、`bans`、`status <玩家>`、`reset <玩家>` | 无参/`report`=玩家检举最近一次惩罚（30 分钟内有效，控制台提示需游戏内）；`bans`/`status` 需 3++；`reset` **仅 4 级/admin**；别名 `检举`/`jubao`/`reportpunish` 可用（最终实现：`punishmentReport.kts:329-360`） |
| T55 [人工] | 检举投票通过时被罚者已离线（禁言/禁建/观战场景） | 仍能解除（`PunishmentLiftedEvent` → playerMute/voteOb/playerBuildBan 按 uid 离线清理，Q7） |
| T56 [自动] | 检查实现文件落点 | `wayzer/lib/PunishmentEvents.kt` **不存在**；事件是 `wayzer/ext/playerMute.kts` 内嵌套 class（`import wayzer.ext.PlayerMute.PunishmentAppliedEvent`）；`punishmentReport.kts` 只依赖 playerMute（无反向依赖） |
| T60 [人工] | 被禁言玩家依次执行 `/vote quitOb`、`/vote 解除观战`、`/vote quob`、`/vote 退出观战`（含大小写混用）；再执行 `/vote ob`、`/vote kick`、`/vote mute`、`/votekick`，并在普通聊天发言 | 4 种自救写法**均可执行**（`SELF_RESCUE_VOTE_SUBCOMMANDS`；`quitOb` 已补 `quob`/`退出观战` 别名）；其它惩罚类投票、`/votekick`、普通聊天**仍被拦** |

### J. 用户否决项回归（保持现状，勿改；对应 §7.1）

| ID | 操作 | 期望 |
|---|---|---|
| T57 [人工] | 被禁言玩家执行 `/r <内容>`（私聊回复） | **不被禁言拦截**（N1：`shouldBlockMutedInput` 维持只拦 `t`/`a`/`vote`/`投票`/`votekick`）；普通聊天仍被拦（现状不变） |
| T58 [人工] | 执行 `/vote text`、`/vote sc` | **无新增冷却 / 敏感词 / 频率限制**（N2：保持现状） |
| T59 [人工] | 查看玩家名展示与投票文案 | **无名称规范化、无同名冒充检测、无 UUID 后缀 / 等级后缀**（N3：保持现状） |

### K. 最终实现新增行为回归（2026-10-17 落地）

| ID | 操作 | 期望 |
|---|---|---|
| T61 [人工] | 同一玩家在 60s 内连续被惩罚 2~3 次 | 只弹一次检举弹窗（`popupMinIntervalMillis`=60000）；`lastPunishments` 保留最近一次 |
| T62 [人工] | 在线人数 <3 时被非管理员惩罚 | **不弹窗**，只收到一条文字提示（"当前在线人数不足，暂时无法发起检举投票"） |
| T63 [人工] | 惩罚后关闭弹窗，超过 30 分钟再 `/punishreport` | 提示"最近一次惩罚已超过 30 分钟，无法再通过命令发起检举" |
| T64 [人工] | 3++/4 施罚 vs 3+ 施罚 | 3++/4：不弹窗/不计成功检举；3+：弹窗且受钳制/冷却/封禁约束 |
| T65 [自动] | 配置项默认值（10 项，§3.6） | `config/scripts/data/config.conf` 或 SA 配置可见 `wayzer.ext.playerMute.*` / `wayzer.user.punishmentReport.*`；无手工配置时使用默认值 |
| T66 [人工] | 检举封禁状态查询 | `/punishreport bans` 显示封禁玩家/永久或剩余时间/累计次数；`status <玩家>` 显示单个玩家；3++ 可用、普通玩家被拒、`reset` 仅 4 级 |

## 6. 风险与限制

- **K1 写作用域限制（最终结论）**：本轮只改 `playerMute.kts`、`punishmentReport.kts`（新增）、`voteMute.kts`、`voteOb.kts`、`playerBuildBan.kts`；**`/vote kick`、清场类、账户/IP 封禁、`vote.lib.kt`、`playerInfoTripleTap.kts` 未改**。→ 检举/24h 钳制/封权完整覆盖"禁言 + 强制观战 + 禁建"；`/vote kick` 等由 playerMute 暴露的 `restrictionBanLeftMillis`/`restrictionBanBlockMessage` 预留 API + TODO，后续扩大作用域再接。面板禁用路径因调用 `mutePlayer*`/`forceObPlayer`/`disableBuild*` 已被覆盖（面板本身未改）。
- **K2 脚本依赖方向（最终）**：**`punishmentReport.kts` 单向依赖 `playerMute.kts`**（规则/事件/持久化都在 playerMute）；解罚由 `punishmentReport` 发 `PunishmentLiftedEvent`，playerMute/voteOb/playerBuildBan 各自监听清理。禁止出现 `playerMute → punishmentReport` 反向依赖。
- **K3 热重载/签名兼容**：本轮实现用"新增函数"而非改 `mutePlayerTemporary` 等旧签名（`mutePlayerTemporaryByVote`），避免 hot reload 后旧调用点 `NoSuchMethodError`（`scripts-maintenance.md:390` 教训）；后续再动公开签名仍须冷启动全量重编译验证。
- **K4 持久化机制（最终选择）**：冷却/封禁/计数**用 MdtSettings 两键**（`punishment.report.bans` / `punishment.report.cooldowns`），跨重启；`@Savable/customLoad` 只用于强制观战到期时间等会话态，**不要**把 24h 冷却/次数放进去（重启即丢，`scripts-maintenance.md:1036`）。
- **K5 数据库性能与慢事务**：规则查询在游戏线程同步做会触发慢事务警告（`MdtStorage.kt:52-53`）甚至卡顿；建议启动/玩家进服时预加载到内存缓存，写操作异步 + 失败重试。
- **K6 投票单例互斥**：全服同一时间只有一个投票（`vote.lib.kt:338`），检举可能长时间排不上队；被罚者体验与"惩罚生效后才有检举入口"的时序要设计好（弹窗延迟 + `/punishreport` 无参可重开）。
- **K7 检举滥用/联合作弊（风险提示，本轮未实现额外防线）**：已有防线=70% 通过率、同 IP 只计一票（`vote.lib.kt:181-191`）、`/veto`、单记录一次性、已有投票/进行中检举拦截。仍可能被团体刷票/骚扰；如后续要加"同一被罚者对同一施罚者的检举冷却/审计日志"，需用户另行同意（避免扩大范围）。
- **K8 时间源混用**：项目里 `Instant` 与 `Long millis` 混用（`voteOb.kts`、`ban.kts` 等）；新代码统一 `System.currentTimeMillis()`，避免时区/类型错。
- **K9 SA 静默吞异常**：脚本顶层/初始化异常会被吞（`docs/agent-debug-experience.md` §8/§9；KDoc 内嵌 `/**` 会截断脚本）。新增/改脚本后必须：跑 `sa scan`、`sa load`、`scriptdiag count`、`sa listFailed`、`tools/scan-nested-block-comment.ps1`，并优先用"command 注册探针"验证。
- **K10 未运行时验证项（如实记录）**：本轮运行态只验证了"脚本编译/加载 + 控制台命令（`punishreport bans/status`）"；弹窗/检举投票/解罚/封权/钳制提示需要真实玩家交互；`MdtSettings` 两键的跨重启**写入→读取**需自然重启回归（本轮只验证了读取路径，写入路径未跨重启验证）。完整清单见 **§8.2**。
- **K11 文档漂移**：本文已按最终实现回填（§3 为唯一接口来源）；后续若再改实现，必须同步本文件 + `scripts-maintenance.md`，否则按"文档不能偏移"要求视为回归。
- **K12 不新增 lib 的权衡（最终实现）**：为规避"新增 lib 触发 wayzer 模块重编 → 静态单例双实例"（本会话不能重启），事件/规则/持久化并入 `playerMute.kts`，代价是该文件职责与体积变大（822 行）、`punishmentReport` 独立性下降。后续服务器可正常重启/安全重编模块后，可再评估拆回 lib。
- **K13 用户明确否决项（❌ 勿再提议）**：①禁言不扩展拦截 `r`/私聊/聊天类命令；②`/vote text|sc` 不做冷却/敏感词过滤；③不做名称规范化/同名冒充检测/投票文案 UUID 后缀或等级显示。详见 **§7.1**（2026-10-17 日志冲突分析后用户明确"三条都不要加"）。
- **K14 用户已批准项（N4/D5，已实现）**：禁言拦截豁免自救投票（`SELF_RESCUE_VOTE_SUBCOMMANDS`，`playerMute.kts:745/747-764`；`vote quitOb` 已补 `quob`/`退出观战` 别名，`voteOb.kts:321-323`），避免"禁言+强制观战"死锁。注意**只放行该自救白名单**，后续不要顺手放行其它 vote 子命令；§7.1 N1 不能被误改（`/r` 等仍不拦）。

## 7. 口径决定表（Lead 已拍板 / 按默认执行，2026-10-17）

> 状态说明：**✅ Lead 已定** = Lead 已拍板，权威，实现与文档不得偏离；**✅ 用户已批准** = 用户明确批准要做的决策（对应 §7.2）；**📌 按默认建议执行** = 未单独拍板，按本表"决定/默认"执行。§2.3 是同一口径的需求侧表述。

| ID | 问题 | 状态 | 决定 / 默认 |
|---|---|---|---|
| Q1 | "被检举成功**超过 3 次**"的阈值语义 | ✅ Lead 已定（已实现） | 计数 = **成功通过的检举票数**；**`>3`（第 4 次起）永久封禁**限制性权利；阈值是配置项 `permanentReportStrikeThreshold`，**默认 4**（`strikeCount >= 4` 触发永久，`playerMute.kts:102/377-400`） |
| Q2 | 24h 冷却是否跨惩罚类型 | ✅ Lead 已定 | 按 **`(施罚者 uid, 目标 uid)` 配对、跨惩罚类型共用**（冷却唯一键不含 kind；kind 仅审计） |
| Q3 | 非管理员请求超 24h / 永久如何处置 | ✅ Lead 已定（已实现） | 带时长惩罚**一律钳制到 1440 分钟**（超限静默钳制 + 提示）；**永久类直接拒绝**（含禁言/禁建"留空=永久"路径）；**强制观战无时长参数 → 给非管理员补 24h 到期自动解除**（最终实现，不整类拒绝） |
| Q4 | 投票类惩罚（voteMute/voteOb）是否纳入 | ✅ Lead 已定（已实现） | **纳入**：投票**发起前** `preparePunishment` 检查，**通过后**再检查一次并应用（双重）；进入同一 24h 共用冷却窗口；3++/4 发起保持原上限（`voteMute.kts:81/110`、`voteOb.kts:247-253/285`） |
| Q5 | "永久禁止限制性权利"的范围 | ✅ Lead 已定（已实现，部分 TODO） | 已覆盖：观战/禁言/禁建 + 发起 `mute`/`ob` 投票；**`/vote kick`、清场类、账户封禁本轮未接**（API 已留 `restrictionBanLeftMillis`/`restrictionBanBlockMessage` + TODO）；不含换图/rollback/游戏结束等 |
| Q6 | 永久封禁能否被 4 级手动解除 / 是否要申诉入口 | 📌 按默认建议已实现 | 4 级可 `/punishreport reset <玩家>` 手动解除（清封禁记录+计数，不清冷却）；无独立审计表；申诉入口本轮不做 |
| Q7 | 禁建（`playerBuildBan.kts`）是否纳入写作用域 | ✅ Lead 已定 + 已实现 | **已纳入**：非管理员临时禁建钳制/永久拒绝/24h 冷却/检举封禁拦截/检举通过可离线解除（`playerBuildBan.kts:69/109/178/196`）；`voteKick.kts` 不纳入 |
| Q8 | 解罚范围 | 📌 按默认建议执行 | 只解除"被检举的那一条记录"对应的限制；同目标其他限制不动（管理员叠加限制也不动） |
| Q9 | 弹窗/公告里施罚者名字是否公开 | ✅ Lead 已定 | **显示施罚者名字**（弹窗给被罚者看、公开投票描述给全服看），不做脱敏 |
| Q10 | 冷却/次数按"当前主体 uid"还是"设备 UUID"归一 | 📌 按默认建议执行 | 同一设备 UUID 视为同一人（结合 `PlayerData.ids` 聚合）；卡密/换号恶意绕过归账号/IP 风控范畴，本轮不追 |
| N4（内部编号 D5） | 禁言拦截是否豁免**自救投票** | ✅ **用户已批准 2026-10-17（已实现）** | `SELF_RESCUE_VOTE_SUBCOMMANDS = {"quitob","quob","解除观战","退出观战"}`（`playerMute.kts:745`，判断 `:747-764`，大小写不敏感）；其它 vote 子命令仍拦；`quitOb` 已补 `quob`/`退出观战` 别名（`voteOb.kts:321-323`） |

> **实现状态**：以上决策均已在提交 `f845d19` 落地，接口与配置见 §3；未运行时验证项见 §8.2。Q6/Q8/Q10 按默认建议实现。

> 管理员层级边界（✅ Lead 已定）：**仅 3++ 与 4 级**为管理员层级；0/1/2/3/3+ 均为"非管理员"，适用检举保护、24h 钳制与 24h 冷却。

### 7.1 用户已明确否决项（❌ 不做，勿再提议）

> **背景（2026-10-17 18:22 日志冲突分析，牢猫/wweww 事件）**：该分析里提出的三条改进建议，用户看完明确回复"**三条都不要加**"。以下事项**已终局否决**：不再纳入设计、不再作为建议提出；第二阶段及后续维护者也不要在文档/报告里重新提议。若用户未来主动改变主意，必须先更新本节（记录时间与用户原话）再实现。

| # | 被否决的建议 | 用户结论 | 必须保持的现状 |
|---|---|---|---|
| N1 | 把 `r`（私聊回复）及其他私聊/聊天类命令纳入禁言拦截 | ❌ **不做** | `wayzer/ext/playerMute.kts` 的 `shouldBlockMutedInput`（基线 `:142-153`）**维持只拦** `/t`、`/a`、`/vote`、`/投票`、`/votekick`；`/r`、私聊及其他聊天类命令**不被禁言拦截**（普通聊天仍按现行为拦） |
| N2 | 给 `/vote text`、`/vote sc` 加冷却 / 敏感词过滤 | ❌ **不做** | 两者**维持现状**：不新增本次改动范围内的冷却、敏感词、频率限制逻辑 |
| N3 | 名称规范化 / 同名冒充检测 / 投票文案显示 UUID 后缀或等级 | ❌ **不做** | 玩家名显示、投票文案**保持现状**：不做名称规范化、不做同名冒充检测、不在投票文案追加 UUID 后缀或等级后缀 |

> 注：§7.2 的 N4（已批准）只对 `/vote`/`投票` 的**自救子命令**做豁免，不改变 N1 的结论——`/r` 与私聊类命令仍不纳入拦截。回归时两者一起验证（T57-T59 + T60）。
>
> 验收口径：这几项在测试里只验证"**未被改动**"（见 §5 J 组 T57-T59），任何"顺手优化"都视为偏离用户口径。**本章节只列否决项**；用户已批准的 N4 见 §7.2，不要混入本节。

### 7.2 用户已批准项（✅ 2026-10-17）

> 与 §7.1 的否决项分开记录：**本节是用户明确批准要做的**，不属于"不要加"。背景=2026-10-17 牢猫/wweww 冲突：受害者被"禁言 + 强制观战"后完全无法自救（不能发言、不能发起退出观战投票），形成死锁；用户已同意豁免。

| # | 批准内容 | 结论 | 实现要求 |
|---|---|---|---|
| N4（内部编号 D5） | 禁言拦截**豁免自救投票** | ✅ **用户已批准 2026-10-17** | `playerMute.kts` 的 `shouldBlockMutedInput` 对 `vote`/`投票` 命令**解析子命令**：`quitOb` / `quob` / `退出观战` / `解除观战`（**不区分大小写**）允许通过；其它 vote 子命令（`ob`/`kick`/`mute`/`text`/`sc` 等）仍拦截；`/t`、`/a`、`/votekick` 与普通聊天仍按现状拦 |

**实现状态（提交 `f845d19`，已落地）**：

- `playerMute.kts:745`：`SELF_RESCUE_VOTE_SUBCOMMANDS = setOf("quitob", "quob", "解除观战", "退出观战")`；`:747-764` 的 `shouldBlockMutedInput` 会解析 `vote`/`投票` 的子命令，命中白名单即放行（大小写不敏感），其它 vote 子命令、`/t`、`/a`、`/votekick` 与普通聊天仍按现状拦截；无子命令的 `/vote` 仍拦。
- `voteOb.kts:321-323`：`quitOb` 主名 + 别名 `quob` / `解除观战` / `退出观战`（与白名单对齐，避免"放行后仍是无效子命令"）。
- 回归/验收：见 §5 **T60**；同时确认 §7.1 N1（`/r` 等仍不被拦）不被误改。

## 8. 第二阶段执行记录（2026-10-17）

> **文档责任**：本 agent 只改 docs + `coreMindustry/menu.kts`，不碰实现脚本；所有接口以 punish-system 最终实现（提交 `f845d19`）为准，用户要求"文档不能偏移"。

### 8.1 已完成

| # | 事项 | 结果 |
|---|---|---|
| 1 | `docs/punishment-system-design.md` 回填最终接口 | ✅ §3 按最终实现重写（架构/事件/规则 API/各入口/持久化/配置/检举流程/弹窗/命令/差异对照）；§2.3、§5、§7、§9 同步校正；清除"草案/实现偏离"临时表述 |
| 2 | `coreMindustry/menu.kts` 菜单入口 | ✅ 玩家区新增"检举惩罚"（`/punishreport` 无参）；管理区新增"检举封禁管理"（`/punishreport bans`，3++/4 经 `canSeeAdminHelp` 可见）；`vote quitOb` 条目补别名/自救说明 |
| 3 | `docs/dev-workspace-guide.md` | ✅ 在"调试工具（2026-10-17 新增）"补 `/punishreport` 管理命令（`scriptdiag` 原已有，未重复添加） |
| 4 | 交叉检查（不改别人文件） | 见 §8.3 |
| 5 | `docs/scripts-maintenance.md` 维护批次 | ⏳ **暂缓**：docs-diag 先补「第二十批（小音效通道调查）」；等 Lead 说"可以写了"再写「第二十一批（惩罚体系）」，**写前先读顶部**，避免覆盖他人内容 |

### 8.2 未运行时验证项（如实记录，需真实玩家 / 自然重启）

- **弹窗**：触发条件（在线人数 ≥3、60s 节流、3s 延迟）、按钮点击、关闭后 `/punishreport` 在 30 分钟内重开；
- **检举投票**：70% 阈值、被检举者不能投票、被罚者自救投票（`bypassDenyVote`）、同一时间单投票互斥；
- **解罚**：禁言/观战/禁建三类在投票通过后解除（含目标离线场景）；
- **封权**：1 天封禁的提示与到期恢复；第 4 次成功检举 → 永久封禁（含重启后仍生效）；
- **钳制/冷却**：玩家信息面板与投票路径的钳制提示、跨类型 24h 冷却拒绝提示、非管理员"永久"选项拒绝提示；
- **跨重启写入路径**：`punishment.report.bans` / `punishment.report.cooldowns` 的**写入→重启→读取**（本轮只验证了读取路径，未做自然重启回归）；
- **菜单**：`/help` 新增条目在 3++/4 与普通玩家视角的可见性与点击可用性（本轮只做脚本加载验证，真实菜单需游戏内确认）。

### 8.3 交叉检查结果

- `docs/scripts-maintenance.md`：等写第二十一批时再核对（当前不写）。
- `agent-debug-experience.md` / `current-issues.md` / `trust-system.md` / `security-guard.md`：本轮核对未发现与最终实现直接矛盾的结论。`trust-system.md:155`（临时禁言/临时禁建只存内存）仍成立（实现未改临时状态持久化）；`security-guard.md:76` 同口径。
- **如后续发现矛盾：只报告 Lead，不自行修改他人文档。**

### 8.4 约束

不改惩罚实现脚本、不 git commit、不重启服务器（按任务书）。
## 9. 附录：本文引用的关键代码位置

| 位置 | 内容 |
|---|---|
| `wayzer/user/trustLevel.kts:13-24, 67-76` | 等级定义与排序 |
| `wayzer/user/trustLevel.kts:90-98` | 未登录会话强制 0 级 |
| `wayzer/user/trustLevel.kts:120-154` | `canModerateTrustTarget` / `canDirectRestrictTrustTarget` |
| `wayzer/user/trustLevel.kts:156-157` | `pluginAdminMaxBanMinutes`（默认 7 天，当前未被使用） |
| `wayzer/user/trustLevel.kts:257-284` | `@admin` 通配 / `@pluginAdmin` 白名单 |
| `wayzer/ext/playerMute.kts:51-95` | **事件**：`PunishmentAppliedEvent` / `PunishmentLiftedEvent`（嵌套 class） |
| `wayzer/ext/playerMute.kts:99-111` | 配置项 4 个 + 规则参数读取 |
| `wayzer/ext/playerMute.kts:117-187` | `MdtSettings` 两键持久化（bans/cooldowns） |
| `wayzer/ext/playerMute.kts:216-362` | 封禁/冷却查询 + `preparePunishment`（钳制/永久拒绝） |
| `wayzer/ext/playerMute.kts:377-434` | `applyReportStrike` / `clearRestrictionBan` / `activeRestrictionBans` |
| `wayzer/ext/playerMute.kts:443-501` | `emitPunishmentApplied` / `liftMuteByUids` / 解罚事件监听 |
| `wayzer/ext/playerMute.kts:558-741` | 禁言 / 临时禁言 / `mutePlayerTemporaryByVote` / 解禁 |
| `wayzer/ext/playerMute.kts:745-764` | `SELF_RESCUE_VOTE_SUBCOMMANDS` + `shouldBlockMutedInput`（N4/D5） |
| `wayzer/ext/playerMute.kts:788-822` | `/playermute`、`/playerunmute`、权限默认 |
| `wayzer/user/punishmentReport.kts:38-43` | 检举配置 6 项 |
| `wayzer/user/punishmentReport.kts:92-259` | 惩罚生效→弹窗→检举投票→解罚/封权流程 |
| `wayzer/user/punishmentReport.kts:273-360` | `bans`/`status`/`reset` 文本与 `/punishreport` 命令 |
| `wayzer/cmds/voteMute.kts:21, 67-110` | 投票禁言 3 天上限、发起前检查、`mutePlayerTemporaryByVote` |
| `wayzer/cmds/voteOb.kts:42-70, 72-137, 203-227, 234-300` | 观战状态/到期时间、直罚、到期清扫、投票路径 |
| `wayzer/cmds/voteOb.kts:321-323` | `quitOb` 主名 + 别名 `quob`/`解除观战`/`退出观战` |
| `wayzer/ext/playerBuildBan.kts:69-196` | 禁建永久拒绝/钳制/冷却/发事件/离线解禁（Q7） |
| `wayzer/ext/playerInfoTripleTap.kts:519-551, 595-624, 686-831` | 面板时长输入、按钮权限推导（本轮未改；面板最终走上述入口函数） |
| `wayzer/vote.lib.kt:24-48, 331-340, 395-452` | VoteEvent 参数、配置、禁止发起投票持久化 |
| `wayzer/vote.lib.kt:181-191, 217-252, 314-325, 502-565` | 同 IP 计票、投票菜单、一票否决、废弃 VoteService |
| `wayzer/vote.kts:59-77, 112-166` | `/vote` 入口、禁投票命令、权限默认 |
| `wayzer/cmds/voteKick.kts:50-82` | 投票踢出（60 分钟 ban） |
| `wayzer/user/ban.kts:25-50, 223-317` | 封禁记录/入服检查/`/banX`、`/unbanX`、`/banlist` |
| `wayzer/user/banStore.kts:22-29` | 独立脚本内建表 + `DBApi.registerTable` 先例 |
| `wayzer/security/forceObAutoCleaner.kts:64-75, 133-173, 230-269` | 自动清理边界/命令/定时器 |
| `wayzer/lib/MdtStorage.kt:458-465, 546-551, 977-1083, 3414-3436` | 禁言表、Settings 表、读写 API、禁言存储函数 |
| `wayzer/lib/TrustSystemEvents.kt:5-9, 59-63` | 事件模板、`PlayerMuteChangedEvent` |
| `wayzer/ext/playerMute.kts:20-22`、`wayzer/lib/PlayerData.kt:10-14, 65-69` | 事件发射模板、主体/游客判定 |
| `coreMindustry/menu.kts:49-56, 103-301, 926-950` | `HelpEntryDef`、帮助条目表、`canSeeAdminHelp` 聚合 |
| `coreMindustry/menu.lib.kt:34, 116` | `MenuBuilder` / `sendTo`（弹窗基础设施） |
| `docs/scripts-maintenance.md:361-392, 1036, 2110` | 投票禁言 3 天/游客闸门/重编译教训、Savable 语义、面板时长现状 |
| `docs/trust-system.md:153-160` | 持久化现状说明（本轮未新增 Exposed 表，冷却/封禁用 `MdtSettings` 两键，见 §3.5） |
| `docs/security-guard.md:22, 76` | IP 封禁持久化（同 MdtSettings 口径）、临时禁言/禁建内存态 |
| `docs/agent-debug-experience.md` §8/§9 | SA 静默失败与 KDoc 截断坑（脚本改动必须验证） |

## 菜单入口核实（2026-10-17）

> 核实问题：玩家被其他玩家通过**玩家信息面板**（`playerInfoTripleTap.kts`）施加禁言/强制观战/禁建时，被罚者是否会收到检举弹窗。实现代码：`playerMute.kts`（规则/事件，事件 :51/:83）、`punishmentReport.kts`（监听 :92、弹窗 :141）、`voteOb.kts`、`playerBuildBan.kts`。

| 面板动作（`playerInfoTripleTap.kts`） | 调用函数（行号） | 是否 fire `PunishmentAppliedEvent` | 事件 source / 备注 |
|---|---|---|---|
| 禁言此玩家 · 临时时长 | `mutePlayerTemporary`（:771 → playerMute :635，fire :660） | 是 | `direct`；非管理员钳制≤1440、24h 冷却、检举封禁生效 |
| 禁言此玩家 · 留空=永久 | `mutePlayer`（:769 → playerMute :558，fire :596） | 仅真正生效时 fire | 非管理员永久请求被 Q3 规则拒绝 → 不生效、无事件/弹窗（符合口径） |
| 强制观战此玩家 | `forceObPlayer`（:748 → voteOb :72，fire :125） | 是 | 4 级/3++：`source=admin`（管理员层级→不弹窗）；3+：`source=direct`+`directOwnerUid`，额外补 24h 到期 |
| 禁止ta建造 · 临时时长 | `disableBuildTemporary`（:795 → playerBuildBan :109，fire :145） | 是 | `direct`；同上钳制/冷却/封禁 |
| 禁止ta建造 · 留空=永久 | `disableBuild`（:793 → playerBuildBan :69，fire :101） | 仅真正生效时 fire | 非管理员永久请求被拒绝 → 无事件 |
| 解除ta禁言/解除限制（:756/:689/:780） | `unmutePlayer`/`releaseForceObPlayer`/`enableBuild` | 否（属解罚，不需要） | — |
| 投票类按钮（:677/:681） | `startKickVote` / `startObVote`（voteOb :234，fire :287） | ob 投票通过后 fire；kick 不在本轮范围 | `source=vote`，施罚者归因为投票发起者 |
| ban / ban ip / 禁止发起投票（:803/:806/:823） | ban 系统 / `VoteEvent.banVoteStarter` | 否 | 不在本轮惩罚体系范围（Q7/Q5 已说明） |

结论：**面板施加的临时禁言/强制观战/临时禁建都会 fire 事件 → `punishmentReport` 监听后给被罚者弹窗**；弹窗还需满足：`enabled=true`、目标非游客（`targetAuthed`）、施罚者等级 <3++（3++/4 不弹）、在 `Groups.player` 中找到目标、在线人数 ≥ `minOnlinePlayers`（默认 3）、距上次该目标弹窗 ≥ `popupMinIntervalMillis`（默认 60s），之后延迟 `popupDelayMillis`（默认 3s）弹窗（`punishmentReport.kts:101-139`）。非管理员"永久"类请求会被 Q3 规则先拒绝，因此不会有弹窗（行为正确）。

其它菜单/旁路：`quickOps.kts` 只转发 `/vote` 子指令（走 vote 路径，会 fire）；全仓 grep 确认 `temporaryMutes/limitPlayers/buildBanReasons/MdtStorage.setMute` 的写入只存在于 `playerMute.kts`/`voteOb.kts`/`playerBuildBan.kts` 内部，**未发现绕过事件的旁路**。

**性能检查**：游戏线程新增分支均为内存查找；持久化状态改为 `onEnable` 用 `launch(Dispatchers.IO)` 异步预加载（实测日志：`已异步加载持久化状态…耗时 1ms，线程=DefaultDispatcher-worker-13`），落库改为锁内生成快照 + 单 IO 协程串行异步写（实测：`异步落库已生效（写线程=DefaultDispatcher-worker-13）`），失败限频 60s 告警；面板惩罚实测耗时 A/临时禁言 2ms、C/直观战 5ms、D/投票禁言 1ms（无同步 DB）。剩余同步 DB 仅为原有 `MdtStorage.setMute/getMuteReason/clearMute`（禁言应用/解除，低频，未改）。

## 2026-10-17 补充：`/vote quitMute` 禁言自救（实现后追加，仅引用/补记）

> 用户新增需求"被禁言者可以发起取消禁言投票"已实现（提交 `7140bed`；维护记录见 `scripts-maintenance.md` 第二十五批）。
> 本节只作引用与补记，**不改上文口径**：上文 §1 G1"禁言/禁建没有自救入口"、§2.3/§7 的 N4 白名单等均为当时表述，
> 以本节与最终实现为准，后续维护请以 `playerMute.kts`/`voteMute.kts` 代码为真源。

- 命令：`/vote quitMute`（子指令，别名 `解除禁言`/`解禁`），**仅当前被禁言者本人**可发起；未禁言提示拒绝。
- 行为：**只解除临时禁言**；永久禁言（无期限 DB 记录）拒绝并提示联系管理员（管理员层级以下施加的可走 `/punishreport` 检举）；
  阈值沿用 VoteEvent 默认 50%+fastSuccess；`bypassDenyVote` 仅本人、`ignoreStartBan=true`（被禁止发起投票/强制观战/
  未完成教程时也可自救）；通过后 `unmutePlayer`，投票期间离线用 `liftMuteByUids` 兜底，并广播结果。
- 配套：`playerMute.SELF_RESCUE_VOTE_SUBCOMMANDS` 增加 `quitmute`/`解除禁言`/`解禁`，新增 `isPermanentMuted(target)`；
  `coreMindustry/menu.kts` 帮助条目、`voteTutorial.kts` 教程提及。
- **未做运行时行为验证**（按用户要求，写入未验证项）。
