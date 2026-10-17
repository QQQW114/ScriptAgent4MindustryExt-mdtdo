# 小音效 Sound 通道切换与重叠播放审计（MindustryX B509 / Mindustry 160.7）

> 审计日期：2026-10-17 | 审计对象：`wayzer/ext/soundEffectMenu.kts`（服务器小音效菜单）
> 基线：本机开发服 `server-2026.10.10.B509.jar`（MindustryX 预览构建 B509，基于 Mindustry 7.160.7 official/release），进程 2026-10-17 17:38 启动，6567/6859 运行中。
> 方式：只读探测（控制台 6859 + 上游 `js` 命令）+ 对 B509 jar 的 `javap` 字节码核对 + 与 `参考项目/Mindustry-master`（tags/v160.3）源码交叉核对。
> **未改任何 `.kts` 主脚本、未写临时脚本、未重启、未 commit、未 `sa scan`。** 本报告是唯一新增文件。

## 0. 结论摘要

1. **不能**把服务器小音效切到「音效（Sound）通道」：B509 仍把声音按 **short（16 位）** 序列化（`TypeIO.writeSound → Writes.s`），而 DataAudio 自定义音效 id 从 **100001** 起，客户端读回时被截断成 `-31071`，`Sounds.getSound(-31071)` 返回空音效 `Sounds.none` → 客户端静默、不报错。这是上游刻意限制（源码注释：*only the standard sound constants in Sounds are supported; modded sounds are not*）。
2. **不能**在 Music（音乐）通道实现重叠播放：服务端 `/sfx` 现走 `Call.playMusic(String)`，客户端 `SoundControl` 只有 **单个 `current` 音乐槽**；`playMusic(music, interrupt=true)` 会先 `current.stop()`，`playOnce()` 在 `current != null` 时直接 return —— 两次播放要么互相打断、要么后一次被忽略。
3. **结论排序**：现状（音乐通道、单条播放）是当前协议内唯一可用的自定义音频通道；真正实现「可重叠音效」必须做**客户端侧改动**（X 上游协议扩展 / 自定义客户端），纯服务端脚本无最小可行切换方案。文档中的历史结论正确，本报告补齐 B509 逐字节证据与两条替代思路（logic 客户端本地播放、内容资产 Sound 字段旁路），但均标注为实验性/不推荐。

### 0.1 用户决策（2026-10-17）

**采用方案 A：维持现状**——继续用 `Call.playMusic` 音乐通道，单条播放、不重叠；**明确不做客户端/上游协议扩展（方案 C）**。

- 用户口径（2026-10-17）："确认不行就不搞了，不做客户端"。
- 因此本报告只作为**调查/判断依据**，不转实现任务；若用户未来改变主意，再按 §5 方案 C 评估。
- **B510+ 升级时需按 §8 复跑确认**（short 序列化、100001 偏移、客户端单 `current` 槽都可能随上游版本变化）。

## 1. 现状：脚本怎么注册、怎么播放（代码位置）

文件：`mdtserver/config/scripts/wayzer/ext/soundEffectMenu.kts`（2026-07-21 后未改，本次审计时 590 行）

### 1.1 注册（双注册）

| 位置 | 行为 |
|---|---|
| `soundEffectMenu.kts:189-250` `ensureSoundAssetsFromDisk()` | 扫 `config/assets/sounds/**/*.ogg|mp3`，同一文件**同时**注册为两种 DataAsset |
| `:228` | `SoundAsset`：`path = 相对路径`（如 `omg.ogg`）、`name = 文件名`（如 `omg`） |
| `:236-240` | `MusicAsset`：`path = "sfx-<name>.ogg"`、`name = "sfx-<name>"`（如 `sfx-omg`） |
| `:148-187` `registerAudioAssetFromFile()` | 反射 `DataAsset.readOverride(path, Fi)` + 写 `name` 字段 + 塞进 `state.data.getSounds()/getMusic()`；按 path/name 去重、按 `byteHash` 判重 |
| `:91-96` `reloadAudioAssets()` | 反射调用 `DataManager.reloadAudio()` 让新资产进音频系统 |
| `:529`、`:531-533`、`:544-546` | 脚本加载、`WorldLoadEvent`、`onEnable` 三处兜底注册（防地图加载替换 `state.data`） |

### 1.2 播放（默认走音乐字符串通道）

| 位置 | 行为 |
|---|---|
| `:34` `playViaMusicPacket` 默认 **true** | 「使用 playMusic 字符串通道播放小音效；规避官方 v159 DataAsset sound ID 超出 short 导致 Call.sound 无声的问题」 |
| `:375-386` `sendTo()` | 默认 `Call.playMusic(con, entry.musicName, true)`（`musicName = "dp-" + "sfx-" + name`）；`playViaMusicPacket=false` 时才 `Call.sound(con, entry.sound, volume, pitch, 0f)` |
| `:380` | 正在听点歌的玩家直接跳过本次小音效（避免 `interrupt=true` 掐断长音乐） |
| `:279`、`:98-104` | 用 `dataAssetSoundIdOffset = 100_000` + 自增规则 + 反射 `Sounds.getSound(id)` 反查 Sound 对象（id 从 100001 起） |
| `:30`、`:365-368` | 全局最小播放间隔默认 800ms（`ignoreInterval` 内部调用可绕过） |

### 1.3 运行态实际资产（本节为本次实测新发现）

实测 `Vars.state.data` 里音效资产的路径**并非脚本写入的 `omg.ogg`，而是 `server-assets/omg.ogg`**：

```
dataNotNull=true sounds=1 music=1
| S0 name=omg path=server-assets/omg.ogg hash=HF23TSLDIUF6IUJVAIPEMKEM5DSS535WHW4C7Q2BLML4KRUWQHMA
| M0 name=sfx-omg path=sfx-omg.ogg      hash=HF23TSLDIUF6IUJVAIPEMKEM5DSS535WHW4C7Q2BLML4KRUWQHMA
| players=0
```

原因：B509 上游 `ServerControl.loadDataAssets()` 会自动扫描**数据目录的 `assets/`**（本服 = `mdtserver/config/assets/`，与脚本的 `soundAssetDir()` 是同一目录），把 `sounds/`、`music/` 等目录下的文件注册成 path 带 `server-assets/` 前缀的 DataAsset，并通过 `DataPatchLoadEvent` 注入世界资产回合：

- `参考项目/Mindustry-master/server/src/mindustry/server/ServerControl.java:266-268`（`dataAssetDirectory = dataDirectory.child("assets")`、启动时 `loadDataAssets()`）
- 同文件 `:403-470` `loadDataAssets()`；`:426` `String prefix = "server-assets/"`；`:596-598` 控制台命令 `reloadassets`
- 同文件 `:386-396` `Events.on(DataPatchLoadEvent.class, ...)` 把服务器资产按「同 type + 同 path（忽略大小写）」合并进世界资产列表

因此当前 `SoundAsset` 实际由**上游扫描**产生，脚本 `:228` 的注册在同名（name 命中 + hash 相同）时返回 false、不会重复添加；`MusicAsset`（`sfx-omg.ogg`）仍由脚本注册（脚本只把文件放 `sounds/`，不会进上游 `music/` 扫描范围）。

> 这也意味着脚本 `:189-250` 的「SoundAsset 反射注册」在正常「启动 → 换图」流程里是冗余兜底；`/sfx` 列表里的 `soundId=100001` 依赖 `getSounds()` 顺序与 DataAudioLoader 的 100001 起始规则（当前只有 1 个音效资产，一致）。

### 1.4 历史文档既有结论（本次复核一致）

`docs/official-v159-compat.md:352`、`docs/scripts-maintenance.md:5171`、`docs/v159-network-sync.md:110`、`docs/scripts-maintenance.md:5258`（server assets/`reloadassets` 提及）均记录过 short-id 限制与「小音效借音乐通道」。本次在 B509 上**逐字节复核，结论不变**（见 §3、§4）。

## 2. 上游机制（B509 实测/反编译 + v160.3 源码行号）

### 2.1 Sound 通道：id 从 100001 起，但线上只发 short

| 证据 | 内容 |
|---|---|
| B509 `javap -c mindustry.mod.DataAudioLoader` | `load()` 内 `ldc 100001` → `istore_3`，每个 `SoundAsset` 执行 `Sounds.registerSound(sound, nextSoundId ++)`；即自定义音效 id 固定从 **100001** 起 |
| `参考项目/Mindustry-master/core/src/mindustry/mod/DataAudioLoader.java:13,23,37` | `soundIdOffset = 100_000`；`nextSoundId = soundIdOffset + 1`；`Sounds.registerSound(sound, nextSoundId++)` |
| B509 `javap -c mindustry.io.TypeIO` | `writeSound(Writes,Sound)` = `write.s(Sounds.getSoundId(sound))`；`readSound(Reads)` = `Sounds.getSound(read.s())`（**short** 写入/读出） |
| `参考项目/Mindustry-master/core/src/mindustry/io/TypeIO.java:1223-1229` | 原文注释：*note that only the standard sound constants in Sounds are supported; modded sounds are not.* |
| B509 `javap -c mindustry.gen.SoundCallPacket` | `write()` 调 `TypeIO.writeSound`；`handled()` 调 `TypeIO.readSound`；`handleClient()` 调 `NetClient.sound(sound,volume,pitch,pan)` |
| B509 `javap -c mindustry.gen.Sounds` | `getSound(int)` = `idToSound.get(id, Prov=none)`；`lambda$getSound$0` 返回 `Sounds.none`；`getSoundId(sound)` = `soundToId.get(sound, -1)` |
| B509 `javap -c mindustry.core.NetClient` | `sound(Sound,...)`：`if(sound==null || headless) return;` 然后 `sound.play(clamp(volume,0,8)*Core.audio.sfxVolume, clamp(pitch,0,20), pan, false, false)` |
| `参考项目/MindustryX-main/Arc/arc-core/src/arc/audio/Sound.java:125,136` | `play()`：`!Core.audio.initialized || currentlyLoading` → `-1`；`handle==0 || bus==null` → `-1`（`Sounds.none` 是 handle=0 的空 Sound，故静默不崩） |

**溢出实例（本次实测）**：id `100001` = `0x186A1`，写 short 后为 `0x86A1` = 有符号 `-31071`；客户端读回 `-31071` 去查 `idToSound` 只有 `0..~200`（原版）与 `100001+`（自定义）的键，**永远查不到** → 返回 `Sounds.none` → 静默。

> 补充：headless 服务端 `Core.assets == null`、`Core.audio` 是 `arc.mock.MockAudio` 且 `initialized=false`，`Sounds.load()` 的资产加载回调不会执行（`idToSound` 几乎为空），DataAudioLoader 只能创建 `new Sound()` 占位对象并注册 id。所以服务端**从未真正加载音频数据**，播放能力只在客户端；`Call.sound` 能服务原版声音，是因为原版声音 id（0~204）在客户端 short 范围内可正确映射。

### 2.2 Music 通道：字符串可稳定传输，但客户端单槽不可重叠

| 证据 | 内容 |
|---|---|
| B509 `javap -c mindustry.gen.PlayMusicCallPacket` | 包内字段 `String musicName`、`boolean interrupt`，线上传字符串（本次实测 14 字节往返，名称与 interrupt 均保真） |
| `参考项目/Mindustry-master/core/src/mindustry/core/NetClient.java:265-271` | `playMusic(name, interrupt)` → `SoundControl.findMusic(name)` → `control.sound.playMusic(music, interrupt)` |
| `参考项目/Mindustry-master/core/src/mindustry/audio/SoundControl.java:37` | `protected @Nullable Music current;` —— **唯一当前音乐槽** |
| 同文件 `:194-206` `playMusic()` | `interrupt && current != null` → `current.stop()`、`current = null`；之后 `playOnce(music)`；`current == music` 时置 `silenced=true` |
| 同文件 `:317-330` `playOnce()` | `if(current != null || music == null || !shouldPlay()) return;` → **已有一首在放时新请求直接丢弃** |
| 同文件 `:340` `findMusic()` | 依次尝试 `name`、`name.ogg`、`name.mp3`、`music/name.ogg`、`music/name.mp3`、`music/name`；DataAudioLoader 把自定义音乐注册到 `Core.assets["dp-"+name]`（源码 `DataAudioLoader.java:60`），故脚本传 `dp-sfx-*` 能在第一档命中 |
| `参考项目/Mindustry-master/core/src/mindustry/mod/DataAudioLoader.java:48-63` | MusicAsset 在**非 headless** 时 `Core.assets.addAsset(realName="dp-"+name, Music.class, music)`；headless 服务端跳过（故服务端 `Core.assets=null`，实测一致） |

即：**限制在 Mindustry 的 `SoundControl` 单槽 + 协议设计，不是 SoLoud/arc 的限制**。`arc.audio.Music` 每个实例自带 handle（`Arc/.../Music.java:32,102`），arc 层多实例可并发播放；要让客户端并发播放音乐，只差「绕开 `SoundControl.current` 的触发方式」。

### 2.3 其他可选支路核对（均不可用于「服务端下发自定义音效」）

| 支路 | 结论 | 证据 |
|---|---|---|
| 新增字符串音效包（如 `PlaySoundNameCallPacket`） | **B509 不存在**。音频相关网络包只有 `SoundCallPacket`、`SoundAtCallPacket`（short id）与 `PlayMusicCallPacket`（String） | `jar tf` 全量类名枚举；`mindustry/gen/` 下无其它 Sound 包 |
| 逻辑处理器 `playsound` 走客户端本地播放 | **协议上可行但需客户端模拟逻辑**：`PlaySoundI.run` 无 `headless`/`net.client()` 守卫，`sounds.getSound(id.numi())` 后直接 `sound.play(...)`；客户端 `DataAudioLoader` 在非 headless 时把 `@sfx-dp-*` 写进 `Vars.logicVars`（`DataAudioLoader.java:39-45`） | `参考项目/Mindustry-master/core/src/mindustry/logic/LExecutor.java:2228-2257`（PlaySoundI，无守卫）；`:2259-2278`（PlayMusicI 有 `if(headless) return`）；`DataAudioLoader.java:41`（仅非 headless 写 `@sfx-` 变量）；`BuildingComp.update():2270-2282` 两侧都调 `updateTile()`；`LogicBlock.java:481` `updateTile` 内无网络端守卫。本次实测服务端 `Vars.logicVars.get("@sfx-dp-omg") == null`（headless 不写），即变量只在客户端存在 |
| 内容资产（ContentAsset）自定义 `Effect` | **不支持**：可加载内容类型不含 effect | `参考项目/Mindustry-master/core/src/mindustry/mod/data/ContentAsset.java:13` |
| 数据补丁（PatchAsset）改 Sound 字段 | **不支持**：DataPatcher 无 Sound/Music 类型处理（`grep` 该文件无匹配），补丁只能改内容对象常规字段 | `参考项目/Mindustry-master/core/src/mindustry/mod/DataPatcher.java` |
| 内容资产里的 Sound 字段（Block/UnitType/Weather 等） | **理论可行但重**：客户端 `ContentParser` 对 `Sound.class` 字段会在非 headless 下按 `Core.assets` 名字解析（可命中 `dp-*`）；且 `DataManager.load` 先 `soundLoader.load` 再 `patcher.apply`，顺序允许内容 JSON 引用数据音效 | `参考项目/Mindustry-master/core/src/mindustry/mod/ContentParser.java:310-325`；`DataManager.java:219`（先 audio）与 `:226`（后 patch/content）；`ConstructBlock.java:113-116`（`constructFinish` 在客户端按 `block.placeSound.at(...)` 播放 —— 服务端 `Call.constructFinish` 可触发客户端本地音效）。详见 §6 方案 E |

## 3. 实测记录（2026-10-17，开发服 B509，在线 0 人）

工具：`.agents/sa-cmd.ps1 -Command "<控制台命令>" -WaitSeconds N`（TCP 6859 原始 UTF-8 + `\n`）。探针使用上游控制台自带的 `js`（Mindustry Rhino 控制台，`Vars.mods.scripts.runConsole`），**没有新建任何脚本文件**，因此无需 `sa unload`/删文件；未改 `.kts`，无需跑嵌套注释扫描器。

### 3.1 服务端身份与 headless 音频状态

```
js "ver=" + Packages.mindustry.core.Version.number + "." + ... 
→ ver=7.160.7 type=official modifier=release | headless=true | audio=arc.mock.MockAudio initialized=false | state=mindustry.core.GameState
```

结论：B509 = Mindustry 7.160.7 official/release + MindustryX 预览补丁；dedicated server 无真实音频后端（`MockAudio.initialize()` 空实现，`initialized=false`）。

### 3.2 `state.data` 资产表（脚本 + 上游 server-assets 混合）

```
js var d=Packages.mindustry.Vars.state.data; ... d.getSounds()/getMusic()/getAllAssets() ...
→ dataNotNull=true sounds=1 music=1
  | S0 name=omg path=server-assets/omg.ogg hash=HF23TSLDIUF6IUJVAIPEMKEM5DSS535WHW4C7Q2BLML4KRUWQHMA
  | M0 name=sfx-omg path=sfx-omg.ogg      hash=HF23TSLDIUF6IUJVAIPEMKEM5DSS535WHW4C7Q2BLML4KRUWQHMA
  | players=0
→ map=a all=3
  | patch:patch-a8e0b8b8-8434-42d1-a696-8e2b3ab39dc0.json:name=Patch0   （与本次音效无关的补丁资产）
  | sound:server-assets/omg.ogg:name=omg
  | music:sfx-omg.ogg:name=sfx-omg
  | dataDir=C:\...\mdtserver\config
```

对应「脚本注册日志」：`config/logs/console-20261017-173848.log` 中 17:38:51 `已热注册/刷新 2 个服务器小音效资产。`（脚本加载时 SoundAsset+MusicAsset 初注册），17:38:59/17:47:42/17:52:20 各 `1 个`（推测：换图/世界加载后 `state.data` 被替换、MusicAsset 被脚本补回；SoundAsset 由上游 server-assets 资产替代，按 name+hash 命中不重复添加。未逐步打断点验证）。

### 3.3 核心证据：自定义音效 id 与 wire 截断往返（只读）

```
js var S=...Sounds; var T=...TypeIO; var W=...Writes; var R=...Reads;
   var snd=S.getSound(100001); ... TypeIO.writeSound→2字节→TypeIO.readSound
→ sndNonNull=true sndId=100001 isNone=false
  shortOfId=-31071
  getSound(short)isNone=true
  wireLen=2 wireHex=[-122, -95]        // 0x86A1 = 100001 & 0xFFFF
  backIsNone=true backId=-1            // 客户端读回后解析成 Sounds.none
  stdId=185 stdWire=[0, -71]
  stdBackSame=false                    // 见下方说明（headless 服务端未加载原版声音）
```

- `Sounds.getSoundId(自定义sound)=100001` → `TypeIO.write.s` 只写低 16 位；`TypeIO.read.s` 得 `-31071`；`Sounds.getSound(-31071)` 返回 `Sounds.none`（id=-1）。这正是「服务器日志说播放成功、客户端无声」的机制级解释。
- `stdBackSame=false` 的说明：headless 服务端不执行客户端侧的 `Sounds.load()`（`Core.assets=null`，资产加载回调不运行），服务端 `idToSound` 里没有原版声音对象（本次实测服务端 `getSound(185)` 返回 `none`）；但**客户端**（非 headless）会执行回调，把 0~204 号原版声音注册进 `idToSound`，所以 `Call.sound` 对原版声音一直有效。`SoundCallPacket.handleClient` 在客户端拿到的是客户端侧 `Sounds.getSound(short)`。
- 该实验证明：**不是脚本反射取错 Sound 对象，而是协议层在客户端读回时丢 id。**

### 3.4 Music 包往返 + 客户端侧注册缺口

```
js new PlayMusicCallPacket(); musicName="dp-sfx-omg"; interrupt=true; write→read→handled
→ musicWireLen=14 musicName=dp-sfx-omg interrupt=true
  | Core.assets=null                    // headless 服务端没有 AssetManager
  | serverLogicVar=null                 // @sfx-dp-omg 只在客户端 logicVars
  | logicVarsCount=37
```

字符串音乐名在线上**无损**；服务端只发名字，客户端本地解析 `Core.assets["dp-sfx-omg"]`（DataAudioLoader 非 headless 时注册）。

### 3.5 上游服务器资产目录 + `reloadassets`（B509 已内置）

```
reloadassets
→ Loaded 1 data asset files.
```
（`config/assets/sounds/omg.ogg` 被 `ServerControl.loadDataAssets()` 扫到；该命令只重新扫描/重建 `dataAssets` 表，**不会自动给在线玩家推送音频**，推送仍要世界资产回合/`sendWorldAndAssets`。）

### 3.6 现有 `/sfx` 状态

```
（控制台，无玩家上下文时等价于 /sfx list）
小音效目录：C:\...\mdtserver\config\assets\sounds
服务器小音效列表
来源：Mindustry 159 Data Asset；播放默认走 playMusic 字符串通道规避官方sound ID问题。
1. omg soundId=100001 music=dp-sfx-omg path=omg.ogg
```
`sa listFailed` 为空、`scriptdiag count` = `脚本统计：共160 加载156 启用151 失败0`、`根指令注册数=157`（运行态与脚本状态正常，无音效相关失败）。

### 3.7 无法验证项（环境限制）

- dev server **在线 0 人、headless 无音频输出**：无法验证客户端是否真的无声/是否可重叠、`musicvol` 音量影响、卡顿等**听感/客户端行为**；以上客户端结论全部来自 B509 字节码 + v160.3 源码的机制推断。
- 无法验证真实客户端对 `server-assets/*` 资产的接收/注册时序（需玩家进服；历史上进服资产同步有已知重载副作用，见 `docs/official-v159-compat.md` §9/§11）。

## 4. 判定

| 问题 | 判定 | 依据 |
|---|---|---|
| 能把小音效改成 `SoundAsset` 并在服务端用 `Call.sound` 播放吗？ | **不能**（B509/160.7 当前协议） | §2.1、§3.3：short 截断 + 100001 偏移；上游注释明确 mod 音频不支持 |
| 音效通道能重叠播放吗？ | **不能**（因为根本播不出来自定义音频） | 同上；原版声音可重叠（`Sound` 实例可并发），但不是自定义音频 |
| 音乐通道能重叠播放吗？ | **不能** | §2.2、`SoundControl` 单 `current` 槽 + `playOnce` 早退 |
| 现状是否可以继续用？ | **可以**，且是当前协议内唯一可行方案 | 字符串音乐名无损、客户端注册 `dp-*`；代价是音乐音量、单条、可能打断点歌（已有跳过逻辑） |
| 要真正实现「可重叠自定义音效」 | **需要客户端侧改动**（上游协议扩展或自定义客户端） | §6 方案 C；与 `docs/v159-network-sync.md:125`「真正的局内热重载只有客户端补丁一条路」同一结论 |

## 5. 最小切换方案（结论：脚本层无可行方案）

**结论先说**：不存在「只改 `soundEffectMenu.kts` 就能切 Sound 通道」的最小改动。任何试图 `Call.sound(自定义 Sound)` 的改法都会回到 short 截断问题，客户端静默。任务要求「先不要改主脚本」——本报告也未做任何改动，以下仅为方案与风险分析。

### 方案 A（推荐，零代码）：维持音乐通道现状

继续 `Call.playMusic(con, "dp-sfx-*", true)`。已知限制（与现状一致）：受客户端**音乐音量**影响、一次只能一条、会打断正在听的点歌（现有 `:380` 已对点歌玩家跳过）、同一音效重复播放会重启当前槽。若只是"播放提示音"，这在 B509 内是正确解。

> **用户 2026-10-17 已拍板采用本方案；方案 C（客户端/上游协议扩展）明确不做**，见 §0.1。B510+ 升级时需按 §8 复跑确认。

### 方案 B（可选重构，仍是音乐通道，不解决重叠）：改用上游 `config/assets/*` + `reloadassets`

- 把每个小音效文件同时放入 `config/assets/sounds/`（SoundAsset）与 `config/assets/music/sfx-<name>.ogg`（MusicAsset）；上游启动/`reloadassets` 自动注册后者 `name=sfx-<name>` → 客户端 `Core.assets["dp-sfx-<name>"]`，播放名不变（`dp-sfx-*`）。
- 可删掉脚本里约 100 行反射注册代码与 `DataManager.reloadAudio()` 调用，热刷新改为：控制台 `reloadassets`（或者保留脚本的 `worldResyncCoordinator` 手动同步链路）。
- **收益**：少一段依赖反射的脆弱代码；资产仍由原版协议同步。**风险**：`reloadassets` 只是重新扫描，对在线玩家的资产推送仍需完整世界/资产回合（`docs/v159-network-sync.md`）；重复文件占两份内存；`server-assets/` 与脚本旧路径并存期需按 name 去重（当前脚本已按 name 匹配，安全）。**仍然不能重叠。**

### 方案 C（唯一根治，需客户端/上游决策）

1. **上游协议扩展（推荐给 X 上游提 PR）**：给音频增加「按名字播放」包，例如 `PlaySoundNameCallPacket(String name, float volume, float pitch, float pan)`；服务端把 `dp-sfx-<name>` 发给客户端；客户端 `Core.assets.getOrNull("dp-"+name, Sound.class)` 命中 DataAudioLoader 注册的**真实 Sound 对象**后直接 `sound.play(...)`（`arc` 层可并发、走 sfx 音量、天然重叠），旧客户端可回退现有 `SoundCallPacket`（原版声音 short 路径不受影响）。改动面：新增 1 个 packet 类 + `Call`/`NetClient` 各一个方法（约 40~60 行，需双端同版本）。
2. **自定义客户端 mod**：客户端监听自定义包，按 `dp-*` 名字取 `Core.assets` 的 Sound 并 `play`。`server.properties` 已 `allowCustomClients=true`，但需要所有玩家安装同款客户端 mod 并解决分发/版本一致性——**属产品决策**，不是脚本能完成的。
3. 风险：`TypeIO.writeSound` 若改 int 会破坏与官方原版客户端的协议兼容（所有客户端必须同步升级）；新增包是更小的兼容面。

### 方案 D（实验性，不建议上线）：world processor + 客户端本地 `playsound`

服务端通过 `Call`/配置更新一个地图内 logic 处理器代码，让**客户端本地**执行 `playsound @sfx-dp-<name>`（或 `playmusic`）。依据：`PlaySoundI` 无 headless/client 守卫（§2.3），客户端 `logicVars` 有 `@sfx-dp-*`。风险/未验证：需要当前地图存在逻辑处理器；服务端也跑同一段逻辑，`@sfx-*` 变量在 headless 服务端不存在（本次实测 `null`，LAssembler 可能报未知变量）；世界处理器逻辑是否在所有客户端逐帧执行需客户端实测；每次播放要配置处理器代码，频率/副作用不可控。**只适合实验验证，不建议作为功能方案。**

### 方案 E（理论旁路，不推荐）：给可加载内容加 Sound 字段

新建一个 1x1 隐藏 `Block`（ContentAsset 可加载）并设置 `placeSound: "dp-omg"`，服务端对该方块调 `Call.constructFinish` → 客户端 `ConstructBlock.constructFinish` 在 `!headless` 且可见时播放 `block.placeSound.at(...)`（`参考项目/Mindustry-master/core/src/mindustry/world/blocks/ConstructBlock.java:113-116`），得到**客户端本地、可重叠、走 sfx 音量、可定位**的播放。依据顺序：`DataManager.load` 先注册音频再解析内容（`DataManager.java:219/226`），`ContentParser` 会在客户端用 `Core.assets` 名字解析 Sound（`ContentParser.java:310-325`）。

但风险明显：需要新增 content JSON+贴图（客户端会看到/渲染临时方块）、要维护与服务器世界的同步或接受短暂 desync、无真实客户端的回归验证、对现有 CP/内容卸载链路是额外负担。**结论：理论可行、工程量与风险不成比例，不推荐。**

> 关于「多 music 交叉/队列播放」：队列只能串行（`SoundControl.current` 单槽，`interrupt=true` 硬切；`playOnce` 在忙时直接丢弃），且服务端 headless **无法解码音频时长**（无音频数据，只有占位 Sound），无法实现"等前一条播完再播下一条"的可靠节拍；交叉淡入淡出只有自动 ambient music 的 `fade`，协议无法控制。因此队列/交叉都不是重叠的替代实现。

## 6. 风险与未验证项

**已验证（服务端可观测）**：B509 版本/headless；`state.data` 音效/音乐资产真实内容与来源（脚本 + server-assets）；自定义音效 id=100001 与 wire 2 字节截断（`-31071` → `Sounds.none`）；音乐包字符串往返无损；headless `Core.assets=null` / `Core.audio=MockAudio`；上游 `reloadassets` 可用；现有 `/sfx` 与脚本状态正常（`scriptdiag count`、`sa listFailed` 空）。

**未验证**：
1. 真实客户端行为（无声、音乐音量、能否重叠、`sendWorldAndAssets` 后的资产注册时序）——无客户端、dev 服 0 人，全部结论为字节码/源码推断。
2. v160.3 → B509（160.7）之间上游音频/资产同步路径是否有其他改动未逐条 diff（本次只核对直接相关类；`TypeIO`/`DataAudioLoader`/`SoundControl`/`DataManager`/`ServerControl` 字节码与 160.3 源码语义一致）。
3. `server-assets/` 资产 + 脚本旧路径在「客户端 DataAudioLoader 重名去重」时的行为：headless 服务端因 `registered` 集合不填充（`DataAudioLoader.java:39` 在 `:44 registered.add` 之前 continue）不做去重，客户端会去重——若未来同一音效同时以 `omg.ogg` 与 `server-assets/omg.ogg` 两个 path 存在，服务端 id 可能是 100001/100002 而客户端只有 100001，**会造成 ID 错位**（当前实测 `getSounds()=1`，暂无此问题；此风险只影响"将来若修好 sound 通道"）。
4. 方案 D（logic 本地播放）、方案 E（内容 Sound 字段）均未实测，且涉及客户端执行/世界副作用。
5. `reloadassets` 后在线玩家的资产推送未验证（未发世界资产回合）。

## 7. 受影响文档清单（本次只建本文件，未改下列文档）

> 下列内容由本审计结论驱动；建议由文档巡检/维护 agent 统一更新，避免与本次写冲突。行号为 2026-10-17 当前版本。

| 文档 | 应更新段落 | 建议内容 |
|---|---|---|
| `docs/official-v159-compat.md` | §11「基于 v159 Data Asset 的音效/点歌脚本」（约 :341-368）；§10「启动目录与服务器资产目录」（约 :315-339） | ①在 §11 补充本次 B509 逐字节证据（TypeIO short、100001 偏移、wire 实测）与「不能切 Sound 通道/不能重叠」结论，链接本审计；②说明 `state.data` 中 SoundAsset 实际来自上游 `ServerControl.loadDataAssets()`（`server-assets/` 前缀），脚本的 SoundAsset 反射注册只是兜底；③§10 补 `reloadassets` 控制台命令与「config/assets 自动扫描」行为（ServerControl:403/426/386） |
| `docs/scripts-maintenance.md` | 新批次（在「2026-07-07：服务器小音效菜单」:5270 之后追加；该批次本身 :5271-5273 也可加一行「后续审计」指针） | 追加「2026-10-17：小音效 Sound 通道审计」批次：结论（不可切 Sound、不可重叠）、证据摘要（§3.3 wire 截断实测）、未改代码、未验证项、链接本审计；并注明「若未来做客户端补丁/上游 PR 再补实现批次」 |
| `docs/current-issues.md` | §3「局内热重载替代改进」（约 :22-31）；§B「新增待办」（约 :143-155） | ①§3 的 ②「小音效窗口期注册」项下补注：「音效通道不可用（short id 截断），重叠播放需客户端补丁/上游协议，见 sound-effect-channel-audit.md」；②§B 新增一条「小音效重叠播放：需用户决策是否走客户端方案（方案 C），纯服务端不可行」 |
| `docs/v159-network-sync.md` | 「资产的传输形态」（约 :93-103）、「局内热重载可行性核对」（约 :71-135） | ①明确该结论已在 **B509/v160.7** 复核（原文基于 160.3）；②补充「音效通道不可用」的字节码级证据（TypeIO short + 100001 偏移 + 2 字节 wire 实测 + `Sounds.none` 兜底），作为「不变量」；③仍保留「无音频推送通道、跨版本必须重载世界」的口径 |
| `docs/v159-data-assets-hot-reload.md` | 「验证记录（2026-10-16 / 10-17…）」之后新增小节 | 新增「B509 服务器资产目录自动加载与 `reloadassets`」：`ServerControl.loadDataAssets()` 扫描 `config/assets/{sounds,music,...}` 并以 `server-assets/` 前缀经 `DataPatchLoadEvent` 注入资产回合；`reloadassets` 只重扫不推送；音频资产虽能注册/同步，但 sound 通道播放仍受 short id 限制（链接本审计） |
| `docs/project-memory.md` | 「最近状态/里程碑」区（按现有格式追加一条） | 一条 2026-10-17 记录：小音效通道审计完成，结论=不能切 Sound 通道、重叠需客户端侧改动、现状音乐通道维持；证据链见 `sound-effect-channel-audit.md` |
| `docs/skill-system.md` 等其它提及「Call.sound 不能播自定义音频」的文档 | 约 :186 的结论行 | 无需改动结论；如需统一口径可加一句「B509 逐字节复核见 sound-effect-channel-audit.md」 |
| `docs/help-menu.md` / `coreMindustry/menu.kts` 菜单文案 | 小音效入口说明 | **当前无需改**（行为不变）。仅当未来采用方案 B/C 改变播放通道/音量文案时同步 |

## 8. 复现与清理

- 探测手段：控制台 `js`（上游自带，无需落盘脚本）+ 只读反射；未新增/修改 `config/scripts/**` 任何文件，**无需 `sa unload`/删文件/重扫**；未重启、未 commit。
- 关键命令（可复跑，均为只读/幂等）：
  - `pwsh -File .agents\sa-cmd.ps1 -Command "js <探测表达式>" -WaitSeconds 10`
  - `pwsh -File .agents\sa-cmd.ps1 -Command "reloadassets" -WaitSeconds 8`
  - `pwsh -File .agents\sa-cmd.ps1 -Command "scriptdiag count" -WaitSeconds 6`
- 反编译核对：`javap -p[: -c] -classpath mdtdo/mdtserver/server-2026.10.10.B509.jar mindustry.io.TypeIO mindustry.mod.DataAudioLoader mindustry.gen.Sounds mindustry.audio.SoundControl mindustry.core.NetClient mindustry.gen.PlayMusicCallPacket mindustry.gen.SoundCallPacket mindustry.server.ServerControl mindustry.mod.DataManager`
- 临时产物：分析用 javap 输出写在 `%TEMP%`，已清理；仓库与运行数据无改动。

## 附录 A：B509 关键字节码摘录

```
// mindustry.io.TypeIO
public static void writeSound(arc.util.io.Writes, arc.audio.Sound);
  invokestatic mindustry/gen/Sounds.getSoundId:(Larc/audio/Sound;)I
  invokevirtual arc/util/io/Writes.s:(I)V          // ← short
public static arc.audio.Sound readSound(arc.util.io.Reads);
  invokevirtual arc/util/io/Reads.s:()S             // ← short
  invokestatic mindustry/gen/Sounds.getSound:(I)Larc/audio/Sound;

// mindustry.mod.DataAudioLoader.load(Seq<SoundAsset>, Seq<MusicAsset>)
  ldc 100001 → nextSoundId
  ... new Sound()（headless）→ Sounds.registerSound(sound, nextSoundId++);
  if(Vars.headless || !Core.audio.initialized() || sound.file == null) continue;  // 跳过 Core.assets/逻辑变量
  ... Music: Music.create(file)（非 headless）；Core.assets.addAsset(prefix+name, Music.class, music)

// mindustry.gen.Sounds
public static Sound getSound(int) → idToSound.get(id, Prov=→ Sounds.none)
public static int getSoundId(Sound) → soundToId.get(sound, -1)

// mindustry.audio.SoundControl
protected Music current;                             // 单槽
public void playMusic(Music m, boolean interrupt) {
  if(interrupt && current != null){ current.stop(); current = null; }
  playOnce(m); if(current == m) silenced = true;
}
protected void playOnce(Music m){ if(current != null || m == null || !shouldPlay()) return; ... current = m; m.play(); }

// mindustry.core.NetClient
public static void sound(Sound s, float v, float p, float pan){ if(s==null||headless) return; s.play(clamp(v,0,8)*Core.audio.sfxVolume, clamp(p,0,20), pan,false,false); }
```

## 附录 B：相关上游源码位置（参考项目 `Mindustry-master` = tags/v160.3；与 B509 字节码语义一致）

- `core/src/mindustry/io/TypeIO.java:1223-1229`（short sound、mod 音频不支持注释）
- `core/src/mindustry/mod/DataAudioLoader.java:13-14,23-46,48-63`（100000 偏移、`dp-` 前缀、headless 分支、Sound/Music 注册）
- `core/src/mindustry/audio/SoundControl.java:37,194-206,317-330,340-...`（单 current、playMusic/playOnce/findMusic）
- `core/src/mindustry/core/NetClient.java:265-271,274-...`（playMusic 字符串通道、sound 本地播放）
- `core/src/mindustry/mod/DataManager.java:203-208,210-229`（reloadAudio、load 顺序：audio → bundle/image → patch/content）
- `server/src/mindustry/server/ServerControl.java:266-268,386-396,403-470,596-598`（config/assets 自动扫描、`server-assets/` 前缀、DataPatchLoadEvent 注入、`reloadassets`）
- `core/src/mindustry/logic/LExecutor.java:2228-2278`（PlaySoundI 无守卫 / PlayMusicI headless 守卫）
- `core/src/mindustry/mod/ContentParser.java:310-325`（内容 JSON 的 Sound 字段按 Core.assets 名字解析，headless 返回 none）
- `core/src/mindustry/world/blocks/ConstructBlock.java:58-121`（construct/deconstructFinish 在客户端播 placeSound/breakSound，服务端 headless 跳过）
- `参考项目/MindustryX-main/Arc/arc-core/src/arc/audio/Sound.java:124-136`、`Music.java:32,102`、`arc/mock/MockAudio.java`（headless 空音频后端、handle=0 静默）
