# MDT 数据库/持久化说明

## 当前结论

本项目已迁移到 ScriptAgent 3.4 的标准 `Services` 数据库体系：

- 数据库 API 模块：`coreLibrary/db/lib/DBApi.kt`，包名 `coreLib.db.DBApi`
- 默认连接器：`coreLibrary/DBConnector.kts`
- 默认落盘位置：`mdtserver/config/scripts/data/h2DB.db`
- 默认数据库：H2 文件数据库
- 可切换 PostgreSQL：通过 `DBConnector.kts` 配置 `driverMaven/driver/url/user/password`

SA 3.4 不应继续把数据库 Provider 放在 `DBApi.kts` 内的旧 `ServiceRegistry` 对象中。新版本按模块/脚本使用隔离类加载器，同名 kts 对象可能在不同依赖脚本中各生成一份：连接器向其中一份 `provide`，业务脚本却从另一份读取，最终持续报 `No Provider for coreLibrary.DBApi.DB`。当前实现将 DB API 放入独立模块库，并使用 SA 3.4 的全局 `Services.get<Database>()` / `Services.provide(db)`。

`coreLibrary/db` 公共 API 模块只允许包含 Exposed，不得导入 H2 驱动。H2 JDBC 驱动仅由 `coreLibrary/DBConnector.kts` 动态加载，并通过 `Database.connect { DriverManager.getConnection(...) }` 创建连接；WayZer 业务模块只依赖 `coreLibrary/db`，不依赖具体连接器。原因是 `coreLibrary/extApi/KVStore` 另有独立的 `h2-mvstore` 版本：若公共 DB 模块或 WayZer 业务依赖链暴露 H2，`wayzer/user/lang` 会同时看到两个 ClassLoader 中的 `org.h2.mvstore.MVMap`，玩家进入时触发 `LinkageError: loader constraint violation`。

本轮不迁移旧 `@Savable` 数据；相关 MDT 自定义系统直接切换到新表。

## H2 文件数据库卡顿/冷启动处理

2026-07-01 新服 watchdog 日志显示，频繁“聊天正常但世界/单位短暂停住，恢复后玩家被拉回”的卡顿并非地图逻辑或单位逻辑，而是游戏主线程在 H2 MVStore 文件同步/关闭阶段阻塞：`FileChannelImpl.force` -> `FileStore.sync` -> `MVStore.compactFile/closeStore` -> `JdbcConnection.close`。同一时间慢事务日志出现 `getSetting cost=32129ms thread=HeadlessApplication`、`getMuteReason cost=3382ms thread=HeadlessApplication`，说明只要仍有数据库访问落在游戏线程，就会被新 VPS 的慢 fsync 放大成全服卡顿。

处理：

- `coreLibrary/DBConnector.kts` 的默认 H2 URL 改为 `jdbc:h2:H2DB_PATH;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`。
- 若旧配置仍写着 `jdbc:h2:H2DB_PATH`，脚本会在运行时自动为 H2 URL 补上缺失的 `DB_CLOSE_DELAY=-1` 与 `DB_CLOSE_ON_EXIT=FALSE`；PostgreSQL 等其它 JDBC URL 不受影响。
- `DB_CLOSE_DELAY=-1` 让 H2 文件库在最后一个连接关闭后仍保持打开，避免每个事务结束都触发数据库关闭、同步、压缩。
- `DB_CLOSE_ON_EXIT=FALSE` 避免 JVM 退出钩子再次执行 H2 关闭压缩；正常运行期间数据仍按事务提交落盘。
- H2 启动预热与周期保活默认开启，每 5 分钟在 `Dispatchers.IO` 执行一次轻量 `SELECT 1`，用于降低部分云服/VPS 磁盘或块存储休眠后首次访问的唤醒卡顿。
- 管理指令 `/diskwarmup [status|on|off|now|interval <1~1440>]` 可在运行中查看、启停、立即预热或调整间隔；权限 `coreLibrary.admin.diskWarmup`，默认 `@admin`。开关与间隔分别持久化为 `h2DiskWarmupEnabled` / `h2KeepAliveMinutes`。
- 预热只是针对云服磁盘休眠的运维补丁，不是 TPS 优化器；它不会关闭数据库连接，也不能修复本身已很慢的磁盘。自动与手动预热有原子互斥，不会并发叠加查询。

注意：H2 URL/驱动等连接参数需要**完整重启服务端**后生效；`/diskwarmup` 只动态控制已创建 H2 连接上的预热循环。若重启后仍持续出现多秒级慢事务：

1. 如果日志集中在 `thread=HeadlessApplication`，继续把对应调用迁移到 `Dispatchers.IO`、缓存或批量写入。
2. 如果所有线程上的数据库操作都稳定 5-10 秒以上，优先检查 VPS 磁盘 IO、杀毒/网盘同步、虚拟化块存储空闲唤醒；长期方案是迁移到 PostgreSQL。

## 数据库业务功能总开关

2026-08-01 将旧“服务器测试模式”进一步拆分为可独立停用的数据库业务功能。这里的“总开关”**不会物理关闭数据库连接**，而是统一暂停玩家可直接触发或会持续产生较多数据库访问的可选业务；账号登录、信任权限、封禁、禁言、IP 风控和性能保护始终保留，避免关闭数据库业务时同时失去安全与运维能力。

当前可管理的九项业务：

- 在线时长自动记录；
- 成就系统与自动检测；
- 数据库 Wiki；
- 玩家 MDC 转账与红包；
- 最近玩家加入/离开记录及离线管理面板；
- 信任等级自动晋升/降级检测；
- 资历等级自动晋升/调整检测；
- 排行榜；
- 玩家信息面板中的称号、在线时长、赞踩、认可、MDC 等数据库资料显示。

持久化与兼容：

- 总开关 `databaseBusinessFeaturesEnabled` 默认开启，使用 ScriptAgent `config.key` 落盘；它作为覆盖层，不改写九个子开关的原值，重新开启后恢复原先配置。
- 子开关使用现有 `MdtSettings`，键名为 `serverFeatures.database.<feature>.enabled`，缺失时全部按 `true` 补齐。
- 不新增表、列或 DDL；旧生产数据库可直接加载。已有成就、Wiki、排行榜来源数据、红包和最近玩家记录不会因关闭开关而删除。
- 红包过期退款维护即使在“转账/红包”关闭时仍继续，避免已发红包的资金永久冻结。
- 在线时长关闭时会先精确结算到切换时刻；停用期间不记录，也不会在重新开启后补记。管理员手动设置/增加在线时长不受影响。
- 信任/资历开关只停止自动脏标记、批处理与周期复查；管理员手动检查、设置和锁定仍可使用。
- 玩家资料显示关闭后，玩家交互、处罚、观战、禁建等按钮继续存在；底层仍读取必要信任等级，防止通过关闭资料显示绕过权限边界。
- Wiki、成就、排行榜、最近玩家和资料缓存均在关闭时清理；异步读取使用状态/代次复核，不能在关闭后以“晚到任务”重新回填缓存或继续写库。

4级管理根指令（控制台也可使用）：

- `/databasefeatures [status|menu|on|off]`
- `/playtimerecording [on|off]`
- `/achievementtoggle [on|off]`
- `/wikitoggle [on|off]`
- `/mdctransfertoggle [on|off]`
- `/recentplayerrecording [on|off]`
- `/trustpromotiontoggle [on|off]`
- `/senioritypromotiontoggle [on|off]`
- `/leaderboardtoggle [on|off]`
- `/playerprofilestats [on|off]`

`/serverfeatures` 菜单已增加数据库业务子菜单；上述根指令会被 `/help` 的权限过滤搜索自动索引。总开关与九个子开关默认均为开启。

## 本轮新增/修改文件

- `mdtserver/config/scripts/wayzer/lib/MdtStorage.kt`
  - MDT 自定义系统的数据库表定义与读写函数。
  - 只做存储层，不放业务规则。
  - 已加入慢事务日志：单次事务超过 `200ms` 输出函数名/线程/耗时，超过 `1000ms` 标记为严重慢事务。
- `mdtserver/config/scripts/wayzer/mdtDatabase.kts`
  - 依赖 `coreLibrary/db`。
  - 调用 `DBApi.registerTable(*MdtStorage.tables())` 注册 MDT 表。
- `mdtserver/config/scripts/wayzer/module.kts`
  - 依赖 `coreLibrary/DBConnector`，确保数据库模块与连接器进入 WayZer 业务依赖链。
- `mdtserver/config/scripts/wayzer/user/accountAuth.kts`
  - 使用账号表完成注册/登录/改密/自动登录，并按服务器设置执行注册在线要求与已绑定默认信任/资历等级下限。
- `mdtserver/config/scripts/wayzer/user/serverFeatureSettings.kts`
  - 缓存并管理服务器功能设置；所有状态写入现有 `MdtSettings`，不新增表或列。
- `mdtserver/config/scripts/wayzer/lib/DatabaseFeatureSettings.kt`
  - 定义九项数据库业务枚举、稳定服务接口和运行时状态变更事件。
- `mdtserver/config/scripts/wayzer/user/databaseFeatureSettings.kts`
  - 加载总开关与九个子开关、提供状态服务并广播有效状态变化；不负责具体业务规则。
- `mdtserver/config/scripts/wayzer/lib/ServerFeatureSettings.kt`
  - 向帖子、账号、口碑、认可、信任和结算脚本提供稳定的跨脚本设置接口。
- `mdtserver/config/scripts/wayzer/user/accountGuestControl.kts`
  - 使用 `MdtSettings` 保存“今日未登录玩家强制观战”的日期。
- `mdtserver/config/scripts/wayzer/user/accountIpGuard.kts`
  - 使用 `MdtSettings` 保存风险IP索引/到期时间；`MdtIpAccountBindings` 仅保留旧版绑定清理兼容。
- `mdtserver/config/scripts/wayzer/user/shopCore.kts`
  - 使用商店购买统计表记录玩家购买次数。
- `mdtserver/config/scripts/wayzer/user/titleShop.kts`
  - 使用称号商店商品表保存可动态修改的商品配置。
- `mdtserver/config/scripts/wayzer/user/skillShop.kts`
  - 使用玩家技能表保存已购买/解锁的商店技能。
- `mdtserver/config/scripts/wayzer/user/forumPosts.kts`
  - 使用帖子表和评论表保存玩家交流内容。
- `mdtserver/config/scripts/wayzer/user/tips.kts`
  - 使用 `MdtSettings` 保存 Tips 列表与默认导入标记。
- `mdtserver/config/scripts/wayzer/map/performanceGuard.kts`
  - 使用 `MdtSettings` 保存性能优化模式。
- `mdtserver/config/scripts/wayzer/map/performanceGuardExperimental.kts`
  - 使用 `MdtSettings` 保存实验性性能优化的前置模式、处理器关闭记录和兜底换图绕过标记。
- `mdtserver/config/scripts/wayzer/reGrief/trafficMonitor.kts`
  - 使用 `MdtSettings` 保存实验性上行预算。

## 现有表分组

### 账号/绑定

- `MdtAccounts`
  - QQ 账号名、密码哈希、账号状态、创建时间、最近登录时间。
- `MdtAccountBindings`
  - 当前保存 QQ 绑定记录；后续可扩展其他绑定类型。
- `MdtPlayerSubjects`
  - 保存游戏 UUID 与账号主体 `account:<id>` 的映射。
  - 记录最近名字、IP、USID、登录时间。
  - 表版本已提升到 `2`，用于补充账号系统所需字段。
- `MdtIpAccountBindings`
  - 旧版 `1IP=1账号` 绑定表；当前不再作为硬限制，只用于兼容清理与排查。
  - 记录最近名字、UUID、USID、首次绑定时间、最近出现时间。
- `MdtSettings`
  - 保存小型全局设置，例如 `account.guestForceObDate`、风险IP `account.ipRisk.index` / `account.ipRisk.<ip>`、同IP小号提示最近身份 `account.ipLast.<ip>`、同IP踢出计数 `account.ipKick.<ip>`、最近玩家面板 `playerInfo.recentPlayers.v1`、`tips.items`、`tips.seeded.v1`、`trafficMonitor.budgetMbps`。
  - 服务器功能键：`serverFeatures.mdcSettlementMultiplier`、`serverFeatures.forumEnabled`、`serverFeatures.registrationOnlineRequirementEnabled`、`serverFeatures.socialActionsEnabled`、`serverFeatures.defaultBoundTrustLevel`。缺失键会按 `1/true/true/true/1` 安全默认值补齐。旧键 `defaultBoundTrustLevel` 保留不改，新语义为信任/资历共同下限；`serverFeatures.defaultBoundLevelSeniorityMigrated` 记录旧数据库是否已完成一次性资历补齐。
  - 数据库业务子开关：`serverFeatures.database.playTimeRecording.enabled`、`achievement.enabled`、`wiki.enabled`、`mdcTransferAndRedPacket.enabled`、`recentPlayerRecording.enabled`、`trustPromotion.enabled`、`seniorityPromotion.enabled`、`leaderboard.enabled`、`playerProfileStats.enabled`；完整前缀均为 `serverFeatures.database.`，缺失时默认开启。
  - `serverTestMode.enabled` 仅作为遗留兼容键保留；新脚本加载时强制写为 `false`，不再驱动任何账号或MDC逻辑。

账号登录后，业务系统的主体 ID 会从未登录时的游戏 UUID 切换为 `account:<id>`；因此赞踩、MDC、资历/在线时长、称号、成就等数据都会挂在账号主体上。注册新账号成功时，会额外把该游客 UUID 在 `MdtTrustProfiles` 中已落库的当前/累计 MDC 合并到新账号主体，并清理来源 MDC，避免注册前游玩获得的 MDC 丢失。

默认绑定等级批量调整复用 `MdtAccounts`、`MdtTrustProfiles` 与 `MdtSeniorityProfiles`：

- 不执行 DDL，不要求旧数据库预先迁移表结构；
- 以 `account:<id>` 为账号主体，按每批 400 个 UID 查询/更新，避免数据库参数数量上限；
- 整次设置写入、信任/资历缺失资料插入和低等级提升位于同一个事务，失败整体回滚；
- 只提升缺失或低于新默认值的账号资料，不降级现有玩家，也不会授予信任 `3+`、`3++`、`4` 或资历 `4`；已锁定的资历若低于全局默认下限，仍会被抬高，但锁定状态不变；未锁定资历的自动检测也会以该默认值作为已绑定账号下限；
- 存储层分别返回信任/资历的扫描、插入、提升计数，不把大规模 UID 列表搬回游戏线程；业务层清空两套等级缓存并只刷新当前在线玩家。
- 旧数据库首次运行新脚本时会执行一次兼容扫描；完成后写入标记，下次启动不重复全库扫描。若中途失败，不写标记，下次启动安全重试。

自动登录只检查游戏 UUID + USID，不检查 IP。`last_ip` 仍保留为最近登录记录字段，但不作为登录条件。

密码哈希已实现于 `wayzer/user/accountPassword.kts`：使用 JDK 自带 `PBKDF2WithHmacSHA256`、随机盐、`120000` 次迭代，格式类似 `pbkdf2$iterations$salt$hash`。管理员重置密码时输入明文新密码，脚本内部哈希保存；游戏内重置通过输入框收集新密码，避免把明文密码写进聊天/服务器日志。

账号删除/注销会删除 `MdtAccounts`、对应 `MdtAccountBindings`、对应 `MdtIpAccountBindings`，并清空该账号主体 `account:<id>` 及已绑定游戏 UUID 主体下的业务数据，包括MDC、资历/在线时长、赞踩、认可、称号、成就、技能、随机形态、禁言、帖子/评论、红包记录等。

### 信任/MDC

- `MdtTrustProfiles`
  - `manual_level_code`
  - `level_locked`
  - `current_points`
  - `total_points`

- `MdtSeniorityProfiles`
  - `level_code`：资历等级 `0-4`，自然晋升最高写入 `3`。
  - `level_locked`：是否锁定资历自动调整。
  - `play_millis`：累计在线时长；指令以小时为单位显示/设置。

### 赞/踩

- `MdtReputationStats`
  - 被赞、被踩、送出赞、送出踩总数。
- `MdtReputationDaily`
  - 每日每个发起者对每个目标的赞/踩计数。
  - 用于每日额度限制。

### 认可

- `MdtRecognitionStats`
- `MdtRecognitionPairs`
- `MdtRecognitionDaily`

用于保存被认可/认可他人总数、每对玩家是否已认可过，以及每日认可限制。

### 称号

- `MdtTitleDefinitions`
- `MdtPlayerTitles`
- `MdtEquippedTitles`

系统内置 `guest` 游客称号仍在脚本内定义；成就、商店等后续发放的动态称号会写入数据库。

### 商店

- `MdtTitleShopItems`
  - 称号商店商品配置：商品 ID、称号实际内容或 `custom:<长度>`、售价、等级要求、认可数要求、启用状态。
- `MdtShopPurchaseStats`
  - 玩家在各商店购买某商品的次数统计，用于后续成就、排行、消费统计或限购规则。
- `MdtPlayerSkills`
  - 玩家已购买/解锁的技能 code 与来源。

称号商店商品不硬编码在菜单里，管理员可通过 `/titleshopadmin` 修改；首次启用且商品表为空时会写入预设商品。
技能商店当前商品为脚本内预设，玩家购买结果写入 `MdtPlayerSkills`。

### 成就

- `MdtPlayerAchievements`
  - 保存玩家已完成成就 code、来源与完成时间。
- `MdtCustomAchievements`
  - 保存管理员自定义成就定义：名称、启用/隐藏状态、条件类型/条件值、MDC奖励、称号奖励。

### 随机形态/禁言

- `MdtRandomForms`
- `MdtMutedPlayers`

### 帖子

- `MdtForumSections`
  - 保存帖子分区 code、显示名、介绍、排序和启用状态。
- `MdtForumPosts`
  - 保存帖子所属分区、作者、标题、正文、置顶状态、评论数、从帖子入口产生的作者赞踩成功点击数、创建/更新时间。
  - 表版本已提升到 `2`，用于补充帖子分区字段 `section_code`。
- `MdtForumComments`
  - 保存帖子评论。
- `MdtForumAuthorStats`
  - 保存玩家历史累计发帖数，用于“首次发贴”等成就和后续排行/统计扩展。

帖子系统的“为作者点赞/点踩”实际写入玩家赞踩表 `MdtReputationStats` / `MdtReputationDaily`，帖子表内的作者赞踩成功点击数只用于旧帖清理排序，不作为独立赞踩系统。

帖子系统还会在 `MdtSettings` 中保存：

- `forum.stats.totalPosts`：历史累计总发帖数。
- `forum.stats.initialized.v1`：发帖统计初始化标记。
- `forum.lockedPostIds`：被锁定、不会被自动清理的帖子 ID 列表。
- `forum.postChangeHistory`：最近 10 条玩家修改/删除帖子记录。



### MDC红包

- `MdtRedPackets`
  - 红包主体：发包者、总额、剩余额度、总份数、剩余份数、留言、状态、创建/过期时间。
- `MdtRedPacketClaims`
  - 抢红包记录：红包 ID、领取者主体、领取者名字、领取MDC、领取时间。

红包逻辑只改变“当前MDC”，不会增加“累计MDC”。过期红包的剩余MDC会退回发包者当前MDC。

红包抢完结算时会广播领取者列表与手气王排行；未登录游客不能抢红包，需要先登录或注册。

### 性能优化

常驻/实验性性能优化系统使用 `MdtSettings` 保存小型全局状态：

- `performanceGuard.mode`：当前性能优化模式，`normal` / `off` / `experimental`。
- `performanceGuard.experimental.previousMode`：开启实验性优化前的模式，用于关闭或兜底换图后恢复。
- `performanceGuard.experimental.disabledLogicPositions`：实验性优化关闭过的逻辑处理器位置列表。
- `performanceGuard.experimental.forceChangingMap`：实验性兜底换图期间临时绕过地图筛选拦截。
- `serverPressure.tpsThresholds`：管理员在游戏内设置的 TPS 压力阈值，格式为 `L1,L2,L3,L4,恢复`。
- `trafficMonitor.budgetMbps`：实验性上行预算，默认 18 Mbps。

## 已切换为数据库持久化的脚本

- `wayzer/user/trustLevel.kts`
- `wayzer/user/trustPoint.kts`
- `wayzer/ext/playerReputation.kts`
- `wayzer/ext/playerRecognition.kts`
- `wayzer/user/playerTitle.kts`
- `wayzer/user/achievement.kts`
- `wayzer/user/shopCore.kts`
- `wayzer/user/titleShop.kts`
- `wayzer/user/skillShop.kts`
- `wayzer/ext/playerRandomForm.kts`
- `wayzer/ext/playerMute.kts`
- `wayzer/user/forumPosts.kts`

## 维护边界

- 业务规则仍放在各功能脚本里，不要把晋升、成就、赞踩限制等规则塞进 `MdtStorage.kt`。
- 新系统需要持久化时，优先在 `MdtStorage.kt` 增加表和小型读写函数，再由对应业务脚本调用；仅少量全局开关优先复用 `MdtSettings`，避免无必要 DDL。
- 如果需要排行榜/统计，优先使用结构化表，不建议把新核心数据塞进字符串 KV。
- 旧 `@Savable` 数据本轮不迁移；账号系统也不迁移旧外部统一登录数据。如果未来需要迁移，另写一次性迁移脚本，不和业务脚本混在一起。
