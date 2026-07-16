@file:Depends("wayzer/user/shopList", "商店列表入口")
@file:Depends("wayzer/user/shopCore", "通用商店核心")
@file:Depends("wayzer/user/playerTitle", "称号系统")
@file:Depends("coreMindustry/menu", "称号商店菜单")
@file:Depends("coreMindustry/utilTextInput", "自定义称号输入")

package wayzer.user

import arc.util.Strings
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import java.lang.Integer.toUnsignedString

private object TitleShopConfig {
    const val TITLE_SHOP_CODE = "title"
    const val CUSTOM_TITLE_PREFIX = "custom:"
    const val MAX_SHOP_ITEM_ID_LENGTH = 32
    const val MAX_CUSTOM_TITLE_RAW_LENGTH = 80
}

private val shopList = contextScript<ShopList>()
private val shopCore = contextScript<ShopCore>()
private val playerTitle = contextScript<PlayerTitle>()
private val textInput = contextScript<coreMindustry.UtilTextInput>()

private data class PresetTitleShopItem(
    val id: String,
    val titleContent: String,
    val price: Int,
    val requiredLevelCode: String,
    val requiredRecognitions: Int = 0,
)

private suspend fun <T> db(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private val TITLE_SHOP_CACHE_TTL_MILLIS = 60_000L

private data class TitleShopItemsCacheEntry(
    val items: List<MdtStorage.TitleShopItemRecord>,
    val loadedAt: Long,
)

private val titleShopItemsCache = mutableMapOf<Boolean, TitleShopItemsCacheEntry>()

private val presetItems = listOf(
    PresetTitleShopItem("1", "custom:7", 50, "3+"),
    PresetTitleShopItem("2", "custom:20", 500, "3+"),
    PresetTitleShopItem("3", "[gold]富可敌国", 1000, "2"),
    PresetTitleShopItem("4", "百尺竿头", 100, "1"),
    PresetTitleShopItem("5", "[pink]指导顾问", 10, "2", 10),
    PresetTitleShopItem("6", "[green]受人敬仰", 10, "2", 20),
    PresetTitleShopItem("7", "[gold]无所不知", 1, "3", 30),
    PresetTitleShopItem("8", "[gold]元气满满", 1, "3", 20),
)

private fun normalizeItemId(id: String): String? {
    val fixed = id.trim()
    if (fixed.isEmpty() || fixed.length > TitleShopConfig.MAX_SHOP_ITEM_ID_LENGTH) return null
    if (fixed.any { it.isWhitespace() || it == '|' }) return null
    return fixed
}

private fun customLimit(titleContent: String): Int? {
    if (!titleContent.lowercase().startsWith(TitleShopConfig.CUSTOM_TITLE_PREFIX)) return null
    return titleContent.substringAfter(':').toIntOrNull()?.takeIf { it in 1..20 }
}

private fun isCustomItem(item: MdtStorage.TitleShopItemRecord): Boolean = customLimit(item.titleContent) != null

private fun titleShopDisplayName(item: MdtStorage.TitleShopItemRecord): String {
    val limit = customLimit(item.titleContent)
    return if (limit != null) "自定义称号（限制${limit}字符）" else item.titleContent
}

private fun itemRequirementText(item: MdtStorage.TitleShopItemRecord): String = with(shopCore) {
    shopRequirementText(item.price, item.requiredLevelCode, item.requiredRecognitions)
}

private fun clearTitleShopCache() {
    titleShopItemsCache.clear()
}

private fun loadSortedItems(includeDisabled: Boolean = false, forceRefresh: Boolean = false): List<MdtStorage.TitleShopItemRecord> {
    val now = System.currentTimeMillis()
    val cached = titleShopItemsCache[includeDisabled]
    if (!forceRefresh && cached != null && now - cached.loadedAt <= TITLE_SHOP_CACHE_TTL_MILLIS) {
        return cached.items
    }
    val items = MdtStorage.titleShopItems(includeDisabled).sortedWith(
        compareBy<MdtStorage.TitleShopItemRecord> { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.id }
    )
    titleShopItemsCache[includeDisabled] = TitleShopItemsCacheEntry(items, now)
    return items
}

private fun sortedItems(includeDisabled: Boolean = false): List<MdtStorage.TitleShopItemRecord> =
    loadSortedItems(includeDisabled)

private fun fixedTitleCode(itemId: String): String = "shop_${itemId.replace(Regex("[^A-Za-z0-9_\\-]"), "_")}".take(64)

private fun customTitleCode(uid: String): String {
    val uidPart = toUnsignedString(uid.hashCode(), 36)
    val timePart = java.lang.Long.toString(System.currentTimeMillis(), 36)
    return "shop_custom_${uidPart}_${timePart}".take(64)
}

private fun validateCustomTitle(input: String, limit: Int): String? {
    val fixed = input.replace('\n', ' ').replace('\r', ' ').trim()
    if (fixed.isEmpty()) return null
    if ('|' in fixed) return null
    if (fixed.length > TitleShopConfig.MAX_CUSTOM_TITLE_RAW_LENGTH) return null
    val visible = Strings.stripColors(fixed).trim()
    if (visible.isEmpty()) return null
    if (visible.length > limit) return null
    return fixed
}

private suspend fun askCustomTitle(player: Player, limit: Int): String? {
    val raw = with(textInput) {
        textInput(
            player,
            "自定义称号",
            "请输入自定义称号。\n可见字符限制：${limit}字。\n输入正确后才会扣除MDC。",
            lengthLimit = TitleShopConfig.MAX_CUSTOM_TITLE_RAW_LENGTH,
            timeoutMillis = 60_000,
        )
    } ?: return null
    val fixed = validateCustomTitle(raw, limit)
    if (fixed == null) {
        player.sendMessage("[red]自定义称号格式不正确：不能为空，不能包含 |，可见字符不能超过 ${limit} 个")
    }
    return fixed
}

private fun seedPresetItemsIfEmpty() {
    if (!MdtStorage.titleShopIsEmpty()) return
    presetItems.forEach {
        MdtStorage.upsertTitleShopItem(
            it.id,
            it.titleContent,
            it.price,
            it.requiredLevelCode,
            it.requiredRecognitions,
            enabled = true,
        )
    }
    clearTitleShopCache()
}

private suspend fun buyTitleShopItem(player: Player, item: MdtStorage.TitleShopItemRecord) {
    val requirementError = with(shopCore) {
        checkShopRequirements(player, item.price, item.requiredLevelCode, item.requiredRecognitions)
    }
    if (requirementError != null) {
        player.sendMessage("[red]无法购买：$requirementError")
        return
    }

    val uid = PlayerData[player].id
    val itemName = titleShopDisplayName(item)
    val limit = customLimit(item.titleContent)
    val titleCode: String
    val titleContent: String
    if (limit != null) {
        titleContent = askCustomTitle(player, limit) ?: return
        titleCode = customTitleCode(uid)
    } else {
        titleContent = item.titleContent
        titleCode = fixedTitleCode(item.id)
        if (titleCode in with(playerTitle) { playerOwnedTitleCodes(uid) }) {
            player.sendMessage("[yellow]你已经拥有该称号，无需重复购买")
            return
        }
    }

    if (!with(shopCore) { completeShopPurchase(player, TitleShopConfig.TITLE_SHOP_CODE, item.id, itemName, item.price) }) return

    val granted = with(playerTitle) {
        grantTitle(uid, titleCode, titleContent, "称号商店：${item.id}")
    }
    if (granted) {
        player.sendMessage("[green]已获得称号：[white]${titleContent}[]，可在 [gold]/title[] 中佩戴")
    } else {
        player.sendMessage("[yellow]购买已完成，但称号发放结果异常或你已拥有该称号；如有疑问请联系管理员")
    }
}

private suspend fun openTitleShop(player: Player, forceRefresh: Boolean = false) {
    val items = db { loadSortedItems(includeDisabled = false, forceRefresh = forceRefresh).filter { it.enabled } }
    if (items.isEmpty()) {
        MenuBuilder<Unit>("称号商店") {
            msg = "[yellow]当前暂无称号商品。"
            option("关闭") {}
        }.sendTo(player, 60_000)
        return
    }

    object : PagedMenuBuilder<MdtStorage.TitleShopItemRecord>(items, prePage = 6) {
        override suspend fun renderItem(item: MdtStorage.TitleShopItemRecord) {
            val tag = if (isCustomItem(item)) "[pink]自定义[]" else "[cyan]称号[]"
            option("${item.id}. $tag ${titleShopDisplayName(item)}\n[gray]${itemRequirementText(item)}") {
                buyTitleShopItem(player, item)
            }
        }

        override suspend fun build() {
            title = "[yellow]称号商店"
            msg = "[acid]点击商品即可购买。固定称号重复拥有时不会扣MDC；自定义称号输入正确后才会扣MDC。"
            super.build()
        }
    }.sendTo(player, 60_000)
}

private fun listItemsText(includeDisabled: Boolean = true, forceRefresh: Boolean = false): String {
    return loadSortedItems(includeDisabled, forceRefresh).joinToString("\n") { item ->
        val status = if (item.enabled) "启用" else "禁用"
        "${item.id}. ${titleShopDisplayName(item)} / ${itemRequirementText(item)} / $status"
    }.ifBlank { "无" }
}

onEnable {
    launch(Dispatchers.IO) { seedPresetItemsIfEmpty() }
    with(shopList) {
        registerShop(TitleShopConfig.TITLE_SHOP_CODE, "称号商店", "使用MDC购买称号", "/titleshop")
    }
}

onDisable {
    with(shopList) { unregisterShop(TitleShopConfig.TITLE_SHOP_CODE) }
}

command("titleshop", "打开称号商店") {
    aliases = listOf("称号商店")
    attr(ClientOnly)
    body { openTitleShop(player!!) }
}

command("titleshopadmin", "管理指令：管理称号商店商品") {
    usage = "list | set <商品id> <称号内容|custom:长度> <售价> <等级要求> [认可要求] | del <商品id> | seed"
    aliases = listOf("tshopadmin", "称号商店管理")
    permission = "wayzer.admin.titleShop"
    body {
        val op = arg.getOrNull(0)?.lowercase() ?: "list"
        when (op) {
            "list", "查看" -> reply("[cyan]称号商店商品：\n[white]${db { listItemsText(forceRefresh = true) }}".with())
            "set", "add", "设置", "添加" -> {
                if (arg.size < 5) replyUsage()
                val itemId = normalizeItemId(arg[1]) ?: returnReply("[red]商品id非法：不能为空，不能含空格或 |，最长 ${TitleShopConfig.MAX_SHOP_ITEM_ID_LENGTH} 字符".with())
                val titleContent = arg[2].trim()
                if (titleContent.isEmpty()) returnReply("[red]称号内容不能为空；自定义称号商品请使用 custom:7 这类格式".with())
                customLimit(titleContent)?.let { } ?: run {
                    if (titleContent.lowercase().startsWith(TitleShopConfig.CUSTOM_TITLE_PREFIX)) {
                        returnReply("[red]自定义称号商品格式错误，请使用 custom:7 或 custom:20，范围 1..20".with())
                    }
                }
                val price = arg[3].toIntOrNull()?.takeIf { it >= 0 } ?: returnReply("[red]售价必须是非负整数".with())
                val level = with(shopCore) { normalizeShopLevelCode(arg[4]) }
                    ?: returnReply("[red]等级要求错误，请使用 0/1/2/3/3+/4".with())
                val recognitions = if (arg.size >= 6) arg[5].toIntOrNull()?.takeIf { it >= 0 } ?: returnReply("[red]认可数要求必须是非负整数".with()) else 0
                val changed = MdtStorage.upsertTitleShopItem(itemId, titleContent, price, level, recognitions, enabled = true)
                clearTitleShopCache()
                val message = (if (changed) "[green]已保存称号商品" else "[yellow]商品无变化") +
                        "\n[cyan]${itemId}. ${titleContent} / ${with(shopCore) { shopRequirementText(price, level, recognitions) }}"
                reply(message.with())
            }
            "del", "delete", "remove", "删除" -> {
                if (arg.size < 2) replyUsage()
                val itemId = normalizeItemId(arg[1]) ?: returnReply("[red]商品id非法".with())
                val removed = MdtStorage.deleteTitleShopItem(itemId)
                if (removed) clearTitleShopCache()
                reply(if (removed) "[green]已删除称号商品：$itemId".with() else "[yellow]未找到称号商品：$itemId".with())
            }
            "seed", "preset", "预设" -> {
                presetItems.forEach {
                    MdtStorage.upsertTitleShopItem(it.id, it.titleContent, it.price, it.requiredLevelCode, it.requiredRecognitions, enabled = true)
                }
                clearTitleShopCache()
                reply("[green]已写入/覆盖称号商店预设商品".with())
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.titleShop", group = "@admin")
