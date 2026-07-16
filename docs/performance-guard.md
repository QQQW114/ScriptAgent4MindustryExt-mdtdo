# 性能优化系统

## 脚本路径

```text
mdtserver/config/scripts/wayzer/map/performanceGuard.kts
mdtserver/config/scripts/wayzer/map/performanceGuardExperimental.kts
mdtserver/config/scripts/wayzer/reGrief/trafficMonitor.kts
mdtserver/config/scripts/wayzer/map/serverPressure.kts
mdtserver/config/scripts/wayzer/map/serverPressureActions.kts
mdtserver/config/scripts/wayzer/reGrief/syncThrottle.kts
mdtserver/config/scripts/wayzer/reGrief/inactivePressureCheck.kts
mdtserver/config/scripts/wayzer/reGrief/unitLimit.kts
mdtserver/config/scripts/wayzer/ext/mainThreadWatchdog.kts
```

相关改动：

```text
mdtserver/config/scripts/wayzer/map/mapFilter.kts
mdtserver/config/scripts/coreMindustry/menu.kts
mdtserver/config/scripts/coreMindustry/scoreboard.kts
```

## 设计

性能优化拆为“模式入口 + 检测 + 执行”三层：

1. `performanceGuard.kts`：保留 `/perf`、`/vote perf` 与 `performanceGuard.mode` 模式管理。默认 `normal`。
2. `performanceGuardExperimental.kts`：保留 `/xperf`、`/vote xperf` 与实验性模式切换/兜底兼容。默认不自动启用。
3. `trafficMonitor.kts`：监听 `mindustryX.events.SendPacketEvent`，估算应用层上行需求并换算 Mbps。
4. `serverPressure.kts`：综合 TPS、估算上行、玩家/单位/子弹/同步实体数量，输出压力等级。
5. `serverPressureActions.kts`：按压力等级执行实际措施。
6. `syncThrottle.kts`：仅实验性模式上行超限时接管同步频率。
7. `inactivePressureCheck.kts`：仅实验性模式上行超限时进行挂机弹窗检测。
8. `mainThreadWatchdog.kts`：独立诊断“聊天正常但单位/世界不更新，恢复后玩家被拉回”的主线程停顿。

`performanceGuard.mode`：

- `normal`：常驻保守优化工作，标准性能优化也介入 PVP。
- `off`：性能优化系统不自动介入。
- `experimental`：实验性优化接管，启用上行优化、同步限制和挂机检测。

旧脚本内的本地 TPS 清理循环已默认关闭，避免双重 TPS 检测/双重规则覆盖；实际自动执行以 `serverPressureActions.kts` 为准。`unitLimit.kts` 的常驻“超单位数立即杀单位”也默认关闭，单位清理由压力等级接管；其超大敌方单位/终结波自动投降兜底仍保留。

## 压力等级

- 等级 1：关闭火焰生成、清理已有火焰/子弹，并开始少量清理 1 阶低级单位。
- 等级 2：暂停波次、推迟 `wavetime`、设置临时单位上限，继续清理更多 1-2 阶非玩家/非玩家队伍单位。
- 等级 3：仅实验性模式启用；关闭世界处理器/逻辑处理器，并清理更多/更高阶单位。
- 等级 4：标准模式触发兜底暂停；实验性模式触发兜底强制换图；仅实验性模式下，当 TPS 与上行同时超限时，会统计当前非玩家普通单位数量并击杀数量前三的单位种类。

单位清理优先级：

- 永远不清理玩家正在控制的单位、核心出生单位、限时单位和方块挂载单位。
- 优先处理波次队伍/非玩家队伍，再考虑玩家队伍；PVP 中玩家队伍优先级最低。
- 使用显式单位阶级表判断 Serpulo / Erekir 单位，不再按血量或花费粗暴排序，避免 Erekir 低阶单位因血量/权重更高而挤掉 Serpulo 高阶单位。当前口径按 `docs/hybrid-unit-catalog.md` 的 T1-T5 单位表维护。
- 实验性疑似 PPS 顶满清理会保留低阶辅助/可挖矿单位 `mono`、`pulsar`、`quasar`、`poly`、`mega`，清理其它 T1-T3 单位，并额外清理 `quell`、`disrupt`、`anthicus` 和场上 `scathe` 炮台；标准模式不会触发这条多人退出启发式清理。
- 清理单位使用 `kill()` 走原版死亡同步链路，不再直接 `remove()`；炮台使用建筑 `kill()` 触发原版建筑销毁同步。

标准模式不再因为 PVP 地图跳过，但执行措施会尽量优先处理火焰、子弹、非玩家单位，降低对 PVP 玩法的直接破坏。

回退规则：

- 压力等级降低后会逐步回退高等级措施，而不是等到完全恢复才一次性解除。
- 降到等级 1：恢复等级 2/3 措施，例如波次、单位上限、处理器；继续保留等级 1 的清火/清子弹。
- 降到等级 2：恢复等级 3 实验性措施，例如处理器；继续暂停波次并限制单位。
- 降到等级 0：恢复全部规则。
- 压力措施只用 `Call.setRule` 同步被实际修改的规则字段（火焰、波次、单位上限、世界处理器开关等），不再每 5 秒 `Call.setRules` 全量覆盖 Rules，避免把玩家客户端本地显示类改动（例如去战争迷雾）反复同步回去，也减少不必要上行。
- 为防止 TPS/上行在阈值附近反复横跳，非零降级默认需要连续 2 次稳定采样；退出压力也需要连续达到恢复线。
- 压力措施播报只在本轮压力周期首次升到更高等级时提示，降级回退不刷屏。
- 性能优化被手动关闭（`/perf off` 或切到 `off`）时，`serverPressureActions.kts` 会优先读取当前模式并立即停止后续措施，同时回退已修改的规则、恢复被自动关闭的处理器，并解除由性能系统自动触发的暂停。

## 上行优化

`trafficMonitor.kts` 统计的是服务端应用层尝试发送的包大小乘以目标连接数，属于“同步需求估算”，不是云服务器网卡被硬限速后的实际出口速率。

默认参数：

- 上行预算：18 Mbps。
- 恢复线：预算的 90%，即默认约 16.2 Mbps。

实验性模式下：

- 估算上行超过预算时，`serverPressure.kts` 提升同步限制等级。
- `syncThrottle.kts` 参考柠檬 `betterSync.kts`，临时推迟原版同步并按当前等级自定义发送状态/实体快照。
- 上行持续超限会逐步增大同步间隔：超过预算约 6 轮提升到至少 2 级，约 12 轮提升到至少 3 级。
- 同步限制启用后默认至少保持约 45 秒；降级需要连续 3 次稳定采样，完全退出需要连续 6 次低于恢复线，避免“限速后恢复—退出—再次超限”的循环。
- 同步限制的开启/解除提示带最小播报间隔，避免上行在预算线附近波动时反复刷屏。
- 同步限制期间会记录单位生成/销毁事件：生成后按冷却触发额外快照；销毁事件在下一帧向受限连接补发 `UnitDestroy` 包，降低客户端错过单位消失造成幽灵单位/不同步的概率。
- 同步限制发送实体快照前会调用 `beforeWrite()`，并取消每玩家每次同步的实体类名排序，减少高实体量时的 CPU/分配浪费。
- 隐藏实体 `hiddenSnapshot` 带按玩家缓存和最小刷新间隔，隐藏列表不变时不再每次受限同步都重复发送，避免上行优化效果被重复隐藏包抵消。
- `syncThrottleEnabled` 默认开启；如怀疑实验性同步限制导致“聊天正常但世界/单位短暂停止同步后回弹”，可临时设为 `false` 关闭该接管层，但保留其它压力判断与措施。
- 单玩家受限快照发送耗时超过 `sendCostWarnMillis`（默认 120ms）会按冷却输出日志，便于判断是否是同步快照本身过重。
- `inactivePressureCheck.kts` 会在上行超限时和之后每 15 分钟发送挂机确认，无响应玩家会被踢出服务器；3+/4级不做豁免。
- 当估算上行达到预算的 60% 以上，并在 2 秒窗口内出现 2 名及以上玩家退出或 3 名及以上连续退出时，视作疑似 PPS 顶满，触发一次低阶单位/导弹源清理；该策略仅实验性模式启用，并带独立冷却，避免重复刷屏。Mindustry `PlayerLeave` 事件无法可靠提供 timeout 原因，因此这里按短时间异常退出启发式处理。
- 当估算上行需求超过预算 200% 时，触发严重超量兜底：清理 T4 及以下单位（即保留 T5），并广播“上行需求量严重超量（>200%），已清理t5以下所有单位”。该策略仅实验性模式启用，并带独立冷却。

## 主线程卡顿诊断

`mainThreadWatchdog.kts` 用于定位服务端短暂停止推进游戏逻辑的情况。典型表现是玩家聊天仍可收发，但单位、建筑、伤害与世界同步暂停，恢复后玩家被拉回。

- 后台线程默认每 500ms 检查一次游戏线程更新时间；游戏线程超过 3000ms 未推进时记录“主线程疑似停顿”以及主线程、网络、协程、寻路等相关线程堆栈。
- 游戏线程恢复后会补一条“主线程刚从 xx ms 停顿中恢复”日志；若没有提前抓到堆栈，只能说明检测线程未抢到卡顿窗口。
- `/tickwatchdog status` 可查看诊断开关、距上次游戏更新时间、累计更新帧与已捕获主线程名称。
- 服务端手动退出、测试脚本中断或 JVM 正在关闭时可能出现一次误报；需要结合实际玩家反馈与前后日志判断。

## 暂停/继续

- 标准模式达到等级 4 时会自动暂停当前游戏。
- 玩家可通过 `/vote resume` 投票继续。
- 也支持 `/vote pause` 投票暂停。
- 3+级、4级/admin 或控制台可用 `/gamepause on|off|status` 直接管理暂停状态。

## 指令

常驻保守：

- `/perf status`
- `/perf on`
- `/perf off`
- `/perf reset`
- `/vote perf on`
- `/vote perf off`

实验性：

- `/xperf status`
- `/xperf on`
- `/xperf off`
- `/xperf stage <1-4>`（兼容旧手动阶段入口）
- `/xperf fallback`
- `/vote xperf on`
- `/vote xperf off`

上行/压力：

- `/traffic status`
- `/traffic budget <Mbps>`：仅 4级/admin 或控制台。
- `/traffic reset`
- `/pressure status`
- `/pressure tps`：查看当前 TPS 压力阈值。
- `/pressure tps <L1> <L2> <L3> <L4> [恢复]`：3+级、4级/admin 或控制台修改 TPS 触发阈值，例如 `/pressure tps 45 35 30 25 55`。
- `/pressure tps reset`：恢复默认 TPS 压力阈值。
- `/tickwatchdog status`：查看主线程卡顿诊断状态。

暂停：

- `/gamepause on|off|status`
- `/vote pause`
- `/vote resume`

## 积分板显示

- `coreMindustry/scoreboard.kts` 原有 `scoreboard.ext.server-status` 继续显示 TPS/内存。
- `trafficMonitor.kts` 注册 `scoreboard.ext.traffic`，显示 `估算上行: x.xx Mbps`。
- `serverPressure.kts` 注册 `scoreboard.ext.pressure`，仅在压力等级或同步限制大于 0 时显示。

## 存储

- `performanceGuard.mode`：当前性能优化模式。
- `performanceGuard.experimental.previousMode`：开启实验性前的模式。
- `performanceGuard.experimental.disabledLogicPositions`：被实验性措施关闭的逻辑处理器位置。
- `performanceGuard.experimental.forceChangingMap`：实验性兜底换图时临时绕过地图筛选。
- `trafficMonitor.budgetMbps`：实验性上行预算。

## 注意事项

- 估算上行是“发送需求”，用于判断服务器同步压力；如果云服务器硬限制 20 Mbps，实际网卡可能被限制在 20 Mbps，但本脚本仍尽量估算需求端压力。
- 同步限制只在 `experimental` 模式且上行超限时启用；标准模式不会改同步频率或踢挂机玩家。
- 标准模式的等级 4 暂停不会自动继续，需要投票或管理指令恢复。
- 实验性兜底换图会临时绕过 `mapFilter`，避免坏图无法脱离。
- 如果处理器被关闭后脚本异常重载，通常换图即可彻底恢复地图状态。
