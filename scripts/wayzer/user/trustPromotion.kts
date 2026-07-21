@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/ext/playerReputation", "玩家口碑/赞踩")
@file:Depends("wayzer/ext/playerRecognition", "玩家认可")
@file:Depends("wayzer/user/trustPoint", "MDC")

package wayzer.user

import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.RecognitionChangedEvent
import wayzer.lib.ReputationChangedEvent
import wayzer.lib.ServerTestMode
import wayzer.lib.TrustLevelLockChangedEvent
import wayzer.lib.TrustPointChangedEvent
import java.time.LocalDate
import kotlin.math.max

private val trustLevel = contextScript<TrustLevel>()
private val reputation = contextScript<wayzer.ext.PlayerReputation>()
private val recognitionScript = contextScript<wayzer.ext.PlayerRecognition>()
private val trustPoint = contextScript<TrustPoint>()

private val dirtyUids = mutableSetOf<String>()
private val dirtyLock = Any()
private val AUTO_FULL_CHECK_MILLIS by config.key(5 * 60_000L, "信任等级在线玩家兜底全量复查间隔(ms)")
private val AUTO_CHECK_BATCH_SIZE by config.key(6, "信任等级每轮自动检测最多处理玩家数，避免一次性打库卡主线程")
private val EFFECTIVE_DISLIKE_RECENT_DAYS = 7L

private fun recentReputationSinceDate(): String =
    LocalDate.now().minusDays(EFFECTIVE_DISLIKE_RECENT_DAYS - 1).toString()

private fun calculateEffectiveDislikes(
    reputation: MdtStorage.ReputationCounts,
    recognition: MdtStorage.RecognitionCounts,
    recent: MdtStorage.ReputationCounts,
): Int = max(
    0,
    reputation.receivedDislikes +
            recent.receivedDislikes * 2 -
            recognition.received * 2 -
            reputation.receivedLikes / 20 -
            recent.receivedLikes / 3
)

fun effectiveDislikes(uid: String): Int {
    val reputation = MdtStorage.getReputation(uid)
    val recognition = MdtStorage.getRecognition(uid)
    val recent = MdtStorage.getRecentReceivedReputation(uid, recentReputationSinceDate())
    return calculateEffectiveDislikes(reputation, recognition, recent)
}

fun markTrustDirty(uid: String) {
    synchronized(dirtyLock) { dirtyUids += uid }
}

fun markTrustDirty(uids: Iterable<String>) {
    synchronized(dirtyLock) { dirtyUids.addAll(uids) }
}

private fun drainDirtyUids(): List<String> = synchronized(dirtyLock) {
    val batch = dirtyUids.toList()
    dirtyUids.clear()
    batch
}

private fun requeueDirtyUids(uids: Iterable<String>) {
    synchronized(dirtyLock) { dirtyUids.addAll(uids) }
}

private fun onlinePlayerByUid(uid: String): Player? =
    Groups.player.find { PlayerData[it].id == uid }

private fun dirtyUidForAutoCheck(player: Player): String? {
    val data = PlayerData[player]
    return data.id.takeIf { data.authed }
}

private fun isOnlineGuestUid(uid: String): Boolean =
    onlinePlayerByUid(uid)?.let { !PlayerData[it].authed } == true

private fun displayName(uid: String, player: Player?): String =
    player?.name ?: PlayerData.findByShortId(uid)?.name ?: uid

private fun trustLevelCodeFromStats(stats: MdtStorage.TrustPromotionStats, player: Player?): String {
    if (player != null && !PlayerData[player].authed) return "0"
    if (player?.admin == true) return "4"
    return stats.manualLevelCode ?: if (player != null) "1" else "0"
}

private fun targetLevelCode(player: Player?, stats: MdtStorage.TrustPromotionStats, current: String): String {
    // 3++/4 都是人工任命层级，自动晋升系统既不产生，也不降级。
    if (current == "3++" || current == "4") return current

    val bound = player != null && PlayerData[player].authed || with(trustLevel) { trustLevelOrder(current) >= 10 }
    val receivedLikes = stats.reputation.receivedLikes
    val givenLikes = stats.reputation.givenLikes
    val receivedRecognitions = stats.recognition.received
    val givenRecognitions = stats.recognition.given
    val points = stats.points.current
    val totalPoints = stats.points.total
    val effectiveDislikeCount = calculateEffectiveDislikes(stats.reputation, stats.recognition, stats.recentReputation)

    val can2 = bound &&
            receivedLikes >= 10 &&
            givenLikes >= 20 &&
            totalPoints >= 10

    val can3 = can2 &&
            receivedLikes >= 20 &&
            givenLikes >= 30 &&
            totalPoints >= 100 &&
            points > 50 &&
            effectiveDislikeCount <= 20 &&
            receivedRecognitions >= 5 &&
            givenRecognitions >= 1

    val can3Plus = can3 &&
            receivedLikes >= 30 &&
            totalPoints >= 200 &&
            points > 100 &&
            effectiveDislikeCount <= 15 &&
            receivedRecognitions >= 20

    val target = when {
        can3Plus -> "3+"
        can3 -> "3"
        can2 -> "2"
        bound -> "1"
        else -> "0"
    }

    // 自然等级 3/3+ 玩家最多因动态条件掉到 2 级。
    return if (with(trustLevel) { trustLevelOrder(current) } >= 20 &&
        with(trustLevel) { trustLevelOrder(target) } < 20
    ) "2" else target
}

fun checkTrustLevel(uid: String) {
    ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.ownsUid(uid) }?.let { return }
    val player = onlinePlayerByUid(uid)
    val stats = MdtStorage.getTrustPromotionStats(uid)
    val oldLevel = trustLevelCodeFromStats(stats, player)
    if (oldLevel == "4") return
    if (stats.levelLocked) return

    val newLevel = targetLevelCode(player, stats, oldLevel)
    if (oldLevel == newLevel) return

    with(trustLevel) { setTrustLevel(uid, newLevel) }
    val name = displayName(uid, player)
    val oldOrder = with(trustLevel) { trustLevelOrder(oldLevel) }
    val newOrder = with(trustLevel) { trustLevelOrder(newLevel) }

    with(trustLevel) { emitTrustLevelChanged(uid, oldLevel, newLevel) }

    if (newOrder > oldOrder) {
        broadcast("[gold]恭喜[white]$name[gold]升级到了[yellow]${newLevel}级[gold]！".with())
    } else {
        broadcast("[yellow]$name 的等级降为了[orange]${newLevel}级[yellow]！".with())
    }
}

private fun safeCheckTrustLevel(uid: String, reason: String = "auto") {
    if (isOnlineGuestUid(uid)) return
    runCatching {
        val startedAt = System.currentTimeMillis()
        checkTrustLevel(uid)
        val cost = System.currentTimeMillis() - startedAt
        if (cost >= 200L) logger.warning("信任等级自动检测耗时 ${cost}ms(reason=$reason, uid=$uid)")
    }.onFailure { logger.warning("信任等级自动检测失败(reason=$reason, uid=$uid): ${it.message}") }
}

private fun safeMarkPlayerDirty(player: Player, reason: String = "auto") {
    runCatching { dirtyUidForAutoCheck(player)?.let(::markTrustDirty) }
        .onFailure { logger.warning("信任等级标记待检测失败(reason=$reason, player=${player.plainName()}, uuid=${player.uuid()}): ${it.message}") }
}

listenTo<ReputationChangedEvent> {
    markTrustDirty(uids)
}

listenTo<RecognitionChangedEvent> {
    markTrustDirty(uids)
}

listenTo<TrustPointChangedEvent> {
    markTrustDirty(uids)
}

listenTo<TrustLevelLockChangedEvent> {
    markTrustDirty(uids)
}

listen<EventType.PlayerJoin> {
    safeMarkPlayerDirty(it.player, "join")
}

listen<EventType.GameOverEvent> {
    Groups.player.forEach { safeMarkPlayerDirty(it, "gameover") }
}

listen<EventType.ResetEvent> {
    Groups.player.forEach { safeMarkPlayerDirty(it, "reset") }
}

onEnable {
    Groups.player.forEach { safeMarkPlayerDirty(it, "enable") }
    launch(Dispatchers.game) {
        var lastFullCheckAt = 0L
        while (true) {
            delay(3000)
            val now = System.currentTimeMillis()
            if (now - lastFullCheckAt >= AUTO_FULL_CHECK_MILLIS.coerceAtLeast(60_000L)) {
                Groups.player.toList().forEach { safeMarkPlayerDirty(it, "full-check") }
                lastFullCheckAt = now
            }
            val dirty = drainDirtyUids()
            val limit = AUTO_CHECK_BATCH_SIZE.coerceAtLeast(1)
            dirty.take(limit).forEach { safeCheckTrustLevel(it, "dirty") }
            if (dirty.size > limit) requeueDirtyUids(dirty.drop(limit))
        }
    }
}

command("trustcheck", "检查玩家信任等级条件") {
    usage = "[玩家id/3位id]"
    permission = "wayzer.admin.trustCheck"
    body {
        val target = arg.firstOrNull()?.let { id ->
            PlayerData.findByShortId(id)?.let { it.id to (it.player?.name ?: it.name) } ?: (id to id)
        } ?: run {
            val p = player ?: return@run null
            PlayerData[p].id to p.name
        } ?: replyUsage()

        val uid = target.first
        checkTrustLevel(uid)
        val targetPlayer = Groups.player.find { PlayerData[it].id == uid }
        reply(
            """
                |[cyan]玩家：[white]${target.second}
                |[cyan]等级：[white]${with(trustLevel) { getTrustLevelCode(uid, targetPlayer) }}
                |[cyan]等级锁定：[white]${if (with(trustLevel) { isTrustLevelLocked(uid) }) "是" else "否"}
                |[cyan]收到赞：[white]${with(reputation) { playerLikes(uid) }}
                |[cyan]送出赞：[white]${with(reputation) { playerGivenLikes(uid) }}
                |[cyan]总被踩：[white]${with(reputation) { playerDislikes(uid) }}
                |[cyan]有效被踩：[white]${effectiveDislikes(uid)}
                |[cyan]被认可：[white]${with(recognitionScript) { playerReceivedRecognitions(uid) }}
                |[cyan]认可他人：[white]${with(recognitionScript) { playerGivenRecognitions(uid) }}
                |[cyan]当前MDC：[white]${with(trustPoint) { getTrustPoints(uid) }}
                |[cyan]累计MDC：[white]${with(trustPoint) { getTotalTrustPoints(uid) }}
            """.trimMargin().with()
        )
    }
}

PermissionApi.registerDefault("wayzer.admin.trustCheck", group = "@admin")
