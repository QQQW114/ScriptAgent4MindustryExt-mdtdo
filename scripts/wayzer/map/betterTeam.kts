@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/user/trustLevel", "信任等级")

package wayzer.map

import arc.Events
import coreLibrary.lib.CommandContext
import coreLibrary.lib.Commands
import mindustry.core.NetServer
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.world.blocks.storage.CoreBlock
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import wayzer.MapLoadFinishedEvent
import wayzer.MapManager
import wayzer.user.TrustLevel

name = "更好的队伍"

data class AssignTeamEvent(val player: Player, val group: Iterable<Player>, val oldTeam: Team?) : Event,
    Event.Cancellable {
    var team: Team? = oldTeam
        set(value) {
            field = value
            cancelled = true
        }
    override var cancelled: Boolean = false
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

val spectateTeam = Team.all[255]!!
private fun isPvpLike(): Boolean = state.rules.pvp || MapManager.current.mode == Gamemode.pvp

private fun inlineTagValue(name: String, description: String?): String? {
    val withAt = Regex.escape(name)
    val withoutAt = Regex.escape(name.removePrefix("@"))
    val regex = Regex("""\[(?:$withAt|$withoutAt)(?:=([^\]]*))?]""", RegexOption.IGNORE_CASE)
    val match = regex.find(description.orEmpty()) ?: return null
    return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "true"
}

private fun mapTagValue(name: String): String? {
    inlineTagValue(name, runCatching { state.map.description() }.getOrNull())?.let { return it }
    inlineTagValue(name, MapManager.current.description)?.let { return it }
    val aliases = listOf(name, name.removePrefix("@")).distinct()
    aliases.forEach { key ->
        state.rules.tags.get(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

private fun parseTeamToken(token: String): Team? {
    val value = token.trim().trim('"', '\'')
    if (value.isBlank()) return null
    val id = value.toIntOrNull()
    if (id != null) return Team.all.getOrNull(id)
    return Team.all.filterNotNull().firstOrNull { team ->
        team.name.equals(value, ignoreCase = true)
    }
}

private fun parseBannedTeams(): Set<Team> =
    mapTagValue("@banTeam")
        ?.split(Regex("""[,\s;，、]+"""))
        .orEmpty()
        .mapNotNull(::parseTeamToken)
        .toSet()

val allTeam: Set<Team>
    get() {
        if (!isPvpLike()) return setOf(state.rules.defaultTeam)
        return state.teams.getActive().mapTo(mutableSetOf()) { it.team }.apply {
            remove(Team.derelict)
            removeIf { !it.data().hasCore() }
            removeAll(bannedTeam)
        }
    }

var bannedTeam = emptySet<Team>()

private fun manualTeamOptions(): Set<Team> =
    state.teams.getActive().mapTo(mutableSetOf()) { it.team }.apply {
        remove(Team.derelict)
    }.takeIf { it.isNotEmpty() } ?: setOf(state.rules.defaultTeam)

onEnable {
    val backup = netServer.assigner
    netServer.assigner = NetServer.TeamAssigner { p, g ->
        randomTeam(p, g)
    }
    onDisable { netServer.assigner = backup }
    updateBannedTeam(true)
}
listen<EventType.WorldLoadEvent> { updateBannedTeam(true) }
listen<MapLoadFinishedEvent> { updateBannedTeam(true) }

val savedTeams = mutableMapOf<String, Team>()
private val trustLevel = contextScript<TrustLevel>()

private fun hasTeamCommandTrust(player: Player?): Boolean {
    if (player == null) return true
    return with(trustLevel) { hasTrustLevel(player, "3+") }
}

private val teamCommandAccess = object : Commands.Hidden {
    context(CommandContext)
    override suspend fun visible(): Boolean {
        val p = player ?: return true
        return hasPermission("wayzer.ext.team.change") || hasTeamCommandTrust(p)
    }
}

listen<EventType.ResetEvent> {
    bannedTeam = emptySet()
    savedTeams.clear()
}
listen<EventType.PlayerLeave> { savedTeams[it.player.uuid()] = it.player.team() }

//custom gameover
listen<EventType.BlockDestroyEvent> { e ->
    if (state.gameOver || !state.rules.pvp) return@listen
    if (e.tile.block() is CoreBlock)
        launch(Dispatchers.gamePost) {
            if (state.gameOver) return@launch
            allTeam.singleOrNull()?.let {
                state.gameOver = true
                Events.fire(EventType.GameOverEvent(it))
            }
        }
}
//fix 诈尸问题
listen<EventType.CoreChangeEvent> { e ->
    val team = e.core.team
    launch(Dispatchers.gamePost) {
        if (!team.active())
            Groups.build.filterIsInstance<CoreBuild>().forEach {
                if (it.lastDamage == team) it.lastDamage = Team.derelict
            }
    }
}

fun updateBannedTeam(force: Boolean = false) {
    if (force || bannedTeam.isEmpty())
        bannedTeam = parseBannedTeams()
    Groups.player.filter { it.team() in bannedTeam }.forEach {
        changeTeam(it)
        it.sendMessage("[yellow]因为原队伍被禁用,你已自动切换队伍".with(), MsgType.InfoMessage)
    }
}

fun randomTeam(player: Player, group: Iterable<Player> = Groups.player): Team {
    val allTeam = allTeam
    // 只在“玩家离线后重连”时尝试恢复原队伍。
    // 旧实现还会沿用 player.team()，这在PVP图加载/玩家新加入时很容易把所有人都留在默认队伍
    // （例如上一局全在 sharded，新PVP图也有 sharded 核心），导致看起来没有正常分队。
    val bak = savedTeams.remove(player.uuid())?.takeIf { it in allTeam }
    val fromEvent = Dispatchers.game.safeBlocking { AssignTeamEvent(player, group, bak).emitAsync() }.team
    if (fromEvent != null) return fromEvent
    if (!isPvpLike()) return state.rules.defaultTeam
    return allTeam.shuffled()
        .minByOrNull { group.count { p -> p.team() == it && player != p } }
        ?: state.rules.defaultTeam
}

fun changeTeam(p: Player, team: Team = randomTeam(p)) {
    p.clearUnit()
    p.team(team)
}

export(::changeTeam)
command("team", "队伍管理：切换队伍") {
    usage = "[队伍,不填列出] [玩家3位id,默认自己；指定他人需管理员权限]"
    attr(teamCommandAccess)
    body {
        val team = arg.getOrNull(0)?.toIntOrNull()?.let { Team.get(it) } ?: let {
            val teams = manualTeamOptions().map { t -> "{id}({team.colorizeName}[])".with("id" to t.id, "team" to t) }
            returnReply("[yellow]可用队伍: []{list}".with("list" to teams))
        }
        val targetArg = arg.getOrNull(1)
        val target = targetArg?.let {
            if (!hasPermission("wayzer.ext.team.change")) {
                returnReply("[red]只有管理员可以修改他人队伍。3+级玩家可直接使用 /team <队伍ID> 切换自己。".with())
            }
            PlayerData.findByShortId(it)?.player
                ?: returnReply("[red]找不到玩家,请使用/list查询正确的3位id".with())
        } ?: (player ?: returnReply("[red]请输入玩家ID".with()))
        val oldTeam = target.team()
        changeTeam(target, team)
        if (targetArg == null) {
            broadcast(
                "[yellow]{player.name}[green] 将自己的队伍从 {oldTeam.colorizeName}[green] 调整为 {team.colorizeName}[green]。".with(
                    "player" to target,
                    "oldTeam" to oldTeam,
                    "team" to team,
                )
            )
        } else {
            broadcast(
                "[green]管理员更改了{player.name}[green]的队伍：{oldTeam.colorizeName}[green] -> {team.colorizeName}".with(
                    "player" to target,
                    "oldTeam" to oldTeam,
                    "team" to team,
                )
            )
        }
    }
}

PermissionApi.registerDefault("wayzer.ext.team.change", group = "@admin")
