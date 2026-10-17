# listen vs listenTo 审计报告：SA 自定义事件误用 `listen` 导致监听器静默失效

- 日期：2026-10-17
- 范围：`mdtserver/config/scripts/**`（208 个 `.kts`/`.kt` 文件）
- 背景：`wayzer/ext/liveEventFeed.kts` 在实现游戏内事件流时实测发现：`MapLoadFinishedEvent` 用 `listen` 注册监听器后**从未触发**，改用 `listenTo` 后正常。本报告对全仓同类问题做审计、修复与运行态验证。
- 结论：全仓共发现 **3 处** SA 自定义事件误用 `listen`（即任务点名的 3 处，无其它同类），已全部改为 `listenTo` 并热重载验证通过。其余 183 处 `listen` 均为原生事件，正确。

---

## 1. 问题说明：两套互不相通的事件总线

SA（ScriptAgent）脚本里存在两套事件派发机制，**同名但完全独立**：

| 机制 | API | 注册到哪里 | 由谁派发 | 适用事件 |
|---|---|---|---|---|
| arc / Mindustry 原生事件总线 | `listen<T> { }`（`coreMindustry/lib/ListenExt.kt`） | `arc.Events` 的私有 `events` 映射（按 class 键） | `Events.fire(event)` | `EventType.*` 等游戏原生事件 |
| SA 自定义事件总线 | `listenTo<T> { }`（插件 `cf.wayzer.scriptAgent.EventKt.listenTo`） | 事件类 `companion object : Event.Handler()` 实例里的 `EnumMap<Priority, List<Event.Listen>>` | `event.emitAsync()` / `launchEmit(...)`（内部 `event.handler.handleAsync`） | 实现 `cf.wayzer.scriptAgent.Event` 的自定义事件 |

关键点：

- `listen<T>` 的 `T` 是 `Any` 约束（`ListenExt.kt:86`），所以把 SA 自定义事件类传进去**能编译通过**，但监听器只进了 `arc.Events`；
- SA 自定义事件由 `emitAsync()` 走 `Event.Handler` 派发，**永远不会进 `arc.Events`**，因此 `listen<SA事件>` 注册的回调永不执行；
- 反向也成立：`listenTo<T>` 的 `T : Event`（SA 事件类型），原生事件传进去是编译错误，所以本类 bug 只有 "`listen` + SA 自定义事件" 一个方向。

判定口径（本报告使用）：

1. 事件的**定义**是否实现 `cf.wayzer.scriptAgent.Event`：有 `override val handler: Event.Handler` 或 `companion object : Event.Handler()` → SA 自定义事件，必须 `listenTo`；
2. 事件的**派发点**：`Events.fire(...)` → 原生（`listen`）；`emitAsync()/launchEmit()/handleSync()` → SA（`listenTo`）；
3. 反例核对：本仓不存在 "SA 自定义事件被 `Events.fire` 派发、监听方却用 `listenTo`" 的反向不匹配（见 §6），所以本次只需修 3 处。

---

## 2. 全仓审计口径与结果

扫描方法：递归读 `mdtserver/config/scripts` 下全部 `.kts`/`.kt`，正则提取 `listen<T>` / `listenTo<T>`，逐条按上述口径判定；`listen<EventType.*>` 因类型限定在 `mindustry.game.EventType` 内部，天然是原生事件，可批量归类，其余非 `EventType.` 前缀的逐条人工核对（查 import、定义位置、派发点）。

| 项 | 修复前（审计基线） | 修复后 |
|---|---|---|
| 扫描脚本文件 | 208 | 208 |
| `listen<...>` 出现次数 | **186** | 183 |
| `listenTo<...>` 出现次数 | **70** | 73 |
| SA 自定义事件误用 `listen` | **3**：`wayzer/ext/serverStats.kts:311`、`wayzer/map/betterTeam.kts:97`、`wayzer/map/pvpProtect.kts:152` | 0 |
| 其它 `listen`（原生事件） | 183 处，正确 | 183 处，正确 |

`listen` 事件类型分布（修复前 186 处，`EventType.` 前缀按类型汇总，全部原生、正确）：

| 事件类型 | 次数 | 事件类型 | 次数 |
|---|---|---|---|
| `EventType.ResetEvent` | 39 | `EventType.WorldLoadEndEvent` | 1 |
| `EventType.PlayerLeave` | 34 | `EventType.UnitCreateEvent` | 1 |
| `EventType.PlayerJoin` | 24 | `EventType.UnitChangeEvent` | 1 |
| `EventType.WorldLoadEvent` | 20 | `EventType.UnitBulletDestroyEvent` | 1 |
| `EventType.PlayerChatEvent` | 8 | `EventType.WaveEvent` | 1 |
| `EventType.GameOverEvent` | 8 | `EventType.StateChangeEvent` | 1 |
| `EventType.TapEvent` | 6 | `EventType.SaveWriteEvent` | 1 |
| `EventType.PlayerConnect` | 4 | `EventType.PlayEvent` | 1 |
| `EventType.UnitDestroyEvent` | 4 | `EventType.PickupEvent` | 1 |
| `EventType.BlockDestroyEvent` | 4 | `EventType.PayloadDropEvent` | 1 |
| `EventType.PlayerConnectionConfirmed` | 2 | `EventType.MenuOptionChooseEvent` | 1 |
| `EventType.BlockBuildEndEvent` | 2 | `EventType.DepositEvent` | 1 |
| `EventType.UnitDamageEvent` | 2 | `EventType.CoreChangeEvent` | 1 |
| `EventType.TileChangeEvent` | 2 | `EventType.ConnectPacketEvent` | 1 |
| 非 `EventType.` 前缀（见下表） | 7 | `EventType.ConfigEvent` | 1 |
| | | `EventType.BuildDamageEvent` | 1 |
| | | `EventType.TilePreChangeEvent` | 1 |

非 `EventType.` 前缀的 7 处 `listen`（**本类 bug 只可能出现在这里**）逐条判定：

| # | 文件:行 | 事件类型 | 类型来源 / 派发点 | 结论 |
|---|---|---|---|---|
| 1 | `coreMindustry/utilTextInput.kts:10` | `TextInputEvent` | 显式 `import mindustry.game.EventType.TextInputEvent`；原生 `Events.fire` | 正确 |
| 2 | `wayzer/reGrief/bugFixer.kts:16` | `ResetEvent` | 显式 `import mindustry.game.EventType.ResetEvent`；原生 | 正确 |
| 3 | `wayzer/maps.kts:199` | `WorldLoadBeginEvent` | 显式 `import mindustry.game.EventType.WorldLoadBeginEvent`；原生 | 正确 |
| 4 | `wayzer/reGrief/limitLogicPacket.kts:67` | `Any`（动态 class） | `mindustryX.events.SendPacketEvent`（`参考项目/MindustryX-main/src/mindustryX/events/SendPacketEvent.java:30` 用 `Events.fire` 派发）；`listen<Any>(sendPacketEventClass)` 用 Class 作 key，语义正确 | 正确 |
| 5 | `wayzer/ext/serverStats.kts:311` | `TrustLevelChangedEvent` | **SA 自定义**：`wayzer/lib/TrustSystemEvents.kt:65`（`override val handler = Companion`）；`emitAsync` @ `wayzer/user/trustLevel.kts:170,175` | **错误 → 已修复** |
| 6 | `wayzer/map/betterTeam.kts:97` | `MapLoadFinishedEvent` | **SA 自定义**：`wayzer/maps.manager.kt:48`；`emitAsync` @ `maps.manager.kt:169` | **错误 → 已修复** |
| 7 | `wayzer/map/pvpProtect.kts:152` | `MapLoadFinishedEvent` | 同上 | **错误 → 已修复** |

> 完整逐条清单：`listen` 见附录 A，`listenTo` 见附录 B。

---

## 3. 修复清单

每处仅改注册函数（`listen` → `listenTo`），保持原有行为与顺序；统一加一行注释防止回退：

| 文件:行 | 事件 | 改动 | 附带修正 |
|---|---|---|---|
| `wayzer/ext/serverStats.kts:311` | `TrustLevelChangedEvent` | `listen<...>` → `listenTo<...>` | lambda 内参数：`it.uid`/`it.newLevel` → `uid`/`newLevel`；`return@listen` → `return@listenTo`（见下方“listenTo 的 lambda 是 receiver”） |
| `wayzer/map/betterTeam.kts:97` | `MapLoadFinishedEvent` | `listen<...> { updateBannedTeam(true) }` → `listenTo<...>` | 无 |
| `wayzer/map/pvpProtect.kts:152` | `MapLoadFinishedEvent` | `listen<...> { startPvpProtect() }` → `listenTo<...>` | 无 |

合计 `git diff --stat`：3 文件、+10/−5（含 3 行中文注释）。**未改任何其它 `.kts`/`.kt`**。

> ⚠️ 修 `serverStats` 时第一次热重载编译失败：`Unresolved reference 'it'. (serverStats.kts:315:43)`。原因：`listenTo<T> { }` 的 lambda 是**事件 receiver**（`suspend T.() -> Unit`），事件属性直接用 `uid` / `this.info` 访问，没有隐式 `it`（对比 `liveEventFeed.kts:176` 的 `this.info`、`suffix.kts:26` 的 `uid`）。改成直取属性后重编译通过。

各事件影响的业务（修复后行为变化）：

- `serverStats`：在线玩家信任等级变化时，等级分布统计立即 `MdtStorage.moveStatsPlayerLevel` 迁移；**修复前该监听从未执行**，分布只在玩家下次进服（join 路径同样会迁移）时自愈，属于“即时性丢失”而非数据永久错误。
- `betterTeam`：换图完成后额外执行一次 `updateBannedTeam(true)`（解析地图 `@banTeam` 标签）。此前只有 `WorldLoadEvent`（更早、rules 未必最终态）和 `onEnable` 两个触发点，`MapLoadFinishedEvent` 这个“地图完全就绪后”的兜底一直没跑。注意该处与已有的 `WorldLoadEvent` 逻辑重复但幂等，不冲突。
- `pvpProtect`：**PVP 保护功能此前完全失效**——`startPvpProtect()` 全仓唯一调用点就是这个监听器。修复后 PVP 图加载完成会真正开始保护倒计时/禁区警告（本次只在 survival 图验证到事件到达，见 §5 未验证项）。

---

## 4. 运行态证据（修复前 / 修复后）

方法：控制台 `js`（Rhino）+ Java 反射，**只读**读两处注册表，不修改任何状态：

- SA 侧：取事件类的 `Companion`（即 `Event.Handler`），读其私有字段 `EnumMap map`，汇总各优先级 List 大小与每个 `Event.Listen.getScript().getId()`；
- arc 侧：读 `arc.Events` 私有静态字段 `events`（`ObjectMap`）的 `keyTable`，按事件类名匹配，读 `Seq` 大小与各监听器所属脚本 id。

修复前（服务器 PID 2572，B509，0 玩家）：

```
MLF=[mapScript,wayzer/map/logicDrawGuard,wayzer/ext/liveEventFeed]                       # SA handler 3 个
TLC=[wayzer/user/seniorityLevel,wayzer/user/suffix,wayzer/user/achievement,
     wayzer/user/accountGuestControl,wayzer/security/securityGuard,
     wayzer/ext/playerInfoTripleTap]                                                     # SA handler 6 个
ARC_MLF=x2 [wayzer/map/betterTeam,wayzer/map/pvpProtect]                                 # 错挂在 arc 的 2 个
ARC_TLC=x1 [wayzer/ext/serverStats]                                                      # 错挂在 arc 的 1 个
```

修复并按 §3 热重载后（最终态）：

```
MLF=x5 [mapScript,wayzer/map/logicDrawGuard,wayzer/ext/liveEventFeed,
        wayzer/map/betterTeam,wayzer/map/pvpProtect]                                     # 3 → 5
TLC=x7 [wayzer/user/seniorityLevel,wayzer/user/suffix,wayzer/user/achievement,
        wayzer/ext/serverStats,wayzer/user/accountGuestControl,
        wayzer/security/securityGuard,wayzer/ext/playerInfoTripleTap]                     # 6 → 7
ARC_MLF=x0  ARC_TLC=x0                                                                   # 错挂项已随脚本重载注销（key 保留、Seq 为空）
```

补充：真实事件派发 E2E（在 Lead 停止 E2E 指示前已完成，之后未再做任何 E2E）——临时在 `pvpProtect` 的 `listenTo<MapLoadFinishedEvent>` 里加一行 `logger.info` 探针，控制台 `host 1` 触发一次真实换图（→ `【插件】MDT-杂交版`，`live-events.jsonl` 同步出现 `map_change`），日志拿到：

```
[2026-10-17 | 20:03:21 | 信息] [wayzer.map.pvpProtect] [listener-audit-probe]
MapLoadFinishedEvent 已派发到 pvpProtect 监听器; isPvpLike=false
```

说明事件确实经 SA handler 派发到了新注册的监听器（当前是 survival 图，`isPvpLike=false` 提前返回属预期）。探针随后已删除并重载，最终源码无探针。

---

## 5. 验证记录与未验证项

### 验证记录（全部通过）

| 验证项 | 命令/方式 | 结果 |
|---|---|---|
| KDoc 嵌套 `/**` 扫描 | `pwsh -NoProfile -File mdtdo/tools/scan-nested-block-comment.ps1` | 改前基线、每次改后、最终均输出「未发现块注释内嵌套 '/*'（共 208 个文件）」 |
| 脚本加载 | `sa load wayzer/ext/serverStats`、`sa load wayzer/map/betterTeam`、`sa load wayzer/map/pvpProtect`（含代码缓存重编译） | 均无回显错误（SA 成功静默），`.ktc` 编译产物 mtime 更新 |
| 失败脚本 | `sa listFailed` | 空 |
| 脚本统计 | `scriptdiag count` | `共161 加载157 启用152 失败0`（修复完成时）；随后 E2E 换图加载 `mapScript/tags/hybrid` 后为 `启用153/根指令159`，**失败0 不变**（非本次改动导致） |
| 三脚本状态 | `scriptdiag state <id>` | `状态：Enabled failReason=无` ×3 |
| 注册表运行态 | §4 的 js 反射探针 | 修复前 3/6 + 错挂 2/1 → 修复后 5/7 + arc 归零 |
| 真实事件派发 | `host 1` + 临时 logger 探针 | 见 §4 E2E 日志（探针已删） |

### 未验证项（明确标注）

1. **`TrustLevelChangedEvent` 真实触发链路未做运行时验证**：需要真实玩家在线且信任等级发生变化；当前服务器 0 玩家，且不愿为测试给真实玩家账号改等级/写库。已用“SA handler 监听器数 6→7、且 `serverStats` 出现在监听器列表”的运行态证据代替；下次有玩家等级变化时可在 `config/logs/*.log` 观察统计迁移是否无异常。
2. **`pvpProtect` 的 PVP 保护实际效果未验证**：本次真实换图加载的是 survival 图，只验证到“事件派发到监听器、`isPvpLike=false` 提前返回”。PVP 图加载后是否出现「PVP保护时间,禁止在其他基地攻击」广播与禁区回弹，需等下一次 PVP 图自然轮换或有人值守时再确认。
3. **`betterTeam` 的 `updateBannedTeam` 无可观测副作用**：当前无玩家、当前地图无 `@banTeam` 标签，只验证到监听器注册与事件到达；该函数本身是幂等重解析，风险低。
4. 按 Lead 指示，**未再做进一步的换图/E2E 验证**（上述 §4 E2E 为停止指令前已完成的一次）。

---

## 6. 反向不匹配核查（避免“修错方向”）

- SA 自定义事件是否被 `Events.fire` 派发（会导致 `listenTo` 也收不到）？检查全仓 `Events.fire(...)` 共 12 处：`wayzer/cmds/restart.kts:54`、`vote.kts:206`、`mapScript/tags/flood.kts:1056`、`mapScript/15450.kts:470`、`maps.kts:252`、`betterTeam.kts:140`、`reGrief/unitLimit.kts:77,106`（以上均 `import mindustry.game.EventType.GameOverEvent`，原生）、`contentsTweaker.kts:829`、`mapScript/lib/ScriptMapGenerator.kt:58`（运行时从 `EventType::class.java.declaredClasses` 取的类，原生）、以及 `Events.on(...)` 2 处（`maps.kts:193`、`mapScript/module.kts:189`，同样取 `EventType.declaredClasses`）。**结论：没有 SA 自定义事件走 `Events.fire`，`listenTo` 侧无反向 bug。**
- 另外 `wayzer.GameOverEvent`（`maps.kts:135`，SA 自定义）只在 `maps.kts:153` 用 `emitAsync()` 派发，全仓没有 `listenTo<GameOverEvent>` 监听者（其用途是 `emitAsync().cancelled` 同步取取消结果），无“监听不到”问题。
- `arc.Events` 里 `wayzer.MapLoadFinishedEvent` / `wayzer.lib.TrustLevelChangedEvent` 两个 key 修复后仍存在但 `Seq` 为空（`x0`）：这是 `ListenExt` 注销只 remove 元素不删 key 的正常现象，不影响派发。

---

## 7. 预防规则（下次新增/审查监听器时的 CheckList）

1. **先看事件定义，再选 API**：事件类有 `override val handler: Event.Handler`（或 `companion object : Event.Handler()`）→ 它在 SA 总线上，**只能 `listenTo`**；`mindustry.game.EventType.*` / 明确 `Events.fire` 派发的 → `listen`。
2. **编译器不会帮你**：`listen<T>` 的 `T : Any`，误用照样编译通过；必须靠代码审查/扫描发现。
3. 新增自定义事件的固定模式：
   ```kotlin
   data class XxxEvent(val ...) : Event {
       override val handler: Event.Handler get() = Companion
       companion object : Event.Handler()
   }
   // 派发方：XxxEvent(...).emitAsync() 或 launchEmit(...)；监听方：listenTo<XxxEvent> { ... }
   ```
   并在定义处写注释说明“必须 listenTo”，参考 `wayzer/lib/TrustSystemEvents.kt`、`wayzer/maps.manager.kt`。
4. **扫描命令（改动后自查）**：
   ```powershell
   # 1) 列出所有 listen<...>，凡是类型不以 EventType. 开头的逐条人工确认
   Get-ChildItem -Recurse mdtserver\config\scripts -Include *.kts,*.kt |
     Select-String '\blisten\s*<' | Where-Object { $_.Line -notmatch 'listen\s*<\s*EventType\.' }
   # 2) 交叉核对派发方式：SA 事件的派发点应是 emitAsync/launchEmit，不应是 Events.fire
   Get-ChildItem -Recurse mdtserver\config\scripts -Include *.kts,*.kt |
     Select-String 'emitAsync\(|launchEmit\('
   ```
   非 `EventType.` 前缀的 `listen` 只允许三类：①显式 `import mindustry.game.EventType.X` 的原生事件（`ResetEvent`/`TextInputEvent`/`WorldLoadBeginEvent`）；②MindustryX 原生扩展事件（如 `SendPacketEvent`，用 `listen<Any>(class)` 形式）；③经核对确认由 `Events.fire` 派发的类型。
5. **`listenTo<T> { }` 的 lambda 是 receiver（`suspend T.() -> Unit`）**：事件对象是 `this`，属性直接写 `uid` / `this.info`；**没有隐式 `it`**，提前返回用 `return@listenTo`。从 `listen` 迁移时最容易漏改这里（本任务已踩一次 `Unresolved reference 'it'`）。
6. **热重载注意**：SA 单脚本 `sa load` **不会级联重载 `contextScript<T>()` 的依赖方**（实测：重载 `betterTeam` 后其 8 个依赖方 `.ktc` 缓存 mtime 未变、脚本仍是旧实例）。因此：
   - 只改被依赖脚本本身时，修复对该脚本内监听器立即生效，但依赖方持有的旧实例状态（如 `bannedTeam`/`savedTeams`）会陈旧到下次冷启动；
   - 本次 `betterTeam` 有 8+ 个传递依赖方（`voteOb`/`observer`/`autoGameover`/`forceObAutoCleaner`/`securityGuard`/`accountGuestControl`/`accountIpGuard`/`gameContributionReward` 等），**建议低峰期冷启动一次**彻底刷新（不影响本次修复的功能生效）。

---

## 8. 已知边界 / 不在本报告范围

- `liveEventFeed.kts` 的投票事件用 `Dispatchers.game` 轮询 `VoteEvent.current()`（该事件体系无广播回调），与本报告的 listen/listenTo 无关。
- `listen<EventType.*>` 的既有用法全部正确，本报告只做归类，未改任何原生事件监听器。
- `wayzer/ext/*`、`wayzer/map/*` 之外没有发现同类误用；`参考项目/ScriptAgent-latest`、`复制插件用/scripts`（插件参考副本）不在运行脚本目录，未纳入修复范围。

---

## 附录 A：`listen<...>` 全仓逐条清单（修复前基线，186 处）

| # | 文件:行 | 事件类型 | 归属/派发方 | 结论 |
|---|---|---|---|---|
| 1 | coreMindustry\contentsTweaker.kts:994 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 2 | coreMindustry\contentsTweaker.kts:1001 | EventType.WorldLoadBeginEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 3 | coreMindustry\contentsTweaker.kts:1009 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 4 | coreMindustry\menu.kts:14 | EventType.MenuOptionChooseEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 5 | coreMindustry\menu.kts:959 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 6 | coreMindustry\util\trackBuilding.api.kt:18 | EventType.TilePreChangeEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 7 | coreMindustry\util\trackBuilding.api.kt:23 | EventType.TileChangeEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 8 | coreMindustry\utilMapRule.kts:45 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 9 | coreMindustry\utilTextInput.kts:10 | TextInputEvent | 原生 arc（显式 `import mindustry.game.EventType.TextInputEvent`） | 正确 |
| 10 | coreMindustry\variables.kts:115 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 11 | mapScript\14668.kts:5074 | EventType.TapEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 12 | mapScript\14668.kts:5092 | EventType.PlayerChatEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 13 | mapScript\14668.kts:5096 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 14 | mapScript\14668.kts:5114 | EventType.UnitDamageEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 15 | mapScript\14668.kts:5158 | EventType.UnitDestroyEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 16 | mapScript\15450.kts:556 | EventType.TapEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 17 | mapScript\15450.kts:590 | EventType.UnitDamageEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 18 | mapScript\15450.kts:599 | EventType.UnitDestroyEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 19 | mapScript\15450.kts:603 | EventType.UnitBulletDestroyEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 20 | mapScript\15450.kts:724 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 21 | mapScript\module.kts:45 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 22 | mapScript\module.kts:51 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 23 | mapScript\module.kts:193 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 24 | mapScript\tags\flood.kts:1141 | EventType.GameOverEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 25 | mapScript\tags\flood.kts:1145 | EventType.BuildDamageEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 26 | wayzer\cmds\gatherTp.kts:210 | EventType.PlayerChatEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 27 | wayzer\cmds\gatherTp.kts:225 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 28 | wayzer\cmds\restart.kts:11 | EventType.GameOverEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 29 | wayzer\cmds\restart.kts:15 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 30 | wayzer\cmds\restart.kts:22 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 31 | wayzer\cmds\restart.kts:23 | EventType.StateChangeEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 32 | wayzer\cmds\vote.kts:180 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 33 | wayzer\ext\liveEventFeed.kts:155 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 34 | wayzer\ext\liveEventFeed.kts:161 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 35 | wayzer\ext\liveEventFeed.kts:167 | EventType.PlayerChatEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 36 | wayzer\ext\logicSaveCompat.kts:58 | EventType.SaveWriteEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 37 | wayzer\ext\musicJukebox.kts:1378 | EventType.PlayerChatEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 38 | wayzer\ext\musicJukebox.kts:1400 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 39 | wayzer\ext\musicJukebox.kts:1411 | EventType.PlayerConnect | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 40 | wayzer\ext\observer.kts:21 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 41 | wayzer\ext\observer.kts:22 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 42 | wayzer\ext\observer.kts:24 | EventType.TapEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 43 | wayzer\ext\observer.kts:87 | EventType.WorldLoadEndEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 44 | wayzer\ext\playerBuildBan.kts:247 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 45 | wayzer\ext\playerBuildBan.kts:253 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 46 | wayzer\ext\playerInfoTripleTap.kts:927 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 47 | wayzer\ext\playerInfoTripleTap.kts:935 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 48 | wayzer\ext\playerInfoTripleTap.kts:943 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 49 | wayzer\ext\playerInfoTripleTap.kts:1000 | EventType.TapEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 50 | wayzer\ext\playerMute.kts:775 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 51 | wayzer\ext\playerMute.kts:784 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 52 | wayzer\ext\regionAutoLang.kts:41 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 53 | wayzer\ext\serverStats.kts:286 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 54 | wayzer\ext\serverStats.kts:295 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 55 | wayzer\ext\serverStats.kts:306 | EventType.PlayerChatEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 56 | wayzer\ext\serverStats.kts:311 | TrustLevelChangedEvent | **SA 自定义**（`wayzer/lib/TrustSystemEvents.kt:65`，emitAsync 派发） | **错误，已改 listenTo** |
| 57 | wayzer\ext\soundEffectMenu.kts:531 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 58 | wayzer\ext\soundEffectMenu.kts:535 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 59 | wayzer\ext\soundEffectMenu.kts:539 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 60 | wayzer\ext\welcomeMsg.kts:25 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 61 | wayzer\ext\welcomeMsg.kts:32 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 62 | wayzer\map\adaptivePlayerLimit.kts:237 | EventType.PlayerConnect | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 63 | wayzer\map\adaptivePlayerLimit.kts:255 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 64 | wayzer\map\adaptivePlayerLimit.kts:260 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 65 | wayzer\map\betterTeam.kts:96 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 66 | wayzer\map\betterTeam.kts:97 | MapLoadFinishedEvent | **SA 自定义**（`wayzer/maps.manager.kt:48`，emitAsync 派发） | **错误，已改 listenTo** |
| 67 | wayzer\map\betterTeam.kts:126 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 68 | wayzer\map\betterTeam.kts:130 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 69 | wayzer\map\betterTeam.kts:133 | EventType.BlockDestroyEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 70 | wayzer\map\betterTeam.kts:145 | EventType.CoreChangeEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 71 | wayzer\map\externalCpHotReload.kts:1742 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 72 | wayzer\map\funRuleModes.kts:877 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 73 | wayzer\map\logicDrawGuard.kts:154 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 74 | wayzer\map\mapInfo.kts:21 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 75 | wayzer\map\mapInfo.kts:67 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 76 | wayzer\map\mapInfo.kts:75 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 77 | wayzer\map\mapSnap.kts:68 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 78 | wayzer\map\mapSnap.kts:71 | EventType.TileChangeEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 79 | wayzer\map\mapSnap.kts:74 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 80 | wayzer\map\performanceGuard.kts:306 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 81 | wayzer\map\performanceGuard.kts:314 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 82 | wayzer\map\pvpProtect.kts:152 | MapLoadFinishedEvent | **SA 自定义**（`wayzer/maps.manager.kt:48`，emitAsync 派发） | **错误，已改 listenTo** |
| 83 | wayzer\map\pvpProtect.kts:154 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 84 | wayzer\map\serverPressure.kts:494 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 85 | wayzer\map\serverPressure.kts:504 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 86 | wayzer\map\serverPressureActions.kts:805 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 87 | wayzer\map\serverPressureActions.kts:831 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 88 | wayzer\map\serverPressureActions.kts:849 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 89 | wayzer\map\worldProcessorAdmin.kts:554 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 90 | wayzer\maps.kts:142 | EventType.GameOverEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 91 | wayzer\maps.kts:199 | WorldLoadBeginEvent | 原生 arc（显式 `import mindustry.game.EventType.WorldLoadBeginEvent`） | 正确 |
| 92 | wayzer\module.kts:68 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 93 | wayzer\pvp\autoGameover.kts:46 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 94 | wayzer\pvp\autoGameover.kts:49 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 95 | wayzer\pvp\pvpAlert.kts:31 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 96 | wayzer\pvp\pvpAlert.kts:48 | EventType.BlockBuildEndEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 97 | wayzer\pvp\pvpAlert.kts:72 | EventType.UnitCreateEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 98 | wayzer\pvp\pvpAlert.kts:81 | EventType.BlockDestroyEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 99 | wayzer\reGrief\autoChangeMap.kts:48 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 100 | wayzer\reGrief\autoChangeMap.kts:52 | EventType.ConnectPacketEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 101 | wayzer\reGrief\bugFixer.kts:16 | ResetEvent | 原生 arc（显式 `import mindustry.game.EventType.ResetEvent`） | 正确 |
| 102 | wayzer\reGrief\coreUnitRespawnCompat.kts:51 | EventType.UnitChangeEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 103 | wayzer\reGrief\coreUnitRespawnCompat.kts:70 | EventType.PlayerConnectionConfirmed | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 104 | wayzer\reGrief\coreUnitRespawnCompat.kts:86 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 105 | wayzer\reGrief\coreUnitRespawnCompat.kts:92 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 106 | wayzer\reGrief\history.kts:51 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 107 | wayzer\reGrief\history.kts:65 | EventType.BlockBuildEndEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 108 | wayzer\reGrief\history.kts:72 | EventType.ConfigEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 109 | wayzer\reGrief\history.kts:76 | EventType.DepositEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 110 | wayzer\reGrief\history.kts:79 | EventType.BlockDestroyEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 111 | wayzer\reGrief\history.kts:83 | EventType.PickupEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 112 | wayzer\reGrief\history.kts:88 | EventType.PayloadDropEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 113 | wayzer\reGrief\history.kts:139 | EventType.TapEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 114 | wayzer\reGrief\history.kts:155 | EventType.BlockDestroyEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 115 | wayzer\reGrief\inactivePressureCheck.kts:135 | EventType.PlayerChatEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 116 | wayzer\reGrief\inactivePressureCheck.kts:136 | EventType.TapEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 117 | wayzer\reGrief\inactivePressureCheck.kts:137 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 118 | wayzer\reGrief\inactivePressureCheck.kts:138 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 119 | wayzer\reGrief\inactivePressureCheck.kts:139 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 120 | wayzer\reGrief\limitLogicPacket.kts:67 | Any | 原生（MindustryX `SendPacketEvent`，`Events.fire` 派发） | 正确 |
| 121 | wayzer\reGrief\trafficMonitor.kts:75 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 122 | wayzer\reGrief\trafficMonitor.kts:76 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 123 | wayzer\reGrief\unitLimit.kts:37 | EventType.UnitUnloadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 124 | wayzer\reGrief\unitLimit.kts:73 | EventType.UnitSpawnEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 125 | wayzer\reGrief\unitLimit.kts:85 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 126 | wayzer\reGrief\unitLimit.kts:101 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 127 | wayzer\reGrief\unitLimit.kts:102 | EventType.WaveEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 128 | wayzer\reGrief\worldResyncCoordinator.kts:162 | EventType.PlayerConnectionConfirmed | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 129 | wayzer\reGrief\worldResyncCoordinator.kts:176 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 130 | wayzer\reGrief\worldResyncCoordinator.kts:187 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 131 | wayzer\security\securityGuard.kts:1067 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 132 | wayzer\security\securityGuard.kts:1135 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 133 | wayzer\security\securityGuard.kts:1146 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 134 | wayzer\security\securityGuard.kts:1156 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 135 | wayzer\user\accountAuth.kts:741 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 136 | wayzer\user\accountAuth.kts:763 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 137 | wayzer\user\accountAuth.kts:777 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 138 | wayzer\user\accountGuestControl.kts:171 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 139 | wayzer\user\accountGuestControl.kts:177 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 140 | wayzer\user\accountGuestControl.kts:192 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 141 | wayzer\user\accountIpGuard.kts:613 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 142 | wayzer\user\accountIpGuard.kts:638 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 143 | wayzer\user\accountIpGuard.kts:647 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 144 | wayzer\user\achievement.kts:880 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 145 | wayzer\user\ban.kts:223 | EventType.PlayerConnect | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 146 | wayzer\user\ext\chatPing.kts:44 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 147 | wayzer\user\ext\chatPing.kts:45 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 148 | wayzer\user\ext\skills.kts:582 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 149 | wayzer\user\ext\skillsHybrid.kts:1549 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 150 | wayzer\user\ext\skillsHybrid.kts:1551 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 151 | wayzer\user\ext\skillsLevel2.kts:672 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 152 | wayzer\user\ext\skillsLevel2.kts:676 | EventType.UnitDestroyEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 153 | wayzer\user\ext\skillsLevel2.kts:681 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 154 | wayzer\user\ext\skillsLevel2.kts:687 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 155 | wayzer\user\gameContributionReward.kts:194 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 156 | wayzer\user\gameContributionReward.kts:198 | EventType.PlayEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 157 | wayzer\user\gameContributionReward.kts:217 | EventType.GameOverEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 158 | wayzer\user\gameContributionReward.kts:229 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 159 | wayzer\user\nameExt.kts:89 | EventType.PlayerConnect | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 160 | wayzer\user\playerTitle.kts:355 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 161 | wayzer\user\privateMessage.kts:103 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 162 | wayzer\user\punishmentReport.kts:362 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 163 | wayzer\user\seniorityLevel.kts:454 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 164 | wayzer\user\seniorityLevel.kts:460 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 165 | wayzer\user\seniorityLevel.kts:466 | EventType.GameOverEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 166 | wayzer\user\seniorityLevel.kts:474 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 167 | wayzer\user\shortID.kts:35 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 168 | wayzer\user\skillShop.kts:1037 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 169 | wayzer\user\skillShop.kts:1042 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 170 | wayzer\user\skillShop.kts:1047 | EventType.UnitDestroyEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 171 | wayzer\user\skillShop.kts:1051 | EventType.WorldLoadEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 172 | wayzer\user\skillShop.kts:1058 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 173 | wayzer\user\skillShop.kts:1065 | EventType.GameOverEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 174 | wayzer\user\suffix.kts:24 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 175 | wayzer\user\suffix.kts:147 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 176 | wayzer\user\trustPoint.kts:438 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 177 | wayzer\user\trustPointReward.kts:58 | EventType.GameOverEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 178 | wayzer\user\trustPointReward.kts:74 | EventType.PlayerChatEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 179 | wayzer\user\trustPointReward.kts:83 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 180 | wayzer\user\trustPromotion.kts:217 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 181 | wayzer\user\trustPromotion.kts:221 | EventType.GameOverEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 182 | wayzer\user\trustPromotion.kts:225 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 183 | wayzer\vote.kts:20 | EventType.PlayerJoin | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 184 | wayzer\vote.kts:25 | EventType.PlayerLeave | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 185 | wayzer\vote.kts:31 | EventType.PlayerChatEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |
| 186 | wayzer\vote.kts:47 | EventType.ResetEvent | 原生 arc（`mindustry.game.EventType.*`，`Events.fire` 派发） | 正确 |

## 附录 B：`listenTo<...>` 全仓逐条清单（修复前基线，70 处）

| # | 文件:行 | 事件类型 | 定义 / 派发点 | 结论 |
|---|---|---|---|---|
| 1 | bootStrap\generate.kts:41 | `ScriptStateChangeEvent.Cancellable` | SA 内核可取消事件 `cf.wayzer.scriptAgent.events.ScriptStateChangeEvent$Cancellable`（插件内部派发） | 正确（SA 事件只能用 listenTo） |
| 2 | coreLibrary\commands\permissionCmd.kts:86 | `RequestPermissionEvent` | SA 事件 `coreLibrary/lib/event/RequestPermissionEvent.kt:14`；`emitAsync` @ `coreLibrary/lib/PermissionApi.kt:75` | 正确（SA 事件只能用 listenTo） |
| 3 | coreLibrary\lib\CommandApi.kt:340 | `ScriptDisableEvent` | SA 内核事件 `cf.wayzer.scriptAgent.events.ScriptDisableEvent`（插件内部派发） | 正确（SA 事件只能用 listenTo） |
| 4 | coreLibrary\lib\CommandApi.kt:407 | `ScriptDisableEvent` | SA 内核事件 `cf.wayzer.scriptAgent.events.ScriptDisableEvent`（插件内部派发） | 正确（SA 事件只能用 listenTo） |
| 5 | coreLibrary\lib\ConfigApi.kt:191 | `ScriptStateChangeEvent` | SA 内核事件 `cf.wayzer.scriptAgent.events.ScriptStateChangeEvent`（插件内部派发） | 正确（SA 事件只能用 listenTo） |
| 6 | coreMindustry\lib\ListenExt.kt:62 | `ScriptEnableEvent` | SA 内核事件 `cf.wayzer.scriptAgent.events.ScriptEnableEvent`（插件内部派发） | 正确（SA 事件只能用 listenTo） |
| 7 | coreMindustry\lib\ListenExt.kt:68 | `ScriptDisableEvent` | SA 内核事件 `cf.wayzer.scriptAgent.events.ScriptDisableEvent`（插件内部派发） | 正确（SA 事件只能用 listenTo） |
| 8 | mapScript\module.kts:194 | `MapLoadFinishedEvent` | SA 事件 `wayzer/maps.manager.kt:48`；`emitAsync` @ `wayzer/maps.manager.kt:169` | 正确（SA 事件只能用 listenTo） |
| 9 | wayzer\cmds\voteOb.kts:220 | `PunishmentLiftedEvent` | SA 事件 `wayzer/ext/playerMute.kts:83`；`emitAsync` @ `wayzer/user/punishmentReport.kts:231` | 正确（SA 事件只能用 listenTo） |
| 10 | wayzer\cmds\voteOb.kts:352 | `BetterTeam.AssignTeamEvent` | SA 事件 `wayzer/map/betterTeam.kts:20`；`emitAsync` @ `wayzer/map/betterTeam.kts:187` | 正确（SA 事件只能用 listenTo） |
| 11 | wayzer\ext\liveEventFeed.kts:174 | `MapLoadFinishedEvent` | SA 事件 `wayzer/maps.manager.kt:48`；`emitAsync` @ `wayzer/maps.manager.kt:169` | 正确（SA 事件只能用 listenTo） |
| 12 | wayzer\ext\playerBuildBan.kts:196 | `PunishmentLiftedEvent` | SA 事件 `wayzer/ext/playerMute.kts:83`；`emitAsync` @ `wayzer/user/punishmentReport.kts:231` | 正确（SA 事件只能用 listenTo） |
| 13 | wayzer\ext\playerInfoTripleTap.kts:959 | `ReputationChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:5`；`emitAsync` @ `wayzer/ext/playerReputation.kts:47` | 正确（SA 事件只能用 listenTo） |
| 14 | wayzer\ext\playerInfoTripleTap.kts:960 | `RecognitionChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:17`；`emitAsync` @ `wayzer/ext/playerRecognition.kts:22` | 正确（SA 事件只能用 listenTo） |
| 15 | wayzer\ext\playerInfoTripleTap.kts:961 | `TrustPointChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:29`；`emitAsync` @ `wayzer/user/trustPoint.kts:41,45`、`wayzer/user/accountAuth.kts:448` | 正确（SA 事件只能用 listenTo） |
| 16 | wayzer\ext\playerInfoTripleTap.kts:962 | `TrustLevelChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:65`；`emitAsync` @ `wayzer/user/trustLevel.kts:170,175` | 正确（SA 事件只能用 listenTo） |
| 17 | wayzer\ext\playerInfoTripleTap.kts:963 | `TrustLevelLockChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:47`；`emitAsync` @ `wayzer/user/trustLevel.kts:184` | 正确（SA 事件只能用 listenTo） |
| 18 | wayzer\ext\playerInfoTripleTap.kts:964 | `SeniorityLevelChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:71`；`emitAsync` @ `wayzer/user/seniorityLevel.kts:167` | 正确（SA 事件只能用 listenTo） |
| 19 | wayzer\ext\playerInfoTripleTap.kts:965 | `SeniorityLevelLockChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:77`；`emitAsync` @ `wayzer/user/seniorityLevel.kts:176` | 正确（SA 事件只能用 listenTo） |
| 20 | wayzer\ext\playerInfoTripleTap.kts:966 | `PlayerTitleChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:53`；`emitAsync` @ `wayzer/user/playerTitle.kts:83` | 正确（SA 事件只能用 listenTo） |
| 21 | wayzer\ext\playerInfoTripleTap.kts:968 | `DatabaseFeatureChangedEvent` | SA 事件 `wayzer/lib/DatabaseFeatureSettings.kt:51`；`emitAsync` @ `wayzer/user/databaseFeatureSettings.kts:108` | 正确（SA 事件只能用 listenTo） |
| 22 | wayzer\ext\playerMute.kts:494 | `PunishmentLiftedEvent` | SA 事件 `wayzer/ext/playerMute.kts:83`；`emitAsync` @ `wayzer/user/punishmentReport.kts:231` | 正确（SA 事件只能用 listenTo） |
| 23 | wayzer\ext\playerMute.kts:766 | `OnChat` | SA 事件 `coreMindustry/utilNextChat.kts:5`；`emitAsync` @ `coreMindustry/utilNextChat.kts:12` | 正确（SA 事件只能用 listenTo） |
| 24 | wayzer\map\backCompatibility.kts:11 | `MapChangeEvent` | SA 事件 `wayzer/maps.manager.kt:32`；`emitAsync` @ `wayzer/maps.manager.kt:129` | 正确（SA 事件只能用 listenTo） |
| 25 | wayzer\map\logicDrawGuard.kts:148 | `MapLoadFinishedEvent` | SA 事件 `wayzer/maps.manager.kt:48`；`emitAsync` @ `wayzer/maps.manager.kt:169` | 正确（SA 事件只能用 listenTo） |
| 26 | wayzer\map\mapFilter.kts:152 | `GetNextMapEvent` | SA 事件 `wayzer/maps.registry.kt:61`；`emitAsync` @ `wayzer/maps.registry.kt:103` | 正确（SA 事件只能用 listenTo） |
| 27 | wayzer\map\mapFilter.kts:165 | `MapChangeEvent` | SA 事件 `wayzer/maps.manager.kt:32`；`emitAsync` @ `wayzer/maps.manager.kt:129` | 正确（SA 事件只能用 listenTo） |
| 28 | wayzer\module.kts:61 | `RequestPermissionEvent` | SA 事件 `coreLibrary/lib/event/RequestPermissionEvent.kt:14`；`emitAsync` @ `coreLibrary/lib/PermissionApi.kt:75` | 正确（SA 事件只能用 listenTo） |
| 29 | wayzer\security\securityGuard.kts:1021 | `ConnectAsyncEvent` | SA 事件 `wayzer/lib/ConnectAsyncEvent.kt:13`；`emitAsync` @ `wayzer/module.kts:55` | 正确（SA 事件只能用 listenTo） |
| 30 | wayzer\security\securityGuard.kts:1055 | `BetterTeam.AssignTeamEvent` | SA 事件 `wayzer/map/betterTeam.kts:20`；`emitAsync` @ `wayzer/map/betterTeam.kts:187` | 正确（SA 事件只能用 listenTo） |
| 31 | wayzer\security\securityGuard.kts:1086 | `OnChat` | SA 事件 `coreMindustry/utilNextChat.kts:5`；`emitAsync` @ `coreMindustry/utilNextChat.kts:12` | 正确（SA 事件只能用 listenTo） |
| 32 | wayzer\security\securityGuard.kts:1141 | `TrustLevelChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:65`；`emitAsync` @ `wayzer/user/trustLevel.kts:170,175` | 正确（SA 事件只能用 listenTo） |
| 33 | wayzer\user\accountAuth.kts:724 | `ConnectAsyncEvent` | SA 事件 `wayzer/lib/ConnectAsyncEvent.kt:13`；`emitAsync` @ `wayzer/module.kts:55` | 正确（SA 事件只能用 listenTo） |
| 34 | wayzer\user\accountAuth.kts:758 | `RequestPermissionEvent` | SA 事件 `coreLibrary/lib/event/RequestPermissionEvent.kt:14`；`emitAsync` @ `coreLibrary/lib/PermissionApi.kt:75` | 正确（SA 事件只能用 listenTo） |
| 35 | wayzer\user\accountGuestControl.kts:163 | `BetterTeam.AssignTeamEvent` | SA 事件 `wayzer/map/betterTeam.kts:20`；`emitAsync` @ `wayzer/map/betterTeam.kts:187` | 正确（SA 事件只能用 listenTo） |
| 36 | wayzer\user\accountGuestControl.kts:183 | `TrustLevelChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:65`；`emitAsync` @ `wayzer/user/trustLevel.kts:170,175` | 正确（SA 事件只能用 listenTo） |
| 37 | wayzer\user\accountIpGuard.kts:595 | `ConnectAsyncEvent` | SA 事件 `wayzer/lib/ConnectAsyncEvent.kt:13`；`emitAsync` @ `wayzer/module.kts:55` | 正确（SA 事件只能用 listenTo） |
| 38 | wayzer\user\accountIpGuard.kts:606 | `BetterTeam.AssignTeamEvent` | SA 事件 `wayzer/map/betterTeam.kts:20`；`emitAsync` @ `wayzer/map/betterTeam.kts:187` | 正确（SA 事件只能用 listenTo） |
| 39 | wayzer\user\accountIpGuard.kts:631 | `OnChat` | SA 事件 `coreMindustry/utilNextChat.kts:5`；`emitAsync` @ `coreMindustry/utilNextChat.kts:12` | 正确（SA 事件只能用 listenTo） |
| 40 | wayzer\user\achievement.kts:836 | `TrustLevelChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:65`；`emitAsync` @ `wayzer/user/trustLevel.kts:170,175` | 正确（SA 事件只能用 listenTo） |
| 41 | wayzer\user\achievement.kts:840 | `ReputationChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:5`；`emitAsync` @ `wayzer/ext/playerReputation.kts:47` | 正确（SA 事件只能用 listenTo） |
| 42 | wayzer\user\achievement.kts:844 | `RecognitionChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:17`；`emitAsync` @ `wayzer/ext/playerRecognition.kts:22` | 正确（SA 事件只能用 listenTo） |
| 43 | wayzer\user\achievement.kts:848 | `TrustPointChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:29`；`emitAsync` @ `wayzer/user/trustPoint.kts:41,45`、`wayzer/user/accountAuth.kts:448` | 正确（SA 事件只能用 listenTo） |
| 44 | wayzer\user\achievement.kts:852 | `SeniorityLevelChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:71`；`emitAsync` @ `wayzer/user/seniorityLevel.kts:167` | 正确（SA 事件只能用 listenTo） |
| 45 | wayzer\user\achievement.kts:856 | `PlayerTitleChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:53`；`emitAsync` @ `wayzer/user/playerTitle.kts:83` | 正确（SA 事件只能用 listenTo） |
| 46 | wayzer\user\achievement.kts:860 | `ShopPurchaseEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:89`；`emitAsync` @ `wayzer/user/shopCore.kts:79` | 正确（SA 事件只能用 listenTo） |
| 47 | wayzer\user\achievement.kts:864 | `ForumPostCreatedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:95`；`emitAsync` @ `wayzer/user/forumPosts.kts:1082` | 正确（SA 事件只能用 listenTo） |
| 48 | wayzer\user\achievement.kts:868 | `DatabaseFeatureChangedEvent` | SA 事件 `wayzer/lib/DatabaseFeatureSettings.kt:51`；`emitAsync` @ `wayzer/user/databaseFeatureSettings.kts:108` | 正确（SA 事件只能用 listenTo） |
| 49 | wayzer\user\gameContributionReward.kts:221 | `MapChangeEvent` | SA 事件 `wayzer/maps.manager.kt:32`；`emitAsync` @ `wayzer/maps.manager.kt:129` | 正确（SA 事件只能用 listenTo） |
| 50 | wayzer\user\leaderboard.kts:147 | `TrustPointChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:29`；`emitAsync` @ `wayzer/user/trustPoint.kts:41,45`、`wayzer/user/accountAuth.kts:448` | 正确（SA 事件只能用 listenTo） |
| 51 | wayzer\user\leaderboard.kts:151 | `ReputationChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:5`；`emitAsync` @ `wayzer/ext/playerReputation.kts:47` | 正确（SA 事件只能用 listenTo） |
| 52 | wayzer\user\leaderboard.kts:155 | `RecognitionChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:17`；`emitAsync` @ `wayzer/ext/playerRecognition.kts:22` | 正确（SA 事件只能用 listenTo） |
| 53 | wayzer\user\leaderboard.kts:159 | `ForumPostCreatedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:95`；`emitAsync` @ `wayzer/user/forumPosts.kts:1082` | 正确（SA 事件只能用 listenTo） |
| 54 | wayzer\user\leaderboard.kts:163 | `DatabaseFeatureChangedEvent` | SA 事件 `wayzer/lib/DatabaseFeatureSettings.kt:51`；`emitAsync` @ `wayzer/user/databaseFeatureSettings.kts:108` | 正确（SA 事件只能用 listenTo） |
| 55 | wayzer\user\punishmentReport.kts:92 | `PunishmentAppliedEvent` | SA 事件 `wayzer/ext/playerMute.kts:51`；`emitAsync` @ `wayzer/ext/playerMute.kts:472` | 正确（SA 事件只能用 listenTo） |
| 56 | wayzer\user\seniorityLevel.kts:432 | `TrustPointChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:29`；`emitAsync` @ `wayzer/user/trustPoint.kts:41,45`、`wayzer/user/accountAuth.kts:448` | 正确（SA 事件只能用 listenTo） |
| 57 | wayzer\user\seniorityLevel.kts:433 | `TrustLevelChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:65`；`emitAsync` @ `wayzer/user/trustLevel.kts:170,175` | 正确（SA 事件只能用 listenTo） |
| 58 | wayzer\user\seniorityLevel.kts:434 | `SeniorityLevelLockChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:77`；`emitAsync` @ `wayzer/user/seniorityLevel.kts:176` | 正确（SA 事件只能用 listenTo） |
| 59 | wayzer\user\seniorityLevel.kts:436 | `DatabaseFeatureChangedEvent` | SA 事件 `wayzer/lib/DatabaseFeatureSettings.kt:51`；`emitAsync` @ `wayzer/user/databaseFeatureSettings.kts:108` | 正确（SA 事件只能用 listenTo） |
| 60 | wayzer\user\suffix.kts:25 | `TrustLevelChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:65`；`emitAsync` @ `wayzer/user/trustLevel.kts:170,175` | 正确（SA 事件只能用 listenTo） |
| 61 | wayzer\user\trustLevel.kts:286 | `RequestPermissionEvent` | SA 事件 `coreLibrary/lib/event/RequestPermissionEvent.kt:14`；`emitAsync` @ `coreLibrary/lib/PermissionApi.kt:75` | 正确（SA 事件只能用 listenTo） |
| 62 | wayzer\user\trustPointReward.kts:103 | `PlayerLikedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:11`；`emitAsync` @ `wayzer/ext/playerReputation.kts:51` | 正确（SA 事件只能用 listenTo） |
| 63 | wayzer\user\trustPointReward.kts:107 | `PlayerRecognizedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:23`；`emitAsync` @ `wayzer/ext/playerRecognition.kts:21` | 正确（SA 事件只能用 listenTo） |
| 64 | wayzer\user\trustPromotion.kts:195 | `ReputationChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:5`；`emitAsync` @ `wayzer/ext/playerReputation.kts:47` | 正确（SA 事件只能用 listenTo） |
| 65 | wayzer\user\trustPromotion.kts:199 | `RecognitionChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:17`；`emitAsync` @ `wayzer/ext/playerRecognition.kts:22` | 正确（SA 事件只能用 listenTo） |
| 66 | wayzer\user\trustPromotion.kts:203 | `TrustPointChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:29`；`emitAsync` @ `wayzer/user/trustPoint.kts:41,45`、`wayzer/user/accountAuth.kts:448` | 正确（SA 事件只能用 listenTo） |
| 67 | wayzer\user\trustPromotion.kts:207 | `TrustLevelLockChangedEvent` | SA 事件 `wayzer/lib/TrustSystemEvents.kt:47`；`emitAsync` @ `wayzer/user/trustLevel.kts:184` | 正确（SA 事件只能用 listenTo） |
| 68 | wayzer\user\trustPromotion.kts:211 | `DatabaseFeatureChangedEvent` | SA 事件 `wayzer/lib/DatabaseFeatureSettings.kt:51`；`emitAsync` @ `wayzer/user/databaseFeatureSettings.kts:108` | 正确（SA 事件只能用 listenTo） |
| 69 | wayzer\user\wiki.kts:1034 | `DatabaseFeatureChangedEvent` | SA 事件 `wayzer/lib/DatabaseFeatureSettings.kt:51`；`emitAsync` @ `wayzer/user/databaseFeatureSettings.kts:108` | 正确（SA 事件只能用 listenTo） |
| 70 | wayzer\vote.kts:73 | `ScriptDisableEvent` | SA 内核事件 `cf.wayzer.scriptAgent.events.ScriptDisableEvent`（插件内部派发） | 正确（SA 事件只能用 listenTo） |
