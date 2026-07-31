# `/help` 分区帮助菜单

## 入口

- `/help`：打开新版分区帮助菜单。
- `/helps`：`/help` 的兼容别名。
- `/help <页码>`：打开传统完整指令列表的指定页。
- `/help -v`：在有 `command.detail` 权限时打开带详细信息的完整指令列表。
- `/vote`：打开投票子指令列表；每项只显示一行带颜色的简短说明和一行完整指令，普通视图不再堆叠别名/脚本等低频信息。

## 当前分区

- **玩家指令**：账号注册/登录/改密/注销、玩家信息、私聊、成就、排行榜、帖子列表、Wiki、Tips提示、服务器点歌、称号、随机变换形态、认可、MDC查询/转账/红包、资历等级/在线时长、地图列表/详情、集合、PVP全体聊天、观察者、跨服传送、积分板、语言、历史查询、自动存档槽、颜色/粒子/像素画工具、服务器状态、估算上行、服务器压力、3+队伍管理等常用玩家功能。
- **投票指令**：`/vote` 入口及投降/结束、立即换图（51%同意）、下次自动轮换地图（51%同意）、回档、踢出、旧 `/votekick` 兼容、强制观战、创建存档、跳波、暂停/调整/取消暂停波次、清理建筑记录、自定义文本投票、外部 CP 加载/卸载、banmap、今日 PVP、性能优化、暂停/继续游戏、投票击杀单位、标准无限火力/无限火力promax、反应堆爆炸开关、当前局纯净模式开关、一票否决等相关入口。
- **帖子列表**：快捷打开 `/posts`，作为根菜单直接入口。
- **商店列表**：快捷打开 `/shop`。
- **技能指令**：快捷打开 `/skill`。
- **管理指令**：仅显示当前玩家有权限看到的管理项，例如安全风控、服务器功能设置、数据库业务总开关与九个子开关、管理员频道、服务器介绍轮播、服务器小音效、服务器点歌、管理员技能入口、设置脚下方块/地板、区域填充、单位效果、名字后缀标记、击杀/传送选择器单位、封禁账号/IP、最近玩家面板、CP列表/外部CP、世界处理器/静默世界处理器、换图、资源站代理、加载/关闭/查看地图脚本、结束游戏、强制观战、强制观战高人数清理、禁言/禁建、信任等级/资历等级/在线时长/MDC/账号/IP防熊/地区查询/地图筛选/性能优化管理、暂停/继续游戏、上行预算、服务器压力、人数上限、Wiki/帖子保护与回收站、ScriptAgent 脚本控制等。
- **其他指令**：点击后动态加载当前可见但尚未纳入固定分区的指令。
- **Wiki列表**：快捷打开 `/wiki`；如果脚本未加载才会提示暂未开放。
- **搜索指令**：输入指令名、别名、中文说明、usage 或脚本 ID 关键字，列出当前玩家实际有权查看/使用的匹配项。
- **完整指令列表**：点击后动态加载传统完整列表兜底入口。

## 性能注意

- 顶层 `/help` 只构建固定分区，不再立即扫描全部命令权限，避免菜单打开时造成全服卡顿。
- “其他指令”和“完整指令列表”仍需要扫描当前可见命令，因此只在玩家点击对应入口时懒加载。
- “其他指令”和“完整指令列表”只构建当前页按钮/文本，不再提前把全部指令转成菜单选项。
- “搜索指令”只在玩家主动输入关键字后扫描，并复用指令自身的 `Hidden` / Permission 可见性判断；普通玩家不会因搜索看到仅4级/admin可用的管理指令，有限协管也只会看到自身实际权限项。
- 搜索会动态扫描根指令的名称、别名、描述、usage 与脚本 ID；因此新增普通根指令只要正确填写描述/别名/权限，就会自动进入搜索，不需要每次修改搜索脚本或新建专项文档。只有未来引入非根指令/特殊容器时，才需单独设计索引适配。
- 固定分区条目会在标题前显示对应快捷指令，例如 `/account,账号系统`；完整指令列表只显示“指令（别名）/ 用法”，不再把长描述塞进按钮，避免菜单过宽。
- `/vote` 子指令列表额外按投票类型着色：例如踢出/结束类偏 `[red]`，地图/存档类偏 `[green]`，CP 类偏 `[purple]`，SuperChat 为 `[pink]`；用法行保留 `[status]`、`[波数]` 等可选参数，仅清理真正的颜色标记。
- 玩家指令/管理指令固定分区的标题已加入颜色辅助阅读：`[cyan]` 偏核心/安全入口，`[pink]` 偏内容/社区展示，`[green]` 偏地图/服务器运维与状态，`[yellow]` 偏等级/资料/经济，`[red]` 偏处罚或危险操作，`[light_gray]` 偏底层低频工具。
- 权限判断在单次页面构建中按权限节点缓存，减少同一页刷新时的重复权限检查开销。
- 如果后续添加新的长文本/大列表菜单，应优先分页、懒加载，不要在游戏主线程一次性生成所有内容。

## 维护方式

分区表在：

```text
mdtserver/config/scripts/coreMindustry/menu.kts
```

新增常用指令后，建议把它加入对应的 `playerHelpEntries`、`voteHelpEntries` 或 `adminHelpEntries`。

注意：`MenuBuilder` 的按钮回调不会保留 `/help` 命令处理时的 `CommandContext` 隐式上下文；需要在命令处理阶段把权限/可见指令加载逻辑显式封装后传给菜单，避免帮助菜单脚本编译失败并引发依赖脚本反复加载。

本次已接入：

- `/points`、`/pay`、`/redpacket`、`/grab`：MDC查询、转账与红包；单个红包最多 500 MDC；MDC变动时会私聊提示本人。
- `/seniority`、`/资历`、`/playtime`：查看资历等级、累计在线时长与下一资历等级条件。
- `/captcha`、`/register`、`/login`、`/changepassword`、`/deleteownaccount`：注册验证码、注册、登录、改密与注销；登录不需要验证码。
- `/msg`、`/r`：私聊与回复最近私聊对象。
- `/rank`：排行榜入口，查看MDC、帖子、赞踩、认可排行。
- `/tips`：玩家随机 Tips。
- `/playerinfo`、`/pinfo`：打开玩家信息/交互面板。直接强制未登录游客观战由信任2级提高到3级；非管理员成功操作后默认120秒冷却，且只能解除自己通过面板施加的记录。
- `/music`、`/点歌`、`/bgm`：服务器点歌菜单；`/music vote <网易云歌曲/DJ ID或分享链接>` 或直接输入ID/链接会发起60秒点歌投票，点歌投票期间会阻止其他普通投票发起；玩家像标准投票一样直接在聊天发送单个 `1` / `.` / `0` 表示同意/中立/拒绝（也兼容 `/music yes|neutral|no`）。只有同意者进入排队同步队列，同步完成后才播放。点歌冷却的同意率只按已经明确表态的玩家计算，未表态玩家单独显示但不进入分母。`/music stop` 停止自己的当前音乐；3++可使用 `/music stopall|cancel` 停止全服音乐/取消投票，仅4级/admin可 `/music limit size|duration|cache <值>` 修改限制。
- `/tipadmin`：管理 Tips 内容。
- `/vote nextmap <地图ID>`：需51%同意；投票通过后在下一次自动轮换地图时换到指定地图；期间手动换图不会清空该计划。
- `/vote perf`、`/vote xperf`：投票开启/关闭保守/实验性性能优化。
- `/vote pause`、`/vote resume`：投票暂停/继续当前游戏。
- `/vote gameOver`、`/vote rollback`、`/vote skipWave`、`/vote clear`、`/vote text`：常用基础投票入口。
- `/vote map <地图ID>`：需51%同意；发起立即换图投票，5赞/5反不再通过，需6赞/5反这类超过半数的赞成票。
- `/vote pauseWave [秒数]`、`/vote setWave <波次>`、`/vote resumeWave`：投票暂停波次、调整当前波次或取消暂停波次；PVP 模式禁用波次控制。
- `/vote cp load <文件名|编号>`、`/vote cp unload <文件名|编号|all>`：投票加载/热重载或卸载 `scripts/external-cp/` 下的外部 JSON/HJSON/JSON5 CP 或 v159 Data Assets ZIP，加载/卸载均需 70% 同意；超过慢同步阈值的大文件会拉长分批同步间隔而非直接拒绝。
- `/vote reactor <on|off|status>`：投票开启/关闭反应堆爆炸或查看当前状态。
- `/vote pure`、`/vote pureoff`：投票立即开启/关闭当前这局纯净模式，不再排队未来局数；关闭会恢复开启前的技能限制标签。
- `/votekick <玩家>`：兼容旧原版入口，已归入投票指令分区；内部仍重定向到强制观战投票。
- `/perf`、`/xperf`：3+级/管理员管理性能优化。
- `/traffic`、`/pressure`、`/tickwatchdog`、`/gamepause`：查看上行/压力、主线程卡顿诊断、设置 TPS 压力阈值与管理暂停状态。
- `/ipguard`、`/ipregion`：IP防熊管理与玩家地区查询。
- `/security`：安全风控管理，查看/设置风控模式、IP封禁、聊天/菜单/连接限速状态。
- `/serverfeatures`、`/features`、`/serverconfig`：仅4级/admin或控制台可查看服务器功能总览/打开菜单；菜单内已加入数据库业务功能子菜单，旧子指令仍兼容。
- `/mdcmultiplier`、`/forumtoggle`、`/registerrequirement`、`/socialactions`、`/defaultboundlevel`：拆分后的五个服务器功能管理根指令；分别管理结算MDC倍率、帖子、注册一小时要求、赞踩认可和已绑定玩家默认信任/资历下限。它们会自动被“搜索指令”索引。
- `/databasefeatures`：查看、打开菜单或统一暂停数据库业务功能；只覆盖可选玩家业务，不关闭数据库连接，也不影响账号、权限、封禁、禁言、IP风控与性能保护。
- `/playtimerecording`、`/achievementtoggle`、`/wikitoggle`、`/mdctransfertoggle`、`/recentplayerrecording`、`/trustpromotiontoggle`、`/senioritypromotiontoggle`、`/leaderboardtoggle`、`/playerprofilestats`：九个4级数据库业务子开关。全部是根指令，会自动进入权限过滤的“搜索指令”；普通玩家不会看到这些管理项。
- `/forceobclean`：在线人数过高时清理已登录且被强制观战的普通玩家；3++协管可查看状态/执行单次清理，4级/admin可修改长期开关。
- `/logicdraw`：管理画布/逻辑显示器/逻辑绘图方块，可用于处理不适当显示内容风险；`roundoff/roundon/roundclear` 为仅本局覆盖。
- `/blockban ban <方块ID>`、`/blockban unban <方块ID>`、`/blockunban <方块ID>`：管理员本局单独禁用/解禁某个建筑方块。
- `/vote save`：投票创建当前游戏存档；投票存档槽为 `106-110`，可在 `/slots` 查看。
- `/vote killunits`：投票击杀所有单位。
- `/vote infinitefire`：投票开启120秒标准无限火力。
- `/vote infinitefirepromax`：信任2级及以上可投票开启120秒无限火力promax。
- `/achat`、`/ac`：4级/admin 管理员频道，仅4级/admin可见。
- `/descadmin`：管理服务器列表介绍轮播，可新增/修改/启停文案并立即切换。
- `/skill godmenu`：信任4级/已登录原生admin的神权菜单；在 `/skill` 主菜单中与各技能分类并列，用于调整当前地图倍率、太阳能/拆除返还倍率、当前星球/全部科技限制、编辑器模式与无限火力promax；`/skill infinitefire off` 可手动关闭脚本开启的无限火力promax。
- `/setBlock <方块ID> [队伍ID/队伍名]`：管理员设置脚下方块并可指定队伍；队伍留空默认当前队伍，例如 `/setBlock power-node`、`/setBlock duo crux`。
- `/setFloor <地板ID>`：管理员设置脚下地板，例如 `/setFloor sand`。
- `/fill <block|floor> <x1> <y1> <x2> <y2> <目标方块/地形> [-cover|-keep]`：管理员批量填充建筑层或地板层；坐标可用 `~` 表示自己当前单位所在格；`/fill block ... air` 可清理建筑层；多格建筑会按 Mindustry 锚点映射分格铺设，`-keep` 会保留已有建筑。
- `/suffixmark hide|clear|set <标记>`：仅4级/admin可隐藏/恢复/自定义名字后缀；3++会显示相同管理图标，但不能使用该指令。
- `/kill <玩家UUID/三位UID/#游戏ID/名字|选择器>`：管理员击杀指定玩家当前单位或选择器匹配单位；主推 Minecraft 风格选择器：`@e` 全部单位、`@e[unit=mono]` 所有 mono、`@e[team=2]` 2队所有单位、`@e[team=2,unit=mono]` 2队所有 mono、`@a` 所有玩家附身单位、`@s` 自己；兼容旧写法 `@t[2]`、`@u[mono]`、`@t[2,mono]`。
- `/tp`：管理员传送指令。无参数传送自己到鼠标；`/tp <x> <y>`、`/tp <x,y>` 或 `/tp ~ ~` 传送自己当前单位到地图格坐标；`~` 表示自己当前单位所在轴；`/tp <玩家UUID/短ID/名字>` 传送自己到该玩家；`/tp <玩家1|选择器> <玩家2|选择器>` 传送玩家/选择器单位到目标玩家/选择器位置；`/tp <玩家|选择器> <x> <y>` 可批量传送到地图格坐标；选择器同 `/kill`，目标选择器取第一个有效单位作为坐标。
- `/effect [选择器|玩家UUID] <效果ID> [叠加数] [秒数]`：管理员为选择器或指定玩家当前单位添加可叠加状态效果；无选择器时默认自己，默认持续120秒，例如 `/effect @e[team=2,unit=mono] fast 3`；`/effect [目标] clear [效果ID]` 可清除指定效果，不写效果ID则清除全部状态效果。
- `/mapcmd <地图脚本指令>`：打开当前地图脚本提供的特定指令，例如地图脚本 `/shop` 与服务器商店冲突时可用 `/mapcmd shop`。
- `/team [队伍ID] [玩家ID]`：3+级/4级可调整自己的队伍，指定他人仍需管理员权限；自换队也会全服广播，列队伍/切队伍不再受地图 `@banTeam` 标签限制。
- `/host [地图ID]`、`/gameover [队伍]`：3++/4级/admin强制换图或结束对局；3++需在15秒内重复输入确认，成功后共享5分钟冷却。
- `/banX <3位ID> <分钟> <原因>`、`/unbanX <玩家3位ID/UUID/账号UID|封禁ID>`：3++/4级封禁/解封玩家账号主体；3++只能处理低于3++的玩家、最长7天，且只能解除自己的封禁。
- `/banip <在线玩家3位ID/#游戏ID/名字> [分钟] [原因]`：3++/4级按在线玩家封禁其当前 IP；3++目标/时长边界与 `/banX` 相同。
- `/banips`、`/unbanip <ip>`：查看当前 IP 封禁列表（含 UUID/玩家名）并解除 IP 封禁；3++只能解除自己施加的记录。
- `/banlist`、`/bans`、`/封禁列表`：统一分页查看未到期的玩家/账号封禁与 IP 封禁，显示原因、剩余时长、关联ID/UUID与操作人；点开条目可立即解封。
- `/recentplayers`：3++/4级/admin 查看最近80名玩家，离线玩家也可打开面板并在自身层级边界内封禁账号或最近 IP。
- `/buildban <玩家id/3位id/#游戏id> [理由]`、`/buildunban <玩家id/3位id/#游戏id>`：禁止/解除在线玩家建造与拆除；玩家信息面板执行时可输入分钟数作为临时禁建，留空为永久。
- `/setseniority`、`/lockseniority`、`/setplaytime`、`/addplaytime`：管理玩家资历等级（手动设置会锁定）、资历锁与累计在线时长。
- `/cp`、`/externalcp`、`/worldprocessor <status|on|off|edit on|edit off|cp>`、`/wpq <status|on|off|edit on|edit off>`：`/cp dp` 查看v159完整Data Assets，`/cp files` 查看服务器CP，`/cp load <文件名|编号>` 可不经投票快速加载；属性Patch卸载仍用独立Patch编号。`/externalcp` 管理同一外部CP运行态；`/wpq` 为静默世界处理器控制。
- `/resourceproxy`：开关/设置地图资源站请求的本机代理，例如 `/resourceproxy set 7890 http`、`/resourceproxy set 10808 socks`。
- `/adaptiveplayerlimit`：查看/管理自适应人数上限；默认从服务端启动时人数上限开始，接近满员且上行压力低时每轮扩容，回收带缓冲防止抖动，原生管理员也不再超员插队。
- `/diskwarmup [status|on|off|now|interval <分钟>]`：管理H2云服磁盘预热/保活，减少磁盘或块存储休眠后首次数据库读写卡顿；不是TPS优化器。
- `/ScriptAgent` 及 `/ScriptAgent scan|list|load|hotReload|enable|disable|unload|config|permission|vars`：已归入管理指令高级区；此前这些控制项不是根指令，菜单快捷入口已改为正确的 `/ScriptAgent ...` 子指令形式。
- `/loadmapscript`：管理员手动尝试加载 `scripts/mapScript/<id>.kts`，例如 `/loadmapscript 14668`。
- `/unloadmapscript`、`/mapscripts`：管理员手动关闭指定地图脚本/模式，或列出当前启用的地图脚本。关闭时会显式创建 ScriptAgent 事务、检查最终状态，且不额外扫描磁盘；已实测 `tags/flood` 与 `14668` 可加载后再正常停用。
- `/posts`、`/posts trash`：帖子管理/回收站快捷入口；保护锁在帖子详情页内设置或解除。
- `/wikiadmin`、`/wikiadmin trash`：Wiki 管理/回收站快捷入口；保护锁在 Wiki 页面或管理页内设置或解除。

没有加入固定分区但当前玩家可见的指令，会在玩家点击“其他指令”后动态出现在该列表中。
