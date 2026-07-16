@file:Depends("coreMindustry/menu", "商店列表菜单")

package wayzer.user

import coreMindustry.MenuBuilder
import coreMindustry.lib.RootCommands

private data class ShopEntry(
    val code: String,
    val name: String,
    val description: String,
    val command: String,
)

private val shopEntries = linkedMapOf<String, ShopEntry>()
private var shopEntrySnapshot: List<ShopEntry>? = null

fun registerShop(code: String, name: String, description: String = "", command: String): Boolean {
    val normalized = code.trim()
    if (normalized.isEmpty()) return false
    shopEntries[normalized] = ShopEntry(normalized, name.trim(), description.trim(), command.trim())
    shopEntrySnapshot = null
    return true
}

fun unregisterShop(code: String): Boolean {
    val removed = shopEntries.remove(code.trim()) != null
    if (removed) shopEntrySnapshot = null
    return removed
}

fun listShops(): List<String> = shopEntries.keys.toList()

private fun shopEntriesCached(): List<ShopEntry> =
    shopEntrySnapshot ?: shopEntries.values.toList().also { shopEntrySnapshot = it }

private suspend fun openShopList(player: Player) {
    val entries = shopEntriesCached()
    MenuBuilder<Unit>("商店列表") {
        if (entries.isEmpty()) {
            msg = """
                |[yellow]当前暂无开放商店。
                |[gray]后续会在这里添加称号商店、技能商店等入口。
            """.trimMargin()
            option("关闭") {}
        } else {
            msg = "[acid]请选择要打开的商店："
            entries.forEach { entry ->
                val optionName = if (entry.description.isBlank()) entry.name else "${entry.name}\n[gray]${entry.description}"
                option(optionName) {
                    if (entry.command.isBlank()) {
                        player.sendMessage("[yellow]该商店暂未开放")
                    } else {
                        RootCommands.handleInput(entry.command, player, "/")
                    }
                }
                newRow()
            }
            option("关闭") {}
        }
    }.sendTo(player, 60_000)
}

command("shop", "打开商店列表") {
    aliases = listOf("商店", "shops")
    attr(ClientOnly)
    body {
        openShopList(player!!)
    }
}
