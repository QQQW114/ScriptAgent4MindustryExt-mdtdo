@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("coreMindustry/utilNextChat", "聊天包拦截")
@file:Depends("wayzer/user/trustLevel", "统一即时限制目标边界")

package wayzer.ext

import coreMindustry.UtilNextChat.OnChat
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.PlayerMuteChangedEvent
import wayzer.user.TrustLevel

private val trustLevel = contextScript<TrustLevel>()

private val NO_MUTE = "\u0000"
private val muteReasonCache = java.util.concurrent.ConcurrentHashMap<String, String>()
private data class TemporaryMute(val reason: String, val untilMillis: Long)
private val temporaryMutes = java.util.concurrent.ConcurrentHashMap<String, TemporaryMute>()

private fun emitMuteChanged(uids: Set<String>) {
    launch { PlayerMuteChangedEvent(uids).emitAsync() }
}

private fun resolveOnlineTarget(text: String): Player? {
    if (text.startsWith("#")) {
        text.substring(1).toIntOrNull()?.let { id -> Groups.player.getByID(id)?.let { return it } }
    }
    PlayerData.findByShortId(text)?.player?.let { return it }
    val plain = text.replace(" ", "")
    return Groups.player.find { it.name.replace(" ", "") == plain || it.plainName().replace(" ", "") == plain }
}

private fun affectedKeys(data: PlayerData): Set<String> =
    (data.ids + data.id + data.uuid).filter { it.isNotBlank() }.toSet()

private fun loadMuteReason(data: PlayerData): String? {
    for (id in affectedKeys(data)) {
        MdtStorage.getMuteReason(id)?.let { return it }
    }
    return null
}

private fun temporaryMuteReason(data: PlayerData): String? {
    val now = System.currentTimeMillis()
    val keys = affectedKeys(data)
    var result: String? = null
    keys.forEach { key ->
        val mute = temporaryMutes[key] ?: return@forEach
        if (mute.untilMillis <= now) {
            temporaryMutes.remove(key, mute)
        } else if (result == null) {
            result = mute.reason
        }
    }
    return result
}

private fun cacheMuteReason(uuid: String, reason: String?) {
    muteReasonCache[uuid] = reason ?: NO_MUTE
}

fun muteReason(player: Player): String? {
    temporaryMuteReason(PlayerData[player])?.let { return it }
    muteReasonCache[player.uuid()]?.let { return it.takeIf { reason -> reason != NO_MUTE } }
    val reason = loadMuteReason(PlayerData[player])
    cacheMuteReason(player.uuid(), reason)
    return reason
}

fun isMuted(target: Player): Boolean = muteReason(target) != null

fun canManagePlayerMute(operator: Player, target: Player): Boolean =
    with(trustLevel) { canDirectRestrictTrustTarget(operator, target) }

fun mutePlayer(target: Player, reason: String, operator: Player? = null): Boolean {
    if (operator != null && !canManagePlayerMute(operator, target)) return false
    val finalReason = reason.trim().ifEmpty { "未填写理由" }
    val operatorUid = operator?.let { PlayerData[it].id }
    val targetData = PlayerData[target]
    val affectedUids = affectedKeys(targetData)
    affectedUids.forEach { MdtStorage.setMute(it, finalReason, operatorUid) }

    val operatorName = operator?.name ?: "系统"
    target.sendMessage(
        """
            |[red]你已被禁言
            |[yellow]原因：[white]$finalReason
            |[yellow]可联系有权限的协管/管理进行解除
        """.trimMargin()
    )
    operator?.sendMessage("[green]已禁言 [white]${target.name}[green]，原因：[yellow]$finalReason")
    logger.info("$operatorName 禁言了 ${target.plainName()}，原因：$finalReason")
    cacheMuteReason(target.uuid(), finalReason)
    emitMuteChanged(affectedUids)
    return true
}

fun mutePlayerTemporary(target: Player, minutes: Int, reason: String, operator: Player? = null): Boolean {
    if (operator != null && !canManagePlayerMute(operator, target)) return false
    val fixedMinutes = minutes.coerceAtLeast(1)
    val finalReason = reason.trim().ifEmpty { "未填写理由" }
    val until = System.currentTimeMillis() + fixedMinutes * 60_000L
    val targetData = PlayerData[target]
    val affectedUids = affectedKeys(targetData)
    val record = TemporaryMute(finalReason, until)
    affectedUids.forEach { temporaryMutes[it] = record }
    muteReasonCache.remove(target.uuid())

    val operatorName = operator?.name ?: "系统"
    target.sendMessage(
        """
            |[red]你已被临时禁言 [yellow]${fixedMinutes}分钟
            |[yellow]原因：[white]$finalReason
            |[yellow]可联系有权限的协管/管理进行解除
        """.trimMargin()
    )
    operator?.sendMessage("[green]已临时禁言 [white]${target.name}[green] [yellow]${fixedMinutes}分钟[green]，原因：[yellow]$finalReason")
    logger.info("$operatorName 临时禁言了 ${target.plainName()} ${fixedMinutes}分钟，原因：$finalReason")
    emitMuteChanged(affectedUids)
    return true
}

fun unmutePlayer(target: Player, operator: Player? = null): Boolean {
    if (operator != null && !canManagePlayerMute(operator, target)) return false
    val data = PlayerData[target]
    var removed = false
    affectedKeys(data).forEach {
        if (temporaryMutes.remove(it) != null) removed = true
        if (MdtStorage.clearMute(it)) removed = true
    }
    if (!removed) return false

    val operatorName = operator?.name ?: "系统"
    target.sendMessage("[green]你已被解除禁言")
    operator?.sendMessage("[green]已解除 [white]${target.name}[green] 的禁言")
    logger.info("$operatorName 解除了 ${target.plainName()} 的禁言")
    cacheMuteReason(target.uuid(), null)
    emitMuteChanged((data.ids + data.id).toSet())
    return true
}

private fun shouldBlockMutedInput(text: String): Boolean {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return true

    val commandName = trimmed.removePrefix("/")
        .substringBefore(" ")
        .substringBefore("\t")
        .lowercase()

    // 禁言玩家不允许继续通过聊天类指令或投票聊天入口绕过限制。
    return commandName in setOf("t", "a", "vote", "投票", "votekick")
}

listenTo<OnChat> {
    if (!shouldBlockMutedInput(text)) return@listenTo
    muteReason(player) ?: return@listenTo
    received = true
    launch(Dispatchers.game) {
        player.sendMessage("[yellow]你已被禁言，可联系有权限的协管/管理进行解除")
    }
}

listen<EventType.PlayerJoin> {
    val uuid = it.player.uuid()
    val data = PlayerData[it.player]
    launch(Dispatchers.IO) {
        runCatching { cacheMuteReason(uuid, loadMuteReason(data)) }
            .onFailure { logger.warning("预加载玩家禁言状态失败: ${data.name} ${it.message}") }
    }
}

listen<EventType.PlayerLeave> {
    muteReasonCache.remove(it.player.uuid())
}

command("playermute", "管理指令：禁言玩家") {
    usage = "<玩家id/3位id/#游戏id> [理由]"
    aliases = listOf("pmute", "mute", "禁言")
    permission = "wayzer.admin.playerMute"
    body {
        if (arg.isEmpty()) replyUsage()
        val target = resolveOnlineTarget(arg[0]) ?: returnReply("[red]未找到在线玩家".with())
        if (player != null && !canManagePlayerMute(player!!, target)) {
            returnReply("[red]权限不足：3+只能处理0/1/2级玩家，3++可处理低于3++的玩家，4级保留全局管理。".with())
        }
        val reason = arg.drop(1).joinToString(" ").ifBlank { "未填写理由" }
        if (!mutePlayer(target, reason, player)) returnReply("[yellow]操作者权限或目标等级已变化，禁言已取消。".with())
        reply("[green]已禁言 [white]{target.name}".with("target" to target))
    }
}

command("playerunmute", "管理指令：解除玩家禁言") {
    usage = "<玩家id/3位id/#游戏id>"
    aliases = listOf("punmute", "unmute", "解除禁言")
    permission = "wayzer.admin.playerMute"
    body {
        if (arg.isEmpty()) replyUsage()
        val target = resolveOnlineTarget(arg[0]) ?: returnReply("[red]未找到在线玩家".with())
        if (player != null && !canManagePlayerMute(player!!, target)) {
            returnReply("[red]权限不足：3+只能处理0/1/2级玩家，3++可处理低于3++的玩家，4级保留全局管理。".with())
        }
        if (unmutePlayer(target, player)) {
            reply("[green]已解除 [white]{target.name}[green] 的禁言".with("target" to target))
        } else {
            reply("[yellow]目标当前未被禁言".with())
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.playerMute", group = "@admin")

