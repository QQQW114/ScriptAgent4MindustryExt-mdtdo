# v159 网络同步与完整重同步

> 当前基线为 **Mindustry 160.3 / MindustryX 预览版 `prerelease-2026.09.13.B495`**（2026-09-13；
> 上一基线为正式发行版 X37 / v160.1，B491 更早），
> 160 起实体随世界流传输，见下文"160.1 起：实体随世界流传输"；其余同步不变量沿用。

## 故障模型

v159 的 `sendWorldAndAssets` 会让客户端清空本地实体、重新协商资产并再次确认世界。高上行时，UDP 快照丢失和可靠包排队会造成核心机引用、单位状态及建筑血量恢复缓慢。

音乐、杂交和 CP 都通过同一条完整世界/资产重同步链路放大上行拥塞。

## 当前保护

1. `worldResyncCoordinator.kts` 串行所有内部完整重同步，按 CP、杂交、普通、点歌、SFX 排序；队列上限 32、排队超时 180 秒，任务后保留 2.5 秒恢复间隔。
2. 玩家首次加入不再经过插件门控：已删除 `ConnectAsyncEvent` 阶段的等待/预留槽位，始终交给 Mindustry 原生连接流程，避免确认丢失后卡住全服新连接。
3. 内部 CP、杂交、点歌和 SFX 重同步仍保持全服单队列，但不再读取待加入连接或网络等级，不会反向阻塞首次入服。
4. `syncThrottle.kts` 仅保守调整原生全局实体快照间隔，不改变包可靠性。该快照包含单位位置/移动和 `Player.unit` 控制关系，压力时会从原生约 200ms 增大到 240/280/320ms，可能让丢包时的插值更粗，但不拦截或重放旧状态。每张地图首次进入限制时只广播一次同步限制/挂机检测提示。
5. `coreUnitRespawnCompat.kts` 只在主动取消附身，或客户端完成世界确认后修复核心机引用；当前已有核心单位时仅补快照，单位为空时才尝试 `checkSpawn()`。最多补两份 `Unit -> Player` 小型 UDP 快照，不额外重放 `PlayerSpawn`。

协调器只以真实 `PlayerConnectionConfirmed` 作为内部重同步正常完成。确认超时后不会立即释放内部任务槽位；最多再持有 120 秒，仍异常则断开该内部重同步目标，防止残留世界流与下一内部任务重叠。换图、离线和卸载均会清理任务状态。

## 流量口径

- **总上行**：游戏包、世界流、资产、音乐及其他网络输出，用于门控和带宽观察。
- **游戏同步上行**：实体、状态、建筑和游戏事件，用于性能优化与单位清理。
- **世界/资产流**：入服、音乐、CP、杂交同步，只用于网络保护，禁止触发单位清理。

原版 MindustryX B480 的 `SendPacketEvent` 仍未覆盖 v159 批量发送。本项目的 B480 自定义构建已让批量 `Net.send` 发出事件，并增加 `connections`、`targetCount`、`reliable`；`trafficMonitor.kts` 按“包大小 × 实际目标数”统计，并在启动时明确报告批量字段是否可用。游戏同步采用白名单分类，握手、欢迎、插件包和世界/资产流不会进入清单位口径。

## 不变量

- 不把建筑血量、单位死亡、玩家生成等关键可靠包改成 UDP。
- 不把实体快照改成可靠 TCP。TCP 与 UDP 没有跨通道时序保证，拥塞后到达的旧可靠快照会把已附身的玩家拉回旧核心机/旧位置。
- 不因玩家加入、音乐或 CP 世界流清理单位或触发换图。
- 门控及协调器异常时必须自动放行。
- 极端换图仅允许当前与平均 TPS 同时低于 5 并连续 120 秒。

## 160.1 起：实体随世界流传输（2026-09-12 核对）

上游在 **Mindustry 160** 的提交 `6ef175cb2`（"Building afterReadAll fix + Send entities over network"）中修改了
`core/src/mindustry/net/NetworkIO.java` 的世界收发：

- **发送**（`writeWorld`）：在 `writeContentHeader` + `writeMap` 之后，把原来的 `writeTeamBlocks` 一段替换为
  `writeEntityMapping(stream)` → `writeTeamBlocks(stream)` → `writeWorldEntities(stream, fogFilter)`；
  源码注释自述这是"模仿 `writeEntities`，但用自定义过滤器，略显脆弱"（`NetworkIO.java:65`）。
- **接收**（`loadWorld`）：`readMap` + `readEntities`，新增 `SaveReadState` 贯穿读取；
  读完后把 `Groups.all` / `Groups.unit` 里的实体 id 记入 `netClient.addRemovedEntity()`，
  用于丢弃"世界流还没建立时先到的实体快照"。

### 对本项目优化口径的影响评估（结论：不需要改代码，属于口径内自洽）

1. **流量口径不变**：三口径同源为网卡速率（`trafficMonitor` 读 `netstat -e`），世界流变大只会体现在
   "总上行/世界流"读数里，不需要包级分类，因此**没有需要适配的解析逻辑**。
2. **不触发破坏性措施**：按 `docs/performance-guard.md` 的分层原则，入服世界流属于"资源传输尖峰"，
   不得触发清单位/暂停玩法/换图；性能保护由**总上行**驱动，且单位清理只由游戏同步口径与 TPS 驱动。
3. **重同步协调器无需改动**：`worldResyncCoordinator` 只负责串行与超时，世界流内容变化不影响其语义；
   实体随世界流发送后，客户端不再需要为这批实体额外等待 UDP 快照，反而降低"世界已加载但实体缺失"的窗口。
4. **需要注意但不属于本轮改动**：世界流体积变大意味着**首次入服的可靠流占用更高**，
   在低带宽/高并发入服时可能拉长握手时间；本项目既不门控新玩家（不放行会被判定为破坏可用性），
   也不把世界流算作游戏同步压力，因此维持现状是符合既有不变量的选择。

### 与动态 Content（外部 CP/DP）的关系

本项目的 `contentsTweaker.kts` 会在动态 Content 注销前清理旧 DP 实体引用；实体现在会随世界流序列化，
所以**这条清理链路比 159 更关键**：若世界里残留指向已注销内容的实体，序列化阶段就可能失败。
本轮未改动该逻辑（160 适配曾经只把 `Groups.fire`/`Groups.puddle` 换成 `Groups.all` 遍历，
**该口径已于 2026-09-13 撤回**，见 [性能保护](performance-guard.md#火焰处理2026-09-13移除全部实体遍历)），
但已作为"160 下需重点回归"的项记录：真实多人 + 运行中卸载 DP 仍需受控观察。

## 局内热重载可行性核对（2026-09-13，Mindustry 160.3 源码）

**问题**：杂交 / 音乐 / 小音效 / CP-DP 这几套系统在让客户端同步改动时统一走
`worldResyncCoordinator.resyncWorldAndAssets()`（= `Call.worldDataBegin(con)` + `sendWorldAndAssets(player)`），
客户端表现为**重新加载地图**（等价原版 `/sync`）。是否存在"局内热重载、不重载地图"的更优雅方案？

**结论：内容/音频类改动做不到——这是 v160.3 协议强制的，不是脚本写法问题。** 依据（全部为 v160.3 源码）：

| 环节 | 源码事实 | 后果 |
|---|---|---|
| 服务端发起资产协商 | `core/.../NetServer.java:330-339`：`state.data.hasExternalAssets()` 为真时只置 `determiningAssets=true / receivingAssets=false / hasConnected=false` 并 `sendAssetRequirements(player)` | 资产阶段本身**不**重载世界 |
| 客户端报缺 | `core/.../NetClient.java:156-166`：读需求清单 → 缺的资产索引 → `Call.requestAssets(missing)` | — |
| 服务端回缺 | `core/.../NetServer.java:962-994`：`ids.length == 0`（客户端**全都有**）→ **依然 `sendWorldData(player)`**；否则 `AssetStream` 流式下发 | "已缓存"也照样发整份世界 |
| 客户端收资产 | `core/.../NetClient.java:187-215`：`StreamBegin(isAssets)` → `NetworkIO.loadAssets(...)` → **`Core.app.post(Call::requestWorld)`** | 客户端加载完资产**必定**主动要世界 |
| 服务端回世界 | `core/.../NetServer.java:954-960`：`requestWorld` → `sendWorldData(player)` | — |
| 客户端收世界 | `core/.../NetClient.java:149-154`：`WorldStream` → `NetworkIO.loadWorld(...)` + `finishConnecting()` | **这就是"重新加载地图"** |

即：**只要触发一次资产协商回合，最终一定走到 `sendWorldData` + 客户端 `loadWorld`**，与客户端是否已缓存无关
（`NetServer.java:969` 的注释就是 "no assets required, all cached"，紧接着仍然是 `sendWorldData`）。
另外"重载地图"的**即刻来源**是协调器先发的 `Call.worldDataBegin(con)`：客户端处理它时会
`Groups.clear` + `logic.reset`（`NetClient.java:484-499`），随后 `WorldStream` 里的 `loadWorld` 才重建世界。

### 资产的传输形态（决定"哪些改动必须重载"）

`NetworkIO.writeDataPatches`（`net/NetworkIO.java` 的世界流写入段，对应 `io/SaveVersion.java:604-625`）规定：
**patch / content 两类资产（`DataAssetType` 中标 `embedded=true`）每次世界流都内嵌全文**；
image / sound / music / bundle **只发 32 字节 sha256**，缺缓存时客户端只 `Log.warn` 并保持为空
（`SaveVersion.java:555-602`、`DataAssetType.java:8-13`）。
而客户端**建立内容对象的唯一入口**是 `state.data.load(assets)` ← `readDataPatches` ← `loadWorld`
（`SaveVersion.java:596-598`）——所以"新增内容"必然要重载世界（但**不必重连**，连接会被复用）。
附带结论：**没有音频推送通道**（运行期只有 `TextureStream` 这一条推送通道，仅 PNG 且落在 `net-` 前缀区），
缺音频在客户端表现为"静默无声"。

### 唯一存在的"不重载"通道：纹理

- 动态内容（DP/CP 装卸、基因杂交改 `UnitType` 字段）会改变**内容 id 空间**：`DataPatcher.apply(..., reloadContentWorld)` /
  `unapply(...)` 之后要 `fixContentArrays()`（`mod/DataPatcher.java:88-93, 221, 277-303`），客户端也要
  `DataManager.reloadContent(boolean)`（`mod/DataManager.java:29`）重建内容数组；客户端旧世界里的实体/建筑仍引用旧 id，
  **必须重读世界**才能对齐。
- 音频同样是**位置化 id**：`io/TypeIO.java:1223-1229` 声音按 `Sounds.getSoundId(sound)` 的 short id 传输，
  注释明确"只支持 `Sounds` 里的标准常量，mod 音频不支持"。所以客户端的音频/内容表必须和服务端一致，
  不能靠"少同步一次"糊过去。
- 顺带核对：MindustryX 的 patch 列表里**没有**改动这条资产回合的补丁（`patches/` 下无
  `sendAssetRequirements`/`requestAssets`/`AssetStream` 相关改动），只有 `0063` 的"v146 协议与内容兼容模式"
  （跨版本加入时的内容重映射）与 `0075` 的 DataPatcher 图标刷新。

### 唯一存在的"不重载"通道：纹理

- `NetClient.java:168-185` 的 `TextureStream` 处理器直接 `state.data.addTexture(name, png)` / `removeTexture(name)`，
  **完全不碰世界**；服务端 API 是 `mindustry.core.NetServer.sendTexture(String, byte[])` /
  `sendTexture(NetConnection, String, byte[])` / `removeTexture(...)`，配套 `DataManager.addTexture/removeTexture`。
  本项目的服务端下发菜单已经在用它（`Menus`/`MenuDialog` 的 `TextureStreamEvent`）。
- 也就是说：**纯贴图/图标类改动可以不重载**；一旦涉及 content JSON、音频或 bundle，就必须走资产回合 → 重载。

### 真正的"局内热重载"只有客户端补丁一条路

需要改客户端（我们的 MindustryX patch-first 仓库），要点：
1. `NetClient` 的 `StreamBegin(isAssets)` 分支**不再**无条件 `Call::requestWorld`，改为就地应用：
   `NetworkIO.loadAssets(...)` 后按需 `DataManager.reloadContent(false)` / `reloadImages()` / `reloadAudio()`
   + `DataPatcher.fixContentArrays()`，然后不请求世界；
2. 服务端要能区分"仅资产回合"与"内容回合"（新增标志/包），内容回合照旧重载；
3. `NetServer.requestAssets` 的 `ids.length == 0` 分支不能再无条件 `sendWorldData`。
   风险：客户端内容 id 与旧世界实体可能错位，只能严格限定在"只追加、不删除、不重排"的场景；收益范围仅限使用
   我们客户端构建的玩家。

### 现状口径（本项目选择）

`worldResyncCoordinator` 的串行 + 去抖 + 恢复间隔 + 等 `PlayerConnectionConfirmed`，是**协议允许范围内的正确缓解**，
本轮不改脚本。后续可评估（都需真实多人实测，且不要绕过 id 一致性）：
- 纯贴图类 CP 改动优先走 `sendTexture` 快路径（前提：确认该改动不含 content/音频）；
- 逐玩家"已确认拥有当前资产清单"的记账，用来跳过完全不必要的回合
  （只有"清单完全相同"才安全；"少发一部分"因 id 位置化而不安全）。

### 独立复核（只读审计子代理，结论一致）

- **协议里不存在"资产已同步但世界未重发"的合法终态**：`sendWorldAndAssets` 一次性把
  `hasConnected=false / determiningAssets=true / receivingAssets=false`（`NetServer.java:330-339`），
  这四个标志只能由 `connectConfirm`（`:996-1006`）走完；资产相关包穷举只有
  `AssetRequirementStream / AssetStream / TextureStream / WorldStream`（B495 JAR + `NetClient.java:149/156/168/187`），
  **没有 content/patch 包**；MindustryX 对 `DataManager|state.data|sendTexture|AssetStream` 零改动，
  没有独有能力绕过这条链。
- **协议外 hack 是死路**：在客户端发出 `Call.requestWorld` 之前把 `con.hasConnected` 置回 `true`，
  能让服务端早退、不再发世界流；但客户端 `NetworkIO.loadAssets` 只做 `assetCache.add`
  （`NetClient.java:164-176`），**不会** `state.data.load`，音频/内容依旧不可用；而且客户端不会再发
  `connectConfirm`，本项目的协调器等 `PlayerConnectionConfirmed` 会一路挂到超时。不要走这条路。
- **缺资产是静默失败**：缺 hash 只 `Log.warn`（`SaveVersion.java:587-590`），音频用 `file==null` 建空
  `Sound()/Music()`（`DataAudioLoader.java:34/55`），未知内容 id 回退 `contentMap[0]`（`ContentLoader.java:249-251`）
  ⇒ 现场表现是"静默无声 / 静默错内容"而不是报错。**这正是不能靠"跳过同步"随手省事的根本原因**：
  一旦判断错，没有任何报错可查。
- 澄清一处上游更新日志：v160.2 的 "data patch sounds only use streaming when above 100kb" 指的是
  **本地解码策略**（`DataAudioLoader.java:34`：`file.length() > 100_000 ? Sound.createStream : Sound.createLazy`），
  与网络流式无关——不要在文档里把它当成"音频可流式推送"的依据。

### 替代改进清单（按性价比排序，均未实施，待用户拍板）

1. **常驻热门曲目 + 按清单跳过回合**（收益最大）：把少量热门曲目在**换图/进服窗口**注册进 `state.data`，
   它们会随进服资产协商被客户端顺手下载；此后点歌时若"当前资产清单指纹 == 该玩家上次完成的指纹"，
   协调器可直接跳过回合（不 `worldDataBegin`）→ **不重载地图**。需要：给常驻曲目设下载量上限
   （`musicJukebox.kts:407-413` 已有该顾虑）、逐玩家记账并在 `PlayerConnectionConfirmed` 时重新播种。
2. **小音效改为窗口期注册**：同样在进服/换图时注册，只有新增/变更时才手动同步（`soundEffectMenu.kts:436-486`）。
3. **外部 CP 择时批量**：内容变更无法避免重载，但可固定在换图/世界加载窗口批量应用，
   而不是游戏中途逐玩家重载（`externalCpHotReload.kts:1136-1155`、`worldProcessorAdmin.kts:217-243`）。
4. **杂交**：短期做法是"服务端改内容 + 不主动重载，等下次自然世界流"（`skillsHybrid.kts` 已有
   `hybridAutoWorldSync` 开关可关掉自动同步）；真正零重载要客户端 Mod。
5. **图片走纹理流**：`sendTexture` + `net-` UI 菜单可替代 `pixelPicture.kts` 的逐格 `setNet`（省上行、显示更稳）。

## B485 构建与部署

- 基线：MindustryX `prerelease-2026.08.12.B485` / Mindustry `v159.7`。
- **B485 起不再打 MDT 自定义补丁**：B480 时代的批量发送事件补丁与“可靠自定义实体快照” API 已被上游移除/重构，脚本已相应改为网卡上行统计与 `checkSpawn()` 恢复。
- JAR：`mdtserver/server-2026.08.12.B485.jar`（官方发行版）。
- SHA-256：`4C9C8F89251B351C885267C1E2D5BC51DCF3CEC702363AC6A774C390F39009A5`。

### 历史（B480）

- 基线：MindustryX `prerelease-2026.07.20.B480` / Mindustry `v159.7`。
- 补丁：批量发送事件，以及已编入但不再使用的“可靠自定义实体快照” API。实测确认后者在 TCP 拥塞时会产生延迟旧状态，业务脚本已禁止调用。
- 构建：`gradle --no-daemon server:dist -x tools:doPack`。
- JAR：`mdtserver/server-2026.07.20.B480-mdtdo.jar`。
- SHA-256：`8257C7185BF7915270C396B05A39AD32DD6C6CEC71135CD67A70C4E0906E5ACC`。
