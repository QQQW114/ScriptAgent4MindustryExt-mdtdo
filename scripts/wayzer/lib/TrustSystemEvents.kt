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
