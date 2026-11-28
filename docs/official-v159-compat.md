# 服务端版本兼容说明（v160.x / MindustryX B509）

> **当前运行基线**：Mindustry **v160.7** / MindustryX `prerelease-2026.10.10.B509`（`server-2026.10.10.B509.jar`）。  
> **版本维护原则**：服务端常态化跟进上游版本，由维护者决定目标构建；ScriptAgent 3.4.0 以本项目自行维护为主（当前使用竞态修复构建 `ScriptAgent c2823c1`），仅在上游大改版时评估升级。

---

## 1. 兼容边界与现状事实

- **操作系统限制**：**仅支持 Windows**（`wayzer/reGrief/trafficMonitor.kts` 依赖 Windows `netstat -e` 读取网卡累计出口字节）。
- **与 MindustryX 的真实依赖关系**：
  - `trafficMonitor.kts` **已完全移除** `mindustryX.events.SendPacketEvent` 依赖；
  - 全仓脚本中仅 `wayzer/reGrief/limitLogicPacket.kts` 一处以可选反射 `Class.forName("mindustryX.events.SendPacketEvent")` 挂载监听（缺失时返回 `null` 安全降级）；
  - 生产与本地开发仍以 MindustryX B509 作为统一运行基线，不自建自定义补丁 JAR（原早期的自定义构建方案已废弃）。
- **升级核对原则**：小版本保持兼容；大版本破坏性更新尽量在兼容层隔离；**直接修改 Data Patch 字段结构的上游破坏性变更不做额外兼容层**。

---

## 2. 现存跨版本兼容点（代码入口）

| 兼容点 | 涉及脚本 | 原因与实现方式 |
|---|---|---|
| 单位状态列表 `statuses()` | `wayzer/cmds/effect.kts`、`user/ext/skills.kts`、`skillsHybrid.kts`、`mapScript/tags/hybrid.kts` | 通过反射缓存父类 protected 字段 `statuses`（`statusEntries(...)`）替代缺失的公开 getter |
| `SaveIO.write` 签名变化 | `wayzer/map/autoSave.kts`、`wayzer/cmds/voteSave.kts` | `writeSaveCompat` 优先反射旧签名，缺失时使用 `mindustry.io.SaveOptions.extraTags` |
| v160 移除 `Groups.fire` / `Groups.puddle` | `wayzer/user/ext/skills.kts`、`map/serverPressureActions.kts`、`coreMindustry/contentsTweaker.kts` | 灭火改用按 tile 的 `Fires` API，性能保护仅用 O(1) `state.rules.fire = false`；**热路径严禁 `Groups.all` 全实体遍历**（仅 `contentsTweaker.kts` DP 卸载冷路径保留） |
| 逻辑包限制（MindustryX 扩展） | `wayzer/reGrief/limitLogicPacket.kts` | 可选反射 `mindustryX.events.SendPacketEvent`，非 MindustryX 端自动降级不阻断加载 |
| Data Assets 动态加载与卸载清理 | `coreMindustry/contentsTweaker.kts`、`wayzer/map/externalCpHotReload.kts` | 卸载前强制解除附身与清理旧内容引用、同步调整 `ItemModule`/`LiquidModule` 容量与刷新 `Unloader.allItems` 静态缓存（详见 [v159-data-assets-hot-reload.md](v159-data-assets-hot-reload.md)） |

---

## 3. 音效通道协议限制（升级复检要点）

在 Mindustry v160.7 / B509 上，自定义小音效**不能切换到 Sound 通道**，也**不支持重叠播放**：

- `TypeIO.writeSound` 按 `short` 序列化声音 ID，动态注册的 `SoundAsset` ID 从 `100001` 起，客户端读回时截断为 `-31071` 并回退 `Sounds.none`（静默无声）；
- `Call.playMusic(String)` 通过字符串路由到 `Core.assets["dp-"+name]` 可以正常播放，但客户端 `SoundControl` 仅维护单个 `current` 槽位，无法重叠播放；
- **当前决策**：维持 `Call.playMusic(String)` 现状，不做客户端/协议扩展。

未来升级到更高主版本时，若需确认上游是否放开了自定义音效通道，只需用 `javap` 复核 `mindustry.io.TypeIO` 的 `writeSound` 是否仍使用 `Writes.s` 及 `SoundControl` 是否仍为单槽位即可。
