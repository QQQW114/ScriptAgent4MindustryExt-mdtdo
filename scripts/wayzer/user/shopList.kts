@file:Depends("coreMindustry/menu", "商店列表菜单")

package wayzer.user

import coreMindustry.MenuBuilder
import coreMindustry.lib.CustomMenuParts.RESULT_BACK
import coreMindustry.lib.CustomMenuParts.RESULT_CLOSE
import coreMindustry.lib.CustomMenuParts.actionRow
import coreMindustry.lib.CustomMenuParts.listRow
import coreMindustry.lib.CustomMenuParts.navRow
import coreMindustry.lib.CustomMenuParts.sectionHeader
import coreMindustry.lib.CustomMenuParts.textRow
import coreMindustry.lib.RootCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import coreMindustry.lib.customMenuSupported
import coreMindustry.lib.sendCustomMenu

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

/**
 * 实验功能：用 160 的自定义菜单渲染商店列表（论坛式列表 + 详情页）。
 * 返回是否成功下发；失败（旧客户端/异常）由调用方回退到聊天菜单。
 *
 * 布局：每行 = 商店名（主色） + 说明（次要色） + 右侧"打开"按钮，整行可点；
 * 选中后进入详情页，显示说明与"打开商店"/"返回"按钮。
 */
private fun openShopListCustom(player: Player, entries: List<ShopEntry>): Boolean {
    if (!customMenuSupported(player)) return false
    return runCatching {
        showShopListPage(player, entries)
        true
    }.getOrElse {
        logger.warning("商店列表自定义菜单下发失败，回退聊天菜单：${it.message}")
        false
    }
}

private fun showShopListPage(player: Player, entries: List<ShopEntry>) {
    sendCustomMenu(
        player = player,
        title = "商店列表",
        onSelect = { result, _ ->
            when {
                result == RESULT_CLOSE -> Unit
                result.startsWith("open:") -> {
                    val code = result.removePrefix("open:")
                    val entry = shopEntries[code]
                    if (entry == null) {
                        player.sendMessage("[yellow]该商店已下架".with())
                    } else if (entry.command.isBlank()) {
                        player.sendMessage("[yellow]该商店暂未开放".with())
                    } else {
                        launch(Dispatchers.game) { RootCommands.handleInput(entry.command, player, "/") }
                    }
                }
                result.startsWith("detail:") -> {
                    val code = result.removePrefix("detail:")
                    val entry = shopEntries[code]
                    if (entry == null) {
                        player.sendMessage("[yellow]该商店已下架".with())
                    } else {
                        showShopDetailPage(player, entries, entry)
                    }
                }
            }
        },
    ) {
        if (entries.isEmpty()) {
            sectionHeader("商店列表", "当前暂无开放商店")
            textRow("[gray]后续会在这里添加称号商店、技能商店等入口。")
            navRow(backText = "刷新", closeText = "关闭")
        } else {
            sectionHeader("商店列表", "选择要打开的商店（点击整行查看详情）")
            entries.forEach { entry ->
                listRow(
                    result = "detail:${entry.code}",
                    title = entry.name,
                    subtitle = entry.description.ifBlank { "（无说明）" },
                    trailing = if (entry.command.isBlank()) "未开放" else "打开",
                    accent = if (entry.command.isBlank()) "9aa1b5" else "4da3ff",
                )
            }
            actionRow(listOf("关闭" to RESULT_CLOSE))
        }
    }
}

private fun showShopDetailPage(player: Player, entries: List<ShopEntry>, entry: ShopEntry) {
    sendCustomMenu(
        player = player,
        title = entry.name,
        onSelect = { result, _ ->
            when {
                result == RESULT_CLOSE -> Unit
                result == RESULT_BACK -> showShopListPage(player, entries)
                result == "open" -> {
                    if (entry.command.isBlank()) {
                        player.sendMessage("[yellow]该商店暂未开放".with())
                    } else {
                        launch(Dispatchers.game) { RootCommands.handleInput(entry.command, player, "/") }
                    }
                }
            }
        },
    ) {
        sectionHeader(entry.name, "商店详情")
        textRow(entry.description.ifBlank { "[gray]（该商店暂无说明）" })
        textRow("[gray]入口指令：${entry.command.ifBlank { "未配置" }}")
        actionRow(
            listOfNotNull(
                "打开商店" to "open",
                "返回列表" to RESULT_BACK,
                "关闭" to RESULT_CLOSE,
            )
        )
    }
}

private suspend fun openShopList(player: Player) {
    val entries = shopEntriesCached()
    // 优先尝试 160 自定义菜单（实验）；不可用或失败时回退既有聊天菜单。
    if (openShopListCustom(player, entries)) return

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
                        launch(Dispatchers.game) { RootCommands.handleInput(entry.command, player, "/") }
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
