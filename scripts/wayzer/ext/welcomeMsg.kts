@file:Depends("wayzer/ext/ipRegion", "IP地区识别")

package wayzer.ext

import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.net.Administration

private val ipRegion = contextScript<IpRegion>()

val customWelcome by config.key("customWelcome", true, "是否开启自定义进服信息(中文)") {
    if (dataDirectory != null)
        Administration.Config.showConnectMessages.set(!it)
}
val type by config.key(MsgType.InfoMessage, "发送方式")
val template by config.key(
    """
    Welcome to this Server
    [green]欢迎来自[acid]{region}[green]的{player.name}[green]来到本服务器[]
""".trimIndent(), "欢迎信息模板"
)
val welcomeTemplate by config.key(
    "[cyan][+] 欢迎来自 [acid]{region} [white]的 {player.name} [goldenrod]加入了服务器", "玩家加入提示消息模板，仅在customWelcome开启时生效"
)

listen<EventType.PlayerJoin> {
    val region = with(ipRegion) { getRegionByIP(it.player.con?.address ?: it.player.ip()) }
    it.player.sendMessage(template.with("player" to it.player, "region" to region), type)
    if (customWelcome)
        broadcast(welcomeTemplate.with("player" to it.player, "region" to region))
}

listen<EventType.PlayerLeave> {
    if (customWelcome && it.player.lastText != "[Silent_Leave]")
        broadcast("[coral][-]{player.name} [brick]离开了服务器".with("player" to it.player))
}
