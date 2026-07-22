# v159 统一性能优化与网络同步保护

## 脚本组成

```text
wayzer/map/performanceGuard.kts
wayzer/map/performanceGuardExperimental.kts
wayzer/reGrief/trafficMonitor.kts
wayzer/map/serverPressure.kts
wayzer/map/serverPressureActions.kts
wayzer/reGrief/connectSyncGuard.kts
wayzer/reGrief/worldResyncCoordinator.kts
wayzer/reGrief/syncThrottle.kts
wayzer/reGrief/inactivePressureCheck.kts
wayzer/map/adaptivePlayerLimit.kts
wayzer/reGrief/unitLimit.kts
wayzer/ext/mainThreadWatchdog.kts
```

## 设计边界

v159.7/MindustryX B480 后把压力拆成两条互不混用的链路：

1. **性能压力**：TPS 与“游戏同步上行”。它可以触发清火、清子弹、分级清单位、暂停波次、关闭处理器、暂停游戏和极端换图。
2. **网络压力**：总上行、世界/资产流、TCP 待发积压、待加入连接。它只触发快照降频和入服同步门控，不得触发单位清理。

这样做是为了避免玩家加入、音乐、CP 或地图资产同步产生的瞬时大流量，被误判成单位过多并清理世界。

## 流量口径

`trafficMonitor.kts` 输出三项数据：

- **总上行**：普通网络包估算 + 世界/资产流实际读取进度；包括玩家进服、音乐、CP、聊天/UI 等。
- **游戏同步上行**：状态、实体、建筑、血量等正常游戏同步包；不包括世界流、资产流、音乐播放及连接握手。
- **世界与资产流**：所有 `Streamable` 数据的实际读取进度，主要用于观察玩家进服、地图、音乐和 CP 同步。

积分板显示：

```text
总上行: x.xx Mbps / 同步: y.yy Mbps
```

该数据是应用层发送需求估算，不是云服务器网卡限速后的真实出口速率。`/traffic status` 还显示活动流、待加入连接、最老等待时间、TCP 待发字节及拥塞连接数。

## 压力等级

`serverPressure.kts` 每 5 秒采样，默认使用 6 个样本：

- TPS：L1 `<45`、L2 `<35`、L3 `<30`、L4 `<25`，恢复线 `>=55`。
- 游戏同步上行：预算 `100% / 115% / 140%` 对应 L1/L2/L3。
- 最终性能等级取 TPS 与游戏同步上行中的较高值。
- 世界/资产流只生成 `networkLevel`，不进入最终性能等级。

非零降级和完全恢复均有连续采样滞回，避免阈值附近频繁启停。

## 分级措施

`serverPressureActions.kts` 是唯一的性能措施执行器；`performanceGuardExperimental.kts` 仅保留旧指令兼容入口，不再维护第二套清理、暂停或换图逻辑：

- **等级 1**：关闭火焰、清理火焰/子弹，少量清理 T1 压力单位。
- **等级 2**：暂停波次、推迟 `wavetime`、降低单位上限，继续清理 T1-T2 非玩家压力单位。
- **等级 3**：关闭世界处理器及已有逻辑处理器，扩大到玩家队伍并清理 T1-T3。
- **等级 4**：清理到 T5 范围，并处理数量最多的前三种压力单位；连续多轮仍过低会自动暂停游戏。

每轮只使用当前等级对应的清理预算，不再把 L1-L4 预算累加；L4 默认最多使用其自身的 400 个预算，而不是旧实现误清 750 个。

压力降低时逐级恢复处理器、波次、单位上限与火焰规则。`/perf off`、投票关闭本局性能优化或脚本卸载时，也会恢复已经生效的可逆措施。

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

注意：`snapshotInterval` 控制的是原版全局实体快照，确实包含占主要流量的单位位置/移动与玩家控制关系。增大间隔可能使高丢包时的移动插值更粗，但脚本本身不会删除单位、隐藏单位或重放位置。

带宽治理主要交给完整重同步串行和单位压力清理，快照降频只作为轻量保护。

## 内部完整重同步协调器

`worldResyncCoordinator.kts` 统一接管音乐、小音效、地图杂交、技能杂交、外部 CP 和管理 CP 的完整世界/资产同步：

- 内部完整重同步全服串行，等待真实 `PlayerConnectionConfirmed` 后才正常完成；普通确认超时后继续占用槽位，孤儿传输最多再等 120 秒，仍异常则踢出目标连接，禁止释放槽位后与下一次世界流重叠；
- 显式优先级为管理/外部 CP、杂交、普通任务、点歌、SFX；队列默认上限 32、排队最长 180 秒，每次传输后统一保留 2.5 秒恢复间隔；
- 正常网络直接执行；L1 时内部同步与首次加入共享 2 个槽位，L2+ 等待首次加入结束且全服只允许 1 个世界/资产流，首次加入始终优先；
- 区分内部重同步并抑制重复 MOTD、欢迎菜单和 `PlayerJoin`；
- 玩家离线、超时、换图和脚本卸载都会释放等待，旧地图任务自动作废；
- API 异常时 fail-open 回退普通世界同步，不允许形成永久锁。

`coreUnitRespawnCompat.kts` 已收缩为只在主动取消附身或 `PlayerConnectionConfirmed` 后处理核心机引用。确认后服务端已有核心单位时仅补快照，单位为空时才尝试 `checkSpawn()`。修复时最多发送两份 `Unit -> Player` 小型 UDP 快照；每次发送前都验证 generation、连接已完成、当前单位仍是同一核心单位。玩家附身任何非核心单位会立即作废旧修复。不再额外发送 `PlayerSpawnCallPacket`，也不再使用可靠实体快照，避免 TCP 拥塞后的旧控制关系闪回。

## 网络压力入服门控

`connectSyncGuard.kts` 监听 `ConnectAsyncEvent`，但只在 `networkLevel > 0` 时工作：

- 网络等级 1 默认允许 2 个世界/资产流槽位，首次加入与内部重同步共享；等级 2+ 全服默认只允许 1 个，且首次加入优先。
- 正常网络状态完全不限制入服。
- 等待队列默认最多 8 人、最长等待 12 秒；超时明确拒绝并提示稍后重试，不让连接无限停在无核心机状态。
- 门控只统计已经放行、真正进入同步的连接；等待者不占用同步名额。
- 压力数据超过 20 秒未更新、脚本关闭、协程取消或任意异常时自动 fail-open 放行。
- 玩家完成 `PlayerConnectionConfirmed`、退出或断线后释放预留；脚本卸载会清空全部内存状态。

网络保护不会因为 `/vote perf off` 而关闭。投票关闭的是本局世界清理/规则调整，入服 fail-open 与快照安全保护仍保留，避免上行满时重新形成幽灵连接。

## 极端换图兜底

- 等级 4 不再按若干采样后立刻换图。
- 只有当前 TPS 与滑动均值每次采样都低于 5、连续达到 120 秒，才允许执行最终随机换图。
- 任一当前 TPS 采样恢复到 5 或以上，以及换图、Reset、关闭性能优化，都会清零计时。
- 换图前会先尝试暂停，给清单位、关闭处理器和网络降频留出恢复机会。

## 开关与投票

- `/perf on|off|status|reset`：3+、4级/admin 或控制台管理持久模式。
- `/vote perf off`：只关闭本局性能优化，下局自动恢复服务器默认模式。
- `/vote perf on`：重新启用本局统一性能优化。
- `/xperf` 与 `/vote xperf` 保留为旧入口兼容，不再维护独立 `experimental` 模式。
- `/gamepause on|off|status`、`/vote pause`、`/vote resume` 管理游戏暂停。

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
- 任何入服或快照 Hook 必须 fail-open；宁可暂时退回原版高流量，也不能永久阻止加入或让在线玩家丢失快照。
- 冷启动首次编译 Kotlin 脚本可能触发 watchdog 长停顿；应以服务端完成启动后的运行期日志为准。
