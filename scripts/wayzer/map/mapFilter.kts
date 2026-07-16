@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/vote", "投票服务")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.map

import arc.util.Strings.stripColors
import arc.util.Strings.truncate
import mindustry.game.Gamemode
import mindustry.gen.Player
import wayzer.GetNextMapEvent
import wayzer.MapChangeEvent
import wayzer.MapInfo
import wayzer.MapRegistry
import wayzer.VoteEvent
import wayzer.lib.MdtStorage
import wayzer.user.TrustLevel
import java.time.LocalDate

name = "地图筛选系统"

private val BANNED_MAP_IDS_KEY = "mapFilter.bannedMapIds"
private val PVP_DISABLED_DATE_KEY = "mapFilter.pvpDisabledDate"
private val PERF_FORCE_MAP_BYPASS_KEY = "performanceGuard.experimental.forceChangingMap"

private val trustLevel = contextScript<TrustLevel>()

private fun todayString(): String = LocalDate.now().toString()

fun bannedMapIds(): Set<Int> =
    MdtStorage.getSetting(BANNED_MAP_IDS_KEY)
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .toSet()

private fun saveBannedMapIds(ids: Set<Int>) {
    val value = ids.filter { it > 0 }.sorted().joinToString(",")
    MdtStorage.setSetting(BANNED_MAP_IDS_KEY, value.takeIf { it.isNotEmpty() })
}

fun isMapBanned(id: Int): Boolean = id in bannedMapIds()

fun banMapId(id: Int): Boolean {
    if (id <= 0) return false
    val ids = bannedMapIds().toMutableSet()
    val added = ids.add(id)
    if (added) saveBannedMapIds(ids)
    return added
}

fun unbanMapId(id: Int): Boolean {
    val ids = bannedMapIds().toMutableSet()
    val removed = ids.remove(id)
    if (removed) saveBannedMapIds(ids)
    return removed
}

fun isPvpClosedToday(): Boolean = MdtStorage.getSetting(PVP_DISABLED_DATE_KEY) == todayString()

fun closePvpToday() {
    MdtStorage.setSetting(PVP_DISABLED_DATE_KEY, todayString())
}

fun openPvpToday() {
    MdtStorage.setSetting(PVP_DISABLED_DATE_KEY, null)
}

fun blockReason(info: MapInfo): String? = when {
    isMapBanned(info.id) -> "地图ID ${info.id} 已被封禁"
    isPvpClosedToday() && info.mode == Gamemode.pvp -> "今日PVP已关闭"
    else -> null
}

private fun isPerformanceForceChangingMap(): Boolean =
    MdtStorage.getSetting(PERF_FORCE_MAP_BYPASS_KEY) == "true"

private fun canManageMapFilter(operator: Player?): Boolean {
    if (operator == null) return true // 控制台
    return with(trustLevel) { hasTrustLevel(operator, "3+") }
}

private suspend fun chooseAllowedMap(previous: MapInfo?, preferredMode: Gamemode): MapInfo? {
    val maps = MapRegistry.searchMaps()
        .filter { it != previous }
        .filter { blockReason(it) == null }
    return maps.filter { it.mode == preferredMode }.randomOrNull()
        ?: maps.randomOrNull()
        ?: MapRegistry.searchMaps().filter { blockReason(it) == null }.randomOrNull()
}

private fun mapBrief(info: MapInfo): String =
    "${info.id}: ${stripColors(info.name)} | ${info.mode}"

private suspend fun startBanMapVote(starter: Player, map: MapInfo): Boolean {
    if (isMapBanned(map.id)) {
        starter.sendMessage("[yellow]地图 ${mapBrief(map)} 已在封禁列表中")
        return false
    }
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = "封禁地图([green]{map.id}[]: [green]{map.name}[yellow]|[green]{map.mode}[])".with("map" to map),
        extDesc = "[white]地图作者: [lightgrey]${stripColors(map.author)}[][]\n" +
                "[white]地图简介: [lightgrey]${truncate(stripColors(map.description), 100, "...")}[][]",
    )
    if (!event.awaitResult()) return false
    if (banMapId(map.id)) {
        broadcast("[yellow]投票通过：已封禁地图 [white]${mapBrief(map)}[yellow]，后续自动换图将跳过此地图。".with())
    } else {
        starter.sendMessage("[yellow]该地图已经被封禁")
    }
    return true
}

private suspend fun startPvpCloseVote(starter: Player): Boolean {
    if (isPvpClosedToday()) {
        starter.sendMessage("[yellow]今日PVP已经关闭")
        return false
    }
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = "关闭今日PVP".with(),
        extDesc = "[yellow]通过后，今天内自动换图会跳过PVP地图，也会阻止手动切换到PVP地图。",
    )
    if (!event.awaitResult()) return false
    closePvpToday()
    broadcast("[yellow]投票通过：今日PVP已关闭，后续换图将跳过PVP地图。".with())
    return true
}

private suspend fun startPvpOpenVote(starter: Player): Boolean {
    if (!isPvpClosedToday()) {
        starter.sendMessage("[yellow]今日PVP未关闭，无需开启")
        return false
    }
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = "开启今日PVP".with(),
        extDesc = "[yellow]通过后，今天内允许自动换图或手动切换到PVP地图。",
    )
    if (!event.awaitResult()) return false
    openPvpToday()
    broadcast("[green]投票通过：今日PVP已重新开启。".with())
    return true
}

listenTo<GetNextMapEvent>(Event.Priority.After) {
    if (isPerformanceForceChangingMap()) return@listenTo
    val reason = blockReason(mapInfo) ?: return@listenTo
    val preferredMode = if (isPvpClosedToday() && mapInfo.mode == Gamemode.pvp) Gamemode.survival else mapInfo.mode
    val replacement = chooseAllowedMap(previous, preferredMode)
    if (replacement == null) {
        logger.warning("地图筛选系统：候选地图 ${mapBrief(mapInfo)} 被跳过($reason)，但没有找到可用替代地图。")
        return@listenTo
    }
    logger.info("地图筛选系统：跳过 ${mapBrief(mapInfo)}，原因: $reason；改为 ${mapBrief(replacement)}")
    mapInfo = replacement
}

listenTo<MapChangeEvent>(Event.Priority.Intercept) {
    if (isPerformanceForceChangingMap()) return@listenTo
    val reason = blockReason(info) ?: return@listenTo
    cancelled = true
    broadcast("[red]地图筛选系统已阻止换图：[white]${mapBrief(info)}[red]，原因：$reason".with())
}

onEnable {
    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "banmap", "[green]封禁地图[gray]（需50%同意）") {
        aliases = listOf("封禁地图", "地图封禁")
        usage = "<地图ID>"
        permission = "wayzer.vote.banmap"
        body {
            if (arg.isEmpty()) replyUsage()
            val id = arg[0].toIntOrNull() ?: returnReply("[red]请输入正确的地图ID".with())
            val map = MapRegistry.findById(id, reply)
                ?: returnReply("[red]地图ID错误，无法找到此地图".with())
            startBanMapVote(player!!, map)
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "pvpOff", "[green]关闭今日PVP[gray]（需50%同意）") {
        aliases = listOf("pvpoff", "关闭pvp", "关闭今日pvp")
        permission = "wayzer.vote.pvpday"
        body {
            startPvpCloseVote(player!!)
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "pvpOn", "[green]开启今日PVP[gray]（需50%同意）") {
        aliases = listOf("pvpon", "开启pvp", "开启今日pvp")
        permission = "wayzer.vote.pvpday"
        body {
            startPvpOpenVote(player!!)
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "pvp", "[green]开启/关闭今日PVP[gray]（需50%同意）") {
        usage = "<on|off|status>"
        aliases = listOf("今日pvp")
        permission = "wayzer.vote.pvpday"
        body {
            when (arg.getOrNull(0)?.lowercase()) {
                "off", "false", "0", "disable", "close", "关闭" -> startPvpCloseVote(player!!)
                "on", "true", "1", "enable", "open", "开启" -> startPvpOpenVote(player!!)
                "status", "状态" -> player!!.sendMessage(
                    if (isPvpClosedToday()) "[yellow]今日PVP：已关闭"
                    else "[green]今日PVP：已开启"
                )
                else -> replyUsage()
            }
        }
    }
}

command("banmap", "地图筛选：封禁地图ID") {
    usage = "<地图ID>"
    body {
        if (!canManageMapFilter(player)) {
            returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以直接封禁地图".with())
        }
        if (arg.isEmpty()) replyUsage()
        val id = arg[0].toIntOrNull()?.takeIf { it > 0 }
            ?: returnReply("[red]请输入正确的地图ID".with())
        if (banMapId(id)) {
            broadcast("[yellow]{operator}[yellow] 已封禁地图ID [white]{id}[yellow]，后续自动换图将跳过此地图。".with(
                "operator" to (player?.name ?: "控制台"),
                "id" to id
            ))
        } else {
            reply("[yellow]地图ID [white]{id}[yellow] 已在封禁列表中".with("id" to id))
        }
    }
}

command("unbanmap", "地图筛选：解除地图ID封禁") {
    usage = "<地图ID>"
    aliases = listOf("解封地图", "解除地图封禁")
    body {
        if (!canManageMapFilter(player)) {
            returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以解除地图封禁".with())
        }
        if (arg.isEmpty()) replyUsage()
        val id = arg[0].toIntOrNull()?.takeIf { it > 0 }
            ?: returnReply("[red]请输入正确的地图ID".with())
        if (unbanMapId(id)) {
            broadcast("[green]{operator}[green] 已解除地图ID [white]{id}[green] 的封禁。".with(
                "operator" to (player?.name ?: "控制台"),
                "id" to id
            ))
        } else {
            reply("[yellow]地图ID [white]{id}[yellow] 不在封禁列表中".with("id" to id))
        }
    }
}

command("banmaps", "地图筛选：查看已封禁地图ID") {
    aliases = listOf("mapbans", "封禁地图列表")
    body {
        val ids = bannedMapIds().sorted()
        reply(
            if (ids.isEmpty()) "[green]当前没有封禁地图ID".with()
            else "[yellow]已封禁地图ID：[white]{ids}".with("ids" to ids.joinToString(", "))
        )
    }
}

command("todaypvp", "地图筛选：查看/设置今日PVP开关") {
    usage = "[on|off|status]"
    aliases = listOf("pvpday", "今日pvp")
    body {
        val op = arg.getOrNull(0)?.lowercase() ?: "status"
        when (op) {
            "status", "状态" -> reply(
                if (isPvpClosedToday()) "[yellow]今日PVP：已关闭".with()
                else "[green]今日PVP：已开启".with()
            )
            "off", "false", "0", "disable", "close", "关闭" -> {
                if (!canManageMapFilter(player)) {
                    returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以直接关闭今日PVP".with())
                }
                closePvpToday()
                broadcast("[yellow]{operator}[yellow] 已关闭今日PVP，后续换图将跳过PVP地图。".with(
                    "operator" to (player?.name ?: "控制台")
                ))
            }
            "on", "true", "1", "enable", "open", "开启" -> {
                if (!canManageMapFilter(player)) {
                    returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以直接开启今日PVP".with())
                }
                openPvpToday()
                broadcast("[green]{operator}[green] 已开启今日PVP。".with(
                    "operator" to (player?.name ?: "控制台")
                ))
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.vote.banmap", "wayzer.vote.pvpday")
