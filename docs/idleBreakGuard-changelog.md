# idleBreakGuard 更新日志

> 插件：`scripts/wayzer/ext/idleBreakGuard.kts`
> 版本：**V0.1.6**
> 当前版本维护者：八色 和他的 dsh

## V0.1.6（2026-11-16）

修复沙盒模式误封：切到沙盒（或临时「编辑器模式」）后插件继续判定，会把正常拆除当成乱拆。

- 新增 `isSandboxLike()`：读 `Vars.state.rules.mode()`，`sandbox` / `editor` 都算豁免。
  - 沙盒图加载时 `applyRules(Gamemode.sandbox)` 会置 `infiniteResources=true`；
  - 神权菜单/投票的临时「编辑器模式」会置 `editor=true`；
  - PVP/进攻图不受影响（`mode()` 优先级 pvp/attack 更高，照常检测）。
- 四个入口都加豁免：`initMonitor`（不建窗口）、`handleBatch`（丢弃批次）、
  `BlockBuildBeginEvent`（不判定）、`punish`（兜底不处罚），因此在游戏中途切到沙盒也立即生效。
- 沙盒里进服不消耗每日首次 key；切回生存后仍按当天首次进服监控。
- 新增配置 `ignoreSandboxMode`（默认 `true`），设为 `false` 可回退旧行为。
- 版本号 `V0.1.6`。

验证：

- 本地测试服（`mdtserver-test`，B509 + SA 3.4.0）清缓存后冷启动：
  `共找到169脚本,加载成功165,启用成功160,出错0`，`wayzer/ext/idleBreakGuard` = `Enabled failReason=无`，
  `[IDLEGUARD] idleBreakGuard V0.1.6 已启用`；
- 用临时探针脚本在真实服务端上确认 `Vars.state.rules.mode()` 的判定：
  `infiniteResources=true` → `沙盒`；`editor=true` → `地图编辑器`；全部复位 → `生存`（探针验证完已删除）；
- 真实客户端行为（沙盒里框选拆除不触发强制观战）待用户实测。

## V0.1.5-fix（2026-11-07）

修复多格建筑（接收台 `landing-pad` 为 3×3）的坐标判定：

- `handleBatch` 的 `selected` / `receiverTiles` 使用 `build.tile.pos()`（建筑锚点）；
- `BlockBuildBeginEvent` 原来用 `event.tile.pos()`，拆非锚点格子时会匹配失败；
- 现在统一换算为 `event.tile.build?.tile?.pos() ?: event.tile.pos()`；
- 修复 B 项「批量选中含接收台，拆到接收台角落」被误判为 A，以及 C 项开始拆除未匹配 `selected` 的问题。
- 修复 `/vote quitOb` 提前退出观战后 `monitor.triggered` 仍为 `true`，导致原监控窗口内后续拆除不再触发监控的问题：
  - 观战提前解除时清理 `selected` / `receiverTiles` / `started` / `triggered`；
  - 不延长原监控窗口，不消耗新的每日首次 key。
- 修复“被 OB 后退出重进，再 vote 解除 OB 仍不触发”的问题：
  - `PlayerLeave` 不再立即删除 `obRecords`，保留到重连或记录过期；
  - `tickRelease` 离线时只在 `rec.until` 过期后清理；
  - 重连后检测到 `isForceOb == false` 时重置 `triggered`。

> 由Holy和他的dsh完成实机测试：修复了拆除接收台被 OB 并解除观战后，再次拆除接收台无法再次触发 OB 的问题；同时修复了被 OB 后退出重连、解除观战后不再检测接收台拆除的问题，确保后续拆除接收台仍会重新触发 OB。

验证：

- 冷启动编译/加载成功，`idleBreakGuard V0.1.5` Enabled；
- 由Holy和他的dsh完成实机测试。

## V0.1.5（2026-11-07）

简单汇报一下这次插件更新了什么。

### 建筑物豁免

添加了建筑物豁免：合金传送带，物品管道，铜墙，大型铜墙，废墙，大型废墙，巨型废墙，超巨型废墙，
铍墙，大型铍墙，钨墙，大型钨墙，双管，机械钻头，气动钻头，微型处理器，逻辑显示屏，大型逻辑显示屏，
逻辑显示单元，画布，大型画布。

拆除上述建筑物的时候再也不会计入 50 个建筑物上限了，避免游客玩家进服以后发现前期建筑和
无用建筑以后手痒难耐渴望拆除结果被惩罚了。

### 建筑物黑名单：接收台

添加了建筑物黑名单：接收台。当游客和 1 级玩家在监控窗口内拆除接收台的瞬间，就会被处罚；
批量拆除的建筑物中包含了接收台、并且拆到接收台时，也会被处罚。

处罚理由会写「试图拆除接收台」（直接拆接收台）或「疑似将要拆除接收台」（批量选中含接收台并拆到）。

> 按维护决定：只处罚，不实现“复原接收台”。
