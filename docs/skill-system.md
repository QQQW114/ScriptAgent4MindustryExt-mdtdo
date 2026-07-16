# 技能系统说明

本文档记录当前技能菜单、技能分类、商店技能和后续扩展边界。

杂交单位技能的详细分类、Buff 与品质规则另见 `docs/hybrid-system-design.md`；单位池分类表另见 `docs/hybrid-unit-catalog.md`；基因杂交运行时抽取与炮塔说明另见 `docs/advanced-hybrid-turrets.md`。

## 脚本路径

- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`：技能系统核心入口、技能分类菜单、共享工具函数、菜单条目注册。
- `mdtserver/config/scripts/wayzer/user/ext/skillsCommon.kts`：通用技能实现。
- `mdtserver/config/scripts/wayzer/user/ext/skillsLevel2.kts`：2级技能实现。
- `mdtserver/config/scripts/wayzer/user/ext/skillsLevel3.kts`：3级技能实现。
- `mdtserver/config/scripts/wayzer/user/ext/skillsHybrid.kts`：杂交系统与 `/skill hybrid`。
- `mdtserver/config/scripts/wayzer/user/ext/skillsGodAdmin.kts`：神权菜单与管理员技能实现。
- `mdtserver/config/scripts/wayzer/user/ext/skills.lib.kt`：技能命令容器、冷却、预检查、菜单/主菜单扩展注册表、全场技能免消耗开关。
- `mdtserver/config/scripts/wayzer/user/skillShop.kts`：技能商店与当前商店技能实现。
- `mdtserver/config/scripts/wayzer/map/funRuleModes.kts`：临时玩法规则工具，提供击杀单位、建筑伤害、无限火力promax、编辑器模式、洪水/Lord脚本尝试加载等能力。
- `mdtserver/config/scripts/wayzer/map/loadMapScriptCmd.kts`：管理员手动加载地图脚本指令 `/loadmapscript <id>`。
- `mdtserver/config/scripts/wayzer/cmds/voteFunRules.kts`：投票击杀所有单位、投票开启标准无限火力/无限火力promax。
- `mdtserver/config/scripts/wayzer/lib/MdtStorage.kt`：玩家已购买技能持久化。

## 玩家指令

- `/skill`、`/skills`、`/技能`：打开技能分类菜单。
- `/skill <技能名>`：直接使用某个技能。
- `/skill common`：打开通用技能。
- `/skill level2`：打开 2 级技能。
- `/skill level3`：打开 3 级技能。
- `/skill shop`：打开特殊/商店技能。
- `/skill admin`：打开管理员技能。
- `/skill hybrid`：打开杂交菜单。
- `/skill godmenu`：打开神权菜单。
- `/skillshop`、`/技能商店`：打开技能商店。

玩家信息面板中的“打开技能面板”会快速输入 `/skills`，因此会进入技能分类菜单。

菜单显示优化：通用/2级/3级/特殊商店技能按钮参考技能商店样式，按“技能名 / 效果 / 规则”明确分行，并按每页最多 8 项分页，便于后续继续扩展技能数量；为保证菜单美观，这些分类不再在按钮内显示指令行（仍可直接输入 `/skill <技能名>` 使用）。通用技能名使用 `[cyan]`，2级技能名使用 `[green]`，3级技能名使用 `[purple]`，商店技能名使用 `[cyan]`，规则行统一使用 `[gold]`。

## 技能分类与规则

- 通用技能：任何玩家可查看/使用；全部受 PVP 与 `@noSkills` 限制；无 MDC 消耗；大多 120 秒冷却，个别技能另行标注。
- 2级技能：资历2级及以上玩家可查看/使用；全部受 PVP 与 `@noSkills` 限制。
- 3级技能：资历3级及以上玩家可查看/使用；默认不受普通 `@noSkills` 限制，但仍受 PVP 限制；投票纯净模式会额外添加 `@pureNoLevel3Skills`，此时 3级技能也被禁用。
- 特殊/商店技能：分类对所有玩家可见；具体技能只有已购买且满足资历/认可/消耗等使用条件时显示；默认受 PVP 与 `@noSkills` 限制，少数理财/天气类技能会显式标注不受限制。
- 管理员技能：资历4级玩家可查看/使用；信任4级或已登录的 Mindustry 原生 admin 默认视为资历4级；不受 PVP、`@noSkills`、MDC 消耗和冷却限制。
- 杂交菜单：不放入“2级技能”分类页，而是作为 `/skill` 主菜单顶层入口；资历3级及以上可见，也可通过 `/skill hybrid` 直接打开。单位杂交6 MDC；基因杂交成功后消耗25 MDC且20%玩法失败不扣费；自选基因杂交成功后消耗78 MDC，目标同意后可从目标当前武器/能力列表中手动选择一个基因；基因清洗成功后消耗10 MDC，可清理当前附身单位类型的全部基因。
- 神权菜单：不放在“管理员技能”分类中，而是作为 `/skill` 主菜单顶层入口与通用/2级/3级/管理员技能并列；仅信任4级/已登录原生admin可见，也可通过 `/skill godmenu` 直接打开。

资历等级条件：1级=累计在线1小时+累计100MDC；2级=16小时+600MDC；3级=64小时+2000MDC；4级不自然晋升，信任4级/已登录admin 默认视为资历4级。信任等级仍用于帖子、管理权限等信任边界。

## 通用技能

| code | 显示名 | 消耗 | 冷却 | 效果 |
|---|---|---:|---:|---|
| `clearSelf` | 紫砂 | 0 | 120秒 | 杀死自己 |
| `kill` | 自爆 | 0 | 120秒 | 杀死自己，并对周围敌方单位/建筑造成自身最大血量 1/10 伤害 |
| `cola` | 紫薇 | 0 | 120秒 | 以10%最大生命为代价获得超频/护盾/加速状态 |
| `heal` | 自疗 | 0 | 120秒 | 治疗自己的单位 |
| `copper` | 生锈的铜 | 0 | 120秒 | 给当前队伍核心添加 114 铜 |
| `summonpoly` | 召唤poly | 0 | 120秒 | 召唤一只建造机 poly |
| `summonunloader` | 召唤装卸器 | 0 | 300秒 | 在脚下空地放置 `duct-unloader`，全服广播并对使用者额外提示“你不会真的以为我会给你一个赛普罗的装卸器吧” |
| `illuminator` | 照明器 | 0 | 120秒 | 在脚下空地放置 illuminator |
| `basicdefense` | 初级预制防线 | 0 | 一局一次 | 释放前要求以脚下为锚点的 4x4 范围内没有已有方块；以玩家脚下作为中心 2x2 的左下格，按炮台→供电/电池→墙体顺序建造 4x4 铜墙防线；中心 2x2 包含 `arc`、2个 `solar-panel`、1个 `battery` |
| `extinguish` | 灭火 | 0 | 120秒 | 从玩家中心向外散射原版海啸水子弹，并直接清理周围10格火焰兜底 |
| `disarm` | 缴械 | 0 | 300秒 | 给当前单位添加5分钟缴械效果 |

## 2级技能

| code | 显示名 | 消耗 | 冷却 | 效果 |
|---|---|---:|---:|---|
| `shield` | 护盾 | 2 MDC | 120秒 | 获得等于最大血量的护盾 |
| `health` | 范围治愈 | 2 MDC | 120秒 | 治疗周围200像素内受伤友方单位与建筑，每个目标恢复20%最大生命（至少25） |
| `fullheal` | 完全痊愈 | 5 MDC | 120秒 | 完全治愈当前附身单位，恢复至满血 |
| `fortune` | 查看今日运势 | 0 | 每日一次 | 在“大凶/凶/中吉/吉/大吉”中随机今日运势；随机到大吉时授予 `[gold][无不利！]` 称号 |
| `monoMother` | 递归mono | 2 MDC | 300秒 | 召唤可短时间生成 mono 的递归 mono |
| `lowwallKiller` | 墙壁粉碎者 | 0 | 120秒 | 粉碎脚下一格墙壁 |
| `sourcelottery` | 欧皇物品源 | 2 MDC | 120秒 | 脚下为空地时，1% 概率放置 item-source，否则放置 sorter |
| `coreshard` | 小伙子,来点读品？ | 2 MDC | 120秒 | 在脚下放置一个处理器 |
| `coreZone` | 核心区 | 2 MDC | 120秒 | 生成 3x3 核心区地板 |
| `flying` | 飞起 | 2 MDC | 120秒 | 让当前单位飞起 |
| `landing` | 坠机 | 10 MDC | 120秒 | 让当前单位降落 |
| `runfaster` | 你跑不过我你信不信 | 20 MDC | 0 | 当前单位实际叠加3层 fast 状态；120秒后若该单位仍存活则猝死并广播提示 |
| `boundmega` | 绑定mega | 5 MDC | 一局一次 | 召唤一只专属 `mega` 并强制释放者附身；其他玩家无法附身，若出现异常抢占会销毁该 mega |
| `laststand` | 拼死一搏 | 5 MDC | 300秒 | 当前附身单位获得最大血量护盾与5层叠加 `overclock`，20秒后若仍存活则死亡 |
| `rocket` | 空对地导弹 | 2 MDC | 0 | 将玩家附身单位切换为 `scathe-missile-phase`（找不到该内部单位时临时回退为 elude） |
| `decisiveSquad` | 决胜中队 | 6 MDC | 120秒 | 召唤 5 个携带 `blast-compound` 的 `zenith` |
| `anvilSquad` | 铁砧小队 | 6 MDC | 120秒 | 从随机方向呼叫不可附身雷霆（`quad`）运输机，抵达释放坐标后投放 2 个 `locus` 与 5 个 `stell`，随后运输机自毁；运输机死亡或 40 秒超时则投放取消且不返还 MDC |
| `hammerSquad` | 铁锤小队 | 6 MDC | 120秒 | 从随机方向呼叫不可附身雷霆（`quad`）运输机，抵达释放坐标后投放 3 个 `spiroct`、5 个 `mace` 与 5 个 `atrax`，随后运输机自毁；运输机死亡或 40 秒超时则投放取消且不返还 MDC |

## 3级技能

| code | 显示名 | 消耗 | 冷却 | 效果 |
|---|---|---:|---:|---|
| `blitz` | 骇人空袭 | 10 MDC | 一局一次 | 在玩家位置快速召唤三波自爆苍穹，每波间隔0.2秒，目标点限制在光标方向约45格内；短时间翻倍队伍坠毁伤害 |
| `antiarmor` | 反装甲炮击 | 15 MDC | 300秒 | 在玩家鼠标位置标记半径20格炮击区域，持续30秒；区域内出现敌方单位时锁定最高血量目标，每1.5秒造成目标最大生命10%+800伤害 |
| `pddCut` | 拼夕夕砍一刀 | 10 MDC | 一局一次 | 全场单位当前血量减少 90% |
| `disaster` | 天灾 | 10 MDC | 一局一次 | 随机天气 120 秒，期间每 10 秒削减全场单位 5% 当前血量 |
| `redLightGreenLight` | 123木头人 | 10 MDC | 一局一次 | 停止所有单位，并随机击杀 10 个单位 |
| `gaokao` | 参加高考 | 20 MDC | 每日一次 | 随机 0-750 分；400 分以下按分数扣 MDC，400 分及以上按分数给 MDC；700+授予 `[gold]高考状元` |
| `firetruck` | 消防车 | 0 | 300秒 | 6秒内每秒从玩家中心向外散射原版海啸水子弹，并直接清理周围10格火焰兜底 |
| `omg` | omg | 15 MDC | 0 | omg |
| `standarddefense` | 标准预制防线 | 15 MDC | 一局一次 | 释放前要求以脚下为锚点的 6x6 范围内没有已有方块；以玩家位置为蓝瑟左下坐标，按炮台→供电/电池→墙体顺序建造塑钢墙蓝瑟防线；蓝瑟周边除底部两格电池外均为太阳能板 |
| `missileVolley` | 导弹齐射 | 10 MDC | 0 | 在玩家位置附近召唤 10 个 `scathe-missile-surge` |
| `supplyitem` | 物资补给 | 10 MDC | 0 | 打开当前内容物资菜单或输入物资英文/本地名；为本队核心添加指定物资 x100；PVP 禁用 |
| `randommaga` | 随机maga | 20 MDC | 一局一次 | 召唤一只 `mega`，并直接塞入随机有效建筑方块作为载荷；随机池排除地形/地板/矿物覆盖层、无分类/隐藏/调试/无法构造为载荷的无效方块；仍可突破载荷上限 |
| `nuke` | 核弹打击 | 20 MDC | 一局一次 | 释放时广播光标坐标，5秒后在玩家光标位置触发钍反应堆爆炸效果与范围伤害；抵达提示为“核打击已经抵达！” |
| `refreshskills` | 刷新技能 | 100 MDC | 300秒 | 清除自己当前所有技能冷却；本技能会在释放后重新进入300秒冷却 |
| `tietie` | 贴贴 | 100 MDC | 0 | 指向在线玩家发送贴贴请求；接受后双方静止并传送贴贴，随后生成随机T1-T4单位；拒绝/超时则在释放者位置生成随机T1-T3单位 |

## 管理员技能

| code | 显示名 | 效果 |
|---|---|---|
| `source` | 物品源 | 在脚下附近放置 3x3 物品源 |
| `ecore` | E星核心 | 在脚下附近放置 3x3 核心 |
| `invincible` | 无敌 | 给当前单位添加长时间强力状态 |
| `examtime` | 考试时间！ | 全员依次生成高考成绩，不发称号，仅前三按分数/10四舍五入获得 MDC |
| `freeSkillCost` | 全场技能买单 | 本局所有玩家释放技能不再消耗 MDC；ResetEvent 后自动关闭 |
| `doublemdcreward` | 本局结算MDC翻倍 | 本局贡献结算获得的 MDC 翻倍；ResetEvent 后自动关闭 |
| `killallunits` | 击杀所有单位 | 清空当前地图所有单位 |
| `infinitefire` | 无限火力promax | 默认开启 120 秒无限火力promax；`/skill infinitefire off` 可手动关闭脚本开启的无限火力promax，并停止维护循环 |
| `wallkillerpro` | 墙体粉碎者pro | 粉碎周围 5x5 范围内的玩家墙体与天然墙 |
| `daoshengyi` | 道生一.... | 每秒在身边召唤一只 mono，持续20秒；每只 mono 20秒后死亡 |
| `powersource` | 现在的发电量是1m！电力，轻而易举啊 | 在脚下空地放置 power-source |
| `floodon` / `floodoff` | 开启/关闭洪水脚本 | 尝试启用/停用 `mapScript/tags/flood` |
| `lordon` / `lordoff` | 开启/关闭Lord脚本 | 尝试启用/停用 `mapScript/14668`；该脚本是整图脚本，不保证任意地图适配 |
| `addnoskill` | 开启noskill限制 | 为当前地图规则添加 `@noSkills` 标签；该标签只用于服务端技能检查，不热同步完整规则 |
| `removenoskill` | 解除noskill限制 | 移除当前地图规则中的 `@noSkills` 标签；该标签只用于服务端技能检查，不热同步完整规则 |

`floodon/floodoff`、`lordon/lordoff` 不单独实现脚本生命周期管理；它们复用 `wayzer/map/funRuleModes.kts` 中与 `/loadmapscript`、`/unloadmapscript` 相同的加载/关闭逻辑。关闭时会避免连带停用原本已启用的公共脚本（例如 `coreMindustry/contentsTweaker`）。

## 管理员地图脚本指令

- `/loadmapscript <id或路径>`：尝试加载 `scripts/mapScript/<id>.kts`。
  - 示例：`/loadmapscript 14668` 会尝试加载 `scripts/mapScript/14668.kts`。
  - 示例：`/loadmapscript tags/flood` 会尝试加载 `scripts/mapScript/tags/flood.kts`。
  - 为安全起见，只允许加载 `mapScript` 下的相对路径，不允许 `..` 或绝对路径。
  - `/unloadmapscript <id或路径>` 使用同一套安全关闭逻辑，只关闭目标地图脚本，并恢复误被连带停用的常驻脚本。
  - 强行加载整图专用地图脚本有兼容风险，失败时请查看服务端日志。

## 投票技能/规则

- `/vote killunits`：投票击杀当前所有单位。
- `/vote infinitefire`：投票开启 120 秒标准无限火力。
- `/vote infinitefirepromax`：投票开启 120 秒无限火力promax。
- `/vote pure <1-10>`：投票安排接下来若干局纯净模式，从下一局开始自动添加 `@noSkills` 与 `@pureNoLevel3Skills`。
- `/vote pureoff`：投票取消后续纯净模式；若当前局的 `@noSkills` / `@pureNoLevel3Skills` 由纯净模式自动添加，也会移除。

## 当前商店技能

| 商品ID | 技能code | 显示名 | 购买要求 | 使用限制 |
|---|---|---|---|---|
| `1` | `radar` | 雷达 | 60 MDC，最低资历1级 | 使用4 MDC，冷却600秒，开雾300秒 |
| `2` | `fluid` | 随机液体 | 60 MDC，最低资历1级 | 使用4 MDC，冷却300秒，生成随机2x2液体 |
| `3` | `randomore` | 随机矿 | 60 MDC，最低资历1级 | 使用4 MDC，冷却300秒，生成随机2x2矿物 |
| `4` | `wallkiller` | 粉碎墙壁 | 100 MDC，最低资历1级 | 使用4 MDC，冷却300秒，粉碎3x3墙壁 |
| `5` | `corezone4` | 核心区4x4 | 120 MDC，最低资历1级 | 使用6 MDC，冷却1200秒，生成4x4核心区 |
| `6` | `betray` | 叛变 | 120 MDC，最低资历1级 | 使用10 MDC，冷却300秒，随机策反5个非本队单位 |
| `7` | `banme` | BAN自己5分钟 | 1 MDC，最低资历1级 | 使用0 MDC，踢出自己5分钟并获得5 MDC；使用端不受死亡/PVP/noskill/一局一次限制 |
| `8` | `reptile` | 爬爬盲盒 | 60 MDC，最低资历1级 | 使用2 MDC，冷却200秒，随机召唤T1-T4爬爬 |
| `9` | `teleportation` | 传送 | 60 MDC，最低资历1级 | 使用4 MDC，冷却300秒，传送10个随机单位到自己位置 |
| `10` | `rain` | 唤雨 | 10 MDC，最低资历1级 | 使用0 MDC，冷却240秒，下雨60秒；不受PVP/noskill限制 |
| `11` | `kickmebutoct` | 踢自己送oct | 60 MDC，最低资历1级 | 使用10 MDC，一局一次；踢出自己并给本队召唤 oct |
| `12` | `imcute` | 我很可爱 | 1 MDC，最低资历1级 | 使用0 MDC，一局一次；给自己 +2 MDC；不受PVP/noskill限制 |
| `13` | `lottery` | 抽奖 | 20 MDC，最低资历1级 | 使用5 MDC，一局一次；随机获得 MDC，菜单介绍不显示概率；不受PVP/noskill限制 |
| `14` | `randomunit` | 随机单位 | 60 MDC，最低资历1级 | 使用10 MDC，一局一次；从当前内容列表可召唤单位中抽取一个 |
| `15` | `ultirandom` | `[purple]终极随机` | 200 MDC，最低资历1级 | 使用66 MDC，冷却20秒；倒计时后随机触发加MDC、启用玩法脚本、击杀单位、伤害建筑、踢出自己、假关服、涩图禁令替换逻辑显示为太阳能板、开放全部科技、立即跳10波、全员猫娘、添加 `@noSkills`、扣除使用者100MDC（不足清零）、“砸哇路多”静止全单位15秒或“反客为主”转投场上其它有效队伍 |
| `16` | `missilestorm` | 导弹风暴 | 100 MDC，最低资历1级 | 使用20 MDC，冷却300秒；5秒内每0.5秒在玩家周围召唤5个随机导弹单位，可随机 `scathe-missile`、`scathe-missile-phase`、`scathe-missile-surge`、`scathe-missile-surge-split` |
| `17` | `fishonlyyou` | 此生只属鱼你 | 10 MDC，最低资历2级 | 使用0 MDC，不受 `@noSkills` 限制，默认 PVP 禁用；召唤一只专属 risso 鱼鱼，其他玩家无法附身，会以脚本轻量跟随释放者；同一玩家同时只保留一只 |
| `18` | `missileburst` | 导弹连射 | 100 MDC，最低资历1级 | 使用10 MDC；从当前单位朝向连续发射40个 `scathe-missile-surge-split` 小导弹；默认受 PVP 与 `@noSkills` 限制 |
| `19` | `facephd` | `[gold]对面对面读博` | 200 MDC，最低资历1级 | 使用时不收固定技能费；发起者选择 2/10/25/50/200/500 MDC 赌注并额外支付10%手续费（至少1 MDC），目标同意后双方下注并暂扣，目标MDC不足会取消；胜者拿走双方赌注之和，非凭空赠送MDC；PVP中赌指定队伍获胜，非PVP中赌本局正常完成/玩家方胜利或失败/换图，沙盒/编辑器禁用；一局只能成功发起一次，不受 PVP 与 `@noSkills` 限制 |

备注：2级技能已有 `coreZone`，商店中的 4x4 版本使用 `corezone4`，避免命令 code 冲突。

新增商品从 `10` 开始编号，是为了避免覆盖旧商品 `1-9` 与已购买技能数据；若后续确实要重排商品ID，应先确认持久化购买记录和商店统计是否需要迁移。

## 实现边界备注

- “无限火力promax”会临时提高单位/方块伤害倍率，并让相关 TeamRule 进入 `cheat`/`fillItems`/`infiniteResources`；脚本还会周期性为物品/液体炮塔、单位工厂、重构厂、单位组装厂及常规消耗器补物品/液体/电力。它不等同于所有武器零冷却，也不会无视单位上限；神权菜单可长期开启并手动关闭。
- 当前服务端可通过 `Call.sound` / `Call.soundAt` 触发客户端播放原版或双方都已注册的 `Sounds` 音效；不能直接把任意自定义音频发送给纯原版客户端播放。
- “编辑器模式”当前为近似实现：临时开启 `editor`、`allowEditRules`、`infiniteResources` 与 `instantBuild`，不等同完整沙盒 gamemode；神权菜单可长期开启并手动关闭。
- “神权菜单”的队伍倍率页默认列出默认队伍、波次队伍、基础队伍、在线玩家队伍和当前激活队伍，也可按队伍 ID 手动输入 `0-255` 或按英文名选择未激活队伍，用于调整敌方或其它队伍的血量/攻击等 TeamRule 倍率。
- Lord of War 是 `mapScript/14668.kts` 整图脚本，不是通用标签脚本；`/loadmapscript 14668`、`/skill lordon` 和终极随机中的 Lord 分支只做尝试加载，不保证任意地图可用。
- 随机单位会过滤隐藏、内部、无构造器、无血量单位；因此不是把所有内部/隐藏内容都纳入抽池，避免抽到不可生成或异常单位。

## 持久化

- `MdtPlayerSkills`：玩家已购买/解锁的技能。
- `MdtShopPurchaseStats`：技能商店购买次数统计。
- `MdtSeniorityProfiles`：资历等级、资历锁与累计在线时长。

## 后续扩展建议

新增技能时：

1. 如果是技能本体，优先注册为 `/skill <code>` 子命令，也就是挂到 `SkillCommands` 下。
2. 同步注册 `SkillMenuEntry`，否则玩家只能记命令使用，菜单里看不到。
3. 普通/2级技能默认使用 `SkillPrecheck`，会检查客户端、死亡状态、`@noSkills`。
4. 3级技能建议使用 `SkillPrecheckLevel3`，它会绕过普通 `@noSkills`，但会响应纯净模式的 `@pureNoLevel3Skills`；是否 PVP 禁用由技能自行决定。
5. 管理员技能应只对资历4级/信任4级/已登录admin显示和使用，并显式跳过 `@noSkills` 与 PVP 禁用限制。
6. 商店技能应独立放在 `skillShop.kts` 或后续拆出的商店技能脚本里，不要塞进 `shopList.kts`。
7. 技能使用消耗应通过 `SkillCostManager` 兼容“全场技能买单”。
