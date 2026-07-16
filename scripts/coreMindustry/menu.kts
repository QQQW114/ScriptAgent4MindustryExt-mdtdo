package coreMindustry

import coreLibrary.lib.CommandContext
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.Commands
import coreLibrary.lib.Commands.Hidden
import coreLibrary.lib.util.calPage
import coreMindustry.lib.RootCommands


listen<EventType.MenuOptionChooseEvent> {
    MenuChooseEvent(it.player, it.menuId, it.option).launchEmit(coroutineContext + Dispatchers.game) { e ->
        if (!e.received && it.menuId < 0)
            Call.hideFollowUpMenu(e.player.con, e.menuId)
    }
}

suspend fun <T : Any> sendMenuBuilder(
    player: Player,
    timeoutMillis: Int,
    title: String,
    msg: String,
    builder: suspend MutableList<List<Pair<String, suspend () -> T>>>.() -> Unit
): T? {
    return MenuBuilder<T> {
        this.title = title
        this.msg = msg
        buildList { builder() }.forEachIndexed { i, l ->
            if (i != 0) newRow()
            l.forEach { option(it.first, it.second) }
        }
    }.sendTo(player, timeoutMillis)
}

// .kts 脚本顶层会被编译到脚本类中，不能使用顶层 const val。
private val HELP_PAGE_SIZE = 7
private val HELP_COMMAND_PAGE_CACHE_TTL_MILLIS = 10_000L
private val HELP_OPEN_THROTTLE_MILLIS = 500L

private data class HelpEntryDef(
    val commandName: String?,
    val title: String,
    val description: String,
    val runCommand: String? = commandName?.let { "/$it" },
    val showWhenMissing: Boolean = false,
    val missingMessage: String = "[yellow]该功能暂未开放",
)

private data class HelpMenuEntry(
    val title: String,
    val description: String,
    val runCommand: String? = null,
    val action: (suspend () -> Unit)? = null,
)

private data class HelpEntryPage(
    val items: List<HelpMenuEntry>,
    val page: Int,
    val totalPage: Int,
    val total: Int,
)

private data class CommandInfoPage(
    val items: List<CommandInfo>,
    val page: Int,
    val totalPage: Int,
    val total: Int,
)

private data class CommandSnapshot(
    val signature: Int,
    val commands: List<CommandInfo>,
    val byName: Map<String, CommandInfo>,
)

private data class HelpCommandPageCacheKey(
    val playerId: String,
    val commandSignature: Int,
    val showAll: Boolean,
    val page: Int,
    val pageSize: Int,
    val mode: String,
)

private data class HelpCommandPageCacheEntry(
    val page: CommandInfoPage,
    val loadedAt: Long,
)

private var commandSnapshotCache: CommandSnapshot? = null
private val helpCommandPageCache = linkedMapOf<HelpCommandPageCacheKey, HelpCommandPageCacheEntry>()
private val lastHelpOpenAt = mutableMapOf<String, Long>()

private val playerHelpEntries = listOf(
    HelpEntryDef("account", "[cyan]账号系统", "注册/登录/修改密码"),
    HelpEntryDef("captcha", "[cyan]注册验证码", "注册前获取4位验证码；登录不需要验证码"),
    HelpEntryDef("register", "[cyan]注册账号", "注册MDT DO账号"),
    HelpEntryDef("login", "[cyan]登录账号", "登录MDT DO账号"),
    HelpEntryDef("changepassword", "[cyan]修改密码", "修改当前登录账号密码"),
    HelpEntryDef("deleteownaccount", "[red]注销账号", "注销当前登录账号"),
    HelpEntryDef("playerinfo", "[cyan]玩家信息面板", "打开玩家信息与交互菜单"),
    HelpEntryDef("msg", "[pink]私聊", "给在线玩家发送私聊，/r 可回复最近对象"),
    HelpEntryDef("r", "[pink]回复私聊", "回复最近私聊对象"),
    HelpEntryDef("achievements", "[yellow]成就系统", "查看成就与奖励"),
    HelpEntryDef("rank", "[yellow]排行榜", "MDC/帖子/赞踩/认可排行"),
    HelpEntryDef("posts", "[pink]帖子列表", "玩家交流帖子/评论"),
    HelpEntryDef("wiki", "[cyan]Wiki列表", "查看服务器规则/wiki"),
    HelpEntryDef("tips", "[pink]Tips提示", "查看随机服务器小提示"),
    HelpEntryDef("music", "[pink]服务器点歌", "网易云/服务器音乐库点歌；玩家同意即单独同步播放"),
    HelpEntryDef("title", "[yellow]称号面板", "选择/佩戴已有称号"),
    HelpEntryDef("titleshop", "[yellow]称号商店", "购买称号/自定义称号"),
    HelpEntryDef("skillshop", "[yellow]技能商店", "购买特殊/商店技能"),
    HelpEntryDef("randomform", "[pink]随机变换形态", "随机形态与每日MDC"),
    HelpEntryDef("recognize", "[yellow]认可玩家", "2级后认可其他玩家"),
    HelpEntryDef("points", "[yellow]MDC", "查看当前/累计MDC"),
    HelpEntryDef("seniority", "[yellow]资历等级", "查看资历等级与累计在线时长"),
    HelpEntryDef("pay", "[yellow]MDC转账", "给其他玩家转账MDC"),
    HelpEntryDef("redpacket", "[pink]MDC红包", "发红包/抢红包"),
    HelpEntryDef("grab", "[pink]抢红包", "按红包ID抢MDC红包"),
    HelpEntryDef("redpackets", "[pink]红包列表", "查看当前可抢红包"),
    HelpEntryDef("maps", "[green]地图列表", "查看服务器地图并发起换图"),
    HelpEntryDef("mapcmd", "[green]地图特定指令", "打开当前地图脚本指令，如 /mapcmd shop"),
    HelpEntryDef("mapInfo", "[green]地图详情", "查看当前/指定地图详情"),
    HelpEntryDef("gather", "[green]集合请求", "向队友发出集合请求"),
    HelpEntryDef("t", "[pink]PVP全体聊天", "PVP中向全体玩家发言"),
    HelpEntryDef("ob", "[green]观察者", "切换为观察者"),
    HelpEntryDef("go", "[green]跨服传送", "查看/前往其他服务器"),
    HelpEntryDef("board", "[light_gray]积分板开关", "显示/隐藏左上角积分板"),
    HelpEntryDef("lang", "[light_gray]语言设置", "查看/设置玩家语言"),
    HelpEntryDef("history", "[green]历史查询", "开关建筑历史查询模式"),
    HelpEntryDef("slots", "[green]自动存档槽", "查看自动保存的存档槽"),
    HelpEntryDef("showColor", "[light_gray]颜色列表", "查看可用颜色名"),
    HelpEntryDef("showEffect", "[light_gray]粒子效果预览", "预览指定粒子效果"),
    HelpEntryDef("pixel", "[pink]像素画", "按图片URL绘制像素画"),
    HelpEntryDef("status", "[green]服务器状态", "查看当前服务器信息"),
    HelpEntryDef("traffic", "[green]估算上行", "查看服务器上行需求"),
    HelpEntryDef("pressure", "[green]服务器压力", "查看TPS/上行压力"),
    HelpEntryDef("team", "[yellow]队伍管理(3+)", "3+级/4级可调整自己的队伍；指定他人需管理员权限", "/team"),
)

private val voteHelpEntries = listOf(
    HelpEntryDef("vote", "投票入口", "打开投票指令列表；多数投票需50%同意，特殊投票会单独标注", "/vote"),
    HelpEntryDef("vote", "投降/结束", "投票投降或结束当前局", "/vote gameOver"),
    HelpEntryDef("vote", "投票换图", "发起换图投票，需51%同意", "/vote map"),
    HelpEntryDef("vote", "下次自动轮换", "投票通过后在下一次自动轮换时换到指定地图，需51%同意", "/vote nextmap"),
    HelpEntryDef("vote", "投票回档", "回滚到指定存档槽", "/vote rollback"),
    HelpEntryDef("vote", "投票踢出", "发起踢出玩家投票", "/vote kick"),
    HelpEntryDef("votekick", "原版投票踢出兼容", "兼容旧 /votekick；实际转为强制观战投票", "/votekick"),
    HelpEntryDef("vote", "投票强制观战", "发起强制观战投票", "/vote ob"),
    HelpEntryDef("vote", "投票解除观战", "为自己发起解除强制观战投票", "/vote quitOb"),
    HelpEntryDef("vote", "投票创建存档", "为当前游戏创建投票存档", "/vote save"),
    HelpEntryDef("vote", "投票跳波", "投票快速出波", "/vote skipWave"),
    HelpEntryDef("vote", "暂停波次", "投票临时暂停波次计时", "/vote pauseWave"),
    HelpEntryDef("vote", "取消暂停波次", "投票立即恢复暂停前波次规则", "/vote resumeWave"),
    HelpEntryDef("vote", "调整波次", "投票将当前波次调整到目标波次", "/vote setWave <波次>"),
    HelpEntryDef("vote", "清理建筑记录", "投票清理本队建筑记录", "/vote clear"),
    HelpEntryDef("vote", "自定义投票", "发起自定义文本投票", "/vote text"),
    HelpEntryDef("vote", "SuperChat", "发送中屏醒目留言，不发起实际投票；1级信任以上且2分钟冷却", "/vote sc <内容>"),
    HelpEntryDef("vote", "投票加载CP", "投票加载/热重载外部JSON/HJSON CP，需70%同意", "/vote cp load <文件名|编号>"),
    HelpEntryDef("vote", "投票卸载CP", "投票卸载已加载外部CP，需70%同意", "/vote cp unload <文件名|编号|all>"),
    HelpEntryDef("vote", "投票封禁地图", "投票 ban 掉问题地图", "/vote banmap"),
    HelpEntryDef("vote", "今日PVP开关", "投票关闭/开启今日PVP", "/vote pvp"),
    HelpEntryDef("vote", "关闭今日PVP", "投票关闭今日PVP", "/vote pvpOff"),
    HelpEntryDef("vote", "开启今日PVP", "投票开启今日PVP", "/vote pvpOn"),
    HelpEntryDef("vote", "游客观战", "投票开启今日未登录玩家观战", "/vote guestOb"),
    HelpEntryDef("vote", "解除游客观战", "投票解除今日未登录玩家观战", "/vote guestObOff"),
    HelpEntryDef("vote", "保守性能优化", "投票开启/关闭常驻性能保护", "/vote perf"),
    HelpEntryDef("vote", "实验性性能优化", "投票开启/关闭强力性能保护", "/vote xperf"),
    HelpEntryDef("vote", "暂停游戏", "投票暂停当前游戏", "/vote pause"),
    HelpEntryDef("vote", "继续游戏", "投票继续当前游戏", "/vote resume"),
    HelpEntryDef("vote", "投票击杀单位", "投票击杀当前所有单位", "/vote killunits"),
    HelpEntryDef("vote", "投票标准无限火力", "投票开启120秒标准无限火力", "/vote infinitefire"),
    HelpEntryDef("vote", "投票无限火力promax", "投票开启120秒无限火力promax", "/vote infinitefirepromax"),
    HelpEntryDef("vote", "反应堆爆炸", "投票开启/关闭反应堆爆炸", "/vote reactor"),
    HelpEntryDef("veto", "一票否决", "3+级/4级一键否决当前投票", "/veto"),
)

private val adminHelpEntries = listOf(
    HelpEntryDef("security", "[cyan]安全风控菜单", "管理聊天/菜单/连接风控与IP封禁"),
    HelpEntryDef("servertestmode", "[red][危险]服务器测试模式", "特殊测试服临时账号/MDC/资历覆盖，启用需二次确认"),
    HelpEntryDef("achat", "[cyan]管理员频道", "发送仅4级/admin可见的消息"),
    HelpEntryDef("descadmin", "[pink]服务器介绍轮播", "管理服务器列表介绍滚动"),
    HelpEntryDef("sfx", "[pink]服务器小音效", "播放 assets/sounds 中的预置小音效"),
    HelpEntryDef("music", "[pink]服务器点歌", "发起/管理网易云或 config/music-jukebox/library 点歌投票，管理可调整限制/停止全服音乐"),
    HelpEntryDef("skill", "[cyan]管理员技能", "打开管理员技能分类", "/skill admin"),
    HelpEntryDef("skill", "[cyan]神权菜单", "信任4级专用：规则倍率/科技限制/编辑器/无限火力", "/skill godmenu"),
    HelpEntryDef("setBlock", "[green]设置脚下方块", "按方块ID设置管理员脚下方块"),
    HelpEntryDef("setFloor", "[green]设置脚下地板", "按地板ID设置管理员脚下地板"),
    HelpEntryDef("fill", "[green]区域填充", "批量填充建筑层/地板层，支持覆盖或保留"),
    HelpEntryDef("suffixmark", "[yellow]名字后缀标记", "自定义/隐藏管理后缀标记"),
    HelpEntryDef("kill", "[red]击杀单位", "击杀玩家或选择器单位：@e[team=2,unit=mono]/@a/@s，也可玩家UUID"),
    HelpEntryDef("tp", "[red]传送单位", "传送自己/玩家/选择器单位到鼠标、地图坐标或目标单位"),
    HelpEntryDef("effect", "[purple]单位效果", "给选择器/玩家UUID单位添加可叠加状态效果"),
    HelpEntryDef("host", "[green]强制换图", "管理换图"),
    HelpEntryDef("load", "[green]加载存档", "管理加载自动存档"),
    HelpEntryDef("gameover", "[red]结束游戏", "管理结束当前局"),
    HelpEntryDef("forceOB", "[cyan]强制观战", "强制/解除玩家观战"),
    HelpEntryDef("forceobclean", "[cyan]清理强制观战", "高人数时清理强制观战玩家"),
    HelpEntryDef("playermute", "[red]禁言玩家", "管理禁言玩家"),
    HelpEntryDef("playerunmute", "[red]解除禁言", "管理解除玩家禁言"),
    HelpEntryDef("buildban", "[red]禁建玩家", "禁止在线玩家建造/拆除；玩家面板支持临时时长"),
    HelpEntryDef("buildunban", "[red]解除禁建", "解除在线玩家建造/拆除限制"),
    HelpEntryDef("banX", "[red]禁封账号", "禁封玩家账号/主体"),
    HelpEntryDef("unbanX", "[red]解除禁封", "按3位ID/UUID/账号UID解除玩家账号/主体禁封"),
    HelpEntryDef("banip", "[red]封禁玩家IP", "按在线玩家三位UID/#ID/名字封禁IP"),
    HelpEntryDef("unbanip", "[red]解除IP封禁", "解除安全风控IP封禁"),
    HelpEntryDef("banips", "[cyan]IP封禁列表", "查看当前被封禁IP、UUID与玩家名"),
    HelpEntryDef("recentplayers", "[cyan]最近玩家", "查看最近80名玩家并打开离线管理面板"),
    HelpEntryDef("cp", "[green]CP列表", "列出当前已经加载的数据包/属性修改"),
    HelpEntryDef("externalcp", "[purple]外部CP", "管理 scripts/external-cp 下的外部JSON/HJSON CP热重载"),
    HelpEntryDef("worldprocessor", "[green]世界处理器", "查看/开启/关闭世界处理器与编辑权限"),
    HelpEntryDef("worldprocessorquiet", "[green]静默世界处理器", "静默开启/关闭世界处理器与编辑权限，不全局播报", "/wpq"),
    HelpEntryDef("banmap", "[green]封禁地图", "直接封禁地图ID"),
    HelpEntryDef("unbanmap", "[green]解除地图封禁", "解除地图ID封禁"),
    HelpEntryDef("banmaps", "[green]地图封禁列表", "查看已封禁地图ID"),
    HelpEntryDef("resourceproxy", "[green]资源站代理", "开关/设置地图站本机代理"),
    HelpEntryDef("loadmapscript", "[green]加载地图脚本", "手动加载 scripts/mapScript/<id>.kts", "/loadmapscript"),
    HelpEntryDef("unloadmapscript", "[green]关闭地图脚本", "手动关闭指定地图脚本/模式", "/unloadmapscript"),
    HelpEntryDef("mapscripts", "[green]地图脚本列表", "列出当前启用的地图脚本", "/mapscripts"),
    HelpEntryDef("todaypvp", "[green]今日PVP", "查看/设置今日PVP开关"),
    HelpEntryDef("logicdraw", "[green]逻辑绘图开关", "开关画布/逻辑显示器绘图方块，支持本局临时禁止"),
    HelpEntryDef("blockban", "[green]方块禁用/解禁", "本局单独禁止或解除禁止某个建筑方块"),
    HelpEntryDef("perf", "[green]性能优化", "查看/管理常驻保守性能保护"),
    HelpEntryDef("xperf", "[green]实验性性能优化", "查看/管理强力性能保护"),
    HelpEntryDef("gamepause", "[green]暂停/继续游戏", "管理当前游戏暂停状态"),
    HelpEntryDef("traffic", "[green]上行预算", "查看/设置估算上行预算"),
    HelpEntryDef("pressure", "[green]服务器压力", "查看TPS/上行压力/设置TPS阈值"),
    HelpEntryDef("adaptiveplayerlimit", "[green]人数上限", "查看/设置自适应人数上限"),
    HelpEntryDef("tipadmin", "[pink]Tips管理", "添加/删除/发送服务器小提示"),
    HelpEntryDef("setlevel", "[yellow]设置信任等级", "设置玩家0/1/2/3/3+/4级"),
    HelpEntryDef("setadmin", "[yellow]原生管理员", "设置/取消玩家admin"),
    HelpEntryDef("locklevel", "[yellow]锁定等级", "锁定/解除自动晋升控制"),
    HelpEntryDef("trustcheck", "[yellow]信任检查", "检查玩家晋升条件"),
    HelpEntryDef("setseniority", "[yellow]设置资历等级", "设置并锁定玩家0/1/2/3/4资历等级"),
    HelpEntryDef("lockseniority", "[yellow]锁定资历", "锁定/解除资历自动晋升"),
    HelpEntryDef("setplaytime", "[yellow]设置在线时长", "按小时设置玩家累计在线时长"),
    HelpEntryDef("addplaytime", "[yellow]增加在线时长", "按小时增加玩家累计在线时长"),
    HelpEntryDef("trustpoint", "[yellow]MDC管理", "查看/修改玩家MDC"),
    HelpEntryDef("reputation", "[yellow]赞踩管理", "查看/修改玩家赞踩数"),
    HelpEntryDef("playertitle", "[pink]称号管理", "授予/撤销玩家称号"),
    HelpEntryDef("titledef", "[yellow]称号定义", "定义/更新称号显示名"),
    HelpEntryDef("titleshopadmin", "[pink]称号商店管理", "管理称号商店商品"),
    HelpEntryDef("achadmin", "[yellow]成就管理", "自定义成就/授予/撤销玩家成就"),
    HelpEntryDef("setpassword", "[yellow]重置密码", "管理重置玩家账号密码"),
    HelpEntryDef("deleteaccount", "[red]删除账号", "管理删除玩家账号"),
    HelpEntryDef("accountqq", "[yellow]查询QQ账号", "按三位UID等查询QQ"),
    HelpEntryDef("guestob", "[cyan]游客观战控制", "查看/设置今日未登录玩家强制观战"),
    HelpEntryDef("ipguard", "[cyan]IP防熊", "查看/解除IP临时限制与账号绑定"),
    HelpEntryDef("ipregion", "[cyan]IP地区", "查询玩家IP地区识别结果"),
    HelpEntryDef("posts", "[pink]帖子管理", "打开帖子列表/分区/回收站", "/posts"),
    HelpEntryDef("posts", "[pink]帖子回收站", "恢复/彻底删除已删帖子", "/posts trash"),
    HelpEntryDef("posts", "[pink]帖子保护锁", "进帖子详情设置/解除保护锁", "/posts"),
    HelpEntryDef("wikiadmin", "[pink]Wiki管理", "新增/编辑/保护/回收Wiki", "/wikiadmin"),
    HelpEntryDef("wikiadmin", "[pink]Wiki回收站", "恢复/彻底删除已删Wiki", "/wikiadmin trash"),
    HelpEntryDef("wikiadmin", "[pink]Wiki保护锁", "进Wiki管理页设置/解除保护锁", "/wikiadmin"),
    HelpEntryDef("logicSaveCheck", "[green]逻辑存档检查", "检查并清理异常逻辑变量"),
    HelpEntryDef("restart", "[red]计划重启", "计划服务器重启"),
    HelpEntryDef("ScriptAgent", "[light_gray]ScriptAgent入口", "高级：脚本控制总入口", "/ScriptAgent"),
    HelpEntryDef("ScriptAgent", "[light_gray]扫描脚本", "高级：重新扫描脚本", "/ScriptAgent scan"),
    HelpEntryDef("ScriptAgent", "[light_gray]脚本列表", "高级：列出模块或脚本", "/ScriptAgent list"),
    HelpEntryDef("ScriptAgent", "[light_gray]加载脚本", "高级：加载ScriptAgent脚本/模块", "/ScriptAgent load"),
    HelpEntryDef("ScriptAgent", "[light_gray]热重载脚本", "高级：热重载脚本", "/ScriptAgent hotReload"),
    HelpEntryDef("ScriptAgent", "[light_gray]启用脚本", "高级：启用ScriptAgent脚本", "/ScriptAgent enable"),
    HelpEntryDef("ScriptAgent", "[light_gray]停用脚本", "高级：停用ScriptAgent脚本", "/ScriptAgent disable"),
    HelpEntryDef("ScriptAgent", "[light_gray]卸载脚本", "高级：卸载ScriptAgent脚本", "/ScriptAgent unload"),
    HelpEntryDef("ScriptAgent", "[light_gray]配置管理", "高级：查看/修改脚本配置", "/ScriptAgent config"),
    HelpEntryDef("ScriptAgent", "[red]权限管理", "高级：查看/修改权限", "/ScriptAgent permission"),
    HelpEntryDef("ScriptAgent", "[light_gray]变量查询", "高级：查看变量/占位符", "/ScriptAgent vars"),
    HelpEntryDef("saveSnap", "[green]保存地图截图", "保存当前服务器地图截图"),
    HelpEntryDef("clearUnit", "[red]清除单位", "清除所有单位"),
    HelpEntryDef("spawn", "[green]召唤单位", "管理召唤单位"),
    HelpEntryDef("profiler", "[green]性能采样", "服务器性能采样"),
    HelpEntryDef("debugLogicPacket", "[light_gray]逻辑发包调试", "查看逻辑发包速率"),
    HelpEntryDef("dosBanClear", "[red]清理dosBan", "清理dosBan列表"),
    HelpEntryDef("forceUpdate", "[red]强制更新", "强制更新服务器版本"),
    HelpEntryDef("js", "[red]JS执行", "危险：在服务器运行JS"),
)

private fun topLevelHelpPrefix(prefix: String): Boolean =
    prefix.isEmpty() || prefix == "/" || prefix == "* "

private fun compactText(text: String, limit: Int = 64): String {
    val oneLine = text.replace('\n', ' ').replace('\r', ' ').trim()
    return if (oneLine.length <= limit) oneLine else oneLine.take(limit) + "..."
}

private fun commandInput(prefix: String, name: String): String {
    val raw = prefix.ifBlank { "/" }.trimStart()
    val p = if (raw.startsWith("/")) raw else "/$raw"
    return if (p.endsWith(" ")) p + name else p + name
}

private fun optionText(title: String, description: String): String =
    if (description.isBlank()) title else "$title\n[gray]$description"

private fun isVoteHelpPrefix(prefix: String): Boolean {
    val normalized = prefix.trim().trimEnd().lowercase()
    return normalized == "/vote" || normalized == "vote"
}

private fun voteHelpCommandColor(commandName: String): String = when (commandName.lowercase()) {
    "gameover", "kick", "killunits" -> "[red]"
    "map", "nextmap", "rollback", "save" -> "[green]"
    "skipwave", "pausewave", "setwave", "resumewave", "unpausewave", "pureoff" -> "[yellow]"
    "infinitefire", "infinitefirepromax", "reactor" -> "[orange]"
    "cp" -> "[purple]"
    "pure", "ob", "quitob", "clear" -> "[cyan]"
    "text" -> "[lightgray]"
    else -> "[white]"
}

private suspend fun runHelpCommand(player: Player, command: String) {
    RootCommands.handleInput(command, player, "/")
}

private suspend fun noopHelpBack() {}

private fun uniqueSortedCommands(cmds: Commands): List<CommandInfo> {
    val unique = LinkedHashSet<CommandInfo>()
    cmds.subCommands().values.forEach { unique += it }
    return unique.sortedBy { it.name.lowercase() }
}

private fun commandSignature(cmds: Commands): Int =
    cmds.subCommands().values
        .map { "${it.name}:${it.aliases.joinToString(",")}:${it.permission}:${it.script?.id.orEmpty()}" }
        .sorted()
        .joinToString("|")
        .hashCode()

private fun commandSnapshot(cmds: Commands): CommandSnapshot {
    val signature = commandSignature(cmds)
    commandSnapshotCache?.takeIf { it.signature == signature }?.let { return it }
    val commands = uniqueSortedCommands(cmds)
    return CommandSnapshot(signature, commands, commands.associateBy { it.name.lowercase() }).also {
        commandSnapshotCache = it
        helpCommandPageCache.clear()
    }
}

private suspend fun CommandContext.visibleCommandInfo(
    info: CommandInfo,
    permissionCache: MutableMap<String, Boolean>,
): Boolean {
    return info.attrs.all { attr ->
        attr !is Hidden || when (attr) {
            is Commands.Permission -> {
                if (!permissionCache.containsKey(attr.permission)) {
                    permissionCache[attr.permission] = hasPermission(attr.permission)
                }
                permissionCache[attr.permission] == true
            }
            else -> attr.visible()
        }
    }
}

private fun pruneHelpCommandPageCache(now: Long = System.currentTimeMillis()) {
    if (helpCommandPageCache.size <= 256) return
    val iterator = helpCommandPageCache.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (now - entry.value.loadedAt > HELP_COMMAND_PAGE_CACHE_TTL_MILLIS || helpCommandPageCache.size > 192) {
            iterator.remove()
        }
    }
}

private fun markHelpOpenTooFast(player: Player): Boolean {
    val key = player.uuid()
    val now = System.currentTimeMillis()
    val last = lastHelpOpenAt[key]
    lastHelpOpenAt[key] = now
    return last != null && now - last < HELP_OPEN_THROTTLE_MILLIS
}

private suspend fun CommandContext.visibleHelpCommandPage(
    commands: List<CommandInfo>,
    showAll: Boolean,
    requestPage: Int,
    pageSize: Int,
    extraFilter: (CommandInfo) -> Boolean = { true },
): CommandInfoPage {
    val permissionCache = mutableMapOf<String, Boolean>()

    suspend fun visible(info: CommandInfo): Boolean {
        if (showAll) return true
        return visibleCommandInfo(info, permissionCache)
    }

    var total = 0
    val firstPassItems = mutableListOf<CommandInfo>()
    val firstStart = (requestPage.coerceAtLeast(1) - 1) * pageSize
    commands.forEach { info ->
        if (visible(info) && extraFilter(info)) {
            if (total in firstStart until (firstStart + pageSize)) firstPassItems += info
            total++
        }
    }

    val totalPage = maxOf(1, (total + pageSize - 1) / pageSize)
    val page = requestPage.coerceIn(1, totalPage)
    if (page == requestPage.coerceAtLeast(1)) {
        return CommandInfoPage(firstPassItems, page, totalPage, total)
    }

    // 请求页超出范围时只重新收集最终页；仍只构建当前页按钮，避免完整指令列表一次性生成所有文本。
    val start = (page - 1) * pageSize
    var index = 0
    val items = mutableListOf<CommandInfo>()
    commands.forEach { info ->
        if (visible(info) && extraFilter(info)) {
            if (index in start until (start + pageSize)) items += info
            index++
        }
    }
    return CommandInfoPage(items, page, totalPage, total)
}

private suspend fun CommandContext.visibleHelpCommandPageCached(
    player: Player,
    commandSignature: Int,
    commands: List<CommandInfo>,
    showAll: Boolean,
    requestPage: Int,
    pageSize: Int,
    mode: String,
    extraFilter: (CommandInfo) -> Boolean = { true },
): CommandInfoPage {
    val key = HelpCommandPageCacheKey(
        player.uuid(),
        commandSignature,
        showAll,
        requestPage.coerceAtLeast(1),
        pageSize,
        mode,
    )
    val now = System.currentTimeMillis()
    helpCommandPageCache[key]?.takeIf { now - it.loadedAt <= HELP_COMMAND_PAGE_CACHE_TTL_MILLIS }?.let {
        return it.page
    }
    val page = visibleHelpCommandPage(commands, showAll, requestPage, pageSize, extraFilter)
    helpCommandPageCache[key] = HelpCommandPageCacheEntry(page, now)
    pruneHelpCommandPageCache(now)
    return page
}

private suspend fun CommandContext.buildConfiguredEntries(
    defs: List<HelpEntryDef>,
    commandByName: Map<String, CommandInfo>,
    player: Player
): List<HelpMenuEntry> {
    val permissionCache = mutableMapOf<String, Boolean>()
    val entries = mutableListOf<HelpMenuEntry>()
    defs.forEach { def ->
        val info = def.commandName?.let { commandByName[it.lowercase()] }
        val exists = def.commandName == null || info != null
        if (!exists && !def.showWhenMissing) return@forEach
        if (info != null && !visibleCommandInfo(info, permissionCache)) return@forEach
        val title = def.runCommand?.let { "${it.trim()},${def.title}" } ?: def.title
        entries += HelpMenuEntry(title, def.description, def.runCommand) {
            val run = def.runCommand
            if (!exists) {
                player.sendMessage(def.missingMessage)
            } else if (run != null) {
                runHelpCommand(player, run)
            }
        }
    }
    return entries
}

private fun rawCommandEntry(player: Player, prefix: String, info: CommandInfo, showAll: Boolean): HelpMenuEntry {
    val input = commandInput(prefix, info.name)
    val alias = if (info.aliases.isEmpty()) "" else info.aliases.joinToString(separator = ",", prefix = "（", postfix = "）") {
        commandInput(prefix, it)
    }
    val voteHelp = isVoteHelpPrefix(prefix)
    val commandColor = if (voteHelp) voteHelpCommandColor(info.name) else "[gold]"
    val usage = if (voteHelp) info.usage.removePrefix("[yellow]").removeSuffix("[]") else info.usage
    val usageLine = when {
        usage.isBlank() && voteHelp -> "$commandColor$input[]"
        usage.isBlank() -> input
        voteHelp -> "[yellow]$input $usage[]"
        else -> "$input $usage"
    }
    val detail = buildString {
        if (!showAll) return@buildString
        info.script?.let { append(" | ${it.id}") }
        if (info.permission.isNotBlank()) append(" | ${info.permission}")
    }
    val desc = buildString {
        append(if (voteHelp) "" else "[lightgray]")
        append(usageLine)
        if (detail.isNotBlank()) {
            append("\n[gray]")
            append(detail.trim().removePrefix("|").trim())
        }
    }
    return HelpMenuEntry("$commandColor$input[gray]$alias", desc, input)
}

private suspend fun openHelpEntryList(
    player: Player,
    titleText: String,
    messageText: String,
    items: List<HelpMenuEntry>,
    openRoot: suspend () -> Unit,
    initialPage: Int = 1,
) {
    if (items.isEmpty()) {
        player.sendMessage("[yellow]该分类当前没有可用条目")
        openRoot()
        return
    }
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            title = titleText
            msg = messageText
            val (page, totalPage) = calPage(selectedPage, HELP_PAGE_SIZE, items.size)
            selectedPage = page
            items.subList((page - 1) * HELP_PAGE_SIZE, (page * HELP_PAGE_SIZE).coerceAtMost(items.size))
                .forEach { item ->
                    option(optionText(item.title, item.description)) {
                        item.action?.invoke() ?: item.runCommand?.let { runHelpCommand(player, it) }
                    }
                    newRow()
                }
            option("<-") { selectedPage = page - 1; refresh() }
            option("$page/$totalPage") { refresh() }
            option("->") { selectedPage = page + 1; refresh() }
            newRow()
            option("返回") { openRoot() }
            option("关闭") {}
        }
    }.sendTo(player, 60_000)
}

private suspend fun openPagedHelpEntryList(
    player: Player,
    titleText: String,
    messageText: String,
    loadPage: suspend (Int, Int) -> HelpEntryPage,
    openRoot: suspend () -> Unit,
    initialPage: Int = 1,
) {
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            val pageData = loadPage(selectedPage, HELP_PAGE_SIZE)
            selectedPage = pageData.page
            title = titleText
            msg = if (pageData.total == 0) {
                "[yellow]该分类当前没有可用条目"
            } else {
                "$messageText\n[gray]仅构建当前页 ${pageData.items.size} 条 / 共 ${pageData.total} 条。"
            }
            pageData.items.forEach { item ->
                option(optionText(item.title, item.description)) {
                    item.action?.invoke() ?: item.runCommand?.let { runHelpCommand(player, it) }
                }
                newRow()
            }
            repeat(HELP_PAGE_SIZE - pageData.items.size) {
                option("") { refresh() }
                newRow()
            }
            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("${pageData.page}/${pageData.totalPage}") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(pageData.totalPage); refresh() }
            newRow()
            option("返回") { openRoot() }
            option("关闭") {}
        }
    }.sendTo(player, 60_000)
}

private suspend fun openRawHelpMenu(
    player: Player,
    sortedCommands: List<CommandInfo>,
    commandSignature: Int,
    prefix: String,
    showAll: Boolean,
    initialPage: Int,
    openRoot: (suspend () -> Unit)? = null,
    commandContext: CommandContext,
) {
    val backAction: suspend () -> Unit = openRoot ?: { noopHelpBack() }
    openPagedHelpEntryList(
        player,
        if (prefix.isEmpty() || prefix == "/") "完整指令列表" else "指令列表：$prefix",
        "点击指令可直接快捷输入；这是完整列表，日常建议优先使用 /help 的分区入口。",
        { requestPage, pageSize ->
            val commandPage = commandContext.visibleHelpCommandPageCached(
                player,
                commandSignature,
                sortedCommands,
                showAll,
                requestPage,
                pageSize,
                "raw:${prefix.ifBlank { "/" }}",
            )
            HelpEntryPage(
                commandPage.items.map { rawCommandEntry(player, prefix, it, showAll) },
                commandPage.page,
                commandPage.totalPage,
                commandPage.total,
            )
        },
        backAction,
        initialPage
    )
}

private suspend fun openOtherHelpMenu(
    player: Player,
    sortedCommands: List<CommandInfo>,
    commandSignature: Int,
    commandContext: CommandContext,
    usedCommandNames: Set<String>,
    openRoot: suspend () -> Unit,
    initialPage: Int = 1,
) {
    openPagedHelpEntryList(
        player,
        "其他指令",
        "尚未纳入固定分区的可用指令。",
        { requestPage, pageSize ->
            val commandPage = commandContext.visibleHelpCommandPageCached(
                player,
                commandSignature,
                sortedCommands,
                false,
                requestPage,
                pageSize,
                "other:${usedCommandNames.hashCode()}",
            ) {
                it.name.lowercase() !in usedCommandNames && it.name.lowercase() != "help"
            }
            HelpEntryPage(
                commandPage.items.map { rawCommandEntry(player, "/", it, false) },
                commandPage.page,
                commandPage.totalPage,
                commandPage.total,
            )
        },
        openRoot,
        initialPage,
    )
}

private suspend fun openHelpRoot(
    player: Player,
    sortedCommands: List<CommandInfo>,
    commandSignature: Int,
    commandByName: Map<String, CommandInfo>,
    showAll: Boolean,
    canSeeAdminHelp: Boolean,
    commandContext: CommandContext,
) {
    val usedNames = (playerHelpEntries + voteHelpEntries + adminHelpEntries)
        .mapNotNull { it.commandName?.lowercase() }
        .toSet() + setOf("shop", "skill", "wiki", "help")

    val playerEntries = commandContext.buildConfiguredEntries(playerHelpEntries, commandByName, player)
    val voteEntries = commandContext.buildConfiguredEntries(voteHelpEntries, commandByName, player)
    val adminEntries = if (canSeeAdminHelp) commandContext.buildConfiguredEntries(adminHelpEntries, commandByName, player) else emptyList()

    suspend fun reopenRoot() {
        openHelpRoot(player, sortedCommands, commandSignature, commandByName, showAll, canSeeAdminHelp, commandContext)
    }

    MenuBuilder<Unit>("MDT帮助") {
        msg = """
            |[cyan]请选择帮助分区。
        """.trimMargin()

        var column = 0
        fun rootOption(text: String, action: suspend () -> Unit) {
            option(text, action)
            column += 1
            if (column >= 2) {
                newRow()
                column = 0
            }
        }

        if (playerEntries.isNotEmpty()) {
            rootOption("玩家指令\n[gray]账号/信息/成就等") {
                openHelpEntryList(player, "玩家指令", "普通玩家常用功能。", playerEntries, { reopenRoot() })
            }
        }
        if (voteEntries.isNotEmpty()) {
            rootOption("投票指令\n[gray]/vote投票入口") {
                openHelpEntryList(player, "投票指令", "投票相关功能；点击条目会快捷输入对应指令。", voteEntries, { reopenRoot() })
            }
        }

        rootOption("帖子列表\n[gray]玩家交流/评论") { runHelpCommand(player, "/posts") }

        if ("shop" in commandByName) {
            rootOption("商店列表\n[gray]打开 /shop") { runHelpCommand(player, "/shop") }
        }
        if ("skill" in commandByName) {
            rootOption("技能指令\n[gray]打开 /skill") { runHelpCommand(player, "/skill") }
        }

        if (adminEntries.isNotEmpty()) {
            rootOption("管理指令\n[gray]仅显示你可使用的项") {
                openHelpEntryList(player, "管理指令", "管理/高信任权限功能。", adminEntries, { reopenRoot() })
            }
        }
        rootOption("其他指令\n[gray]未归类的指令") {
            openOtherHelpMenu(player, sortedCommands, commandSignature, commandContext, usedNames, { reopenRoot() })
        }

        rootOption("Wiki列表\n[gray]文档/wiki入口") {
            if ("wiki" in commandByName) runHelpCommand(player, "/wiki")
            else player.sendMessage("[yellow]Wiki列表暂未开放，后续会接入 /wiki")
        }
        rootOption("完整指令列表\n[gray]分页显示全部指令") {
            openRawHelpMenu(player, sortedCommands, commandSignature, "/", showAll, 1, { reopenRoot() }, commandContext)
        }
        if (column != 0) newRow()
        option("关闭") {}
    }.sendTo(player, 60_000)
}

onEnable {
    val bak = Commands.helpOverwrite
    onDisable { Commands.helpOverwrite = bak }
    Commands.helpOverwrite = impl@{ cmds, showAll, page ->
        val player = player ?: return@impl

        if (markHelpOpenTooFast(player)) CommandInfo.Return()
        val commandContext = context
        val snapshot = commandSnapshot(cmds)
        if (!topLevelHelpPrefix(prefix) || showAll || page != 1) {
            openRawHelpMenu(player, snapshot.commands, snapshot.signature, prefix, showAll, page, null, commandContext)
        } else {
            val canSeeAdminHelp =
                      hasPermission("wayzer.admin.security") ||
                    hasPermission("wayzer.admin.serverTestMode") ||
                    hasPermission("wayzer.maps.host") ||
                    hasPermission("wayzer.admin.trustPoint") ||
                      hasPermission("wayzer.admin.account") ||
                      hasPermission("wayzer.admin.forceOb") ||
                      hasPermission("wayzer.admin.forceObClean") ||
        hasPermission("wayzer.admin.ipguard") ||
        hasPermission("wayzer.admin.serverDescription") ||
        hasPermission("wayzer.admin.soundEffect") ||
        hasPermission("wayzer.admin.music") ||
        hasPermission("wayzer.admin.setBlock") ||
                      hasPermission("wayzer.admin.fill") ||
                      hasPermission("wayzer.admin.effect") ||
                      hasPermission("suffix.admin") ||
                      hasPermission("wayzer.admin.kill") ||
                      hasPermission("wayzer.admin.logicDraw") ||
                      hasPermission("wayzer.admin.blockBan") ||
                      hasPermission("wayzer.ext.team.change") ||
                      hasPermission("wayzer.map.adaptivePlayerLimit") ||
                      hasPermission("wayzer.map.resourceProxy") ||
                      hasPermission("scriptAgent.admin")
            openHelpRoot(player, snapshot.commands, snapshot.signature, snapshot.byName, showAll, canSeeAdminHelp, commandContext)
        }
        CommandInfo.Return()
    }
}

listen<EventType.PlayerLeave> {
    lastHelpOpenAt.remove(it.player.uuid())
}
