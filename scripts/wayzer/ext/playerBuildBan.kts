@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration
import wayzer.lib.PlayerData
import java.util.concurrent.ConcurrentHashMap

private val trustLevel = contextScript<wayzer.user.TrustLevel>()
private val NO_BUILD_BAN = "\u0000"
private val WARN_INTERVAL_MILLIS = 2_000L
private data class TemporaryBuildBan(val reason: String, val untilMillis: Long)

@Savable
val buildBanReasons = mutableMapOf<String, String>()
customLoad(::buildBanReasons) { buildBanReasons.putAll(it) }

private val buildBanCache = ConcurrentHashMap<String, String>()
private val temporaryBuildBans = ConcurrentHashMap<String, TemporaryBuildBan>()
private val lastWarnAt = ConcurrentHashMap<String, Long>()
private val buildBanLock = Any()

private fun affectedKeys(data: PlayerData): Set<String> = (data.ids + data.id + data.uuid).filter { it.isNotBlank() }.toSet()

private fun loadBuildBanReason(data: PlayerData): String? = synchronized(buildBanLock) {
    affectedKeys(data).firstNotNullOfOrNull { buildBanReasons[it] }
}

private fun temporaryBuildBanReason(data: PlayerData): String? {
    val now = System.currentTimeMillis()
    var result: String? = null
    affectedKeys(data).forEach { key ->
        val record = temporaryBuildBans[key] ?: return@forEach
        if (record.untilMillis <= now) {
            temporaryBuildBans.remove(key, record)
        } else if (result == null) {
            result = record.reason
        }
    }
    return result
}

private fun cacheBuildBanReason(player: Player, reason: String?) {
    buildBanCache[player.uuid()] = reason ?: NO_BUILD_BAN
}

fun buildBanReason(player: Player): String? {
    temporaryBuildBanReason(PlayerData[player])?.let { return it }
    buildBanCache[player.uuid()]?.let { return it.takeIf { cached -> cached != NO_BUILD_BAN } }
    val reason = loadBuildBanReason(PlayerData[player])
    cacheBuildBanReason(player, reason)
    return reason
}

fun isBuildBanned(player: Player): Boolean = buildBanReason(player) != null

fun canManageBuildBan(operator: Player, target: Player): Boolean {
    return with(trustLevel) { canDirectRestrictTrustTarget(operator, target) }
}

fun disableBuild(target: Player, reason: String, operator: Player? = null): Boolean {
    if (operator != null && !canManageBuildBan(operator, target)) return false
    val data = PlayerData[target]
    val finalReason = reason.trim().ifEmpty { "未填写理由" }
    val keys = affectedKeys(data)
    synchronized(buildBanLock) {
        keys.forEach { buildBanReasons[it] = finalReason }
    }
    cacheBuildBanReason(target, finalReason)
    val operatorName = operator?.name ?: "系统"
    target.sendMessage(
        """
            |[red]你已被禁止建造/拆除
            |[yellow]原因：[white]$finalReason
            |[yellow]可联系有权限的协管/管理进行解除
        """.trimMargin()
    )
    operator?.sendMessage("[green]已禁止 [white]${target.name}[green] 建造/拆除，原因：[yellow]$finalReason")
    logger.info("$operatorName 禁止了 ${target.plainName()} 的建造/拆除权限，原因：$finalReason")
    return true
}

fun disableBuildTemporary(target: Player, minutes: Int, reason: String, operator: Player? = null): Boolean {
    if (operator != null && !canManageBuildBan(operator, target)) return false
    val fixedMinutes = minutes.coerceAtLeast(1)
    val data = PlayerData[target]
    val finalReason = reason.trim().ifEmpty { "未填写理由" }
    val until = System.currentTimeMillis() + fixedMinutes * 60_000L
    val record = TemporaryBuildBan(finalReason, until)
    affectedKeys(data).forEach { temporaryBuildBans[it] = record }
    buildBanCache.remove(target.uuid())
    val operatorName = operator?.name ?: "系统"
    target.sendMessage(
        """
            |[red]你已被临时禁止建造/拆除 [yellow]${fixedMinutes}分钟
            |[yellow]原因：[white]$finalReason
            |[yellow]可联系有权限的协管/管理进行解除
        """.trimMargin()
    )
    operator?.sendMessage("[green]已临时禁止 [white]${target.name}[green] 建造/拆除 [yellow]${fixedMinutes}分钟[green]，原因：[yellow]$finalReason")
    logger.info("$operatorName 临时禁止了 ${target.plainName()} 的建造/拆除权限 ${fixedMinutes}分钟，原因：$finalReason")
    return true
}

fun enableBuild(target: Player, operator: Player? = null): Boolean {
    if (operator != null && !canManageBuildBan(operator, target)) return false
    val data = PlayerData[target]
    val keys = affectedKeys(data)
    val removed = synchronized(buildBanLock) {
        var changed = false
        keys.forEach {
            if (temporaryBuildBans.remove(it) != null) changed = true
            if (buildBanReasons.remove(it) != null) changed = true
        }
        changed
    }
    if (!removed) {
        cacheBuildBanReason(target, null)
        return false
    }
    cacheBuildBanReason(target, null)
    val operatorName = operator?.name ?: "系统"
    target.sendMessage("[green]你已被解除建造/拆除限制")
    operator?.sendMessage("[green]已解除 [white]${target.name}[green] 的建造/拆除限制")
    logger.info("$operatorName 解除了 ${target.plainName()} 的建造/拆除限制")
    return true
}

private fun resolveOnlineTarget(text: String): Player? {
    if (text.startsWith("#")) {
        text.substring(1).toIntOrNull()?.let { id -> Groups.player.getByID(id)?.let { return it } }
    }
    PlayerData.findByShortId(text)?.player?.let { return it }
    val fixed = text.trim()
    val plain = fixed.replace(" ", "")
    return Groups.player.find {
        it.uuid() == fixed ||
                PlayerData[it].id == fixed ||
                PlayerData[it].shortId.equals(fixed, ignoreCase = true) ||
                it.name.equals(fixed, ignoreCase = true) ||
                it.plainName().equals(fixed, ignoreCase = true) ||
                it.name.replace(" ", "").equals(plain, ignoreCase = true) ||
                it.plainName().replace(" ", "").equals(plain, ignoreCase = true)
    }
}

private fun canCommandManage(operator: Player?, target: Player): Boolean =
    operator == null || canManageBuildBan(operator, target)

private fun canUseBuildBanCommand(operator: Player?): Boolean =
    operator == null || with(trustLevel) { hasTrustLevel(operator, "3+") }

private fun warnBuildBanned(player: Player, reason: String) {
    val now = System.currentTimeMillis()
    val last = lastWarnAt[player.uuid()] ?: 0L
    if (now - last < WARN_INTERVAL_MILLIS) return
    lastWarnAt[player.uuid()] = now
    player.sendMessage("[yellow]你已被禁止建造/拆除：[white]$reason[yellow]。可联系有权限的协管/管理解除。")
}

registerActionFilter {
    if (it.type == Administration.ActionType.placeBlock || it.type == Administration.ActionType.breakBlock) {
        val target = it.player ?: return@registerActionFilter true
        val reason = buildBanReason(target) ?: return@registerActionFilter true
        warnBuildBanned(target, reason)
        return@registerActionFilter false
    }
    true
}

listen<EventType.PlayerJoin> {
    val player = it.player
    val data = PlayerData[player]
    cacheBuildBanReason(player, loadBuildBanReason(data))
}

listen<EventType.PlayerLeave> {
    buildBanCache.remove(it.player.uuid())
    lastWarnAt.remove(it.player.uuid())
}

command("buildban", "管理指令：禁止玩家建造") {
    usage = "<玩家id/3位id/#游戏id> [理由]，或 /buildban list"
    aliases = listOf("nobuild", "禁建", "禁止建造")
    body {
        if (arg.isEmpty()) replyUsage()
        if (!canUseBuildBanCommand(player)) returnReply("[red]权限不足：需要3+级或4级。".with())
        if (arg[0].equals("list", ignoreCase = true) || arg[0] == "列表") {
            val online = Groups.player.toList().filter { isBuildBanned(it) }
            if (online.isEmpty()) returnReply("[green]当前没有在线玩家被禁止建造/拆除。".with())
            returnReply(online.joinToString("\n") { p ->
                "[yellow]${p.name}[gray](${PlayerData[p].shortId})[white]：${buildBanReason(p) ?: "未填写理由"}"
            }.with())
        }
        val target = resolveOnlineTarget(arg[0]) ?: returnReply("[red]未找到在线玩家".with())
        if (!canCommandManage(player, target)) returnReply("[red]权限不足：3+只能处理0/1/2级玩家，3++可处理低于3++的玩家，4级保留全局管理。".with())
        val reason = arg.drop(1).joinToString(" ").ifBlank { "未填写理由" }
        if (!disableBuild(target, reason, player)) returnReply("[yellow]操作者权限或目标等级已变化，禁建已取消。".with())
        reply("[green]已禁止 [white]{target.name}[green] 建造/拆除".with("target" to target))
    }
}

command("buildunban", "管理指令：解除玩家禁建") {
    usage = "<玩家id/3位id/#游戏id>"
    aliases = listOf("allowbuild", "unnobuild", "解除禁建", "允许建造")
    body {
        if (arg.isEmpty()) replyUsage()
        if (!canUseBuildBanCommand(player)) returnReply("[red]权限不足：需要3+级或4级。".with())
        val target = resolveOnlineTarget(arg[0]) ?: returnReply("[red]未找到在线玩家".with())
        if (!canCommandManage(player, target)) returnReply("[red]权限不足：3+只能处理0/1/2级玩家，3++可处理低于3++的玩家，4级保留全局管理。".with())
        if (enableBuild(target, player)) {
            reply("[green]已解除 [white]{target.name}[green] 的建造/拆除限制".with("target" to target))
        } else {
            reply("[yellow]目标当前未被禁止建造/拆除".with())
        }
    }
}
