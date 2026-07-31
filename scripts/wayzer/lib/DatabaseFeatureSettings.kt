package wayzer.lib

import cf.wayzer.scriptAgent.Event
import coreLibrary.lib.util.ServiceRegistry

/**
 * 会直接产生玩家数据库读写，或具有持续后台数据库负载的可停用业务功能。
 *
 * 账号身份、信任权限、封禁、禁言、IP风控与性能保护不属于此枚举，数据库业务总开关
 * 不得让这些安全/运维能力失效。
 */
enum class DatabaseFeature(val code: String, val displayName: String) {
    PlayTimeRecording("playTimeRecording", "在线时长记录"),
    Achievement("achievement", "成就系统"),
    Wiki("wiki", "Wiki系统"),
    MdcTransferAndRedPacket("mdcTransferAndRedPacket", "MDC转账/红包"),
    RecentPlayerRecording("recentPlayerRecording", "最近玩家记录"),
    TrustPromotion("trustPromotion", "信任等级自动调整"),
    SeniorityPromotion("seniorityPromotion", "资历等级自动调整"),
    Leaderboard("leaderboard", "排行榜"),
    PlayerProfileStats("playerProfileStats", "玩家资料数据显示"),
}

interface DatabaseFeatureSettings {
    /** 数据库业务总开关；只覆盖本枚举内的可选业务功能。 */
    fun masterEnabled(): Boolean

    /** 子功能保存的原始配置，不受总开关覆盖。 */
    fun configuredEnabled(feature: DatabaseFeature): Boolean

    /** 子功能当前实际状态：总开关与子开关均开启时才为 true。 */
    fun enabled(feature: DatabaseFeature): Boolean = masterEnabled() && configuredEnabled(feature)

    fun setMasterEnabled(enabled: Boolean): Boolean
    fun setConfiguredEnabled(feature: DatabaseFeature, enabled: Boolean): Boolean
    fun statusText(): String

    companion object : ServiceRegistry<DatabaseFeatureSettings>()
}

/** 服务热重载的短窗口优先保持旧行为，避免因暂时取不到 Provider 意外关闭业务功能。 */
fun isDatabaseFeatureEnabled(feature: DatabaseFeature): Boolean =
    DatabaseFeatureSettings.getOrNull()?.enabled(feature) ?: true

fun isDatabaseFeatureConfiguredEnabled(feature: DatabaseFeature): Boolean =
    DatabaseFeatureSettings.getOrNull()?.configuredEnabled(feature) ?: true

fun isDatabaseBusinessMasterEnabled(): Boolean =
    DatabaseFeatureSettings.getOrNull()?.masterEnabled() ?: true

data class DatabaseFeatureChangedEvent(
    val feature: DatabaseFeature,
    val oldEnabled: Boolean,
    val newEnabled: Boolean,
    val changedAtMillis: Long,
) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}
