# 成就系统说明

本文档记录当前成就系统的边界、预设成就、奖励规则和后续扩展方式。

## 脚本路径

- `mdtserver/config/scripts/wayzer/user/achievement.kts`

相关脚本：

- `wayzer/user/trustLevel.kts`：等级条件。
- `wayzer/user/trustPoint.kts`：MDC奖励。
- `wayzer/ext/playerReputation.kts`：赞/踩统计。
- `wayzer/ext/playerRecognition.kts`：认可统计。
- `wayzer/user/playerTitle.kts`：称号奖励。
- `wayzer/user/forumPosts.kts`：发帖事件与发帖数统计。
- `wayzer/lib/TrustSystemEvents.kt`：成就/等级变化事件。
- `wayzer/lib/MdtStorage.kt`、`wayzer/mdtDatabase.kts`：成就完成记录与相关统计持久化。

## 柠檬插件实现对比

已检查 `柠檬的开源插件/open/scripts/lemon/user/achievement.kts`：

- Lemon 版核心是 `finishAchievement(account, name, exp, broadcast)`，由地图/脚本主动调用后在事务中记录成就并发放经验。
- 该实现适合地图脚本直接“完成某个成就”，但没有通用的可配置条件菜单。
- MDT 当前成就系统保留事件驱动检测，并在此基础上加入“管理员自定义成就”：条件用预设参数，奖励为 MDC/称号。
- 旧 Lemon 兼容层仍用于承接地图脚本调用；如需把地图脚本成就正式映射到 MDT 成就，可后续把 `finishAchievement` 桥接到 MDT 的特殊/地图成就定义。

## 玩家指令

- `/achievements`
- `/achievement`
- `/成就`
- `/成就系统`

打开成就面板。面板规则：

- 显示所有普通成就和特殊成就。
- 未完成隐藏成就只显示：`[gold]****（隐藏成就）`。
- 隐藏成就未完成前不显示名字和奖励。
- 已完成成就的奖励位置显示：`[green][已完成]`。
- 点击已完成成就会全服展示：
  - `xxx正在展示ta的xxx[gold]成就！ 奖励：xxx MDC+称号奖励*1`
- 点击未完成成就只给自己提示，不全服广播；提示会包含该成就的达成要求。隐藏成就未完成前仍隐藏名字和奖励，但会显示达成要求。

## 管理指令与管理菜单

- `/achadmin [menu]`
- `/achadmin <玩家id/3位id> [list|check|grant|revoke] [成就code]`
- `/achievementadmin`
- `/成就管理`

用法：

- `/achadmin`：4级/管理员打开成就管理菜单。
- `/achadmin <玩家> list`：查看已完成成就。
- `/achadmin <玩家> check`：立即检测该玩家可自动完成的成就。
- `/achadmin <玩家> grant <code>`：直接授予某个成就，主要用于特殊成就。
- `/achadmin <玩家> revoke <code>`：撤销成就完成记录，但**不会回滚已发奖励**。

管理菜单可操作：

- 查看自定义成就列表。
- 新建自定义成就。
- 启用/禁用成就。
- 设置公开/隐藏。
- 修改成就名。
- 修改达成条件类型与条件值。
- 修改 MDC 奖励。
- 修改称号奖励代号与显示名。
- 删除自定义成就。
- 刷新自定义成就缓存。
- 对在线玩家批量触发一次成就检测。

权限：

- `wayzer.admin.achievement`，默认注册给 `@admin`。
- 信任等级 `4` 的在线玩家会进入 `@admin` 组，因此可以使用。

## 成就完成提示

普通成就完成时，只提示当前玩家：

```text
你完成了xxxx！奖励:xxxx
```

隐藏成就完成时，除提示本人外，还会全服广播：

```text
xxxx完成了隐藏成就：xxx！
```

## 当前预设成就

| code | 成就名 | 类型 | 条件 | 奖励 |
|---|---|---|---|---|
| `level_1_verified` | 已认证 | 普通 | 等级达到 `1` | `5` MDC |
| `level_2` | 升到2级 | 普通 | 等级达到 `2` | `10` MDC + 称号奖励 |
| `level_3` | 升到3级 | 普通 | 等级达到 `3` | `50` MDC + 称号奖励 |
| `level_3_plus` | 升到3+级 | 普通 | 等级达到 `3+` | `100` MDC |
| `first_like_given` | 首次回应 | 普通 | 首次送出赞 | `5` MDC |
| `first_forum_post` | 首次发贴 | 普通 | 首次发布帖子 | `5` MDC |
| `first_recognition_received` | 第一次认可 | 普通 | 首次收到认可 | `20` MDC |
| `received_10_likes` | 受到赞赏 | 普通 | 收到赞 `>=10` | `20` MDC |
| `given_10_likes` | 出于爱 | 普通 | 送出赞 `>=10` | `10` MDC |
| `thanks` | 谢谢 | 普通 | 收到赞 `>=10` 且送出赞 `>=20` | `30` MDC |
| `solution_agency` | 解决方案机构 | 隐藏 | 收到赞 `>=100` 且被认可 `>=20` | `200` MDC + 称号奖励 |
| `children_day` | 儿童节！ | 普通 | `6月1日` 登录/触发检测 | `20` MDC |
| `new_year` | 元旦快乐 | 普通 | `1月1日` 登录/触发检测 | 称号奖励 |
| `seed_user` | 种子用户 | 特殊 | 仅管理员授予 | `10` MDC + 称号奖励 |
| `contributor` | 贡献者 | 特殊 | 仅管理员授予 | 称号奖励 |

## 自定义成就

自定义成就定义持久化在数据库表 `MdtCustomAchievements`，玩家完成记录仍统一写入 `MdtPlayerAchievements`。

第一版自定义成就采用“单条件”设计，避免 AND/OR 表达式导致菜单复杂与检测成本不可控。可用条件参数：

| 条件 code | 菜单名 | 条件值 |
|---|---|---|
| `received_likes` | 获赞数 | 整数 |
| `given_likes` | 点赞数 | 整数 |
| `received_dislikes` | 被踩数 | 整数 |
| `given_dislikes` | 点踩数 | 整数 |
| `received_recognitions` | 获认可数 | 整数 |
| `given_recognitions` | 认可他人数 | 整数 |
| `forum_posts` | 发帖数 | 整数 |
| `play_hours` | 在线小时 | 整数，累计在线小时 |
| `current_mdc` | 当前MDC | 整数 |
| `total_mdc` | 累计MDC | 整数 |
| `trust_level` | 信任等级 | `0/1/2/3/3+/4` |
| `seniority_level` | 资历等级 | `0/1/2/3/4` |
| `login_date` | 特定日期在线/登录 | `MM-dd` 或 `yyyy-MM-dd` |
| `login_hour` | 特定小时在线/登录 | `0-23` |

奖励：

- MDC：完成时一次性增加当前/累计 MDC。
- 称号：填写称号代号与显示名后，完成时授予 `ach_<称号代号>`。

性能约束：

- 自定义成就定义有 60 秒内存缓存，管理员修改/删除后立即失效。
- 单次成就检测会加载一次 `AchievementStatsSnapshot`，其中合并了 MDC、赞踩、认可、在线时长、发帖等统计；内置统计类成就与自定义成就共用该快照，避免每条成就单独查询数据库。
- 检测入口仍保持事件驱动：等级/MDC/赞踩/认可/资历变化、发帖、商店购买、玩家加入与管理员手动检测。

## 后续添加成就的边界

当前成就系统已经能直接检测：

- 等级。
- 当前/累计MDC。
- 收到赞、收到踩、送出赞、送出踩。
- 被认可、认可他人。
- 称号拥有/佩戴状态。
- 日期类登录/检测。

如果后续成就涉及以下行为，需要先新增统计或事件：

- 建造/拆除建筑数量。
- 击杀、死亡、伤害、治疗。
- 在线时长。
- 特定日期/小时登录或在线触发检测。
- 连续签到（尚未记录连续天数，需要新增统计）。
- 商店购买次数、MDC消费累计。
  - `MdtShopPurchaseStats` 已记录商店购买次数。
  - `ShopPurchaseEvent` 已会触发一次成就检测；新增商店类成就时可在条件里调用 `MdtStorage.shopPurchaseCount(...)`。
- 发帖次数。
  - `MdtForumAuthorStats` 已记录玩家历史累计发帖数。
  - `ForumPostCreatedEvent` 已会触发一次成就检测；新增发帖类成就时可在条件里调用 `MdtStorage.getForumAuthorPostCount(uid)`。
- 技能使用次数或技能等级。
- 特定地图/特定模式/特定波次。

## 添加成就时的建议

在 `achievement.kts` 的 `achievements` 列表中新增 `AchievementDefinition`：

- `code` 必须稳定，不要改名；完成记录依赖它。
- `name` 是玩家看到的成就名。
- `requirement` 是玩家点击未完成成就时看到的达成要求；隐藏成就未完成前仍隐藏名字和奖励，但会显示该字段。
- `rewardPoints` 是奖励MDC。
- `titleRewards` 是称号奖励；显示时不会展示具体称号，只显示“称号奖励”。
- `hidden = true` 表示隐藏成就。
- `special = true` 表示只能管理员授予，不参与自动检测。
- `condition` 是自动完成条件。

注意：奖励发放是一次性的，成就完成记录先落盘再发奖励，避免重复领奖。已完成记录保存于数据库 `MdtPlayerAchievements`。
