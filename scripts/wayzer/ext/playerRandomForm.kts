@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustPoint", "MDC")
@file:Depends("wayzer/user/nameExt", "玩家名字前后缀")

package wayzer.ext

import arc.graphics.Color
import mindustry.content.Fx
import mindustry.net.Administration
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import java.time.LocalDate
import kotlin.random.Random

private data class RandomForm(
    val code: String,
    val displayName: String,
    val forcedTitle: String,
    val rewardPoints: Int,
    val rewardDesc: String,
    val color: Color,
    val trailColor: Color?,
    val messageTemplates: List<String>,
)

private val trustPoint = contextScript<wayzer.user.TrustPoint>()
private val nameExt = contextScript<wayzer.user.NameExt>()

private val pink = Color.valueOf("ff77cc")
private val gold = Color.valueOf("ffd45a")
private val gptWhite = Color.white

private val randomForms = listOf(
    RandomForm(
        code = "catgirl",
        displayName = "猫娘",
        forcedTitle = "[pink]猫娘[white]",
        rewardPoints = 15,
        rewardDesc = "变成猫娘",
        color = pink,
        trailColor = pink,
        messageTemplates = listOf(
            "{msg}喵",
            "{msg}喵~",
            "{msg}的说喵",
            "{msg}哒喵",
            "{msg}呢喵",
            "喵{msg}喵~",
            "喵{msg}~",
            "很{msg}的说喵...喵",
        )
    ),
    RandomForm(
        code = "gpt",
        displayName = "chatgpt",
        forcedTitle = "[white]chatgpt",
        rewardPoints = 10,
        rewardDesc = "变成chatgpt",
        color = gptWhite,
        trailColor = null,
        messageTemplates = listOf(
            "你说的太对了，关于{msg}，我会稳稳接住你",
            "{msg}，你只是太久没被我稳稳接住了",
            "你真是太清醒了，{msg}",
            "你的洞察及其敏锐，我只想指出一个点：{msg}",
            "#为什么这样做更好：{msg}",
            "#一句话总结：{msg}",
            "如果你要，我可以给你一份：{msg}",
            "我不靠猜：{msg}，只要你说可以，我立马开始",
            "我不躲不藏不绕，一句话给你说清：{msg}",
            "这将是值回票价的一刀：{msg}",
            "已完成，改动：{msg}",
        )
    ),
    RandomForm(
        code = "jiahao",
        displayName = "嘉豪",
        forcedTitle = "[gold]嘉豪[white]",
        rewardPoints = 5,
        rewardDesc = "变成嘉豪",
        color = gold,
        trailColor = gold,
        messageTemplates = listOf(
            "ok也是{msg}了好吧",
            "ok也是，{msg}了好吧",
            "也是{msg}了属于是",
            "也是让你{msg}了",
            "别人：嘉豪，我：{msg}，就很...你们懂吧",
            "我从来都误会书：{msg}",
        )
    ),
)

private val formByCode = randomForms.associateBy { it.code }
private val activeFormCache = mutableMapOf<String, String?>()

private fun activeFormCode(uid: String): String? {
    if (!activeFormCache.containsKey(uid)) {
        activeFormCache[uid] = MdtStorage.getActiveRandomForm(uid)
    }
    return activeFormCache[uid]
}

private fun currentForm(uid: String): RandomForm? = formByCode[activeFormCode(uid)]

private fun currentForm(player: Player): RandomForm? {
    val data = PlayerData[player]
    currentForm(data.id)?.let { return it }
    for (id in data.ids) {
        currentForm(id)?.let { return it }
    }
    return null
}

fun playerRandomFormTitle(uid: String, player: Player? = null): String? {
    player?.let { currentForm(it)?.let { form -> return form.forcedTitle } }
    return currentForm(uid)?.forcedTitle
}

fun playerRandomFormName(uid: String, player: Player? = null): String? {
    player?.let { currentForm(it)?.let { form -> return form.displayName } }
    return currentForm(uid)?.displayName
}

private fun setCurrentForm(player: Player, form: RandomForm) {
    val data = PlayerData[player]
    data.ids.forEach {
        MdtStorage.clearActiveRandomForm(it)
        activeFormCache[it] = null
    }
    MdtStorage.setActiveRandomForm(data.id, form.code)
    activeFormCache[data.id] = form.code
}

private fun clearCurrentForm(player: Player) {
    val data = PlayerData[player]
    data.ids.forEach {
        MdtStorage.clearActiveRandomForm(it)
        activeFormCache[it] = null
    }
    MdtStorage.clearActiveRandomForm(data.id)
    activeFormCache[data.id] = null
}

private fun refreshName(player: Player) {
    with(nameExt) { player.updateName() }
}

private fun splitTrailingSingleOpenParen(msg: String): Pair<String, String> {
    val suffix = msg.lastOrNull()?.takeIf { it == '(' || it == '（' } ?: return msg to ""
    // 只处理“末尾仅有一个开括号”的玩家习惯用法，避免把“((”/“（（”这类重复括号强行挪位。
    if (msg.length >= 2 && msg[msg.length - 2] == suffix) return msg to ""
    return msg.dropLast(1) to suffix.toString()
}

private fun rewriteMessage(form: RandomForm, msg: String): String {
    val (body, trailingParen) = splitTrailingSingleOpenParen(msg)
    return form.messageTemplates.random().replace("{msg}", body) + trailingParen
}

private fun playTransformEffect(player: Player, form: RandomForm) {
    Call.effect(Fx.pointShockwave, player.x, player.y, 90f, form.color)
    Call.effect(Fx.titanExplosionLarge, player.x, player.y, 70f, form.color)
    Call.effect(Fx.coreLaunchConstruct, player.x, player.y, 45f, form.color)
}

private fun playTrailEffect(player: Player, form: RandomForm) {
    val color = form.trailColor ?: return
    val x = player.x + Random.nextFloat() * 8f - 4f
    val y = player.y + Random.nextFloat() * 8f - 4f
    Call.effect(Fx.healBlock, x, y, 1.2f, color)
}

private fun tryGiveDailyReward(player: Player, form: RandomForm) {
    val data = PlayerData[player]
    val rewardIds = (data.ids + data.id).distinct()
    val today = LocalDate.now().toString()
    if (rewardIds.any { MdtStorage.getRandomFormRewardDate(it) == today }) {
        player.sendMessage("[yellow]今天已经通过变换形态获取过MDC了哦！")
        return
    }

    rewardIds.forEach { MdtStorage.setRandomFormRewardDate(it, today) }
    with(trustPoint) { addTrustPoints(data.id, form.rewardPoints, form.rewardDesc) }
    player.sendMessage("[green]+${form.rewardPoints} MDC[white][${form.rewardDesc}]")
    player.sendMessage("[yellow]一天仅可通过变换形态获取一次MDC哦！（每种形态提供的MDC也不同）")
}

fun toggleRandomForm(player: Player) {
    val oldForm = currentForm(player)
    if (oldForm != null) {
        clearCurrentForm(player)
        refreshName(player)
        player.sendMessage("[green]你恢复了原本的状态")
        return
    }

    val form = randomForms.random()
    val oldName = player.name
    setCurrentForm(player, form)
    refreshName(player)
    playTransformEffect(player, form)
    broadcast("[gold]${oldName}[yellow]变成了[white]${form.displayName}[yellow]！".with())
    tryGiveDailyReward(player, form)
}

fun setRandomForm(player: Player, code: String, reward: Boolean = false, announce: Boolean = true): Boolean {
    val form = formByCode[code] ?: return false
    val oldName = player.name
    setCurrentForm(player, form)
    refreshName(player)
    playTransformEffect(player, form)
    if (announce) {
        broadcast("[gold]${oldName}[yellow]变成了[white]${form.displayName}[yellow]！".with())
    }
    if (reward) tryGiveDailyReward(player, form)
    return true
}

fun setCatgirlForm(player: Player, reward: Boolean = false, announce: Boolean = true): Boolean =
    setRandomForm(player, "catgirl", reward, announce)

private val formChatFilter = Administration.ChatFilter { player, msg ->
    currentForm(player)?.let { rewriteMessage(it, msg) } ?: msg
}

registerVarForType<Player>().apply {
    registerChild("prefix.2randomFormTitle", "随机形态强制头衔") {
        playerRandomFormTitle(PlayerData[it].id, it)
    }
}

onEnable {
    netServer.admins.chatFilters.add(formChatFilter)
    onDisable {
        netServer.admins.chatFilters.remove(formChatFilter)
    }

    loop(Dispatchers.game) {
        Groups.player.forEach { player ->
            currentForm(player)?.let { playTrailEffect(player, it) }
        }
        delay(800)
    }
}

command("randomform", "随机变换形态") {
    aliases = listOf("随机形态", "变换形态")
    attr(ClientOnly)
    body {
        toggleRandomForm(player!!)
    }
}
