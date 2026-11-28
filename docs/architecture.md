# MDT DO 系统架构与模块路由

> 本文是新会话了解项目全貌与定位文件的**唯一总览入口**。  
> **规则**：只记录当前生效的架构、运行基线、模块路由与不变量；所有细节以对应领域文档和源码为准。

---

## 1. 项目概览与仓库结构

MDT DO 是一套完整的 Mindustry 专服产品，统一维护游戏服务端插件脚本、独立门户 Web、主服本地发信服务、生产更新器与开发调试工具链：

| 路径（开源仓） | 本地主工作区对应路径 | 职责 |
|---|---|---|
| `scripts/` | `mdtserver/config/scripts/` | ScriptAgent 3.4.0 服务端插件树（`bootStrap` / `coreLibrary` / `coreMindustry` / `wayzer` / `mapScript` / `lemon`） |
| `stats-web/` | `stats-web/` | 独立门户 Web（三页静态前端 + Windows PowerShell HttpListener 后端，默认 `127.0.0.1:8090`） |
| `email-service/` | `email-service/` | 仅主服本地发信服务（`127.0.0.1:40150`，阿里云 DirectMail，只发 QQ 邮箱验证码） |
| `updater/` | `updater/` | 生产自动更新器（主服 `main` / 分服 `mdc-do`、运行中热更新、自动备份与回滚） |
| `tools/` | `tools/` | 统一本地静态体检、冷启动与热重载调试 CLI（`mdt-dev.ps1`、`scan-nested-block-comment.ps1`） |
| `docs/` | `docs/` | 现行架构、工作流、调试运维手册与各系统规范文档 |
| `.scratch/` | `.scratch/` | 本地临时草稿箱（`.gitignore` 忽略，存放测试计划与排查笔记，永不入库） |

---

## 2. 运行环境与当前基线

- **游戏服务端**：Mindustry **v160.7** / MindustryX `prerelease-2026.10.10.B509`（`server-2026.10.10.B509.jar`）。
- **脚本引擎**：ScriptAgent4MindustryExt 3.4.0 维护者竞态修复构建（运行时标识 `ScriptAgent c2823c1`，位于 `mdtserver/config/mods/ScriptAgent4MindustryExt-3.4.0-allInOne.jar`）。
- **操作系统约束**：**仅支持 Windows**（`wayzer/reGrief/trafficMonitor.kts` 通过 Windows `netstat -e` 采集网卡累计出口字节；Web 门户与更新器均基于 Windows PowerShell 5.1 / 7+）。
- **服务端兼容性事实**：当前脚本树**不再硬依赖 MindustryX 专有包事件**（`trafficMonitor.kts` 已改用 `netstat -e`）；全仓仅 `wayzer/reGrief/limitLogicPacket.kts` 一处以可选反射读取 `mindustryX.events.SendPacketEvent`（缺失时安全降级）。生产与开发仍统一采用 MindustryX 构建作为当前运行基线。
- **脚本加载基线**（2026-11-28 `main` 实测）：共 169 脚本、加载 165、启用 160、出错 0；根指令 163。

---

## 3. 主服与分服分支架构

| 维度 | 主服 MDT DO（`main` 分支） | 分服 MDC DO（`mdc-do` 分支） |
|---|---|---|
| **产品定位** | 功能完整的主线正式服 | 客制化经济自由分服（高货币收入倍率、运营侧关闭部分帖子系统、账号宽松） |
| **账号验证** | 注册与老号补绑要求 QQ 邮箱验证码（已移除 `/captcha`），配备 `email-service` | 不部署邮箱绑定脚本与 `email-service`，保留原有 `/captcha` |
| **通用功能** | 所有玩法、安全、性能、Web、文档与修复均先合入 `main` | 随 `main` 同步通用功能与修复，排除邮箱专属路径（见 [docs/mdc-do-sync.md](mdc-do-sync.md)） |
| **生产更新** | `updater/update-mainserver.bat`（或仅插件 `update-mainserver-scripts.bat`） | `updater/update-subserver.bat`（默认指向 `mdc-do`） |

---

## 4. 模块与领域文档路由表（新会话定位入口）

> 任何新会话动工前，请按本表定位唯一对应的领域文档与脚本目录。新增系统文档时必须同步登记到本表。

### 核心流程与运维手册

| 主题 | 文档 | 说明 |
|---|---|---|
| 开发与协作工作流 | [workflow.md](workflow.md) | 信息分层、Issue 分级、单模块 5 步闭环、文档防退化纪律 |
| 本地调试与排障 | [dev-and-debug.md](dev-and-debug.md) | `mdt-dev.ps1` / `sync-repo.ps1` 用法、SA 3.4.0 编译陷阱、H2 查库、日志定位 |
| 生产部署与同步 | [deployment.md](deployment.md) | 自动更新器、运行中热更新 vs 停服、运行数据保护清单、回滚路径 |
| 分服同步规范 | [mdc-do-sync.md](mdc-do-sync.md) | `mdc-do` 客制化定位、同步清单与排除清单 |
| 当前未决事项 | [backlog.md](backlog.md) | 仅保留尚未完成或待真实客户端验证的事项 |

### 账号、权限、社区与经济

| 模块 | 主要脚本路径 | 权威领域文档 |
|---|---|---|
| 账号与认证 | `scripts/wayzer/user/account*.kts`、`serverFeatureSettings.kts` | [account-system.md](account-system.md) |
| QQ 邮箱绑定（主服） | `scripts/wayzer/user/emailBind.kts`、`emailOnboarding.kts`、`accountMigrate.kts` | [account-email-binding.md](account-email-binding.md) |
| 邮箱发信服务（主服） | `email-service/` | [email-service.md](email-service.md) / [../email-service/README.md](../email-service/README.md) |
| 信任、资历与 MDC 经济 | `scripts/wayzer/user/trust*.kts`、`seniority*.kts`、`playerTitle.kts` | [trust-system.md](trust-system.md) |
| 惩罚与检举体系 | `scripts/wayzer/ext/playerMute.kts`、`playerBuildBan.kts`、`cmds/voteOb.kts`、`voteMute.kts`、`user/punishmentReport.kts` | [punishment-system-design.md](punishment-system-design.md) |
| 数据库与持久化 | `scripts/coreLibrary/db/`、`DBConnector.kts`、`wayzer/lib/MdtStorage.kt`、`databaseFeatureSettings.kts` | [database-system.md](database-system.md) |
| 技能系统 | `scripts/wayzer/user/ext/skills*.kts`、`wayzer/user/skillShop.kts` | [skill-system.md](skill-system.md) |
| 商店系统 | `scripts/wayzer/user/shopCore.kts`、`titleShop.kts`、`skillShop.kts` | [shop-system.md](shop-system.md) |
| 成就系统 | `scripts/wayzer/user/achievement.kts` | [achievement-system.md](achievement-system.md) |
| 帖子系统 | `scripts/wayzer/user/forumPosts.kts` | [forum-posts.md](forum-posts.md) |
| Wiki 系统 | `scripts/wayzer/user/wiki.kts` | [wiki-system.md](wiki-system.md) |
| 排行榜 / 私聊 / Tips / 介绍 / 地区语言 | `scripts/wayzer/user/leaderboard.kts`、`ext/privateMsg.kts`、`user/tips.kts`、`ext/serverDesc.kts`、`ext/regionWelcome.kts` | [leaderboard-system.md](leaderboard-system.md) · [private-message.md](private-message.md) · [tips-system.md](tips-system.md) · [server-description.md](server-description.md) · [region-welcome-language.md](region-welcome-language.md) |

### 安全风控、性能、网络与地图玩法

| 模块 | 主要脚本路径 | 权威领域文档 |
|---|---|---|
| 安全风控与反刷 | `scripts/wayzer/security/securityGuard.kts`、`forceObAutoCleaner.kts` | [security-guard.md](security-guard.md) |
| DOS 黑名单 IP 白名单 | `scripts/wayzer/security/dosWhitelist.kts` | [dos-ip-whitelist.md](dos-ip-whitelist.md) |
| 批量框选拆除监控 | `scripts/wayzer/ext/idleBreakGuard.kts` | [idleBreakGuard.md](idleBreakGuard.md) |
| 逻辑绘图/显示器管控 | `scripts/wayzer/security/logicDrawGuard.kts` | [logic-draw-guard.md](logic-draw-guard.md) |
| 统一性能与压力保护 | `scripts/wayzer/map/performanceGuard*.kts`、`serverPressure*.kts`、`reGrief/trafficMonitor.kts` | [performance-guard.md](performance-guard.md) |
| 网络同步与完整重同步 | `scripts/wayzer/reGrief/worldResyncCoordinator.kts`、`syncThrottle.kts`、`coreUnitRespawnCompat.kts` | [v159-network-sync.md](v159-network-sync.md) |
| 外部 CP / Data Assets 热重载 | `scripts/wayzer/map/externalCpHotReload.kts`、`coreMindustry/contentsTweaker.kts` | [v159-data-assets-hot-reload.md](v159-data-assets-hot-reload.md) |
| 地图 DP 标签（`@dp`/`@nodp`） | `scripts/wayzer/map/mapDpTags.kts` | [map-dp-tags.md](map-dp-tags.md) |
| 地图脚本与投票存档 | `scripts/mapScript/**`、`wayzer/cmds/voteSave.kts` | [map-scripts.md](map-scripts.md) · [vote-save.md](vote-save.md) |
| 杂交单位玩法 | `scripts/mapScript/tags/hybrid.kts`、`wayzer/user/ext/skillsHybrid.kts` | [hybrid-system-design.md](hybrid-system-design.md) · [hybrid-unit-catalog.md](hybrid-unit-catalog.md) · [advanced-hybrid-turrets.md](advanced-hybrid-turrets.md) |
| 服务端版本兼容记录 | 跨脚本兼容桥 | [official-v159-compat.md](official-v159-compat.md) |

### 菜单、门户 Web 与更新器

| 模块 | 主要代码路径 | 权威领域文档 |
|---|---|---|
| `/help` 分区帮助菜单 | `scripts/coreMindustry/menu.kts` | [help-menu.md](help-menu.md) |
| MenuV3 服务端菜单与跨菜单导航 | `scripts/coreMindustry/lib/menuV3.kt`、`menuNav.kt` | [custom-menu.md](custom-menu.md) |
| 门户 Web（状态/上传/审核/日志） | `stats-web/**` | [web-portal.md](web-portal.md) / [../stats-web/README.md](../stats-web/README.md) |
| 服务器状态统计 JSON 契约 | `scripts/wayzer/ext/serverStats.kts` | [server-status-stats.md](server-status-stats.md) |
| 游戏内实时事件流 JSONL 契约 | `scripts/wayzer/ext/liveEventFeed.kts` | [live-events-feed.md](live-events-feed.md) |
| 生产自动更新器 | `updater/**` | [../updater/README.md](../updater/README.md) |

---

## 5. 长期架构不变量

1. **稳定性与状态正确性优先**：不破坏原生连接/重生/可靠包语义；关键状态（建筑血量、死亡、生成）绝不降级为不可靠 UDP，实体快照绝不改成可靠 TCP。
2. **网络压力与游戏性能分层**：世界流、资产、音乐、CP 传输尖峰不得触发清单位、暂停玩法或阻塞入服；极端换图仅在当前与平均 TPS 同时低于 5 且持续 120 秒时触发。
3. **失败可恢复，优先放行**：门控、队列、反射与异步任务必须有界、有超时、异常时回退或放行，绝不永久锁死主循环或新玩家入服。
4. **游戏主线程零阻塞 IO**：数据库查询与文件写入统一放 `Dispatchers.IO` 协程或事件队列单写者，热路径禁止同步 DB 与 `Groups.all` 全实体遍历。
5. **零外部依赖、单职责执行者**：脚本与门户 Web 不引入第三方外部依赖或遥测；性能清理、菜单导航、惩罚规则各自只保留一个权威执行者。
6. **核心模块库与运行数据隔离**：`coreLibrary/db` 严禁暴露 H2 驱动（防与 `KVStore` 产生 `LinkageError`）；`data/`、`external-cp/`、`cache/`、`web-data/`、`config.json` 与源码严格分离。

---

## 6. 已明确否决的需求（勿再提议）

以下方向已由维护者评估否决，除非维护者主动重新提出，否则不要在 Issue、PR 或文档中再次建议或实现：

- **禁言拦截扩展到 `/r` 或私聊命令**：禁言仅拦截普通聊天与 `/t`、`/a`、`/vote`（豁免 `quitOb`/`quitMute` 自救）、`/votekick`。
- **`/vote text`、`/vote sc` 增加冷却或敏感词过滤**：维持现状。
- **玩家名规范化、同名冒充检测、投票文案追加 UUID 或等级后缀**：维持现状。
- **IP 风控增加行为语义规则**（互骂/刷屏/票滥用判定等）：复杂度过高，维持现有规则。
- **自定义小音效切换至 Sound 通道或实现客户端重叠播放**：Mindustry v160 `TypeIO` 按 `short` 传输声音 ID，自定义 SoundAsset（ID ≥ 100001）会被截断为 `Sounds.none` 静默无声，而音乐通道受客户端单槽限制不可重叠；维持 `Call.playMusic(String)`，不做客户端改造。
- **将 6859 控制台端口暴露到公网**：生产如需热更新仅允许绑定 `127.0.0.1` / `localhost`。
