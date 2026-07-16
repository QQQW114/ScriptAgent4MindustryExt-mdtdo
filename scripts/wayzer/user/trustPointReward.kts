@file:Depends("wayzer/user/trustPoint", "MDC")
@file:Depends("wayzer/ext/playerReputation", "玩家口碑/赞踩")
@file:Depends("wayzer/ext/playerRecognition", "玩家认可")

package wayzer.user

import wayzer.lib.PlayerLikedEvent
import wayzer.lib.PlayerData
import wayzer.lib.PlayerRecognizedEvent
import java.time.Duration

private val GG_POINTS = 3
private val RECEIVED_LIKE_POINTS = 5
private val RECEIVED_RECOGNITION_POINTS = 25
private val MIN_REWARD_GAME_TIME = Duration.ofMinutes(5)

private val trustPoint = contextScript<TrustPoint>()

private val rewardFlushIntervalMillis by config.key(1000L, "MDC奖励批量写入间隔(ms)，用于GG/点赞/认可等奖励，避免游戏结束后多人同时写库卡顿")
private val pendingRewards = mutableMapOf<String, Int>()
private val pendingRewardLock = Any()

private var ggRewardOpen = false
private val ggEligibleUids = mutableSetOf<String>()
private val ggRewardedUids = mutableSetOf<String>()

private fun playerUid(player: Player): String = PlayerData[player].id

private fun addRewardPoints(uid: String, amount: Int) {
    if (amount <= 0) return
    synchronized(pendingRewardLock) {
        pendingRewards[uid] = (pendingRewards[uid] ?: 0) + amount
    }
}

private fun drainPendingRewards(): Map<String, Int> = synchronized(pendingRewardLock) {
    val batch = pendingRewards.toMap()
    pendingRewards.clear()
    batch
}

private fun flushPendingRewards(reason: String = "auto") {
    val batch = drainPendingRewards().filterValues { it > 0 }
    if (batch.isEmpty()) return
    runCatching {
        val startedAt = System.currentTimeMillis()
        with(trustPoint) { addTrustPointsBatch(batch, "RewardBatch:$reason") }
        val cost = System.currentTimeMillis() - startedAt
        if (cost >= 200L) logger.warning("MDC奖励批量写入耗时 ${cost}ms(reason=$reason, size=${batch.size})")
    }.onFailure {
        synchronized(pendingRewardLock) {
            batch.forEach { (uid, amount) -> pendingRewards[uid] = (pendingRewards[uid] ?: 0) + amount }
        }
        logger.warning("MDC奖励批量写入失败(reason=$reason, size=${batch.size}): ${it.message}")
    }
}

listen<EventType.GameOverEvent> {
    val gameTime by PlaceHold.reference<Duration>("state.gameTime")
    if (gameTime < MIN_REWARD_GAME_TIME) {
        ggRewardOpen = false
        ggEligibleUids.clear()
        ggRewardedUids.clear()
        return@listen
    }

    ggRewardOpen = true
    Groups.player.forEach { player ->
        val uid = playerUid(player)
        ggEligibleUids.add(uid)
    }
}

listen<EventType.PlayerChatEvent> {
    if (!ggRewardOpen || !it.message.equals("gg", ignoreCase = true)) return@listen

    val uid = playerUid(it.player)
    if (uid !in ggEligibleUids || !ggRewardedUids.add(uid)) return@listen

    addRewardPoints(uid, GG_POINTS)
}

listen<EventType.ResetEvent> {
    ggRewardOpen = false
    ggEligibleUids.clear()
    ggRewardedUids.clear()
    launch(Dispatchers.IO) { flushPendingRewards("reset") }
}

onEnable {
    launch(Dispatchers.IO) {
        while (true) {
            delay(rewardFlushIntervalMillis.coerceAtLeast(250L))
            flushPendingRewards("periodic")
        }
    }
}

onDisable {
    flushPendingRewards("disable")
}

listenTo<PlayerLikedEvent> {
    addRewardPoints(targetUid, RECEIVED_LIKE_POINTS)
}

listenTo<PlayerRecognizedEvent> {
    addRewardPoints(targetUid, RECEIVED_RECOGNITION_POINTS)
}
