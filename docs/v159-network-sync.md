# v159 网络同步与完整重同步

> 当前基线已更新为 **Mindustry 160.1 / MindustryX B491**（2026-09-12），160 起实体随世界流传输，
> 见下文"160.1 起：实体随世界流传输"；其余同步不变量沿用。

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
本轮未改动该逻辑（160 适配只改了 `Groups.fire`/`Groups.puddle` 的遍历方式），
但已作为"160 下需重点回归"的项记录：真实多人 + 运行中卸载 DP 仍需受控观察。

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
