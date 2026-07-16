@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/map/mapFilter", "地图筛选系统")

package wayzer.cmds

import arc.util.Strings.stripColors
import arc.util.Strings.truncate
import wayzer.*
import wayzer.map.MapFilter

private val mapFilter = contextScript<MapFilter>()

private fun mapVoteRequireNum(all: Int): Int =
    ((all.coerceAtLeast(1) * 51) + 99) / 100

private fun ensureMapAllowedForVote(player: Player, map: MapInfo): Boolean {
    val reason = with(mapFilter) { blockReason(map) }
    if (reason != null) {
        player.sendMessage("[red]无法发起换图投票：该地图被地图筛选系统阻止，原因：$reason".with())
        return false
    }
    return true
}

fun voteMap(player: Player, map: MapInfo) {
    launch(Dispatchers.game) {
        if (!ensureMapAllowedForVote(player, map)) return@launch
        val event = VoteEvent(
            this, player,
            "换图([green]{nextMap.id}[]: [green]{nextMap.name}[yellow]|[green]{nextMap.mode}[])".with("nextMap" to map),
            extDesc = "[white]地图作者: [lightgrey]${stripColors(map.author)}[][]\n" +
                    "[white]地图简介: [lightgrey]${truncate(stripColors(map.description), 100, "...")}[][]",
            supportSingle = true,
            requireNum = ::mapVoteRequireNum,
        )
        if (!event.awaitResult()) return@launch
        broadcast("[yellow]异步加载地图中，请耐心等待".with())
        if (MapManager.loadMapSync(map))
            broadcast("[green]换图成功,当前地图[yellow]{map.name}[green](id: {map.id})".with())
    }
}

fun voteNextAutoMap(player: Player, map: MapInfo) {
    launch(Dispatchers.game) {
        if (!ensureMapAllowedForVote(player, map)) return@launch
        if (map == MapManager.current) {
            player.sendMessage("[yellow]目标地图已经是当前地图，不建议设置为下次自动轮换地图。".with())
            return@launch
        }
        val pending = MapManager.getPendingAutoRotateMap()
        val event = VoteEvent(
            this, player,
            "下次自动轮换地图([green]{nextMap.id}[]: [green]{nextMap.name}[yellow]|[green]{nextMap.mode}[])".with("nextMap" to map),
            extDesc = buildString {
                append("[white]注：投票通过后不会立刻换图，而是在下一次自动轮换地图时切换到该地图。[][]\n")
                append("[gray]如果期间被管理员或其他机制手动换图，本计划仍会保留，直到下一次自动轮换时生效。[][]\n")
                append("[white]地图作者: [lightgrey]${stripColors(map.author)}[][]\n")
                append("[white]地图简介: [lightgrey]${truncate(stripColors(map.description), 100, "...")}[][]")
                if (pending != null) {
                    append("\n[yellow]当前已有计划：${pending.target.id} ${stripColors(pending.target.name)}，通过后会被本次投票覆盖。")
                }
            },
            supportSingle = true,
            requireNum = ::mapVoteRequireNum,
        )
        if (!event.awaitResult()) return@launch
        MapManager.setPendingAutoRotateMap(map, player.plainName())
        broadcast(
            "[green]投票通过：下次自动轮换地图将换到 [yellow]{nextMap.name}[green](id: {nextMap.id})，发起人：[white]{player.name}".with(
                "nextMap" to map,
                "player" to player,
            )
        )
    }
}

fun VoteService.register() {
    addSubVote("换图投票", "<地图ID>", "map", "换图") {
        if (arg.isEmpty())
            returnReply("[red]请输入地图序号".with())
        val map = arg[0].toIntOrNull()?.let { MapRegistry.findById(it, reply) }
            ?: returnReply("[red]地图序号错误,可以通过/maps查询".with())
        voteMap(player!!, map)
    }
    addSubVote("下次自动轮换地图投票", "<地图ID>", "nextmap", "endmap", "aftermap", "下局换图", "结束后换图") {
        if (arg.isEmpty())
            returnReply("[red]请输入地图序号".with())
        val map = arg[0].toIntOrNull()?.let { MapRegistry.findById(it, reply) }
            ?: returnReply("[red]地图序号错误,可以通过/maps查询".with())
        voteNextAutoMap(player!!, map)
    }
    addSubVote("回滚到某个存档(使用/slots查看)", "<存档ID>", "rollback", "load", "回档") {
        val save = arg.firstOrNull()?.toIntOrNull()
            ?: returnReply("[red]请输入正确的存档编号".with())
        val map = MapManager.getSlot(save)
            ?: returnReply("[red]存档不存在或存档损坏".with())
        start(player!!, "回档({save})".with("save" to save), supportSingle = true) {
            MapManager.loadSave(map)
            broadcast("[green]回档成功".with(), quite = true)
        }
    }
}

onEnable {
    VoteService.register()
}
