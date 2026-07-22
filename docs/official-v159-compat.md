# 官方 Mindustry v159.x / MindustryX B480（v159.7）兼容层说明

> 当前生产候选基线为 MindustryX `prerelease-2026.07.20.B480` / Mindustry v159.7，并继续保留官方端与旧 API 的反射降级。ScriptAgent 仍按项目当前版本独立维护，不与本轮网络补丁混合升级。

## 总体原则

- 优先保持 X 端原行为：如果运行时检测到 X 端 API，则继续走原逻辑。
- 官方端缺少的 X API 不直接引用，改为反射检测；缺失时只降级对应边缘功能，避免整条依赖链加载失败。
- 不为官方端硬造高风险同步/网络 Hook；无法稳定兼容的实验功能直接 no-op，并打印明确警告。
- 兼容层集中使用 `*Compat`、运行时 `Class.forName(...)`、`javaClass.getField/getDeclaredField(...)` 等方式，方便搜索和切除。

## 2026-07-21：B480 / v159.7 自定义专服补丁

- 参考源码已更新到官方 Mindustry `v159.7` 与 MindustryX `prerelease-2026.07.20.B480`。上游的 “Fixed large world sending” 与连接修复位于 Steam `desktop/.../SNet.java`，不覆盖 headless 专服的 `ArcNetProvider`、`NetServer.sendWorldAndAssets`、`connectConfirm`、批量 `Net.send` 或核心机恢复链路。
- `SendPacketEvent` 增加 `connections`、`targetCount`、`reliable`，并在批量 `Net.send(Object, Iterable<NetConnection>, boolean)` 发出事件，解决过去流量统计只覆盖单连接/广播入口而漏算批量快照的问题。
- B480 JAR 仍包含 `NetServer.writeCustomEntitySnapshot(Player, Iterable<Syncc>, boolean)`，但生产脚本已禁止使用其可靠模式。TCP 与 UDP 没有跨通道顺序；上行拥塞时，可靠旧快照可能晚于新 UDP 状态到达，把玩家拉回旧核心机或旧附身位置。
- 补丁文件为 `patches/client/0075-H.API-emit-SendPacketEvent-for-bulk-sends.patch` 与 `0076-H.API-allow-reliable-custom-entity-snapshots.patch`。
- 构建命令：`gradle --no-daemon server:dist -x tools:doPack`。普通 `server:dist` 会被无关的 `tools:doPack` ClassNotFound 阻断。
- 部署候选：`mdtserver/server-2026.07.20.B480-mdtdo.jar`；SHA-256：`8257C7185BF7915270C396B05A39AD32DD6C6CEC71135CD67A70C4E0906E5ACC`。
- B480 冷启动验证结果：共找到 156 脚本、加载成功 152、启用成功 148、出错 0。

## 已处理的兼容点

### 1. 单位状态叠加 `statuses()` 缺失

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\cmds\effect.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\user\ext\skills.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\user\ext\skillsHybrid.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\mapScript\tags\hybrid.kts`

原因：官方 v159 的 `mindustry.gen.Unit` 没有公开 `statuses()` 方法，但具体单位类仍有 protected `statuses` 字段；旧脚本直接 `unit.statuses()` 会编译失败。

兼容方式：

- 新增 `statusEntries(...)`，通过反射沿父类查找 `statuses` 字段并缓存 `Field`。
- `/effect`、技能叠加 buff、杂交基因 buff 快照/继承改用该兼容函数。

切除方式：

- 若后续 X 端重新提供稳定 `unit.statuses()`，可把 `statusEntries(unit).add/forEach/size` 改回 `unit.statuses().add/forEach/size`，删除 `statusFieldCache/statusEntries`。

### 2. `SaveIO.write(Fi, StringMap)` API 改为 `SaveOptions`

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\map\autoSave.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\cmds\voteSave.kts`

原因：官方 v159 移除了/不再暴露旧签名 `SaveIO.write(Fi, StringMap)`，改用 `mindustry.io.SaveOptions.extraTags`。

兼容方式：

- 新增 `writeSaveCompat(file, extraTags)`：
  1. 优先反射寻找旧版 `SaveIO.write(Fi, StringMap)`；
  2. 找不到时创建 `mindustry.io.SaveOptions`，设置 `extraTags`，再调用 `SaveIO.write(Fi, SaveOptions)`。

切除方式：

- 如果只支持 v159+，可直接使用 `SaveOptions`；
- 如果只支持旧 X 端，可改回 `SaveIO.write(tmp, extTag)` 并删除兼容函数。

### 3. MindustryX `SendPacketEvent` 缺失

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\reGrief\trafficMonitor.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\reGrief\limitLogicPacket.kts`

原因：官方端没有 `mindustryX.events.SendPacketEvent`，直接 import 会导致脚本加载失败，并连带 `securityGuard`、`serverPressure` 等依赖失败。

兼容方式：

- 移除直接 import，改为 `Class.forName("mindustryX.events.SendPacketEvent")` 运行时检测。
- 检测到 X 端事件时，通过 `listen<Any>(sendPacketEventClass)` 继续统计/拦截。
- 官方端检测不到时：
  - `trafficMonitor` 保留预算配置与命令，但精确上行估算为 0/空数据，并打印一次警告；
  - `limitLogicPacket` 禁用“世界处理器发包速率”统计，仅保留其他不依赖 X 事件的保护。

切除方式：

- 等 X v159 可用后，可恢复直接 import `SendPacketEvent` 与强类型监听；或保留反射版本以继续兼容官方端。

### 4. v159/B480 快照频率保护替代 X35 单连接接管

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\reGrief\syncThrottle.kts`

原因：官方 v159 与 MindustryX B480 均已移除或变更 X35 时代以下接口或签名：

- `NetConnection.syncTime`
- `NetConnection.snapshotsSent`
- `Call.stateSnapshot(NetConnection, ...)`
- `Syncc.isSyncHidden(Player)`

这些能力属于 X35 的逐玩家手工同步接管，不能继续套用到 v159 的共享序列化/批量发送流程。

兼容方式：

- 不再反射或重写 `syncTime`、逐玩家 `stateSnapshot/entitySnapshot/hiddenSnapshot`。
- 通过反射读取 v159 原生 `Administration.Config.snapshotInterval`，压力时只增大原生快照间隔，恢复时还原启用前值。
- 不再拦截、取消或重发状态、实体、建筑快照；可靠的建筑血量更新也不会降级为 UDP。
- 官方端与 X 端都只调整原生 `snapshotInterval`；B480 的 `SendPacketEvent` 仅用于统计和既有逻辑包保护。

切除方式：

- 若未来上游提供稳定的“排除未完成连接”批量快照 API，应另行审计后接入，不能恢复事件取消后手工重发的旧方案。
- 不建议恢复 X35 逐玩家自定义序列化；这会丢失 v159 的共享序列化优化并重新引入幽灵单位风险。

### 5. `Rules.hiddenBuildItems` 缺失

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\user\ext\skillsGodAdmin.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\user\skillShop.kts`

原因：X35 的 `Rules.hiddenBuildItems` 在官方 v159 不存在，直接访问会编译失败。

兼容方式：

- 新增 `clearHiddenBuildItemsCompat(rules)`：反射查找字段，存在则读取 size 并 clear；不存在则返回 0。
- “开放科技/开放全部科技限制”仍会清理 `bannedBlocks`、`bannedUnits`、`researched` 等官方端存在的字段。

切除方式：

- 若 X 端恢复该字段，可继续保留反射版；若只跑 X 端且想简化，可改回直接 `rules.hiddenBuildItems.size/clear()`。

### 6. `healthChanged()` 缺失

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\user\ext\skillsLevel2.kts`

原因：部分版本的单位/建筑没有公开 `healthChanged()`，范围治疗直接调用会在官方端编译失败。

兼容方式：

- 新增 `notifyHealthChangedCompat(target)`，存在同名无参方法时反射调用，不存在则跳过。
- 治疗本身仍正常执行；缺少该方法时只少一次即时健康变化通知，常规实体/建筑同步仍会更新。

切除方式：

- 如果目标端稳定提供 `healthChanged()`，可恢复直接调用。

### 7. v159 Data Asset / Content Patch 桥接

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\coreMindustry\contentsTweaker.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\user\ext\skillsHybrid.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\mapScript\tags\hybrid.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\map\worldProcessorAdmin.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\map\externalCpHotReload.kts`

原因：官方 v159 将旧 CP 流程从 `state.patcher` / `ContentPatchLoadEvent` 迁移到 `state.data` / `DataPatchLoadEvent` / `mindustry.mod.data.PatchAsset`。原脚本如果继续直接访问 `state.patcher`，会在官方端编译或运行失败；同时 X35/旧端仍需要旧 patcher 路径。

兼容方式：

- `contentsTweaker.kts` 提供统一桥接函数：
  - `currentPatchStrings()`
  - `loadedPatchInfos()`
  - `patchInfoFor(...)`
  - `applyPatchStrings(...)`
- `applyPatchStrings(...)` 优先尝试 v159 原生 Data Asset 补丁路径：反射创建 `PatchAsset`，调用 `state.data.reloadPatches(Seq<PatchAsset>)`。
- v159 路径不可用时回退旧版 `state.patcher.apply(Seq<String>)`，并尽量触发旧版 `ContentPatchLoadEvent`。
- 杂交、地图特色杂交、`/cp` 管理与 `/vote cp` 外部 CP 都改为通过上述桥接函数读取/重放运行态 CP，不再直接读写 `state.patcher`。

切除方式：

- 若未来只支持官方 v159+，可删除旧版 `legacyPatcher()` / `applyLegacyPatches(...)` / `ContentPatchLoadEvent` 兼容分支，保留 `state.data` + `PatchAsset` 路径。
- 若未来只支持 MindustryX 旧 patcher，可删除 `nativeDataManager()` / `applyNativeDataPatches(...)` 分支并恢复直接 `state.patcher` 调用。
- 若 MindustryX v159 保留官方 Data Asset API，建议保留桥接函数，只把业务脚本继续维持在统一入口，减少再次迁移成本。

### 8. 地图脚本 CP 加载事件兼容

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\mapScript\module.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\maps.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\mapScript\lib\ScriptMapGenerator.kt`

原因：地图特色脚本与脚本生成地图原本依赖旧版 `ContentPatchLoadEvent` 把地图/脚本 CP 注入旧 patcher；官方 v159 使用 `DataPatchLoadEvent` 收集服务器/地图 Data Asset。

兼容方式：

- `mapScript/module.kts` 运行时同时尝试注册 `ContentPatchLoadEvent` 与 `DataPatchLoadEvent`：
  - 旧版事件向 `Seq<String>` 追加补丁字符串；
  - v159 事件向资产序列追加 `PatchAsset`。
- `wayzer/maps.kts` 在旧版或 v159 补丁事件阶段都尽量提前应用 `MapManager.tmpVarSet`，`WorldLoadBeginEvent` 继续兜底，避免地图 rules/tags 因事件时机变化丢失。
- `ScriptMapGenerator.kt` 生成地图加载时同样走 `loadPatchesCompat()`：优先触发 `DataPatchLoadEvent` 并调用 `state.data.load(...)`，不支持时回退旧版 `ContentPatchLoadEvent` + `state.patcher.apply(...)`。

切除方式：

- 只保留官方 v159+ 时，可删除旧版 `ContentPatchLoadEvent` 注册与 `state.patcher` 回退。
- 只保留旧端时，可删除 `DataPatchLoadEvent` / `PatchAsset` / `state.data.load` 分支。

### 9. 世界数据同步与 159 服务器资产的风险边界

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\user\ext\skillsHybrid.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\mapScript\tags\hybrid.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\map\externalCpHotReload.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\map\worldProcessorAdmin.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\ext\soundEffectMenu.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\ext\musicJukebox.kts`

原因：官方 v159 增加服务器 Data Asset，同步补丁、音效、音乐时只发送旧 `sendWorldData(Player)` 无法让客户端补收新资产；但 `sendWorldAndAssets(Player)` 也不是轻量的“补发单个资产”接口，而是完整的资产确认与重新进入世界握手。

源码复核结论（官方 v159.6）：

- `NetServer.sendWorldAndAssets(Player)` 在存在外部资产时会把连接的 `determiningAssets=true`、`receivingAssets=false`、`hasConnected=false`，再进入资产需求/请求/世界数据/`connectConfirm` 流程。
- 官方地图/世界重载使用 `WorldReloader.begin/end()`：开始时清除玩家单位并广播 `worldDataBegin`，结束时 `player.reset()` 后再执行 `sendWorldAndAssets`。这说明该 API 的设计前提是完整世界重载，而不是在正常游戏中无状态地补发资产。
- 从 v159.1 到 v159.6，`NetServer.sendWorldAndAssets`、`sendWorldData` 与该资产握手流程没有实质修改。因此 159.6 没有解决“运行中补发资产后核心机/单位消失、重复进入世界”的问题。
- SA v3.4.0 虽增加 `mapAssets`，但其 `wayzer/maps.manager.kt` 换图路径仍调用 `sendWorldData(Player)`，没有使用 `sendWorldAndAssets(Player)`；在线玩家换到含新资产的地图时仍可能缺少资产。该问题在 v3.4.0 后的 8.0 分支当前提交中也尚未修正。

当前兼容代码状态：

- 外部 CP、音乐和小音效脚本仍保留 `sendWorldAndAssetsCompat(...)`，用于实验性手动同步或既有功能兼容。
- 这些入口不能再视为“稳定的资产热同步”。分批间隔只能降低上行尖峰，不能修复完整握手带来的玩家状态重置问题。
- 小音效已禁止玩家进服后自动重发；`/sfx sync` 只应作为排障手段。
- 点歌运行时新增音乐若必须让客户端补收，目前最稳妥方式仍是玩家重进，或等待上游提供单资产同步能力。

切除方式：

- 不要因为只支持官方 v159+ 就把所有入口直接替换为 `sendWorldAndAssets(Player)`；除非调用点本身就在完整换图/世界重载流程内。
- 若未来 X 端或官方端提供轻量的单资产同步接口，应统一替换音乐、小音效和外部 CP 的运行中同步入口。
- 若目标端没有稳定的轻量资产同步，禁用运行中新增音频资产，改为服务器启动前预置资产或要求玩家重进。

### 10. 启动目录与服务器资产目录

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\start-server.ps1`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\start-server.sh`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\assets\`

原因：官方 v159 把服务器 Data Asset 放在数据目录 `assets/` 下，并会把旧 `patches/` 迁移到 `assets/patches/`。如果启动脚本每次都重新创建旧 `patches/`，会导致官方端反复出现迁移警告。

兼容方式：

- 启动脚本会确保以下目录存在：
  - `config/assets/patches`
  - `config/assets/content`
  - `config/assets/bundles`
  - `config/assets/sprites`
  - `config/assets/sounds`
  - `config/assets/music`
- 启动脚本不再主动创建旧版 `config/patches`。

切除方式：

- 若未来只支持旧端且不使用 v159 Data Asset，可恢复旧 `config/patches` 目录逻辑。
- 若继续支持 v159 或 MindustryX v159，建议保留 `config/assets/*` 初始化。

### 11. 基于 v159 Data Asset 的音效/点歌脚本

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\ext\soundEffectMenu.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\ext\musicJukebox.kts`
- `C:\Users\qw114\Desktop\other\mdt保留\docs\help-menu.md`
- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\coreMindustry\menu.kts`

原因：小音效与点歌依赖 v159 Data Asset 让客户端自动下载服务器音频。它们不是单纯的“官方端降级兼容”，但属于为了官方 v159 新资产系统增加的脚本，后续若切回旧端或 X 端未跟进该 API，需要能快速定位。

兼容方式：

- 小音效读取 `config/assets/sounds/`。官方 v159 的 DataAsset sound 运行时 ID 从 `100001` 开始，但 `Call.sound` 仍按 `short` 序列化声音 ID，直接播放自定义 `SoundAsset` 会在客户端错位/无声；因此 `soundEffectMenu.kts` 会把同一音频同时热注册为 `SoundAsset` 与 `MusicAsset(sfx-*)`，默认通过 `Call.playMusic(String)` 的字符串通道播放。客户端 `DataAudioLoader` 会把该音乐资产注册为 `dp-sfx-*`，所以实际发送的播放名必须带 `dp-` 前缀。代价是小音效受客户端“音乐音量”影响，不再走音效音量。
- 运行中新增/修改音效时，`soundEffectMenu.kts` 会反射创建音频资产、调用 `readOverride(...)` 并执行 `DataManager.reloadAudio()` 热注册；同时会检查明显伪装格式（如 `.mp3` 文件头实际为 `ftypM4A` 的 M4A/MP4 容器），避免菜单显示成功但客户端无法解码而无声。
- 点歌文件不再放入 `config/assets/music/`。v159/B477 会递归扫描该目录，并在每名新玩家进服时自动协商/同步其中全部音乐；缓存歌曲放在这里会绕过点歌投票的“仅同意者同步”设计。
  - 网络缓存：`config/music-jukebox/jukebox/`
  - 内置曲库：`config/music-jukebox/library/`
  - 旧 `config/assets/music/jukebox/` 与 `library/` 会在脚本启用时自动迁移，并刷新服务器固定资产列表。
- 网易云点歌支持普通歌曲 ID/链接与 `/dj?id=...` 电台节目链接；DJ 节目会解析 `program.mainSong.id` 后再下载真实音频。管理员可用 `/music limit size|duration|cache <值>` 调整单曲大小、时长与缓存保留数量。
- 点歌仍通过 `sendWorldAndAssetsCompat(...)` 尝试给同意播放的目标玩家补发音乐资产，再播放对应音频；同意者会先进入全局同步队列，避免多人同时触发世界+资产重同步造成上行/磁盘尖峰。该队列只能控制负载，不能保证完整握手期间玩家状态不被重置，因此此路径仍属于实验功能。全服强制播放入口已移除，`/music play` 仅作为兼容入口改为发起点歌投票。
- 歌曲只在同意者进入同步队列后临时注册进当前 `state.data`；对应队列不再使用后立即从服务端音乐资产列表移除。客户端已经同步的歌曲仍保留在其本地资产缓存，服务端后续可直接向该玩家播放，但后来进服的玩家不会被动下载历史点歌。
- 自动播放不再按歌曲大小估算固定等待时间。脚本会等待客户端发回 `PlayerConnectionConfirmed`（表示资产接收、世界数据加载均已完成），在该事件内恢复 `hasConnected` 以阻止重复 motd/`PlayerJoin`，再稍作音频注册缓冲后发送播放包。慢线路不会再因为超过估算时间而过早播放。
- 达到主超时后会释放同步队列，但保留默认 120 秒的迟到确认宽限；若客户端在宽限期内最终完成，歌曲会加入“我的已同步音乐”，不再擅自自动播放。宽限结束仍无确认时才释放临时资产记录。
- 玩家可通过 `/music synced` 打开“我的已同步音乐”菜单，或 `/music replay <编号/名称>` 手动重播本次连接中已经同步完成的歌曲；手动重播只发送播放指令，不会再次传输音乐文件。
- 原版/B477 的运行时资产清单是全服共享状态，不支持真正的“只给一个连接注册资产”。因此脚本只在目标玩家实际开始同步时临时挂载当前一首歌曲，结束或超时立即卸载；不再提前挂载整条队列。已移除“全服在线超过 6 人便禁用新歌曲同步”的粗粒度限制，改为单人串行队列、世界同步状态检查、上行预算与玩家间恢复间隔保护。
- 服务器浏览器人数来自 `Groups.player.size()`。音乐重同步期间玩家连接仍在服务端玩家组中，即使其客户端正停留在无核心机/世界加载状态也会计入在线人数；因此“浏览器显示20人、实际只看到7人活动”通常代表其余连接被资产/世界流拖住，而不是数据库人数或虚假机器人计数。

### 地图脚本

- 159 DataPatcher 不再接受把嵌套字段路径伪装成内容名，例如 `quell.weapons.0.bullet.spawnUnit` 会被视为实例化新内容。保留脚本需在已有内容对象内部使用字段路径。
- 地图杂交的动态基因补丁仍会调用 `sendWorldAndAssets` 逐人同步，否则客户端无法及时跟进运行时内容修改；该路径会重载世界，因此保留防抖与逐人间隔。
- 旧地图脚本已收缩，仅保留 `14668`、`15450`、`@hybrid`、`@flood` 四个重点玩法，减少未验证旧API进入生产服。
- 小音效按固定资产处理：脚本加载阶段、`onEnable` 与 `WorldLoadEvent` 都会把 `config/assets/sounds/` 注册进 `state.data`，避免地图加载替换 `state.data` 后新进玩家拿不到 `sfx-*` 音频；播放前不会自动补发资产。实测玩家进服后自动 `sendWorldAndAssets` 容易卡在无核心机/无单位状态，因此小音效侧已禁止所有自动补发，只保留 `/sfx sync` 手动强制同步。若玩家刚加入后立即播放小音效，脚本只会延迟补发一次 `playMusic` 播放包，不重发世界/资产。
- 官方 v159 的 `sendWorldAndAssets(Player)` 会把 `NetConnection.hasConnected` 临时置为 `false`，客户端同步完世界后会再次触发 `connectConfirm`。点歌脚本不再提前猜测并恢复该状态，而是等待 `PlayerConnectionConfirmed` 后再设置，避免重复 motd/`PlayerJoin` 的同时不抢跑官方握手。小音效仍不主动触发这条世界重同步链路。

切除方式：

- 若目标端没有稳定 Data Asset 音频同步，直接禁用/删除 `soundEffectMenu.kts` 与 `musicJukebox.kts`，同时移除 `menu.kts` 与 `help-menu.md` 中对应入口即可。
- 若未来 X 端提供更轻量的“单资产同步”接口，可只替换 `sendWorldAndAssetsCompat(...)` 和播放前等待逻辑，保留菜单/权限/缓存层。

## 当前官方端测试时的预期降级

- 上行估算：官方端没有 X 包事件，`/traffic` 只能查看/设置预算，实时估算不准或为 0。
- 快照保护：官方端仍可调整 v159 原生 `snapshotInterval`；当前实现无论官方端还是 X 端都不执行快照拦截/重路。
- 世界处理器发包速率保护：官方端没有 X 包事件时无法按包速率检测；仍保留其他不依赖包事件的保护。
- CP/资产同步：官方 v159 会优先走 `sendWorldAndAssets`；旧端没有该方法时只做普通世界同步，贴图/音频类资产可能需要重进或无法热同步。
- 小音效/点歌：依赖 v159 Data Asset。若运行端没有 `state.data` 音频资产列表或没有 `sendWorldAndAssets`，脚本会降级提示，但实际播放体验可能不完整。
- `server.properties` 中 `locale=default` 对官方 v159 会报 `Unknown config: 'locale'`，这是启动配置项差异，不是脚本兼容层；可在官方测试服配置中删除该行。

## MindustryX B477 + ScriptAgent 3.3.2 实测结果（2026-07-16）

- 服务端已切换为 `server-2026.07.15.B477.jar`，本轮不升级 ScriptAgent，继续使用 `ScriptAgent4MindustryExt-3.3.2.jar`。
- 清理旧脚本编译缓存后完成全量编译与启动验证：`共找到201脚本,加载成功197,启用成功144,出错0`。
- B477 的 `mindustryX.events.SendPacketEvent` 可用，`trafficMonitor.kts` 与 `limitLogicPacket.kts` 恢复 X 端发包事件链路；`locale=default` 也可正常应用。
- 当时的 `syncThrottle.kts` 因 X35 单连接接口消失而安全降级；该历史结论已在 2026-07-18 的后续实现中替换为 v159 原生快照间隔保护，不再使用旧接口。
- 启动阶段只观察到既有的 H2 `getSetting` 慢事务提示、`tpsLimit` 冗余提醒和上述同步限制降级提醒；没有脚本编译/加载错误。
- 测试停服时出现的脚本停止超时和 watchdog 停顿提示发生在 SA 3.3.2 的集中卸载阶段，不属于 B477 启动兼容失败；生产服仍需重点观察正常运行期间是否出现同类停顿。

## 后续维护建议

1. 新增官方兼容时，优先在本文件补一条：原因、文件、降级行为、切除方式。
2. 搜索关键字可快速定位兼容层：
   - `Compat`
   - `Class.forName("mindustryX`
   - `getDeclaredField("statuses")`
   - `SaveOptions`
   - `snapshotInterval`
   - `connectSyncGuard`
   - `PatchAsset`
   - `DataPatchLoadEvent`
   - `sendWorldAndAssets`
3. 等 MindustryX 跟进 v159 后，先跑脚本加载日志；如果 X 端已兼容，建议逐项删除 no-op 降级，保留低风险反射兼容也可以。

## MindustryX B477 + ScriptAgent 3.4.0 迁移结果（2026-07-17）

- 开发服已从 SA 3.3.2 切换到 `ScriptAgent4MindustryExt-3.4.0-allInOne.jar`，旧 JAR 改名为 `.disabled` 备份。
- 已补齐 SA 3.4 `.metadata` 模块描述、K2 `-Xcontext-parameters`、新版 Command API/控制指令/热加载接口，以及旧脚本使用的 `contextScript<T>()` 兼容层。
- 数据库已改用 SA 3.4 `Services`：新增 `coreLibrary/db` 模块，`DBApi` 包名为 `coreLib.db.DBApi`；连接器成功日志应包含“连接已建立”“表结构检查”“SA 3.4 Database Provider 已注册”。禁止重新把数据库服务退回 kts 内的旧 `ServiceRegistry` 对象，否则会因脚本类加载器隔离复现 `No Provider`。
- 技能共享库适配 Kotlin 2.3：`SkillScope` 显式携带 `arg`，避免匿名 context parameter 只能满足上下文约束、却无法直接解析 `CommandContext.arg`。管理员技能、杂交、三级技能与 `/tp` 已恢复编译。
- 重点地图脚本 `14668`、`15450`、`@hybrid`、`@flood` 均可加载；`15450` 将柠檬 DAO 的 `PlayerData` 使用别名导入，避免与地图内部同名数据类冲突。
- 当前冷启动修复后 `sa fail` 无输出。首次全量 K2 编译/集中启用阶段可能触发 watchdog 的启动期长停顿；应与服务器已经开放端口后的运行时停顿区分。

### 159 取消附身后核心机定向恢复

新增：`mdtserver/config/scripts/wayzer/reGrief/coreUnitRespawnCompat.kts`

- 只处理“玩家原附身单位仍然存活，但玩家单位变为 null”的主动取消附身场景；原单位正常死亡仍走原版死亡延迟。
- 默认 120ms 后若服务端仍无玩家单位，调用 `player.checkSpawn()` 请求核心机。
- 159 的实体快照可能先写 Player、后写新核心单位；客户端读取 Player 时找不到单位 ID，会把 `Player.unit` 保持为 null。兼容脚本在 `PlayerConnectionConfirmed` 后，若服务端已有有效核心单位就直接按“核心单位 -> Player”补快照；服务端单位为空时才尝试 `checkSpawn()`。
- 不再额外重发 `PlayerSpawnCallPacket`，也不再发送可靠实体快照。前者在客户端会先执行 `player.set(core)`，后者会在 TCP 拥塞后成为延迟旧状态，两者都可直接造成可见回弹。
- 每次补发前复核连接已完成资产/世界同步、generation 未变、玩家当前仍控制同一核心单位；一旦附身其他单位立即取消旧修复。
- 该修复不调用 `sendWorldData` 或 `sendWorldAndAssets`，不会让玩家重新进入完整世界/资产同步，也不会给全服增加大流量。
- 玩家离线、换图时清理内存状态；换队/强制观战若目标队伍无核心，不会凭空生成核心机。

实机回归必须覆盖：首次进服、附身存活单位后取消附身、正常死亡、换队、强制观战/解除观战、换图。服务端加载成功只能证明 API 兼容，客户端是否立即恢复核心机仍需真实客户端确认。

### SA 3.4 生产服首次迁移的脚本类加载器空指针

若生产服出现 `ScriptClassLoader.<init>(ScriptClassLoader.kt:24)` 的 `NullPointerException`，而具体脚本在开发服能够正常编译，这不是业务脚本内部空指针。SA 3.4 该行会直接读取每个编译依赖的 `scriptInfo.classLoader!!`；旧版本缓存残留，或启动并行实例化时某个依赖尚未建立 ClassLoader，都会在这里失败。

处理顺序：

1. 若服务器已经完成启动且依赖脚本均已启用，先在控制台执行 `sa reload wayzer/user/achievement`，再执行 `sa reload wayzer/ext/playerInfoTripleTap` 和 `sa fail`。依赖已实例化后重载通常可直接恢复。
2. 若仍失败，停服后删除或改名 `config/scripts/cache/`，再完整重启，让 SA 3.4 重新编译全部脚本。不要删除 `config/scripts/data/`，数据库和业务配置不在编译缓存目录内。
3. 从旧 SA 迁移时不要把生产服原有 `config/scripts/cache` 与新脚本一起保留或复制；该目录已加入 Git 忽略。

该错误与 Java 版本、成就数据库内容无关。若清空缓存后每次冷启动仍随机复现，则属于 SA 3.4 高依赖脚本的并行实例化竞态，应保留完整启动日志并考虑回退 SA 或修补上游 Loader。

### SA 3.4 的 H2/MVStore 类加载器冲突与控制台阻塞

- `coreLibrary/extApi/KVStore` 使用 `h2-mvstore:2.3.232`；MDT 数据库连接器默认使用 H2 JDBC `2.0.206`。二者可以在隔离模块中并存，但不能同时暴露给同一业务脚本依赖链。
- `coreLibrary/db` 已移除 H2 Maven 导入，`wayzer/module.kts` 也不再依赖 `coreLibrary/DBConnector`，只依赖数据库 API。连接器使用 Connection lambda，让驱动留在连接器自身 ClassLoader。
- 原冲突表现为玩家进入时 `wayzer.ext.RegionAutoLang.hasManualLang` 报 `loader constraint violation ... org.h2.mvstore.MVMap`，随后地区语言设置失败。部署修复后必须清空 `config/scripts/cache` 全量重编译。
- 生产日志还显示主线程卡在 `JLine Display.update/printAbove/FileOutputStream.write`，最长可持续数分钟。`coreMindustry/console.kts` 已把终端刷新转移到有界 IO 队列；游戏线程只投递字符串，不再同步等待宿主面板或慢磁盘刷新终端。

## 2026-07-18：B477 网络同步与性能压力重新分层

> 历史记录：其中基于发包事件隔离待加入连接快照的方案已在 B480 定稿中删除；当前实现以本文开头的 2026-07-21 章节为准。

- `trafficMonitor.kts` 区分总上行、游戏同步上行、世界/资产流；并读取 TCP 待发积压、待加入连接与活动流。
- `serverPressure.kts` 将游戏同步上行用于性能等级，将世界/资产流和 TCP 积压只用于网络保护；音乐、CP、玩家入服不会再触发单位清理。
- 新增 `connectSyncGuard.kts`：只在网络压力时限制同时进行世界同步的人数，正常状态不限制；等待超时、压力数据失效、脚本异常或卸载均 fail-open，防止永久无法入服。
- `syncThrottle.kts` 当时改用 v159 原生 `snapshotInterval` 并尝试事件重路；该重路后来审计为风险过高，B480 版本已彻底移除，只保留原生间隔调整。
- 标准/实验性性能措施合并；`/vote perf off` 只关闭本局世界清理与规则调整，网络 fail-open 保护继续运行。
- 最终换图只允许在当前 TPS 与滑动均值每次采样都低于 5、连续 2 分钟时触发。
