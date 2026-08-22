package wayzer.lib

import cf.wayzer.scriptAgent.Event

data class ReputationChangedEvent(val uids: Set<String>) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class PlayerLikedEvent(val fromUid: String, val targetUid: String) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class RecognitionChangedEvent(val uids: Set<String>) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class PlayerRecognizedEvent(val fromUid: String, val targetUid: String) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class TrustPointChangedEvent(val uids: Set<String>) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

/**
 * MDC 发放事件（只统计“新发放”给已登录账号主体的数量）：
 * - 只在 addTrustPoints / addTrustPointsBatch 正向发放与 setTrustPoints 上调时触发；
 * - 转账、红包领取/退回、面对面读博结算等存量流转不触发（不属于新发放）；
 * - 只对 account:<id> 主体触发，游客主体不触发（发放统计排除游客）。
 */
data class MdcGrantedEvent(val uid: String, val amount: Int, val reason: String) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class TrustLevelLockChangedEvent(val uids: Set<String>) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class PlayerTitleChangedEvent(val uids: Set<String>) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class PlayerMuteChangedEvent(val uids: Set<String>) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class TrustLevelChangedEvent(val uid: String, val oldLevel: String, val newLevel: String) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class SeniorityLevelChangedEvent(val uid: String, val oldLevel: String, val newLevel: String) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class SeniorityLevelLockChangedEvent(val uids: Set<String>) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class AchievementCompletedEvent(val uid: String, val achievementCode: String) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class ShopPurchaseEvent(val uid: String, val shopCode: String, val itemId: String) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

data class ForumPostCreatedEvent(val uid: String, val postId: Int) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}
