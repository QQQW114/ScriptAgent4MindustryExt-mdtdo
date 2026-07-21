# MDT 脚本维护总览

本文档用于记录本项目中**由我们新增或修改过的脚本/模块**，便于后续维护者或 Agent 快速了解：

- 文件路径
- 脚本职责
- 我们做过的关键改动
- 重要依赖/注意事项
- 后续维护建议

> 维护规则：后续只要新增、重命名、拆分、删除或明显修改脚本职责，都应同步更新本文档。

## 2026-07-21：MindustryX B480 / v159.7 网络同步与性能链路定稿

- 参考项目更新到 Mindustry `v159.7`、MindustryX `prerelease-2026.07.20.B480`。上游的大世界发送修复仅影响 Steam `SNet`，headless 专服仍需本项目补丁。
- B480 自定义补丁补齐批量 `Net.send` 的 `SendPacketEvent`，事件携带 `connections`、`targetCount`、`reliable`；`trafficMonitor.kts` 按实际目标数统计批量上行。游戏同步改用白名单分类，握手、欢迎、插件、音乐、CP、杂交世界流不再触发性能清理。
- `coreUnitRespawnCompat.kts` 在首次加入、内部重同步、核心单位变化和主动取消附身后可靠补发 `PlayerSpawn`，按 0/1/3/8 秒重试 `Unit -> Player`；第一轮实体快照可靠，后续 UDP，不执行全量 `/sync`。
- `worldResyncCoordinator.kts` 统一接管点歌、SFX、技能/地图杂交、外部 CP 与管理 CP。优先级为 CP 300、杂交 200、普通 150、点歌 100、SFX 50；队列上限 32、排队 180 秒、任务后恢复 2.5 秒。L1 与首次加入共享 2 槽，L2+ 全服 1 槽且首次加入优先；确认超时会继续持槽最多 120 秒。
- `syncThrottle.kts` 删除快照拦截与重路，只把原生间隔保守调整为 240/280/320ms；可靠建筑血量包不再降级为 UDP。
- `serverPressureActions.kts` 每轮只使用当前级预算，L4 默认最多 400，并处理数量前三的压力单位。`performanceGuardExperimental.kts` 已收缩为兼容入口，第二套清单位、暂停与直接换图执行器彻底移除。
- 构建：`gradle --no-daemon server:dist -x tools:doPack`；产物 `mdtserver/server-2026.07.20.B480-mdtdo.jar`；SHA-256 `8257C7185BF7915270C396B05A39AD32DD6C6CEC71135CD67A70C4E0906E5ACC`。冷启动：156 个脚本、加载 152、启用 148、出错 0。

## 2026-07-18：v159 网络同步保护与性能优化统一

类型：网络同步适配、上行统计重构、性能措施合并

涉及文件：

- `mdtserver/config/scripts/wayzer/reGrief/trafficMonitor.kts`
- `mdtserver/config/scripts/wayzer/map/serverPressure.kts`
- `mdtserver/config/scripts/wayzer/map/serverPressureActions.kts`
- `mdtserver/config/scripts/wayzer/reGrief/connectSyncGuard.kts`
- `mdtserver/config/scripts/wayzer/reGrief/syncThrottle.kts`
- `mdtserver/config/scripts/wayzer/map/performanceGuard.kts`
- `mdtserver/config/scripts/wayzer/map/performanceGuardExperimental.kts`
- `mdtserver/config/scripts/wayzer/map/adaptivePlayerLimit.kts`
- `mdtserver/config/scripts/wayzer/reGrief/inactivePressureCheck.kts`

改动：

- 上行拆分为总上行、游戏同步上行、世界/资产流；积分板显示“总上行 / 同步上行”。
- 世界流、玩家加入、音乐与 CP 只进入网络保护，不参与清单位、关闭处理器等性能判断。
- 新增仅在网络压力时启用的入服同步门控；正常状态不限制，超时/异常/压力数据失效/卸载均 fail-open。
- X35 的逐玩家同步接管替换为 v159 原生 `snapshotInterval` 调整；待加入连接快照重路失败时回退原版广播，不能丢快照。
- 标准/实验性性能执行器合并，旧 `/xperf` 仅保留兼容入口；投票可关闭本局性能优化。
- 原有分级清单位、PPS 异常退出、严重超量和等级4数量前三清理继续保留；PPS 保护 `mono/pulsar/quasar/poly/mega` 辅助线。
- 最终换图改为 TPS 滑动均值连续 2 分钟低于 5 才触发。

验证：

- SA 3.4 + MindustryX B477 冷启动：`共找到155脚本,加载成功151,启用成功147,出错0`。
- 首次冷编译期间 watchdog 可记录十余秒启动停顿；服务器开放端口后的运行期需另行实机观察多人加入和上行满载。

## 2026-07-17：ScriptAgent 3.4 迁移与159核心机重生修复

类型：基础框架迁移、数据库服务迁移、客户端单位同步兼容

涉及文件：

- `mdtserver/config/mods/ScriptAgent4MindustryExt-3.4.0-allInOne.jar`
- `mdtserver/config/scripts/bootStrap/default.kts`
- `mdtserver/config/scripts/coreLibrary/.metadata`
- `mdtserver/config/scripts/coreLibrary/db/.metadata`
- `mdtserver/config/scripts/coreLibrary/db/module.kts`
- `mdtserver/config/scripts/coreLibrary/db/lib/DBApi.kt`
- `mdtserver/config/scripts/coreLibrary/DBConnector.kts`
- `mdtserver/config/scripts/coreLibrary/lib/ServicesExt.kt`
- `mdtserver/config/scripts/coreLibrary/lib/CommandApi.kt`
- `mdtserver/config/scripts/coreLibrary/lib/CommandApiExt.kt`
- `mdtserver/config/scripts/coreLibrary/lib/ContextScriptCompat.kt`
- `mdtserver/config/scripts/wayzer/module.kts`
- `mdtserver/config/scripts/wayzer/mdtDatabase.kts`
- `mdtserver/config/scripts/wayzer/lib/MdtStorage.kt`
- `mdtserver/config/scripts/wayzer/user/banStore.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skills.lib.kt`
- `mdtserver/config/scripts/wayzer/reGrief/coreUnitRespawnCompat.kts`

改动：

- ScriptAgent 从 3.3.2 迁移到 3.4.0；旧 JAR 保留为 `.disabled`，SA 编译缓存备份移到 `mdtserver/sa-cache-backups/`，不得放回 `config/scripts/` 让扫描器误识别。
- 按 SA 3.4 模块规则补充 `.metadata`，Kotlin 编译参数改为 `-Xcontext-parameters`；同步迁移控制指令、热加载、配置、Command API 与 Mindustry 命令实现。
- 新增 `ContextScriptCompat.kt`，反射兼容本项目大量旧 `contextScript<T>()` 调用；后续新脚本优先使用 SA 3.4 官方 Services/上下文 API，不继续扩大兼容层。
- 数据库从旧 `coreLibrary/DBApi.kts` 的 `ServiceRegistry` 迁移到独立 `coreLibrary/db` 模块和 SA 3.4 `Services`。旧方式会因脚本类加载器隔离出现“连接器已 provide、业务仍 No Provider”；当前 `DBConnector` 连接并初始化表结构后调用 `Services.provide(db)`，同时保留 H2 预热/保活和慢 IO 提示。
- `wayzer/module.kts` 依赖 `coreLibrary/DBConnector`；`MdtStorage`、`mdtDatabase`、`banStore` 改用 `coreLib.db.DBApi`。启动日志已确认 Provider 注册成功，`No Provider` 消失。
- Kotlin 2.3 下匿名 context parameter 不再让技能 lambda 直接解析 `CommandContext.arg`；`SkillScope` 显式暴露参数列表，恢复管理员技能、杂交、三级技能与集合/传送脚本编译。
- `CommandApi` 的注册/移除/重建操作增加同步保护，避免 SA 3.4 集中卸载脚本时并发修改 `registeredCommands` 触发 `ConcurrentModificationException`。
- 新增159核心机重生兼容：主动取消附身后调用 `checkSpawn()`，并按“新核心单位 -> Player”顺序给目标客户端补发两个实体的定向快照及可靠 `PlayerSpawnCallPacket`。不进行全量世界/资产同步。
- 重点地图 `14668`、`15450`、`@hybrid`、`@flood` 已在 SA 3.4 下恢复加载；`15450` 的柠檬 `PlayerData` 使用别名避免 Kotlin 2.3 同名冲突。

验证状态：

- 数据库连接、表初始化、Provider 注册成功。
- 修复后控制台 `sa fail` 无输出。
- 核心机兼容脚本已编译/热加载；仍需真实客户端验证取消附身、正常死亡、换队、观战与换图。
- 生产服若首次迁移后在 `ScriptClassLoader.kt:24` 实例化 `wayzer/user/achievement` 时空指针，优先判定为旧 `config/scripts/cache` 或 SA 3.4 并行实例化竞态；先在依赖全部启用后重载 achievement/playerInfoTripleTap，仍失败再停服清空编译缓存。不要误删 `config/scripts/data`。
- 生产服复测发现 H2 JDBC 2.0.206 与 KVStore 的 h2-mvstore 2.3.232 同时进入 WayZer 业务 ClassLoader，玩家加入时 `regionAutoLang` 稳定触发 `MVMap loader constraint violation`。已从 `coreLibrary/db` 移除 H2、将 `wayzer` 依赖改回纯 DB API，并让 `DBConnector` 使用 Connection lambda 隔离驱动。全量清缓存冷启动验证为154脚本、出错0。
- `coreMindustry/console.kts` 的 System.out 包装不再在日志调用线程直接执行 JLine `printAbove`；改为容量1024的 IO 输出队列，避免生产宿主控制台/磁盘输出阻塞 HeadlessApplication 主线程数十秒至数分钟。

## 文档索引

- `docs/scripts-maintenance.md`：项目级脚本维护总览，也就是本文档。
- `docs/trust-system.md`：信任等级、赞踩、认可、MDC（MDT DO Credit）相关的指令与规则说明。
- `docs/hybrid-unit-catalog.md`：杂交单位玩法开发用单位分类表，整理 T1-T5、陆辅/空辅/海战/导弹等单位池。
- `docs/achievement-system.md`：成就系统、预设成就、奖励和后续扩展边界。
- `docs/leaderboard-system.md`：排行榜系统，包含MDC、帖子、赞踩、认可排行。
- `docs/shop-system.md`：通用商店、称号商店、商品管理与后续商店扩展边界。
- `docs/skill-system.md`：技能分类菜单、商店技能、技能扩展边界。
- `docs/database-system.md`：数据库持久化、表分组与账号系统落盘说明。
- `docs/account-system.md`：账号注册/登录、密码、管理员改密、未登录观战投票说明。
- `docs/private-message.md`：私聊系统、回复最近对象、限速与禁言联动说明。
- `docs/region-welcome-language.md`：地区欢迎、IP地区识别与自动语言选择说明。
- `docs/map-scripts.md`：从柠檬开源插件迁入的地图特殊玩法脚本、兼容层与排除项。
- `docs/help-menu.md`：新版 `/help` 分区菜单的入口、分区和维护方式。
- `docs/wiki-system.md`：Wiki 系统、长文本分页显示与 3+级/管理员游戏内编辑说明。
- `docs/forum-posts.md`：帖子系统、评论、为作者赞踩、自动清理和权限说明。
- `docs/tips-system.md`：Tips 小提示系统、随机轮播、管理指令和存储说明。
- `docs/server-description.md`：服务器列表介绍轮播、管理指令和存储说明。
- `docs/performance-guard.md`：v159统一性能优化、网络同步保护、TPS分级措施与极端换图说明。
- `docs/security-guard.md`：安全风控、聊天/菜单/连接限速、IP封禁与同IP共票说明。
- `docs/vote-save.md`：投票创建存档、存档槽位与回档说明。
- `docs/logic-draw-guard.md`：逻辑绘图/显示器/画布方块开关与服务器列表内容安全说明。

---

## 已新增/修改脚本记录

### 2026-07-05 `/fill` 多格建筑与 `~` 坐标支持

类型：管理指令修复/扩展
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/cmds/setBlock.kts`
- `mdtserver/config/scripts/wayzer/cmds/gatherTp.kts`
- `docs/help-menu.md`
- `docs/scripts-maintenance.md`

改动：

- 修复 `/fill block ... air` 被 `air.isFloor` 误判为地形的问题；现在 `air` 只允许作为建筑层清理目标，`/fill floor ... air` 会明确拒绝。
- `/fill` 坐标新增 `~`：表示执行者当前单位所在格对应轴，例如 `/fill block ~ ~ ~ ~ air` 清理脚下建筑，`/fill block ~ ~ 20 20 copper-wall` 从脚下填到目标坐标。
- `/tp` 地图格坐标同样支持 `~`，例如 `/tp ~ ~`、`/tp @e[unit=mono] ~ ~`。
- `/fill block` 取消“仅支持 1x1 方块”的硬限制，改为按 Mindustry `Tile.setBlock` 的实际锚点映射铺设多格建筑：`offset = -(size - 1) / 2`，因此 `size=2` 覆盖 `anchor..anchor+1`，`size=3` 覆盖 `anchor-1..anchor+1`，`size=4` 覆盖 `anchor-1..anchor+2`。
- 多格建筑填充会按建筑尺寸步进，只放置完整落在选择区域内的建筑；`-keep` 会检查整块占地，只要占地任一格已有建筑就跳过该建筑，避免重叠。

### 2026-07-05 `/effect` 支持清除状态效果

类型：管理指令扩展
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/cmds/effect.kts`
- `docs/help-menu.md`
- `docs/scripts-maintenance.md`

改动：

- `/effect` 新增 `clear` 子语义：`/effect [选择器|玩家UUID] clear [效果ID]`。
- 指定效果 ID 时只清除目标单位身上该状态效果的全部叠加条目；不指定效果 ID 时调用原版 `clearStatuses()` 清除全部状态效果。
- 无选择器时仍默认 `@s`，因此 `/effect clear` 会清理执行者当前单位全部状态，`/effect clear fast` 会清理执行者当前单位的 `fast`。

### 2026-07-05 新增2级技能：查看今日运势

类型：2级娱乐技能新增
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/user/ext/skillsLevel2.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
- `docs/skill-system.md`
- `docs/scripts-maintenance.md`

机制：

- 新增 `/skill fortune`，中文别名 `查看今日运势` / `今日运势` / `运势`，资历2级，PVP 与 `@noSkills` 禁用。
- 每个玩家账号每天只能查看一次；结果在“大凶、凶、中吉、吉、大吉”中随机，无 MDC 消耗。
- 随机到“大吉”时通过 `wayzer/user/playerTitle` 授予称号 `[gold][无不利！]`，称号 code 为 `daily_fortune_no_disadvantage`。

### 2026-07-05 小队运输机/成就提示/反装甲炮击规则调整

类型：技能稳定性与成就提示优化
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/user/ext/skillsLevel2.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skillsLevel3.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
- `mdtserver/config/scripts/wayzer/user/achievement.kts`
- `docs/skill-system.md`
- `docs/achievement-system.md`
- `docs/scripts-maintenance.md`

改动：

- 铁砧小队/铁锤小队雷霆（`quad`）运输机增加显式死亡与 40 秒超时检查；若运输机死亡、失效或超时未抵达，投放取消、清理不可附身状态记录，并仅提示释放者 MDC 不返还。
- 成就系统为 `AchievementDefinition` 增加 `requirement` 文本；玩家点击未完成成就时，会收到仅自己可见的达成要求。隐藏成就未完成前仍隐藏名字和奖励，但会显示达成要求。
- 反装甲炮击从 10 MDC、一局一次调整为 15 MDC、300 秒冷却。

### 2026-07-05 新增3级技能：反装甲炮击

类型：3级技能新增
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/user/ext/skillsLevel3.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
- `docs/skill-system.md`
- `docs/scripts-maintenance.md`

机制：

- 新增 `/skill antiarmor`，中文别名 `反装甲炮击` / `反装甲集束炮击`，资历3级，PVP禁用，投票纯净模式禁用，消耗 15 MDC，300 秒冷却。
- 在玩家鼠标位置生成半径 20 格、持续 30 秒的炮击区域；释放时有区域波纹与标签提示，持续期间周期性显示区域波纹。
- 区域内存在敌方单位时，优先锁定当前区域内最大生命值最高的目标；锁定目标仍有效且未离开区域时保持锁定，否则重新搜索。
- 每 1.5 秒对锁定目标造成 `目标最大生命值 * 10% + 800` 伤害，并显示炮击特效和伤害标签。

维护备注：

- 当前实现只打单位，不打建筑；中立 `derelict` 单位不视为敌方目标。
- 逻辑参考了世界处理器“反装甲集束炮击”的范围提示、持续锁定和按最大生命百分比加固定伤害的思路，但按服务器技能需求收敛为单目标锁定。

### 2026-07-05 2级小队技能运输机投放调整

类型：技能表现/投放逻辑调整
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/user/ext/skillsLevel2.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
- `docs/skill-system.md`
- `docs/scripts-maintenance.md`

改动：

- `anvilSquad`（铁砧小队）与 `hammerSquad`（铁锤小队）不再立即在玩家周围生成全部单位；改为在释放坐标周围随机方向、随机距离生成一架不可附身雷霆（`quad`）运输机。
- 运输机自动飞往玩家释放技能时的坐标，抵达后投放原本的小队单位并自毁；运输机 ID 使用内存表拦截 `UnitControlCallPacket`，世界重载/脚本卸载时清理，避免残留；若运输机死亡或 40 秒超时未抵达，则取消投放且不返还 MDC。
- `decisiveSquad`（决胜中队）召唤的 `zenith` 现在与 3级 `blitz`（骇人空袭）单位一致，携带 `Items.blastCompound` 60。

维护备注：

- 运输机仅作为表现与延迟投放载体，投放单位仍沿用原本类型、数量和状态效果。
- 若后续需要让运输机承受更多火力，可单独调整 `launchSquadDeliveryQuad` 中的护盾/状态；当前仅附带 `disarmed` 与短时 `shielded`。

### 2026-07-05 `/tp` 坐标传送与集合安全判定修复

类型：管理传送指令扩展、集合技能修复
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/cmds/gatherTp.kts`
- `mdtserver/config/scripts/coreMindustry/menu.kts`
- `docs/help-menu.md`
- `docs/scripts-maintenance.md`

改动：

- `/tp` 新增直接坐标传送：`/tp <x> <y>`、`/tp <x,y>` 或 `/tp ~ ~` 将执行者当前单位传送到地图格坐标；`/tp <玩家|选择器> <x> <y>` 可将指定玩家/选择器单位批量传送到地图格坐标；坐标中的 `~` 表示执行者当前单位所在轴。
- `/tp` 仍保留原有语义：无参数传送自己到鼠标；单个玩家参数传送自己到目标玩家；两个非坐标参数按“来源玩家/选择器 → 目标玩家/选择器”处理。
- 修复集合 `go` 的安全判定：地面单位只要目标格可通行且没有大型地面单位占位即可传送；若集合点被大型地面单位占用或不可通行，会在周围 6 格内寻找最近安全格；原先判定条件写反，导致非空军单位经常被误判为“目标位置无法安全传送”。

维护备注：

- `/tp` 坐标按地图格坐标解析，而不是世界像素坐标；与 `/setblock`、集合广播中的坐标口径一致。
- 若后续希望支持世界像素坐标，应新增显式语法，避免与玩家三位短ID/目标选择器产生歧义。

### 2026-07-04 管理单位选择器统一为 Minecraft 风格

类型：管理指令选择器统一、旧语法兼容
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/cmds/unitSelector.lib.kt`
- `mdtserver/config/scripts/wayzer/cmds/killPlayer.kts`
- `mdtserver/config/scripts/wayzer/cmds/gatherTp.kts`
- `mdtserver/config/scripts/wayzer/cmds/effect.kts`
- `mdtserver/config/scripts/coreMindustry/menu.kts`
- `docs/help-menu.md`
- `docs/scripts-maintenance.md`

改动：

- 新增共享单位选择器库，`/kill`、`/tp`、`/effect` 统一使用同一套解析逻辑，避免各指令选择器能力不一致。
- 主推 Minecraft 风格选择器：`@e[unit=mono]`、`@e[team=2]`、`@e[team=2,unit=mono]`，并支持 `@a`、`@s`、`limit=<数量>`、`sort=nearest|furthest|random`。
- 保留旧写法兼容：`@t[2]` 等价 `@e[team=2]`，`@u[mono]` 等价 `@e[unit=mono]`，`@t[2,mono]` 等价 `@e[team=2,unit=mono]`。
- 直接玩家目标继续支持并明确包含玩家 UUID/主体ID/三位短ID/`#游戏ID`/名字；`/effect` 为避免与效果名冲突，只对非 `@` 的首参数做精确玩家匹配，不做模糊名字匹配。

维护备注：

- 后续新增需要选单位的管理指令应直接复用 `resolveUnitSelection(...)` 与 `unitSelectorHelpText()`，不要再复制选择器实现。
- `unitSelector.lib.kt` 需要配套 `unitSelector.kts` 作为 ScriptAgent 可依赖脚本；调用方需声明 `@file:Depends("wayzer/cmds/unitSelector", "管理单位选择器")`，否则会在加载时报 `Unresolved reference: resolveUnitSelection/unitSelectorHelpText`。
- 文档主推 `@e[team=2,unit=mono]`，旧 `@t` / `@u` 仅作为兼容语法保留。

### 2026-07-04 技能分页、召唤装卸器与投票列表配色

类型：技能菜单扩展、投票帮助可读性优化
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/user/ext/skillsCommon.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
- `mdtserver/config/scripts/coreMindustry/menu.kts`
- `mdtserver/config/scripts/wayzer/vote.lib.kt`
- `docs/skill-system.md`
- `docs/help-menu.md`
- `docs/scripts-maintenance.md`

改动：

- 新增通用技能 `/skill summonunloader`（别名“召唤装卸器/装卸器/unloader”）：在玩家脚下空地放置 `duct-unloader`，0 MDC，300 秒冷却；成功后全服广播，并向使用者额外发送赛普罗装卸器梗提示。
- `/skill` 下通用、2级、3级、特殊/商店等技能分类页改为每页最多 8 个技能，保留返回主菜单与关闭按钮，避免技能继续增多后菜单过长。
- `/vote` 子指令列表在帮助菜单中按投票类型给指令名着色，并把用法行标为 `[yellow]`；`/vote kick` 等危险/处罚类入口使用 `[red]`，CP 入口使用 `[purple]`。

维护备注：

- 新增技能需要同时在 `skillsCommon.kts` 注册命令、在 `skills.kts` 注册/注销 `SkillMenuEntry`，并同步 `docs/skill-system.md`。
- 技能分页当前只改变菜单显示，不改变 `/skill <code>` 的直接调用方式或原有冷却/权限逻辑。

### 2026-07-04 账号权限二次校验与游客 MDC 注册迁移

类型：账号安全加固、MDC 数据归属修复
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/user/accountAuth.kts`
- `mdtserver/config/scripts/wayzer/user/accountGuestControl.kts`
- `mdtserver/config/scripts/wayzer/user/accountIpGuard.kts`
- `mdtserver/config/scripts/wayzer/user/trustPoint.kts`
- `mdtserver/config/scripts/wayzer/lib/MdtStorage.kt`
- `docs/account-system.md`
- `docs/trust-system.md`
- `docs/database-system.md`
- `docs/scripts-maintenance.md`

改动：

- 修复加载期编译问题：`accountAuth` 中 `hasPermission` 是挂起权限检查，账号管理二次校验包装函数必须声明为 `suspend`，否则会导致 `accountAuth` 及依赖的游客控制/安全风控/白名单加载失败。
- 复查账号相关管理入口：未登录会话仍会被信任等级系统剥离 UUID/历史主体/原生 `@admin` 权限组，已登录 4 级或已登录原生 admin 才会进入 `@admin`。
- `/setpassword`、`/deleteaccount`、`/accountqq`、`/guestob`、`/ipguard`、`/trustpoint` 在声明权限节点之外，指令体内也增加二次权限检查；控制台仍保留原有显式 `confirm` 用法。
- `MdtStorage` 新增 `migrateTrustPoints(fromUid, toUid)`：只合并 `MdtTrustProfiles` 中的当前/累计 MDC，不迁移手动信任等级或等级锁；来源只有 MDC 时删除来源行，存在其它信任元数据时仅清零来源 MDC。
- `/register` 成功创建并登录新账号后，会把注册前游客 UUID 已落库的当前/累计 MDC 合并到新账号主体，并向玩家提示保留数量；测试模式下不迁移正式 MDC。

维护备注：

- 当前迁移仅覆盖“注册新账号”流程，未对登录已有账号做自动合并，避免玩家通过未绑定 UUID 反复攒游客 MDC 后导入任意旧账号。
- 若后续要支持“登录已有账号时导入本设备游客 MDC”，需要额外做风控与一次性标记，防止刷取。

### 2026-07-03 外部 CP 加载兼容性与大文件慢同步

类型：外部 CP 热重载兼容性修复、错误信息增强
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/map/externalCpHotReload.kts`
- `docs/help-menu.md`
- `docs/scripts-maintenance.md`

改动：

- 外部 CP 文件扫描从仅 `.json` 扩展为 `.json` / `.hjson`，`/externalcp` 与 `/vote cp` 均可按编号、文件名或不带扩展名的显示名选择。
- 解析流程改为优先使用 `Jval.read(raw)` 原生 JSON/HJSON 读取，支持根对象无大括号、未加引号键、三引号多行文本等 HJSON 语法；失败后再回退旧的 ContentsTweaker 兼容预处理。
- 原 `2MB` 文件大小限制改为“慢同步阈值”：超过阈值不再拒绝加载，而是在菜单/投票/加载结果中提示“文件较大，将缓慢同步”，并使用更长的玩家分批世界数据同步间隔。新增 `hardExternalCpBytes` 作为真正硬上限，默认 `64MB`，防止误放超大文件。
- 加载失败现在会记录完整堆栈到日志，并把失败阶段、解析模式和安全截断后的错误信息返回给操作者/投票广播；避免只看到菜单关闭或“加载失败”却不知道原因。
- `/vote cp` 加载、热重载、卸载单个与卸载全部外部 CP 的投票门槛统一调整为 70% 同意。

维护备注：

- 大文件仍会在应用 CP 时占用主线程，慢同步只降低后续 `sendWorldData` 对在线玩家的网络/瞬时压力；如果后续出现 5MB+ CP 加载瞬间长卡顿，再考虑把“读取+Jval规范化”迁移到 IO 线程，回游戏线程只做 `state.patcher.apply`。
- `hardExternalCpBytes` 是安全兜底，不应作为常规限制；调低它会重新造成用户所说的“大 CP 直接拒绝加载”。

### 2026-07-01 `/maps` / 投票换图 NoSuchMethodError 排查

类型：Java 17/21 兼容修复、脚本缓存排查
涉及脚本：

- `mdtserver/config/scripts/coreMindustry/menu.new.kt`
- `mdtserver/config/scripts/wayzer/vote.lib.kt`
- `mdtserver/config/scripts/wayzer/cmds/voteMap.kts`

定位：

- `/maps` 崩在 `java.util.List.removeLast()`：这是 Java 21 才有的 `List` 方法；若脚本在 Java 21 环境编译、但服务端用 Java 17 运行，会出现 `NoSuchMethodError`。
- `/vote map` 崩在 `VoteEvent.<init>`：更像 `vote.lib.kt` 与 `voteMap.kts` 的编译缓存/依赖 ABI 不一致，常见于复制了旧 `config/scripts/cache` 或热更新后没有全量重编。

改动/处理：

- `menu.new.kt` 将 `menu.removeLast()` 改为 `menu.removeAt(menu.lastIndex)`，避免依赖 Java 21 `List.removeLast()`，兼容 Java 17。
- 若部署服继续出现 `VoteEvent.<init>` 的 `NoSuchMethodError`，应停止服务端后清理 `config/scripts/cache/compiled` 与 `config/scripts/cache/by_md5`，再完整重启，让 `vote.lib.kt` 和依赖脚本全量重编。

维护备注：

- 不建议把本机 `config/scripts/cache/` 一起复制到新服；该目录是编译缓存，不是运行数据。
- 服务端 Java 17 可以继续使用；若使用 Java 21 也可以，但所有脚本缓存必须由同一运行环境重新编译，避免 17/21 混用。

### 2026-07-01 H2 数据库 fsync/冷启动卡顿修复

类型：数据库连接器修复、新服卡顿诊断
涉及脚本/文档：

- `mdtserver/config/scripts/coreLibrary/DBConnector.kts`
- `docs/database-system.md`
- `docs/scripts-maintenance.md`

定位：

- 新服 watchdog 抓到主线程停顿时，`HeadlessApplication` 卡在 `FileChannelImpl.force` -> `org.h2.mvstore.FileStore.sync` -> `MVStore.compactFile/closeStore` -> `JdbcConnection.close`。
- 慢事务日志同时出现 `getSetting cost=32129ms thread=HeadlessApplication`、`getMuteReason cost=3382ms thread=HeadlessApplication`，后台 IO 线程也有 `setSetting/getReputation/playerOwnedTitles/playerOwnedSkillCodes/banStore.findNotEnd` 等 5-10 秒慢事务。
- 结论：主要是新 VPS 磁盘/H2 文件库 fsync、关闭/压缩、空闲后冷启动被数据库访问触发；不是地图单位逻辑本身。

改动：

- H2 默认连接 URL 改为 `jdbc:h2:H2DB_PATH;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`。
- 运行时自动为旧 H2 URL 补齐 `DB_CLOSE_DELAY=-1`、`DB_CLOSE_ON_EXIT=FALSE`，其它 JDBC URL 不改。
- `DB_CLOSE_DELAY=-1` 避免每个事务最后一个连接关闭时把 H2 整库关闭并触发同步/压缩。
- 新增 H2 启动预热与周期保活：默认每 5 分钟在 `Dispatchers.IO` 执行一次轻量 `SELECT 1`，降低部分 VPS 磁盘空闲一段时间后首次访问的冷启动卡顿；保活耗时超过 1 秒会输出警告。

维护备注：

- 该修复需要完整重启服务端后生效，不能只热加载普通业务脚本。
- 重启后继续观察 `[数据库] 严重慢事务`：若仍有 `thread=HeadlessApplication` 多秒慢事务，优先把对应业务调用迁移到 IO/缓存；若所有线程均多秒，优先排查 VPS 磁盘 IO 或考虑 PostgreSQL。

### 2026-07-01 逻辑绘图/单方块禁用与鱼鱼商店技能

类型：管理指令补充、商店技能新增
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/map/logicDrawGuard.kts`
- `mdtserver/config/scripts/wayzer/user/skillShop.kts`
- `mdtserver/config/scripts/coreMindustry/menu.kts`
- `docs/logic-draw-guard.md`
- `docs/skill-system.md`
- `docs/shop-system.md`
- `docs/help-menu.md`

改动：

- `/logicdraw` 增加 `roundoff`、`roundon`、`roundclear`：仅当前局覆盖逻辑绘图/画布/显示器方块策略，换图或 Reset 后恢复全局 `map.logicDraw.enabled`。
- 新增 `/blockban ban <方块ID>`、`/blockban unban <方块ID>`、`/blockban status <方块ID>`、`/blockunban <方块ID>`：管理员可在本局单独禁用/解禁某个建筑方块，并通过 `Call.setRules` 同步客户端。
- `/help` 管理分区加入“方块禁用/解禁”，逻辑绘图条目补充本局临时禁止说明。
- 技能商店新增商品 `17/fishonlyyou`：`此生只属鱼你`，购买 10 MDC、要求资历2级、使用0 MDC、不受 `@noSkills` 限制，默认仍按商店技能规则 PVP 禁用。释放后召唤一只专属 `risso` 鱼鱼，脚本记录所有者并拦截其他玩家附身；同一玩家同时只保留一只，鱼鱼会轻量跟随释放者。

维护备注：

- `/blockban` 属于本局高危规则修改，可临时覆盖地图原本的 `bannedBlocks`；换图/Reset 后本局手动记录会清空。
- 鱼鱼的“飞行”实现为对该单位保持 `elevation=1f` 并轻量位移跟随；`risso` 原始单位类型并未全局改为飞行，避免污染其它地图/单位。

### 2026-07-01 投票门槛显示与单位种类选择器

类型：投票系统信息显示、管理选择器补充
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/vote.lib.kt`
- `mdtserver/config/scripts/wayzer/cmds/vote.kts`
- `mdtserver/config/scripts/wayzer/cmds/voteKick.kts`
- `mdtserver/config/scripts/wayzer/cmds/voteOb.kts`
- `mdtserver/config/scripts/wayzer/map/externalCpHotReload.kts`
- `mdtserver/config/scripts/wayzer/cmds/killPlayer.kts`
- `mdtserver/config/scripts/wayzer/cmds/gatherTp.kts`
- `mdtserver/config/scripts/coreMindustry/menu.kts`
- `docs/help-menu.md`

改动：

- 投票库默认通过门槛从 60% 改为有效赞/反票 50%；投票广播和确认菜单新增“通过要求”说明，显示当前赞/反票所需赞成数、全员参与时估算所需赞成数，并明确中立票不计入赞/反。
- `/vote` 指令列表描述统一补充门槛标签：多数投票显示“需50%同意”，`/vote cp` 显示“需70%同意”；PVP 本队投降保留同队 80%，本队建筑记录清理保留同队 40% 并单独标注。
- `/vote cp load/unload/all` 的加载、热重载、卸载单个和卸载全部外部 CP 投票均改为 70% 同意，菜单与投票说明同步显示 70%。
- `/kill` 与 `/tp` 后续统一到共享选择器库；主推 `@e[team=队伍id,unit=单位ID]`，旧 `@t[队伍id]`、`@u[单位ID]`、`@t[队伍id,单位ID]` 作为兼容语法保留。
- `/tp <来源选择器> <目标选择器>` 仍按来源批量传送；目标选择器匹配多个单位时取第一个有效单位作为目标坐标，避免一次指令产生多目标歧义。

维护备注：

- 当前 50% 是 `ceil(有效赞反票 * 0.5)`，因此偶数赞/反票平票可通过；若后续要“必须超过半数”，需要改为 `floor(n / 2) + 1` 并同步文档。
- `/vote cp` 的 70% 仍允许单人局 `supportSingle` 快速通过，和其它支持单人快速投票的入口保持一致。

### 2026-07-01 启动脚本 script-load 日志修复

类型：启动脚本日志修复
涉及文件：

- `mdtserver/start-server.ps1`
- `mdtserver/server.properties`

改动：

- 修复 `config/logs/script-load/` 不再生成 `current.log`、`last.log`、`last-error.log` 的问题。
- 直接原因：`config/logs/script-load/java-logging.properties` 中的 `java.util.logging.FileHandler.pattern` 被写成空值/漏写，导致 Java `FileHandler` 没有明确输出目标，ScriptAgent 早期脚本加载日志无法落到 `script-load/current.log`。
- 深层原因：Windows PowerShell 5.1 在读取 UTF-8 无 BOM 的 `.ps1` 时会按本地 ANSI 解码，函数内中文注释在极端情况下会变成带续行/控制含义的乱码，进而影响后续配置写入。该函数已改为 ASCII-only 注释，脚本文件已保存为 UTF-8 BOM，并改用 here-string 固定写出 `java.util.logging.FileHandler.pattern=config/logs/script-load/current.log`。
- 排查过程中曾触发一次 `-DryRun` 写配置，已按原有启动输出恢复 `server.properties` 的关键配置（`jar`、`javaOptions`、服务器名、端口、MOTD、人数、shuffle 等）。

验证：

- 已执行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File mdtserver/start-server.ps1 -DryRun`。
- `config/logs/script-load/java-logging.properties` 已重新包含：
  - `java.util.logging.FileHandler.pattern=config/logs/script-load/current.log`

维护备注：

- 当前正在运行的服务端不会重新读取这个 Java logging 配置；需要下次通过 `start-server.ps1`/`start-server.cmd` 正常重启后，`script-load/current.log` 才会重新生成。
- 后续修改 Windows 启动脚本时，关键执行路径附近尽量避免中文注释，或确保 `.ps1` 以 UTF-8 BOM 保存。

### 2026-06-30 `[危险]服务器测试模式`

类型：特殊测试服临时覆盖层、账号/MDC/资历隔离
涉及脚本：

- `mdtserver/config/scripts/wayzer/lib/ServerTestMode.kt`
- `mdtserver/config/scripts/wayzer/user/serverTestMode.kts`
- `mdtserver/config/scripts/wayzer/user/accountAuth.kts`
- `mdtserver/config/scripts/wayzer/user/trustLevel.kts`
- `mdtserver/config/scripts/wayzer/user/seniorityLevel.kts`
- `mdtserver/config/scripts/wayzer/user/trustPoint.kts`
- `mdtserver/config/scripts/wayzer/user/trustPromotion.kts`
- `mdtserver/config/scripts/wayzer/user/gameContributionReward.kts`
- `mdtserver/config/scripts/coreMindustry/menu.kts`

改动：

- 新增独立脚本 `/servertestmode`（别名 `/testmode`、`/测试模式`），权限 `wayzer.admin.serverTestMode`，默认 `@admin`，管理帮助菜单新增 `[red][危险]服务器测试模式`。
- 启用状态写入数据库 `MdtSettings.serverTestMode.enabled`；脚本热加载/服务端重启后会按数据库状态恢复，显式关闭才会写入 `false` 并清理临时数据。
- 启用后只把已登录账号玩家切到 `test:<account:id>` 临时主体；未登录游客仍保持 UUID 游客态，不能获得测试 MDC，也不会进入信任1/资历3。
- 账号登录/自动登录/注册/验证码在测试模式下仍可用；登录成功后立即切入临时测试主体。改密、注销等破坏性账号操作暂时关闭，避免测试期误操作正式账号。
- 正式账号本身为信任4级或原生 admin 的玩家在测试模式下仍视为4级；其他已登录测试玩家临时视为信任 `1`、资历 `3`。
- `trustPoint.kts` 对 `test:<account:id>` MDC 读写改走 `ServerTestMode` 临时服务；测试模式开启时非 test 主体 MDC 返回0且不写正式 MDC 表。当前/累计 MDC 保存在 `config/scripts/.server-test-mode/server-test-mode.tmp.properties`，关闭测试模式时删除。
- 本局贡献结算在测试模式下额外 `×10`，但只奖励已切入 test 主体的登录玩家；未登录游客不会获得结算 MDC。提示中会显示“测试模式×10”。
- 信任/资历自动晋升检测跳过测试 UID，避免写入正式信任/资历表。

维护备注：

- 该模式设计用于测试服/特殊时期，不建议在正式服长期启用。
- `ServerTestMode.kt` 是跨脚本轻量服务接口；新增会读取登录态、MDC、资历或结算奖励的脚本时，应判断是否需要尊重该服务。
- 临时文件不是正式数据库迁移方案，只用于模式开启期间的临时 MDC/在线时长恢复/排查；测试模式开关本身落在数据库，关闭后临时文件应被删除。

### 2026-06-30 投票/管理指令语义复查

类型：需求语义复查、偏差修正与文档同步
涉及脚本：

- `mdtserver/config/scripts/wayzer/cmds/killPlayer.kts`
- `mdtserver/config/scripts/wayzer/cmds/gatherTp.kts`
- `mdtserver/config/scripts/wayzer/ext/playerInfoTripleTap.kts`
- `mdtserver/config/scripts/wayzer/ext/playerMute.kts`
- `mdtserver/config/scripts/wayzer/ext/playerBuildBan.kts`
- `mdtserver/config/scripts/wayzer/ext/playerReputation.kts`
- `mdtserver/config/scripts/wayzer/lib/MdtStorage.kt`
- `mdtserver/config/scripts/wayzer/user/trustPromotion.kts`
- `mdtserver/config/scripts/wayzer/vote.lib.kt`
- `mdtserver/config/scripts/wayzer/cmds/vote.kts`
- `mdtserver/config/scripts/wayzer/cmds/voteOb.kts`
- `mdtserver/config/scripts/wayzer/map/externalCpHotReload.kts`
- `mdtserver/config/scripts/wayzer/map/worldProcessorAdmin.kts`
- `mdtserver/config/scripts/coreMindustry/menu.kts`

本批复查结论/改动：

- `/kill` 已从“只击杀玩家当前单位”扩展为支持目标解析与选择器：玩家 UUID/主体ID/三位短ID/`#游戏ID`/名字，以及 `@e` 全部单位、`@a` 所有玩家附身单位、`@s` 指令执行者单位；当前主推 `@e[team=2,unit=mono]` 这类标准过滤写法，旧 `@t[队伍id]`、`@u[单位ID]`、`@t[队伍id,单位ID]` 仅作兼容。
- `/tp` 已支持：无参数传送自己到鼠标、`/tp <玩家UUID/短ID/名字>` 传送自己到该玩家、`/tp <玩家1|选择器> <玩家2|选择器>` 批量传送。选择器与 `/kill` 一致并支持单位种类筛选；第二个参数为选择器时取该选择器的第一个有效单位作为目标点。
- 玩家信息面板中的“禁言/禁建”现在先询问时长：输入正整数分钟为临时限制，留空为永久，取消则中断；临时禁言/禁建只保存在内存，永久禁言走数据库，永久禁建走脚本 Savable 状态。
- 新增 `playerBuildBan.kts`：拦截 `placeBlock`/`breakBlock`，禁止目标建造与拆除；4级可处理普通目标，3+级可处理低于3+的目标。`/help` 管理分区补入禁建/解禁入口。
- 赞/踩每日额度检查与写入继续走 `recordReputationVoteChecked` 单事务；本轮额外修复历史重复 `MdtReputationDaily` 行导致单目标上限“随机/偏低”的风险：检查时按所有重复行求和，成功写入后合并到第一行并删除重复行。旧兼容函数也同步改为求和/合并。
- 当前点赞额度：1级 `每目标3/日、总10/日`，2级 `每目标5/日、总25/日`，3/3+级 `每目标8/日、总50/日`，4级不限；点踩额度：1级 `每目标2/日、总5/日`，2级 `每目标3/日、总8/日`，3/3+级 `每目标5/日、总12/日`，4级不限。
- 有效被踩公式已调整为最近行为更敏感：`max(0, 总被踩 + 最近7天被踩*2 - 被认可*2 - 总被赞/20 - 最近7天被赞/3)`；`MdtReputationDaily` 默认保留最近14天用于短期统计。
- `/vote cp` 已支持加载、热重载、卸载单个外部 CP 与卸载全部外部 CP；加载/卸载投票均要求 70% 同意，并在投票菜单中按“未加载=加载、已加载=卸载”显示。
- `/worldprocessorquiet`（`/wpq`）提供静默开启/关闭世界处理器与世界处理器编辑权限的入口；只回复操作者/控制台并写日志，不做全局播报。
- 投票库默认投票资格不再依赖玩家当前单位是否死亡，避免换图/单位死亡导致已投票数丢失；新增全局投票拒绝谓词，`voteOb.kts` 注册“被投票/管理强制观战者不能参与普通投票”，但 `/vote quitOb` 通过 `bypassDenyVote` 允许本人为解除观战发起/参与自己的投票。
- `/vote pauseWave`、`/vote setWave`、`/vote resumeWave` 分别用于暂停波次计时、调整当前波次和取消暂停波次；PVP 模式继续禁止这些波次控制投票，避免和 PVP 本队投降逻辑混淆。

维护备注：

- `@e`、`@e[team=2]` 与单位种类批量选择器（如 `@e[unit=mono]`、`@e[team=1,unit=mono]`）属于批量高危选择器，当前仅通过管理员权限入口暴露；后续若开放给非管理员，必须增加二次确认。
- 投票稳定性本轮只取消“单位死亡/换图 dead 状态”对票数的影响；玩家真正离线仍会移除其票。
- 目前 `VoteEvent.canVote` 默认为 `true`，具体团队投票（如 PVP 投降、清理本队建筑记录）仍由调用方传入 `canVote = { it.team() == team }` 控制。

### 2026-06-30 性能优化压力措施补强

类型：性能保护策略修复与文档口径同步
涉及脚本：

- `mdtserver/config/scripts/wayzer/map/serverPressureActions.kts`
- `docs/performance-guard.md`
- `docs/hybrid-unit-catalog.md`

本批改动：

- 按 `docs/hybrid-unit-catalog.md` 的新单位表复核压力清理口径，保留 `mono`、`pulsar`、`quasar`、`poly`、`mega` 作为低阶辅助/可挖矿单位，避免疑似 PPS 顶满时误清关键辅助。
- 新增实验性疑似 PPS 顶满检测：上行达到预算 60% 以上时，若短时间内多人异常/连续退出，自动清理除保留辅助外的 T1-T3 单位，并额外清理 `quell`、`disrupt`、`anthicus` 与场上 `scathe` 炮台；标准模式不触发。
- 新增实验性严重上行超量兜底：估算上行超过预算 200% 时，清理 T4 及以下单位并提示“上行需求量严重超量（>200%），已清理t5以下所有单位”；标准模式不触发。
- 新增实验性等级4（TPS 与上行同时超限）额外兜底：统计当前非玩家普通单位数量并击杀数量前三的单位种类；标准模式不触发。
- 修复性能优化系统处于压力等级时被手动关闭后仍继续执行措施的问题：`serverPressureActions` 每轮先读取实时 `performanceMode()`，关闭时立即停止并回退。
- 修复性能压力自动暂停/暂停出波等规则在手动退出压力等级或关闭性能优化后未完整回退的问题；自动暂停只回退系统自身触发的暂停，不覆盖管理员手动暂停。

维护备注：

- Mindustry `PlayerLeave` 事件没有可靠暴露 timeout/closed 原因，因此“异常超时”策略按 2 秒窗口内多人退出/连续退出启发式判定，并配独立冷却避免重复触发。
- 新增清理策略仍遵守非玩家控制、非核心出生、非限时/挂载单位的安全过滤；单位销毁继续走 `kill()`，避免幽灵单位/不同步。

### 2026-06-30 文档追溯补齐与诊断记录

类型：文档维护追溯、性能诊断补充
涉及脚本/文档：

- `mdtserver/config/scripts/wayzer/ext/mainThreadWatchdog.kts`
- `mdtserver/config/scripts/wayzer/reGrief/syncThrottle.kts`
- `mdtserver/config/scripts/wayzer/user/accountIpGuard.kts`
- `mdtserver/config/scripts/wayzer/user/accountGuestControl.kts`
- `mdtserver/config/scripts/mapScript/14668.kts`
- `mdtserver/config/scripts/mapScript/15463.kts`
- `docs/skill-system.md`
- `docs/performance-guard.md`
- `docs/account-system.md`
- `docs/security-guard.md`
- `docs/map-scripts.md`

本批补齐：

- 追溯当前工作区 diff 与最近提交后，补齐 3级技能 `物资补给`、纯净模式额外禁用 3级技能、理财类商店技能不受 PVP/noskill 限制等技能文档。
- 记录 `mainThreadWatchdog.kts` 主线程卡顿诊断用途、`/tickwatchdog status` 与 `syncThrottleEnabled`/快照耗时告警配置，便于继续定位“聊天正常但世界卡住后回弹”。
- 补齐同 IP 小号提示的延迟检查、去重、连接阶段轻量记录，以及今日游客观战状态内存缓存/异步持久化的说明。
- 补齐地图脚本 `14668`、`15463` 的 `PlayerLeave` 离线等待循环降频记录。

维护备注：

- 本次能追溯的是当前对话上下文、git 历史和当前工作区 diff；若存在未提交且已被覆盖的早期自然语言意图，无法完全恢复，只能以代码现状与日志为准。
- 后续提交前建议固定执行 `git diff --name-status HEAD`，若有脚本功能变更但没有对应 docs 变更，需要主动补文档或在临时任务文档说明“不需文档”的理由。

### 2026-06-26 技能系统拆分整理

类型：结构整理与维护性优化
涉及脚本：

- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skillsCommon.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skillsLevel2.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skillsLevel3.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skillsHybrid.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skillsGodAdmin.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skills.lib.kt`
- `mdtserver/config/scripts/wayzer/user/achievement.kts`

本批改动：

- 将原本堆在 `skills.kts` 中的技能实现按职责拆分为通用、2级、3级、杂交、神权/管理员五个模块脚本；`skills.kts` 仅保留核心菜单、共享工具、菜单条目注册与全局状态清理。
- `skills.lib.kt` 新增 `SkillMainMenuRegistry`，让杂交菜单、神权菜单由各自模块挂入 `/skill` 主菜单，减少核心入口硬编码。
- 拆分模块通过 `contextScript<Skills>()` 显式访问核心脚本共享工具，避免 ScriptAgent 依赖脚本的 top-level 声明不可直接解析。
- 服务端启动验证通过：`共找到193脚本,加载成功189,启用成功136,出错0`。
- 顺带修复 `achievement.kts` 自定义成就称号奖励在 Kotlin 2.1 下 nullable smart cast 编译失败的问题。

### 2026-06-11 小任务批次

类型：小功能补齐与显示文案调整
涉及脚本：

- `mdtserver/config/scripts/wayzer/user/forumPosts.kts`
- `mdtserver/config/scripts/wayzer/user/adminChat.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
- `mdtserver/config/scripts/wayzer/user/ext/skills.lib.kt`
- `mdtserver/config/scripts/wayzer/ext/playerRandomForm.kts`
- `mdtserver/config/scripts/wayzer/map/funRuleModes.kts`
- `mdtserver/config/scripts/wayzer/cmds/voteFunRules.kts`
- `mdtserver/config/scripts/wayzer/user/skillShop.kts`
- `mdtserver/config/scripts/wayzer/user/accountIpGuard.kts`
- `mdtserver/config/scripts/wayzer/map/betterTeam.kts`
- `mdtserver/config/scripts/wayzer/maps.manager.kt`
- `mdtserver/config/scripts/wayzer/maps.kts`
- `mdtserver/config/scripts/wayzer/cmds/voteMap.kts`
- `mdtserver/config/scripts/wayzer/cmds/setBlock.kts`
- `mdtserver/config/scripts/wayzer/cmds/killPlayer.kts`
- `mdtserver/config/scripts/wayzer/ext/serverDescription.kts`
- `mdtserver/config/scripts/coreMindustry/menu.kts`
- `mdtserver/config/scripts/coreLibrary/lib/CommandApi.kt`
- `mdtserver/config/scripts/wayzer/vote.lib.kt`
- `mdtserver/config/scripts/wayzer/vote.kts`
- `mdtserver/config/scripts/wayzer/cmds/mapsCmd.kts`
- `mdtserver/config/scripts/mapScript/module.kts`
- `mdtserver/config/scripts/wayzer/map/worldProcessorAdmin.kts`
- `mdtserver/config/scripts/wayzer/map/adaptivePlayerLimit.kts`
- `mdtserver/config/scripts/wayzer/security/securityGuard.kts`
- `mdtserver/config/scripts/wayzer/ext/playerInfoTripleTap.kts`

本批改动：

- 帖子详情页新增“分享到聊天”；`/posts share <帖子ID>` 会向聊天栏发送帖子标题、分区与 `/posts <帖子ID>` 快捷打开提示；等级分区帖子只发送给有权限查看该分区的玩家，避免标题泄漏。
- 新增管理员频道脚本 `wayzer/user/adminChat.kts`，提供 `/achat`、`/ac`、`/adminchat`，仅4级/admin可发送和接收。
- 管理员技能新增 `/skill addnoskill`，用于给当前地图添加 `@noSkills` 标签；原 `/skill removenoskill` 保留。
- 技能系统新增一批技能：通用技能“灭火/缴械/初级预制防线”，2级技能“坠机/你跑不过我你信不信”，3级技能“消防车/标准预制防线/导弹齐射/核弹打击/刷新技能/贴贴”，管理员技能“考试时间！”；`SkillCooldown` 支持按玩家清除冷却以供“刷新技能”使用。灭火/消防车已改为直接灭火兜底 + 从玩家中心向外散射原版海啸水子弹；范围治愈会治疗周围受伤友方单位与建筑；空对地导弹优先附身 `scathe-missile-phase`；预制防线释放前分别要求 4x4/6x6 空地，范围内已有方块则拒绝释放，并按“炮台→供电/电池→墙体”分阶段放置。
- 技能菜单通用/2级/3级/特殊商店分区参考技能商店按钮风格优化：技能名、效果、规则明确分行，并移除按钮内指令行以保证美观；通用/2级/3级分别使用 `[cyan]`、`[green]`、`[purple]` 标识，商店技能使用 `[cyan]`，规则行使用 `[gold]`。
- 技能数值调整：2级技能与商店技能使用 MDC 消耗翻倍（消耗0、抽奖、终极随机除外）；抽奖改为使用5 MDC且一局一次，终极随机保持66 MDC/20秒。
- 投票弹窗改为按“投票信息 / 当前票况 / 投票说明”分区显示，赞成/中立/反对/待定按钮使用有效颜色与说明；文字投票广播也明确列出 `赞成(y/1)`、`中立(.)`、`反对(n/0)`。
- 积分板新增 `scoreboard.ext.team-units` 扩展行，显示 `我方总单位数: {player.team.units}`；全局变量为 `Team` 增加 `units` 子变量，返回当前队伍单位数。
- 注册验证码改为仅用于注册：获取 `/captcha` 时要求本次服务端启动以来该 UUID 累计在线满1小时，不足时显示还需在线多久；登录 `/login` 不再需要验证码，并给登录失败增加 UUID+IP 双维度临时冷却，且不会因断开重连被立即清空。
- 同步限制优化：受限同步不再每次都对同步实体按类名排序；发送实体快照前补调用 `beforeWrite()`；隐藏实体 `hiddenSnapshot` 增加按玩家缓存与最小刷新间隔，避免同步限制开启后重复发送完全相同的隐藏列表；兼容旧入口的单位清理改用 `kill()` 触发原版销毁同步链路，减少幽灵单位。
- 随机形态脚本导出强制设置指定形态能力；终极随机可将全员强制切换为猫娘形态，玩家后续仍可通过 `/randomform` 恢复。
- 商店技能“终极随机”改为使用66 MDC、冷却20秒，并新增立即跳波10波、全员猫娘、当前地图添加 `@noSkills`、扣除使用者100MDC（不足清零）、“砸哇路多”全单位静止15秒等随机项；后续移除终极随机中的编辑器模式/无限火力promax随机项，改为“涩图禁令”（逻辑显示单元替换为太阳能板）、“开放科技”（开放全部星球科技/建造限制）与“反客为主”（使用者转投场上其它有效队伍）。
- “无限火力”显示名调整为“无限火力promax”，“沙盒模式”显示名调整为“编辑器模式”；同步调整投票和技能菜单文案。
- `/skill` 主菜单新增顶层“神权菜单”入口，与通用/2级/3级/管理员技能并列；仅信任4级/已登录原生admin可用，支持调整当前地图全局规则倍率、指定队伍/其它队伍 TeamRule 倍率（可手动输入 0-255 队伍ID），开放当前星球或全部星球科技限制，并长期开关编辑器模式与无限火力promax。
- 同 IP 小号提示广播改为同时显示上次同 IP 身份的名字、UUID 与短ID；账号认证后也会重新检查同IP身份切换，但同一游戏UUID的正常登录态切换不会误报。
- `/team` 队伍管理移除 PVP 与自换权限限制，所有玩家均可在任意模式切换自己的队伍；指定他人换队仍保留管理员权限检查；命令菜单归入管理分区，手动切队不再受地图 `@banTeam` 限制。
- `trustPoint.kts` 的红包结算会广播领奖者列表与手气王排行；未登录游客不能抢红包；单个红包总额上限 500 MDC。
- `/vote nextmap <地图ID>`（别名 `/vote endmap`、`/vote aftermap`、`/vote 下局换图`、`/vote 结束后换图`）调整为“下次自动轮换地图”投票；投票通过后只记录目标地图，期间即使被手动/其他机制换图也不会清空，直到下一次自动轮换地图时覆盖自动随机下一图。
- 新增 `/setBlock <方块ID> [队伍ID/队伍名]`（别名 `/setblock`、`/settile`、`/设置方块`），管理员可将脚下建筑层设置为指定方块并可指定方块队伍；队伍留空默认使用玩家当前队伍，例如 `power-node`、`duo crux`。
- 新增 `/setFloor <地板ID>`（别名 `/setfloor`、`/设置地板`），管理员可单独设置脚下地板层，例如 `sand`，避免把地板误放到建筑层导致该位置无法建造。
- 新增 `/fill <block|floor> <x1> <y1> <x2> <y2> <目标方块/地形> [-cover|-keep]`（别名 `/填充`），管理员可批量填充建筑层或地板层；建筑层支持 `air` 清理和多格建筑分格铺设，默认 `-cover`，`-keep` 会保留已有建筑，单次区域格数受 `fillMaxTiles` 配置限制。
- 新增 `/kill <玩家UUID/三位UID/#游戏ID/名字|选择器>`，管理员可击杀指定在线玩家当前附身单位或选择器匹配的批量单位；当前选择器主推 `@e[team=2,unit=mono]`，`/tp` 同步支持选择器传送与单位种类筛选。
- 新增 `/effect [选择器|玩家UUID] <效果ID> [叠加数] [秒数]`（别名 `/效果`、`/buff`），管理员可对选择器或指定玩家当前单位添加可叠加状态效果；无选择器时默认 `@s`，默认持续 120 秒，叠加实现与“你跑不过我你信不信”/杂交 buff 一致，直接追加 `StatusEntry`；后续扩展 `clear`，支持 `/effect [目标] clear [效果ID]` 清除指定或全部状态效果。
- 新增服务器列表介绍轮播 `wayzer/ext/serverDescription.kts`，参考 Mindustry `Config.desc` / `NetworkIO.writeServerData()` 实现；`/descadmin` 可管理多条介绍、手动切换、锁定/解除锁定某条介绍、开启/关闭轮播。
- 投票系统允许游客发起投票；失败后游客使用更长冷却（默认30分钟），并将失败投票发起冷却同时写入玩家 UUID 与 IP，修复 `/maps` 菜单和 `/votekick` 绕过发起冷却的问题。
- `CommandApi` 记录所有已注册指令（含重复别名/被覆盖指令），供地图脚本冲突入口使用；当前地图脚本提供的同名/别名指令统一通过 `/mapcmd <地图脚本指令>` 执行，例如 `/mapcmd shop`，避免与 `/maps` 地图页码/筛选/换图入口冲突。
- 地图脚本加载后会扫描面向玩家的地图特定指令，并提示 `可使用/mapcmd xxx来打开地图特定指令!`。
- 新增 `/cp`、`/worldprocessor <status|on|off|edit on|edit off|cp>`：管理员可列出当前已加载 CP/数据包；游戏内 `/cp` 会打开 CP 管理菜单，可查看详情、临时卸载或禁用并卸载 CP；也可开启/关闭世界处理器与切换 `allowEditWorldProcessors`。注意该编辑开关是原版全局规则，开启后允许所有玩家编辑世界处理器，不是仅管理员编辑。
- 新增 `/banip <在线玩家3位ID/#游戏ID/名字> [分钟] [原因]`，根据在线玩家当前 IP 建立安全风控 IP 封禁。
- 新增 `/banips` 菜单与 `/unbanip <ip>`；IP封禁记录会保存封禁目标 UUID/玩家名，列表菜单可查看当前被封 IP、对应 UUID/玩家名、剩余时间与原因，并可点开解除。
- 玩家信息面板中 4级玩家查看他人时新增 `ban掉ta` 与 `ban掉ta的ip`，均会要求输入时长与理由。
- 新增 `/recentplayers` 最近80名玩家面板；离线玩家面板只按需读取资料，不加入在线资料缓存，可用于事后封禁账号或最近 IP，避免造成玩家资料缓存泄漏。
- `/help` 管理指令补充管理员频道、服务器介绍轮播、神权菜单、设置脚下方块/地板、名字后缀标记、banip、最近玩家、CP列表、世界处理器入口、人数上限，并更新队伍管理、无限火力promax文案。

备注：

- `addNoSkillsTag` 与 `removeNoSkillsTag` 都只修改服务端 `rules.tags`，不热同步完整 Rules，避免无必要的客户端断连风险。
- `/team` 手动切队入口不再遵守地图 `@banTeam` 禁用队伍限制；`@banTeam` 仍用于自动分队/换图后的默认分配。


### 2026-06-13 MindustryX headless 运行崩溃热修

类型：服务端 JAR 二进制最小热修 / 上游补丁记录
涉及文件：

- `mdtserver/server-2026.05.X35.jar`
- `mdtserver/server-2026.05.X35.jar.bak-headless-20260613-020640`
- `参考项目/MindustryX-2026.05.X35/patches/client/0046-UI-ARC-logic-Support.patch`

问题：

- 运行时崩溃栈为 `LogicBlock$LogicBuild.interactable -> RenderExt.<clinit> -> UIExt.<clinit> -> ModsRecommendDialog.<clinit>`。
- `LogicBlock$LogicBuild.interactable` 在 headless 服务端路径中无条件读取 `RenderExt.showOtherInfo`，导致 MindustryX 客户端渲染/UI扩展类被初始化。
- `UIExt`/`ModsRecommendDialog` 依赖客户端 UI 资源，headless 服务端中 `Tex.nomap` 为 `null`，最终触发 `ExceptionInInitializerError` / `NullPointerException` 并退出。
- 该问题不一定在启动时触发，通常要等逻辑块/卸载器等建筑交互或邻近更新路径调用 `interactable` 时才会运行时崩溃。

处理：

- 当前服务端已切换为 `server-2026.05.X35.jar`，`server.properties` 已指向该 JAR。
- X35 编译产物仍存在同一处 headless 防护遗漏；已对当前配置使用的 `server-2026.05.X35.jar` 做最小字节码热修：将 `LogicBlock$LogicBuild.interactable` 中读取 `RenderExt.showOtherInfo` 的 `getstatic` 替换为常量 `false`，等价于服务端只使用原版 `super.interactable(team)`，避免加载客户端 `RenderExt/UIExt`。
- 保留原 X35 JAR 备份：`server-2026.05.X35.jar.bak-headless-20260613-020640`。
- 同步修正本地 X35 参考补丁：该处逻辑应为 `super.interactable(team) || (!headless && RenderExt.showOtherInfo)`，避免未来重新构建时复现。

维护建议：

- 后续更换 MindustryX 服务端 JAR 后，如再次出现 `RenderExt should not access in Headless`、`UIExt should not be initialized in headless mode` 或 `Tex.nomap is null`，优先检查是否有客户端渲染/UI扩展在 headless 路径中缺少 `!headless` 防护。
- 如果使用上游新版 JAR，不能只看启动是否报错；建议用 `javap` 检查 `LogicBlock$LogicBuild.interactable` 是否仍无条件读取 `RenderExt.showOtherInfo`。

---
### 性能/懒加载维护约定

- 常用菜单、长文本菜单、大列表菜单不要在 `Dispatchers.game` 中一次性查询/构建全部内容。
- Wiki、帖子、完整帮助列表这类会随项目膨胀的内容，应分页读取、只构建当前页按钮文本。
- 数据库查询、自动清理、长文本摘要/排序等纯数据工作优先放入 `Dispatchers.IO`；Mindustry 世界对象、玩家对象和 `Call` 调用仍优先留在游戏线程。
- 如果新增系统需要列表页，优先在 `MdtStorage` 提供分页/轻量摘要接口，避免在脚本层 `listAll().drop().take()`。
- 如果打开菜单导致全服顿挫，优先检查：同步数据库读写、全量遍历玩家/单位/帖子/命令、长文本一次性塞进按钮、频繁刷新菜单。

### `mdtserver/start-server.ps1` / `mdtserver/start-server.sh`

类型：修改服务端启动脚本
职责：读取 `server.properties`、生成 Mindustry 启动命令、启动 Windows/Linux 服务端。

本项目改动：

- 添加日志轮转，避免 Mindustry 的 `config/logs/log-0.txt` 跨多次启动持续追加。
- 启动前如果发现遗留 `log-0.txt`，会复制到 `config/logs/last.log`，并移动到 `config/logs/history/YYYY-MM-DD/previous-时间.log`。
- 服务端正常退出后，会把本次 `log-0.txt` 复制到 `config/logs/last.log`，并移动到 `config/logs/history/YYYY-MM-DD/startup-时间.log`。
- 额外通过 Java `java.util.logging.FileHandler` 保存 ScriptAgent 脚本加载日志到 `config/logs/script-load/current.log`，用于保留脚本加载、依赖解析等不会进入 `log-0.txt` 的早期 Java 日志；不再用 PowerShell 事件或 Bash `tee` 拦截/转发 Java stdout/stderr，避免影响日志页显示速度、控制台输入和换行。
- 每次运行结束后会把脚本加载日志复制到 `config/logs/script-load/last.log`，并归档为 `config/logs/script-load/history/YYYY-MM-DD/script-load-startup-时间.log` 或 `script-load-restart-时间.log`。
- 从脚本加载日志中过滤 `ERROR` / `SEVERE` / `WARNING` / `[E]` / `[W]`、中文“错误/严重/警告”、脚本“加载失败/无法启用/编译失败”、异常与 Kotlin `Unresolved reference` 等内容，写入 `config/logs/script-load/last-error.log`；存在内容时同步归档为 `script-error-startup-时间.log` / `script-error-restart-时间.log`。
- `/restart` 会以退出码 `2` 关闭当前 Java 进程；Windows/Linux 启动脚本现在都会识别退出码 `2` 并在 2 秒后重新拉起服务端，因此“计划重启”不再只是关服。
- 重启循环中的第二次及后续运行日志会使用 `restart-时间.log` 归档，便于和普通启动区分。
- 服务端运行期间，`config/logs/log-0.txt` 仍是当前运行中的 Mindustry 实时日志，`config/logs/script-load/current.log` 是当前脚本加载日志；如果进程被强杀，下一次启动会归档遗留的 `log-0.txt` / `script-load/current.log`。
- 2026-05-28 修正：上一版顶层 `console-0.log` / `last-console.log` / `last-error.log` 容易被外部日志页扫描并且 stdout/stderr 转发会造成显示变慢、换行异常；现在改为 `script-load/` 子目录的 Java 日志文件，并在下次启动时把旧顶层文件移动到 `config/logs/script-load/legacy-top-level/`。
- 2026-05-28 修正：Java `FileHandler.pattern` 改为 `config/logs/script-load/current.log` 纯 ASCII 相对路径；`java.util.logging` 读取 `.properties` 时会把未转义中文绝对路径解码成乱码，导致包含中文目录名时 `current.log.lck` 创建失败。
- `-DryRun` / `--dry-run` 只打印启动参数，不会触发日志轮转。

备注：

- `last.log` 是最近一次 Mindustry 日志快照；脚本加载日志的 `last.log` / `last-error.log` 位于 `config/logs/script-load/`，避免和日志页读取的主日志混在一起。
- Windows 和 Linux 启动脚本都实现了相同的日志目录约定。
- 如果不用这些启动脚本，而是直接执行 `java -jar ...`，则 `/restart` 仍只能让 Java 进程以退出码 `2` 退出，外部进程管理器必须自行按退出码重启。

---

### `mdtserver/config/scripts/coreMindustry/menu.kts`

类型：修改核心菜单/帮助脚本
职责：Mindustry 菜单事件桥接、通用 `sendMenuBuilder`，以及 `/help` 客户端菜单覆盖。

本项目改动：

- 将顶层 `/help` 从“完整指令平铺列表”改为分区菜单。
- 当前分区/入口：玩家指令、投票指令、帖子列表、商店列表、技能指令、管理指令、其他指令、Wiki列表、完整指令列表。
- “帖子列表”已作为 `/help` 根菜单直接入口，同时也保留在玩家指令分区，可快速打开 `/posts`。
- 玩家指令分区已加入 `/msg`，用于私聊在线玩家；`/r` 可回复最近私聊对象。
- 玩家指令分区已加入 `/tips`，管理指令分区已加入 `/tipadmin`。
- 管理指令分区已加入 `/ipguard` 与 `/ipregion`。
- 管理指令分区已加入 `/security`，用于查看/管理安全风控。
- 管理指令分区已加入 `/posts`/`/wikiadmin` 的回收站与保护锁菜单入口；保护/解除操作在帖子详情页、Wiki页面/管理页中通过独立按钮完成，避免无ID快捷按钮只回显用法。
- `/help <页码>`、`/help -v` 和非顶层帮助（例如 `/vote` 的子指令帮助）仍走传统完整列表，避免破坏原有查询方式。
- `/helps` 已作为 `/help` 的兼容别名。
- 顶层 `/help` 打开时不再立即扫描全部可见指令，避免一次性触发大量权限检查/数据库读取导致全服卡顿；“其他指令”和“完整指令列表”改为点击后再懒加载。
- 完整列表/其他指令加载时只构建当前页按钮文本；仍需统计总页数时会扫描命令元信息，但不会一次性生成全部菜单项。
- 完整列表/其他指令加载时会缓存本次页面构建中的权限判断结果，减少重复权限事件开销。
- 管理指令分区按固定配置和管理权限入口显示；未归类指令点击“其他指令”时再动态收集。
- 2026-05-28 优化：固定分区条目标题前显示快捷指令，例如 `/account,账号系统`；完整指令列表和“其他指令”只显示“`/cmd（/alias）` + `/cmd <usage>`”，不再显示完整长描述，避免按钮过宽和菜单阅读困难。
- 2026-06-12 优化：玩家指令/管理指令固定分区按功能重要性给标题加颜色标签：核心/安全用 `[cyan]`，内容展示用 `[pink]`，地图/服务器运维与状态用 `[green]`，等级/资料/经济用 `[yellow]`，处罚/危险操作用 `[red]`，底层低频工具用 `[light_gray]`。
- 2026-05-28 补齐：投票分区加入 `/vote gameOver`、`/vote rollback`、`/vote skipWave`、`/vote clear`、`/vote text`、`/vote reactor`；管理分区加入 `/skill admin` 管理员技能入口。

备注：

- 分区维护方式见 `docs/help-menu.md`。
- 后续新增玩家常用/管理常用指令时，建议同步加入 `playerHelpEntries`、`voteHelpEntries` 或 `adminHelpEntries`，避免功能长期堆在“其他指令”。
- 如果再次出现打开帮助菜单导致全服停顿，优先检查是否有新的顶层菜单在 `Dispatchers.game` 中做全量权限扫描、同步数据库查询或大文本一次性渲染。
- 菜单按钮回调不在 `CommandContext` 的隐式 receiver 内；需要在命令处理阶段显式捕获/传入可见指令加载函数，避免 Kotlin 2 编译时出现 receiver 不匹配，导致 `coreMindustry/menu` 加载失败并连锁触发依赖脚本反复加载失败。

---

### `mdtserver/config/scripts/coreMindustry/menu.lib.kt`

类型：修改核心菜单库
职责：`MenuBuilder` / `PagedMenuBuilder` 以及菜单发送底层封装。

本项目改动：

- 新增 `IMenuOpenGuard` 服务接口。
- `MenuBuilder.sendTo(...)` 在实际 `Call.menu/followUpMenu` 前会调用该接口，允许安全脚本按玩家/IP/菜单频率拦截菜单打开。
- 当前由 `wayzer/security/securityGuard.kts` 提供实现，用于防止刷菜单拖慢主线程；菜单打开过快只提示/踢出，不进入异常分或封禁阶梯。

备注：

- 菜单拦截必须返回轻量字符串或 `null`；不要在接口实现中做大量数据库查询。

---

### `mdtserver/config/scripts/coreMindustry/scoreboard.kts`

类型：修改核心积分板脚本
职责：周期性向玩家左上角发送积分板/服务器信息弹窗。

本项目改动：

- 在 `scoreboard.ext.server-status` 中加入服务器状态行，显示当前 TPS 与 JVM 已用内存。
- 显示格式：`服务器状态: <TPS> TPS / <内存MB> MB`。
- 在 `scoreboard.ext.team-units` 中加入我方单位数量行，显示 `我方总单位数: {player.team.units}`；`player.team.units` 由 `coreMindustry/variables.kts` 的 `Team.units` 子变量提供。
- 该行通过现有 `{listPrefix scoreboard.ext|joinLines}` 扩展位进入积分板，尽量不破坏原模板结构。

备注：

- `/status` 的 TPS 与内存来源同样来自 `Core.graphics.framesPerSecond` 与 `Core.app.javaHeap`。
- 如果后续手动覆盖了积分板模板，需要保留 `{listPrefix scoreboard.ext|joinLines}` 才能继续显示此扩展行。

---

### `mdtserver/config/scripts/wayzer/user/forumPosts.kts`

类型：新增帖子脚本
职责：提供玩家交流帖子、评论、为作者赞踩入口和自动清理机制。

当前功能：

- `/posts` 打开帖子分区列表。
- `/posts <帖子ID>` 直接打开帖子。
- `/posts <分区code>` 直接打开分区帖子列表。
- `/posts new` 发布帖子。
- `/posts history` 查看最近 10 条玩家修改/删除帖子记录。
- `/posts lock <帖子ID>`、`/posts unlock <帖子ID>`：3+级与管理员锁定/解除自动清理保护。
- `/posts protect <帖子ID>`、`/posts unprotect <帖子ID>`：4级/admin 设置/解除帖子保护锁，保护后4级以下不可编辑/删除。
- `/posts trash`、`/posts restore <帖子ID>`、`/posts purge <帖子ID>`：4级/admin 管理回收站，支持恢复或彻底删除。
- 1级及以上玩家可发布帖子和评论。
- 分区首页显示 `当前帖子总数` 与 `总发帖数`，其中总发帖数为历史累计统计。
- 默认分区：全部分区、MDT分区、其他分区、搞七捻三、运营反馈。
- 帖子详情按钮包括：为作者点赞、为作者点踩、发布评论、查看评论（x条）。
- “为作者点赞/点踩”直接复用 `wayzer/ext/playerReputation.kts` 的玩家赞踩逻辑，受到现有每日限制、自赞限制、0级限制等约束。
- 帖子作者可修改自己的帖子标题和正文。
- 帖子正文与评论使用 `MdtTextFormat` 轻量格式渲染，支持 `#`/`##` 标题、列表、引用、重点、代码、分割线、链接提示和输入框快速换行。
- 帖子分区页提供“最近变更”，统一显示最近 10 条玩家修改/删除帖子记录。
- 帖子菜单与文本输入等待时间已调整为约 30 分钟。
- 3+级玩家与管理员可修改、置顶/取消置顶、锁定/解除锁定、删除任意帖子到回收站。
- 非4级/admin删除帖子需要填写理由，并受到删除频率限制；4级/admin可管理保护锁与回收站。
- 3+级玩家与管理员可新增/修改帖子分区名称和介绍。
- 默认增加等级分区：1级分区、2级分区、3级分区、3+级分区；等级不足的玩家不能查看对应分区帖子。
- “全部分区”会按当前玩家等级隐藏不可见的高等级分区帖子，避免标题泄漏。
- 启动时和每日首次打开帖子列表时自动清理超过上限的旧普通帖；置顶帖和锁定帖不会被自动清理；自动清理和普通删除都会先软删除到回收站。
- 发帖成功后触发 `ForumPostCreatedEvent`，供成就/统计扩展使用。
- 帖子列表与评论列表已改为数据库分页懒加载，只读取当前页；自动清理和列表/评论读取放入 `Dispatchers.IO`。
- 当前页帖子锁定状态批量读取，避免每个帖子按钮重复查询锁定列表。

存储：

- 使用 `MdtStorage` 中的独立表 `MdtForumSections`、`MdtForumPosts`、`MdtForumComments`、`MdtForumAuthorStats`。
- `MdtForumPosts` 当前表版本为 `2`，用于补充 `section_code` 分区字段。
- 清理日期、历史总发帖数、锁定帖 ID 列表使用 `MdtSettings` key：
  - `forum.cleanup.lastDate`
  - `forum.stats.totalPosts`
  - `forum.stats.initialized.v1`
  - `forum.lockedPostIds`
  - `forum.protectedPostIds`
  - `forum.postChangeHistory`
  - `forum.deleted.<帖子ID>.by/reason/at`
- 详细规则见 `docs/forum-posts.md`。

---

### `mdtserver/config/scripts/wayzer/user/privateMessage.kts`

类型：新增私聊脚本
职责：提供在线玩家之间的私聊与最近对象回复。

当前功能：

- `/msg <玩家3位ID/#游戏ID/名字> <内容>` 给在线玩家发送私聊。
- `/m`、`/tell`、`/w`、`/私聊` 为 `/msg` 别名。
- `/r <内容>`、`/reply`、`/回复` 回复最近私聊对象。
- 1级及以上玩家可发送私聊，0级/未绑定玩家不可发送，降低机器人骚扰。
- 被 `wayzer/ext/playerMute.kts` 禁言的玩家不能发送私聊，避免绕过禁言。
- 不能给自己发私聊；单条私聊默认限制 240 字；默认 10 秒最多 5 条。
- 私聊不落盘，只保存在线期间最近回复对象与临时限速计数；玩家离线后清理。
- `/help` 玩家指令分区已加入“私聊”。

文档：`docs/private-message.md`

---

### `mdtserver/config/scripts/wayzer/user/leaderboard.kts`

类型：新增排行榜脚本
职责：读取已落盘的 MDT 统计数据，提供玩家可查看的排行菜单。

当前功能：

- `/rank`、`/leaderboard`、`/排行榜`、`/排行` 打开排行榜。
- `/help` 玩家指令分区已加入“排行榜”。
- 当前排行：
  - MDC排行
  - 累计MDC排行
  - 发帖数排行
  - 被赞排行
  - 被踩排行
  - 送出赞排行
  - 送出踩排行
  - 被认可排行
  - 认可他人排行
- 只读数据库，不修改玩家状态。
- 查询放入 `Dispatchers.IO`，显示最多前 10 名。

存储：

- 读取 `MdtStorage` 中的 `MdtTrustProfiles`、`MdtForumAuthorStats`、`MdtReputationStats`、`MdtRecognitionStats`。
- 显示名优先使用在线玩家名或 `MdtPlayerSubjects.last_name`，并清理历史记录中残留的 `[游客]` 等前置头衔，取不到时显示 UID。
- 详细说明见 `docs/leaderboard-system.md`。

---

### `mdtserver/config/scripts/wayzer/user/wiki.kts`

类型：新增 Wiki 脚本
职责：提供服务器 Wiki/规则长文本页面、分页查看菜单与游戏内编辑入口。

当前功能：

- `/wiki` 打开 Wiki 列表。
- `/wiki <id>` 直接打开指定 Wiki 页面。
- `/wiki admin` 或 `/wikiadmin` 打开 Wiki 管理菜单。
- `/wikiadmin protect <id>`、`/wikiadmin unprotect <id>`：4级/admin 设置/解除 Wiki 保护锁。
- `/wikiadmin trash`、`/wikiadmin restore <id>`、`/wikiadmin purge <id>`：4级/admin 管理 Wiki 回收站。
- 3+级玩家与管理员可在游戏内新增、编辑、删除 Wiki 页面；普通删除会移入回收站。
- 被4级/admin保护锁锁定的 Wiki，4级以下不可编辑/删除。
- 非4级/admin删除 Wiki 需要填写理由，并受到删除频率限制。
- Wiki 页面提供“最近修改”入口，每页记录最近 10 次新增/编辑者与动作。
- Wiki 正文使用 `MdtTextFormat` 轻量格式渲染，支持 `#`/`##` 标题、列表、引用、重点、代码、分割线、链接提示和输入框快速换行。
- Wiki 编辑相关菜单/文本输入已延长到约 30 分钟，便于游戏内编辑长文本。
- 首次启动时导入预置 Wiki：`linuxdo-guidelines` / `linux do社区准则节选`。
- Wiki 列表/管理列表已改为只读取当前页轻量摘要；摘要包含 `preview` 与 `bodyLength`，避免列表预读正文。
- Wiki 详情/历史/编辑等数据库读取放入 `Dispatchers.IO`。

存储：

- 当前复用 `MdtStorage` 的 `MdtSettings`，不新增独立表。
- 新增回收站/保护锁相关 key：`wiki.trash.index`、`wiki.protectedIds`、`wiki.page.<id>.deletedBy/deleteReason/deletedAt`。
- 详细 key 与维护边界见 `docs/wiki-system.md`。

备注：

- Wiki 页面正文使用 `msg` 分页显示，按钮只保留上一页/下一页/返回/关闭，避免长文本塞进大量按钮造成客户端菜单卡顿。
- 脚本只能延长服务端等待玩家响应的超时；若 Mindustry 客户端自身硬编码关闭弹窗/输入框，则无法在脚本侧覆盖。
- 后续若要做 Wiki 版本历史、审核流程或更细权限，再考虑拆出独立表。

---

### `mdtserver/config/scripts/wayzer/user/tips.kts`

类型：新增 Tips 小提示脚本
职责：定时随机展示服务器规则/氛围/趣味小提示，并提供管理维护指令。

当前功能：

- 启动时默认导入 `C:\Users\qw114\Desktop\临时tips.txt` 中整理的 48 条预设 Tips。
- 每隔 `10` 分钟随机向在线玩家在聊天栏广播一条 Tips，方便玩家注意和回看；间隔参考柠檬开源插件 `wayzer/ext/alert.kts`。
- `[tips]` 标签默认权重 `5`，普通想法类提示使用英文标签 `[idea]`，默认权重 `1`，因此规则类 Tips 出现概率更高。
- 随机选择会记住最近 5 条已发送 Tips，并优先避开近期内容，降低连续重复消息概率。
- 玩家可用 `/tips` 立即查看一条随机 Tips。
- 管理员可用 `/tipadmin` 添加、修改、删除、启用/停用、立即发送 Tips。

存储：

- 复用 `MdtStorage` 的 `MdtSettings`：
  - `tips.items`
  - `tips.seeded.v1`

备注：

- 管理权限为 `wayzer.admin.tips`，默认注册给 `@admin`。
- `.kts` 顶层不要使用 `const val`，本脚本常量使用普通 `private val`，避免 ScriptAgent 编译失败。
- 详细指令见 `docs/tips-system.md`。

---

### `mdtserver/config/scripts/wayzer/ext/logicSaveCompat.kts`

类型：新增兼容修复脚本
职责：修复部分地图逻辑块运行时变量导致自动存档失败的问题。

当前功能：

- 监听 `EventType.SaveWriteEvent`，在保存前扫描所有 `LogicBlock.LogicBuild` 的运行时变量。
- 若发现逻辑变量中暂存了当前服务端 `TypeIO.writeObject` 不能写入的 `arc.struct.Seq`，则将该运行时变量置空。
- 提供管理指令 `/logicSaveCheck` 手动检查并清理。

背景：

- 地图 `24228`（死区）运行一段时间后出现自动存档失败：
  - `Unknown object type: class arc.struct.Seq`
  - 堆栈位于 `LogicBlock$LogicBuild.write`。
- 这是逻辑块运行时变量的序列化兼容问题，不是玩家数据/数据库问题。
- 2026-05-28 修复：v158 下 `LExecutor.unit` 可能尚未初始化为 `null`，保存前清理时改为判空后再清理 `@unit` 变量，避免 `/logicSaveCheck` 或 `SaveWriteEvent` 触发 `unit must not be null`。

备注：

- 只清理逻辑块运行时变量缓存，不修改逻辑代码本身。
- 权限：`wayzer.admin.logicSaveCompat`，默认注册给 `@admin`。

---

### `mdtserver/config/scripts/mapScript/*`（柠檬地图特殊玩法脚本迁入）

类型：批量新增地图玩法脚本
职责：按地图 ID、`@mapScript` 或地图标签启用特殊玩法逻辑。

本项目改动：

- 从 `柠檬的开源插件/ScriptAgent4MindustryExt-7.5/scripts/mapScript` 迁入一批当前服缺失的地图特殊玩法脚本。
- 已覆盖用户重点提到的 `Lord of War` 与 `TankWars`。
- `flood/洪水模式`：柠檬开源包内只有私有模块元数据；后续从外部 `mindustry_flood-main` 工程提取玩法思路，新增本服兼容版 `mapScript/tags/flood.kts`。
- 详细清单、筛选排除项和兼容性说明见 `docs/map-scripts.md`。

相关兼容脚本：

- `mdtserver/config/scripts/coreMindustry/utilNext.kts`
- `mdtserver/config/scripts/coreMindustry/contentsTweaker.kts`
- `mdtserver/config/scripts/coreMindustry/menu.kts`
- `mdtserver/config/scripts/wayzer/maps.manager.kt`
- `mdtserver/config/scripts/wayzer/maps.kts`
- `mdtserver/config/scripts/mapScript/module.kts`
- `mdtserver/config/scripts/mapScript/lib/TagSupport.kt`
- `mdtserver/config/scripts/mapScript/tags/voidProduce.kts`
- `mdtserver/config/scripts/mapScript/tags/flood.kts`
- `mdtserver/config/scripts/lemon/module.kts`
- `mdtserver/config/scripts/lemon/lib/dao/PlayerData.kt`
- `mdtserver/config/scripts/lemon/user/achievement.kts`

备注：

- Lemon 旧成就调用由 no-op 兼容层承接，不接入 MDT 自建成就/MDC系统。
- `mapScript/tags/flood.kts` 同时注册 `@flood` 与 `@floodV2`；当地图使用 `[@floodV2]` 时，会提示“本服不存在floodV2，已启用flood用于兼容”。
- 2026-06-12 根据 `C:/Users/qw114/Downloads/mindustry_flood-main (1)/mindustry_flood-main` 复查原玩法，给 `mapScript/tags/flood.kts` 补回：
  - 普通泉眼在短时间承受足量伤害后进入 `SUSPENDED` 压制，压制期间停止产水。
  - `coreBastion/coreCitadel/coreAcropolis` 作为充能泉眼计数，需要摧毁后才可通关。
  - 非洪水队伍冲击反应堆在普通泉眼附近满功率启动后，净化/摧毁最近的普通泉眼并清理附近洪水。
  - 全部普通泉眼同时被压制/已净化，且充能泉眼为 0 时，保持 `@floodNullifyPeriod` 秒触发默认队伍胜利。
- 2026-06-12 继续按该参考工程完整重实现 flood 玩法：
  - 普通泉眼按核心类型产水，并在高水位包围时升级；压制会削减升级进度。
  - 充能泉眼加入充能、爆发产水、溢出回血/升级与状态标签。
  - 钍反应堆/孢子发射器会远程投洪水，非洪水队伍 `segment` 可拦截；震爆塔会向附近建筑堆洪水；雷达会锁定单位并充能打击。
  - 力墙/单位护盾会吸收洪水并过载爆洪；洪水队伍爬虫/蜘蛛单位接触建筑会爆洪；天垠免疫洪水且不会压制/伤害洪水建筑。
  - 为避免原工程 0.03 秒 tick 带来的服务端压力，当前兼容版按 1 秒 tick 折算关键数值，并提供 `@floodChargedChargeScale`、`@floodSporeCooldown` 等标签调整。
- v8/157.4 标签加载修复：
  - `wayzer/maps.manager.kt` 从地图介绍/资源站 meta 中解析 `[@tag]`，在 `SaveIO.load(...)` 后合并回 `Vars.state.rules.tags`，并发出 `MapLoadFinishedEvent`。
  - `wayzer/maps.kts` 在 `WorldLoadBeginEvent` 兜底应用 `MapManager.tmpVarSet`，避免无内容补丁地图丢失预设 rules。
  - `mapScript/module.kts` 监听 `MapLoadFinishedEvent`，在 tags 恢复后加载 `@mapScript` 与标签脚本，并按地图 key 防重复。
  - `mapScript/lib/TagSupport.kt` 支持按约定路径 `mapScript/tags/<tag>` 查找尚未注册的标签脚本，并将 `@floodV2` 兼容到 `mapScript/tags/flood`。
- 当前诊断结果：`共找到156脚本,加载成功152,启用成功100,出错0`。
- 已验证：
  - `host 17680` 加载 `mapScript/tags/flood`。
  - `host 18189` 加载 `mapScript/15463` 与 `mapScript/tags/flood`。
  - `host 22023` 触发 `@floodV2 -> @flood` 兼容提示并加载 `mapScript/tags/flood`。
- 2026-05-28 根据线上日志修复 `mapScript/15450.kts`（TankWars）空单位兼容：
  - 击杀结算时若击杀者已死亡/断线导致 `player.unit()` 返回 `null`，不再抛出 `unit(...) must not be null`，经验/灵魂区倍率回退到被击杀单位所在区域。
  - 单位受伤事件中被伤害单位可能没有所属玩家，`unit.player` 改为判空后再刷新战斗状态，避免 `getPlayer(...) must not be null`。
  - 拦截 `UnitControlCallPacket` 时丢弃空 `packet.unit` 或无连接玩家的数据包，避免 `unit must not be null`。
- 2026-06-12 再次修复 `mapScript/15450.kts`（TankWars）迁移后热循环异常：
  - 玩家 `unit()` 可能为 `null` 时不再读取 `type`，避免主玩法循环抛 NPE 后被 `loop` 自动暂停 10 秒，导致升级/商店炮台部署看起来卡住。
  - 升级检测改为单次 tick 可连续处理多级经验溢出，并对等级到单位类型的索引做上限保护。
  - 升级换单位时先保存坐标再击杀旧单位，避免击杀后再次读取旧 `player.unit()` 造成不同步/空单位问题。
  - 辅助武器部署改为寻找附近可放置地块，`setNet` 成功后才标记本单位已使用；减少当前脚下不可放置时“概率性不放炮台”的情况。
  - 单位销毁后移除脚本侧 `UnitData` 缓存，避免长期保留已销毁单位状态。

---

### `mdtserver/config/scripts/wayzer/map/mapFilter.kts`

类型：新增地图筛选脚本
职责：封禁问题地图、按“今日PVP”状态筛掉 PVP 地图，并提供对应投票入口。

当前功能：

- `/banmap <地图ID>`：3+级、4级/admin 或控制台可直接封禁地图 ID。
- `/unbanmap <地图ID>`：3+级、4级/admin 或控制台可解除地图封禁。
- `/banmaps`：查看当前封禁地图 ID 列表。
- `/todaypvp [on|off|status]`：查看/设置今日 PVP 开关；`off` 后自动换图会跳过 PVP 地图，手动换到 PVP 图也会被阻止。
- `/vote banmap <地图ID>`：玩家投票封禁地图。
- `/vote pvpOff`、`/vote pvpOn`、`/vote pvp <on|off|status>`：玩家投票关闭/开启今日 PVP。

实现说明：

- 封禁地图 ID 保存在 `MdtSettings` 的 `mapFilter.bannedMapIds`。
- 今日 PVP 关闭状态保存在 `MdtSettings` 的 `mapFilter.pvpDisabledDate`，值为当天日期，次日自动失效。
- 监听 `GetNextMapEvent`：自动换图候选若被封禁或今日 PVP 关闭时为 PVP 图，会自动重新选择可用地图。
- 监听 `MapChangeEvent`：手动 `/host`、投票换图等直接切图若命中封禁/PVP关闭规则，会取消换图并广播原因。
- `wayzer/cmds/voteMap.kts` 已接入本脚本，发起换图投票前会先检查地图筛选状态。
- 实验性性能优化执行兜底强制换图时，会通过 `performanceGuard.experimental.forceChangingMap` 临时绕过本脚本拦截，避免卡服图无法脱离。

备注：

- 该系统用于处理如 `22171 / Lord of Flood 洪水棋盘` 这类地图内容补丁与 v8/157.4 不兼容的问题图。
- 当前只按地图 ID 封禁，暂不记录封禁原因/操作者；后续如需要排行榜式维护坏图库，可扩展存储结构。

---

### `mdtserver/config/scripts/wayzer/map/performanceGuard.kts`

类型：统一性能优化模式入口
职责：维护 `performanceGuard.mode`、本局投票关闭状态、`/perf` 与 `/vote perf`；实际检测和执行由 `serverPressure.kts` / `serverPressureActions.kts` 完成。

当前功能：

- `/perf status`：查看当前性能优化模式与兼容状态。
- `/perf on`、`/perf off`、`/perf reset`：3+级、4级/admin 或控制台管理常驻性能优化。
- `/vote perf off` 只关闭本局性能优化，下次 `WorldLoadEvent` 恢复服务器持久模式；`/vote perf on` 可在本局重新开启。
- 旧本地 TPS 清理循环通过 `legacyLocalLoopEnabled=false` 默认关闭，避免双重检测/双重覆盖。
- 标准与实验性执行逻辑已经合并；PVP 同样受保护，但按压力单位优先级尽量最后处理玩家队伍。
- `performanceGuard.mode` 运行中使用内存缓存，管理指令/投票切换模式时同步写库与更新缓存。

存储：

- `performanceGuard.mode`：持久模式，只使用 `normal` / `off`；旧 `experimental` 启动时自动迁移为 `normal`。

备注：

- 投票关闭本局性能优化不会关闭网络 fail-open 保护和快照安全保护；只停止世界清理、规则修改与极端换图。
- 详细说明见 `docs/performance-guard.md`。

---

### `mdtserver/config/scripts/wayzer/map/performanceGuardExperimental.kts`

类型：旧实验性性能指令兼容层
职责：保留既有 `/xperf`、`/vote xperf` 和手动阶段入口，实际统一切换 `performanceGuard.kts` 的 `normal/off` 状态。

当前功能：

- `/xperf on` 等价于开启统一性能优化，不再写入独立 `experimental` 模式。
- `/xperf off` / `/vote xperf off` 使用统一关闭接口；投票关闭为本局状态。
- `/xperf stage <1-4>`、`/xperf fallback` 仅作为旧管理入口保留，不应成为自动执行主链路。
- 旧本地实验性检测循环默认关闭，避免与 `serverPressureActions.kts` 重复执行。

存储：

- `performanceGuard.experimental.disabledLogicPositions`：记录压力措施关闭的逻辑处理器位置。
- `performanceGuard.experimental.forceChangingMap`：极端兜底换图期间临时绕过地图筛选拦截。

备注：

- 不要重新扩展第二套自动压力判断；新措施统一添加到 `serverPressureActions.kts`。
- 详细说明见 `docs/performance-guard.md`。

---

### `mdtserver/config/scripts/wayzer/reGrief/trafficMonitor.kts`

类型：v159 上行与世界流监控
职责：统计总上行、游戏同步上行、世界/资产流，并观察待加入连接与 TCP 待发积压。

当前功能：

- `/traffic status`：查看总上行、游戏同步上行、世界/资产流、预算、活动流、待加入、TCP待发与主要包类型。
- `/traffic budget <Mbps>`：4级/admin 或控制台设置上行预算，默认 18 Mbps。
- `/traffic reset`：重置采样窗口。
- 注册 `scoreboard.ext.traffic`，在积分板显示 `总上行 / 同步上行`。
- `NetworkTransferSnapshot` 提供活动流、待加入、最老等待、TCP待发、最大单连接待发和拥塞连接数。

存储：

- `trafficMonitor.budgetMbps`：上行预算。

备注：

- 普通包是应用层发送需求估算；世界/资产流按 `ByteArrayInputStream.available()` 的实际读取进度统计。
- MindustryX 有 `SendPacketEvent` 时统计普通包；官方端缺少该事件时仍可统计世界/资产流与 TCP 状态。
- 游戏同步分类排除连接、菜单、聊天、音频、资产、世界开始等包；世界/资产流不会进入游戏同步上行。

---

### `mdtserver/config/scripts/wayzer/map/serverPressure.kts`

类型：统一性能/网络压力判断
职责：分别计算性能等级与网络保护等级，输出 v159 快照间隔建议。

当前功能：

- `/pressure status`：查看性能等级、TPS等级、游戏同步等级、网络等级、快照限制、总/同步/世界流与连接状态。
- `/pressure tps`：查看当前 TPS 分级阈值。
- `/pressure tps <L1> <L2> <L3> <L4> [恢复]`：3+级、4级/admin 或控制台修改 TPS 触发阈值；阈值会持久化到数据库。
- `/pressure tps reset`：恢复默认 TPS 阈值。
- 注册 `scoreboard.ext.pressure`，有性能压力或快照限制时显示。
- 性能等级只取 TPS 与游戏同步上行；网络等级取总上行、活动世界流、TCP积压和待加入时间。
- 世界/资产流不得进入单位清理等级，只能推动快照保护和入服门控。
- 非零压力降级需要连续稳定采样，退出压力也需要连续达到恢复线，避免 TPS/上行在阈值附近反复横跳。
- 同步限制另有独立滞回：默认启用后至少保持约 45 秒，降级需连续 3 次稳定采样，退出需连续 6 次低于恢复线，避免上行限速在波次流量波动中频繁启停。

备注：

- 只负责判断，不直接清理世界，避免检测与执行耦合。
- 快照限制可由游戏同步或网络压力触发；即使本局投票关闭性能优化，网络保护仍可工作。
- 压力升高播报只在当前压力周期首次升到更高等级时触发；完全恢复后才会重置播报等级，减少刷屏。

---

### `mdtserver/config/scripts/wayzer/map/serverPressureActions.kts`

类型：新增服务器压力措施执行脚本
职责：根据 `serverPressure.kts` 输出的等级实际执行清火、清子弹、限单位、暂停波次、关闭处理器、暂停/换图等措施。

当前功能：

- 等级1：关闭火焰并清理火焰/子弹，同时少量清理 1 阶低级单位。
- 等级2：暂停波次、推迟 `wavetime`、设置临时单位上限并清理更多 1-2 阶非玩家压力单位。
- 等级3：关闭世界/逻辑处理器，扩大到玩家队伍并清理 T1-T3 压力单位。
- 等级4：继续清理到配置阶级；TPS与游戏同步同时超限时额外清理数量前三种单位，并在持续过低后暂停游戏。
- 压力等级降低时会逐步回退高等级措施：降到1级恢复波次/单位上限/处理器，降到2级恢复处理器，降到0级恢复全部。
- 游戏同步达到预算60%且2秒内多人退出时执行 PPS 兜底；保留 `mono/pulsar/quasar/poly/mega`，额外清理导弹单位与 `scathe`。
- 游戏同步超过预算200%时清理 T4 及以下单位；世界/音乐/CP流不会触发。
- 最终换图仅在当前TPS与滑动均值每次采样都低于5、连续2分钟时允许执行。
- `/gamepause on|off|status`：3+级/4级/admin 或控制台管理暂停状态。
- `/vote pause`、`/vote resume`：玩家投票暂停/继续。

存储：

- `performanceGuard.experimental.disabledLogicPositions`
- `performanceGuard.experimental.forceChangingMap`

备注：

- 统一模式介入 PVP，但优先清理火焰、子弹、波次队伍和非玩家队伍。
- 不使用单纯 `unitBuildSpeedMultiplier = 0` 作为单位限制核心，改为设置 `rules.unitCap` 与压力分级清理单位。
- 单位清理使用显式 Serpulo/Erekir 阶级表，避免按血量/权重排序导致 Erekir 低阶单位压过 Serpulo 高阶单位；并使用 `kill()` 走原版死亡同步链路，不再直接 `remove()`。
- 压力规则同步使用 `Call.setRule` 逐字段发送实际变更，不再在每轮压力执行时 `Call.setRules(state.rules)`；避免反复覆盖客户端本地显示类 Rules（如 fog/staticFog）并降低上行浪费。

---

### `mdtserver/config/scripts/wayzer/map/adaptivePlayerLimit.kts`

类型：新增自适应人数上限脚本
职责：根据当前人数与 `serverPressure.kts` 的上行压力判断，动态调整 Mindustry 原生 `playerLimit`。

当前功能：

- `/adaptiveplayerlimit status`：查看启用状态、当前人数、动态上限、原生上限、基础/最高/步长/快满提前量、平均上行、上行压力安静时间与下次调整时间。
- `/adaptiveplayerlimit on|off`：启用/关闭自适应人数上限；关闭时恢复脚本启用时的原生 `playerLimit`。
- `/adaptiveplayerlimit reset`：将动态上限重置为基础上限。
- `/adaptiveplayerlimit base <人数|reset>`：设置基础人数；`reset` 表示继续使用服务端启动时的原生 `playerLimit`。
- `/adaptiveplayerlimit max <人数>`：设置动态最高人数，默认 32，防止人数无限上涨。
- `/adaptiveplayerlimit threshold <Mbps>`：设置允许扩容的平均上行阈值，默认 16 Mbps。
- `/adaptiveplayerlimit step <人数>`：设置每轮增减步长，默认 2。
- `/adaptiveplayerlimit headroom <人数>`：设置快满扩容提前量，默认 2；例如基础上限 18 时，当前人数达到 16 就可在性能条件满足时扩容。
- `/adaptiveplayerlimit quiet <分钟>`：设置扩容前需要连续无上行压力的时间，默认 5 分钟。

调节规则：

- 初始动态上限来自服务端启动时原生 `playerLimit`（可配置覆盖，当前基础上限预期为 18）。
- 每分钟最多调整一次。
- 若当前人数达到“动态上限 - 快满提前量”，且最近 5 分钟没有上行压力、平均上行低于阈值，则动态上限增加 2。
- 回收时保留快满缓冲：只有当前人数低于“动态上限 - 快满提前量 - 步长”才会减少 2，避免 18 上限/16 人扩到 20 后下一轮立刻降回 18。
- 原生 `playerLimit` 会同步为动态上限，让服务器列表显示更直观的满员状态。
- 不保留管理员插队槽；即使 Mindustry 原生 admin 绕过原版人数检查进入 `PlayerConnect`，脚本也会按同一动态上限检查并踢出超员连接。
- 指令名避免使用 `/playerlimit`，防止覆盖 Mindustry 启动参数/原版服务端命令 `playerlimit 18`；脚本启动后默认延迟 10 秒接管原生上限，以读取启动参数设置后的真实基础人数。

权限：

- 管理指令权限：`wayzer.map.adaptivePlayerLimit`，默认注册给 `@admin`。
- `/help` 管理指令分区已加入 `/adaptiveplayerlimit`。

备注：

- 脚本依赖 `serverPressure.kts`，以网络/快照压力识别最近上行繁忙状态。
- 人数统计包含 `Groups.player`、`PlayerConnect` 待处理、`connectSyncGuard` 已预留和等待连接，防止未完成 `connectConfirm` 的幽灵连接绕过人数限制。

---

### `mdtserver/config/scripts/wayzer/reGrief/connectSyncGuard.kts`

类型：v159 网络压力入服同步门控
职责：只在网络压力时限制同时进行世界/资产同步的连接数，减少上行满载时的幽灵玩家与长期无核心机。

当前功能：

- 网络等级0完全不限制；等级1默认允许2名、等级2+默认允许1名同时同步。
- 只把已经放行进入同步的连接计入名额，等待者不占自己的名额。
- 等待队列默认最多8人、最长12秒；超时提示稍后重试。
- 压力数据20秒未更新、脚本关闭、协程取消、异常或卸载时 fail-open。
- `PlayerConnectionConfirmed`、退出和断线时释放预留。

备注：

- 该脚本依赖 `ConnectAsyncEvent`，位置必须在原版 `sendWorldAndAssets` 之前。
- 不得改成常态门控，也不得在异常时 fail-close；重要入服链路宁可回原版高流量，不能永久拒绝所有新玩家。

---

### `mdtserver/config/scripts/wayzer/reGrief/syncThrottle.kts`

类型：v159 原生快照频率保护
职责：使用 v159 原生快照间隔和批量发送，不再逐玩家手工序列化全部实体。

当前功能：

- 反射读取 `Administration.Config.snapshotInterval`，保存原始值。
- 压力时按 `serverPressure` 建议增大间隔，默认280/420/560ms，上限800ms；绝不低于原生间隔。
- 恢复或卸载后还原原始 `snapshotInterval`。
- MindustryX `SendPacketEvent` 可用且存在待加入连接时，将状态/实体/建筑快照批量发送给 `hasConnected=true` 的玩家。
- 重路必须先发送成功再取消原广播；任一失败会禁用重路并 fail-open 回退原版广播，避免在线玩家丢快照或每帧异常。

备注：

- 官方端没有 `SendPacketEvent` 时仍可调整原生间隔，但不执行待加入连接重路。
- 不要恢复 X35 的 `syncTime/snapshotsSent` 逐玩家实现；它会绕过 v159 共享序列化优化。
- 若玩家反馈世界卡住，应对照 `/pressure status`、`/traffic status` 与 watchdog，确认是主线程停顿还是网络上行满载。

---

### `mdtserver/config/scripts/wayzer/ext/mainThreadWatchdog.kts`

类型：新增诊断脚本
职责：在后台检测游戏主线程是否长时间未推进，辅助定位“聊天正常但单位/世界停止同步，恢复后玩家被拉回”的半卡死问题。

当前功能：

- `watchdogEnabled` 默认开启；后台按 `checkIntervalMillis`（默认 500ms）检查 `Trigger.update` 的最近更新时间。
- 主线程超过 `stallWarnMillis`（默认 3000ms）未更新时，按 `dumpCooldownMillis` 冷却输出相关线程堆栈，优先包含游戏主线程、网络、协程、脚本与寻路线程。
- 主线程恢复后会输出恢复耗时日志；如果没有抓到停顿窗口，也会留下恢复记录。
- `/tickwatchdog status`：查看诊断状态、距上次游戏更新、累计更新帧与捕获到的主线程名称。

维护备注：

- 该脚本只做日志诊断，不主动修复/踢人/换图。
- 服务端正常关闭、测试脚本强制退出或 JVM 停止期间可能出现一次误报，应结合玩家反馈和前后日志判断。

---

### `mdtserver/config/scripts/wayzer/reGrief/unitLimit.kts`

类型：保留兜底单位限制脚本
职责：保留超大敌方单位数/终结波自动投降兜底；旧版“队伍单位超量立即杀低级单位”默认关闭，由 `serverPressureActions.kts` 在有 TPS/上行压力时接管。

当前功能：

- `legacyUnitCleanupEnabled=false` 时，不再在 `UnitUnloadEvent` 中常驻清单位，避免无压力时误伤玩家造出的高级单位。
- 若临时开启旧版清理，也改为按显式 1/2 阶单位表清理，不再用 `maxHealth < 1000` 或纯血量排序。
- 敌方单位数超过 5000 时仍自动投降并清理波次队伍。
- 下一波预计生成数超过 3000 时仍进入终结波自动投降兜底。

备注：

- 新的单位清理策略维护在 `serverPressureActions.kts`，不要再在本脚本扩展常驻清理逻辑。

---

### `mdtserver/config/scripts/wayzer/reGrief/inactivePressureCheck.kts`

类型：游戏同步压力挂机检测脚本
职责：仅游戏同步上行超限时发送挂机确认，移出长期无响应玩家，降低同步倍数压力。

当前功能：

- `serverPressure.trafficLevel > 0` 时发送弹窗/聊天提示，后续每 15 分钟最多提示一次。
- 玩家点击“我还在”、聊天或点击地图均视为响应。
- 到期无响应则踢出服务器。

备注：

- 不对 3+级/4级做豁免，避免额外复杂逻辑。
- 与菜单自动超时区分：弹窗自动关闭不会立刻判定挂机，只有超时前没有任何响应才会踢出。
- 世界/资产流、音乐、CP和玩家加入不会触发挂机检测。

---

### `mdtserver/config/scripts/wayzer/reGrief/autoChangeMap.kts`

类型：修改既有防卡图自动换图脚本
职责：当长时间无人可正常游玩/无人存活时自动换图，避免卡服图或无法复活状态长期占用服务器。

本项目改动：

- 新增配置项 `idleFallbackMapId`，默认 `13752`。
- 触发无人游玩自动换图时，优先切换到 `13752` 地图。
- 如果未找到该地图 ID，则记录警告并回退为原本随机换图逻辑。
- 提示后的 5 秒等待结束时会再次检查是否已有可游玩的存活玩家，避免玩家刚恢复/刚加入时仍被误切图。
- 换图改为等待 `MapManager.loadMapSync(...)` 结果，只有实际加载成功后才标记 `newMap=true`，加载失败或被拦截时下一轮继续检测。

备注：

- 当前仍沿用原逻辑：约 100 秒没有可游玩的存活玩家后提示，5 秒后换图。
- 该逻辑不同于启动时 `wayzer/map/autoHost.kts` 的自动开服逻辑。

---

### `mdtserver/config/scripts/wayzer/maps.manager.kt`

类型：修改既有地图管理库
职责：地图加载流程、地图切换事件、地图标签恢复。

本项目改动：

- 新增 `MapLoadFinishedEvent`：地图读取完成且 tags 合并回 `Vars.state.rules.tags` 后触发，供 `mapScript/module.kts` 延后加载地图脚本。
- 从地图介绍与资源站 meta description 中解析 `[@tag]`，兼容 v8/157.4 下 tags 在 `SaveIO.load(...)` 后丢失的问题。
- 加载地图时会用 `MapInfo.meta` 中的 `name/author` 覆盖资源站 `.msav` 内嵌的显示元数据，并在 `SaveIO.load(...)` 后再次写回 `Vars.state.map`：
  - 解决部分资源站地图文件内仍写着 `Editor Playtesting` / `Unknown`，但资源站 API 中名称与作者正确，导致游戏内积分板显示错误的问题。
  - 不会强行覆盖已有地图 `description`，避免误删 `[@flood]`、`[@mapScript]` 等写在原地图介绍里的玩法标签。
- `loadMapSync(info, map)` 返回值由 `Unit` 改为 `Boolean`：
  - `true`：实际完成换图。
  - `false`：被 `MapChangeEvent` 取消，例如地图筛选系统阻止了该地图。
- `SaveIO.load(...)` 后会按 `MapInfo.mode` 恢复 PVP/进攻等关键模式标记：
  - 解决资源站或本地地图 `.msav` 内嵌 rules 把 PVP 覆盖回生存模式，导致不分队、结束时不显示胜利队伍的问题。
  - 加载日志会输出 `loadMap rules: infoMode=..., rulesMode=..., pvp=..., activeTeams=...`，用于定位地图识别/分队问题。
- PVP 换图后给在线玩家重新分队前，会先在 `reset()` 后把待分配玩家临时置为 `Team.derelict`：
  - 避免 `betterTeam.randomTeam` 把上一局/上一张图的队伍当作旧队伍保留，导致所有玩家继续留在同一队。

备注：

- 调用方若需要显示“换图成功”，应以 `loadMapSync(...) == true` 为准。

---

### `mdtserver/config/scripts/wayzer/map/betterTeam.kts`

类型：修改既有队伍脚本
职责：替换 Mindustry 默认队伍分配器，提供 PVP 队伍均衡、观察者队伍和禁用队伍处理。

本项目改动：

- 修复 PVP 分队粘住旧队伍的问题：
  - 旧逻辑会优先沿用 `player.team()`，新玩家或换图后的玩家如果当前队伍正好也是新 PVP 图的可用队伍，就会全部留在同一队。
  - 现在只在玩家离线后重连时通过 `savedTeams` 尝试恢复原队伍；普通新加入/换图重分配会按当前 PVP 图核心队伍重新均衡。
- PVP 判断增加 `MapManager.current.mode == pvp` 兜底：
  - 即使某些加载阶段 `state.rules.pvp` 暂时还没恢复，分队器仍会按当前地图模式取有核心的 PVP 队伍。
- `allTeam` 仍只取当前 PVP 图中有核心、非 derelict、未被 `@banTeam` 禁用的队伍。
- `@banTeam` 会在 `MapLoadFinishedEvent` 后重新解析，并以当前地图介绍/资源站描述中的 `[@banTeam=...]` 为准、`rules.tags` 兜底：
  - 修复了 v8 下 `rules.tags` 没有同步到地图描述里 `[@banTeam]` 时禁用队伍不生效的问题。
  - 支持 `,`、`;`、空格、中文逗号/顿号分隔，支持队伍数字ID或队伍名（如 `sharded`），允许写 `[banTeam=...]` 不带 `@`。
- `/team` 手动切队的可选列表和切换目标不再过滤 `@banTeam`，管理员需要临时拉人到被标签禁用的队伍时不会被拦截；自动分队仍继续遵守 `@banTeam`。

备注：

- 强制观战、游客观战等脚本仍通过 `AssignTeamEvent` 拦截分队结果，不受这次改动影响。

---

### `mdtserver/config/scripts/wayzer/map/pvpProtect.kts`

类型：修改既有地图辅助脚本
职责：PVP 开局保护，阻止玩家/单位在保护时间内进入敌方核心区域。

本项目改动：

- PVP 判断增加 `MapManager.current.mode == pvp` 兜底，避免部分地图 rules 恢复时序导致保护脚本不启动。
- 保护逻辑改为在 `MapLoadFinishedEvent` 后启动，并优先读取地图介绍/资源站描述里的 `[@pvpProtect=...]`，`rules.tags` 兜底：
  - 修复了 `[@pvpProtect=0]` 在 v8 下仍然回退到默认 600 秒（10 分钟）保护的问题（即 `rules.tags` 没有同步到地图描述里的值时不再失效）。
  - 解析为 `0/false/off/no/none/disable/disabled` 时关闭保护；为 `true/on/yes/enable/enabled` 或缺省值时沿用 config 中的默认值；为数字时直接生效（秒，最小 0）。
  - 允许写 `[pvpProtect=...]` 不带 `@`。
- 修复旧逻辑把闯入者传送到“最近核心”附近的问题：
  - 闯入敌方区域时最近核心往往正是敌方核心，旧逻辑会把玩家反复传送在敌方核心附近。
  - 现在改为传送回己方最近核心周围的可通行、安全格。
- 玩家警告加入 5 秒冷却，避免单位持续越界时每秒刷屏。

---


### `mdtserver/config/scripts/wayzer/lib/MdtStorage.kt`

类型：新增共享存储模块
职责：MDT 自定义系统的数据库表定义与读写函数。

当前功能：

- 定义并封装信任等级、资历等级/在线时长、MDC、赞/踩、认可、称号、成就/自定义成就、商店、随机形态、禁言、帖子、红包等系统的数据库表。
- 账号/绑定相关表：`MdtAccounts`、`MdtAccountBindings`、`MdtPlayerSubjects`、`MdtSettings`。
- 商店/技能相关表：`MdtTitleShopItems`、`MdtShopPurchaseStats`、`MdtPlayerSkills`、`MdtSeniorityProfiles`。
- 成就相关表：`MdtPlayerAchievements`、`MdtCustomAchievements`。
- 帖子相关表：`MdtForumSections`、`MdtForumPosts`、`MdtForumComments`、`MdtForumAuthorStats`。
- 对业务脚本提供小型读写函数，业务规则仍保留在各自脚本中。

重要依赖：

- `coreLibrary/DBApi`
- Exposed ORM/SQL DSL，由 ScriptAgent 自带 DBApi 引入。

备注：

- 该文件是存储层，不应放晋升条件、成就条件、赞踩额度等业务规则。
- 账号登录后主体 ID 使用 `account:<id>`；未登录时仍使用游戏 UUID。`MdtPlayerSubjects` 同时记录游戏 UUID、账号主体、最近 IP/USID/名字/登录时间。

---

### `mdtserver/config/scripts/wayzer/lib/MdtTextFormat.kt`

类型：新增共享文本格式工具
职责：为 Wiki、帖子等菜单长文本提供轻量格式渲染与输入换行辅助。

当前功能：

- 将有限的 Markdown 子集转换成 Mindustry 菜单彩色文本：`#`/`##` 标题、列表、引用、`**重点**`、反引号代码、`---` 分割线、链接提示。
- 图片语法不会嵌入图片，只显示图片链接提示，符合原版客户端菜单限制。
- 将输入框中的 `\n`、`|`、全角 `｜` 转为真实换行，缓解游戏内输入多行正文麻烦的问题。

备注：

- 这不是完整 Markdown 引擎，不支持表格、真正图片、HTML 或复杂嵌套格式。

---

### `mdtserver/config/scripts/wayzer/mdtDatabase.kts`

类型：新增数据库注册脚本
职责：注册 MDT 自定义系统数据库表。

当前功能：

- 依赖 `coreLibrary/DBApi`。
- 调用 `registerTable(*MdtStorage.tables())`。
- 由依赖它的业务脚本确保表被纳入 DB 初始化。

备注：

- 不放业务逻辑。
- `wayzer/lib/*.kts` 在当前项目结构中不适合作为普通脚本依赖，因此注册脚本放在 `wayzer/mdtDatabase.kts`。

---

### `mdtserver/config/scripts/wayzer/vote.lib.kt`

类型：修改既有投票服务库
职责：投票事件和投票命令框架。

本项目改动：

- 增加 `VoteEvent.vetoBy(operator, reason)`：让当前投票以失败结果结束；被否决的投票同样会给发起者写入失败冷却，避免非管理员被否决后立刻反复发起。
- 增加 `VoteEvent.current()` 与 `VoteEvent.vetoCurrent(...)`：供外部脚本安全获取/否决当前投票。
- 投票弹窗增加 `关闭` 按钮；不改变投票结果，仅关闭当前弹窗，后续仍可通过聊天或再次弹窗投票。
- 投票计数改为“同 IP 只计一票”，同 IP 多个玩家投票时以后一次投票为准；状态栏的赞成/中立/反对/未投票也按 IP 去重统计。
- 投票失败冷却同时按玩家 UUID 与 IP 写入；未登录游客发起投票失败时使用更长冷却（默认30分钟），同 IP 其他发起入口也会受该冷却约束。
- 投票创建时会快照发起者 UUID/IP 冷却键；即使发起者在投票结束前离线，失败/否决时也能按发起时 IP 写入冷却。
- 拥有 `wayzer.admin.voteUnlimited` 的 4级/管理员发起投票时跳过失败冷却检查，失败/被否决也不会写入 UUID/IP 发起冷却；同一时间仍只允许一个全局投票。
- 默认投票资格不再依赖 `player.dead()`，避免投票期间单位死亡或换图导致票数被过滤；新增全局拒绝谓词接口，供强制观战等脚本禁止特定玩家参与普通投票。

备注：

- 投票服务库不直接硬编码信任等级，只通过权限 `wayzer.admin.voteUnlimited` 做管理员冷却豁免；一票否决权限判断放在 `wayzer/user/trustVoteVeto.kts`。
- 0级/未登录玩家允许发起投票；限制主要来自失败冷却、同 IP 共享发起冷却与正在进行的投票互斥。
- 同 IP 共票只影响投票权重与发起冷却，不阻止同 IP 多名玩家进入服务器。

---

### `mdtserver/config/scripts/wayzer/vote.kts`

类型：修改既有投票入口脚本
职责：注册 `/vote` 根命令、聊天文字投票与投票状态显示。

本项目改动：

- `/vote` 根命令只负责当前投票互斥、子命令分发、聊天文字投票与状态显示。
- 0级/未登录玩家可发起投票；游客失败后的长冷却由 `VoteEvent` 统一处理，因此 `/vote`、`/maps` 菜单与 `/votekick` 入口都走同一套限制。
- 额外注册 `wayzer.admin.voteUnlimited` 给 `@admin`，用于 4级/管理员无限制发起投票（仅豁免冷却，不开启并发投票）。
- 新增波次控制投票：`/vote pauseWave [秒数]` 暂停波次计时、`/vote setWave <波次>` 调整当前波次、`/vote resumeWave` 取消暂停波次；PVP 模式禁用这些波次控制投票。

---

### `mdtserver/config/scripts/wayzer/module.kts`

类型：修改既有模块脚本
职责：WayZer 主模块声明与公共依赖。

本项目改动：

- 增加 `@file:Depends("coreLibrary/DBApi", "数据库储存")`。
- 目的：让 `wayzer/lib/MdtStorage.kt` 能在 `wayzer` 模块编译时引用 Exposed/DBApi 类型。

---

### `mdtserver/config/scripts/wayzer/user/accountPassword.kts`

类型：新增工具脚本
职责：账号密码哈希与校验。

当前功能：

- 使用 `PBKDF2WithHmacSHA256`、随机盐、`120000` 次迭代生成密码哈希。
- 存储格式为 `pbkdf2$迭代次数$saltBase64$hashBase64`。
- 提供：
  - `hashPassword(plain)`
  - `verifyPassword(plain, encoded)`

备注：

- 不保存密码、不处理登录流程，只做密码工具。
- 管理员重置密码时输入明文新密码，由账号脚本调用本工具哈希后保存。

---

### `mdtserver/config/scripts/wayzer/user/accountAuth.kts`

类型：新增功能脚本
职责：MDT 自建 QQ+密码账号注册/登录系统。

当前功能：

- `/account` 打开账号菜单。
- `/captcha` 获取注册用的 4 位验证码；要求本次服务端启动后该 UUID 累计在线满 1 小时，数据只保存在内存中不落盘；不足时会提示当前累计在线与剩余所需在线时长。
- `/register` 注册 QQ 账号，注册成功立即登录。
- `/login` 登录已有账号。
- `/changepassword` 修改当前账号密码。
- `/setpassword <QQ账号>` 管理员重置账号密码；游戏内通过输入框填写新密码，避免明文密码进入聊天/服务器日志。
- `/deleteownaccount` 玩家注销当前账号，需要当前密码与确认菜单。
- `/deleteaccount <QQ账号/玩家3位ID/UUID/account:id> [confirm]` 管理员删除账号。
- `/accountqq <玩家3位ID/UUID/account:id/QQ账号>` 管理员查询玩家对应 QQ。
- 玩家连接时若游戏 UUID + USID 与上次登录记录一致，则自动登录。
- 未自动登录的玩家加入时不再自动弹出账号菜单，只提示已有账号可 `/login`，注册需满足在线时长后 `/captcha`。
- 手动登录不再需要验证码；手动注册前必须先通过 `/captcha` 获取注册验证码，并在弹窗中填写；验证码默认 5 分钟有效，成功或错误都会消耗。
- 登录失败限制按 UUID 与 IP 双维度记录；断开重连不会清空失败冷却，避免批量尝试密码通过重连绕过。
- IP 只作为最近登录记录保存，不作为自动登录条件。
- 登录后把 `PlayerData[player].id` 设置为 `account:<id>`，使信任等级默认进入 1 级基础状态。
- 登录/注册后触发信任等级变化事件与晋升检测。
- 为已登录玩家补充 `@authed` 权限组。

相关指令：

- `/account` / `/账号`
- `/captcha` / `/验证码` / `/authcode` / `/code`
- `/register` / `/注册`
- `/login` / `/登录`
- `/changepassword` / `/passwd` / `/改密` / `/修改密码`
- `/setpassword <QQ账号>` / `/resetpassword` / `/账号改密` / `/重置密码`
- `/deleteownaccount` / `/注销账号` / `/账号注销`
- `/deleteaccount <QQ账号/玩家3位ID/UUID/account:id> [confirm]` / `/delaccount` / `/账号删除` / `/删除账号`
- `/accountqq <玩家3位ID/UUID/account:id/QQ账号>` / `/qqof` / `/查qq` / `/查询qq` / `/账号查询`

重要依赖：

- `wayzer/mdtDatabase`
- `coreMindustry/menu`
- `coreMindustry/utilTextInput`
- `wayzer/user/accountPassword`
- `wayzer/user/accountIpGuard`
- `wayzer/user/trustLevel`
- `wayzer/user/trustPromotion`

备注：

- QQ 号当前限制为 `5-12` 位数字且唯一。
- 密码默认至少 `6` 位，最多 `64` 位，不允许空白字符。
- 注册验证码默认 4 位数字，有效期由 `注册验证码有效期(ms)` 配置控制，默认 5 分钟；获取时检查本次启动内存在线时长，不写入数据库，并在未满足时展示还需在线多久。
- 管理员玩家使用 `/setpassword` 时会弹出密码输入框与确认菜单；控制台使用时需要 `setpassword <QQ账号> <新密码> confirm`。
- 管理员玩家使用 `/deleteaccount` 时会弹出确认菜单；可通过 QQ、玩家 3 位 ID、完整 UUID 或 `account:<id>` 定位账号；控制台使用时需要 `confirm` 参数。
- 删除/注销账号会删除账号登录记录、设备绑定，并清空该账号主体 `account:<id>` 及已绑定游戏UUID主体下的业务数据。
- 同一游戏 UUID 已绑定到其他账号时，不允许直接切换账号。
- 同一账号已在线时，不允许重复登录。
- 登录/注册前会调用 `accountIpGuard`：登录已有账号不再受单IP多账号限制；注册会检查当前IP是否处于风险期。
- 详细规则见 `docs/account-system.md`。

---

### `mdtserver/config/scripts/wayzer/user/accountIpGuard.kts`

类型：新增功能脚本
职责：账号/IP防熊，降低换 UUID/USID 或小号刷赞、反复破坏的风险。

当前功能：

- 监听 `ConnectAsyncEvent`，在正式入服前统计同 IP 短时间内出现的游客身份、账号身份和 USID，但只用于排查，不再因单IP多身份直接拒绝入服；该阶段只做轻量记录，不再额外查询自动登录数据库。
- 允许单IP登录多个已有账号；登录已有账号和 UUID+USID 自动登录不受风险IP限制。
- 记录同 IP 最近一次入服身份；默认 24 小时内同 IP 换账号/UUID 入服时广播 `xxx进入了游戏，可能为 xxx 的小号`，同时显示上次同 IP 身份的名字、UUID 与短ID，只提示不拦截。
- 小号提示在 `PlayerJoin` 后延迟 `altBroadcastDelayMillis`（默认 3 秒）执行，等待账号自动登录状态稳定；同一 IP/前后身份组合 60 秒内只广播一次，避免重连或认证事件重复刷屏。
- 当玩家被 `ban.kts` 管理禁封，或已有封禁账号尝试进入时，自动把其当前/最近登录 IP 标记为风险IP，默认持续 24 小时。
- 经 `voteKick.kts` 投票踢出的短时禁封会先记录到同 IP 踢出计数；默认 24 小时内同 IP 累计 2 次投票踢出后再标记风险IP，避免单次误踢直接影响同 IP 游客。
- 风险IP期间：禁止注册新账号；未登录玩家会被强制分配为观察者并轻量禁言；登录已有账号后解除未登录态限制。
- 会记录由本脚本临时切入观察者的游客 UUID；管理员解除风险 IP、风险记录过期或玩家登录成功时，会释放本脚本造成的观战残留，避免风险期结束后游客卡在观察者队伍。
- 本地/内网 IP 默认忽略，方便测试环境本机调试。
- 旧版 `MdtIpAccountBindings` 只保留清理兼容，不再作为 `1IP=1账号` 硬限制。

相关指令：

- `/ipguard status`
- `/ipguard check <ip>`
- `/ipguard risk <ip> [原因]`
- `/ipguard unrisk <ip>`
- `/ipguard unblock <ip>`（`unrisk` 兼容别名）
- `/ipguard release <ip>`（风险已解除后，手动释放该 IP 的观战残留）
- `/ipguard unbind <ip>`（清理旧版IP绑定记录）

备注：

- 该脚本只负责 IP 风控逻辑；账号注册/登录入口仍在 `accountAuth.kts`。
- 普通入服小号记录/广播已迁到 IO 写入 + 游戏线程回调，避免玩家加入瞬间同步查库；若提示晚几秒出现属于预期。
- 风险记录和同IP辅助状态落在 `MdtSettings`，键包括 `account.ipRisk.index`、`account.ipRisk.<ip>`、`account.ipLast.<ip>`、`account.ipKick.<ip>`；服务器重启后仍会生效，到期后自动视为失效。
- 详细说明见 `docs/account-system.md`。

---

### `mdtserver/config/scripts/wayzer/user/accountGuestControl.kts`

类型：新增功能脚本
职责：未登录玩家强制观战的投票与管理控制。

当前功能：

- 默认不限制未登录玩家游玩。
- `/vote guestOb` 投票通过后，当天内未登录玩家会被强制分配为观察者。
- `/vote guestObOff` 投票通过后，解除当天未登录玩家强制观战。
- `/guestob [on|off|status]` 管理员查看/手动设置今日限制。
- 今日游客观战状态运行时走内存缓存，脚本启用时异步从 `MdtSettings` 加载；投票/管理切换后异步持久化 `account.guestForceObDate`，次日自动失效，避免投票资格与分队热路径同步查库。
- 游客观战投票参与资格只要求已登录，不再依赖当前单位是否死亡，避免换图/死亡导致投票资格波动；强制观战玩家的普通投票拦截由 `voteOb.kts` 的全局拒绝谓词处理。
- 会记录由本脚本切入观察者的游客 UUID；关闭今日游客观战或玩家登录后，只释放本脚本造成的观战残留，不会误解除安全风控、风险 IP、投票强制观战或玩家主动观战。

相关指令：

- `/vote guestOb`
- `/vote 游客观战`
- `/vote 未登录观战`
- `/vote guestObOff`
- `/vote 解除游客观战`
- `/vote 解除未登录观战`
- `/guestob [on|off|status]`

重要依赖：

- `wayzer/user/accountAuth`
- `wayzer/mdtDatabase`
- `wayzer/vote`
- `wayzer/map/betterTeam`

备注：

- 发起者可以是未登录游客；失败时按投票系统游客长冷却与同 IP 发起冷却处理。
- 当前只有已登录玩家可参与此投票。

---

### `mdtserver/config/scripts/wayzer/security/securityGuard.kts`

类型：新增安全风控脚本
职责：处理刷屏、菜单限速、短时间多连接/多身份、非菜单异常请求累计与临时 IP 封禁。

当前功能：

- 监听 `ConnectAsyncEvent`，检测单 IP 连接频率与短时间多 UUID/USID。
- 监听 `OnChat`，对同 IP 聊天/指令做限速。
- 通过 `coreMindustry/menu.lib.kt` 的 `IMenuOpenGuard` 对菜单打开做限速。
- 菜单风控已区分未绑定/已绑定：
  - 未绑定玩家仍按 IP 低阈值限制，默认 `8/5s`。
  - 已绑定玩家改为按玩家 UUID 计数并使用更高阈值，默认 `40/10s`，避免同 IP 误伤。
  - `/help`/指令类菜单对已绑定玩家再乘额外倍率，默认 `x3`，正常快速翻页通常不会被拦截。
  - 原生 admin/4+admin 不参与菜单限速处罚。
- 菜单限速处罚：首次提示，继续触发只踢出；不增加异常分，不封禁账号/IP。
- 非菜单类异常处罚阶梯：首次提示，第二次踢出，继续触发则临时封禁 IP，默认 24 小时。
- 异常累计会自动进入风控/增强风控模式；普通阈值默认 15 分，增强阈值默认 30 分；自动模式默认随机 20-60 分钟后解除，只写日志并私聊通知在线4级/admin。
- 普通风控默认只限制风控开启后新进入且不能自动登录的游客：将其临时分配为观察者，并丢弃普通聊天/大部分指令，只保留登录、注册、账号、帮助等必要入口；已在线游客不会被批量切观战。
- 增强风控模式下只允许已登录且可 UUID+USID 自动登录的玩家进服；已在服游客会被强制观战。
- 读取 `trafficMonitor` 的估算上行；当前默认不由上行自动进入安全风控，避免和 `serverPressure`/性能优化系统重复触发。
- IP 封禁、风控模式使用 `MdtSettings` 落盘，重启后仍可恢复。
- 内部记录安全风控造成的观战 UUID；风控解除/过期、玩家登录、`/security release` 或 `/security reset` 会尝试释放对应观战残留。
- 管理菜单 `/security` 可集中查看状态、切换普通/增强风控、解除风控、重置计数、释放安全风控观战游客、开关今日游客观战、查看封禁 IP 与观战来源速查。

相关指令：

- `/security status`
- `/security` / `/security menu`
- `/security spectators`
- `/security mode normal|guard|enhanced [分钟] [原因]`
- `/security banip <ip> [分钟] [原因]`
- `/security unbanip <ip>`
- `/security check <ip/3位ID>`
- `/security reset`
- `/security release`

备注：

- 详细说明见 `docs/security-guard.md`。
- 该脚本是“游戏层风控”，不能替代云防火墙/高防/系统防火墙。
- 与 `accountIpGuard.kts` 分工：`accountIpGuard` 处理账号被踢/被封后的未登录限制；`securityGuard` 处理实时刷屏/连接异常和 IP 封禁，刷菜单只做提示/踢出限速。
- 2026-06-11：移除菜单打开过快对异常分/风控分的影响；菜单限速命中后改为先提示、继续触发仅踢出，不再因刷菜单封禁账号或 IP。
- 2026-05-24：普通安全风控默认不再强制游客观战，避免与“今日游客强制观战”状态混淆；增强风控仍会强制未登录玩家观战/限制进入。上行超预算自动进入安全风控默认关闭，交由服务器压力/性能优化系统处理。
- 2026-05-24：`/security` 已加入 `/help` 的“管理指令”分区；安全风控菜单按钮增加分行，避免所有按钮挤在同一行。

---

### `mdtserver/config/scripts/wayzer/security/forceObAutoCleaner.kts`

类型：新增管理/资源保护脚本
职责：在线人数较高时，自动清理一部分“已登录且被投票/管理强制观战”的玩家，释放服务器玩家位和同步资源。

依赖：

- `wayzer/cmds/voteOb`
- `wayzer/map/betterTeam`
- `wayzer/user/trustLevel`

默认配置：

- `enabledDefault=true`：默认启用。
- `playerThreshold=14`：在线人数达到或超过 14 触发检查。
- `targetPlayerCount=12`：按超过目标人数的数量计算本轮清理需求。
- `maxCleanPerRun=2`：每轮最多踢出 2 名候选玩家。
- `checkIntervalMillis=60000`：每 60 秒检查一次。
- `warnDelayMillis=30000`：清理前 30 秒提醒在线管理。
- `sameIpCleanupEnabled=true`：达到或超过触发阈值时同时清理同IP多账号玩家。
- `sameIpProtectAdmins=true`：同IP多账号清理默认保护信任4级/admin玩家。

边界：

- 只把“已登录/已绑定 + 在观察者队伍 + `voteOb.limitPlayers` 有强制观战记录”的在线玩家作为候选。
- 不清理未绑定游客、正常玩家、主动 `/ob` 观察者、今日游客观战、风险 IP、安全风控造成的游客观战。
- 当在线人数达到或超过阈值时，还会检查已登录玩家中的同IP多账号：每个IP保留一个账号/主体（优先保留信任等级更高、非观战、admin），踢出其余同IP多账号；默认不踢信任4级/admin。
- 清理动作是“踢出当前在线玩家”，不移除 `voteOb.limitPlayers` 的强制观战记录；如果该玩家重新进入，原强制观战处罚仍会继续生效。
- 自动清理前会私聊通知 4级/admin 在线管理，并写日志。管理可用 `/forceobclean off` 关闭。

指令：

- `/forceobclean status`：查看状态、阈值、强制观战候选、同IP多账号候选和待执行倒计时。
- `/forceobclean on|off`：启用/关闭自动清理。
- `/forceobclean run [数量]`：立即手动清理指定数量强制观战候选；同时按当前配置清理同IP多账号候选。

权限：

- `wayzer.admin.forceObClean`，默认注册给 `@admin`；4级玩家因信任等级桥接拥有该权限。

---

### `mdtserver/config/scripts/wayzer/user/trustVoteVeto.kts`

类型：新增功能脚本
职责：为 3+级/4级 玩家提供当前投票的一票否决权。

当前功能：

- `/veto [原因]`：否决当前正在进行的投票。
- 直接按信任等级判断：达到 `3+` 或 `4` 才能使用。
- 不依赖普通权限组，避免 3+ 不是 admin 时无法使用。

相关指令：

- `/veto [原因]`
- `/否决 [原因]`
- `/一票否决 [原因]`
- `/一票否决权 [原因]`

重要依赖：

- `wayzer/vote`
- `wayzer/user/trustLevel`

备注：

- 实际结束投票的底层接口放在 `wayzer/vote.lib.kt` 的 `VoteEvent.vetoCurrent(...)` / `VoteEvent.vetoBy(...)`。
- 本脚本只负责等级判断与命令入口。

---

### `mdtserver/config/scripts/wayzer/user/ext/whiteList.kts`

类型：修改既有脚本 / 兼容入口
职责：保留旧脚本路径，避免旧引用加载失败。

当前功能：

- 旧的 mindustry.top 外部统一登录、RPC/KV 登录缓存逻辑已移除。
- 本文件只依赖并引入新的账号系统：
  - `wayzer/user/accountAuth.kts`
  - `wayzer/user/accountGuestControl.kts`
- 不再注册 `/login`，避免与新账号系统冲突。

备注：

- 后续不要在本文件重新堆账号业务逻辑；账号相关逻辑应继续放在 `accountAuth` / `accountGuestControl` / 存储层中。

---

### `mdtserver/config/scripts/wayzer/ext/ipRegion.kts`

类型：新增功能脚本
职责：提供 IP 到国家/地区的查询能力，并注册玩家占位符。

当前功能：

- 读取 `config/scripts/data/ip2region.xdb`。
- 同时兼容 `config/scripts/data/ip2region_v4.xdb`。
- 如果数据库文件不存在，默认会尝试自动下载 `ip2region_v4.xdb`；下载失败时脚本不会卸载，地区回退为“未知地区”。
- 本地/内网 IP 显示为“本地网络”。
- 欢迎消息使用“中国玩家显示省份，海外玩家显示国家”的规则。
- 注册玩家占位符：
  - `{player.regionName}`
  - `{player.countryName}`
- `/ipregion [玩家名/三位ID]`：管理员查询玩家 IP 地区。
- `/ipregion status|reload|ip <IP>`：查看状态、重载数据库或直接查 IP。

备注：

- 参考柠檬开源插件 `lemon/ext/ip2region.kts`，但改为缺少 xdb 时软回退，避免启动失败。
- 详细说明见 `docs/region-welcome-language.md`。

---

### `mdtserver/config/scripts/wayzer/ext/welcomeMsg.kts`

类型：修改既有欢迎脚本
职责：玩家进服欢迎信息。

本项目改动：

- 依赖 `wayzer/ext/ipRegion.kts`，欢迎消息中加入地区。
- 默认欢迎广播模板改为“欢迎来自 xxx 的 xxx 加入服务器”。
- `customWelcome` 默认启用，同时会关闭原版连接提示，避免重复刷屏。

备注：

- 如果缺少 `ip2region.xdb`，地区会显示“未知地区”或“本地网络”，不会影响进服。

---

### `mdtserver/config/scripts/wayzer/ext/regionAutoLang.kts`

类型：新增功能脚本
职责：根据地区/客户端 locale 自动选择服务器菜单语言。

当前功能：

- 中国地区或中文客户端默认使用 `zh`。
- 非中国地区或非中文客户端默认使用 `en`。
- 默认不覆盖玩家手动 `/lang` 设置。

备注：

- 柠檬参考项目中的 `autoTranslate.kts` 是脚本文本自动补翻译工具，不是聊天实时翻译；本轮未默认接入外部机器翻译接口。
- 如后续需要自动补全 `lang.ini`，建议单独加可开关脚本并限制并发/频率。

---

### `mdtserver/config/scripts/wayzer/ext/playerInfoTripleTap.kts`

类型：修改/新增功能脚本
职责：玩家信息弹窗入口。

当前功能：

- 监听玩家点击地图事件。
- 玩家连续点击同一格子 `2` 次后，查找该位置 `3x3` 范围内最近玩家。
- 双击链路加入约 `1.2s` 超时；超过该时间的同坐标点击会重新计数，降低正常建造/操作误触发信息面板的概率。
- 找到玩家时打开标准菜单弹窗。
- 提供 `/playerinfo` 指令作为双击逻辑的兜底入口；不填目标时弹出在线玩家列表。
- 玩家资料面板加入短 TTL 内存缓存：
- 玩家进入服务器时会预热缓存赞踩、认可、MDC、称号、信任等级/资历等级等需要查数据库的资料。
  - 在线期间默认 `60s` 兜底刷新一次；正常情况下由数据变更事件主动刷新缓存。
  - 玩家离线时立即卸载缓存。
  - 同一玩家重复打开信息面板默认限频 `500ms`。
  - 赞踩、认可、MDC、等级、称号等数据变更事件会主动重取在线玩家资料并更新缓存。
  - 强制观战、禁言状态不进入资料缓存，打开面板时即时读取，避免管理按钮显示滞后。
- 弹窗展示：
  - 名字
  - 称号
  - 等级
  - 被赞数
  - 被踩数
  - 给别人的赞
  - 踩别人的数
  - 有效被踩
  - 被认可数
  - 认可他人数
  - 当前MDC
  - 累计MDC
- 弹窗操作：
  - `赞ta`
  - `踩ta`
  - `认可ta`，仅查看者等级 `>=2` 时显示
  - 查看自己时显示若干入口按钮，按钮通过快速输入指令跳转：
    - `打开称号面板` -> `/title`
    - `打开技能面板` -> `/skills`
    - `打开商店列表` -> `/shop`
    - `打开成就系统` -> `/achievements`
    - `打开投票页面` -> `/vote`
    - `打开/help页面` -> `/help`
  - 查看自己时可点击 `随机变换形态`
  - 查看 `0` 级玩家时，可按查看者等级显示投票踢出、投票强制观战、直接强制观战
  - `3+` 及以上玩家查看低于 `3+` 的目标时，可直接强制观战/解除强制观战
  - `3+` 及以上玩家查看低于 `3+` 的目标时，可禁言/解除禁言
  - `返回`

相关指令：

- `/playerinfo [玩家id/3位id/#游戏id]`
- `/pinfo`
- `/玩家信息`

重要依赖：

- `coreMindustry/menu`
- `coreMindustry/utilTextInput`
- `wayzer/ext/playerReputation`
- `wayzer/ext/playerRecognition`
- `wayzer/ext/playerRandomForm`
- `wayzer/ext/playerMute`
- `wayzer/mdtDatabase`
- `wayzer/user/trustPoint`
- `wayzer/user/trustPromotion`
- `wayzer/user/trustLevel`
- `wayzer/user/achievement`
- `wayzer/user/shopList`
- `wayzer/cmds/voteKick`
- `wayzer/cmds/voteOb`

备注：

- 文件名仍保留 `TripleTap`，但实际触发逻辑已改为“双击同一坐标”。
- 如果 `3x3` 范围内没有玩家，则不显示弹窗。
- 打开自己时隐藏赞、踩、认可按钮，但底层赞踩/认可脚本仍保留禁止自赞/自认可校验。
- `禁言` 和 `随机变换形态` 的实际逻辑已拆分到独立脚本，菜单脚本只负责显示入口与调用接口。
- 自己菜单里的功能入口统一采用“快速输入指令”实现；后续系统补齐对应指令后，只需要把菜单按钮指向目标指令。

---

### `mdtserver/config/scripts/wayzer/user/achievement.kts`

类型：新增功能脚本
职责：成就系统、成就菜单、成就奖励、特殊成就管理、自定义成就管理。

当前功能：

- 提供 `/achievements` 成就面板。
- 显示普通成就、特殊成就和隐藏成就占位。
- 未完成隐藏成就只显示 `[gold]****（隐藏成就）`，不显示名字和奖励。
- 已完成成就的奖励位置显示 `[green][已完成]`。
- 点击已完成成就可全服展示。
- 成就完成时提示玩家：`你完成了xxxx！奖励:xxxx`。
- 隐藏成就完成时额外全服广播。
- 4级/管理员可从 `/achadmin` 或成就菜单进入成就管理菜单。
- 自定义成就定义持久化到 `MdtCustomAchievements`，完成记录仍写入 `MdtPlayerAchievements`。
- 自定义成就支持菜单化新增/删除/启用/隐藏、修改名称、条件、MDC奖励、称号奖励。
- 自定义成就第一版使用单条件，内置条件包含获赞/点赞/踩/认可/发帖/在线小时/当前MDC/累计MDC/信任等级/资历等级/特定日期或小时在线登录。
- 自定义成就定义有 60 秒缓存，管理修改会立即失效；检测时通过一次 `AchievementStatsSnapshot` 合并读取统计，内置统计类成就与自定义成就共用快照，避免每条成就单独查库。
- 奖励支持：
  - MDC。
  - 称号奖励。
- 特殊成就不参与自动检测，只能通过管理指令发放。
- 新增普通成就 `first_forum_post` / `首次发贴`：首次发布帖子，奖励 `5` MDC。
- 监听 `ForumPostCreatedEvent`，发帖成功后触发一次成就检测。

相关指令：

- `/achievements`
- `/achievement`
- `/成就`
- `/成就系统`
- `/achadmin [menu]`
- `/achadmin <玩家id/3位id> [list|check|grant|revoke] [成就code]`
- `/achievementadmin`
- `/成就管理`

重要依赖：

- `coreMindustry/menu`
- `wayzer/user/trustLevel`
- `wayzer/user/trustPoint`
- `wayzer/ext/playerReputation`
- `wayzer/ext/playerRecognition`
- `wayzer/user/playerTitle`
- `wayzer/user/forumPosts`
- `wayzer/lib/TrustSystemEvents.kt`

持久化数据：

- 已切换到数据库，主要表见 `docs/database-system.md`：
  - `MdtPlayerAchievements`

备注：

- 管理指令权限为 `wayzer.admin.achievement`，默认注册给 `@admin`。
- `/achadmin revoke` 只撤销完成记录，不回滚MDC或称号奖励。
- 已移除旧“自定义称号券”奖励/显示/管理逻辑；自定义称号改由称号商店直接售卖。
- 详细预设成就和扩展边界见 `docs/achievement-system.md`。

---

### `mdtserver/config/scripts/wayzer/user/shopList.kts`

类型：新增功能脚本
职责：商店列表入口框架。

当前功能：

- 提供 `/shop` 商店列表。
- 当前没有开放商店时显示“当前暂无开放商店”。
- 提供后续商店系统接入接口：
  - `registerShop(code, name, description, command)`
  - `unregisterShop(code)`
  - `listShops()`

相关指令：

- `/shop`
- `/商店`
- `/shops`

重要依赖：

- `coreMindustry/menu`

备注：

- “称号商店”“技能商店”等脚本可依赖本脚本，并调用 `registerShop` 把入口注册到 `/shop`。
- 本脚本只负责商店入口列表，不负责扣MDC、发货或商品管理。

---

### `mdtserver/config/scripts/wayzer/user/shopCore.kts`

类型：新增功能脚本
职责：通用商店核心校验、扣MDC、购买统计。

当前功能：

- 提供商店共用的等级代码标准化与要求文本生成。
- 提供购买前校验：
  - 当前MDC是否足够。
  - 信任等级是否达到商品要求。
  - 被认可数是否达到商品要求。
- 提供 `completeShopPurchase(...)`：
  - 扣除MDC。
  - 写入 `MdtShopPurchaseStats` 购买次数。
  - 触发 `ShopPurchaseEvent`。
- 提供 `shopPurchaseCount(...)` 给后续成就/排行/限购逻辑调用。

重要依赖：

- `wayzer/mdtDatabase`
- `wayzer/user/trustPoint`
- `wayzer/user/trustLevel`
- `wayzer/ext/playerRecognition`
- `wayzer/lib/TrustSystemEvents.kt`

持久化数据：

- `MdtShopPurchaseStats`

备注：

- 本脚本不放具体商品效果；称号、技能等具体发放逻辑应由各自商店脚本处理。

---

### `mdtserver/config/scripts/wayzer/user/titleShop.kts`

类型：新增功能脚本
职责：称号商店，负责称号商品展示、购买与商品管理指令。

当前功能：

- 启用时注册到 `/shop`，入口名为“称号商店”。
- 提供 `/titleshop` 直接打开称号商店。
- 商品从数据库 `MdtTitleShopItems` 读取，并按商品 ID 排序。
- 首次启用且商品表为空时写入预设商品 `1..8`。
- 支持固定称号商品：
  - 购买成功后调用 `playerTitle.grantTitle` 发放称号。
  - 玩家已拥有该固定称号时不重复扣MDC。
- 支持自定义称号商品：
  - 管理配置使用 `custom:<长度>`，如 `custom:7`。
  - 玩家输入合法自定义称号后才扣MDC并发放。
- 提供管理指令动态维护商品。

相关指令：

- `/titleshop`
- `/称号商店`
- `/titleshopadmin list`
- `/titleshopadmin set <商品id> <称号内容|custom:长度> <售价> <等级要求> [认可要求]`
- `/titleshopadmin del <商品id>`
- `/titleshopadmin seed`
- `/tshopadmin`
- `/称号商店管理`

重要依赖：

- `wayzer/user/shopList`
- `wayzer/user/shopCore`
- `wayzer/user/playerTitle`
- `coreMindustry/menu`
- `coreMindustry/utilTextInput`

持久化数据：

- `MdtTitleShopItems`
- `MdtShopPurchaseStats`

备注：

- 管理指令权限为 `wayzer.admin.titleShop`，默认注册给 `@admin`；因此 `4` 级玩家在线时也可使用。
- 当前称号内容参数按普通指令空格分割；如后续需要带空格的商品名，可再扩展为输入框管理流程。
- 原“自定义称号券”逻辑已移除，自定义称号改为通过称号商店商品直接购买。
- 详细商品配置与预设见 `docs/shop-system.md`。

---

### `mdtserver/config/scripts/wayzer/user/suffix.kts`

类型：修改既有脚本
职责：玩家名字后缀标记、客户端类型/电脑端标识、管理/VIP后缀。

当前功能：

- 保留客户端类型后缀、电脑端后缀、权限组后缀。
- 新增 `/suffixmark`（别名 `/adminsuffix`、`/后缀标记`、`/管理标记`）：
  - `/suffixmark hide`：隐藏自己的管理后缀标记。
  - `/suffixmark clear`：恢复默认后缀逻辑。
  - `/suffixmark set <标记>`：自定义自己的后缀标记。
  - `/suffixmark <玩家/3位ID> hide|clear|set <标记>`：为目标玩家设置。
- 自定义标记允许颜色标签/十六进制颜色；原始长度限制 64，去颜色后可见字符限制 16。
- 空字符串作为“隐藏”覆盖值保存，因此不会继续显示默认管理图标。

权限：

- `/suffixmark` 使用 `suffix.admin`，默认注册给 `@admin`；信任4级玩家在线时也可用。

---

### `mdtserver/config/scripts/wayzer/user/playerTitle.kts`

类型：新增功能脚本
职责：正式称号系统、称号佩戴菜单、称号前缀显示。

当前功能：

- 提供独立 `/title` 称号面板。
- 称号面板支持：
  - 点击佩戴已拥有称号。
  - 点击“不佩戴头衔”清除手动佩戴状态。
  - 固定每页 `5` 个称号项。
  - 自动计算总页数。
  - 翻页按钮与页码按钮始终位于固定位置。
- 将未绑定/游客标识纳入正式称号层：
  - 未绑定玩家默认显示 `[游客]`。
  - 该显示不再由白名单脚本单独拼接。
- 正式称号与随机形态头衔互相独立：
  - 正式称号使用 `nameExt` 的 `prefix.1title`。
  - 随机形态继续使用 `prefix.2randomFormTitle`。
  - 最终名字效果为：`[正式称号][随机形态头衔]玩家名...`
- 提供后续系统接入接口：
  - `registerTitleDefinition(code, displayName, description)`
  - `grantTitle(uid, code, displayName, description)`
  - `revokeTitle(uid, code)`
  - `equipTitle(player, code)`
  - `equipTitle(uid, code, player)`
  - `clearEquippedTitle(player)`
  - `playerTitleName(uid, player)`

相关指令：

- `/title`
- `/称号`
- `/titles`
- `/playertitle <玩家id/3位id> [list|grant|revoke|equip|clear] [code] [显示名]`
- `/titleadmin`
- `/givetitle`
- `/称号管理`
- `/titledef <code> <显示名>`
- `/deftitle`
- `/定义称号`

重要依赖：

- `coreMindustry/menu`
- `wayzer/user/nameExt`
- `wayzer/lib/TrustSystemEvents.kt`

持久化数据：

- 已切换到数据库，主要表见 `docs/database-system.md`：
  - `MdtTitleDefinitions`
  - `MdtPlayerTitles`
  - `MdtEquippedTitles`

备注：

- 管理指令权限为 `wayzer.admin.playerTitle`，默认注册给 `@admin`；因此 `4` 级玩家在线时也可使用。
- `guest` 是系统保留称号代码，用于游客/未绑定显示，不能通过管理指令授予或撤销。
- 后续成就系统或称号商店只需要调用 `grantTitle` 发放称号，玩家即可在 `/title` 面板佩戴。
- `/playertitle equip` 已支持离线玩家，只要目标已拥有该称号即可直接写入佩戴状态；玩家下次上线会显示。
- `/titledef` 用于提前定义或修改称号显示名，避免同一称号代码后续发放时显示名不一致。
- 称号代码最长 `64` 字符；称号显示名会去掉换行并限制在 `80` 字符内，避免名字前缀过长或破坏菜单显示。
- 玩家正式称号前缀已加入运行期缓存：已拥有称号、当前佩戴称号和称号定义会懒加载并在授予/撤销/佩戴/清除/定义修改时失效或更新，避免 `nameExt` 周期刷新名字时反复读取数据库造成主线程顿挫。
- 游客默认称号直接返回 `[游客]`，不会再为了游客前缀查询称号数据库。
- `nameExt` 现在会优先使用账号连接阶段记录的玩家原名，并清理已拼接进真实名缓存的前置 `[xxx]` 头衔和残缺颜色标签，降低换图失败/脚本重载后出现 `[游客][游客]`、`[white` 残片或半截名字的概率。

---

### `mdtserver/config/scripts/wayzer/ext/playerRandomForm.kts`

类型：新增功能脚本
职责：随机形态、形态聊天改写、临时强制头衔与形态MDC奖励。

当前功能：

- 提供 `随机变换形态` 能力：
  - 无特殊形态时随机变为 `猫娘`、`chatgpt` 或 `嘉豪`。
  - 已有特殊形态时再次使用会恢复原状态。
- 每种形态会强制临时头衔：
  - 猫娘：`[pink]猫娘[white]`
  - chatgpt：`[white]chatgpt`
  - 嘉豪：`[gold]嘉豪[white]`
- 每种形态会改写普通聊天内容，并支持同一形态内随机抽取不同预设话术。
- 每次变换形态时播放大型扩散特效。
- 导出 `setRandomForm` / `setCatgirlForm`，供其它脚本强制设置指定形态；默认不发每日MDC奖励，玩家仍可再次使用 `/randomform` 恢复。
- 猫娘形态持续出现粉色小特效。
- 嘉豪形态持续出现金色小特效。
- chatgpt 形态无持续小特效。
- 每名玩家每天仅第一次变换形态获得MDC：
  - 猫娘：`+15`
  - chatgpt：`+10`
  - 嘉豪：`+5`
- 每日形态MDC领取按玩家所有关联主体 id 检查；领取成功后会给所有关联 id 标记今日已领取，避免登录/未登录主体切换导致重复领奖。

相关指令：

- `/randomform`
- `/随机形态`
- `/变换形态`

重要依赖：

- `wayzer/user/trustPoint`
- `wayzer/user/nameExt`

持久化数据：

- 已切换到数据库：`MdtRandomForms`。

备注：

- 当前形态头衔通过 `nameExt` 的 `prefix.2randomFormTitle` 实现，属于临时显示层，不写入正式称号系统。
- 正式称号由 `wayzer/user/playerTitle.kts` 的 `prefix.1title` 提供；两者会按前缀序号依次拼接。
- 聊天改写通过 Mindustry `ChatFilter` 实现，普通聊天与会经过过滤器的聊天指令会生效；如果某个脚本自行绕过过滤器直接发消息，则不会被本脚本改写。
- 当前形态读取已加入运行期缓存；持续小特效循环与聊天改写不会再每次读取数据库，只在首次读取或切换/清除形态时同步数据库与缓存。

---

### `mdtserver/config/scripts/wayzer/ext/playerMute.kts`

类型：新增功能脚本
职责：玩家禁言与解除禁言。

当前功能：

- 记录被禁言玩家。
- 被禁言玩家发送普通聊天或投票/聊天类入口时会被拦截。
- 被拦截时提示：`你已被禁言，可联系3+级玩家/管理进行解除`。
- 提供接口供玩家信息/交互面板调用：
  - `isMuted(target)`
  - `mutePlayer(target, reason, operator)`
  - `unmutePlayer(target, operator)`
- 禁言/解除禁言后会发出 `PlayerMuteChangedEvent`，供后续可能需要监听禁言状态变化的系统使用。

相关指令：

- `/playermute <玩家id/3位id/#游戏id> [理由]`
- `/pmute`
- `/mute`
- `/禁言`
- `/playerunmute <玩家id/3位id/#游戏id>`
- `/punmute`
- `/unmute`
- `/解除禁言`

重要依赖：

- `coreMindustry/utilNextChat`

持久化数据：

- 已切换到数据库：`MdtMutedPlayers`。

备注：

- 禁言拦截使用聊天包拦截而不是单纯 `ChatFilter`，目的是避免被禁言玩家通过 `y/n/赞成/反对` 等聊天投票词继续参与投票。
- 普通非聊天类指令默认不拦截，方便玩家继续使用帮助、信息面板等基础功能。
- 玩家信息面板中，`3+` 及以上玩家只能对低于 `3+` 的目标显示禁言/解禁入口；管理指令权限注册给 `@admin`。

---

### `mdtserver/config/scripts/wayzer/user/ext/chatPing.kts`

类型：修改既有聊天提醒脚本
职责：在聊天中使用 `@三位短ID` 或 `@all` 提醒对应玩家。

本项目改动：

- 移除聊天过滤器中的 `runBlocking { hasPermission(...) }`，避免玩家发 `@all` 时在聊天过滤链路同步等待权限系统，造成主线程停顿。
- `@all` 权限改为运行期缓存；玩家加入时异步刷新，缓存未命中或未授权时再次异步刷新。
- `wayzer.user.ext.chatPing.pingAll` 默认注册给 `@admin`，因此 4级/admin 可使用 `@all`。

备注：

- 如果玩家刚被提升权限，第一次 `@all` 可能只触发异步刷新，下一次才生效；这是为了避免聊天线程同步卡顿。

---

### `mdtserver/config/scripts/wayzer/cmds/voteKick.kts`

类型：修改既有脚本
职责：投票踢出玩家。

本项目改动：

- 保留原有 `/vote kick` 投票踢人逻辑。
- 新增可被其它脚本直接调用的 `startKickVote(starter, target, reason)` 接口。
- 玩家信息/交互面板可通过该接口发起投票踢出。
- 投票踢出最终仍走 `wayzer/user/ban.kts` 的短时禁封，并计入 `accountIpGuard.kts` 的同IP踢出窗口；默认同 IP 24 小时内累计 2 次后触发风险IP标记。

重要依赖：

- `wayzer/vote`
- `wayzer/user/ban`
- `coreMindustry/utilTextInput`
- `coreMindustry/menu`

备注：

- 目标如果拥有 `wayzer.admin.skipKick` 权限，投票成功后也不会被踢出。
- 菜单调用时会先弹出理由输入框。

---

### `mdtserver/config/scripts/wayzer/user/ext/skills.kts`

类型：修改既有脚本
职责：技能总入口、技能分类菜单与通用/2级/3级/管理员技能指令。

当前功能：

- `/skill`、`/skills`、`/技能` 打开技能分类菜单。
- 技能分类：`通用技能`、`2级技能`、`3级技能`、`特殊/商店技能`、`管理员技能`。
- 按玩家资历等级隐藏不满足条件的分类：资历2/3/4分别对应2级、3级、管理员技能；信任4级/已登录admin 默认视为资历4级。
- 菜单中点击技能会快速执行对应 `/skill <code>` 指令；通用/2级/3级/特殊商店分类的按钮只显示技能名、效果和规则，不再显示指令行。
- 通用技能：`clearSelf` 紫砂、`kill` 自爆、`cola` 紫薇、`heal` 自疗、`copper` 生锈的铜、`summonpoly` 召唤poly、`illuminator` 照明器、`basicdefense` 初级预制防线、`extinguish` 灭火、`disarm` 缴械。
- 2级技能：`shield` 护盾、`health` 范围治愈、`fullheal` 完全痊愈、`fortune` 查看今日运势、`monoMother` 递归mono、`lowwallKiller` 墙壁粉碎者、`sourcelottery` 欧皇物品源、`coreshard` 读品、`coreZone` 3x3核心区、`flying` 飞起、`landing` 坠机、`runfaster` 你跑不过我你信不信、`rocket` 空对地导弹、`decisiveSquad`/`anvilSquad`/`hammerSquad` 三个投放小队。
- 3级技能：`blitz` 骇人空袭、`antiarmor` 反装甲炮击、`pddCut` 拼夕夕砍一刀、`disaster` 天灾、`redLightGreenLight` 123木头人、`gaokao` 参加高考、`firetruck` 消防车、`standarddefense` 标准预制防线、`missileVolley` 导弹齐射、`nuke` 核弹打击、`refreshskills` 刷新技能、`tietie` 贴贴。
- 管理员技能：`examtime` 考试时间、`source` 物品源、`ecore` E星核心、`invincible` 无敌、`freeSkillCost` 全场技能买单、`killallunits` 击杀所有单位、`infinitefire` 无限火力promax、`wallkillerpro` 墙体粉碎者pro、`daoshengyi` 道生一....、`powersource` 电力源、`floodon/floodoff`、`lordon/lordoff`、`addnoskill/removenoskill`。

重要规则：

- 通用与2级技能受 PVP 和 `@noSkills` 限制。
- 3级技能不受 `@noSkills` 限制，但仍受 PVP 限制。
- 管理员技能不受 PVP、`@noSkills`、MDC消耗和冷却限制。
- 2026-06-11：技能资格从信任等级切换为资历等级；信任等级继续负责帖子、管理权限等信任边界。
- `freeSkillCost` 开启后，本局技能使用消耗免除；`ResetEvent` 自动关闭。
- 2026-05-28 修复：
  - 管理员技能权限统一走资历4判断；已登录的 Mindustry 原生 admin 会通过信任等级桥接显示为 `4+admin` 并视为资历4，未登录原生 admin 不直接绕过账号认证。
  - `wallkillerpro` 现在同时清理玩家周围 5x5 的玩家墙体 `Wall` 与地图天然墙 `StaticWall`；此前只处理有建筑实体的玩家墙体，打天然墙/地形墙会显示释放但没有实际破墙。

相关指令：

- `/skill`
- `/skills`
- `/技能`
- `/skill common`
- `/skill level2`
- `/skill level3`
- `/skill shop`
- `/skill admin`
- `/skill <技能code>`

重要依赖：

- `coreMindustry/menu`
- `wayzer/user/seniorityLevel`
- `wayzer/user/trustPoint`
- `wayzer/user/ext/skills.lib.kt`
- `wayzer/user/playerTitle`：高考状元、今日运势大吉等技能称号授予。
- `wayzer/map/funRuleModes`：管理员玩法规则技能。

备注：

- 玩家信息/交互面板的 `打开技能面板` 按钮通过快速输入 `/skills` 跳转到该菜单。
- 详细分类与扩展边界见 `docs/skill-system.md`.

---

### `mdtserver/config/scripts/wayzer/user/ext/skills.lib.kt`

类型：修改既有共享库
职责：技能命令容器、技能预检查、冷却、菜单注册表与技能消耗控制。

当前功能：

- `SkillCommands`：`/skill <子命令>` 的技能命令容器。
- `SkillPrecheck`：客户端、死亡状态、`@noSkills` 检查。
- `SkillPrecheckIgnoreNoSkills`：客户端、死亡状态检查，但不检查 `@noSkills`，供管理员/特殊技能等显式绕过场景使用。
- `SkillPrecheckLevel3`：3级技能预检查；绕过普通 `@noSkills`，但会响应纯净模式 `@pureNoLevel3Skills`。
- `SkillNoPvp`：PVP 禁用检查。
- `SkillCooldown`：技能冷却；`-1` 表示一局一次；支持全局重置和按玩家重置。
- `SkillMenuCategory` / `SkillMenuEntry` / `SkillMenuRegistry`：技能菜单分类与技能入口注册。
- `SkillCostManager`：本局技能使用消耗免除开关，由管理员技能“全场技能买单”使用。
- `skillBody`：技能命令体封装；技能成功执行后自动写入冷却。

备注：

- `SkillCooldown` 在 `ResetEvent` 由 `skills.kts` 统一重置。
- 后续脚本若新增技能菜单项，应注册到 `SkillMenuRegistry`，并在脚本禁用时注销。
- 新技能如存在 MDC 使用消耗，应兼容 `SkillCostManager.freeCostSponsor()`。

---

### `mdtserver/config/scripts/wayzer/user/skillShop.kts`

类型：新增功能脚本
职责：技能商店与特殊/商店技能。

当前功能：

- 启用时注册到 `/shop`，入口名为“技能商店”。
- 提供 `/skillshop`、`/技能商店`。
- 购买技能时校验MDC、资历等级与认可数，并通过 `shopCore` 写入购买统计。
- 已购买技能写入 `MdtPlayerSkills`。
- 已购买且满足当前使用条件的技能会显示在 `/skill` -> `特殊/商店技能` 分类中。
- 技能商店的等级要求也使用资历等级，避免技能购买/使用继续依赖信任等级。
- 当前商品/技能：
  - `radar`：雷达，开雾300秒，使用4 MDC，冷却600秒。
  - `fluid`：随机液体，生成随机2x2液体，使用4 MDC，冷却300秒。
  - `randomore`：随机矿，生成随机2x2矿物，使用4 MDC，冷却300秒。
  - `wallkiller`：粉碎墙壁，粉碎3x3墙壁，使用4 MDC，冷却300秒。
  - `corezone4`：核心区4x4，使用6 MDC，冷却1200秒。
  - `betray`：叛变，随机策反5个非本队单位，使用10 MDC，冷却300秒。
  - `banme`：BAN自己5分钟，使用0 MDC，获得5 MDC并踢出自己5分钟；使用端不受死亡/PVP/noskill/一局一次限制。
  - `reptile`：爬爬盲盒，使用2 MDC，冷却200秒。
  - `teleportation`：传送10个随机单位到自己位置，使用4 MDC，冷却300秒。
  - `rain`：唤雨，下雨60秒，使用0 MDC，冷却240秒。
  - `kickmebutoct`：踢自己送oct，使用10 MDC，一局一次。
  - `imcute`：我很可爱，使用0 MDC，一局一次，给自己+2 MDC。
  - `lottery`：抽奖，使用5 MDC，一局一次，随机获得 MDC。
  - `randomunit`：随机单位，使用10 MDC，一局一次。
  - `ultirandom`：终极随机，使用66 MDC，冷却20秒，随机触发强力效果/跳波/全员猫娘/禁用技能/扣MDC/砸哇路多/涩图禁令/开放科技/反客为主等效果；不再随机开启编辑器模式或无限火力promax。
  - `missilestorm`：导弹风暴，购买100 MDC，使用20 MDC，冷却300秒，5秒内每0.5秒召唤5个随机创伤导弹单位。
  - `fishonlyyou`：此生只属鱼你，购买10 MDC、要求资历2级，使用0 MDC且不受 `@noSkills` 限制，默认 PVP 禁用；召唤专属 `risso` 鱼鱼，其他玩家无法附身，同一玩家同时只保留一只。
  - `missileburst`：导弹连射，购买100 MDC，使用10 MDC，从当前单位朝向连续发射40个 `scathe-missile-surge-split` 小导弹。
  - `facephd`：面对面读博，购买200 MDC，不收固定使用费；发起者选择赌注并额外支付10%手续费，目标同意后双方各暂扣赌注，目标MDC不足会取消，胜者拿走双方赌注之和；PVP赌指定胜队，非PVP赌本局正常完成/玩家方胜利或失败/换图，沙盒/编辑器禁用。

相关指令：

- `/skillshop`
- `/技能商店`
- `/skill radar`
- `/skill fluid`
- `/skill randomore`
- `/skill wallkiller`
- `/skill corezone4`
- `/skill betray`
- `/skill banme`
- `/skill reptile`
- `/skill teleportation`
- `/skill rain`
- `/skill kickmebutoct`
- `/skill imcute`
- `/skill lottery`
- `/skill randomunit`
- `/skill ultirandom`
- `/skill missilestorm`
- `/skill fishonlyyou`

重要依赖：

- `wayzer/user/shopList`
- `wayzer/user/shopCore`
- `wayzer/user/ext/skills`
- `wayzer/user/trustPoint`
- `wayzer/user/trustLevel`
- `wayzer/ext/playerRecognition`
- `coreMindustry/menu`
- `wayzer/map/funRuleModes`

持久化数据：

- `MdtPlayerSkills`
- `MdtShopPurchaseStats`

备注：

- 当前技能商店商品是脚本内预设；如果后续需要管理员动态改商品，再新增独立商品表与管理指令。
- 2级技能已有 `coreZone`，商店中的 4x4 版本使用 `corezone4`，避免命令 code 冲突。
- 新增商品从 `10` 开始编号，用于避免覆盖旧商品 `1-9` 和已有购买数据。

---

### `mdtserver/config/scripts/wayzer/map/funRuleModes.kts`

类型：新增共享玩法工具脚本
职责：为技能、投票和管理指令提供临时玩法规则操作。

当前功能：

- `killAllUnits()`：击杀当前所有单位。
- `damageAllBuildings(percent)`：按当前血量百分比伤害所有建筑。
- `enableStandardInfiniteFire(durationMillis, operator)`：标准无限火力，只临时开启单位/队伍无限弹药兼容字段，并为当前队伍炮塔补足开火所需弹药/液体/供电，不开启 `fillItems`/`infiniteResources`，不填充核心资源，不提高伤害倍率。`enableInfiniteFire(durationMillis, operator)` 仍表示无限火力promax，会提高单位/方块伤害倍率；同步开启 TeamRule `cheat`/`fillItems`/`infiniteResources`，解除单位工厂启动延迟，并每 250ms 为当前队伍的物品炮塔、液体炮塔、单位工厂、重构厂、单位组装厂及其常规消耗器补足弹药/物品/液体/电力。若当前服务端分支存在 `unitAmmo` / `TeamRule.infiniteAmmo` 字段，则通过反射兼容开启单位无限弹药，结束后恢复；`durationMillis <= 0` 表示长期启用，需调用 `disableInfiniteFire(...)` 手动恢复。
- `enableSandbox(durationMillis, operator)`：编辑器模式，除全局无限资源/瞬间建造/editor 外还会给当前玩家/核心队伍设置 `cheat`、`fillItems`、`infiniteResources` 与高建造速度；若存在 `TeamRule.infiniteAmmo` 字段则反射开启并在结束后恢复；`durationMillis <= 0` 表示长期启用，需调用 `disableSandbox(...)` 手动恢复。
- `removeNoSkillsTag()`：移除当前地图规则 `@noSkills` 标签；这是服务端技能预检查使用的标签，不向客户端热同步完整规则。
- `setReactorExplosions(enabled, operator)` / `reactorExplosionsEnabled()`：热同步当前地图 `Rules.reactorExplosions`，供投票开关反应堆爆炸使用。
- `setFloodMode(on/off)`：尝试启用/停用 `mapScript/tags/flood`。
- `setLordOfWarMode(on/off)`：尝试启用/停用 `mapScript/14668`。
- `currentMapScriptStatuses()` / `currentMapScriptStatusText()`：列出当前启用的 `mapScript/` 脚本，供 `/mapscripts` 和 `/mapinfo` 使用。
- `setMapScriptEnabled(raw, enabled)`：安全归一化并尝试加载/停用 `mapScript` 下脚本。

备注：

- Lord of War 是整图专用脚本，不是通用 tag；这里只提供热加载尝试，失败需查看日志。
- 2026-05-28 修复：`/unloadmapscript` / `floodoff` / `lordoff` 关闭地图脚本时会先记录当前已启用的公共脚本，以及不依赖目标地图脚本的其它地图脚本；停用目标后把误被 ScriptAgent 递归停用/卸载的公共脚本重新加载并启用，避免连带关闭 `coreMindustry/contentsTweaker` 等常驻依赖。
- 管理员技能 `floodon/floodoff`、`lordon/lordoff` 与 `/loadmapscript`、`/unloadmapscript` 共用 `setMapScriptEnabled`，不要另起一套地图脚本加载/关闭实现。
- 2026-05-28 修复：`removenoskill` 只移除服务端 `@noSkills` 标签，不再调用 `Call.setRules` 向所有客户端热同步完整规则；该标签只由服务端技能预检查读取，避免内容补丁/规则热同步在少数地图上触发全员断连。
- 2026-05-28 修复：当前 v158 服务端分支没有 `Rules.unitAmmo` 与 `TeamRule.infiniteAmmo` 字段，旧写法会导致 `funRuleModes.kts` 编译失败并连锁禁用技能/投票/地图脚本指令；现改为反射可选读写这些字段，字段不存在时跳过弹药逻辑。
- 2026-05-28 修复：无限火力不再只是倍率/规则字段；现在会给炮塔补弹药/液体并用 `cheat` 让电网满载，同时给工厂/重构厂/组装厂补物品和液体。热量炮塔的 `heatRequirement` 会临时置 0 并在结束或 ResetEvent 时恢复，避免无热源导致“无限火力”下仍无法开火。
- 2026-05-28 修复：KTS 脚本成员不能使用 `const val`，`infiniteFireMaintainIntervalMillis` 改为普通 `val`；否则会导致 `wayzer/map/funRuleModes` 加载失败，并连锁影响 `mapInfo`、`skills`、`gatherTp`、`voteFunRules`、`loadMapScriptCmd`、`skillShop` 等 7 个脚本。
- 当前服务端网络层只有 `Call.sound(...)` / `Call.soundAt(...)`，经 `TypeIO.writeSound` 写入的是 `Sounds.getSoundId(sound)` 的短整型 ID；因此可让客户端播放原版/双方都已注册的声音，不能向纯原版客户端下发任意自定义音频字节流。
- 无限火力与沙盒仍是规则级热修改；它们不重新加载地图，但现在会同步修改 TeamRule，实际效果比单纯 `Call.setRules` 更完整。

---

### `mdtserver/config/scripts/wayzer/map/loadMapScriptCmd.kts`

类型：新增管理指令脚本
职责：允许管理员手动尝试加载/关闭 `scripts/mapScript/<id>.kts`，并查看当前启用的地图脚本。

当前功能：

- `/loadmapscript <id或路径>`、`/加载地图脚本 <id或路径>`。
- 示例：`/loadmapscript 14668` -> `scripts/mapScript/14668.kts`。
- 示例：`/loadmapscript tags/flood` -> `scripts/mapScript/tags/flood.kts`。
- `/unloadmapscript <id或路径>`：手动关闭指定地图脚本/地图模式。
- `/mapscripts`：列出当前启用的 `mapScript/` 脚本。
- 只允许 `mapScript` 下相对路径，拒绝 `..`、绝对路径和异常字符。
- 关闭地图脚本走 `wayzer/map/funRuleModes.kts` 的统一安全关闭逻辑；若底层递归停用了 `ContentsTweaker` 等原本启用的公共脚本，会立即恢复。
- `/loadmapscript`、`/unloadmapscript`、`/mapscripts` 均已加入 `/help` 管理指令分区，方便管理员从菜单查看。

权限：

- `wayzer.map.loadMapScript`，默认 `@admin`。

---

### `mdtserver/config/scripts/wayzer/cmds/voteFunRules.kts`

类型：新增投票脚本
职责：把部分强力玩法规则操作开放为玩家投票。

当前功能：

- `/vote killunits`：投票击杀当前所有单位。
- `/vote infinitefire`：投票开启 120 秒标准无限火力（不填充核心资源/不提高伤害倍率）。
- `/vote infinitefirepromax`：投票开启 120 秒无限火力promax；发起者需要信任等级2级及以上。
- `/vote reactor <on|off|status>`：投票开启/关闭当前地图反应堆爆炸，或查看当前状态。
- 已加入 `/help` 投票指令分区。

---

### `mdtserver/config/scripts/wayzer/cmds/voteOb.kts`

类型：修改既有脚本
职责：投票强制观战与强制观战限制。

本项目改动：

- 保留原有投票强制观战与 `/forceOB` 管理指令。
- 新增供菜单调用的接口：
  - `isForceOb(target)`
  - `forceObInfo(target)`
  - `forceObPlayer(target, reason, operator)`
  - `releaseForceObPlayer(target)`
  - `startObVote(starter, target, reason)`
- 玩家信息/交互面板可通过这些接口发起投票观战、直接强制观战或解除强制观战。
- `forceObAutoCleaner.kts` 使用 `forceObInfo(target)` 获取强制观战原因与开始时间，用于筛选/排序清理候选。

重要依赖：

- `wayzer/cmds/voteKick`
- `wayzer/map/betterTeam`

持久化数据：

- `limitPlayers`

备注：

- 目标如果拥有 `wayzer.admin.skipKick` 权限，投票成功后也不会被强制观战。
- 直接强制观战会记录目标当前所有 `PlayerData.ids`，以减少换身份绕过限制的可能。

---

### `mdtserver/config/scripts/wayzer/ext/playerReputation.kts`

类型：新增功能脚本
职责：玩家赞/踩系统。

当前功能：

- 保存玩家收到的赞/踩总数。
- 保存玩家送出的赞/踩总数。
- 控制每日点赞/点踩额度。
- 禁止给自己点赞/点踩。
- 禁止同IP账号之间互相点赞，避免刷赞/MDC；点踩仍沿用原有每日限制。
- `0` 级玩家不可点赞/点踩。
- 点赞成功后通知点赞者与被赞者。
- 点踩成功后只通知点踩者。
- 提供管理指令查看/修改玩家被赞/被踩数量。
- `likePlayer` / `dislikePlayer` 会返回是否成功，供帖子系统在成功后记录“从帖子入口产生的作者赞踩点击”。

相关指令：

- `/reputation <玩家id/3位id> [赞|踩] [set|add] [数量]`
- `/rep`
- `/口碑`
- `/赞踩`

重要依赖：

- `wayzer/user/trustLevel`
- `wayzer/user/playerTitle`
- `wayzer/lib/TrustSystemEvents.kt`

持久化数据：

- 已切换到数据库：`MdtReputationStats`、`MdtReputationDaily`。

备注：

- 管理指令修改赞数/踩数只修正统计，不自动补发“收到赞”的MDC。
- 如果需要补MDC，请使用 `/trustpoint`。
- 文件名已从容易和投票系统混淆的 `playerVote` 方向改为 `playerReputation`。
- 玩家信息面板中的“称号”读取正式称号系统；随机形态头衔仅作为名字前缀的临时层显示。

---

### `mdtserver/config/scripts/wayzer/ext/playerRecognition.kts`

类型：新增功能脚本
职责：玩家认可系统。

当前功能：

- `2` 级及以上玩家可认可他人。
- 每名玩家每天只能认可一人一次。
- 每名玩家对同一个目标终身只能认可一次。
- 禁止认可自己。
- 禁止同IP账号之间互相认可，避免刷认可/MDC。
- 认可成功后通知认可者与被认可者。
- 触发认可相关事件，供MDC与晋升系统使用。

相关指令：

- `/recognize <玩家id/3位id>`
- `/认可 <玩家id/3位id>`

重要依赖：

- `wayzer/user/trustLevel`
- `wayzer/lib/TrustSystemEvents.kt`

持久化数据：

- 已切换到数据库：`MdtRecognitionStats`、`MdtRecognitionPairs`、`MdtRecognitionDaily`。

备注：

- “认可”是高于普通点赞的信任信号。
- 当前认可会影响有效被踩计算、MDC奖励、等级晋升条件。

---

### `mdtserver/config/scripts/wayzer/lib/TrustSystemEvents.kt`

类型：新增共享事件文件
职责：保存信任/口碑相关跨脚本事件类型。

当前事件：

- `ReputationChangedEvent`
- `PlayerLikedEvent`
- `RecognitionChangedEvent`
- `PlayerRecognizedEvent`
- `TrustPointChangedEvent`
- `TrustLevelLockChangedEvent`
- `PlayerTitleChangedEvent`
- `TrustLevelChangedEvent`
- `AchievementCompletedEvent`
- `ShopPurchaseEvent`
- `ForumPostCreatedEvent`

被使用脚本：

- `wayzer/ext/playerReputation.kts`
- `wayzer/ext/playerRecognition.kts`
- `wayzer/user/trustPoint.kts`
- `wayzer/user/trustPointReward.kts`
- `wayzer/user/trustPromotion.kts`
- `wayzer/user/trustLevel.kts`
- `wayzer/user/playerTitle.kts`
- `wayzer/user/achievement.kts`
- `wayzer/user/shopCore.kts`
- `wayzer/user/titleShop.kts`
- `wayzer/user/forumPosts.kts`

备注：

- 跨脚本共享事件放在 `.kt` 文件中，避免 `.kts` 之间直接引用事件类导致加载/编译问题。
- 新版 ScriptAgent/Kotlin 中，`emitAsync()` 需要在协程中调用；业务脚本中应使用 `launch { ... }` 包装。

---

### `mdtserver/config/scripts/wayzer/user/seniorityLevel.kts`

类型：新增功能脚本
职责：玩家累计在线时长与资历等级系统。

当前功能：

- 仅统计已登录账号主体的累计在线时长，内部按毫秒落库，指令以小时为单位显示/设置。
- 资历等级用于技能分类、技能商店技能购买/使用与管理员技能入口；信任等级仍控制帖子、管理权限、风控/处罚等信任边界。
- 自动资历条件：
  - 资历1级：在线 `1` 小时且累计MDC `100`。
  - 资历2级：在线 `16` 小时且累计MDC `600`。
  - 资历3级：在线 `64` 小时且累计MDC `2000`。
  - 资历4级：不自然晋升；信任4级/已登录admin 默认视为资历4级，也可由管理员手动设置。
- 监听MDC变化、信任等级变化、玩家进出与结算/重置事件，按批次检测资历晋升/调整；自动检测队列加锁并对单个玩家异常做隔离，另每5分钟对在线玩家做兜底全量复查，避免事件丢失或某次检测异常导致满足条件后不自动晋升。
- 支持资历锁，锁定后自动检测不再调整该玩家资历等级；信任4级/已登录admin 的有效资历4覆盖不写入资历表，避免取消信任4级后残留。

相关指令：

- `/seniority`、`/资历`、`/playtime`、`/在线时长`
- `/setseniority <uuid/3位id> <0|1|2|3|4>`：设置并锁定资历等级，防止下一轮自动检测覆盖；恢复自动资历用 `/lockseniority <玩家> off`。
- `/lockseniority <uuid/3位id> [on|off|toggle]`
- `/setplaytime <uuid/3位id> <小时>`
- `/addplaytime <uuid/3位id> <小时>`

持久化数据：

- `MdtSeniorityProfiles.level_code`
- `MdtSeniorityProfiles.level_locked`
- `MdtSeniorityProfiles.play_millis`

---

### `mdtserver/config/scripts/wayzer/user/trustLevel.kts`

类型：新增功能脚本
职责：信任等级系统。

当前等级：

- `0`
- `1`
- `2`
- `3`
- `3+`
- `4`

当前功能：

- 根据玩家状态返回信任等级。
- 已登录的在线 Mindustry admin 视为 `4` 级；未登录会话仍按游客处理。
- 关键安全边界：传入在线 `Player` 时，如果当前会话未完成账号认证，则一律按 `0` 级游客处理；不会因为该 UUID/UID 数据库里曾经有手动等级，或连接带有 Mindustry 原生 `admin` 标记，就继承脚本侧权限。
- 权限事件中会过滤未登录玩家的 `uuid`、`PlayerData.ids` 历史主体权限组与原生 `@admin` 组，避免 `Player.hasPermission` 默认携带的 UUID/admin 身份绕过账号登录态。
- 新增统一辅助函数：`isSessionAuthed(player)`、`hasTrustLevel(player, required)`、`isTrustAdmin(player)`，供技能、Wiki、帖子、安全等脚本复用，避免各脚本重复写等级判断。
- 支持通过指令设置玩家等级。
- `4` 级玩家可使用 `/setlevel`。
- 支持通过指令锁定/解除某名玩家的等级自动调整。
- 在线 `4` 级玩家会被加入 `@admin` 权限组，可使用注册给管理员组的管理指令。
- `/setlevel`、`/locklevel`、`/setadmin` 统一声明 `wayzer.admin.trustLevel` 管理权限，并在指令体内二次检查当前会话信任4级/admin，避免未登录 UUID/admin 身份绕过。

相关指令：

- `/setlevel <uuid/3位id> <0|1|2|3|3+|4>`
- `/locklevel <uuid/3位id> [on|off|toggle]`
- `/levellock`
- `/等级锁`
- `/锁等级`
- `/setadmin <在线玩家id/3位id/#id/名字> [on|off|toggle]`

持久化数据：

- 已切换到数据库：`MdtTrustProfiles.manual_level_code`、`MdtTrustProfiles.level_locked`。

重要备注：

- 当前“管理设置等级”和“自然晋升等级”共用同一个字段。
- `4` 级不会被自动降级。
- 非 `4` 且未锁定等级的玩家，在触发自动检查时可能被晋升或降级。
- 等级锁定开启后，晋升系统不会再控制该玩家等级；关闭后会通过 `TrustLevelLockChangedEvent` 触发下一次等级检测并恢复自动控制。
- 等级和等级锁定状态使用运行期缓存；`/setlevel`、晋升系统、`/locklevel` 修改时会同步更新缓存，减少权限判断、积分板变量或菜单判断反复读取数据库的主线程压力。
- 注意：需要判断在线玩家权限时，应优先调用 `hasTrustLevel(player, ...)` 或 `isTrustAdmin(player)`，不要只拿 UID 离线查等级，否则可能绕过“当前会话必须已登录”的边界。

---

### `mdtserver/config/scripts/wayzer/user/trustPoint.kts`

类型：新增功能脚本
职责：MDC系统。

当前功能：

- 保存玩家当前MDC。
- 保存玩家累计MDC。
- 提供增加、扣除、消费、设置当前值、设置累计值等接口。
- 提供玩家查询指令。
- MDC获得/失去时向在线玩家发送统一短提示，例如 `MDC-10（技能消耗）`；本局贡献结算保留原详细结算提示，不重复刷屏。
- 提供管理指令。

相关指令：

- `/points`
- `/积分`
- `/trustpoint <玩家id/3位id> [set|add|spend|totalSet|totalAdd] [数量]`
- `/tpoint`
- `/改积分`、`/改mdc`

持久化数据：

- 已切换到数据库：`MdtTrustProfiles.current_points`、`MdtTrustProfiles.total_points`。

备注：

- 正数增加会同时增加当前MDC与累计MDC。
- 消费/扣除只减少当前MDC，不减少累计MDC。
- 信任等级晋升条件目前同时使用当前MDC与累计MDC；资历等级的MDC条件使用累计MDC。

---

### `mdtserver/config/scripts/wayzer/user/trustPointReward.kts`

类型：新增功能脚本
职责：GG、赞、认可等非贡献结算 MDC 奖励发放。

当前奖励：

- 结算后发送 `gg`：
  - 本局时长至少 `5` 分钟。
  - 每局每人只奖励一次。
  - 奖励 `+3` MDC。
- 收到赞：
  - 奖励 `+5` MDC。
- 收到认可：
  - 奖励 `+25` MDC。

重要依赖：

- `wayzer/user/trustPoint`
- `wayzer/ext/playerReputation`
- `wayzer/ext/playerRecognition`
- `wayzer/lib/TrustSystemEvents.kt`

备注：

- 成就奖励已由 `wayzer/user/achievement.kts` 独立处理，不放在本脚本中。
- 每局贡献结算奖励已拆到 `wayzer/user/gameContributionReward.kts`，本脚本不再发固定 `+2` MDC，避免重复结算。
- 不足 `5` 分钟的局不会开启 `gg` 奖励窗口。
- GG/点赞/认可奖励写入后由 `trustPoint.kts` 的统一 MDC 变化提示显示，不再额外发送一条独立 GG 奖励消息。

---

### `mdtserver/config/scripts/wayzer/user/gameContributionReward.kts`

类型：新增功能脚本
职责：参考柠檬统计脚本，在每局结算时按玩家贡献发放 MDC。

当前规则：

- 统计口径：
  - 每秒记录在线时间。
  - 死亡或处于观察者队伍的时间按挂机/无效时间记录。
  - PVP 中记录玩家出战队伍，胜利方按活跃率给予额外分。
- 发放门槛：
  - 沙盒、编辑器、无限资源模式不发。
  - 本局游戏时长至少 `15` 分钟。
  - 个人在线时间超过 `60` 秒，且活跃时间至少 `60` 秒。
- 贡献分：
  - `score = 在线秒数 - 0.8 * 无效秒数 + PVP胜利加成`
  - `PVP胜利加成 = 600 * 活跃率`，最多等效 `600` 秒贡献。
- MDC：
  - `MDC = ceil(score * 12 / 3600)`，单局封顶 `48`。
  - 满活跃约每 `10` 分钟 `+2` MDC；满活跃 `1` 小时约 `+12` MDC。
  - PVP胜利最多约额外 `+1` MDC。

重要依赖：

- `wayzer/user/trustPoint`
- `wayzer/maps`
- `wayzer/map/betterTeam`

备注：

- 结算时会按 UID 合并同一账号/主体的多段记录，避免重连重复领奖。
- `GameOverEvent` 正常结算；若直接换图，也会在 `MapChangeEvent.Before` 尝试按中立结果结算一次。

---

### `mdtserver/config/scripts/wayzer/user/trustPromotion.kts`

类型：新增功能脚本
职责：信任等级晋升/降级检测。

当前功能：

- 计算有效被踩：
  - `max(0, 总被踩 + 最近7天被踩*2 - 被认可数*2 - 总被赞/20 - 最近7天被赞/3)`
  - `MdtReputationDaily` 默认保留最近 14 天，用于最近 7 天赞/踩统计；历史重复日计数行会在写入时合并。
- 根据赞、认可、MDC、有效被踩等条件计算目标等级。
- 等级变化时全局播报。
- 使用脏队列延迟检查，避免实时频繁计算。
- 每 5 分钟会兜底把当前在线玩家加入一次检测队列，避免事件丢失/脚本热重载后满足条件但未自动晋升，只能靠 `/trustcheck` 强制检查才升级。

相关指令：

- `/trustcheck [玩家id/3位id]`

触发检查：

- 赞/踩变化。
- 认可变化。
- MDC变化。
- 等级锁定状态变化。
- 玩家进服。
- 游戏结束。
- 地图重置。
- 脚本启用。

重要依赖：

- `wayzer/user/trustLevel`
- `wayzer/ext/playerReputation`
- `wayzer/ext/playerRecognition`
- `wayzer/user/trustPoint`
- `wayzer/lib/TrustSystemEvents.kt`

重要备注：

- `4` 级与已登录在线 admin 不参与自动降级。
- 已达到 `2` 级及以上的玩家，动态条件不足时最低只会降到 `2` 级。
- 被 `/locklevel` 锁定的玩家不参与自动晋升/降级；解锁时会触发一次后续检测。
- 当前自动晋升与管理设置仍共用等级字段，但已通过独立锁定表避免被晋升系统覆盖。

---

## 维护约定

后续新增功能时，请同步维护：

1. 本文档：记录脚本路径、职责、依赖和注意事项。
2. `docs/trust-system.md`：如果涉及信任/赞踩/认可/MDC指令或规则，更新具体规则。
3. `docs/database-system.md`：如果新增/重命名持久化表或改变存储边界，同步更新。
4. `docs/account-system.md`：如果调整注册/登录/密码/游客观战投票，必须同步更新。
5. 如果新增跨脚本事件，优先放入合适的 `.kt` 共享文件，避免 `.kts` 间事件类引用问题。
6. 如果新增管理指令，明确是否注册给 `@admin` 组，以及是否允许 `4` 级玩家使用。




---

### MDC转账/红包补充记录

相关脚本：

- `mdtserver/config/scripts/wayzer/user/trustPoint.kts`
- `mdtserver/config/scripts/wayzer/lib/MdtStorage.kt`
- `mdtserver/config/scripts/wayzer/user/accountAuth.kts`

本项目改动：

- MDC系统名称统一为 `MDC`，全名 `MDT DO Credit`。
- 新增玩家指令 `/points`、`/pay`、`/redpacket`、`/grab`、`/redpackets`。
- 转账与红包只影响当前MDC，不增加累计MDC，避免绕过晋升/累计统计口径。
- 红包数据持久化到 `MdtRedPackets` 与 `MdtRedPacketClaims`，红包 10 分钟过期，剩余MDC退回发包者。
- 账号删除/注销会同步清理账号主体及已绑定游戏UUID主体下的MDC、资历/在线时长、称号、赞踩、认可、成就、技能、随机形态、禁言、帖子/评论、红包记录等业务数据。

---

### 菜单缓存与懒加载补充记录（2026-05-21）

本轮目标：降低玩家频繁打开菜单时的数据库重复读取和主线程构建压力，尤其是 `/help`、排行榜、商店、成就、Wiki、帖子等非实时菜单。

涉及脚本：

- `mdtserver/config/scripts/coreMindustry/menu.kts`
  - `/help` 使用命令快照缓存与短TTL可见指令页缓存。
  - 对连续快速打开 `/help` 增加极短限频，避免反复构建完整指令列表拖慢TPS。
- `mdtserver/config/scripts/wayzer/user/leaderboard.kts`
  - 排行榜各分类加入短TTL缓存。
  - MDC、赞踩、认可、发帖事件触发时清理对应排行缓存。
- `mdtserver/config/scripts/wayzer/user/shopList.kts`
  - 商店入口列表使用内存快照，商店注册/卸载时失效。
- `mdtserver/config/scripts/wayzer/user/titleShop.kts`
  - 称号商店商品列表加入TTL缓存。
  - 管理新增、删除、覆盖预设商品后立即清理缓存。
- `mdtserver/config/scripts/wayzer/user/skillShop.kts`
  - 玩家已购买技能列表加入在线期TTL缓存。
  - 玩家进服预热，购买技能后失效。
- `mdtserver/config/scripts/wayzer/user/achievement.kts`
  - 玩家已完成成就集合加入TTL缓存。
  - 完成成就时同步写入缓存，撤销成就时失效。
- `mdtserver/config/scripts/wayzer/user/wiki.kts`
  - Wiki列表页、正文页、最近修改文本加入分页/TTL缓存。
  - 新增、修改、删除Wiki后清理相关缓存。
  - 仍保持“列表只加载当前页摘要，不预读正文”的边界。
- `mdtserver/config/scripts/wayzer/user/forumPosts.kts`
  - 帖子分区、统计、帖子列表页、帖子正文、评论页、锁定ID加入短TTL缓存。
  - 发帖、评论、修改、删除、置顶、锁定、作者赞踩计数、分区修改、自动清理后清理缓存。
  - 帖子列表仍按页读取，不做全量加载。
- `mdtserver/config/scripts/wayzer/user/playerTitle.kts`
  - 已有称号拥有/佩戴缓存，本轮未改动。
- `mdtserver/config/scripts/wayzer/ext/playerInfoTripleTap.kts`
  - 已有玩家资料缓存，本轮继续保留。

维护备注：

- Wiki/帖子这类后续会膨胀的数据只缓存当前页或单条内容，不缓存全库，避免内存随内容规模线性暴涨。
- 菜单缓存是性能优化层，不改变数据库作为最终真实数据源的原则。
- 所有会修改内容的入口需要记得清缓存；后续新增帖子/Wiki/商店/排行相关管理指令时，也要同步补充失效逻辑。

---

### 4级权限桥接修复记录（2026-05-22）

涉及脚本：

- `mdtserver/config/scripts/wayzer/user/trustLevel.kts`
  - 4级玩家在权限事件中会被加入 `@admin` 组。
  - 本轮补齐 `@admin` 组通配权限：`scriptAgent.*`、`coreLibrary.*`、`coreMindustry.*`、`wayzer.*`、`mapScript.*`。
  - 目的：避免某些管理指令只写了 `permission` / `requirePermission`，但没有单独 `PermissionApi.registerDefault(..., group = "@admin")`，导致4级玩家仍无法使用。
- `mdtserver/config/scripts/wayzer/maps.kts`
  - 显式把 `wayzer.maps.gameover` 注册给 `@admin`。
- `mdtserver/config/scripts/wayzer/cmds/clearUnit.kts`
  - 显式把清除单位指令权限注册给 `@admin`。

维护备注：

- 4级仍不等于 Mindustry 原生 `player.admin = true` 标志；而是在 ScriptAgent 权限层拥有 `@admin` 等效权限。
- 依赖原生 `player.admin` 布尔值的地方仍需要单独判断信任等级；依赖 `hasPermission(...)` / `permission` / `requirePermission(...)` 的指令会被这次桥接覆盖。

---

### 原生管理员开关与 `4+admin` 显示（2026-05-22）

涉及脚本：

- `mdtserver/config/scripts/wayzer/user/trustLevel.kts`
  - 新增 `/setadmin <在线玩家id/3位id/#id/名字> [on|off|toggle]`。
  - 4级/admin 可把在线玩家设置为 Mindustry 原生管理员，或取消其原生管理员。
  - 该操作只改变原生 `player.admin` / Mindustry admins 列表，不写入 MDT 信任等级字段。
  - 新增 `getTrustLevelDisplayCode(...)`：已登录原生 admin 在线时显示 `4+admin`，内部逻辑等级按 `4` 处理。
  - `{player.trustLevel}` 占位符现在显示带 admin 标记的等级；新增 `{player.trustLevelCode}` 作为不含原生 admin 标记的原始信任等级代码。
- `mdtserver/config/scripts/wayzer/ext/playerReputation.kts`
  - 玩家信息面板读取等级显示时改用 `getTrustLevelDisplayCode(...)`，因此已登录原生 admin 显示为 `4+admin`。
- `mdtserver/config/scripts/coreMindustry/menu.kts`
  - 管理帮助分区加入 `/setadmin`。

维护备注：

- 不要把 `4+admin` 直接写入数据库手动等级字段；数据库仍只保存 `0/1/2/3/3+/4`。
- 等级排序/额度逻辑已兼容 `4+admin`，按 `4` 级处理。

---

### 投票创建存档（2026-05-24）

涉及脚本：

- `mdtserver/config/scripts/wayzer/cmds/voteSave.kts`
  - 新增 `/vote save [存档ID] [备注]`，玩家可通过投票创建当前游戏存档。
  - 投票存档槽固定为 `106-110`；不填存档ID时优先使用空槽/损坏槽，都存在时覆盖最旧投票存档。
  - 默认成功创建后有 5 分钟写盘冷却，避免频繁创建存档。
- `mdtserver/config/scripts/wayzer/map/autoSave.kts`
  - `/slots` 现在同时显示自动存档 `100-105` 与投票存档 `106-110`。
  - 提示 `/vote rollback <存档ID>` 回档与 `/vote save [存档ID] [备注]` 创建存档。
- `mdtserver/config/scripts/coreMindustry/menu.kts`
  - `/help` → “投票指令”加入“投票创建存档”入口。

维护备注：

- `111` 是 `MapManager` 使用的临时回档槽，不要把投票存档范围扩展到 `111`。
- 存档写入仍需要在游戏进行中执行；如果地图自身存在不能序列化的逻辑变量，写盘失败会记录到服务端日志。

---

### 逻辑绘图/显示器方块开关（2026-05-24）

涉及脚本：

- `mdtserver/config/scripts/wayzer/map/logicDrawGuard.kts`
  - 新增 `/logicdraw status|on|off`，用于管理画布、逻辑显示器、tile logic display 等绘图/显示方块；2026-07-01 追加 `roundoff|roundon|roundclear`，用于仅当前局临时覆盖。
  - 2026-07-01 追加 `/blockban ban|unban|status <方块ID>` 与 `/blockunban <方块ID>`，用于本局单独禁用/解禁某个建筑方块。
  - 关闭时向当前 `state.rules.bannedBlocks` 加入受控方块，并通过 `Call.setRules` 同步客户端。
  - 状态落盘到 `MdtSettings`：`map.logicDraw.enabled`。
  - 地图加载后会重新应用策略；如果地图自身原本禁用了这些方块，执行 `/logicdraw on` 时会尊重地图原规则，不强行解除地图自带禁用。
- `mdtserver/config/scripts/coreMindustry/menu.kts`
  - 管理帮助分区加入 `/logicdraw` 与 `/blockban`。

维护备注：

- 该脚本服务于服务器列表审核的内容安全要求：避免 NSFW 显示器/画布图、辱骂性绘图等。
- 当前受控方块：`canvas`、`large-canvas`、`logic-display`、`large-logic-display`、`tile-logic-display`。
- 默认 `defaultLogicDrawEnabled=true`，即不主动改变原玩法；需要默认禁用时，可调整脚本配置或执行 `/logicdraw off`。

---

### 第一轮静态 review 修复记录（2026-06-12）

涉及脚本：

- `mdtserver/config/scripts/wayzer/vote.lib.kt`
  - 修复快速成功判断在“有效投票全为中立/待定”时可能出现 0 赞成也成功的边界；最低通过需求强制至少 1 票赞成。
  - 继续保留“同 IP 最后一次投票为准”的计票机制，并复核游客失败冷却与 IP 维度冷却入口。
- `mdtserver/config/scripts/wayzer/cmds/voteKick.kts`
  - 在线目标解析补全：支持完整 UUID、账号主体 UID、短 ID、`#游戏ID` 与名字。
  - 在创建投票前拦截管理员/受保护目标，避免先消耗一次投票再在投票通过后失败。
- `mdtserver/config/scripts/wayzer/cmds/voteOb.kts`
  - 强制观战状态按玩家所有历史主体 ID 检查与释放，避免账号/UUID 切换后残留。
  - 与投票踢出一致，创建投票前拦截管理员/受保护目标。
- `mdtserver/config/scripts/wayzer/reGrief/syncThrottle.kts`
  - 复用 `ReusableByteOutStream` 时改用 `DataOutputStream.flush()`，不再关闭底层流。
  - 复核同步限制期间隐藏实体列表、单位生成/销毁触发额外同步与离开玩家缓存清理，避免限制同步后重复发送不必要项或漏发单位销毁。
- `mdtserver/config/scripts/wayzer/reGrief/trafficMonitor.kts`
  - `PacketSerializer` 与复用 `ByteBuffer` 进入同一把锁，避免网络事件并发时互相覆盖 position。
  - 包大小序列化失败日志加入同类限频，避免异常包持续刷日志拖慢服务端。
- `mdtserver/config/scripts/wayzer/user/ext/skills.lib.kt`
  - 技能前置检查在 `dead()` 之外显式检查当前单位是否为空/死亡，降低玩家单位瞬时为空导致 NPE 的风险。
  - `SkillCooldown.checkCoolDown` 清理已过期冷却项，避免长期在线/大量 UUID 下冷却表持续膨胀。
  - `syncTile` 写完快照后使用 `flush()` 再发送。
- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
  - 多个直接 `player.unit()` 的技能改为空值保护并给玩家提示，包括自杀/自爆/治疗/缴械、二级状态技能、飞起/坠机/导弹、管理员无敌等。
  - 调整“读品”技能判定顺序：脚下已有方块时先返回，不扣除 MDC。
  - 复核三级/管理员技能的 noskill/PVP/资历边界；神权菜单仍仅按信任4级开放。
- `mdtserver/config/scripts/wayzer/user/skillShop.kts`
  - 商店技能前置检查同样显式检查当前单位是否为空/死亡。
  - 只有商品确实要求认可数时才查询认可数据，减少打开商店/技能菜单时的数据库读取。
  - 玩家离开时失效已购买技能缓存，避免在线缓存超出生命周期。
  - 粉碎墙壁、爬爬盲盒、传送等依赖单位的商店技能补空值保护。
- `mdtserver/config/scripts/wayzer/user/accountAuth.kts`
  - 注册验证码“本次启动累计在线 1 小时”缓存加入上限与离线 UUID 淘汰，避免批量 UUID 长时间运行后内存无界增长。
  - 登录失败冷却仍按 UUID + IP 维度保留，不在离线时清空。
- `mdtserver/config/scripts/wayzer/ext/playerInfoTripleTap.kts`
  - `/playerinfo` 目标解析补全完整 UUID、主体 UID 与短 ID，提升管理员和玩家定位目标的可靠性。
  - 继续保留离线 80 名最近玩家面板与在线资料缓存；玩家离开时清理资料缓存，避免面板缓存泄漏。
- `mdtserver/config/scripts/wayzer/security/securityGuard.kts`
  - `/banip` 在线目标解析补全完整 UUID、主体 UID 与短 ID。
  - 菜单打开过快仍只提示/踢出，不计异常分，不触发账号/IP封禁。
- `mdtserver/config/scripts/wayzer/user/trustLevel.kts`
  - `/setadmin` 在线目标解析补全完整 UUID、主体 UID 与短 ID。
  - 未登录会话继续剥离历史主体与 `@admin` 权限组；4级已登录玩家继续桥接到 `@admin`。
- `mdtserver/config/scripts/mapScript/15450.kts`
  - TankWars 玩家离开后的队伍清理等待循环由忙循环改为 `delay(1_000L)`，避免 30 秒高频空转。
  - 复核击杀奖励、HUD 主循环、升级与支援炮台放置逻辑，关键 `unit()` 路径已具备空值兜底。

维护备注：

- 本轮 review 只处理明确的 bug / 权限边界 / 性能浪费 / NPE 风险；未擅自改玩法数值、技能平衡或菜单风格方向。
- 后续若运行日志出现脚本加载错误，优先检查本节涉及的空值保护与新增解析分支；若是地图脚本专属玩法异常，应按具体地图脚本单独 review。

---

### `coreMindustry/variables` 单位弹药变量兼容修复（2026-06-13）

涉及脚本：

- `mdtserver/config/scripts/coreMindustry/variables.kts`
  - 修复当前 v158 服务端分支加载失败：`Unresolved reference: unitAmmo`。
  - 原变量 `{unit.ammo}` 直接读取 `state.rules.unitAmmo`；当前服务端的 `Rules` 已没有该字段。
  - 改为通过反射可选读取 `Rules.unitAmmo`，字段不存在时按旧逻辑的“未启用单位弹药”处理，返回 `{unit.maxAmmo}`。
  - 如果未来分支重新提供 `unitAmmo` 与单位 `ammo` 字段，则优先读取真实单位弹药；读不到单位弹药字段时兜底为护盾值，保持旧脚本兼容。

维护备注：

- 与 `wayzer/map/funRuleModes.kts` 中对 `unitAmmo` / `TeamRule.infiniteAmmo` 的可选反射策略保持一致，避免不同 Mindustry 分支字段差异导致核心变量脚本连锁加载失败。

---

### 洪水/Lord 脚本热加载崩溃排查与兼容修复（2026-06-14）

涉及脚本：

- `mdtserver/config/scripts/mapScript/tags/flood.kts`
  - 排查 `C:\Users\qw114\Desktop\other\新建 文本文档.txt` 后，时间线显示 `mapScript/tags/flood` 已在 00:27:01 手动停止，随后 00:27:20 手动启用 `mapScript/14668`，崩溃发生在 `14668` 内容补丁警告之后。
  - 因此本次 `PowerGraph.getTotalBatteryCapacity` 中 `battery.block.consPower == null` 的 NPE 更像是运行中热加载整图内容补丁造成的电网/方块状态不一致，而不是 flood 运行循环直接崩溃。
  - `onDisable` 现在会清理本轮 flood 生成的洪水视觉方块与运行态缓存，降低管理员临时 `floodon/floodoff` 后残留方块影响后续脚本的概率。
- `mdtserver/config/scripts/mapScript/14668.kts`
  - 修正部分 ContentPatcher 旧键名/JSON 问题：`microProcessor`、`logicProcessor`、`hyperProcessor`、`shockMine` 改为当前内容 ID 写法；移除当前分支不存在的 `interplanetary-accelerator.launching` 字段；修复 `quell` 片段漏逗号/尾逗号。
  - 日志中还出现 `avoid use yield() in Dispatchers.game, use nextTick instead`，已将该脚本内 `Dispatchers.game` 相关等待点从 `yield()` 改为 `nextTick()`，避免同类警告刷屏。
  - 仍需关注未来日志中可能残留的兼容警告，例如 `quell.weapons.0.bullet.spawnUnit` 不能按“新内容”实例化等；这些不是本次 PowerGraph 崩溃的直接栈顶，但后续若 Lord 地图异常应继续 review。
- `mdtserver/config/scripts/wayzer/map/funRuleModes.kts`
  - 仍保留管理员手动启用/停用 `mapScript/14668` 的能力；是否运行中热加载由管理员自行判断。
- `mdtserver/config/scripts/wayzer/user/ext/skills.kts`
  - 管理员技能 `lordon/lordoff` 保持可用，分别尝试启用/停用 `mapScript/14668`。

维护备注：

- 这次日志更符合“管理误操作/运行中热加载不适配整图脚本”而不是 flood 核心玩法逻辑崩溃；不再对管理指令或管理员技能做硬限制。
- 后续若手动测试 Lord of War，可继续使用 `/loadmapscript 14668` 或 `/skill lordon`；若再次出现 PowerGraph 或 ContentPatcher 异常，再按新日志继续排查。

---

### 资源站换图网络健壮性优化（2026-06-14）

涉及脚本：

- `mdtserver/config/scripts/wayzer/map/resourceHelper.kts`
  - 已对照 `C:\Users\qw114\Downloads\Mindustry-resource-4-nuxt3\Mindustry-resource-4-nuxt3` 前端源码：
    - `app/utils/const.ts` 中 `API_BASE = "https://api.mindustry.top/"`。
    - `app/backendApi/maps/index.ts` 中地图列表/详情/下载分别使用 `/api/maps/list`、`/api/maps/<id>.json`、`/api/maps/<id>.msav`，前端再映射到 `api.mindustry.top`。
    - 该仓库主要是 Nuxt 前端，不包含资源站后端实现或源站直连 IP，因此不能从仓库中得到绕过 Cloudflare 的源站地址。
  - 当前资源站接口默认使用 `https://api.mindustry.top`，不是 `https://mindustry.top` 根域；根域无解析不会直接影响脚本默认配置，但 `api.mindustry.top` 与 `www.mindustry.top` 同样走 Cloudflare，国内 VPS 到该线路仍可能间歇超时。
  - 原 `httpGet` 只设置 `readTimeout = 3000ms`，没有设置 `connectTimeout`；当机房到 Cloudflare/DNS/IPv6 出现黑洞或握手卡住时，`/maps`、`/host <资源站ID>`、投票换图可能长时间等待或失败。
  - 改为 `HttpURLConnection`，新增可配置连接/读取超时与重试间隔：
    - `httpConnectTimeoutMillis = 8000`
    - `httpReadTimeoutMillis = 15000`
    - `httpRetryDelayMillis = 1500`
  - 增加 `User-Agent`，并在非 2xx HTTP 状态时抛出包含状态码的错误，便于从日志区分 DNS/连接超时/HTTP 错误。
  - 已移除前一版加入的磁盘地图缓存，不再写入 `resource-site-cache`，避免额外占用空间。
  - 新增本机代理兜底配置与管理指令：
    - `resourceProxyEnabled = false`，默认仍直连资源站。
    - `resourceProxyHost = 127.0.0.1`
    - `resourceProxyPort = 7890`
    - `resourceProxyType = HTTP`，也支持 `SOCKS`。
    - `/resourceproxy status` 查看状态。
    - `/resourceproxy set 7890 [http|socks]` 设置本机代理端口并启用。
    - `/resourceproxy on/off` 启用/关闭代理。
    - `/resourceproxy host 127.0.0.1` 设置代理地址。
    - `/resourceproxy test` 测试当前直连/代理模式能否访问资源站接口。
- `mdtserver/config/scripts/coreMindustry/menu.kts`
  - `/help` 管理指令分区加入 `/resourceproxy`，并把管理菜单可见性判断补上 `wayzer.map.resourceProxy`。
  - `/help` 管理指令分区加入 `/adaptiveplayerlimit`，并把管理菜单可见性判断补上 `wayzer.map.adaptivePlayerLimit`。
  - 复查根指令归类：`/team` 补入管理指令分区，旧 `/votekick` 补入投票指令分区。
  - 修正 ScriptAgent 高级菜单项：脚本扫描/列表/加载/热重载/启停/卸载/配置/权限/变量查询均改为正确的 `/ScriptAgent ...` 子指令入口，避免原先按 `/enable`、`/config` 等不存在的根指令检查而不显示。
- `mdtserver/config/scripts/wayzer/map/betterTeam.kts`
  - `/team` 列队伍和切队伍移除地图 `@banTeam` 标签限制；该标签只继续影响自动分队。
- `mdtserver/config/scripts/wayzer/map/adaptivePlayerLimit.kts`
  - 新增自适应人数上限：接近满员（默认距离上限2人内）且最近5分钟无上行压力、平均上行低于阈值时每轮扩容；回收保留同等缓冲防止扩容后立刻降回；原生 `playerLimit` 同步为动态上限，不保留管理员插队槽。

维护备注：

- 插件换图接口走 `https://api.mindustry.top`，不是浏览器网页 `https://www.mindustry.top/map`；网页能打开不等价于 VPS 上的 Java 进程能直连接口。
- 如果 VPS 上挂 Clash/V2Ray，可开启本机代理让插件只对资源站请求走 `127.0.0.1:<端口>`；例如 Clash mixed-port 常见 `7890` 用 HTTP，V2Ray SOCKS 常见 `10808` 可用 `socks`。
- 若 VPS 经常 `api.mindustry.top` 解析/连接失败，仍建议在启动参数加入 `-Djava.net.preferIPv4Stack=true`，并将系统 DNS 改为服务商本地 DNS、`223.5.5.5` 或 `119.29.29.29`。

## 2026-06-22：单位杂交与玩法文档

- 新增文档 `docs/hybrid-system-design.md`，记录杂交技能、Buff 遗传、品质与特殊效果规则；继续保留 `docs/hybrid-unit-catalog.md` 作为单位分类表。
- `wayzer/user/ext/skills.kts` 新增 `/skill hybrid <目标>`（别名 `/skill 杂交`、`/skill breed`）：
  - 目标玩家需弹窗同意；拒绝/超时取消，不扣费、不进入成功冷却。
  - 单位杂交消耗 6 MDC；资历3级杂交菜单入口，PVP 与 `@noSkills` 禁用。
  - 发起者单位为父、目标单位为母；核心机/导弹禁止作为父母，`renale`/`latum` 只能同种杂交。
  - 按父母单位分类与 T 级生成子单位，类别不同则 60% 继承父类别、40% 继承母类别；Erekir 空军有低概率生出导弹。
  - 父母分别独立 20%-50% 随机死亡概率；50% 概率从父或母继承已有可识别 Buff；60% 概率触发品质效果；20% 概率触发抖擞精神/基因突变特殊效果。
- `docs/skill-system.md` 已补充“杂交”技能条目。

## 2026-06-22：基因杂交第一版与炮塔白名单

- 新增 `docs/advanced-hybrid-turrets.md`：整理基因杂交炮塔白名单、分批开放建议、同步策略与测试记录口径。
- `wayzer/user/ext/skills.kts` 的 `/skill hybrid` 调整为 3级资历“杂交系统”菜单：
  - 单位杂交：6 MDC，复用单体子单位玩法，不修改 CP。
  - 基因杂交：25 MDC，20% 玩法失败概率；仅当前地图有效，使用 `$hybrid-*` 临时 CP，不写入地图 tag。
  - 基因杂交发起者必须是普通单位；目标可为普通单位模板或玩家控制的白名单炮塔；炮塔/建筑不能作为发起者。
  - 当前地图内同一发起者单位类型最多获得 1 个单位基因和 1 个炮塔基因；换图/Reset 后记录与临时 CP 清空。
  - CP 应用失败会尝试回滚并回补 MDC；成功后广播提示“新生成单位生效”，并建议客户端显示异常时 `/sync` 或重进。
- 第一批炮塔模板：`duo`、`scatter`、`hail`、`arc`、`lancer`、`salvo`。
- 第一批单位基因模板：`dagger`、`flare`、`fortress`、`nova`、`pulsar`、`quasar`、`poly`、`mega`、`stell`、`locus`。


## 2026-06-22：杂交菜单与基因杂交模板测试修正

- `/skill` 主菜单新增独立 `[pink]杂交菜单` 顶层入口，资历3级可见；移除“2级技能”分类页内的“杂交系统”按钮，避免分类位置混乱。
- 基因杂交武器模板补充 `recoil=0` 与显式 `recoilTime`，修复新增炮塔/武器贴图在实体世界中因未初始化 recoilTime 而向后快速漂移的问题。
- “新星修复”基因杂交模板改为同时追加修复场能力、中心修复武器与 `canHeal=true`，提高修复能力在新生成单位上的可见性与可用性。


## 2026-06-22：基因杂交改为运行时真实对象抽取

- 基因杂交不再使用逐单位/逐炮塔手写模板，改为从当前已加载的 `UnitType.weapons`、`UnitType.abilities` 或玩家控制炮塔的实际弹药中随机抽取一个基因。
- 因为抽取来源是当前运行时对象，地图 CP 修改过的单位武器、能力与炮塔弹药也会被纳入基因杂交候选。
- 新增保守序列化器：只写入可安全表达为 CP 的字段，跳过贴图、音效、特效、运行时函数、控制器等不适合动态复制的字段；无法序列化时拒绝该目标并回补 MDC。
- 基因杂交发起者/目标单位限制收紧为只排除核心机与导弹单位，不再要求出现在单位杂交单位分类表中。


## 2026-06-22：基因杂交运行时序列化补丁修复

- 根据 `last.log`，基因杂交 CP 未生效的直接原因是运行时序列化把 `despawnUnit=null` 等空字段写进补丁后，又经 `contentsTweaker.addPatch` 的文本预处理把 `null` 变成字符串 `"null"`，导致 DataPatcher 报 `No unit found with name 'null'`，武器没有真正追加。
- 修复：运行时序列化器现在跳过空字段；基因杂交临时 CP 也改为直接写入 `contentPatches` 并触发 `ContentPatchLoadEvent`，避免 `contentsTweaker.addPatch` 对合法 JSON 的二次文本替换破坏 `null/true/false`。
- 新增生效校验：基因杂交应用后会检查目标 `UnitType.weapons/abilities` 数量是否实际增加；未增加则回滚 CP 并让调用方回补 MDC，避免日志只警告但玩家看到“成功”实际无效。

## 2026-06-22：基因杂交同步与现有单位刷新修复

- 定位：`/sync` 会走 `Call.worldDataBegin + Vars.netServer.sendWorldData` 并携带 `state.patcher.patches`，普通实体快照不会携带运行时 CP；基因杂交成功后若不主动全量同步，客户端仍可能按旧内容预测武器/能力，表现为护盾/治疗/伤害被服务端快照拉回。
- 修复：基因杂交 CP 应用成功后自动对所有在线玩家下发一次世界数据同步；失败回滚后也下发一次同步，避免客户端残留错误内容。同步现在按玩家分批执行（默认约每 350ms 一个玩家），避免同时让所有玩家全量接收世界数据造成瞬时上行尖峰。
- 修复：DataPatcher 每次应用都会先 unapply 再重放所有补丁，现有单位的 `mounts/abilities` 可能仍引用旧的 Weapon/Ability 实例；现在会在每次基因杂交补丁应用后重建所有已基因杂交单位类型的现有单位 `mounts` 与 `abilities`。
- 修复：Erekir 多部件炮塔（如 `smite`、`malign`）没有与方块 ID 完全同名的单张可旋转炮塔贴图；作为单位武器时改用主体区域映射（如 `smite-mid`、`malign-main`、`scathe-mid`），避免世界实体武器贴图找不到。
- 修复：运行时序列化跳过 `trailEffect/trailRotation/trailInterval` 等依赖原特效对象的子弹拖尾字段；此前复制了 `trailRotation=true` 但跳过原 `trailEffect` 时，默认拖尾会把“角度”当“大小”参数，导致天谴/魔灵类炮弹特效随旋转异常变大。
- 改进：基因杂交运行时序列化深度从 4 提高到 9，并允许复制 `fragBullet`、`intervalBullet`、`spawnBullets`、`spawnUnit/despawnUnit` 等嵌套子弹/导弹单位字段，修复泰坦爆炸、天帝分裂、创伤导弹、雷霆轰炸、埃里克尔飞船导弹等被退化成默认子弹的问题。
- 改进：已知 `Fx.*` 特效与 `Sounds.*` 音效会按字段名写入 CP；炮塔杂交的单位武器现在会尽量继承炮塔 `shootSound`。未知的运行时 lambda/custom Effect 仍会跳过，避免 CP 无法解析或客户端崩溃。

## 2026-06-22：玩家名字颜色隔离修复

- `wayzer/user/nameExt.kts` 不再把玩家连接包原名统一 `stripColors`；玩家在名字中手动添加的合法颜色标记会被保留。
- 名字拼接改为在前缀、玩家名、后缀之间插入 `[white]` 隔离，避免头衔/随机形态/观战/后缀等组件的颜色泄漏到玩家名字或聊天内容。
- 仍会清理玩家名末尾残缺颜色标签（如断掉的 `[white`），避免异常重连/换图后出现半截颜色标记导致后续文本变色。

## 2026-06-23：基因杂交 CP 回滚与炮塔空弹药崩服修复

- 定位：基因杂交失败时如果用 `contentsTweaker.contentPatches` 直接恢复，会遗漏当前地图脚本通过 `ContentPatchLoadEvent` 注入但不在 `contentPatches` 内的地图 CP，表现为失败后地图 CP 被卸载/失效。
- 修复：基因杂交应用前记录 `state.patcher.patches` 作为“当前实际生效 CP”，失败时按该列表精确恢复；`contentPatches` 只恢复为 ContentsTweaker 自己的临时列表，避免把地图 CP 误删或塞回临时列表造成后续重复。
- 修复：CP 重放/回滚后立即扫描现有炮塔，清理旧 CP 留下的无效弹药条目；若出现 `hasAmmo()==true` 但 `peekAmmo()==null` 的危险炮塔，则移除该异常炮塔，防止原版 `Turret.updateReload()` 空指针崩服。
- `setBlock` 增加炮塔安全检查：当前无有效弹药/子弹配置的 Item/Liquid/Power/Continuous 炮塔会拒绝放置，避免管理员放出因 CP 异常导致的崩服炮塔。

## 2026-06-23：/cp 指令真实 CP 状态修正

- `wayzer/map/worldProcessorAdmin.kts` 的 `/cp` 不再按下标把 `ContentsTweaker.patchList` 名称套到 `state.patcher.patches` 上，避免地图脚本 CP、临时基因杂交 CP、事件注入 CP 顺序不一致时显示错误。
- `/cp` 现在直接读取当前 `state.patcher.patches` 的 PatchSet，显示每个 CP 的真实名称、解析状态、警告数量与内容预览。
- 新增 `/cp <编号>` 与 `/worldprocessor cp <编号>`，可查看单个 CP 的详情和前若干条 patcher 警告，方便调试 CP 兼容与基因杂交失败原因。
- 2026-06-26 补充：`/cp` 仅保留管理员权限；游戏内默认打开 CP 管理菜单，支持“临时卸载”（仅当前运行列表移除）与“禁用并卸载”（同时移除当前地图 ContentsTweaker 记录）。操作后会重放剩余 CP、清理无效炮塔弹药并分批向玩家同步世界数据。

## 2026-06-23：基因杂交特效兼容修正

- 基因杂交运行时序列化不再只支持 `Fx.*` 静态特效；现在会尝试序列化 `mindustry.entities.effect.*` 数据化特效（如 `ParticleEffect`、`WaveEffect`、`ExplosionEffect`、`MultiEffect`、`WrapEffect` 等），提高兼容已被地图 CP 修改过的炮塔/单位子弹特效。
- 对仍无法序列化的运行时 lambda/base `Effect`，现在显式写入 `none`，避免字段被跳过后由 `BulletType` 默认值退化成白色命中/消失圆圈。
- 恢复拖尾特效相关字段的复制（`trailEffect`、`trailChance`、`trailInterval`、`trailParam`、`trailSpread`、`trailRotation` 等）；若拖尾特效本身无法复制，会降级为 `none` 而不是产生错误默认效果。

## 2026-06-23：自选基因杂交

- `wayzer/user/ext/skills.kts` 的杂交菜单新增 `[gold]自选基因杂交`：资历3级入口，目标同意后向发起者展示目标当前可提取的武器/能力列表。
- 自选基因杂交消耗 78 MDC；选择菜单中的某个武器/能力后，复用基因杂交的临时 CP 叠加、失败回滚、炮塔弹药保护与可选完整同步逻辑。
- 为控制复杂度与避免无限叠 CP，自选基因杂交沿用基因杂交槽位限制：同一发起者单位类型最多 1 个单位基因 + 1 个炮塔基因。

## 2026-06-23：基因/自选基因杂交复杂武器诊断与兼容

- 基因/自选基因杂交的子弹序列化深度调整为 14 层，用于复杂武器链路，重点覆盖 `fragBullet`、`intervalBullet`、`spawnBullets`、`spawnUnit/despawnUnit`、溅射、穿透与雷电字段。
- 新增“基因杂交武器诊断”日志：应用 CP 后会输出源武器与新武器的子弹摘要，包含主弹/分裂弹类型、伤害、溅射、穿透、碰撞、分裂触发、导弹/生成子弹等关键字段。
- 若源武器存在关键二级链路而新武器应用后丢失（如 `fragBullet`、溅射、`intervalBullet`、`spawnBullets`、导弹单位），本次 CP 视为未实际生效，会自动回滚并由调用方回补 MDC。
- 激光/轨道/霰弹类复杂线性武器显式保留分裂触发与碰撞开关，改善“穿透线伤害正常但阻挡点散射/爆破丢失”的兼容性。
- 说明：基因/自选基因杂交当前没有单位/炮塔准入白名单；只排除核心机、导弹单位，以及无法序列化/无有效弹药的目标。`hybridTurretWeaponRegionOverrides` 只是少数多部件炮塔的贴图名映射，不是白名单。

## 2026-06-23：基因杂交同步策略降噪

- 源码确认：运行时 CP 只会随完整世界数据流同步；`/sync` 与脚本旧逻辑都会走 `Call.worldDataBegin + Vars.netServer.sendWorldData`，客户端会清空实体并显示重载地图流程。
- `skills.kts` 新增 `hybridAutoWorldSync=true`，默认恢复基因/自选基因杂交后的自动完整同步，以保证运行时 CP 尽量立即显示/生效；如后续更重视体验，可改配置关闭，改由显示异常的玩家手动 `/sync` 或重进。
- 移除杂交 CP 应用后的 `Call.setRules(Vars.state.rules)`，因为它不能下发 CP，只会发送完整 Rules，容易造成无意义同步与客户端本地显示设置被覆盖。

## 2026-06-23：杂交内存占用复查

- 未发现基因/自选基因杂交存在无限增长的强引用缓存：单位杂交冷却、当前地图基因杂交记录会在 `ResetEvent`/`WorldLoadEvent`/脚本卸载时清理。
- 分批完整同步协程现在会在换图/重置/脚本卸载时通过 `advancedHybridSyncGeneration` 失效，且在每名玩家延迟后再次检查代数，避免旧同步任务继续持有玩家列表并向新地图发送旧轮次同步。
- 大 CP 地图上基因杂交造成的内存升高主要来自 DataPatcher 重放当前所有 CP、运行时序列化复杂武器、以及 `/sync` 完整世界数据压缩/发送的短期分配；属于峰值占用，正常情况下会随 GC 回落，不是持续泄漏。

## 2026-06-23：杂交命名与单位杂交播报统一

- 杂交系统用户侧口径统一为：`单位杂交`（生成子单位玩法）、`基因杂交`（随机抽取目标武器/能力基因）、`自选基因杂交`（手动选择目标武器/能力基因）；旧参数 `normal/advanced/ultimate` 以及旧中文参数仍保留兼容。
- 单位杂交成功广播新增详情行，显示遗传父/母方血统、是否继承父/母方 buff、品质与父母死亡判定。
- 单位杂交品质效果中的状态现在除 `invincible` 外使用无限时间；抖擞精神改为通过直接追加 `StatusEntry` 实现同名 Buff 多层叠加，播报会显示 `xN`。
- `runfaster` 的“3层 fast”改为直接追加 3 个 `fast` 状态条目，实现真实叠层，不再依赖 `statusSpeed` 动态速度模拟。

## 2026-06-23：`@hybrid` 地图特色杂交玩法

- 新增 `mapScript/tags/hybrid.kts`，地图介绍/规则标签包含 `[@hybrid]` 时自动启用；玩家可用 `/hybrid` 或 `/杂交` 打开独立杂交菜单。
- 该脚本不依赖本服账号、权限、资历、MDC、技能系统，只依赖 ScriptAgent 地图标签与菜单基础库，便于迁移到其他服务器。
- 地图内开放三种玩法：`单位杂交` 生成子单位且父母单位分别概率死亡，队伍共享冷却 10 秒；`基因杂交` 随机抽取目标当前单位/炮塔武器或能力，队伍共享冷却 120 秒且不击杀双方；`自选基因杂交` 列出目标可获取基因供选择，队伍共享冷却 300 秒且成功后击杀双方/摧毁目标炮塔。
- 地图版不额外排除核心机、导弹等单位；无法序列化/无有效弹药的目标仍会拒绝，避免 CP 解析失败或炮塔空弹药崩服。
- 地图版基因杂交取消单位基因单次限制，同一单位类型可多次获得单位基因；炮塔基因仍最多 1 个，避免炮塔武器无限叠加造成性能/同步风险。
- 基因杂交动态 CP 直接基于当前 `state.patcher.patches` 叠加与回滚，不写入本服 `contentsTweaker` 临时补丁列表；脚本卸载时会移除本脚本添加的动态基因补丁。
- 成功应用基因后会分批向在线玩家发送完整世界数据同步；提示玩家若客户端显示异常可手动 `/sync` 或重进。

## 2026-06-25：结算/自动晋升与地图脚本加载性能排查

- 定位：每局结束后卡顿的高嫌疑点不止数据库。`mapScript/module.kts` 原本在每次 `ResetEvent` 都执行 `ScriptRegistry.scanRoot()` 并卸载可更新脚本，生产服每局换图都会进行磁盘扫描/脚本事务，容易造成结算后的明显停顿；现改为配置 `scanRootOnReset=false` 默认关闭，仅调试热更新时开启。注意：关闭的是“扫描脚本目录/热更新卸载”步骤，不关闭 Reset 时的地图脚本 disable；旧地图脚本仍会在 Reset 正常禁用。
- 资历系统在线时长不再在离线/结算/周期 tick 时逐玩家同步写库；改为内存累加，周期、GameOver、Reset 时批量写入，并放到 `Dispatchers.IO` 执行；脚本卸载时才做最终阻塞 flush。
- 信任等级与资历等级自动检测改为每轮限量处理（默认 6 名玩家），并将原先多次查询 MDC/赞踩/认可/在线时长的逻辑合并为单次统计查询，避免 GameOver/Reset 标记大量玩家后在下一轮一次性打库。
- 信任/资历自动检测现在跳过未登录游客：PlayerJoin/GameOver/Reset/兜底全量复查只会把已登录玩家加入自动检测队列；若队列中残留在线游客 UID，也会在执行前直接丢弃，避免游客频繁连接导致无意义 DB 查询。
- 本局贡献 MDC 结算从“每名获奖玩家一次 `addTrustPoints`”改为一次批量奖励写入，并迁移到 `Dispatchers.IO`；写入完成后再回到游戏线程发送个人提示和结算广播。
- 新增慢操作日志：资历在线时长批量写入、资历/信任自动检测、贡献奖励批量写入、地图脚本卸载/加载/Reset 扫描超过阈值会输出耗时，便于继续定位是否还存在数据库或 CP/脚本加载卡顿。
- 健壮性补强：`loadCurrentMapScripts()` 在发现 `loadedMapScriptKey` 从旧地图切到新地图但中间没有 Reset 时，会先兜底禁用旧地图脚本，并在禁用后检查是否仍有非控制器地图脚本处于启用状态，防止异常流程下新旧地图脚本/CP 重叠。
- 判断：当前更像是若干同步热路径叠加（每局扫描脚本 + 逐玩家同步 DB + 杂交 CP 同步峰值），暂不需要立即更换数据库结构；优先把热路径批量化/异步化并用慢日志确认真实瓶颈。若慢日志仍显示 DB 单次事务持续数秒，再考虑迁移到独立数据库或进一步把 `MdtStorage` 统一封装成 IO 队列。

## 2026-06-25：杂交资历与结算 MDC 调整

- `/skill hybrid` 杂交系统入口从资历2级调整为资历3级；主技能菜单中也仅资历3级及以上显示杂交菜单。
- 杂交系统价格整体约为原来的 1/10：单位杂交 `6` MDC，基因杂交 `25` MDC，自选基因杂交 `78` MDC；失败回补与提示同步改为新价格。
- 本局贡献 MDC 结算翻倍：结算系数从 `6` 提到 `12`，单局封顶从 `24` 提到 `48`，提示文案同步为满活跃约每10分钟 `+2` MDC。

## 2026-06-25：游戏结束 MDC 奖励写库再优化

- 复查游戏结束相关 MDC 奖励路径：本局贡献结算已是 IO 批量写入；仍可能在游戏结束后由多名玩家发送 `gg` 触发逐条同步 `addTrustPoints`。
- `trustPointReward.kts` 新增 MDC 奖励缓冲队列，GG奖励、点赞奖励、认可奖励会先进入内存队列，再按默认 `1000ms` 间隔批量写入数据库；Reset/脚本卸载时会主动 flush。
- 该优化避免游戏结束后多人同时 `gg` 时在主线程连续打库；写入慢于 `200ms` 会输出 `MDC奖励批量写入耗时` 日志。

## 2026-06-25：投降限制与标准无限火力投票

- `/vote gameOver` 不再因为当前地图 `rules.canGameOver=false` 拒绝投降；这是玩家主动投票结算入口。PVP 仍保留原本“本队投降”逻辑，只摧毁发起队伍核心，不会误触发非 PVP 的全局投降结算。
- `/vote infinitefire` 调整为 120 秒“标准无限火力”：不启用 `fillItems` / `infiniteResources`，不填充核心资源，不提高伤害倍率，只补足炮塔开火所需弹药/液体/供电并兼容无限弹药字段。
- 新增 `/vote infinitefirepromax`：保留原无限火力promax效果，包含建筑输入补足、无限资源与伤害倍率提升；发起者需要信任等级2级及以上。

## 2026-06-25：MDC显示与promax投票等级限制

- `trustPoint.kts` 新增在线玩家当前 MDC 缓存；MDC变化时向本人发送仅自己可见的消息提示，不显示到积分板。
- MDC 增减统一通过 `trustPoint.kts` 向在线玩家发送短提示，如 `MDC-10（技能消耗）`、`MDC+5（奖励）`；本局贡献结算因已有详细个人结算与排行榜，避免重复提示。
- GG 奖励不再额外即时发送“GG奖励”提示，批量入账后走统一 MDC 变化提示。
- `/vote infinitefirepromax` 发起者需要信任等级2级及以上；`/vote infinitefire` 标准无限火力仍不加该信任等级门槛。

## 2026-06-26：技能、杂交清洗与纯净模式投票

- `skills.kts`：杂交菜单新增“基因清洗”，消耗 10 MDC，清理当前附身单位类型记录的单位/炮塔基因补丁，重放剩余 CP、刷新现有单位并分批同步客户端。
- `skills.kts`：新增 2级技能 `/skill fullheal`（完全痊愈），消耗 5 MDC，120 秒冷却，恢复当前附身单位至满血。
- `skills.kts`：3级“骇人空袭”消耗从 5 MDC 调整为 10 MDC；改为在玩家位置快速生成三波苍穹，每波间隔 0.2 秒，目标限制在光标方向约 45 格内并冲向目标附近坠毁。
- `skills.kts` + `gameContributionReward.kts`：新增管理员技能 `/skill doublemdcreward`，给当前局添加 `@doubleMdcReward`，本局贡献结算 MDC 翻倍，Reset 后清理。
- `voteFunRules.kts`：新增 `/vote pure <1-10>` 与 `/vote pureoff`。纯净模式通过后从下一局开始按剩余局数自动添加 `@noSkills`；取消投票会清空排队局数，并只移除由纯净模式本身添加的当前局 `@noSkills`。
- `vote.lib.kt`：投票弹窗分区与按钮颜色重新整理，提高发起者、类型、票况、说明和文字投票提示的可读性。


## 2026-06-25：数据库调用主线程堵塞 review 与边缘功能迁移

- 复查 `MdtStorage` 调用点：论坛/Wiki/排行榜/称号商店等大列表和菜单型功能已基本使用 `withContext(Dispatchers.IO)` 或缓存；结算奖励、在线时长、GG/点赞/认可奖励已在前序改为批量/IO；仍需重点关注聊天热路径、周期任务和积分板这类高频入口。
- `trustPoint.kts`：MDC变化提示通过游戏线程发送仅本人可见的消息，数据库写入仍走原业务入口/批量入口；在线当前值缓存只用于减少变动提示/查询时的重复读取，不挂到积分板。
- `playerMute.kts`：禁言状态原本在每条聊天拦截时查询 `MdtStorage.getMuteReason`；现在加入在线玩家禁言缓存，玩家进服时在 `Dispatchers.IO` 预加载，禁言/解禁时同步更新缓存，离线清理，避免聊天热路径频繁打库。
- `serverDescription.kts`：服务器介绍轮播原本每次定时切换都读取多项 `MdtSettings`；现在缓存介绍列表、轮播开关、基础介绍、当前ID和锁定ID，管理员修改时同步更新缓存，轮播周期不再反复打库。
- `MdtStorage.kt` 与 `banStore.kts`：数据库事务统一经过本地慢事务封装；单次事务超过 `200ms` 会输出 `[数据库] 慢事务`，超过 `1000ms` 会输出 `[数据库] 严重慢事务`，日志包含入口函数名、耗时和线程名。若看到 `thread=main`/游戏线程的慢事务，应优先把对应调用迁移到 `Dispatchers.IO` 或改为缓存/批量写入。
- 当前没有直接改数据库结构。若后续慢日志仍显示 H2 单事务持续数秒，再考虑把 `MdtStorage` 统一封装为串行 IO 队列或迁移到独立数据库；现阶段优先减少主线程高频同步查询。

## 2026-06-26：玩家加入信息面板缓存异步化

- 定位：`wayzer/ext/playerInfoTripleTap.kts` 在 `PlayerJoin`/`PlayerLeave` 直接读写最近玩家记录，并在进服时同步预热玩家信息面板缓存；该路径会读取口碑、认可、MDC、信任/资历、称号与在线时长等多张表，玩家加入时容易让游戏线程短暂停顿。
- 玩家资料缓存改为“游戏线程只采集不可变快照，`Dispatchers.IO` 查询数据库，完成后再回游戏线程写入缓存”；资料变更事件不再同步刷新缓存，而是只失效对应 UID，下次打开面板时异步查询并回填。
- 最近玩家列表新增内存缓存与 2 秒防抖保存；加入/离开只把快照交给 IO 线程处理，不再在游戏线程完整读写 `MdtSettings` 的最近玩家大字符串。
- 最近玩家菜单与离线玩家详情页改为通过 `withContext(Dispatchers.IO)` 读取缓存/资料，避免管理员打开最近玩家面板时在游戏线程打库。
- 注意：该轮优先处理“玩家加入瞬间卡一下”的高嫌疑脚本；若实测仍有 join 卡顿，继续按慢事务日志排查 `achievement.kts` 的进服成就检测、`playerTitle.kts/nameExt.kts` 的进服改名与其它 `PlayerJoin` 监听器。

## 2026-06-26：高频赞踩/称号/成就链路优化补充

- `wayzer/ext/playerReputation.kts`：点赞与点踩共用同一套额度检查与写入路径；每日清理只在日期变化时执行一次，额度检查和计数写入合并为 `MdtStorage.recordReputationVoteChecked(...)` 单事务，避免一次赞/踩连续打多次数据库。实际赞踩仍即时落库，因为帖子作者赞踩计数、每日限额和即时反馈需要准确结果。
- `wayzer/user/achievement.kts`：赞、踩、认可、MDC、等级、称号、商店、发帖等事件不再立即逐次检测成就，而是按 UID 防抖合并；连续点赞/点踩会等待短时间后只触发一轮成就统计读取，降低连锁数据库压力。
- `wayzer/user/playerTitle.kts`：进服时称号拥有/佩戴/定义缓存改为 IO 线程预热，名字前缀刷新优先使用缓存；游客不查库，避免 `nameExt` 刷新名字时在游戏线程同步查询称号数据库。
- `wayzer/user/accountIpGuard.kts`：进服/认证后的 IP 身份记录改为 IO 写入，并加入风险 IP 索引缓存；普通连接路径不再每次为了不存在的风险记录打库。

## 2026-06-26：外部 JSON/HJSON CP 热重载与投票加载

- 新增 `mdtserver/config/scripts/wayzer/map/externalCpHotReload.kts`，提供外部 JSON/HJSON Content Patch 热重载系统。
- 外部 CP 文件目录为 `mdtserver/config/scripts/external-cp/`；仅扫描该目录下的 `.json` / `.hjson` 文件，不允许任意路径读取。超过慢同步阈值的大文件会使用更长同步间隔而非直接拒绝，真正硬上限由 `hardExternalCpBytes` 控制。
- 管理指令：`/externalcp`（别名 `/ecp`、`/外部cp`、`/热重载cp`）。管理可打开菜单查看可用 CP、直接加载/热重载、卸载单个或卸载全部已加载外部 CP。
- 投票入口：`/vote cp` 打开外部 CP 投票列表；`/vote cp load <文件名|编号>` 发起加载/热重载指定外部 CP 的投票，`/vote cp unload <文件名|编号|all>` 发起卸载投票；加载/卸载均需 70% 同意。加载通过后会读取 JSON/HJSON、优先使用 Jval 原生解析并回退 ContentsTweaker 兼容预处理，随后叠加到当前运行态 CP，并分批向在线玩家发送世界数据同步。
- 外部 CP 仅当前局运行态有效；换图/Reset 后会清理脚本记录，ContentsTweaker/地图加载流程会重放地图本身 CP，不会把外部 CP 写入地图 tag 或数据库。
- 失败保护：加载前记录当前 `state.patcher.patches` 与 `contentPatches`，解析失败/应用失败会回滚并返回明确错误；加载/卸载后复用炮塔空弹药保护，避免 CP 变动导致当前地图炮塔 `peekAmmo()==null` 崩服。
- 电网保护：CP 加载/卸载/回滚后会修正 `Block.consumesPower` 与 `consPower` 不一致、拆掉“不再需要电力模块”的建筑模块、清空旧 `PowerGraph/PowerGraphUpdater` 并从当前建筑重建电网；用于避免卸载 CP 后旧电网消费者列表残留，进入 `PowerGraph.distributePower` 时因 `consPower == null` 触发 NPE 崩服。
- 客户端同步：成功或回滚后使用与杂交系统同类的分批 `Call.worldDataBegin + Vars.netServer.sendWorldData`。若玩家显示异常，仍提示可手动 `/sync` 或重进。
- `/vote` 列表中会按分类着色并显示通过门槛：多数投票显示“需50%同意”，`cp` 分类使用 `[purple]` 并标注“需70%同意”；管理帮助菜单新增 `[purple]外部CP` 项。

## 2026-07-07：Mindustry 159 Data Asset / CP 兼容

- 参考 159 原版实现后确认：服务器端数据资产位于数据目录 `assets/`，CP 文件迁移到 `assets/patches/`；原版通过 `DataPatchLoadEvent` 注入服务器资产，并由 `state.data` / `DataManager` 管理补丁、内容、贴图、音频等资产。
- 启动脚本 `mdtserver/start-server.ps1` 与 `mdtserver/start-server.sh` 会自动确保 `config/assets/{patches,content,bundles,sprites,sounds,music}` 存在，避免首次启用 159 Data Asset 时手动建目录。
- 启动脚本不再主动创建旧版 `config/patches`；官方 159 会把该旧目录迁移到 `config/assets/patches` 并删除旧目录，继续创建会导致每次启动都出现迁移警告。
- `coreMindustry/contentsTweaker.kts` 增加版本兼容桥：旧版优先兼容 `state.patcher.apply(Seq<String>)`，159+ 优先通过反射创建 `mindustry.mod.data.PatchAsset` 并调用 `state.data.reloadPatches(...)`。对外提供 `currentPatchStrings()`、`loadedPatchInfos()`、`patchInfoFor(...)`、`applyPatchStrings(...)`，避免业务脚本直接依赖已移除的 `state.patcher`。
- `skillsHybrid.kts`、`mapScript/tags/hybrid.kts`、`worldProcessorAdmin.kts`、`externalCpHotReload.kts` 改为通过 ContentsTweaker 兼容桥读取/重放 CP，基因杂交、地图特色杂交、`/cp` 管理与 `/vote cp` 外部 CP 不再直接引用 `state.patcher`。
- `mapScript/module.kts` 不再硬编码 `ContentPatchLoadEvent` 泛型监听；改为按运行时存在的事件类型注册旧版 `ContentPatchLoadEvent` 与 159 `DataPatchLoadEvent`，旧版向 `patches` 追加字符串，159 向 `assets` 追加 `PatchAsset`。
- `wayzer/maps.kts` 同样按运行时事件提前应用 `MapManager.tmpVarSet`，保证地图 `rules/tags` 在旧版内容补丁事件或 159 Data Asset 事件阶段尽量已经恢复，`WorldLoadBeginEvent` 仍作为兜底。
- `mapScript/lib/ScriptMapGenerator.kt` 的脚本生成地图加载流程也改为反射兼容旧版 `ContentPatchLoadEvent/state.patcher` 与 159 `DataPatchLoadEvent/state.data.load`，脚本生成地图同样能加载服务器 Data Asset（补丁/音效等），并避免 159 移除旧事件后生成地图脚本无法编译/加载。
- 分批世界同步兼容 159 外部资产：杂交/外部 CP/CP 管理同步时若 `Vars.netServer` 存在 `sendWorldAndAssets(Player)`，会在 `worldDataBegin` 后走原生“资产需求 + 世界数据”流程；旧版仍回退到 `sendWorldData(Player)`。
- 注意：159 原生 `assets/sprites`、`assets/sounds`、`assets/music` 可以解决客户端资源下载问题，但当前 `/vote cp` 仍是“读取 `config/scripts/external-cp` 中的 JSON/HJSON 并作为运行态补丁叠加”的玩法入口；若后续要投票启停带贴图/音频的完整 Data Asset 包，应另做 assets 目录启停/`reloadassets` 管理层。

## 2026-07-08：CP 卸载链路复查与内容快照清理

- 排查 `D:\log-19.txt` 后确认旧 X35 运行中出现的“硫混/硫化物工厂不产出、建电力节点不自动连接”高度符合外部/地图 CP 污染：`奇妙双科cp.json` 会把 `pyratite-mixer` 改为“硫化物製備廠”，并移除电力消耗；如果旧 ContentPatcher 卸载时没有可靠恢复全局 `Block` 单例，后续无 CP 地图也可能继承该状态。
- `coreMindustry/contentsTweaker.kts` 新增内容状态快照工具：可对当前 blocks/items/liquids/status 的 CP 高频字段做轻量快照，并在重放 CP 前恢复。快照会跳过贴图/运行时构造缓存，重点覆盖 Block 的 `consumers/consPower/outputItems/outputLiquids/requirements/buildVisibility/health/itemCapacity/liquidCapacity/ammoTypes` 等字段；数组、`Seq`、`ObjectMap` 和物品/液体堆会做轻量拷贝，降低旧 patcher “apply(empty) 但内容对象未回原状”的风险。此前测试过全字段快照，每次恢复会涉及约 10 万字段，已收窄为白名单以避免 CP 重放/换图时额外卡顿。
- 全局 CP 应用入口 `contentsTweaker.applyPatchStrings(...)` 现在会先恢复 `ContentsTweaker原始内容基线`，再调用 159 DataAsset patcher 或旧 ContentPatcher 重放传入的完整补丁列表。因此地图标签 CP、`/cp` 管理、外部 CP 热重载、杂交动态 CP 等所有经由该入口的链路都会走“恢复基线 -> 重放当前应保留 CP”的模式，而不是只依赖各功能脚本自行回滚。
- `wayzer/map/externalCpHotReload.kts` 在脚本启用时记录服务器内容基线，在首次加载外部 CP 前记录“外部 CP 前基线”。外部 CP 的加载、热重载、卸载单个、卸载全部、失败回滚与脚本卸载清理，都会先恢复对应基线，再重放当前应保留的 CP 列表，随后继续执行原有建筑模块/电网修复、无效炮塔弹药清理与分批世界同步。
- 因 `contentsTweaker` 已经在 Reset 时清空补丁并从原始内容基线恢复，外部 CP 的 Reset 监听只清理自身运行态，不再重复调用 `applyPatchStrings(emptyList())`，避免换图时内容快照恢复执行两遍。
- `/cp` 管理链路也记录 CP 管理脚本启用基线；管理员临时卸载/禁用并卸载 CP 时，会在重放剩余 CP 前先恢复该基线，避免单纯从 patcher 列表移除补丁但全局内容对象仍残留旧字段。
- 限制：该快照是运行态安全兜底，不等价于完整重启。若脚本是在服务器已经被 CP 污染后才热加载，基线也可能包含污染；若 CP 修改了复杂嵌套对象/贴图资源/自定义内容类，仍建议在 CP 测试后重启服务端。后续若继续观察到“无 CP 地图仍有 CP 字段”，优先检查基线捕获时间与是否有地图 CP 在 Reset 后被异常重复注入。

## 2026-07-07：服务器小音效菜单

- 新增 `mdtserver/config/scripts/wayzer/ext/soundEffectMenu.kts`，提供管理员小音效菜单与 `/sfx` 指令。
- 音效来源为 Mindustry 159 Data Asset 声音资产：将 `.ogg` / `.mp3` 放入 `mdtserver/config/assets/sounds/`，在服务端加载资产/换图后会进入 `state.data.getSounds()`，脚本按 DataAudioLoader 的 `100000+` 声音 ID 规则找到对应 `Sound`。
- 管理指令：`/sfx` 打开菜单，`/sfx list` 查看当前已加载小音效，`/sfx reload` 从 `config/assets/sounds/` 热注册/刷新当前地图的声音资产，`/sfx play <编号/名称>` 向全服播放，`/sfx sync` 分批向在线玩家重发世界与 159 服务器资产；别名包括 `/soundfx`、`/sounds`、`/音效`、`/小音效`、`/播放音效`。
- 权限节点：`wayzer.admin.soundEffect`，默认给 `@admin`；`/help` 管理指令分区新增“服务器小音效”入口。
- 默认全局播放间隔 `800ms`，避免误点连刷；可通过脚本配置调整默认音量、音高与是否全服文字提示。
- 2026-07-07 排查无声问题后调整：官方 v159 的 DataAsset sound 运行时 ID 从 `100001` 开始，但 `Call.sound` 的网络序列化仍写入 `short`，会导致自定义音效 ID 溢出后客户端无声。脚本现在会把 `assets/sounds` 下的小音效同时反射注册为 `SoundAsset` 与 `MusicAsset(sfx-*)`，播放默认改走 `Call.playMusic(String)` 字符串通道；客户端 `DataAudioLoader` 会把该音乐资产注册成 `dp-sfx-*`，所以播放名必须带 `dp-` 前缀。这会受客户端“音乐音量”影响，但可规避 `Call.sound` 的 ID 问题。
- 同步策略也做了保守化：小音效定位为固定资产，脚本加载阶段即热注册，后续新进玩家理论上应走官方加入世界时的资产需求/下载流程拿到 `sfx-*` 音频；播放前不自动 `sendWorldAndAssets`。2026-07-07 晚实测发现“玩家进服后自动补发资产”会让客户端卡在无核心机/无单位状态，因此已回退所有小音效自动补发，只保留 `/sfx sync` 作为管理员手动排障入口，并在菜单/提示中标明它可能重载客户端世界、导致短暂显示异常。
- 2026-07-07 追加修复“首次进服无声、重进正常”：官方 v159.1 未修改 `NetServer.sendWorldAndAssets` / `DataAudioLoader` / `Call.playMusic` 相关流程；问题更像是地图加载后 `state.data` 被替换导致脚本加载阶段注册的小音效资产丢失，或客户端首次进服后 `dp-sfx-*` 音频本地注册略晚于播放包。脚本现在会在 `onEnable` 与 `WorldLoadEvent` 重新注册固定小音效资产；玩家加入后的短窗口内播放小音效时，不重发世界/资产，只延迟补发一次 `playMusic` 播放包给刚进服玩家。
- 新增 3级技能 `/skill omg`：显示名与介绍均为 `omg`，消耗 15 MDC，无技能冷却；实际效果为调用固定小音效 `omg.ogg`。
- 音频文件检查：脚本会提示明显伪装格式，例如扩展名为 `.mp3` 但文件头为 `ftypM4A` 的 M4A/MP4 容器。此类文件本地播放器可播放，但 Mindustry sound 资产通常无法按 MP3/OGG 稳定解码，应转码为真正的 `.ogg` 或 `.mp3` 后再放入目录。

## 2026-07-07：服务器点歌 / 音乐库

- 新增 `mdtserver/config/scripts/wayzer/ext/musicJukebox.kts`，基于 Mindustry 159 Data Asset 音乐资产实现服务器点歌。
- 网络点歌来源先实现网易云音乐：`/music <网易云歌曲/DJ ID或分享链接>` 会解析普通歌曲与 `/dj?id=...` 电台节目；DJ 节目会先请求 `api/dj/program/detail`，取 `program.mainSong.id` 作为真实音频下载 ID。脚本会读取歌曲名/歌手/时长，校验最大时长与最大文件大小后再下载；下载结果必须像 MP3/OGG，避免把版权错误页当音乐同步。
- 最近网络点歌缓存目录：`mdtserver/config/music-jukebox/jukebox/`；默认保留最近 6 首，可通过脚本配置或 `/music limit cache <数量>` 调整。重新点到已有缓存时会刷新文件时间；有新歌曲进入缓存后按最后使用时间保留最新 N 首并清理更早缓存。
- 服务器内置音乐库目录：`mdtserver/config/music-jukebox/library/`；将 `.mp3` / `.ogg` 放入该目录后，可用 `/music library` 查看，玩家 `/music votelib <编号/名称>` 发起点歌投票。该目录刻意位于 `assets` 外，避免新玩家进服时被原版资产握手自动同步全部曲库。
- 玩家点歌改为明确的60秒点歌投票：`/music vote <ID/链接>` 或直接 `/music <ID/链接>` 会先解析/校验/下载音乐，然后全服提示“谁点了哪首歌”。玩家像标准投票一样直接在聊天发送单个 `1` / `.` / `0` 表示同意/中立/拒绝；也兼容 `/music yes|neutral|no` 或在延迟弹出的菜单中选择同意/中立/拒绝。只有同意者会进入排队同步队列，同步完成后才播放。拒绝/中立/不响应者不会同步，也不会被打断当前正在听的其他音乐。
- 投票互斥：点歌投票进行中会通过 `VoteEvent.registerStartBlocker` 阻止其他普通投票发起；反过来，已有普通投票进行中时也不允许发起点歌投票。`vote.kts` 的聊天快捷投票在无普通投票但存在外部投票阻塞器时不再误提示“投票已结束”，由点歌脚本消费 `1` / `.` / `0` 表态。
- 点歌没有传统通过门槛，但投票结束会全服提示听歌人数；若发起者本次同意率低于25%，会进入点歌冷却，防止快速刷投票。当前同一时间只允许一个点歌投票或一个正在解析的点歌请求。
- 管理指令：已移除“直接给全服同步播放”功能；`/music play <网易云ID/分享链接>`、`/music playlib <编号/名称>` 仅保留为兼容入口，会改为发起点歌投票。`/music limit size <大小>`、`/music limit duration <时长>`、`/music limit cache <数量>` 可运行时调整单曲最大大小、最大时长与网络缓存保留数量；`/music stopall` 停止所有玩家音乐并取消当前点歌投票；`/music cancel` 取消当前点歌投票。权限节点 `wayzer.admin.music` 默认给 `@admin`。
- 玩家指令：`/music stop` 停止自己的当前音乐；别名包括 `/点歌`、`/bgm`、`/musicvote`。
- 并发保护：每个玩家有独立的待播放序号，并新增全局排队同步队列，避免多名玩家一瞬间同时 `sendWorldAndAssets` 把上行/磁盘打满。如果玩家在上一首仍在队列/同步中又同意了新音乐，旧任务完成后不会反向覆盖新音乐。玩家离线会清理其播放/投票状态，避免长期占用内存。
- 同步优化：脚本会按“玩家 UUID + 音乐资产路径/名称/大小”记录已完成过 `sendWorldAndAssets` 的音乐；同一玩家再次播放同一首歌会跳过重复资产同步并直接播放，玩家离线时清理该内存缓存。若脚本重载或玩家重连，会保守地重新同步一次。
- 官方 v159 重同步弹窗优化：原版 `sendWorldAndAssets` 会临时把连接 `hasConnected` 置回 false，客户端同步完成后会再次走 `connectConfirm`。点歌脚本现在等待 `PlayerConnectionConfirmed` 真实就绪事件后再恢复该状态，避免一次音乐资产同步被当成重新进服，也不再提前干预握手。
- 限制与注意：当前 159 暴露给插件的资产分发流程仍以 `sendWorldAndAssets` / 世界数据流为主，点歌同步会让同意玩家经历一次资产/世界同步等待；脚本用串行队列降低瞬时上行。若 X 端后续提供“只发单个 Data Asset 且带完成回调”的接口，可再替换为更轻量的同步方式。

## 2026-07-16：点歌缓存退出默认进服资产同步

- 确认 v159/B477 的专服会递归扫描 `config/assets/music/`；此前放在 `assets/music/jukebox` 的 4 首缓存歌曲（约 12.3MB）与固定音效一起显示为 `Loaded 5 data asset files`，导致新玩家首次进服时自动协商并下载全部缓存歌曲。
- 点歌缓存与内置曲库迁移到 `config/music-jukebox/`，并加入 `.gitignore`。脚本首次启用会自动迁移旧 `assets/music/jukebox`、`assets/music/library` 文件，并反射刷新 `ServerControl` 的固定 Data Asset 列表；刷新失败时明确提示需要再重启一次。
- 玩家同意点歌后，目标歌曲才会临时注册为 `MusicAsset` 并进入逐玩家同步队列；队列不再引用该歌曲后，脚本会从当前 `state.data` 中卸载其 `jukebox/` 或 `library/` 临时资产，防止之后进入服务器的玩家被动同步历史点歌。
- 固定小音效 `assets/sounds/omg.ogg` 仍保留首次进服同步，但体积仅约 18KB。若仍出现 `LoadingFragment.showingProgress()` 的客户端空指针，说明仅存在一个早期 AssetStream 就可能触发官方/B477 客户端竞态，需要继续评估取消固定音效进服同步或等待客户端空值保护修复。

## 2026-07-16：点歌下载后播放确认与手动重播

- 排查“歌曲已经下载但没有播放”后，确认旧流程只按文件大小等待固定秒数，无法代表广东等慢线路客户端已经完成世界重载与音频注册；超出估算时间时可能提前发送 `playMusic`。
- 点歌同步现在为每个连接建立一次就绪等待器，等待客户端触发 `PlayerConnectionConfirmed`。该事件由原版客户端在资产接收完成、世界流加载完成后发送，比固定延迟更可靠；超时默认 120 秒，并在确认后额外等待 1.5 秒再播放。
- 对慢线路按歌曲大小追加等待预算（默认每 MB 15 秒，另加 30 秒基础预算，且不会低于 120 秒），并记录每名玩家完成资产/世界同步的实际耗时，便于区分线路过慢与客户端音频播放失败。
- 主等待超时后不再永久占用串行队列，但会额外保留默认 120 秒的迟到确认宽限和对应临时音乐资产。客户端若在宽限期内完成，会将歌曲登记到该玩家的“我的已同步音乐”菜单并提示手动播放；为避免旧同步与新同步互相覆盖，宽限未结束前不会再次对同一连接发起另一轮音乐重同步。
- 在 `PlayerConnectionConfirmed` 事件内将点歌重同步连接恢复为 `hasConnected=true`，既提供真实完成信号，也继续避免重复 motd 与重复 `PlayerJoin`。
- 新增玩家菜单“我的已同步音乐”：`/music synced` 打开列表，`/music replay <编号/名称>` 可直接重播。本次连接中成功同步的歌曲会记录在内存，玩家退出时清除；手动播放不会重复进行资产/世界同步。

## 2026-07-16：点歌上行保护与短暂播放中断修复

- 确认官方/B477 的运行时 Data Asset 表是全服共享的：只要把排队歌曲提前挂入 `state.data`，期间进入服务器的新玩家也会被原版进服握手要求下载这些歌曲。旧实现还会同时保留同步队列内多首歌曲，可能让新玩家一次被动同步全部排队音乐，表现为在线人数已经增加但客户端长时间没有核心机，并持续占满上行。
- 点歌歌曲不再在玩家点击同意时立刻注册；仅轮到某一名玩家实际同步时挂载当前一首歌曲，任务结束或超时立即卸载。排队歌曲不进入全局资产表，超时后也不再额外保留两分钟“迟到确认”资产，避免扩大到后来进服玩家。
- 运行时新音乐同步不再按全服在线人数直接禁用，避免人多时所有玩家都无法点歌。同步仍使用单人串行队列（默认最多等待 6 人），每名玩家完成后至少等待 5 秒；开始下一次同步前还会检查上行估算和其他玩家是否正处于资产/世界加载阶段。旧 `/music limit syncplayers` 参数仅返回已移除提示。
- 已同步歌曲仍可在高人数下直接播放，不触发资产/世界重同步。同步成功后无论玩家是否在过程中切换投票选择，都会记录资产已经到达，避免再次同意时重复下载同一首歌。
- `/music queue` 增加正常、协商资产、接收资产、等待世界确认等连接状态，后台也会记录容量保护取消及“新玩家在临时音乐资产挂载期间进入”的警告，便于定位无核心机与人数异常。
- 小音效因官方 sound ID 问题借用 `playMusic` 通道，过去会用 `interrupt=true` 直接停止正在播放的点歌。现在对正在听点歌的玩家跳过该次小音效，优先保证长音乐不中断；管理员 `/sfx sync` 在音乐同步期间或在线超过 4 人时会被拒绝，防止一次让全服重新加载世界。

## 2026-07-16：159地图脚本收缩与重点兼容

- 地图脚本维护范围收缩为 `14668`、`15450`、`tags/hybrid`、`tags/flood`，保留 `mapScript/module.kts` 与 `mapScript/lib/*` 框架；其余57个旧地图脚本及专用辅助文件从生产目录移除，Git历史仍可恢复。
- `15450` 的主循环 `yield()` 改为短 `delay`；保留 `shop` 别名，通过 `/mapcmd shop` 打开地图灵魂升级菜单。
- `14668` 将159不再接受的顶层 `quell.weapons.0.bullet.spawnUnit` 内容补丁改为 `quell` 内部字段路径；移除100ms彩色模式名循环和2秒一次的全服规则同步；空敌方列表不再调用 `random()`。
- 地图杂交保留基因变化后的全服自动同步，159 下优先走 `sendWorldAndAssets`，旧端回退 `sendWorldData`；连续变化防抖后按350ms逐人发送。
- 洪水循环增加运行时异常隔离与慢tick限频日志，防止单个异常建筑终止整个玩法，也为大图性能定位提供数据。

## 2026-07-07：官方 Mindustry v159 兼容层文档

- 新增官方端兼容层说明文档：docs/official-v159-compat.md。
- 后续凡是为官方 v159 做的反射兼容/no-op 降级，应同步记录原因、涉及脚本、切除方式，避免等 MindustryX 跟进后遗留临时兼容代码。

## 2026-07-08：投票菜单 SC 与风控限速小优化

- `/vote sc <内容>`（别名 `/vote superchat`、`/vote 醒目留言`）加入投票菜单：仅 1 级信任及以上可用，每名玩家 2 分钟冷却；该入口不会创建实际投票，也不会弹投票菜单，只在全服中屏和聊天栏显示一条 SuperChat/醒目留言。
- 投票踢出与投票强制观战不再在发起阶段直接拦截“目标为管理员”；允许玩家完整发起投票流程，但在投票通过后再次检查目标权限。若目标是管理员，只广播“错误：xxxx为管理员，如有问题请与服主联系”，不执行踢出/强制观战。
- `securityGuard.kts` 将聊天与指令限速提示拆分：普通聊天继续沿用原阈值，指令输入阈值按配置 `commandLimitMultiplier`（默认 2 倍）放宽；`/t` 等队伍聊天指令同样会进入该限速链路，防止用指令刷屏。

## 2026-07-10：换图门槛、队伍指令与无限火力关闭修复

- `/vote map` 与 `/vote nextmap` 的通过门槛从默认 50% 调整为 51% 向上取整；例如 5 赞 / 5 反不再通过，需要 6 赞 / 5 反这类超过半数的赞成票。投票列表与帮助菜单同步标注“需51%同意”。
- `/team` 不再允许普通玩家直接切换自己的队伍；客户端执行该指令需要信任 3+ 级/4级或管理员权限。`/team <队伍ID> <玩家3位ID>` 仍仅管理员可改他人队伍；自换队也改为全服广播，避免无提示绕过地图限制/队伍玩法。
- `/team` 帮助入口从管理分区移入玩家分区中的“3+队伍管理”，依赖命令可见性过滤，低于 3+ 的玩家不会在固定帮助菜单中看到该项。
- `/skill infinitefire [on/off]` 支持管理员手动关闭脚本开启的无限火力promax；`funRuleModes.disableInfiniteFire` 对规则恢复、电网/炮塔热量门槛恢复和 `Call.setRules` 做了错误隔离，避免关闭时抛出异常导致菜单/指令报错。若仍有字段恢复失败，会停止维护循环并广播建议换图或重启完全恢复。

## 2026-07-16：上游 v159.6 / MindustryX B477 / ScriptAgent 3.4 复核

- 参考源码已更新：官方 Mindustry 定位 `v159.6`；MindustryX 定位 `prerelease-2026.07.15.B477`（官方 v159.6 基线）；ScriptAgent 增加 `v3.4.0` 发布标签参考，并将 8.0 开发分支更新到 `6f16791`。
- MindustryX B477 已包含 X35 时本服手工处理过的 `LogicBlock$LogicBuild.interactable` headless 崩溃修复，并增加 Data Patch 运行时新增物品/单位的客户端数组越界修复；本轮按独立升级 X 的方式验证，不和 SA 升级同时进行。
- 官方 v159.1 至 v159.6 没有改变 `sendWorldAndAssets` 的完整资产/重进世界握手。该接口不是轻量单资产同步；点歌、小音效和运行时 CP 补发资产仍可能让玩家进入无核心机/单位消失状态。
- SA v3.4.0 是 K2、`.metadata` 模块、数据库模块与 Command API 的结构性迁移，不能只替换 JAR。当前脚本树的旧式 `context(CommandContext)` 扩展、数据库 API 和模块布局均需迁移。
- SA v3.4.0 的 `mapAssets` 只完成 Data Asset 注入，但 `wayzer/maps.manager.kt` 仍用 `sendWorldData` 向在线玩家发送新地图，没有补齐资产需求握手；直接改成 `sendWorldAndAssets` 也必须配套完整 `WorldReloader` 玩家状态流程。
- 详细结论见 `docs/upstream-review-2026-07-16.md`，官方兼容层风险说明同步更新于 `docs/official-v159-compat.md`。

## 2026-07-16：MindustryX B477 部署验证

- `mdtserver/server.properties` 的运行 JAR 已切换为 `server-2026.07.15.B477.jar`；ScriptAgent 保持 `3.3.2`，本轮不混入 SA 3.4 迁移。
- 先移动旧脚本编译缓存，再进行完整冷启动编译。最终结果为：`共找到201脚本,加载成功197,启用成功144,出错0`，地图特色杂交脚本与数据库、CP、音效等主链路均完成启用。
- B477 保留 `SendPacketEvent` 与 `LogicBlock.running` 等当前脚本使用的 X API，因此上行统计/逻辑包保护不再走官方端缺失事件的降级分支。
- X35 的单连接快照节流接口在 B477/v159 已被移除或更改，`syncThrottle.kts` 保持反射探测失败后 no-op；警告文案已改为明确说明 B477 API 变化。不要在生产切换这一轮同时重写实体同步接管。
- 正常启动期间无脚本错误。测试主动退出后，SA 3.3.2 集中卸载多个脚本时出现 3 秒停止超时，并触发一次 watchdog 停顿记录；这是停服阶段的既有卸载行为，不计入 B477 运行错误。
- 生产观察重点：玩家进出、换图、外部 CP 加载/卸载、点歌资产同步、上行统计与逻辑包保护。运行中若出现 watchdog 停顿，应与停服卸载日志区分。
# 2026-07-22：崩溃自动重启与 v159 ZIP CP 热重载

- `start-server.ps1` / `start-server.sh` 默认启用异常退出自动重启；正常 `exit`、常见 Ctrl+C/终止信号以及退出码 0 不重启，服务端主动退出码 2 仍按原逻辑快速重启。
- 连续崩溃按 5、10、20、40、60 秒退避；稳定运行 300 秒后清零连续崩溃计数，可通过 `server.properties` 调整或关闭。
- `externalCpHotReload.kts` 新增 `.json5` 与 Mindustry v159 `.zip` Data Assets 支持，ZIP可同时提供 patches、content、bundles、sprites、sounds、music。
- 热重载改为重建完整 `DataManager` 资产集合；卸载 ZIP 会撤销其新增内容与二进制资产，不再只处理补丁字符串。
- 增加 ZIP 路径、数量、单文件/累计解压大小、压缩比、PNG尺寸、资产冲突、Patch/Content错误、缓存缺失和并发操作保护。
- 失败时恢复操作前的完整 Data Assets，再执行建筑、电网、炮塔和实体兼容修复；JVM级崩溃由启动监督器兜底重启。
- 实际冒烟验证通过：156脚本、加载152、启用148、错误0；临时ZIP的 Patch/Content/Bundle/Sprite 成功加载并完整卸载。
- 详细格式和边界见 `docs/v159-data-assets-hot-reload.md`。
# 2026-07-22：点歌同意率与统一封禁菜单

- 点歌冷却判定的同意率分母改为“同意+中立+拒绝”的已表态人数；未表态玩家仍在投票结果中单独显示，但不再拉低同意率、触发不必要冷却。
- `/banlist`（`/bans`、`/封禁列表`、`/封禁管理`）新增统一管理菜单，合并数据库 `PlayerBanV2` 未到期玩家封禁和安全风控 IP 封禁。
- 列表按到期时间排序，显示封禁类型、玩家/IP、原因和剩余时长；详情页显示关联ID/UUID、操作人及起止时间，并可快速解封。
- `PlayerBanStore` 增加 `listActive()`；`securityGuard` 提供只读 IP 封禁视图与管理解封接口；`MdtStorage` 增加批量主体名称查询，避免逐条数据库事务。
- 冷启动验证：156脚本、加载152、启用148、错误0；通过命令Socket实际执行 `/banlist`，成功读取并合并当前玩家封禁和 IP 封禁。

# 2026-07-22：玩家面板直接强制观战收紧

- `playerInfoTripleTap.kts` 将“直接强制未登录游客观战”的门槛从信任2级提高到3级；游客边界改为实时检查 `PlayerData[target].authed == false`，不再把已登录但资料等级显示0的玩家当作游客。
- 3+对低于3+玩家的原有直接处理权保留；二级玩家仍可使用 `/vote ob`。
- `voteOb.kts` 新增非管理员面板直接强制观战成功冷却，默认120秒，按登录主体 UID 计时；只在实际成功施加后记录。
- 强制观战元数据记录 `direct/admin/vote/system` 来源与操作者 UID；非管理员仅可解除自己的 `direct` 记录，等级后续下降也仍可主动撤回。管理员保留全局解除，旧记录按 `legacy` 处理。
- 面板按钮执行前与理由输入后各做一次实时权限/目标状态/冷却校验，防止菜单打开后目标登录、操作者降级或其他人已经处理造成竞态。
- 冷启动脚本编译验证通过：`156` 个脚本、加载 `152`、启用 `148`、出错 `0`；`voteOb` 与 `playerInfoTripleTap` 均成功加载/启用。

# 2026-07-22：3++ 插件协管等级

- 信任层级新增 `3++`（别名 `3pp` / `3plusplus`），排序位于3+与4之间，赞踩额度按3级处理。`3++` 与4级一样不会自然晋升，也不被自动晋升系统降级，只能由4级/admin或控制台手动任命/撤销。
- `trustLevel.kts` 为3++加入独立 `@pluginAdmin` 组，不加入 `@admin`、不设置原生admin，只注册明确白名单权限。3++继承3+玩法/社区权限，可处理低于3++的目标，4级保留全局管理。
- `suffix.kts` 将管理图标与 `/suffixmark` 命令权限拆分：3++通过 `suffix.staffMark` 显示与4级相同的管理图标，但不获得 `suffix.admin`，不能修改/隐藏后缀。等级变化会清理后缀缓存并刷新名字。
- 3++获得 `wayzer.admin.skipKick`，普通投票踢出/投票强制观战无法对其生效；玩家面板、`/forceOB`、禁建和封禁入口都改为分层目标检查。高人数强制观战/同IP清理也保护3++/4级。
- 白名单指令：`/host`、`/gameover`、`/banX`、`/unbanX`、`/banlist`、`/banip`、`/unbanip`、`/banips`、`/forceOB`、`/recentplayers`、`/logicdraw`、`/blockban`、`/blockunban`、`/forceobclean status|run`、`/music stopall|cancel`；`/gamepause` 等原3+直接权限自动继承。
- 3++账号/IP封禁默认上限7天，仅可解除自己施加的记录；统一封禁菜单和IP菜单都根据操作者UID决定是否显示解封按钮。
- `/host` / `/gameover` 对3++增加15秒重复输入确认、成功后共享5分钟冷却与审计日志；4级/admin和控制台保持原行为。
- 明确不授权：`@admin` 通配、`/achat`、`/suffixmark`、信任/资历/MDC/账号管理、神权/管理员技能、ScriptAgent、CP/世界处理器、存档加载、重启/退出及其他世界改写权限。
- `skills.kts` 的管理员技能前置对3++增加硬性排除；即使某个3++被手动设为资历4，也不会看到/使用管理员技能。神权菜单原本就仅检查信任4级/admin。
- 最终冷启动验证通过：`共找到156脚本,加载成功152,启用成功148,出错0`；目标日志未发现 `Unresolved reference`、编译失败或字段保存异常。主动 `exit` 后仍有少量既有脚本卸载超时，属于停服阶段，不影响本轮3++脚本加载与启用。

# 2026-07-22：收紧3+直接限制目标

- 3+通过玩家面板或相关脚本直接强制观战、禁建时，只能处理信任等级低于3的玩家，即0/1/2级；不能再直接处理3级玩家。
- 目标边界使用独立的 `canDirectRestrictTrustTarget` 判断，避免把禁言、账号封禁等其他分层权限一并误收紧。3++仍可处理低于3++的目标，4级保留全局管理。
- 冷启动验证通过：`共找到156脚本,加载成功152,启用成功148,出错0`；未发现本轮相关编译或启用错误。
