# 实时事件流（游戏内 live events feed）

> 状态：2026-10-17 实现并在本机开发服（MindustryX B509）完成验证；与 Web 门户（`web-portal`）按本文数据契约对接。
> **2026-10-18 更新**：轮转改为保留 **12 份**旋转历史（`keepRotatedFiles=12`）、`maxPlayerChars` 默认 **256（最低按 128 钳制）**；
> Web 端 `/api/events/history` 会消费这些旋转文件（见 `web-portal.md` §4.5/§7.4）。
> 实现文件：`mdtdo/mdtserver/config/scripts/wayzer/ext/liveEventFeed.kts`。
> 运行数据：`mdtdo/mdtserver/config/stats/live-events.jsonl`（位于 `config/stats/`，该目录不进 git）。

## 概述

`liveEventFeed.kts` 把游戏内**聊天 / 加入 / 离开 / 投票 / 换图 / 系统消息**追加写入一个 JSONL 文件，
供服务器门户 Web 独立读取。设计目标：

- **不占用游戏主线程**：事件监听器只做「取字段 + 内存入队」，文件 IO 全部在 `Dispatchers.IO` 的单一写者协程；
- **可分离**：脚本不依赖 Web 后端、不联网、不读写数据库，Web 起停与脚本互不影响；
- **可增量消费**：`ts` 严格递增，Web 端用 `afterTs`（严格大于）轮询即可不丢事件；
- **文件有界**：单文件约 2MB 自动轮转，保留 **12 份**旋转历史（`live-events.1.jsonl`…`live-events.12.jsonl`，`.1` 最新、编号越大越旧，超出删最旧）；
- **失败不影响游戏**：写盘/轮转异常只记录限频日志并自动重试。

## 数据契约（Web 端按此读取，脚本侧不要单方面修改）

输出文件：

```
mdtserver/config/stats/live-events.jsonl
```

- **UTF-8 无 BOM**，append-only；
- **每行一个完整 JSON 对象**，行内不会出现真实换行（`text` 内的换行以 `\n` 转义），每行以 `\n` 结尾。

字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `ts` | long | 是 | 事件写入时间，epoch 毫秒。**严格递增**：同一毫秒内多条事件自动 +1ms；脚本热重载后从文件尾部恢复上一条 `ts`，跨重载也保持递增。Web 端 `afterTs` 为「严格大于」语义不会丢事件。 |
| `type` | string | 是 | 枚举：`chat` / `join` / `leave` / `vote_start` / `vote_pass` / `vote_fail` / `map_change` / `system` |
| `text` | string | 是 | **Mindustry 原始颜色标记文本**（如 `[red]`、`[#RRGGBB]`、`[]`）。脚本只做 JSON 转义，不剥离颜色标记、不 HTML 转义（渲染/转义由前端做）；超长按字符截断到 `maxTextChars`（默认 500）。 |
| `player` | string | 是 | 玩家显示名（原始文本，可能含颜色标记）。`system` / `map_change` 为空串。 |
| `playerId` | string | 否 | Mindustry UUID。`system` / `map_change` 省略该字段；无法取得合法 UUID 时（如本地假玩家 `[LOCAL]`）也省略。 |

各 `type` 语义：

| type | text 内容 | player / playerId |
| --- | --- | --- |
| `chat` | 玩家聊天原文（含颜色标记） | 发言人 |
| `join` | 固定文案「加入了服务器」 | 加入者 + uuid |
| `leave` | 固定文案「离开了服务器」 | 离开者 + uuid |
| `map_change` | `[accent]加载地图：[white]<图名> [gray]模式：[white]<本地化模式> [gray]作者：[white]<作者> [gray]ID：[white]<id>` | 空 / 省略 |
| `vote_start` | `[gold]投票开始：[white]<发起人>[yellow] 发起 <投票描述>` | 发起人 + uuid |
| `vote_pass` | `[green]投票通过：[white]<发起人>[yellow] 发起的 <投票描述>` | 发起人 + uuid |
| `vote_fail` | `[red]投票失败：[white]<发起人>[yellow] 发起的 <投票描述>` | 发起人 + uuid |
| `system` | 系统/运维修消息（目前为事件流启停） | 空 / 省略 |

实测样例（`system` / `map_change` 为真实游戏事件落盘行；两条 `chat` 为验证阶段用控制台构造事件产生）：

```json
{"ts":1792236669354,"type":"system","text":"[gray]实时事件流已启动","player":""}
{"ts":1792236591703,"type":"map_change","text":"[accent]加载地图：[white][stat]烬区 [gray]模式：[white]生存 [gray]作者：[white][#f3e979]不知去向 [gray]ID：[white]24959","player":""}
{"ts":1792236274411,"type":"chat","text":"引号\" 反斜杠\\ 制表\t换行\n [red]颜色[] [#00ff00]HEX[] 中文","player":"[cyan]转义测试员[]"}
{"ts":1792236288834,"type":"chat","text":"burst-0","player":"[pink]压测"}
```

`join` / `leave` / `vote_*` 的样例等待真实玩家出现后由脚本自动采集（字段口径见上表）。

## 事件来源与实现机制

| 事件 | 来源 | 实现 |
| --- | --- | --- |
| `join` / `leave` | Mindustry `EventType.PlayerJoin` / `PlayerLeave` | `listen<...>` 取 `player.name` + `player.uuid()` 入队 |
| `chat` | Mindustry `EventType.PlayerChatEvent` | `listen<...>` 取 `event.message` 原文入队（被限速/拦截、未进入服务端的消息不会触发） |
| `map_change` | `wayzer/maps` 的 SA 自定义事件 `MapLoadFinishedEvent`（每次 `MapManager.loadMapSync` 成功后广播，含 `MapInfo`） | `listenTo<MapLoadFinishedEvent>`，取图名/模式/作者/ID。覆盖 `/host`、游戏结束自动换图、投票换图、`/load` 存档等所有换图路径 |
| `vote_start` / `vote_pass` / `vote_fail` | `wayzer/vote.lib.kt` 的 `VoteEvent` | **该投票框架没有暴露开始/结束事件**，本次未改 `vote.lib.kt`，改为在 `Dispatchers.game` 上每 250ms 轮询公开 API `VoteEvent.current()`：观察到出现→发 `vote_start`；观察到消失→按 `event.succeed` 发 `vote_pass` / `vote_fail`。轮询只读 `AtomicReference` 和内存字段，无 IO |
| `system` | 脚本自身生命周期 | `onEnable` 写「已启动」、`onDisable` 写「已停止」；也是后续运维播报的预留类型 |

### 投票接入的已知边界（重要）

轮询方案对常规投票（持续数十秒）足够可靠，但有两类边界：

1. **单玩家快速投票**：`VoteEvent` 的 `supportSingle` 快速成功路径在把事件注册为 `active` **之前**就结束（`vote.lib.kt` 里 `active.compareAndSet` 之前的分支），轮询永远看不到 → 该投票不会进事件流；
2. **开始与结束都发生在两次轮询之间**（间隔 <250ms，实际几乎只可能是上述快速路径）→ 同样可能漏采。

**后续最小改造方案**（本轮不做，因为写作用域只允许本脚本 + 本文档，且 `vote.lib.kt` 在禁止改动清单里）：
在 `vote.lib.kt` 的 `VoteEvent` 进入/退出 `active` 的位置各 `emitAsync` 一个 SA 自定义事件（带 `starter` / `voteDesc` / `succeed`），
本脚本改成 `listenTo` 即可做到零轮询、零漏采。在改造前，普通投票（≥1 秒）可正常采集。

## 写入策略与线程模型

```
游戏线程（监听器）                  Dispatchers.IO 单写者协程
─────────────────────             ─────────────────────────────
PlayerJoin/Leave/Chat   ──offer──>  ConcurrentLinkedQueue
MapLoadFinishedEvent    ──offer──>        │  每 400ms drain 一次
Vote 轮询(Dispatchers.game) ─offer──>     │  每批 ≤200 条
                                          ▼
                              一次性拼接整批 JSON 行 → FileOutputStream(append) 写一次 → flush/close
                                          │
                              超阈值（默认 2MB）→ 先 rename 主文件为 .1 → 再 append 新建主文件
```

要点：

- **监听器不做任何 IO/查库/联网**，只构造一个小对象入队（聊天高频时也只是一次队列 offer）；
- **单写者**：所有文件操作都在唯一 IO 协程内，天然避免写竞争；`writeLock` 只用于与停用收尾/手动轮转互斥；
- **批量写**：默认 400ms 一次（约 2.5 次/秒，符合「≤2-4 次/秒」），每批一次 `append`，避免逐行开关文件；
- **行完整性**：整批先拼接成完整字符串再一次性写；每行 JSON 都完整，`text` 内换行转义为 `\n`，不会写半行；
- **ts 严格递增**：写者分配 `ts = max(事件时间, 当前时间, 上一条 ts + 1)`；初始化时从文件尾部 16KB 内恢复最后一条 `ts`，保证热重载后仍严格递增；
- **轮转**：写一批前若「当前大小 + 本批字节数」超过阈值，则做**链式移位**：先删最旧的第 `keepRotatedFiles+1` 份，再把 `.N` 依次后移
  （`.1→.2`、`.2→.3`…），最后把主文件 rename 为 `live-events.1.jsonl` 并新建主文件。**先移位/rename 再新建**，读取端不会读到半截文件；
  默认保留 12 份（`keepRotatedFiles`，钳制 1~100），启动时若主文件已超阈值也会先轮转（实测见下文）；
- **异常降级**：写失败时把该批事件放回队列，下一轮重试；错误日志最多每 60 秒一条，不影响游戏。轮转失败（例如读取端正占用文件）也会在下轮重试；
- **内存有界**：队列上限 `maxQueueSize`（默认 4096），极端过载时丢弃最旧事件并限频告警（门户可能缺事件，但游戏/内存不受影响）；
- **停用收尾**：`onDisable` 补一条 `system` 停止事件并用 `runBlocking(Dispatchers.IO)` 同步 flush 一次（仅停用/关服路径，正常运行时不会在主线程做 IO）。

性能量级：聊天高峰按 20 条/秒估算，每 400ms 约 8 行、单次 append 约 2KB；写者协程空闲时每 400ms 只做一次空队列检查。
文件上限 = 主文件 ≤ 阈值 + 一批（最坏多一批，≤200 条 × 500 字符 ≈ 130KB）+ 12 份旋转文件（各 ≤阈值+一批），默认合计 ≤约 26MB，可用 `keepRotatedFiles` 调整。

## 读取方式（给 Web 后端）

后端已实现两个接口：
- `GET /api/events?afterTs=<ms>&limit=<n>`：**增量**，只读主文件 `live-events.jsonl`；
- `GET /api/events/history`：**历史查询**，跨「主文件 + 旋转历史」倒序分页（`cursor=ts` 只返回更早、支持 type/keyword/fromTs/toTs、
  limit 100/最大 200），见 `web-portal.md` §4.5/§7.4。

读取建议：

1. **增量接口只读主文件** `live-events.jsonl`；`live-events.N.jsonl` 是旋转历史（`.1` 最新、`.12` 最旧），
   由历史查询接口按「当前 → .1 → .2 …」倒序消费——不要用增量接口去读它们；
2. 每次读取整个主文件（≤约 2MB，开销可控）→ 按行 `JSON.parse` → 过滤 `ts > afterTs` → 按 `ts` 升序取前 `limit` 条；
3. 建议在进程内记录 `lastSeenTs`（不要按字节 offset，轮转后 offset 会失效）；
4. 容错要求（后端已确认）：文件不存在 / 轮转瞬间为空 / 半行 / 坏行 / 非 UTF-8 都要跳过而不是报 500；轮转后 `ts` 仍严格递增，不会回退。

## 配置键

SA 配置键全名 = `wayzer.ext.liveEventFeed.<键名>`，可用 `sa config get/set` 查看修改。
大部分键在下次使用处即时生效（无需重载）；`outputPath` 在写者启动时固化，改路径需 `sa load wayzer/ext/liveEventFeed`。

| 键名 | 默认值 | 说明 |
| --- | --- | --- |
| `feedDefaultEnabled` | `true` | 是否启用采集（关闭后监听器不产生事件；已入队事件仍会落盘） |
| `outputPath` | `stats/live-events.jsonl` | 输出路径（相对 `Vars.dataDirectory`，即 config 目录） |
| `flushIntervalMillis` | `400` | 批量写入间隔（ms），钳制在 100~5000 |
| `maxQueueSize` | `4096` | 内存队列上限，超出丢弃最旧事件 |
| `maxTextChars` | `500` | `text` 最大字符数，超出截断（按 code point 边界，不会切坏 emoji） |
| `maxPlayerChars` | `256` | `player` 显示名最大字符数（Web 展示用；**最低按 128 钳制**，默认 256 以容纳颜色标记/长名） |
| `rotateBytes` | `2097152`（2MB） | 单文件轮转阈值；下限 4096 字节防误配 |
| `keepRotatedFiles` | `12` | 保留的旋转历史份数（`live-events.1` 最新；钳制 1~100，超出删最旧） |
| `votePollMillis` | `250` | 投票状态轮询间隔（ms），钳制在 100~2000 |

修改示例：

```
sa config set wayzer.ext.liveEventFeed.rotateBytes 2097152
```

## 运维/诊断命令

```
/livefeed                 （别名：事件流、实时事件）status，查看状态
/livefeed rotate          手动轮转：链式移位后把主文件 rename 为 live-events.1.jsonl（最旧一份被删），主文件在下一条事件时重建
```

权限：`wayzer.livefeed.admin`（默认 `@admin` 组；控制台可用）。

`status` 输出示例：

```
实时事件流
输出文件：config\stats\live-events.jsonl
状态：运行中  文件大小：1728 B  轮转阈值：2097152 B 保留旋转历史=12份 玩家名上限=256字符
已写事件：10  批次：3  轮转：2  队列：0
最后 ts：1792236508193  丢弃：0  最近错误：无
```

## 验证记录（2026-10-17，本机开发服 B509，未重启服务器）

| 项目 | 结果 |
| --- | --- |
| 脚本扫描/加载 | `sa scan` → 新增 1 脚本；`sa load wayzer/ext/liveEventFeed` 成功 |
| 故障检查 | `sa listFailed` 为空；`scriptdiag count` = 共161 / 加载157 / 启用152 / **失败0**（加载前为 160/156/151）；`scriptdiag commands livefeed` 命中 1 |
| 真实事件 | `system`（启停多条）、`map_change`（实测 3 条，均由真实换图流程产生，含中文图名、颜色标记、本地化模式「生存」） |
| 聊天链路 | 服务端无真实客户端在线，用控制台 `js` 构造 `EventType.PlayerChatEvent`（`Player.create()` 假玩家 + `arc.Events.fire`）走**真实监听链路**验证；`say` 实测只广播、不产生 `PlayerChatEvent`，不能用来测聊天 |
| JSON 完整性 | 全部行 PowerShell `ConvertFrom-Json` 解析成功；文件 UTF-8 无 BOM（首字节 `0x7B`）；字段 `ts/type/text/player`(+`playerId`) 与契约一致 |
| ts 严格递增 | 一口气构造 25 条同毫秒聊天 → 落盘 `ts` 连续 +1（…834 ~ …858，25 个值严格递增）；全程逐行校验无回退 |
| 转义 | 消息含 `"`、`\`、制表、换行、`[red]`、`[#00ff00]`、`[]`、中文 → JSON 转义正确、颜色标记原样保留 |
| 截断 | 710 字符文本 → 落盘 500 字符 |
| 轮转-启动时 | 临时把阈值调成 4096B 后重载：主文件 4186B → 自动 rename 成 `live-events.1.jsonl`，新主文件 89B |
| 轮转-运行中 | 主文件 3545B + 下批 1728B 超过阈值 → 写前轮转：`.1`=3545B、主文件=1728B |
| 轮转-手动 | `/livefeed rotate` 后主文件 1728B → `.1`，主文件待下条事件重建 |
| 轮转-多份（2026-10-18） | `livefeed status` 显示「保留旋转历史：12 份」「玩家名上限=256字符」；`livefeed rotate` 生成 `live-events.1.jsonl` 并打印「实时事件流已轮转：live-events.jsonl -> live-events.1.jsonl（保留旋转历史 12 份）」；链式移位按 `.1→.2…`，超出 12 份删最旧 |
| 停用收尾 | 每次热重载均能看到旧实例写出的 `system` 停止行，说明 `onDisable` flush 生效 |
| **未覆盖** | 真实 `join`/`leave`（无客户端可加入）、真实玩家 `chat`、`vote_*`（投票需真实玩家；轮询逻辑已实现但无运行实例证据）、队列溢出/磁盘写失败的降级路径（仅代码审查） |

> 部署后一旦有真实玩家加入/聊天/投票，脚本会自动采集，无需额外操作；建议首次多人时段抽查 `join`/`leave`/`vote_*` 三类行是否符合契约。

## 已知边界与 TODO

- **投票快速路径漏采**：见上文「投票接入的已知边界」；彻底修复需在 `vote.lib.kt` 增加 SA 事件（本轮按写作用域限制未改，留 TODO）。
- **`join`/`leave` 的 text 是固定文案**：未接入欢迎语/地区（需要依赖 `ipRegion` 等脚本的数据，本轮未做；契约允许 text 放欢迎语/地区，后续可补）。
- **广播类系统消息采集不到**：Mindustry 的 `broadcast`/控制台 `say` 没有事件可监听；如需把全服公告也进门户，可后续加轻量 hook 或提供 `livefeed say <text>` 运维广播命令（本轮未做）。
- **轮转阈值存在一批过冲**：写前检查，单文件最坏为阈值 + 一批（≤约 130KB），符合「约 2MB 轮转」的约定；
  保留 12 份旋转后磁盘占用上限约 26MB（主文件 + 12 份，各 ≤阈值+一批），可用 `keepRotatedFiles` 调整。
- **热重载会产生 `system` 停/启两条事件**：属正常行为，门户可直接展示或按 type 过滤。
- **同进程多实例**（旧实例未停干净又加载新实例）理论上可能出现双写；正常 SA 热重载是「先停旧再启新」，停用收尾会 flush，实测正常。

## 集成注意：SA 自定义事件必须用 `listenTo`（本轮实测踩坑）

- **Mindustry/arc 事件**（`EventType.PlayerJoin`、`EventType.PlayerChatEvent` 等）用 `listen<T> { }`；
- **ScriptAgent 自定义事件**（`cf.wayzer.scriptAgent.Event` 的子类，如 `MapLoadFinishedEvent`、`TrustLevelChangedEvent`）必须用 `listenTo<T> { }`。

本轮首次实现时误用了 `listen<MapLoadFinishedEvent>`：**编译通过、脚本 Enabled、无任何报错，但换图时监听器完全不触发**（`listen` 只把监听器挂到 `arc.Events`，而 SA 自定义事件由 `Event.Handler` 自己分派）。
改为 `listenTo` 后立即生效。

顺带发现的三处同类误用**已由 task-14 修复并审计**（2026-10-17，提交 `69d4932`；详见
[listen/listenTo 审计](listener-listen-vs-listenTo-audit.md)）：

- `wayzer/ext/serverStats.kts:311` `listen<TrustLevelChangedEvent>` → `listenTo`（等级分布即时迁移此前从未触发，
  属"即时性丢失"，玩家进服路径会自愈）；
- `wayzer/map/betterTeam.kts:97`、`wayzer/map/pvpProtect.kts:152` `listen<MapLoadFinishedEvent>` → `listenTo`
  （换图后禁用队伍刷新此前未执行；**PVP 保护此前完全失效**，修复后 PVP 图加载完成会真正开始保护）。
- **未验证**：`TrustLevelChangedEvent` 需真实玩家等级变化才能端到端确认；`betterTeam` 有 8+ 个 `contextScript` 依赖方，
  单脚本热重载不级联，建议低峰期冷启动一次彻底刷新。
