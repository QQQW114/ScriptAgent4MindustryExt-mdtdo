@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustPoint", "MDC")
@file:Depends("wayzer/user/trustLevel", "信任等级")
@file:Depends("wayzer/ext/playerRecognition", "认可数据")

package wayzer.user

import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.ShopPurchaseEvent

/**
 * MDT 通用商店核心。
 *
 * 只放通用校验、扣MDC和购买统计；具体商品效果由称号商店/技能商店等业务脚本处理。
 */

private val trustPoint = contextScript<TrustPoint>()
private val trustLevel = contextScript<TrustLevel>()
private val recognition = contextScript<wayzer.ext.PlayerRecognition>()

fun normalizeShopLevelCode(text: String): String? = when (text.trim().lowercase()) {
    "0" -> "0"
    "1" -> "1"
    "2" -> "2"
    "3" -> "3"
    "3+", "3p", "3plus" -> "3+"
    "4" -> "4"
    else -> null
}

fun shopLevelName(levelCode: String): String = with(trustLevel) { trustLevelName(levelCode) }
fun shopUid(player: Player): String = PlayerData[player].id

fun shopRequirementText(price: Int, requiredLevelCode: String = "0", requiredRecognitions: Int = 0): String {
    val parts = mutableListOf("${price.coerceAtLeast(0)} MDC")
    if (with(trustLevel) { trustLevelOrder(requiredLevelCode) } > 0) {
        parts += "${requiredLevelCode}级"
    }
    if (requiredRecognitions > 0) parts += "被认可${requiredRecognitions}次"
    return parts.joinToString(" / ")
}

fun checkShopRequirements(
    player: Player,
    price: Int,
    requiredLevelCode: String = "0",
    requiredRecognitions: Int = 0,
): String? {
    val uid = shopUid(player)
    val currentPoints = with(trustPoint) { getTrustPoints(uid) }
    if (currentPoints < price) return "MDC不足：需要 ${price}，当前 ${currentPoints}"

    if (!with(trustLevel) { hasTrustLevel(player, requiredLevelCode) }) {
        return "信任等级不足：需要 ${requiredLevelCode}级（${shopLevelName(requiredLevelCode)}）"
    }

    val receivedRecognitions = with(recognition) { playerReceivedRecognitions(uid) }
    if (receivedRecognitions < requiredRecognitions) {
        return "被认可数不足：需要 ${requiredRecognitions}，当前 ${receivedRecognitions}"
    }
    return null
}

fun completeShopPurchase(
    player: Player,
    shopCode: String,
    itemId: String,
    itemName: String,
    price: Int,
): Boolean {
    val uid = shopUid(player)
    if (!with(trustPoint) { spendTrustPoints(uid, price.coerceAtLeast(0), "Shop:$shopCode:$itemId") }) {
        player.sendMessage("[red]MDC不足，购买失败")
        return false
    }
    MdtStorage.recordShopPurchase(uid, shopCode, itemId)
    launch { ShopPurchaseEvent(uid, shopCode, itemId).emitAsync() }
    player.sendMessage("[green]购买成功：[white]$itemName[green]，消耗 [yellow]${price.coerceAtLeast(0)}[green] MDC")
    return true
}

fun shopPurchaseCount(uid: String, shopCode: String, itemId: String? = null): Int =
    MdtStorage.shopPurchaseCount(uid, shopCode, itemId)
