# MDT 数据库/持久化说明

## 当前结论

本项目已接入 ScriptAgent 自带的标准数据库体系：

- 数据库入口：`coreLibrary/DBApi.kts`
- 默认连接器：`coreLibrary/DBConnector.kts`
- 默认落盘位置：`mdtserver/config/scripts/data/h2DB.db`
- 默认数据库：H2 文件数据库
- 可切换 PostgreSQL：通过 `DBConnector.kts` 配置 `driverMaven/driver/url/user/password`

本轮不迁移旧 `@Savable` 数据；相关 MDT 自定义系统直接切换到新表。

## H2 文件数据库卡顿/冷启动处理

2026-07-01 新服 watchdog 日志显示，频繁“聊天正常但世界/单位短暂停住，恢复后玩家被拉回”的卡顿并非地图逻辑或单位逻辑，而是游戏主线程在 H2 MVStore 文件同步/关闭阶段阻塞：`FileChannelImpl.force` -> `FileStore.sync` -> `MVStore.compactFile/closeStore` -> `JdbcConnection.close`。同一时间慢事务日志出现 `getSetting cost=32129ms thread=HeadlessApplication`、`getMuteReason cost=3382ms thread=HeadlessApplication`，说明只要仍有数据库访问落在游戏线程，就会被新 VPS 的慢 fsync 放大成全服卡顿。

处理：

- `coreLibrary/DBConnector.kts` 的默认 H2 URL 改为 `jdbc:h2:H2DB_PATH;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`。
- 若旧配置仍写着 `jdbc:h2:H2DB_PATH`，脚本会在运行时自动为 H2 URL 补上缺失的 `DB_CLOSE_DELAY=-1` 与 `DB_CLOSE_ON_EXIT=FALSE`；PostgreSQL 等其它 JDBC URL 不受影响。
- `DB_CLOSE_DELAY=-1` 让 H2 文件库在最后一个连接关闭后仍保持打开，避免每个事务结束都触发数据库关闭、同步、压缩。
- `DB_CLOSE_ON_EXIT=FALSE` 避免 JVM 退出钩子再次执行 H2 关闭压缩；正常运行期间数据仍按事务提交落盘。
- 新增 H2 启动预热与周期保活，默认每 5 分钟在 `Dispatchers.IO` 执行一次轻量 `SELECT 1`，用于降低部分 VPS 磁盘空闲后首次访问的冷启动/唤醒卡顿。可通过 `h2KeepAliveMinutes <= 0` 关闭。

注意：该连接参数和保活逻辑需要**完整重启服务端**后生效；只热加载普通业务脚本不足以改变已创建的数据库连接。若重启后仍持续出现多秒级慢事务：

1. 如果日志集中在 `thread=HeadlessApplication`，继续把对应调用迁移到 `Dispatchers.IO`、缓存或批量写入。
2. 如果所有线程上的数据库操作都稳定 5-10 秒以上，优先检查 VPS 磁盘 IO、杀毒/网盘同步、虚拟化块存储空闲唤醒；长期方案是迁移到 PostgreSQL。

## 本轮新增/修改文件

- `mdtserver/config/scripts/wayzer/lib/MdtStorage.kt`
  - MDT 自定义系统的数据库表定义与读写函数。
  - 只做存储层，不放业务规则。
  - 已加入慢事务日志：单次事务超过 `200ms` 输出函数名/线程/耗时，超过 `1000ms` 标记为严重慢事务。
- `mdtserver/config/scripts/wayzer/mdtDatabase.kts`
  - 依赖 `coreLibrary/DBApi`。
  - 调用 `registerTable(*MdtStorage.tables())` 注册 MDT 表。
- `mdtserver/config/scripts/wayzer/module.kts`
  - 增加 `coreLibrary/DBApi` 依赖，使 `wayzer/lib/MdtStorage.kt` 可引用 Exposed 表类。
- `mdtserver/config/scripts/wayzer/user/accountAuth.kts`
  - 使用账号表完成注册/登录/改密/自动登录。
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
- 保存小型全局设置，例如 `account.guestForceObDate`、风险IP `account.ipRisk.index` / `account.ipRisk.<ip>`、同IP小号提示最近身份 `account.ipLast.<ip>`、同IP踢出计数 `account.ipKick.<ip>`、最近玩家面板 `playerInfo.recentPlayers.v1`、`tips.items`、`tips.seeded.v1`、`trafficMonitor.budgetMbps`、测试模式开关 `serverTestMode.enabled`。

账号登录后，业务系统的主体 ID 会从未登录时的游戏 UUID 切换为 `account:<id>`；因此赞踩、MDC、资历/在线时长、称号、成就等数据都会挂在账号主体上。注册新账号成功时，会额外把该游客 UUID 在 `MdtTrustProfiles` 中已落库的当前/累计 MDC 合并到新账号主体，并清理来源 MDC，避免注册前游玩获得的 MDC 丢失。

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
- 新系统需要持久化时，优先在 `MdtStorage.kt` 增加表和小型读写函数，再由对应业务脚本调用。
- 如果需要排行榜/统计，优先使用结构化表，不建议把新核心数据塞进字符串 KV。
- 旧 `@Savable` 数据本轮不迁移；账号系统也不迁移旧外部统一登录数据。如果未来需要迁移，另写一次性迁移脚本，不和业务脚本混在一起。
