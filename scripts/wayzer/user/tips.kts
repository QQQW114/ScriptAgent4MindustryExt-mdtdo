@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.user

import wayzer.lib.MdtStorage
import java.time.Duration
import java.util.Base64
import kotlin.random.Random

name = "MDT Tips系统"

private val tipsInterval by config.key(Duration.ofMinutes(10)!!, "Tips自动轮播间隔")
// Tips 按需求固定显示到聊天栏，方便玩家回看；不使用 InfoToast。
private val tipsType = MsgType.Message
private val tipsDisplaySeconds = 10f

// .kts 顶层会被编译到脚本类上下文中，不能使用 const val。
private val TIPS_DATA_KEY = "tips.items"
private val TIPS_SEEDED_KEY = "tips.seeded.v1"
private val TIPS_LIST_PAGE_SIZE = 8
private val RECENT_TIP_MEMORY_SIZE = 5
private val recentTipIds = mutableListOf<Int>()

private data class TipItem(
    val id: Int,
    val tag: String,
    val text: String,
    val weight: Int,
    val enabled: Boolean = true,
)

private data class SeedTip(val tag: String, val text: String)

private val defaultTips = listOf(
    SeedTip("idea", "Where possible begins ——Linux.do"),
    SeedTip("idea", "真诚、友善、团结！"),
    SeedTip("idea", "以真诚待人为荣，以虚伪欺人为耻"),
    SeedTip("idea", "以友善热心为荣，以傲慢冷漠为耻"),
    SeedTip("idea", "以团结协作为荣，以孤立对抗为耻"),
    SeedTip("idea", "以专业敬业为荣，以敷衍了事为耻"),
    SeedTip("idea", "我们的规则与方向，可直接参考 https://linux.do/faq ！"),
    SeedTip("tips", "你知道吗，我们的规则参照点都来自Linuxdo技术社区"),
    SeedTip("tips", "\"在错误的地方\"这个处罚说明这个社区不适合你，你在\"错误的地方\"了"),
    SeedTip("tips", "你可以通过帮助他人来获取信任，成为维护服务器环境的一员！"),
    SeedTip("tips", "请在群内反馈，服主仅负责服务器与插件的维护"),
    SeedTip("tips", "关于贡献者头衔，这是对服务器规则，程序做出贡献的所给予的成就，我们不接受赞助"),
    SeedTip("tips", "有人帮助了你？为ta点个赞！"),
    SeedTip("tips", "某人似乎做的不对？给他点个踩吧"),
    SeedTip("tips", "你仅能认可同一人一次，一天仅能认可他人一次"),
    SeedTip("tips", "被赞，被踩，赞别人，踩别人，认可他人...都会纳入排行榜！"),
    SeedTip("tips", "双击自己或他人，可打开玩家信息面板，快捷操作！"),
    SeedTip("tips", "我们建议每一位玩家都去看服务器的wiki!(/wiki)"),
    SeedTip("tips", "你知道吗？你可以查看其它玩家的帖子！"),
    SeedTip("tips", "当你达到3+级时，你可以直接强制观战/禁言搞破坏/不友好的玩家！"),
    SeedTip("tips", "3++是由4级人工任命的插件协管，拥有管理图标和有限白名单权限，但不是完整admin。"),
    SeedTip("tips", "当你达到2级时，你可以直接强制观战0级可能搞破坏的玩家"),
    SeedTip("tips", "/skill解锁的技能会随等级提高，我们相信玩游戏从来是玩家更重要！"),
    SeedTip("idea", "注意你的言辞，你可能在了错误的地方"),
    SeedTip("idea", "古妹哪塞，服务器配置真的很差喵，卡顿十分抱歉的说"),
    SeedTip("idea", "如果你不够友善，可能会被其他高信任等级玩家直接禁言！"),
    SeedTip("idea", "当你的口碑（各种意义上）下降到一定程度，你可能成为群内投票ban掉的目标"),
    SeedTip("idea", "尊重他人，无论何时何地"),
    SeedTip("idea", "即使有人搞破坏，也不应辱骂对方，应友好的让其离开，不纠缠多余事情（kick）"),
    SeedTip("idea", "正是因为众所周知的社区环境原因  才有了这些规则"),
    SeedTip("idea", "某人PVP打赢——顺手嘲讽了一句？这就是踢出ta最佳理由"),
    SeedTip("idea", "不试图改变社区，而是为社区里充满善意的人提供小社区"),
    SeedTip("idea", "如果不适合这里，也不愿改变自己，请不要强行融入，我们是双向选择"),
    SeedTip("idea", "\"我去，老资历给我踢了！\"没错，但老资历也是受玩家们信任而提拔上来的，我们愿意相信ta的决策"),
    SeedTip("idea", "\"搞笑一样的管理\"我们接受建议，但这个问题本质在于我们愿意接受的是接受我们氛围的玩家"),
    SeedTip("idea", "\"MDTxx环境差？\"——正因如此，你此刻展现出的善意才弥足珍贵"),
    SeedTip("idea", "对我们的管理有意见？权当是我们是社会实验好了"),
    SeedTip("idea", "当你的等级达到3+级，可加入我们的mc群，一起来玩快速更新存档的MC自组包吧！"),
    SeedTip("idea", "关注锈铜喵，关注锈铜谢谢喵"),
    SeedTip("idea", "超市杂鱼喵，超市杂鱼谢谢喵"),
    SeedTip("idea", "服务器：不是怎么老贝榨啊，启用实验性性能优化或许能缓解这点"),
    SeedTip("idea", "性能优化影响到游戏了？投票关闭它！"),
    SeedTip("idea", "服务器的插件修改与制作全由Codex完成！vibe coding，神！"),
    SeedTip("idea", "谁都会黯淡，但并不代表失败，熄灭后的重燃才是算是经过了磨练"),
    SeedTip("idea", "[red]全世界无产者，联合起来！[white]——共产党宣言"),
    SeedTip("idea", "我们迟早会消失，但我们做的事会被记下，会被怀念——沃兹即-便德"),
    SeedTip("idea", "无名小卒，还是名扬天下？——赛博朋克2077"),
    SeedTip("idea", "还想再看你们一眼...——沃载-阀殿"),
    SeedTip("idea", "老爷爷，我给你踩背来喽——电棍笑传之踩踩背"),
    SeedTip("idea", "我去 我喜欢百合"),
    SeedTip("idea", "谁还有多余点数/MDC？"),
    SeedTip("idea", "Also try Minecraft, but remember to add the Create mod!"),
    SeedTip("idea", "每过一段时间，mdt的玩家都会迎来大换血"),
    SeedTip("idea", "反正总会成屎山，我自己写和ai有什么区别吗？——它拉的更快,更多"),
    SeedTip("idea", "[gold]感谢檬总开源！"),
    SeedTip("idea", "关于地图特殊插件乃至所有插件，我们始终愿意分享原始/我们修改过的版本，请直接前往qq群询问管理")
)

private fun normalizeTag(input: String): String =
    input.trim()
        .removePrefix("[")
        .removeSuffix("]")
        .lowercase()
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .take(24)
        .let { if (it == "other") "idea" else it }
        .ifBlank { "idea" }

private fun defaultWeight(tag: String): Int = if (tag.equals("tips", ignoreCase = true)) 5 else 1

private fun encodePart(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodePart(value: String): String =
    String(Base64.getDecoder().decode(value), Charsets.UTF_8)

private fun serializeTip(tip: TipItem): String =
    listOf(
        tip.id.toString(),
        if (tip.enabled) "1" else "0",
        tip.weight.coerceAtLeast(1).toString(),
        encodePart(tip.tag),
        encodePart(tip.text),
    ).joinToString("|")

private fun deserializeTip(line: String): TipItem? = runCatching {
    val parts = line.split('|', limit = 5)
    if (parts.size != 5) return@runCatching null
    TipItem(
        id = parts[0].toInt(),
        enabled = parts[1] == "1",
        weight = parts[2].toInt().coerceAtLeast(1),
        tag = normalizeTag(decodePart(parts[3])),
        text = decodePart(parts[4]).trim(),
    ).takeIf { it.text.isNotBlank() }
}.getOrNull()

private fun loadTips(): List<TipItem> =
    MdtStorage.getSetting(TIPS_DATA_KEY)
        .orEmpty()
        .lineSequence()
        .mapNotNull(::deserializeTip)
        .sortedBy { it.id }
        .toList()

private fun saveTips(tips: List<TipItem>) {
    MdtStorage.setSetting(
        TIPS_DATA_KEY,
        tips.sortedBy { it.id }.joinToString("\n", transform = ::serializeTip)
    )
}

private fun nextTipId(tips: List<TipItem>): Int = (tips.maxOfOrNull { it.id } ?: 0) + 1

private fun seedDefaultTips(force: Boolean = false): Int {
    if (!force && MdtStorage.getSetting(TIPS_SEEDED_KEY) == "true") return 0
    val existing = loadTips().toMutableList()
    val existedKeys = existing.map { it.tag to it.text }.toMutableSet()
    var nextId = nextTipId(existing)
    var added = 0
    defaultTips.forEach { seed ->
        val tag = normalizeTag(seed.tag)
        val text = seed.text.trim()
        if (text.isBlank() || !existedKeys.add(tag to text)) return@forEach
        existing += TipItem(nextId++, tag, text, defaultWeight(tag), enabled = true)
        added++
    }
    saveTips(existing)
    MdtStorage.setSetting(TIPS_SEEDED_KEY, "true")
    return added
}

private fun pickRandomTip(): TipItem? {
    val candidates = loadTips().filter { it.enabled && it.text.isNotBlank() }
    if (candidates.isEmpty()) return null
    val recentIds = recentTipIds.toSet()
    val pool = candidates
        .filter { it.id !in recentIds }
        .takeIf { it.isNotEmpty() } ?: candidates
    val totalWeight = pool.sumOf { it.weight.coerceAtLeast(1) }
    var value = Random.nextInt(totalWeight)
    for (tip in pool) {
        value -= tip.weight.coerceAtLeast(1)
        if (value < 0) return tip
    }
    return pool.last()
}

private fun displayTag(tag: String): String = when (tag.lowercase()) {
    "tips" -> "tips"
    "other" -> "idea"
    else -> tag
}

private fun formatTip(tip: TipItem): String =
    "[gold][${displayTag(tip.tag)}][white] ${tip.text}"

private fun compactText(text: String, limit: Int = 36): String {
    val oneLine = text.replace('\n', ' ').replace('\r', ' ').trim()
    return if (oneLine.length <= limit) oneLine else oneLine.take(limit) + "..."
}

private fun sendTipTo(player: Player?, tip: TipItem) {
    rememberTip(tip)
    player.sendMessage(formatTip(tip).with(), tipsType, tipsDisplaySeconds)
}

private fun broadcastTip(tip: TipItem) {
    rememberTip(tip)
    broadcast(formatTip(tip).with(), tipsType, tipsDisplaySeconds, quite = true)
}

private fun rememberTip(tip: TipItem) {
    recentTipIds.remove(tip.id)
    recentTipIds.add(0, tip.id)
    while (recentTipIds.size > RECENT_TIP_MEMORY_SIZE) {
        recentTipIds.removeAt(recentTipIds.lastIndex)
    }
}

private fun adminList(pageInput: Int): String {
    val tips = loadTips()
    if (tips.isEmpty()) return "[yellow]当前没有Tips"
    val totalPage = ((tips.size + TIPS_LIST_PAGE_SIZE - 1) / TIPS_LIST_PAGE_SIZE).coerceAtLeast(1)
    val page = pageInput.coerceIn(1, totalPage)
    val start = (page - 1) * TIPS_LIST_PAGE_SIZE
    val body = tips.drop(start).take(TIPS_LIST_PAGE_SIZE).joinToString("\n") { tip ->
        val state = if (tip.enabled) "[green]启用" else "[gray]停用"
        "[cyan]#${tip.id} [gold][${tip.tag}][white] w=${tip.weight} $state [lightgray]${compactText(tip.text)}"
    }
    return "[gold]Tips列表 $page/$totalPage\n$body"
}

private fun parseTipForAdd(args: List<String>, startIndex: Int): TipItem? {
    if (args.size <= startIndex) return null
    val tag = normalizeTag(args[startIndex])
    var textStart = startIndex + 1
    val weight = args.getOrNull(textStart)?.toIntOrNull()?.let { value ->
        textStart++
        value.coerceAtLeast(1)
    } ?: defaultWeight(tag)
    val text = args.drop(textStart).joinToString(" ").trim()
    if (text.isBlank()) return null
    return TipItem(0, tag, text, weight, enabled = true)
}

private fun upsertTip(item: TipItem): TipItem {
    val tips = loadTips().toMutableList()
    val saved = if (item.id <= 0) item.copy(id = nextTipId(tips)) else item
    val index = tips.indexOfFirst { it.id == saved.id }
    if (index >= 0) tips[index] = saved else tips += saved
    saveTips(tips)
    return saved
}

onEnable {
    val added = seedDefaultTips()
    if (added > 0) logger.info("Tips系统已导入默认Tips $added 条")
    launch(Dispatchers.game) {
        while (true) {
            delay(tipsInterval.toMillis())
            if (Groups.player.size() <= 0) continue
            pickRandomTip()?.let(::broadcastTip)
        }
    }
}

command("tips", "查看一条随机Tips") {
    aliases = listOf("tip", "小提示")
    body {
        val tip = pickRandomTip() ?: returnReply("[yellow]当前没有可用Tips".with())
        sendTipTo(player, tip)
    }
}

command("tipadmin", "管理指令：管理Tips提示") {
    usage = "<list|add|set|remove|enable|disable|send|seed> ..."
    permission = "wayzer.admin.tips"
    aliases = listOf("tipsadmin")
    body {
        if (arg.isEmpty()) returnReply(adminList(1).with())
        when (arg[0].lowercase()) {
            "list", "列表" -> {
                val page = arg.getOrNull(1)?.toIntOrNull() ?: 1
                returnReply(adminList(page).with())
            }
            "add", "添加" -> {
                val parsed = parseTipForAdd(arg, 1) ?: returnReply("[red]用法：/tipadmin add <标签> [权重] <内容>".with())
                val saved = upsertTip(parsed)
                returnReply("[green]已添加Tips #[white]${saved.id}[green]：[gold][${saved.tag}][white] ${saved.text}".with())
            }
            "set", "修改" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/tipadmin set <id> <标签> [权重] <内容>".with())
                val parsed = parseTipForAdd(arg, 2) ?: returnReply("[red]用法：/tipadmin set <id> <标签> [权重] <内容>".with())
                val old = loadTips().firstOrNull { it.id == id }
                val saved = upsertTip(parsed.copy(id = id, enabled = old?.enabled ?: true))
                returnReply("[green]已修改Tips #[white]${saved.id}[green]".with())
            }
            "remove", "del", "delete", "删除" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/tipadmin remove <id>".with())
                val tips = loadTips().toMutableList()
                val removed = tips.removeIf { it.id == id }
                if (removed) saveTips(tips)
                returnReply((if (removed) "[green]已删除Tips #$id" else "[yellow]未找到Tips #$id").with())
            }
            "enable", "启用" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/tipadmin enable <id>".with())
                val oldTips = loadTips()
                if (oldTips.none { it.id == id }) returnReply("[yellow]未找到Tips #$id".with())
                saveTips(oldTips.map { if (it.id == id) it.copy(enabled = true) else it })
                returnReply("[green]已启用Tips #$id".with())
            }
            "disable", "停用" -> {
                val id = arg.getOrNull(1)?.toIntOrNull() ?: returnReply("[red]用法：/tipadmin disable <id>".with())
                val oldTips = loadTips()
                if (oldTips.none { it.id == id }) returnReply("[yellow]未找到Tips #$id".with())
                saveTips(oldTips.map { if (it.id == id) it.copy(enabled = false) else it })
                returnReply("[green]已停用Tips #$id".with())
            }
            "send", "show", "发送" -> {
                val id = arg.getOrNull(1)?.toIntOrNull()
                val tip = if (id == null) pickRandomTip() else loadTips().firstOrNull { it.id == id }
                if (tip == null) returnReply("[yellow]未找到可发送的Tips".with())
                broadcastTip(tip)
                returnReply("[green]已发送Tips #[white]${tip.id}".with())
            }
            "seed", "导入默认" -> {
                val added = seedDefaultTips(force = true)
                returnReply("[green]已重新导入默认Tips，新增 [white]$added[green] 条".with())
            }
            else -> returnReply("[red]未知操作。用法：/tipadmin list|add|set|remove|enable|disable|send|seed".with())
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.tips", group = "@admin")

