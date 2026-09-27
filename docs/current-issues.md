# 当前问题与待办记录

> 维护约定：本文件只记录**尚未完成**的问题/需求。完成某项任务后，应从本文删除对应条目，并按需同步更新相关系统文档与 `docs/scripts-maintenance.md`。

记录时间：2026-06-12（2026-09-13 更新：补入菜单/版本跟进相关的未完成项）

## 待处理任务

### 1. 菜单缩放：帖子系统已实现，其余系统未接入（如需再做）

- 帖子系统（2026-09-19 已实现）：分区页底部 `缩小 − / 缩放 N% / 放大 ＋ / 重置`，**仅 1 级及以上**；
  落库表 `MdtStorage.PlayerUiPrefs`，内存缓存 + 15 秒合并落盘 + 10 分钟 TTL 清理；详见 `forum-posts.md` 顶部说明。
- **尚未接入**：Wiki / 成就 / 商店等其它 MenuV3 页面仍只有全局默认（移动端 0.85）。
  如需统一，复用同一张表（或加字段）与 `forumUiScale` 同款写法即可。


### 2. 移动端菜单缩放实机验证（等用户反馈）

- 自动缩放只对移动端客户端生效（`NetConnection.mobile`），桌面观感已确认正常；
- 待安卓端实测 `/wiki`、`/posts` 的观感后按反馈调 `MOBILE_SCALE`（或改按屏幕宽窄双套尺寸，成本更高）。

### 3. 局内热重载替代改进（用户 2026-09-13 明确"暂时不必"）

- 审计结论：内容/音频类同步**受 v160.3 协议强制**必须重载地图（源码依据见
  `docs/v159-network-sync.md`「局内热重载可行性核对」），当前 `worldResyncCoordinator` 的串行/去抖即为正确缓解。
- 5 项可做但暂缓的改进（同文档「替代改进清单」）：
  ①常驻热门曲目 + 逐玩家资产指纹记账跳过回合；②小音效窗口期注册；③外部 CP 择时批量；
  ④杂交改为等自然世界流（`hybridAutoWorldSync` 开关）；⑤图片走 `sendTexture` 纹理流替代逐格 `setNet`。
- 真正的零重载需要客户端补丁（MindustryX 客户端 `StreamBegin(isAssets)` 不再 `requestWorld`），成本最大，未启动。

### 4. Wiki / 帖子系统的管理流程仍在聊天菜单

- 已迁移到 `MenuV3` 的只有：Wiki 列表/阅读/最近修改/格式帮助；帖子分区/列表/阅读/评论；成就页。
- **未迁移**（点击后会 `close()` 再进聊天流程）：Wiki 编辑/管理/回收站确认，帖子的发帖/改帖/评论输入/分区管理/回收站/彻底删除确认。
- 口径：这些是管理流程，版式收益小、改动风险大，等页面观感定稿后再评估是否迁移（用户 2026-09-13 已知悉）。

### 5. 版本基线：B495 预览版缺客户端侧验证

- 服务端已按用户要求跟进到 `prerelease-2026.09.13.B495`（Mindustry 160.3），冷启动 `157/153/148/出错0` 已验证；
- **未验证**：真实客户端在该预览版上的观感与内容同步表现（含安卓端）；MindustryX 正式发行版仍是 X37/v160.1，回滚只需改 `server.properties` 的 `jar=`。

### 6. 历史遗留的"待实测/待观察"项（2026-09-13 从 `temp-tasks-2026-06-12.md` 收编）

这些是 2026-06～07 多轮优化后留下的观察项，一直没人闭环，原文照录（括号内为提出时的背景）：

- （2026-06 性能优化轮）**等待服务端实测慢日志**：若仍卡顿，再按日志继续定位 CP 应用、地图加载或具体数据库事务。
- （2026-06 数据库/加入路径优化轮）**等待实测慢事务日志**：若仍有玩家加入瞬间掉刻，继续定位其它 `PlayerJoin` 监听器。
- （2026-07）**CP 包与上行的真实观察**：继续观察包含真实音乐、音效和大型贴图的 CP 包，以及多人同步期间的上行情况。
- （2026-07 网络同步轮）**真实客户端回归**：高上行、首次进服、附身/取消附身与内部世界重同步（`worldResyncCoordinator`）的完整回归。
- （2026-06-30 流程项，已作为长期约定执行）提交前固定检查"脚本有功能变更但 docs 未变更"的风险——该约定已写入
  `scripts-maintenance.md` 的维护规则与 `agent-debug-experience.md` 的提交惯例，视为已落实，保留在此仅作追溯。

## 2026-09-27 用户提出的五项投票/技能改动（待办，供接续）

1. **4x4 核心区"无任何反应 / 只扣 MDC"（✅ 已修，2026-09-27）**（`skillShop.kts` 的 `corezone4`，`SkillShopDefinition("5","corezone4","核心区","召唤4x4核心区", buyPrice=120, useCost=6)`）：
   - **根因（用户从服务端控制台窗口拿到的堆栈定案）**：`skillShop.kts` 的转发包装
     `private fun spawnOverlapError(...) = with(skillsCore) { spawnOverlapError(...) }`
     因为隐式接收者优先级低于当前文件作用域，解析回了自己 → **无限递归 → `StackOverflowError`**。
     命令体第一步就是这个校验，所以铺地板/聊天反馈/广播全部没执行；而 `StackOverflowError` 是 `Error`，
     SA 框架只 `catch (e: Exception)`，于是直接穿透并终止主线程（服务端"看着还活着"：端口在听、socket 能连，
     但世界不 tick、命令全无响应，必须重启）。堆栈只出现在控制台窗口（stderr），`log-0.txt` 里没有。
   - **修法**：改成显式接收者 `skillsCore.spawnOverlapError(player, range, displayName)`（与 `skillsLevel2/3`、`skillsCommon`、`skillsGodAdmin` 一致），
     并在原处留注释；全库扫描"转发包装自递归"确认仅此一处。
   - **同时修**：4x4 原本"先扣费后校验"（崩之前 6 MDC 已扣 → "只扣 MDC"）→ 改为**先校验后扣费**；
     `setFloorSquare` 返回"新铺 / 原本已是 / 越界 / 失败原因"并播报给玩家，新铺格子补 `Fx.placeBlock` 落点反馈。
   - **已排除（免得重复怀疑）**：地板同步没坏（反编译 B495 服务端 / B497 客户端 jar：服务端 `Call.setFloor` 会本地生效**并** `net.send(SetFloorCallPacket, true)`，
     客户端 `handleClient` 直接 `Tile.setFloor(...)`）；测试图 `终极测试地图v7-3` 本身已有 **8923 格**核心区地板，
     在已铺区域使用看不出变化属预期行为（技能会把这句提示出来）。
   - **状态**：脚本已改 + 服务端已冷启动（`158→157`，加载成功 153、启用 148、出错 0）；待用户实测确认。

2. **禁止游客发起"踢出/观战他人"的投票（✅ 已完成）**：`VoteEvent` 新增 `guestForbidden`（框架层拦截）+ 各投票 body 早退；
   `guestVoteBlockReason(player)` 统一文案（"请先用 /login 登录账号"）。已挂：`/vote kick`、`/vote ob`、`/vote mute`、`/vote guestOb`。
3. **新增"投票禁言"（✅ 已完成）**：新脚本 `wayzer/cmds/voteMute.kts`，`/vote mute <玩家名/id> [时长] [理由]`，
   时长支持 `30`（分钟）/`30m`/`2h`/`1d`/`1d12h`，上限 **3 天（4320 分钟）**，没给时长就弹文本框；
   通过后走 `mutePlayerTemporary(target, minutes, reason, null)`（operator 传 null，理由里带发起者名字）。
4. **所有"限制其他玩家"的投票一律禁止游客发起（✅ 已完成）**：与第 2 条同一开关。
   分类口径见 `scripts-maintenance.md` 第十二批：针对某个玩家的挂闸门（kick/ob/mute/guestOb），
   世界/服务器级的（换图、性能、纯净模式、波次、暂停游戏等）不挂；`guestObOff`、`quitOb` 属"放开限制/只作用于自己"，也不挂。
5. **禁止投票观战（✅ 已完成）**：①菜单不再显示原因（上一轮：`option("解除禁止发起投票")`）；
   ②被禁发起投票者仍可 `/vote quitOb` —— 两处闸门都要放行：`VoteEvent.ignoreStartBan = true`（`quitOb` 事件）+
   `/vote` attr 按 `isStartBanExemptVote(...)` 跳过（`voteOb.kts` onEnable 注册 `"quitOb"`/`"解除观战"`）。
6. **出生点保护重做（✅ 已完成，用户 2026-09-27 选口径①）**：旧实现按 `tile.team() == rules.waveTeam` 过滤，
   而出生点是覆盖层、`Tile.team()` 恒为 `derelict` → 恒不触发（等于没保护）。
   现口径：**技能范围碰到任何 `Blocks.spawn` 标记就拒绝** —— `skills.kts` 的 `spawnOverlapError` 改为逐格读覆盖层
   （与 `WaveSpawner.reset()` 同一判据），不再按队伍过滤、不再用 `rules.dropZoneRadius` 折算半径；
   文案带命中坐标与格数。调用方（3x3/4x4 核心区、初级/标准预制防线、管理员放置技能）都是转发包装，无需改动。
   PvP 不做额外豁免（这些放置类技能基本都带 `SkillNoPvp`）。
   **覆盖范围已补齐**（同日第二轮）：全部 13 个会往地上铺地板/覆盖层/方块的技能都挂了闸门
   （3x3/4x4 核心区、初级/标准预制防线、物品源、E星核心、随机液体、随机矿、欧皇物品源、读品、装卸器、照明器、电力源），
   `/pixel` 像素画也改为跳过出生点标记；只拆不建的技能、管理员 `/setBlock|/setFloor|/fill`、地图脚本玩法、
   `randommaga` 的原版载荷投放判定为不需要/无法挂闸门，理由见 `scripts-maintenance.md` 第十三批。
### 交接状态（2026-09-27 第三轮：五项清单已全部落地）

- **服务端**：本机 mdtserver 实例运行中，基线 B495（`server-2026.09.13.B495.jar`）；
  冷启动**新基线 `158/154/149/出错0`**（比 157/153/148 多 1，因为新增了 `wayzer/cmds/voteMute.kts`）；
  命令 Socket 6859 可用，辅助脚本 `.agents/sa-cmd.ps1 -Command "sa <子命令>" -WaitSeconds N`。
  热重载脚本用 `sa load 模块/子路径/脚本名`（例：`sa load wayzer/user/skillShop`）；临时诊断脚本可放到
  `config/scripts/` 下再 `sa scan` + `sa load <名字>`，用完即删（`sa scan` 不会清理已删文件，要 `sa unload`）。
- **仓库**：mdtdo 本轮提交了 4x4 核心区修复与投票三项改动（**未推送**）；插件仓库 `ScriptAgent4MindustryExt-mdtdo` HEAD `b5779e6`（已推送、与 origin 一致）。
- **本轮已完成**：
  1. 第 5 项前半 —— `playerInfoTripleTap.kts` 的"解除禁止发起投票"不再显示原因；
  2. 第 1 项 4x4 核心区 —— 根因是 `with(skillsCore) { spawnOverlapError(...) }` 自递归 → `StackOverflowError`（详见第 1 条）；
  3. 第 2+4 项 —— 游客不得发起作用于他人的投票（kick / ob / mute / guestOb）；
  4. 第 3 项 —— 新增 `/vote mute`（时长文本参数，上限 3 天）；
  5. 第 5 项后半 —— `quitOb` 自救豁免（两处闸门都要放行）；
  6. 第 6 项 —— 出生点保护重做（口径①：范围 ∩ `Blocks.spawn` 标记即拒绝）。
- **五项清单已全部完成**，没有待办项；出生点保护那次是热重载（只改函数体、签名不变）。

- **第 1 项已查清的代码事实（替代更早那版错误结论）**：
  - `skills.kts:286 setCoreZone` 与 `skillShop.kts` 的 `setFloorSquare` 都走 `Vars.world.tile(x,y)?.setFloorNet(Blocks.coreZone)`；
  - `Call.setFloor` 在服务端会**本地生效并广播** `SetFloorCallPacket`，客户端 `handleClient` 会应用 —— 地板同步链路正常，
    早先"`@Remote(called = Loc.server)` 所以服务端调用不广播"的判断是错的；
  - 反编译可用：`& "C:\Program Files\Java\jdk-21\bin\javap.exe" -p -c -classpath <jar> mindustry.gen.Call`（B495 服务端 jar 在 `mdtserver/`；B497 客户端 jar 在 `C:\Users\qw114\Downloads`）。
- **工程纪律（本会话踩坑换来的）**：不碰共享库（`menu.lib.kt` 等，一失败就级联全菜单）；每次只 `sa load` 被改脚本，
  改完 `sa listFailed` 必须为空再报；**改了库（`vote.lib.kt`/`skills.lib.kt` 这类）要冷启动而不是单脚本热重载**，
  否则依赖方仍按旧签名编译，运行时 `NoSuchMethodError`；冷启动验证用 `.agents/test160-b495-run.cmd`；
  重启必须连监管器 `start-server.ps1` 一起停（否则双实例抢端口出假故障），正常启动用 `mdtserver/启动服务器.bat`（`Start-Process` 脱离本会话即可）。