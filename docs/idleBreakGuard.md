# 新玩家批量框选拆除监控（idleBreakGuard）

> 脚本：`scripts/wayzer/ext/idleBreakGuard.kts`
> 部署路径：`config/scripts/wayzer/ext/idleBreakGuard.kts`
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
- 没有 `tile.build` 的格子：跳过；
- 建造中的 `ConstructBuild`（`wasConstructing=true`）：跳过；
- 拆除中的 `ConstructBuild`（`wasConstructing=false`）：用 `previous` 还原原方块，
  按真实建筑计入；
- 其余有 `build` 的方块：按真实建筑计入。

### 3.3 开始拆除的判定

`monitor.started` 由两处置位：

1. 包层处理时，看到选中坐标已经是**拆除态** `ConstructBuild`；
2. `BlockBuildBeginEvent` 中，玩家开始拆的 tile 在 `monitor.selected` 里。

只有 `started=true` 且 `selected.size > 阈值` 才会触发，避免“只框选、不拆”也被强制观战。

### 3.4 触发

调用：

```kotlin
with(voteOb) { forceObPlayer(player, reason, null, null, "system") }
```

- 强制观战 600 秒；
- 全服广播由 `voteOb` 输出；
- 玩家收到聊天提示：
  `检测到批量选中拆除 N 个建筑物，已被强制观战 10 分钟。`

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

---

## 9. 代码位置

| 行（约） | 内容 |
|---|---|
| 53 | `Monitor` 状态 |
| 129 | `handleBatch()` 批次计数 |
| 190 | `trigger()` 强制观战 |
| 220 | `checkGuestRelease()` 游客到期检查 |
| 276 | `listenPacket2Server<ClientSnapshotCallPacket>` |
| 293 | `BlockBuildBeginEvent` 开始拆除 |
| 327 | `onEnable` 100ms 循环 |

维护时注意：不要改 `vote.lib.kt` / `wayzer/lib/*.kt`；块注释里不要出现嵌套的 `/**`；改完清 `cache` 冷启动。
