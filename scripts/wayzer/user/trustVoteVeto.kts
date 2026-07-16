@file:Depends("wayzer/vote", "投票服务")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.user

import wayzer.VoteEvent

private val trustLevel = contextScript<TrustLevel>()

private fun canVetoVote(player: Player): Boolean {
    return with(trustLevel) { hasTrustLevel(player, "3+") }
}

command("veto", "信任等级指令：一票否决当前投票") {
    aliases = listOf("否决", "一票否决", "一票否决权")
    usage = "[原因]"
    attr(ClientOnly)
    body {
        val operator = player!!
        if (!canVetoVote(operator)) {
            returnReply("[red]权限不足：只有 3+级 和 4级 玩家可以一票否决当前投票".with())
        }
        if (VoteEvent.current() == null) {
            returnReply("[yellow]当前没有正在进行的投票".with())
        }
        val reason = arg.joinToString(" ").trim()
        if (VoteEvent.vetoCurrent(operator, reason)) {
            reply("[green]已一票否决当前投票".with())
        } else {
            reply("[yellow]当前没有正在进行的投票".with())
        }
    }
}
