@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/ext/playerMute", "玩家禁言")

package wayzer.user

import wayzer.lib.PlayerData
import java.util.ArrayDeque

name = "MDT私聊系统"

private val trustLevel = contextScript<TrustLevel>()
private val playerMute = contextScript<wayzer.ext.PlayerMute>()

private val PM_MAX_LENGTH = 240
private val PM_RATE_WINDOW_MILLIS = 10_000L
private val PM_RATE_LIMIT = 5
private val PM_MIN_LEVEL = "1"

private val lastReplyTarget = mutableMapOf<String, String>()
private val pmRate = mutableMapOf<String, ArrayDeque<Long>>()

private fun playerUid(player: Player): String = PlayerData[player].id

private fun playerLabel(player: Player): String {
    val shortId = PlayerData[player].shortId
    return "${player.name}[gray]($shortId)[]"
}

private fun compactPm(text: String): String = text
    .replace('\n', ' ')
    .replace('\r', ' ')
    .trim()
    .take(PM_MAX_LENGTH)

private fun canUsePm(player: Player): Boolean {
    val minLevel = PM_MIN_LEVEL
    return with(trustLevel) { hasTrustLevel(player, minLevel) }
}

private fun resolveOnlineTarget(text: String): Player? {
    val fixed = text.trim()
    if (fixed.isEmpty()) return null
    if (fixed.startsWith("#")) {
        fixed.substring(1).toIntOrNull()?.let { id -> Groups.player.getByID(id)?.let { return it } }
    }
    PlayerData.findByShortId(fixed)?.player?.let { return it }
    val plain = fixed.replace(" ", "")
    return Groups.player.find {
        it.name.replace(" ", "").equals(plain, ignoreCase = true) ||
                it.plainName().replace(" ", "").equals(plain, ignoreCase = true)
    }
}

private fun resolveOnlineTargetByUuid(uuid: String): Player? =
    Groups.player.find { it.uuid() == uuid }

private fun rateLimitReason(player: Player): String? {
    val now = System.currentTimeMillis()
    val key = player.uuid()
    val queue = pmRate.getOrPut(key) { ArrayDeque() }
    while (!queue.isEmpty() && now - queue.peekFirst() > PM_RATE_WINDOW_MILLIS) queue.removeFirst()
    if (queue.size >= PM_RATE_LIMIT) {
        val left = ((PM_RATE_WINDOW_MILLIS - (now - queue.peekFirst())) / 1000).coerceAtLeast(1)
        return "[yellow]私聊发送过快，请 ${left}s 后再试。"
    }
    queue.addLast(now)
    return null
}

private fun sendPrivateMessage(sender: Player, target: Player, rawMessage: String) {
    if (!canUsePm(sender)) {
        sender.sendMessage("[yellow]等级不足：1级及以上玩家才能发送私聊。")
        return
    }
    if (with(playerMute) { isMuted(sender) }) {
        sender.sendMessage("[yellow]你已被禁言，暂时不能发送私聊。")
        return
    }
    if (sender.uuid() == target.uuid()) {
        sender.sendMessage("[yellow]不能给自己发私聊哦！")
        return
    }
    val message = compactPm(rawMessage)
    if (message.isBlank()) {
        sender.sendMessage("[yellow]私聊内容不能为空。")
        return
    }
    rateLimitReason(sender)?.let {
        sender.sendMessage(it)
        return
    }

    lastReplyTarget[sender.uuid()] = target.uuid()
    lastReplyTarget[target.uuid()] = sender.uuid()

    val from = playerLabel(sender)
    val to = playerLabel(target)
    sender.sendMessage("[pink][私聊] [white]你[pink] -> [white]$to[pink]: [white]$message")
    target.sendMessage("[pink][私聊] [white]$from[pink] -> [white]你[pink]: [white]$message\n[gray]可使用 /r <内容> 快速回复")
    logger.info("[MDT私聊] ${sender.plainName()} -> ${target.plainName()}")
}

listen<EventType.PlayerLeave> {
    lastReplyTarget.remove(it.player.uuid())
    lastReplyTarget.entries.removeIf { entry -> entry.value == it.player.uuid() }
    pmRate.remove(it.player.uuid())
}

command("msg", "给在线玩家发送私聊") {
    usage = "<玩家3位ID/#游戏ID/名字> <内容>"
    aliases = listOf("m", "tell", "w", "私聊")
    attr(ClientOnly)
    body {
        val sender = player!!
        if (arg.size < 2) replyUsage()
        val target = resolveOnlineTarget(arg[0]) ?: returnReply("[red]未找到在线玩家：${arg[0]}".with())
        sendPrivateMessage(sender, target, arg.drop(1).joinToString(" "))
    }
}

command("r", "回复最近私聊对象") {
    usage = "<内容>"
    aliases = listOf("reply", "回复")
    attr(ClientOnly)
    body {
        val sender = player!!
        if (arg.isEmpty()) replyUsage()
        val targetUuid = lastReplyTarget[sender.uuid()]
            ?: returnReply("[yellow]没有可回复的最近私聊对象。".with())
        val target = resolveOnlineTargetByUuid(targetUuid)
            ?: returnReply("[yellow]最近私聊对象已离线。".with())
        sendPrivateMessage(sender, target, arg.joinToString(" "))
    }
}
