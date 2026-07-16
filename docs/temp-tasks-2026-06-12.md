# 临时任务记录（2026-06-12）

> 本文件用于本轮开发过程追踪。完成并同步正式文档后可删除或清空。

## 待处理

## 当前任务：账号权限审计与游客MDC保留（2026-07-04）

- [x] 复查账号管理权限边界：未登录会话仍被剥离 UUID/历史主体/原生 `@admin` 权限组。
- [x] 为 `/setpassword`、`/deleteaccount`、`/accountqq`、`/guestob`、`/ipguard`、`/trustpoint` 增加指令体内二次权限检查。
- [x] 新增 `MdtStorage.migrateTrustPoints`，注册新账号后合并游客 UUID 已落库的当前/累计 MDC，并清理来源 MDC。
- [x] 注册成功保留游客 MDC 时向玩家发送提示；测试模式下不迁移正式 MDC。
- [x] 同步 `docs/account-system.md`、`docs/trust-system.md`、`docs/database-system.md` 与 `docs/scripts-maintenance.md`。

## 当前任务：外部 CP 兼容加载与大文件慢同步（2026-07-03）

- [x] `/externalcp` / `/vote cp` 扫描范围从 `.json` 扩展到 `.json` / `.hjson`，支持按编号、文件名或显示名加载。
- [x] CP 解析改为优先 Jval 原生 JSON/HJSON，失败后回退旧 ContentsTweaker 兼容预处理；解析失败会返回明确错误。
- [x] 旧 2MB 大小限制改为慢同步阈值，超过阈值提示“文件较大，将缓慢同步”，并拉长分批同步间隔；新增 64MB 硬上限防误放超大文件。
- [x] 加载/应用失败记录完整堆栈，并在聊天返回失败阶段、解析模式与安全截断后的错误信息。
- [x] `/vote cp` 加载、热重载、卸载单个与卸载全部外部 CP 的投票门槛统一调整为 70% 同意。
- [x] 同步 `docs/help-menu.md` 与 `docs/scripts-maintenance.md`。

## 当前任务：投票门槛显示与选择器补充（2026-07-01）

- [x] 投票库默认门槛改为有效赞/反票 50%，投票广播与确认菜单显示当前门槛和全员参与时的估算门槛。
- [x] `/vote` 列表为多数投票标注“需50%同意”，为 `/vote cp` 标注“需70%同意”；PVP同队投降保留80%，本队建筑记录清理保留同队40%并单独标注。
- [x] `/vote cp load/unload` 与菜单加载/卸载/卸载全部均按70%同意计算。
- [x] `/kill`、`/tp` 选择器新增单位种类筛选：`@u[单位ID]`、`@e[type=单位ID]`、`@t[队伍id,单位ID]`。
- [x] 同步 `docs/help-menu.md` 与 `docs/scripts-maintenance.md`。

## 当前任务：逻辑绘图/方块禁用/鱼鱼技能（2026-07-01）

- [x] `/logicdraw` 增加本局覆盖：`roundoff`、`roundon`、`roundclear`。
- [x] 新增 `/blockban`、`/blockunban`，支持本局单独禁用/解禁指定建筑方块。
- [x] 技能商店新增 `fishonlyyou` / “此生只属鱼你”：资历2购买、10MDC售价、0消耗、不受 noskill 限制，召唤专属 risso 鱼鱼并拦截其他玩家附身。
- [x] 同步 `docs/logic-draw-guard.md`、`docs/skill-system.md`、`docs/shop-system.md`、`docs/help-menu.md`、`docs/scripts-maintenance.md`。

## 当前任务：投票/管理指令语义复查（2026-06-30）

- [x] 复查 `/kill`、`/tp` 选择器语义：确认 `@e/@a/@s/@t[队伍id]` 与单位种类选择器 `@u[单位ID]`、`@e[type=单位ID]`、`@t[队伍id,单位ID]` 已接入，`/tp <玩家>` 与 `/tp <玩家1> <玩家2>` 行为符合需求。
- [x] 复查玩家菜单“禁言/禁建”时长：菜单输入正整数为临时限制，留空为永久；临时限制仅内存保存。
- [x] 修正赞/踩历史重复每日计数行导致上限随机/偏低的风险：按重复行求和并在写入时合并。
- [x] 复查有效被踩公式：纳入最近7天被赞/被踩，并降低总被赞抵消权重。
- [x] 复查 `/vote cp`：确认加载、热重载、卸载单个、卸载全部均可投票，且要求 70% 同意。
- [x] 复查世界处理器静默入口：`/wpq` 只回复操作者/控制台，不全局播报。
- [x] 复查投票稳定性：投票资格默认不再依赖玩家单位存活，强制观战通过全局拒绝谓词禁止参与普通投票，`/vote quitOb` 例外允许本人。
- [x] 复查取消暂停波次：`/vote resumeWave` 已加入，并与 `pauseWave/setWave` 一起同步到帮助菜单。
- [x] 补齐 `docs/scripts-maintenance.md`、`docs/help-menu.md`、`docs/trust-system.md`、`docs/security-guard.md`。
- [x] 启动验证脚本加载：`共找到196脚本,加载成功192,启用成功139,出错0`，确认无本轮改动引入的编译错误。

## 当前任务：性能优化压力措施补强（2026-06-30）

- [x] 按 `docs/hybrid-unit-catalog.md` 的新单位表复核 T1/T2/T3 清理口径。
- [x] 新增疑似 PPS 顶满检测：上行达到预算 60% 后，短时间多人退出触发低阶单位/导弹源/scathe 炮台清理。
- [x] 新增严重上行超量（>200%）清理 T4 及以下单位。
- [x] 新增压力等级4且 TPS/上行同时超限时清理数量前三单位种类。
- [x] 修复性能优化关闭后压力措施继续运作与自动暂停/规则未回退问题。
- [x] 同步 `docs/performance-guard.md` 与 `docs/scripts-maintenance.md`。






## 当前任务：技能/投票补充（2026-06-26）

- [x] 杂交系统新增基因清洗，10 MDC 清理当前附身单位类型全部基因。
- [x] 新增 2级技能“完全痊愈”，5 MDC 完全治愈当前单位。
- [x] 调整 3级“骇人空袭”：10 MDC，三波空军从玩家位置冲向光标附近坠毁。
- [x] 新增管理员技能“本局结算MDC翻倍”。
- [x] 新增 `/vote pure <1-10>` 与 `/vote pureoff` 纯净模式投票。
- [x] 优化投票弹窗与文字投票提示的颜色/分区可读性。
- [x] 同步 `docs/skill-system.md` 与 `docs/scripts-maintenance.md`。

## 当前任务：/cp 管理菜单修复（2026-06-26）

- [x] `/cp` 保持仅管理员权限，普通玩家不可使用。
- [x] 游戏内输入 `/cp` 改为打开 CP/数据包管理菜单。
- [x] 菜单内可查看 CP 详情、临时卸载、禁用并卸载。
- [x] 卸载/禁用后重放剩余 CP、清理无效炮塔弹药，并分批同步世界数据。
- [x] 同步 `docs/scripts-maintenance.md`。

## 当前任务：数据库调用主线程堵塞 review（2026-06-25）

- [x] 扫描 `MdtStorage` 调用点，重点看菜单/列表、周期任务、聊天热路径和积分板。
- [x] 复查 MDC 相关高频入口；已取消积分板 MDC 显示，避免引入高频查库。
- [x] 禁言聊天拦截加入在线缓存，玩家进服 IO 预加载，禁言/解禁同步更新。
- [x] 服务器介绍轮播加入内存缓存，定时轮播不再每轮查 `MdtSettings`。
- [x] `MdtStorage` 与 ban 存储层统一封装慢事务日志，单次事务超过 200ms 输出函数名/线程/耗时，超过 1000ms 标记严重慢事务。
- [x] 记录后续判断：若慢日志仍显示 DB 单事务数秒，再考虑统一 IO 队列或数据库迁移。

## 当前任务：MDC显示与promax投票限制（2026-06-25）

- [x] 取消积分板 MDC 显示；保留在线缓存用于MDC变动私聊提示与减少重复读取。
- [x] MDC获得/失去时给本人发送仅自己可见的统一短提示，技能消费等会显示原因。
- [x] GG奖励提示合并到统一MDC变动提示。
- [x] `/vote infinitefirepromax` 发起限制为信任2级及以上。

## 当前任务：管理员自定义成就系统（2026-06-26）

- [x] 检查柠檬开源插件成就实现：其核心为脚本主动 `finishAchievement` 记录并发经验，适合地图脚本调用；MDT 侧采用事件驱动检测 + 可配置条件。
- [x] 新增 `MdtCustomAchievements` 存储表与 CRUD。
- [x] `/achadmin` 无参数打开成就管理菜单；4级/管理员也可从成就面板进入。
- [x] 管理菜单支持新增/删除/启用/隐藏自定义成就，编辑成就名、条件、MDC奖励、称号奖励。
- [x] 预设条件覆盖获赞/点赞/点踩/认可/发帖/在线小时/当前MDC/累计MDC/信任等级/资历等级/特定日期或小时在线登录。
- [x] 自定义成就定义加入 60 秒缓存；修改后立即失效。
- [x] 成就检测使用一次 `AchievementStatsSnapshot` 合并读取统计，内置统计类成就与自定义成就共用快照，避免每条成就单独查库。
- [x] 同步 `docs/achievement-system.md` 与 `docs/scripts-maintenance.md`。

## 当前任务：运行反馈修复（2026-06-26）

- [x] `/vote` 展开后的投票选择列表按类型增加颜色前缀，提高可读性；补齐踢出/强制观战等手动注册投票项颜色。
- [x] 修复基因清洗失败路径：避免 `returnReply` 抛出的控制流异常被同一 `try/catch` 捕获后重复回补 MDC。
- [x] 同步检查基因杂交失败回补路径，将命令版基因杂交错误返回与异常路径拆开，避免同类重复回补风险。
- [x] 按新要求改为基因清洗/基因杂交/自选基因杂交“成功后消耗 MDC”；失败不扣费、不走返还逻辑，仅释放前做余额预检查。
- [x] 修复 `wayzer/map/worldProcessorAdmin` 缺少 `coreMindustry/menu` 依赖导致 `MenuBuilder/PagedMenuBuilder` 无法解析。
- [x] 修复 `worldProcessorAdmin` 在 `with(contentsTweaker)` 隐式接收者中调用本脚本 `normalizeCpPatch` 的编译问题。

## 当前任务：技能脚本拆分整理（2026-06-26）

- [x] 将原集中在 `wayzer/user/ext/skills.kts` 的技能实现拆分为通用、2级、3级、杂交、神权/管理员五个模块脚本。
- [x] `skills.kts` 保留为核心入口，负责共享工具、分类菜单、菜单条目注册与全局 Reset 清理。
- [x] `skills.lib.kt` 增加主菜单扩展注册表，使杂交菜单、神权菜单由各自模块注册，避免核心入口继续硬编码。
- [x] 修复拆分后跨脚本访问核心工具函数的问题，并通过服务端启动加载测试：`共找到193脚本,加载成功189,启用成功136,出错0`。
- [x] 顺带修复自定义成就称号奖励在 Kotlin 2.1 下的 nullable smart cast 编译问题。

## 当前任务：投降与无限火力投票调整（2026-06-25）

- [x] `/vote gameOver` 解除非 PVP 地图 `canGameOver=false` 的投降限制。
- [x] 保留 PVP 本队投降逻辑，避免影响 PVP 队伍胜负处理。
- [x] `/vote infinitefire` 改为标准无限火力，不填充核心资源/不提高伤害倍率。
- [x] 新增 `/vote infinitefirepromax` 保留原 promax 效果。

## 当前任务：性能卡顿排查（2026-06-25）

- [x] 排查 GameOver/Reset 热路径：资历在线时长、信任/资历自动检测、贡献 MDC 结算、地图脚本 Reset 扫描。
- [x] 资历在线时长改为内存累加 + IO 批量 flush。
- [x] 信任/资历自动检测改为单次统计查询 + 每轮限量处理。
- [x] 信任/资历自动检测跳过未登录游客，避免游客连接/结算时进入无意义 DB 检查。
- [x] 本局贡献 MDC 奖励改为批量写入并迁移到 IO。
- [x] GG/点赞/认可等即时 MDC 奖励改为内存缓冲 + 周期批量写入，减少游戏结束后多人同时 GG 的同步 DB 压力。
- [x] 地图脚本每局 Reset 扫描默认关闭，保留调试配置并添加慢日志。
- [x] 玩家加入/离开时的信息面板资料缓存与最近玩家记录迁移到 IO 线程；资料变更事件改为失效缓存，避免 join 时同步查询多张表。
- [ ] 等待服务端实测慢日志，若仍卡顿，再按日志继续定位 CP 应用、地图加载或具体数据库事务。

## 当前任务：`@hybrid` 地图特色杂交（2026-06-23）

- [x] 新增 `mapScript/tags/hybrid.kts` 并注册地图标签 `@hybrid`。
- [x] 添加 `/hybrid` 独立菜单与 `/hybrid unit|gene|select|status` 指令，不依赖账号/权限/资历/MDC/技能系统。
- [x] 地图版单位杂交队伍共享冷却 10 秒，成功后父母单位分别概率死亡。
- [x] 地图版基因杂交队伍共享冷却 120 秒，成功后不击杀双方；单位基因可多次叠加，炮塔基因仍限一次。
- [x] 地图版自选基因杂交队伍共享冷却 300 秒，目标同意后列出可获取武器/能力，成功后击杀双方/摧毁目标炮塔。
- [x] 地图版取消核心机/导弹等额外杂交限制，保留“发起方不是建筑/炮塔”“可序列化”这类安全限制。
- [x] 地图版与技能版尝试通过直接追加 `StatusEntry` 实现同名 Buff 多层叠加。
- [x] 开局与每 5 分钟广播 `/hybrid` 提示。
- [x] 同步 `docs/map-scripts.md`、`docs/hybrid-system-design.md`、`docs/scripts-maintenance.md`。

## 当前任务：基因杂交系统（2026-06-22）

- [x] 整理基因杂交炮塔白名单与模板表：`docs/advanced-hybrid-turrets.md`。
- [x] 将杂交入口改为 2 级资历技能菜单：单位杂交 / 基因杂交。
- [x] 单位杂交现为 6 MDC，复用当前单体子单位玩法。
- [x] 实现基因杂交：临时 CP、25 MDC、20% 玩法失败、单位/炮塔各一次限制、异常回补。
- [x] 第一批炮塔模板：duo、scatter、hail、arc、lancer、salvo。
- [x] 静态检查并同步 `docs/skill-system.md` / `docs/scripts-maintenance.md`。
-[来自维护者]杂交系统已完成，后续可移除任务

## 后续计划池

- 等待新的玩法修改/添加需求。
- 若后续只出现零散报错，按“明确 bug 优先修复，不再做全量 review”的方式处理。
- 如后续新增较大玩法，先在本文件新增任务拆分，再逐步实现并同步正式文档。

## 已同步到正式文档

- flood 完整玩法重实现已同步至 `docs/map-scripts.md` 与 `docs/scripts-maintenance.md`。
- 本轮 5 项修复（同IP小号提示、灭火/消防车水弹、介绍锁定、范围治愈、空对地导弹单位）已同步至 `docs/security-guard.md`、`docs/server-description.md`、`docs/skill-system.md` 与 `docs/scripts-maintenance.md`。
- 本轮导弹技能改动（海啸水弹散射、导弹齐射、导弹风暴）已同步至 `docs/skill-system.md` 与 `docs/scripts-maintenance.md`。
- 本轮预制防线技能与防覆盖空地限制（通用“初级预制防线”4x4、三级“标准预制防线”6x6）已同步至 `docs/skill-system.md` 与 `docs/scripts-maintenance.md`。
- 本轮计分板我方单位数显示、投票弹窗中立/待定按钮默认颜色修复已同步至 `docs/scripts-maintenance.md`。
- 第一轮 review 修复已同步至 `docs/scripts-maintenance.md`：
  - 账号/权限/风控：注册在线时长缓存限量、目标解析补全、菜单/banip/最近玩家边界检查。
  - 投票系统：中立票快速通过边界、投票踢出/强制观战管理员目标预检查、同IP/冷却机制复核。
  - 性能优化：上行统计串行化锁、序列化告警限频、同步流 flush、隐藏实体/单位销毁同步复核。
  - 技能系统：技能前置空单位检查、多个 `player.unit()` 空值保护、失败不扣费边界、商店缓存失效。
  - 菜单/地图脚本：玩家/封禁目标解析补全、TankWars 离线等待忙循环修复与 NPE 风险复核。
- 运行加载失败修复：`coreMindustry/variables.kts` 不再直接引用当前 v158 服务端缺失的 `Rules.unitAmmo` 字段，改为反射兼容。
- 账号注册验证码提示优化：`/captcha` 时长不足会明确显示本次启动累计在线与还需在线多久，满足时显示已达成条件，并同步到账号系统文档。
- 运行崩溃热修：当前 MindustryX 服务端 JAR 的 `LogicBlock$LogicBuild.interactable` 无 headless 防护触发客户端 `RenderExt/UIExt` 初始化；已切换并热修 `server-2026.05.X35.jar`，保留 `.bak-headless-20260613-020640` 备份，同时同步本地 X35 参考补丁。
- 资历自动晋升兜底修复：`seniorityLevel.kts` 的自动检测队列加锁，在线时长写入/资历检测循环增加异常隔离，并每5分钟对在线玩家做兜底复查，避免满足条件后只能手动 `/seniority check` 晋升。
- 资历3级“单位杂交/基因杂交/自选基因杂交”系统已同步至 `docs/hybrid-system-design.md`、`docs/skill-system.md` 与 `docs/scripts-maintenance.md`。
- 基因杂交系统第一版已同步至 `docs/advanced-hybrid-turrets.md`、`docs/skill-system.md` 与 `docs/scripts-maintenance.md`。

## 当前任务：高频 DB 热路径继续优化（2026-06-26）

- [x] 点赞与点踩走同一套优化：每日额度检查与写入合并为单事务，旧数据清理按日期缓存，避免一次操作多次同步查询。
- [x] 点赞/点踩/认可/MDC 等事件触发的成就检测按 UID 防抖合并，连续操作只在等待窗口结束后跑一轮统计读取。
- [x] 玩家加入时的信息面板缓存、称号缓存、IP 身份记录均迁移为游戏线程采集快照 + IO 查询/写入 + 回游戏线程更新缓存。
- [ ] 等待实测慢事务日志；若仍有玩家加入瞬间掉刻，继续定位其它 `PlayerJoin` 监听器。

## 当前任务：外部 JSON/HJSON CP 热重载系统（2026-06-26）

- [x] 新增 `scripts/external-cp/` 作为外部 CP JSON 存放目录。
- [x] 新增 `wayzer/map/externalCpHotReload.kts`，支持扫描、读取、规范化、热加载、失败回滚与分批同步。
- [x] 新增管理指令 `/externalcp`/`/ecp`，支持菜单加载/热重载/卸载外部 CP。
- [x] 新增投票入口 `/vote cp`、`/vote cp load <文件名|编号>`、`/vote cp unload <文件名|编号|all>`，投票通过后当前局热重载或卸载指定外部 CP，加载/卸载均需 70% 同意。
- [x] 外部 CP 不写入地图 tag/数据库，仅当前局有效；Reset/换图清理运行态记录。
- [x] 启动验证通过：`共找到194脚本,加载成功190,启用成功137,出错0`。

## 当前任务：文档追溯补齐（2026-06-30）

- [x] 用 `git status` / `git diff --stat HEAD` 对照当前未提交脚本改动与 docs 改动，确认可通过当前对话上下文、git 历史和工作区 diff 追溯大部分功能变更。
- [x] 补齐 `docs/skill-system.md`：3级物资补给、纯净模式额外禁用3级、理财类商店技能不受 PVP/noskill、3级技能预检查口径。
- [x] 补齐 `docs/performance-guard.md` 与 `docs/scripts-maintenance.md`：`mainThreadWatchdog.kts`、`/tickwatchdog status`、`syncThrottleEnabled` 与受限快照耗时日志。
- [x] 补齐 `docs/account-system.md` / `docs/security-guard.md` / `docs/scripts-maintenance.md`：同IP小号延迟提示、去重、轻量连接记录，今日游客观战状态缓存/异步持久化。
- [x] 补齐 `docs/map-scripts.md` 与 `docs/scripts-maintenance.md`：`14668`、`15463` 离线等待循环降频。
- [ ] 后续提交前固定检查“脚本有功能变更但 docs 未变更”的风险；若不需要文档，需在任务文档备注原因。

## 当前任务：`[危险]服务器测试模式`（2026-06-30）

- [x] 通过当前对话上下文、`git status`、`git diff --stat HEAD` 与现有 docs 追溯近期改动，确认文档可用 git/工作区 diff 补齐，但后续仍需要每轮提交前主动维护。
- [x] 新增 `wayzer/lib/ServerTestMode.kt` 跨脚本服务接口。
- [x] 新增独立脚本 `wayzer/user/serverTestMode.kts`，提供 `/servertestmode` / `/testmode` / `/测试模式` 管理入口与二次确认菜单。
- [x] 启用测试模式后切换已登录账号玩家到 `test:<account:id>` 临时主体；未登录游客仍为0级且不获得测试MDC；关闭后恢复原账号主体并清理临时文件。
- [x] 账号登录/自动登录/注册/验证码在测试模式下仍可用；改密/注销与红包功能在测试模式下关闭，避免误操作正式账号/红包数据库。
- [x] 信任/资历/MDC/结算奖励接入测试模式覆盖：非管理员信任1、资历3，正式自动晋升跳过测试 UID，贡献结算 MDC ×10 并写入临时文件。
- [x] 管理帮助菜单加入 `[危险]服务器测试模式`。
- [x] 同步 `docs/account-system.md`、`docs/trust-system.md`、`docs/help-menu.md`、`docs/scripts-maintenance.md`。
