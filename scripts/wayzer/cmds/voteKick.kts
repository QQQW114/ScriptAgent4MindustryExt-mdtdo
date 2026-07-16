@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/user/ban", "禁封实现")
@file:Depends("coreMindustry/utilTextInput", "输入理由")
@file:Depends("coreMindustry/menu", "菜单选人")

package wayzer.cmds

import coreMindustry.PagedMenuBuilder
import wayzer.VoteEvent

fun CommandContext.readArg(): String? = arg.firstOrNull().also { arg = arg.drop(1) }

suspend fun CommandContext.getTarget(): Player {
    val id = readArg() ?: player?.let { player ->
        var result: Player? = null
        PagedMenuBuilder(Groups.player.toList()) {
            option(it.name) { result = it }
        }.apply {
            title = "选择目标玩家"
            sendTo(player, 60_000)
        }
        if (result != null) return result!!
        null
    } ?: returnReply("[red]请输入玩家名/三位id".with())
    if (id.startsWith("#"))
        Groups.player.getByID(id.substring(1).toIntOrNull() ?: 0)?.let { return it }
    //Try find by name
    val allPlayers = Groups.player.associateBy { it.name.replace(" ", "") }
    for (addLen in 0..arg.size) {
        val argAsName = id + arg.take(addLen).joinToString("")
        val found = allPlayers[argAsName] ?: continue
        arg = arg.drop(addLen)
        return found
    }
    //find by uuid
    return PlayerData.findByShortId(id)?.player
        ?: Groups.player.find { it.uuid() == id || PlayerData[it].id == id || PlayerData[it].shortId.equals(id, ignoreCase = true) }
        ?: returnReply("[red]请输入正确的玩家名".with())
}

val textInput = contextScript<coreMindustry.UtilTextInput>()
suspend fun CommandContext.getInput(name: String, whenEmpty: VarString): String {
    return arg.takeIf { it.isNotEmpty() }?.joinToString(" ")
        ?: player?.let { p ->
            (textInput.textInput(p, "请在60s内输入$name") ?: returnReply("[yellow]已取消输入".with()))
                .takeIf { it.isNotBlank() }
        } ?: returnReply(whenEmpty)
}

val banImpl = contextScript<wayzer.user.Ban>()

suspend fun startKickVote(starter: Player, target: Player, reason: String): Boolean {
    val event = VoteEvent(
        thisScript, starter,
        voteDesc = "踢人(踢出[red]{target}[yellow])".with("target" to target),
        extDesc = "[red]理由: [yellow]${reason}"
    )
    val snapshot = PlayerData[target]
    if (!event.awaitResult()) return false
    if (target.hasPermission("wayzer.admin.skipKick")) {
        broadcast("[red]错误：[white]${target.name}[red]为管理员，如有问题请与服主联系".with())
        return false
    }
    banImpl.ban(snapshot, 60, "投票踢出: $reason", starter)
    return true
}

command("kick", "[cyan]投票踢出某人[gray]（需50%同意）".with(), commands = VoteEvent.VoteCommands) {
    aliases = listOf("踢出")
    usage = "<玩家名/id> <理由>"
    requirePermission("wayzer.vote.kick")
    body {
        val target = getTarget()
        val reason = getInput("踢人理由", "[red]投票踢人需要理由".with())
        val player = player!!
        startKickVote(player, target, reason)
    }
}

PermissionApi.registerDefault("wayzer.admin.skipKick", group = "@admin")
