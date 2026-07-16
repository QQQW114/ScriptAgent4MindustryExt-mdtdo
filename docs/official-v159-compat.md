# 官方 Mindustry v159.x / MindustryX v159.6 兼容层说明

> 目的：让当前脚本集可以在官方 v159.x 服务端上尽量启动、测试菜单/账号/投票/点歌等功能。MindustryX 预览版已于 2026-07-15 跟进官方 v159.6，但尚未形成稳定正式版；在 X 端稳定并完成实际测试前，本文兼容层仍应保留。

## 总体原则

- 优先保持 X 端原行为：如果运行时检测到 X 端 API，则继续走原逻辑。
- 官方端缺少的 X API 不直接引用，改为反射检测；缺失时只降级对应边缘功能，避免整条依赖链加载失败。
- 不为官方端硬造高风险同步/网络 Hook；无法稳定兼容的实验功能直接 no-op，并打印明确警告。
- 兼容层集中使用 `*Compat`、运行时 `Class.forName(...)`、`javaClass.getField/getDeclaredField(...)` 等方式，方便搜索和切除。

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

### 4. 实验性同步频率限制在官方端与 B477 上 no-op

涉及文件：

- `C:\Users\qw114\Desktop\other\mdt保留\mdtserver\config\scripts\wayzer\reGrief\syncThrottle.kts`

原因：官方 v159 与 MindustryX B477 均已移除或变更 X35 时代以下接口或签名：

- `NetConnection.syncTime`
- `NetConnection.snapshotsSent`
- `Call.stateSnapshot(NetConnection, ...)`
- `Syncc.isSyncHidden(Player)`

这些能力属于高风险网络同步接管，不适合在官方端硬造。

兼容方式：

- 通过反射检测上述接口是否齐全。
- 若不齐全，`targetInterval()` 固定返回 0，脚本启用但不接管同步，并打印：
  `当前 MindustryX v159/B477 已移除或变更旧版单连接快照接口，实验性同步频率限制已安全降级为关闭。`
- 若 X 端接口齐全，则继续按原逻辑工作。

切除方式：

- 若未来只跑 X 端，可恢复直接字段/方法调用，删除 `throttleRuntimeSupported` 与 `*Compat` 反射函数。
- 若未来只跑官方端，建议直接删除/禁用该脚本，避免误以为同步限制仍生效。

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
- 原版/B477 的运行时资产清单是全服共享状态，不支持真正的“只给一个连接注册资产”。因此脚本只在目标玩家实际开始同步时临时挂载当前一首歌曲，结束或超时立即卸载；不再提前挂载整条队列。20Mbps 上行环境默认限制在线不超过 6 人才允许同步新歌曲，并在每名玩家之间保留恢复间隔。
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
- 实验性同步限制：官方端自动关闭，不会接管实体同步。
- 世界处理器发包速率保护：官方端没有 X 包事件时无法按包速率检测；仍保留其他不依赖包事件的保护。
- CP/资产同步：官方 v159 会优先走 `sendWorldAndAssets`；旧端没有该方法时只做普通世界同步，贴图/音频类资产可能需要重进或无法热同步。
- 小音效/点歌：依赖 v159 Data Asset。若运行端没有 `state.data` 音频资产列表或没有 `sendWorldAndAssets`，脚本会降级提示，但实际播放体验可能不完整。
- `server.properties` 中 `locale=default` 对官方 v159 会报 `Unknown config: 'locale'`，这是启动配置项差异，不是脚本兼容层；可在官方测试服配置中删除该行。

## MindustryX B477 + ScriptAgent 3.3.2 实测结果（2026-07-16）

- 服务端已切换为 `server-2026.07.15.B477.jar`，本轮不升级 ScriptAgent，继续使用 `ScriptAgent4MindustryExt-3.3.2.jar`。
- 清理旧脚本编译缓存后完成全量编译与启动验证：`共找到201脚本,加载成功197,启用成功144,出错0`。
- B477 的 `mindustryX.events.SendPacketEvent` 可用，`trafficMonitor.kts` 与 `limitLogicPacket.kts` 恢复 X 端发包事件链路；`locale=default` 也可正常应用。
- `syncThrottle.kts` 依赖的 X35 单连接快照字段/调用签名在 B477 中不再存在，因此继续安全降级关闭，不接管实体同步。本轮不为该高风险链路强行重写实现。
- 启动阶段只观察到既有的 H2 `getSetting` 慢事务提示、`tpsLimit` 冗余提醒和上述同步限制降级提醒；没有脚本编译/加载错误。
- 测试停服时出现的脚本停止超时和 watchdog 停顿提示发生在 SA 3.3.2 的集中卸载阶段，不属于 B477 启动兼容失败；生产服仍需重点观察正常运行期间是否出现同类停顿。

## 后续维护建议

1. 新增官方兼容时，优先在本文件补一条：原因、文件、降级行为、切除方式。
2. 搜索关键字可快速定位兼容层：
   - `Compat`
   - `Class.forName("mindustryX`
   - `getDeclaredField("statuses")`
   - `SaveOptions`
   - `throttleRuntimeSupported`
   - `PatchAsset`
   - `DataPatchLoadEvent`
   - `sendWorldAndAssets`
3. 等 MindustryX 跟进 v159 后，先跑脚本加载日志；如果 X 端已兼容，建议逐项删除 no-op 降级，保留低风险反射兼容也可以。
