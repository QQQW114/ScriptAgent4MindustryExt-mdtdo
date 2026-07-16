package wayzer.lib

import coreLibrary.lib.util.ServiceRegistry
import mindustry.gen.Player

/**
 * [危险]服务器测试模式的跨脚本轻量接口。
 *
 * 该接口只提供“当前是否处于测试模式、哪些 UID 属于临时测试主体、临时 MDC/在线时长”
 * 等覆盖能力。真正的启停、临时文件保存和玩家会话切换由独立脚本
 * wayzer/user/serverTestMode.kts 提供。
 */
interface ServerTestMode {
    fun isEnabled(): Boolean
    fun testUid(uuid: String): String
    fun ownsUid(uid: String): Boolean
    fun isTestSession(player: Player): Boolean
    fun formalUid(player: Player): String?
    fun isTestEligible(player: Player): Boolean

    fun applySession(player: Player, reason: String = "")
    fun restoreSession(player: Player, reason: String = "")

    fun getTrustPoints(uid: String): Int
    fun getTotalTrustPoints(uid: String): Int
    fun addTrustPoints(uid: String, amount: Int): Int
    fun addCurrentTrustPoints(uid: String, amount: Int): Int
    fun spendTrustPoints(uid: String, amount: Int): Boolean
    fun transferTrustPoints(fromUid: String, toUid: String, amount: Int): Boolean
    fun setTrustPoints(uid: String, value: Int): Int
    fun setTotalTrustPoints(uid: String, value: Int): Int

    fun getPlayMillis(uid: String): Long
    fun addPlayMillis(uid: String, millis: Long): Long
    fun addPlayMillisBatch(deltas: Map<String, Long>): Map<String, Long>

    fun gameContributionRewardMultiplier(): Int
    fun statusText(): String

    companion object : ServiceRegistry<ServerTestMode>()
}
