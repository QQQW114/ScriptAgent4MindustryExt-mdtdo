# 新玩家批量框选拆除监控（idleBreakGuard）

> 脚本：`scripts/wayzer/ext/idleBreakGuard.kts`
> 部署路径：`config/scripts/wayzer/ext/idleBreakGuard.kts`
> 版本：**V0.1.5**（当前版本维护者：八色 和他的 dsh）
> 更新日志：`docs/idleBreakGuard-changelog.md`
> 首次提交：PR #1（`feat/idlebreakguard`）
> 状态：本地开发服测试通过；真实客户端时序/观感待验证

---

## 1. 作用

对新玩家进服后的**批量框选拆除**做监控：

- 客户端框选删除时，会把这批待拆方块作为 `BuildPlan(breaking=true)` 放进
  `ClientSnapshotCallPacket.plans`；
- 插件在服务端包层读取这批坐标，统计其中**真实建筑物**的数量；
- 游客超过 50 个、1 级超过 100 个、2 级超过 150 个，且确实开始拆除时，
  调用 `voteOb.forceObPlayer` 强制观战 600 秒（理由“疑似乱拆”）。
- **V0.1.5 新增**：
  - 建筑物豁免（§2.2）：21 种墙/传送带/管道/钻头/逻辑块等不计入批量数量；
  - 接收台黑名单（§2.3）：游客/1 级在窗口内拆除接收台立即处罚；批量选中含接收台且未到阈值时，
    **拆到接收台这一格**才处罚（按用户决定：只处罚，不做还原）。

只作用于服务端，不需要客户端 mod。

---

## 2. 监控窗口与阈值

| 信任等级 | 监控窗口 | 批量选中真实建筑上限（超过触发） |
|---|---|---|
| 游客（0） | 120 秒 | 50 |
| 1 级 | 60 秒 | 100 |
| 2 级 | 60 秒 | 150 |
| 3 级及以上 | 不监控 | — |

- 只看信任等级，资历等级不参与；
- 窗口从进服时开始，进服后 1.5 秒 / 10 秒会按当前等级刷新截止时间；
- 窗口内等级变化会按新等级重算截止时间，仍以进服时间为起点；
- 窗口过期后，即使框选数量很大也不会触发。

---

## 2.1 每日首次进服（2026-11-06）

- **游客（未登录）**：每次进服都按游客规则监控，不受每日首次限制。
- **非游客（已登录）**：每天**首次进服**才建立监控窗口；同一天再次进服/重连不再监控。
- 3 级及以上仍然豁免；豁免判定在写“每日首次”之前，不会消耗当天 key。
- 是否“首次”按账号主体 UID 记录，持久化在 MdtStorage 设置键：
  `idleBreakGuard.firstJoin.<uid>`，值为当前周期日期（如 `2026-11-06`）。
  每个账号只保留一个 key，不会按天无限增长；旧版按天 key 不再使用。
- **每日周期在凌晨 4:00 切换**（不是按最后一次进服计时 24 小时，也不是 00:00 切换）：
  - 00:00～03:59 仍算前一天；
  - 04:00 起进入新的一天，首次限制刷新；
  - 可通过配置 `dailyResetHour` 调整（默认 `4`，0～23）。
- 游客在本次会话中登录账号时，会立即按该账号补做“每日首次”判定；如果该账号今天已经进过，则取消本次游客监控。
- 断线 30 秒内重连仍按原监控窗口接续，不算新的一次进服；超过 30 秒后重连则按每日首次规则重新判断。

## 2.2 建筑物豁免（2026-11-07，V0.1.5）

- 新增配置 `buildingWhitelist`：名单内的方块**永不计入**批量拆除数量（与 `conveyorWhitelist` 取并集）。
- 默认豁免 21 种廉价/可快速重建的基础设施（按 `block.name`）：

| 中文名 | block.name | 中文名 | block.name |
|---|---|---|---|
| 合金传送带 | `surge-conveyor` | 物品管道 | `duct` |
| 铜墙 | `copper-wall` | 大型铜墙 | `copper-wall-large` |
| 废墙 | `scrap-wall` | 大型废墙 | `scrap-wall-large` |
| 巨型废墙 | `scrap-wall-huge` | 超巨型废墙 | `scrap-wall-gigantic` |
| 铍墙 | `beryllium-wall` | 大型铍墙 | `beryllium-wall-large` |
| 钨墙 | `tungsten-wall` | 大型钨墙 | `tungsten-wall-large` |
| 双管 | `duo` | 机械钻头 | `mechanical-drill` |
| 气动钻头 | `pneumatic-drill` | 微型处理器 | `micro-processor` |
| 逻辑显示屏 | `logic-display` | 大型逻辑显示屏 | `large-logic-display` |
| 逻辑显示单元 | `tile-logic-display` | 画布 | `canvas` |
| 大型画布 | `large-canvas` | — | — |

- 语义：这些方块**单个也不计数**，而不是“只有全部选中它们时才豁免”；混选时只统计非豁免方块。
- 玩家正常重建自家墙/传送带/逻辑设施不会再被误判。

## 2.3 接收台黑名单（2026-11-07，V0.1.5）

“接收台”= `landing-pad`（地图开局自带、服务器科技里不可制造，负责接收/产出固定物品）。
新增配置 `receiverBlocks`（默认 `landing-pad`）。只对**游客与 1 级**、且在**监控窗口期内**生效：

| 场景 | 判定 | 处罚理由 | 处置 |
|---|---|---|---|
| A. 直接开始拆接收台本身（不在批量选中里，或选中里只有它） | `BlockBuildBeginEvent(breaking)` 且 `ConstructBuild.previous` 是接收台 | `试图拆除接收台` | 立即处罚 |
| B. 批量选中里含接收台、未到阈值，**拆到接收台这一格时** | 同上，且接收台在本次选中集合里、`1 < selected.size <= 阈值` | `疑似将要拆除接收台` | 立即处罚 |
| C. 批量选中超阈值且含接收台，开始拆选中建筑 | `selected.size > 阈值` | `疑似乱拆（批量选中拆除 N 个建筑物）`（与正常处罚一致） | 立即处罚 |

- **B 的时机（用户 2026-11-07 修订）**：不是“一开始拆选中的建筑就处罚”，而是**拆到接收台这一格时**才处罚；
  没拆到接收台就不触发 B。
- 处罚 = `voteOb.forceObPlayer` 强制观战（时长见 `observerSeconds`），同一监控窗口只处罚一次。
- **不实现“还原接收台”（用户 2026-11-07 决定）**：A/B 只处罚，不恢复被拆的接收台；
  接收台拆掉后其接收配置与已接收物品不会由本插件恢复，需要时由管理员/回档处理。
  （开发过程中曾验证过还原方案：`tile.setNet` + `LandingPadBuild.config` / 物品 / 液体 / 血量复制，
  按用户决定已移除，见 `scripts-maintenance.md` 第三十七批。）

## 3. 判定流程

### 3.1 包层收集

```kotlin
listenPacket2Server<ClientSnapshotCallPacket> { con, packet ->
    val plans = packet.plans
    // 只收集 breaking=true 的 (x, y)
}
```

- 包回调在网络线程，只做坐标收集，放进 `ConcurrentLinkedQueue`；
- 游戏线程每 100 毫秒 `drainBatches()` 一次，避免在网络线程碰游戏状态；
- 同一玩家多次快照的坐标会合并去重，防止客户端重发同一批 plans 造成重复计数。

### 3.2 真实建筑物过滤

`handleBatch` 逐坐标检查：

- `Team.derelict` 的地图原有物：跳过；
- 名称含 `boulder` 的巨石：跳过；
- 配置 `conveyorWhitelist` 里的传送带：跳过；
- 配置 `buildingWhitelist` 里的建筑物（§2.2）：跳过；
- 没有 `tile.build` 的格子：跳过；
- 建造中的 `ConstructBuild`（`wasConstructing=true`）：跳过；
- 拆除中的 `ConstructBuild`（`wasConstructing=false`）：用 `previous` 还原原方块，
  按真实建筑计入；
- 其余有 `build` 的方块：按真实建筑计入。

### 3.3 开始拆除的判定

`monitor.started` 由两处置位：

1. 包层处理时，看到选中坐标已经是**拆除态** `ConstructBuild`；
2. `BlockBuildBeginEvent` 中，玩家开始拆的 tile 在 `monitor.selected` 里。

> V0.1.5-fix：多格建筑（如 3×3 接收台）的事件 tile 可能不是锚点；
> `BlockBuildBeginEvent` 现在统一使用 `event.tile.build?.tile?.pos()` 换算成建筑锚点，
> 再与 `selected` / `receiverTiles` 匹配，避免拆角落时漏判 B/C。

只有 `started=true` 且 `selected.size > 阈值` 才会触发，避免“只框选、不拆”也被强制观战。

### 3.4 触发

调用：

```kotlin
with(voteOb) { forceObPlayer(player, reason, null, null, "system") }
```

- 强制观战 600 秒；
- 全服广播由 `voteOb` 输出；
- 玩家收到聊天提示：
  `检测到批量选中拆除 N 个建筑物，已被强制观战 10 分钟。`（V0.1.5 起提示文案为
  `理由，已被强制观战 N 分钟。`，理由包含 §2.3 的接收台专项理由）。
- V0.1.5 起所有处罚走统一入口 `punish(player, monitor, reason)`：同一监控窗口只处罚一次，
  接收台专项处罚（§2.3）复用同一入口。

### 3.5 到期解除

`tickRelease()` 每 100 毫秒扫描：

- 游客：到期后异步查 `MdtStorage.getIpAccountBinding(ip)` 与 `ipGuard.isRiskIp(ip)`；
  疑似小号/风险 IP 时再延长 600 秒，否则解除；
- 非游客：到期直接解除；
- 玩家离线或已不是强制观战：清理记录。

---

## 4. 配置

前缀：`wayzer.ext.idleBreakGuard.*`

| 配置键 | 默认 | 说明 |
|---|---|---|
| `guestMonitorSeconds` | 120 | 游客监控秒数 |
| `level1MonitorSeconds` | 60 | 1 级监控秒数 |
| `level2MonitorSeconds` | 60 | 2 级监控秒数 |
| `guestBatchThreshold` | 50 | 游客批量选中真实建筑上限 |
| `level1BatchThreshold` | 100 | 1 级上限 |
| `level2BatchThreshold` | 150 | 2 级上限 |
| `observerSeconds` | 600 | 强制观战秒数 |
| `keepObserverExtraSeconds` | 600 | 可疑时延长秒数 |
| `guestAltKeepObserver` | true | 游客疑似小号时是否继续观战 |
| `ignoreMapBlocks` | true | 忽略 derelict 地图原有物 |
| `ignoreBoulders` | true | 忽略名称含 boulder 的巨石 |
| `conveyorWhitelist` | `conveyor,titanium-conveyor,plastanium-conveyor` | 只拆这些传送带时豁免 |
| `buildingWhitelist` | 21 种（见 §2.2） | 额外豁免的建筑物（与 `conveyorWhitelist` 取并集，选中不计入） |
| `receiverBlocks` | `landing-pad` | 接收台黑名单：监控对象选中/拆除即处罚；只处罚，不还原（§2.3） |
| `debugLog` | false | 输出批次/触发诊断日志 |

配置示例：

```json
"wayzer": {
  "ext": {
    "idleBreakGuard": {
      "guestMonitorSeconds": 120,
      "level1MonitorSeconds": 60,
      "level2MonitorSeconds": 60,
      "guestBatchThreshold": 50,
      "level1BatchThreshold": 100,
      "level2BatchThreshold": 150,
      "observerSeconds": 600,
      "debugLog": false
    }
  }
}
```

---

## 5. 依赖

`@file:Depends`：

- `coreMindustry` —— `listenPacket2Server`；
- `wayzer/user/trustLevel` —— 等级；
- `wayzer/cmds/voteOb` —— 强制观战/解除；
- `wayzer/user/accountIpGuard` —— 风险 IP 查询。

`voteOb` 本身还会依赖 `betterTeam`、`playerMute` 等，需完整 wayzer 脚本树。

---

## 6. 部署/回滚

部署：

1. 备份 `config/scripts/wayzer/ext/` 与 `config/scripts/data/config.conf`；
2. 复制脚本到 `config/scripts/wayzer/ext/idleBreakGuard.kts`；
3. 清 `config/scripts/cache/`；
4. 冷启动，确认：
   - `scriptdiag state wayzer/ext/idleBreakGuard` = `Enabled failReason=无`；
   - `sa listFailed` 为空；
   - 启动 banner 出错数为 0。

回滚：

- 删除脚本 + 重启；
- 或 `sa unload wayzer/ext/idleBreakGuard`（正在被强制观战的玩家不会自动解除）。

---

## 7. 本地验证记录

环境：MindustryX B509 / SA 3.4.0 / 完整 MDT 脚本树 / 开发服。

已验证：

| 场景 | 结果 |
|---|---|
| 游客选中 50 / 51 | 不触发 / 触发 |
| 1 级选中 100 / 101 | 不触发 / 触发 |
| 2 级选中 150 / 151 | 不触发 / 触发 |
| 监控窗口 120 / 60 / 60 秒 | 窗口内触发；过期后不触发 |
| 建造中的 60 个 `ConstructBuild` | 不计入、不触发 |
| 60 个传送带 | 豁免、不触发 |
| A 选中 60 个，B 去拆其中一个 | A 不被误判 |
| 21 种豁免方块内部名称（2026-11-07） | 运行服 `Vars.content.blocks()` 校验 21/21 全部注册，无拼写错误 |

关键修复：

- 拆除态 `ConstructBuild` 之前被直接跳过，导致 `selected=0`，必须等真正拆掉足够方块才触发；
  现在用 `previous` 还原并计入。
- 移除无法归因操作者的 `TileChangeEvent`，避免“B 拆了 A 选中的建筑，A 被强制观战”。
- `BlockBuildBeginEvent` 只对 `monitor.selected` 里的 tile 生效，避免“选中一批后去拆别的建筑”被误判。

---

## 8. 未验证/已知限制

1. **真实客户端**：本地是服务端构造 `ConstructBuild` 模拟，真实客户端的包拆分、事件顺序、观战提示观感需要实机确认。
2. **终止拆除不回退**：框选后取消，`selected` 仍保留到窗口结束；再次框选会继续累加。
3. **`BlockBuildBeginEvent` 依赖**：若某些路径不触发该事件，可能出现已选中但 `started` 未置位；目前靠包层 `ConstructBuild` 计数兜底。
4. **强制观战是会话态**：不跨重启持久化，重连/重启可能丢失。
5. **阈值是启发式**：正常大规模重建/拆自己建筑也可能达到阈值，需按实际误判调整。
6. **没有 UI/弹窗**：只发聊天提示；强制观战广播由 `voteOb` 负责。
7. **（V0.1.5）接收台 A/B/C 三条判定未实机验证**：本地只验证了编译/加载与方块名/配置项，
   真实客户端下“拆到接收台才触发 B”、广播文案与观战观感仍需实机确认。
8. **（V0.1.5）不还原接收台（用户决定）**：A/B 只处罚，不恢复被拆的接收台；
   拆除后其接收配置与已接收物品不保留，需要时由管理员/回档处理。
9. **（V0.1.5）范围口径**：接收台专项保护只覆盖游客与 1 级（按需求）；2 级及以上不触发专项处罚。
10. **（V0.1.5）B 的时序依赖**：B 依据“本次选中批次里有接收台”的已记录集合；
    若客户端选完立刻开拆、批次还没被插件处理（100ms 合并窗口），可能退化为 A 的理由（处罚照常）。

---

## 9. 代码位置

| 行（约） | 内容 |
|---|---|
| 95 | `Monitor` 状态（含 V0.1.5 的 `receiverTiles`） |
| 296 | `handleBatch()` 批次计数（含豁免与 C 判定） |
| 364 | `punish()` 统一处罚入口 / `trigger()` |
| 457 | `listenPacket2Server<ClientSnapshotCallPacket>` |
| 474 | `BlockBuildBeginEvent` 开始拆除（含接收台 A/B/C 判定） |
| 542 | `onEnable` 100ms 循环 + 版本日志 |

维护时注意：不要改 `vote.lib.kt` / `wayzer/lib/*.kt`；块注释里不要出现嵌套的 `/**`；改完清 `cache` 冷启动。
