@file:Depends("wayzer/user/accountAuth", "MDT账号注册登录")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/vote", "投票服务")
@file:Depends("wayzer/map/betterTeam", "强制观察者")

package wayzer.user

import wayzer.VoteEvent
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.TrustLevelChangedEvent
import wayzer.map.BetterTeam
import java.time.LocalDate

private val GUEST_FORCE_OB_DATE_KEY = "account.guestForceObDate"
private val GUEST_TIP_INTERVAL_MILLIS = 30_000L

private val teams = contextScript<BetterTeam>()
private val lastGuestTips = mutableMapOf<String, Long>()
private val guestForceObUuids = mutableSetOf<String>()
private val guestForceObStateLock = Any()
private var guestForceObDateCache: String? = null
private var guestForceObCacheVersion = 0L

private fun todayString(): String = LocalDate.now().toString()

fun isGuestForceObToday(): Boolean =
    synchronized(guestForceObStateLock) { guestForceObDateCache == todayString() }

private fun persistGuestForceObDateAsync(value: String?) {
    launch(Dispatchers.IO) {
        runCatching { MdtStorage.setSetting(GUEST_FORCE_OB_DATE_KEY, value) }
            .onFailure { logger.warning("今日游客观战状态持久化失败: ${it.message}") }
    }
}

fun enableGuestForceObToday() {
    val today = todayString()
    synchronized(guestForceObStateLock) {
        guestForceObDateCache = today
        guestForceObCacheVersion += 1
    }
    persistGuestForceObDateAsync(today)
}

fun disableGuestForceObToday() {
    synchronized(guestForceObStateLock) {
        guestForceObDateCache = null
        guestForceObCacheVersion += 1
    }
    persistGuestForceObDateAsync(null)
}

private fun loadGuestForceObStateAsync() {
    val version = synchronized(guestForceObStateLock) { guestForceObCacheVersion }
    launch(Dispatchers.IO) {
        val stored = runCatching { MdtStorage.getSetting(GUEST_FORCE_OB_DATE_KEY) }
            .onFailure { logger.warning("今日游客观战状态加载失败: ${it.message}") }
            .getOrNull()
        synchronized(guestForceObStateLock) {
            if (guestForceObCacheVersion == version) {
                guestForceObDateCache = stored
            }
        }
    }
}

private fun tipGuest(player: Player) {
    val now = System.currentTimeMillis()
    val key = player.uuid()
    if ((lastGuestTips[key] ?: 0L) + GUEST_TIP_INTERVAL_MILLIS > now) return
    lastGuestTips[key] = now
    player.sendMessage("[yellow]今日已启用未登录玩家强制观战。请使用 [gold]/login[] 登录，或 [gold]/register[] 注册。")
}

fun forceOnlineGuestsToOb() {
    Groups.player.forEach { player ->
        if (!PlayerData[player].authed) {
            teams.changeTeam(player, teams.spectateTeam)
            guestForceObUuids += player.uuid()
            tipGuest(player)
        }
    }
}

fun releaseOnlineGuestsFromOb(includeUntracked: Boolean = false): Int {
    var released = 0
    Groups.player.forEach { player ->
        val tracked = player.uuid() in guestForceObUuids
        if ((tracked || includeUntracked) && !PlayerData[player].authed && player.team() == teams.spectateTeam) {
            teams.changeTeam(player)
            player.sendMessage("[green]今日未登录玩家强制观战已解除，你已被重新分配队伍。")
            released++
        }
    }
    guestForceObUuids.clear()
    return released
}

private suspend fun startGuestForceObVote(starter: Player): Boolean {
    if (isGuestForceObToday()) {
        starter.sendMessage("[yellow]今日已经启用未登录玩家强制观战")
        return false
    }
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = "今日未登录玩家强制观战".with(),
        extDesc = "[yellow]通过后，今天内未登录账号的玩家会被强制分配为观察者；登录或注册后可正常游玩。",
        canVote = { PlayerData[it].authed },
    )
    if (!event.awaitResult()) return false
    enableGuestForceObToday()
    broadcast("[yellow]投票通过：今日未登录玩家将被强制观战。未登录玩家可使用 [gold]/login[] 或 [gold]/register[]。".with())
    forceOnlineGuestsToOb()
    return true
}

private suspend fun startGuestForceObReleaseVote(starter: Player): Boolean {
    if (!isGuestForceObToday()) {
        starter.sendMessage("[yellow]今日未启用未登录玩家强制观战，无需解除")
        return false
    }
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = "解除今日未登录玩家强制观战".with(),
        extDesc = "[yellow]通过后，今天内未登录账号的玩家不再被强制分配为观察者。",
        canVote = { PlayerData[it].authed },
    )
    if (!event.awaitResult()) return false
    disableGuestForceObToday()
    broadcast("[green]投票通过：今日未登录玩家强制观战已解除。".with())
    releaseOnlineGuestsFromOb()
    return true
}

onEnable {
    loadGuestForceObStateAsync()
    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "guestOb", "[cyan]今日未登录玩家强制观战[gray]（需50%同意）") {
        aliases = listOf("游客观战", "未登录观战")
        permission = "wayzer.vote.guestOb"
        body {
            val starter = player!!
            startGuestForceObVote(starter)
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "guestObOff", "[yellow]解除今日未登录玩家强制观战[gray]（需50%同意）") {
        aliases = listOf("解除游客观战", "解除未登录观战", "游客观战解除")
        permission = "wayzer.vote.guestOb"
        body {
            val starter = player!!
            startGuestForceObReleaseVote(starter)
        }
    }
}

listenTo<BetterTeam.AssignTeamEvent>(Event.Priority.Intercept) {
    if (PlayerData[player].authed) return@listenTo
    if (!isGuestForceObToday()) return@listenTo
    team = teams.spectateTeam
    guestForceObUuids += player.uuid()
    tipGuest(player)
}

listen<EventType.PlayerJoin> {
    if (isGuestForceObToday() && !PlayerData[it.player].authed) {
        tipGuest(it.player)
    }
}

listen<EventType.PlayerLeave> {
    val uuid = it.player.uuid()
    lastGuestTips.remove(uuid)
    if (!isGuestForceObToday()) guestForceObUuids.remove(uuid)
}

listenTo<TrustLevelChangedEvent> {
    val player = Groups.player.find { PlayerData[it].id == uid } ?: return@listenTo
    if (PlayerData[player].authed && player.uuid() in guestForceObUuids && player.team() == teams.spectateTeam) {
        guestForceObUuids.remove(player.uuid())
        teams.changeTeam(player)
        player.sendMessage("[green]你已登录账号，今日游客观战限制不再作用于你。")
    }
}

listen<EventType.ResetEvent> {
    lastGuestTips.clear()
    if (!isGuestForceObToday()) guestForceObUuids.clear()
}

command("guestob", "管理指令：查看/设置今日未登录玩家强制观战") {
    usage = "[on|off|status|release]"
    permission = "wayzer.admin.account"
    aliases = listOf("游客观战控制", "guestforceob")
    body {
        if (player != null && !player!!.hasPermission("wayzer.admin.account")) {
            returnReply("[red]权限不足：需要账号管理权限".with())
        }
        val op = arg.getOrNull(0)?.lowercase() ?: "status"
        when (op) {
            "status", "状态" -> reply(
                ((if (isGuestForceObToday()) "[yellow]今日未登录玩家强制观战：已启用"
                else "[green]今日未登录玩家强制观战：未启用") +
                        "\n[white]本脚本记录的观战游客：[yellow]${guestForceObUuids.size}"
                ).with()
            )
            "on", "true", "1", "enable", "启用", "开启" -> {
                enableGuestForceObToday()
                forceOnlineGuestsToOb()
                reply("[green]已启用：今日未登录玩家将被强制观战".with())
            }
            "off", "false", "0", "disable", "关闭" -> {
                disableGuestForceObToday()
                val count = releaseOnlineGuestsFromOb()
                reply("[green]已关闭今日未登录玩家强制观战，并释放本脚本记录的 [yellow]$count [green]名游客。".with())
            }
            "release", "释放", "解除残留" -> {
                val count = releaseOnlineGuestsFromOb(includeUntracked = true)
                reply("[green]已尝试释放未登录玩家观战残留：[yellow]$count [green]名游客。若安全风控/IP风险/投票强制观战仍生效，会重新进入观战。".with())
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.vote.guestOb")

