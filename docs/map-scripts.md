# 地图脚本维护清单

Mindustry 159/B477 后只继续维护少量明确需要的地图玩法脚本。旧地图脚本数量过多，且大量依赖旧版内容补丁、单位内部字段或高频循环；继续全量保留会增加升级排错和运行时崩溃风险。

## 当前保留脚本

| 脚本 | 用途 | 加载方式 |
|---|---|---|
| `mapScript/14668.kts` | Lord of War | 地图ID、`/loadmapscript 14668`、管理员技能 |
| `mapScript/15450.kts` | TankWars | 地图ID、`/loadmapscript 15450` |
| `mapScript/tags/hybrid.kts` | 地图特色杂交 | 地图标签 `[@hybrid]`、`/loadmapscript tags/hybrid` |
| `mapScript/tags/flood.kts` | 洪水模式兼容版 | `[@flood]` / `[@floodV2]`、`/loadmapscript tags/flood` |
| `mapScript/tags/pure.kts` | 地图纯净模式 | 地图简介标签 `[@pure]`、`/loadmapscript tags/pure` |

同时保留地图脚本框架文件：

- `mapScript/module.kts`
- `mapScript/lib/ContentExt.kt`
- `mapScript/lib/ScriptMapGenerator.kt`
- `mapScript/lib/TagSupport.kt`
- `mapScript/lib/util.kt`

管理员入口：

- `/loadmapscript <ID或路径>`
- `/unloadmapscript <ID或路径>`
- `/mapscripts`
- `/mapcmd <地图脚本指令>`

如果地图引用了已移除的脚本，地图仍按普通地图运行，后台会提示服务器不存在对应地图脚本；不会回退加载其他旧脚本。

## 159/B477 兼容修复

### 14668 / Lord of War

- 旧补丁把 `quell.weapons.0.bullet.spawnUnit` 写成内容名称。159 DataPatcher 会将其视为尝试实例化新内容并警告 `New content must not be instantiated`。现改为在 `quell` 内容内部使用完整字段路径修改既有导弹单位。
- 移除每 100ms 修改彩色模式名、每 2 秒全服发送一次 `Call.setRules` 的循环。该循环会长期制造无意义规则同步和上行开销；模式名改为稳定的 `LordOfWar`。
- 上帝权杖没有有效敌方玩家时不再对空列表调用 `random()`。

### 15450 / TankWars

- 主循环中的 `yield()` 改为短 `delay`，避免 `Dispatchers.game` 持续打印调度警告，并改善脚本卸载时的协程取消。
- 保留地图脚本的 `shop` 别名；全局同名指令冲突由指令框架隔离，玩家使用 `/mapcmd shop` 打开灵魂升级菜单。

### 地图特色杂交

- 基因补丁变化后保持自动同步，以确保客户端跟进新的武器、能力和内容补丁。
- 159 下优先调用 `sendWorldAndAssets`，旧端不支持时回退到 `sendWorldData`；连续基因变化会合并防抖，并按350ms间隔逐人同步。

### 洪水模式

- 主循环和状态显示增加异常隔离，单次建筑/单位异常不会终止整个洪水玩法循环。
- 洪水单次计算超过默认120ms时限频记录规模与耗时，便于定位大地图洪水扩散造成的主线程压力。
- 继续保留卸载时清理洪水视觉方块和运行态缓存的逻辑。
- `/skill floodoff` 与 `/unloadmapscript tags/flood` 共用同一停用链路：先显式创建 ScriptAgent 事务并确认脚本最终已停用，再删除 `@flood` / `@floodV2`；若停用失败则保留或回滚标签，避免出现“脚本仍运行但标签已丢失”的半更新状态。

### 纯净模式标签

- 地图简介/规则标签中出现 `[@pure]` 时，`mapScript/tags/pure.kts` 随本局地图生命周期自动启用。
- 启用后复用 `wayzer/map/funRuleModes.kts` 的纯净模式快照，禁用普通技能和3级技能；卸载/换图时只恢复本标签脚本实际开启的状态，不误删地图原有 `@noSkills` 等限制。
- 换到不带 `[@pure]` 的地图后不会再加载该标签脚本，因此自动回到普通技能规则。
- ScriptAgent 3.4 下手动关闭地图脚本已改为显式停用事务，避免 `/unloadmapscript tags/pure` 报 `No transaction available`。

### 手动启停链路

- `/loadmapscript` 启用前会扫描脚本目录，便于运维复制新脚本后立即加载；`/unloadmapscript` 停用已注册脚本时不再扫描磁盘，减少云服磁盘抖动带来的额外卡顿。
- 启用/停用完成后会检查 `ScriptInfo.enabled` 最终状态，并把底层异常或失败原因返回给管理指令/管理员技能，而不是仅提示“查看日志”。
- 停用目标地图脚本时仍会保护原本已启用且不依赖目标的公共脚本；若 ScriptAgent 递归停用造成连带影响，会尝试恢复这些公共脚本。
- 2026-07-30 本地命令 Socket 已实测 `tags/flood` 与 `14668` 均可先加载再停用；未再出现 `No transaction available`。Lord 加载时仍可能打印既有 ContentPatcher 兼容警告，因此它仍属于整图专用脚本，不保证任意地图热加载均安全。

## 已移除脚本

2026-07-16 按维护范围收缩要求移除了其余57个旧地图脚本及其专用辅助文件，包括旧生成地图、旧塔防、旧PVP、旧标签和 `15716`/`hexed` 等专用支持文件。

这些文件仍可从 Git 历史恢复，但在重新引入前必须：

1. 单独完成159/B477内容补丁兼容；
2. 检查所有长期循环和全服同步；
3. 验证脚本启用、运行和卸载；
4. 更新本文档后再进入生产目录。

## 测试方式

重点脚本不能只看启动编译结果，应在测试服逐个执行：

```text
loadmapscript 15450
unloadmapscript 15450
loadmapscript 14668
unloadmapscript 14668
loadmapscript tags/hybrid
unloadmapscript tags/hybrid
loadmapscript tags/flood
unloadmapscript tags/flood
loadmapscript tags/pure
unloadmapscript tags/pure
```

重点观察：

- `ContentPatcher` 警告；
- `yield()` / `Dispatchers.game` 警告；
- 脚本停止超时；
- 玩家无核心机、重复世界同步；
- 洪水单次计算耗时警告。
