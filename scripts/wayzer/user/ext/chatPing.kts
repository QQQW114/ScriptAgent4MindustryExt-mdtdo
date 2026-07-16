@file:Depends("wayzer/user/shortID")

package wayzer.user.ext

import arc.util.Strings
import coreLibrary.lib.PermissionApi
import mindustry.net.Administration

val regex = Regex("@([a-zA-Z0-9+/]{3})")
val pingAllCache = mutableMapOf<String, Boolean>()

fun refreshPingAllPermission(p: Player) {
    launch {
        pingAllCache[p.uuid()] = p.hasPermission("$dotId.pingAll")
    }
}

fun Player.canPingAllCached(): Boolean {
    if (admin) return true
    val cached = pingAllCache[uuid()]
    if (cached != true) refreshPingAllPermission(this)
    return cached == true
}

val filter = Administration.ChatFilter { p, msg ->
    val players = mutableListOf<Player>()
    msg.replace(regex) { match ->
        val id = match.groupValues[1]
        val name = if (id == "all" && p.canPingAllCached()) {
            players.addAll(Groups.player)
            "全体成员"
        } else {
            val player = PlayerData.findByShortId(id)?.player ?: return@replace match.value
            players.add(player)
            Strings.stripColors(player.name)
        }
        " [gold]@$name[] "
    }.also { newMsg ->
        players.forEach {
            Call.announce(it.con, "[red]有人在聊天区@你，请注意查看:[]\n$newMsg")
        }
    }
}
listen<EventType.PlayerJoin> { refreshPingAllPermission(it.player) }
listen<EventType.PlayerLeave> { pingAllCache.remove(it.player.uuid()) }
onEnable { netServer.admins.chatFilters.add(filter) }
onDisable { netServer.admins.chatFilters.remove(filter) }
PermissionApi.registerDefault("$dotId.pingAll", group = "@admin")
