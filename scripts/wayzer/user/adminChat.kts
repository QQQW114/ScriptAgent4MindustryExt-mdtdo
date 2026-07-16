@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.user

name = "MDT管理员频道"

private val trustLevel = contextScript<TrustLevel>()

private fun canUseAdminChat(player: Player): Boolean =
    with(trustLevel) { isTrustAdmin(player) }

private fun compactAdminChat(text: String): String =
    text.replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(240)

command("achat", "管理员频道：发送仅4级/admin可见的消息") {
    usage = "<内容>"
    aliases = listOf("ac", "adminchat", "管理员频道", "管理频道")
    attr(ClientOnly)
    body {
        val sender = player!!
        if (!canUseAdminChat(sender)) {
            returnReply("[red]权限不足：只有4级/admin可以使用管理员频道。".with())
        }
        val message = compactAdminChat(arg.joinToString(" "))
        if (message.isBlank()) replyUsage()
        Groups.player.filter { canUseAdminChat(it) }.forEach {
            it.sendMessage(
                "[scarlet][管理频道] [white]{sender.name}[gray]: [white]{message}".with(
                    "sender" to sender,
                    "message" to message,
                )
            )
        }
        logger.info("[MDT管理员频道] ${sender.plainName()}: $message")
    }
}

