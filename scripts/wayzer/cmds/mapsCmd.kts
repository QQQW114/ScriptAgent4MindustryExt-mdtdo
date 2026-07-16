@file:Depends("wayzer/maps")
@file:Depends("coreMindustry/menu", "maps菜单")
@file:Depends("wayzer/cmds/voteMap", "发起投票换图")

package wayzer.cmds

import coreMindustry.MenuV2
import coreMindustry.renderPaged
import coreLibrary.lib.CommandContext
import coreLibrary.lib.CommandInfo
import coreLibrary.lib.Commands
import wayzer.MapRegistry

val mapsPrePage by config.key(9, "/maps每页显示数")
val voteMap = contextScript<VoteMap>()

private fun CommandInfo.isEnabledMapScriptCommand(): Boolean =
    script?.enabled == true && script?.id?.startsWith("mapScript/") == true

private fun currentMapScriptCommands(): List<CommandInfo> =
    Commands.Root.registeredSubCommands()
        .filter { it.isEnabledMapScriptCommand() }
        .distinct()

private fun findMapScriptCommand(name: String): CommandInfo? {
    val lower = name.lowercase()
    val commands = currentMapScriptCommands()
    return commands.lastOrNull { it.name.equals(lower, ignoreCase = true) }
        ?: commands.lastOrNull { info -> info.aliases.any { it.equals(lower, ignoreCase = true) } }
}

private fun mapScriptCommandHelp(): String {
    val names = currentMapScriptCommands()
        .flatMap { listOf(it.name) + it.aliases }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
    if (names.isEmpty()) return "[yellow]当前地图没有可通过 /mapcmd 打开的地图特定指令。"
    return names.take(20).joinToString(
        separator = "\n",
        prefix = "[cyan]当前地图特定指令：\n",
    ) { "[white]/mapcmd $it" } + if (names.size > 20) "\n[gray]等 ${names.size} 个" else ""
}

suspend fun CommandContext.handleMapScriptCommandProxy() {
    val name = arg.firstOrNull() ?: returnReply(mapScriptCommandHelp().with())
    val command = findMapScriptCommand(name)
        ?: returnReply(("[red]未找到当前地图特定指令：[yellow]$name\n" + mapScriptCommandHelp()).with())
    subContext().run { command.handle() }
}

command("mapcmd", "打开当前地图脚本提供的特定指令") {
    usage = "<地图脚本指令> [参数]"
    aliases = listOf("mcmd", "mapcommand", "地图指令")
    body {
        handleMapScriptCommandProxy()
    }
}

command("maps", "列出服务器地图") {
    usage = "[page/filter] [page]"
    aliases = listOf("地图")
    body {
        val page = arg.lastOrNull()?.toIntOrNull() ?: 1
        val filter = arg.getOrNull(0).takeUnless { it == page.toString() }
        val maps = MapRegistry.searchMaps(filter)/*.sortedBy { it.id }*/
        val template = "[red]{info.id}  [green]{info.name}[blue] | {info.mode}"
        val player = player ?: returnReply(menu("服务器地图 By WayZer", maps, page, mapsPrePage) { info ->
            template.with("info" to info)
        })
        MenuV2(player) {
            title = "服务器地图($filter)"
            msg = "SA4Mindustry By WayZer\n" +
                    "点击选项可发起投票换图"
            val url = "https://www.mindustry.top"
            option("点击打开Mindustry资源站，查看更多地图\n$url") {
                Call.openURI(player.con, url)
            }
            renderPaged(maps, page, mapsPrePage) {
                option(template.with("info" to it).toPlayer(player)) {
                    if (!player.hasPermission("wayzer.vote.map")) {
                        player.sendMessage("[red]你没有投票换图的权限".with())
                        return@option
                    }
                    voteMap.voteMap(player, it)
                }
            }
        }.send().awaitWithTimeout()
    }
}
