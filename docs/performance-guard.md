# v159 统一性能优化与网络同步保护

## 脚本组成

```text
wayzer/map/performanceGuard.kts
wayzer/map/performanceGuardExperimental.kts
wayzer/reGrief/trafficMonitor.kts
wayzer/map/serverPressure.kts
wayzer/map/serverPressureActions.kts
wayzer/reGrief/worldResyncCoordinator.kts
wayzer/reGrief/syncThrottle.kts
wayzer/reGrief/inactivePressureCheck.kts
wayzer/map/adaptivePlayerLimit.kts
wayzer/reGrief/unitLimit.kts
wayzer/ext/mainThreadWatchdog.kts
```

## 设计边界

v159.7/MindustryX B480 后把压力拆成两条互不混用的链路：

1. **性能压力**：TPS 与“游戏同步上行”。它可以触发清火、清子弹、分级清单位、暂停波次、关闭处理器和极端换图；自动暂停游戏已于 2026-08-06 移除，`/gamepause`、`/vote pause|resume` 的手动暂停保留。
2. **网络压力**：总上行、世界/资产流、TCP 待发积压、待加入连接。它只触发快照降频，不得触发单位清理或阻塞玩家入服。

这样做是为了避免玩家加入、音乐、CP 或地图资产同步产生的瞬时大流量，被误判成单位过多并清理世界。

## 流量口径

`trafficMonitor.kts` 读取 **Windows 网卡累计 Sent 字节**（`netstat -e`）计算真实出口速率（**仅支持 Windows，不支持 Linux**）：

- **网卡上行（总/同步/世界流同源）**：服务器网卡真实出口速率，尽量包含为玩家同步的所有内容——世界流同步、单位同步、世界同步、音乐、CP、聊天/UI 等；这是性能优化系统的硬依赖。
- 不再依赖 MindustryX `SendPacketEvent` 区分包类型；网卡聚合无法区分“游戏同步”与“世界/资产流”，因此总上行/同步上行/世界流三口径同源。

积分板显示：

```text
总上行: x.xx Mbps
```

该数据是网卡真实出口速率（含协议开销），不是应用层发送需求估算；`/traffic status` 还显示待加入连接、最老等待时间、TCP 待发字节及拥塞连接数。

## 压力等级

`serverPressure.kts` 每 5 秒采样，默认使用 6 个样本：

- TPS：L1 `<45`、L2 `<35`、L3 `<30`、L4 `<25`，恢复线 `>=55`。
- 游戏同步上行：预算 `100% / 115% / 140%` 对应 L1/L2/L3。
- 最终性能等级取 TPS 与游戏同步上行中的较高值。
- 世界/资产流只生成 `networkLevel`，不进入最终性能等级。

非零降级和完全恢复均有连续采样滞回，避免阈值附近频繁启停。

## 分级措施

`serverPressureActions.kts` 是唯一的性能措施执行器；`performanceGuardExperimental.kts` 仅保留旧指令兼容入口，不再维护第二套清理、暂停或换图逻辑：

- **等级 1**：关闭火焰规则（`state.rules.fire = false`，O(1) 规则开关）、清理子弹，少量清理 T1 压力单位。
- **等级 2**：在出波暂停开关开启（默认）时暂停波次、推迟 `wavetime`（**30 秒 = 1800 刻**，见下"波次恢复"），降低单位上限，继续清理 T1-T2 非玩家压力单位。
- **等级 3**：关闭世界处理器及已有逻辑处理器，扩大到玩家队伍并清理 T1-T3。
- **等级 4**：清理到 T5 范围，并处理数量最多的前三种压力单位；不再自动暂停游戏，持续极端低 TPS 走下方“极端换图兜底”。

每轮只使用当前等级对应的清理预算，不再把 L1-L4 预算累加；L4 默认最多使用其自身的 400 个预算，而不是旧实现误清 750 个。

进入新性能等级时会广播实际采取的重要措施，例如清理子弹、限制单位、关闭世界处理器和逻辑处理器；等级按 L1-L4 命名，L1-L3 本局每等级仅首次广播，L4 每次重新进入都播报。等级保持期间的持续清理只按默认 30 秒聚合写日志，不再向玩家广播；PPS、严重上行和 L4 数量前三清理的提示也按局去重，恢复播报每局一次。压力采样与措施循环都有逐轮异常隔离，一次实体或规则同步异常不会让整套系统永久停止。

压力降低时逐级恢复处理器、波次、单位上限与火焰规则。`/perf off`、投票关闭本局性能优化或脚本卸载时，也会恢复已经生效的可逆措施。

### 波次恢复（2026-09-12 修复"波次间隔卡住无法复原"）

**现象**：性能保护进入等级 2 后把波次间隔改得很大，压力恢复后无法自动复原，玩家侧表现为"波次间隔很久、等不来下一波"。

**根因**：`state.wavetime` 的单位是**游戏刻**（`Logic.java` 用 `rules.waveSpacing` 赋值，且按 `Time.delta` 递减，60 刻 = 1 秒）。
原实现写入的是 `60f * 60f * 10f` = **36000 刻 = 10 分钟**（不是 10 秒），而恢复时用的是

```kotlin
if (state.wavetime > savedWavetime && savedWavetime > 0f) state.wavetime = savedWavetime
```

当快照里的 `wavetime` 是 **0**（合法的"马上就要出波"状态——`Logic` 在 `wavetime <= 0` 时直接 `runWave()`）或快照未建立时，
该守卫**永不成立**，于是这 10 分钟的值一直留着，只能靠换图/重开一局（或 `/vote pause` 类脚本自己的快照）才清掉。

**修复**（`performanceGuard.kts` 与 `serverPressureActions.kts` 同步改）：

1. 推迟值从 **10 分钟收敛到 30 秒**（`60f * 30f` = 1800 刻）：压力期间足以避开出波，又不会长到影响体验；
2. 恢复改为**哨兵判定**：写入时记录"我们塞进去的那个值"，恢复时只有当 `state.wavetime` 仍等于它
   （说明期间没有别的来源改过）才回写快照值——快照值为 0 也照常恢复，让原版按原节奏出波；
   任何合法的 `wavetime` 变化（其它脚本/投票修改）都会让哨兵失效，**不覆盖**；
3. 同一处旧守卫也存在于 `/vote pauseWave`（`wayzer/cmds/vote.kts`）：其快照记录"推到的值"，
   恢复时按同一规则回退，避免暂停结束后同样卡住。

## 压力措施：unitCap 条目已移除（2026-09-19）

**背景**：用户报"服务器玩家多的时候核心机无法复活，修了很久只缓解没根治"。
排查后确认根因就在这条措施上：

- 原版队伍单位上限 = `Units.getCap(team)` = `max(0, rules.unitCapVariable ? rules.unitCap + team.data().unitCap : rules.unitCap)`
  （`核心/Units.java:124`，`unitCapVariable` 默认 true，核心块另有 `unitCapModifier` 加成）。
- 复活链路：`player.checkSpawn()` → `bestCore()` → `core.requestSpawn(player)` → `Call.playerSpawn(tile, player)`
  （`PlayerComp.java:248-253`、`CoreBlock.java:639-644`）；**名额（单位上限）不足时复活失败**是原版既知行为。
- 本项目的 L2 措施 `applyUnitCap()` 会**先把 `disableUnitCap` 置回 false（让上限生效）再把 `rules.unitCap` 压到
  `min(当前值, level2UnitCap=100)`**；超编单位还会被原版 `unitCapDeath` 清杀（`Units.java:50-53`、`UnitComp.java:605`）。
- 于是"玩家多 → 上行压力高 → 进 L2 → 单位上限被压到 100 → 队伍单位数早已超编 → 玩家死亡后复活不了"，
  与"只在人多时出现"的现象完全吻合。此前修的 `coreUnitRespawnCompat`/`worldResyncCoordinator` 只处理
  单位引用恢复，**不涉及名额**，所以只能缓解。

**处置（用户决定：直接取消这条措施，它与清理单位部分重叠）**：

- 删除 `serverPressureActions.kts` 的 `applyUnitCap()` 函数、其调用点（L2 分支）与 config key `level2UnitCap`；
- **保留**压力快照/恢复里的 `unitCap`/`disableUnitCap` 两行（不再改动这两个值，恢复写入等价值无副作用，
  还能自愈历史版本留下的残留值）；
- 出波暂停（`pushWaveTimeFloor`）、逻辑处理器禁用、单位清理（含 L4 的"数量前三单位清理"）**保持不变**——
  省上行的主力是这几条，unitCap 只是重叠手段。
- 验证：冷启动 `共找到157脚本,加载成功153,启用成功148,出错0` ✓。
- 仍待实测：需要一次"多人/低压复现"确认复活恢复正常（并观察上行是否因此变差——预计影响很小，因为清理措施仍在）。
## 火焰处理（2026-09-13：移除全部实体遍历）

160 移除了 `Groups.fire`，9-12 的适配曾把火焰相关逻辑统一改成 `Groups.all.count { it is Fire }`。
这次把这个口径整体撤回，**火焰不再通过实体遍历处理**：

- `wayzer/reGrief/limitFire.kts` **整脚本删除**（原实现每 tick 对 `Groups.all` 做一次全实体扫描统计火焰数）；
- `performanceGuard.kts` / `serverPressureActions.kts` 的 `clearFires()` 删除，`recordCleanup` 去掉 `fires` 维度；
- `wayzer/user/ext/skills.kts` 的 `clearNearbyFires()` 只保留按 tile 的写法（`Fires.has/extinguish/remove` + `Fx.fireRemove`），
  不再为了兜底再扫一遍全部实体。

保留的是 **O(1) 的规则开关** `state.rules.fire = false`（L1 进入时关闭火焰蔓延，压力恢复时还原），
它不会随实体数量增长，也不依赖 160 已删除的分组字段。也就是说"压力大时关火焰"的保护仍然有效，
被去掉的只是"遍历全部实体去数/去删火焰"这个本身就很贵的实现。

代价：不再主动删掉地图上**已经存在**的火焰，只阻止新的蔓延；原版火焰会自行烧完熄灭。
对性能等级判定无影响——火焰数量本来也没有作为压力输入，只是清理动作的一项统计。

## 单位清理规则

- 使用 `docs/hybrid-unit-catalog.md` 的 Serpulo/Erekir T1-T5 显式表；未知单位才按体型/血量回退估算。
- 不清理玩家当前附身单位、核心出生单位、限时单位、方块挂载单位和不可击杀单位。
- 优先清理波次队伍、非玩家队伍，再考虑玩家队伍；PVP 玩家队伍优先级最低。
- 使用 `kill()` 进入原版死亡同步链路，不使用容易产生幽灵单位的直接 `remove()`。

额外兜底：

- **疑似 PPS 顶满**：游戏同步上行达到预算 60% 以上，2 秒内出现至少 2 次退出或至少 3 次连续退出时，清理其它 T1-T3 单位，并额外清理 `quell`、`disrupt`、`anthicus` 与 `scathe`。保留 `mono`、`pulsar`、`quasar`、`poly`、`mega` 低阶辅助线。
- **严重超量**：游戏同步上行达到预算 200% 且已进入游戏同步最高压力级时，清理 T4 及以下单位，保留 T5；避免采样暖机阶段的单次尖峰误触发。
- 上述两项都只看游戏同步上行，玩家进服、音乐和 CP 世界流不会触发。

## v159 快照保护

旧 X35 脚本依赖的 `NetConnection.syncTime`、`snapshotsSent` 和逐玩家自定义实体序列化已不存在。`syncThrottle.kts` 不再重写整套同步，而是：

- 反射读取并调整 v159 原生 `Administration.Config.snapshotInterval`；
- 原生默认约 200ms，压力时只保守增大到 240/280/320ms，绝不会比原版更频繁；
- 正常恢复后还原脚本启用时的原始值；
- 不再拦截、取消或重发任何快照包，尤其不会把可靠的建筑血量更新降级为 UDP；
- 同步限制约 5 秒后即可随网络恢复快速退出，避免核心机、单位血量和死亡状态长期处于低频刷新。
- 每张地图首次进入同步限制时仅广播一次“同步限制与挂机检测将介入”；同局 L1/L2/L3 波动不重复刷屏。播报标记由长期运行的 `trafficMonitor.kts` 持有，单独热重载 `serverPressure.kts` 或 `syncThrottle.kts` 也不会重复播报，WorldLoad/Reset 才会清零。
- 压力判断快照超过默认 30 秒未更新时，快照频率保护恢复原生间隔，压力措施与挂机检测均 fail-safe，不依据旧高压状态继续处理。

注意：`snapshotInterval` 控制的是原版全局实体快照，确实包含占主要流量的单位位置/移动与玩家控制关系。增大间隔可能使高丢包时的移动插值更粗，但脚本本身不会删除单位、隐藏单位或重放位置。

带宽治理主要交给内部完整重同步串行、挂机检测和单位压力清理，快照降频只作为轻量保护。

## 压力挂机检测

`inactivePressureCheck.kts` 在 `throttleLevel > 0` 且在线玩家达到自适应人数上限的初始/基础值时启用（默认来自服务端启动时的 `playerLimit`，当前预期为 18）：

- 同步限制本身已经带有滞回，不再额外等待连续样本；满足限制等级与人数条件后即可进入检测；
- 默认 30 分钟最多弹出一次，避免上行反复波动持续打扰玩家；
- 压力快照超过默认 30 秒未更新时自动 fail-safe，取消当前检测，不依据旧快照踢人；
- 自适应人数脚本尚未完成启动接管时 fail-safe 暂不触发，不使用过小的固定人数误判；
- 玩家可通过按钮、聊天或点击地图确认，默认 90 秒无响应才移出；
- 同步限制解除、人数低于阈值、换图或 Reset 时取消未完成检测。

## 内部完整重同步协调器

`worldResyncCoordinator.kts` 统一接管音乐、小音效、地图杂交、技能杂交、外部 CP 和管理 CP 的完整世界/资产同步：

- 内部完整重同步全服串行，等待真实 `PlayerConnectionConfirmed` 后才正常完成；普通确认超时后继续占用槽位，孤儿传输最多再等 120 秒，仍异常则踢出目标连接，禁止释放槽位后与下一次世界流重叠；
- 显式优先级为管理/外部 CP、杂交、普通任务、点歌、SFX；队列默认上限 32、排队最长 180 秒，每次传输后统一保留 2.5 秒恢复间隔；
- 内部完整重同步始终使用自己的全服单队列和恢复间隔，不再读取待加入连接或网络等级；
- 区分内部重同步并抑制重复 MOTD、欢迎菜单和 `PlayerJoin`；
- 玩家离线、超时、换图和脚本卸载都会释放等待，旧地图任务自动作废；
- API 异常时 fail-open 回退普通世界同步，不允许形成永久锁。

`coreUnitRespawnCompat.kts` 已收缩为只在主动取消附身或 `PlayerConnectionConfirmed` 后处理核心机引用。确认后服务端已有核心单位时仅补快照，单位为空时才尝试 `checkSpawn()`。修复时最多发送两份 `Unit -> Player` 小型 UDP 快照；每次发送前都验证 generation、连接已完成、当前单位仍是同一核心单位。玩家附身任何非核心单位会立即作废旧修复。不再额外发送 `PlayerSpawnCallPacket`，也不再使用可靠实体快照，避免 TCP 拥塞后的旧控制关系闪回。

## 玩家入服策略

网络压力入服门控已删除。新玩家始终走 Mindustry 原生连接、世界与资产同步流程，不再在 `ConnectAsyncEvent` 阶段等待插件槽位，也不会因为确认事件丢失长期占用预留并卡住后续所有连接。`adaptivePlayerLimit.kts` 只统计自身观察到的 `PlayerConnect` 待确认玩家；内部 CP/点歌/杂交重同步仍由协调器串行，但不会阻塞首次入服。

网络保护不会因为 `/vote perf off` 而关闭。投票关闭的是本局世界清理/规则调整；原生快照安全保护仍保留。

## 极端换图兜底

- 等级 4 不再按若干采样后立刻换图。
- 只有当前 TPS 与滑动均值每次采样都低于 5、连续达到 120 秒，才允许执行最终随机换图。
- 任一当前 TPS 采样恢复到 5 或以上，以及换图、Reset、关闭性能优化，都会清零计时。
- 换图前不再先尝试暂停游戏（自动暂停已整体移除）；清单位、关闭处理器和网络降频在此期间仍持续生效。

## 开关与投票

- `/perf on|off|status|reset`：3+、4级/admin 或控制台管理持久模式。
- `/vote perf off`：只关闭本局性能优化，下局自动恢复服务器默认模式。
- `/vote perf on`：重新启用本局统一性能优化。
- `/xperf` 与 `/vote xperf` 保留为旧入口兼容，不再维护独立 `experimental` 模式。
- `/gamepause on|off|status`、`/vote pause`、`/vote resume` 管理游戏暂停。
- `/wavepause status|on|off`（别名 `出波暂停`）：3+、4级/admin 或控制台启停压力出波暂停；仅内存态、不落盘，默认开启，L2 及以上才会暂停出波，关闭时立即恢复波次规则。
- `/vote wavepauseon`、`/vote wavepauseoff`（别名 `开出波暂停`/`关出波暂停`）：50% 同意启停出波暂停，权限 `wayzer.vote.wavepause`。

## 诊断指令

```text
/traffic status
/traffic budget <Mbps>
/traffic reset
/pressure status
/pressure tps
/pressure tps <L1> <L2> <L3> <L4> [恢复]
/pressure tps reset
/adaptiveplayerlimit status
/tickwatchdog status
```

## 维护注意

- 不要重新引入逐玩家手工序列化全部实体；v159 已提供共享序列化和批量发送优化。
- 不要把 `averageTrafficMbps` 用于清单位；世界/资产流只能进入网络保护。
- 新的单位清理规则应集中维护在 `serverPressureActions.kts`，不要分散回 `unitLimit.kts`。
- **热路径（`Trigger.update` 监听、性能措施轮）禁止 `Groups.all` 全实体扫描**：160 里火焰/洼地仍作为实体存在于 `Groups.all`，
  一次遍历就是全部实体（单位+子弹+火焰+洼地）的代价，量级远大于它想统计的东西。需要按类别取实体时用 `Groups.unit/bullet/build/player`，
  火焰与洼地走 tile（`Fires`/`Puddles`）。
- 不要重新增加 `ConnectAsyncEvent` 入服阻塞链路；宁可暂时退回原版高流量，也不能永久阻止加入。
- 冷启动首次编译 Kotlin 脚本可能触发 watchdog 长停顿；应以服务端完成启动后的运行期日志为准。
