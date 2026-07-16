@file:Depends("wayzer/ext/ipRegion", "IP地区识别")
@file:Depends("wayzer/user/lang", "玩家语言设置")

package wayzer.ext

import wayzer.lib.PlayerData

name = "地区自动语言"

private val ipRegion = contextScript<IpRegion>()
private val userLang = contextScript<wayzer.user.Lang>()

private val autoLangEnabled by config.key(true, "是否根据地区/客户端locale自动选择语言")
private val overrideManual by config.key(false, "是否覆盖玩家手动/lang设置")
private val chinaLang by config.key("zh", "中国/中文客户端默认语言")
private val foreignLang by config.key("en", "非中国/非中文客户端默认语言")
private val notifyPlayer by config.key(false, "自动设置语言时是否提示玩家")

private fun targetLang(player: Player): String {
    val country = with(ipRegion) { getCountryByIP(player.con?.address ?: player.ip()) }
    val locale = player.locale?.lowercase().orEmpty()
    return if (country == "中国" || locale.startsWith("zh")) chinaLang else foreignLang
}

private fun hasManualLang(uid: String): Boolean =
    with(userLang) { settings[uid] != null }

private fun applyAutoLang(player: Player) {
    if (!autoLangEnabled) return
    val data = PlayerData[player]
    if (!overrideManual && hasManualLang(data.id)) return
    val target = targetLang(player)
    val old = with(userLang) { data.lang }
    if (old == target) return
    with(userLang) { data.lang = target }
    if (notifyPlayer) {
        player.sendMessage("[cyan]已根据地区/客户端语言将服务器菜单语言设为：[yellow]$target[]。可使用 [gold]/lang <语言>[] 手动修改。")
    }
}

listen<EventType.PlayerJoin> {
    val player = it.player
    launch(Dispatchers.game) {
        delay(1_500)
        if (player.con != null) applyAutoLang(player)
    }
}
