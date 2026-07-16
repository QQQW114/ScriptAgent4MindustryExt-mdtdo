@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustLevel", "信任等级")
@file:Depends("wayzer/user/trustPoint", "MDC")

package wayzer.user

import coreLibrary.lib.PermissionApi
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.ServerTestMode
import wayzer.lib.SeniorityLevelChangedEvent
import wayzer.lib.SeniorityLevelLockChangedEvent
import wayzer.lib.TrustLevelChangedEvent
import wayzer.lib.TrustPointChangedEvent
import java.util.Locale

/**
 * MDT 资历等级系统。
 *
 * 信任等级继续负责帖子、管理权限、处罚/风控等信任边界；资历等级负责技能分类与技能使用资格。
 */

private val trustLevel = contextScript<TrustLevel>()
private val trustPoint = contextScript<TrustPoint>()

private val HOUR_MILLIS = 60L * 60L * 1000L
private val PLAY_TICK_MILLIS by config.key(60_000L, "资历系统在线时长写入间隔(ms)")
private val AUTO_FULL_CHECK_MILLIS by config.key(5 * 60_000L, "资历系统在线玩家兜底全量复查间隔(ms)")
private val AUTO_CHECK_BATCH_SIZE by config.key(6, "资历系统每轮自动检测最多处理玩家数，避免一次性打库卡主线程")
private val playTickAt = mutableMapOf<String, Long>()
private val playTickAuthed = mutableMapOf<String, Boolean>()
private val dirtyUids = mutableSetOf<String>()
private val dirtyLock = Any()
private val pendingPlayMillis = mutableMapOf<String, Long>()
private val pendingPlayLock = Any()
private val seniorityLevelCache = mutableMapOf<String, String?>()
private val seniorityLockCache = mutableMapOf<String, Boolean>()

private data class SeniorityRequirement(
    val level: String,
    val playHours: Long,
    val totalMdc: Int,
)

private data class SeniorityTarget(
    val uid: String,
    val name: String,
    val player: Player?,
)

private val requirements = listOf(
    SeniorityRequirement("1", 1L, 100),
    SeniorityRequirement("2", 16L, 600),
    SeniorityRequirement("3", 64L, 2000),
)

fun normalizeSeniorityLevelCode(level: String): String? = when (level.trim().lowercase()) {
    "0" -> "0"
    "1" -> "1"
    "2" -> "2"
    "3" -> "3"
    "4", "admin", "4+admin", "4admin" -> "4"
    else -> null
}

fun seniorityLevelOrder(levelCode: String): Int = when (normalizeSeniorityLevelCode(levelCode) ?: "0") {
    "0" -> 0
    "1" -> 10
    "2" -> 20
    "3" -> 30
    "4" -> 40
    else -> 0
}

fun seniorityLevelName(levelCode: String): String = when (normalizeSeniorityLevelCode(levelCode) ?: "0") {
    "0" -> "资历0级"
    "1" -> "资历1级"
    "2" -> "资历2级"
    "3" -> "资历3级"
    "4" -> "资历4级"
    else -> "未知"
}

private fun storedSeniorityLevelCode(uid: String): String {
    ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.ownsUid(uid) }?.let { return "3" }
    if (!seniorityLevelCache.containsKey(uid)) {
        seniorityLevelCache[uid] = MdtStorage.getSeniorityLevelCode(uid)?.let { normalizeSeniorityLevelCode(it) }
    }
    return seniorityLevelCache[uid] ?: "0"
}

private fun isTrustLevel4(uid: String, player: Player?): Boolean =
    with(trustLevel) { getTrustLevelOrder(uid, player) >= trustLevelOrder("4") }

fun isSessionAuthedForSeniority(player: Player): Boolean = PlayerData[player].authed

fun getSeniorityLevelCode(uid: String, player: Player? = null): String {
    player?.let { p ->
        ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.isTestSession(p) }?.let {
            return if (isTrustLevel4(uid, p)) "4" else "3"
        }
    }
    if (player != null && !isSessionAuthedForSeniority(player)) return "0"
    if (isTrustLevel4(uid, player)) return "4"
    return storedSeniorityLevelCode(uid)
}

fun getSeniorityLevelCode(player: Player): String = getSeniorityLevelCode(PlayerData[player].id, player)
fun getSeniorityLevel(uid: String, player: Player? = null): Int = (getSeniorityLevelCode(uid, player).toIntOrNull() ?: 0).coerceIn(0, 4)
fun getSeniorityLevel(player: Player): Int = getSeniorityLevel(PlayerData[player].id, player)
fun getSeniorityLevelOrder(uid: String, player: Player? = null): Int = seniorityLevelOrder(getSeniorityLevelCode(uid, player))
fun getSeniorityLevelOrder(player: Player): Int = getSeniorityLevelOrder(PlayerData[player].id, player)
fun hasSeniorityLevel(player: Player, requiredLevelCode: String): Boolean =
    getSeniorityLevelOrder(player) >= seniorityLevelOrder(requiredLevelCode)
fun isSeniorityAdmin(player: Player): Boolean = hasSeniorityLevel(player, "4")

fun setSeniorityLevel(uid: String, levelCode: String): String {
    val normalized = normalizeSeniorityLevelCode(levelCode) ?: error("Invalid seniority level: $levelCode")
    MdtStorage.setSeniorityLevelCode(uid, normalized)
    seniorityLevelCache[uid] = normalized
    return normalized
}

fun setManualSeniorityLevel(uid: String, levelCode: String): String {
    val normalized = setSeniorityLevel(uid, levelCode)
    // 管理员手动调整资历等级表示玩法资历的人工裁定，应默认锁定，避免下一轮自动检测立刻覆盖。
    setSeniorityLevelLocked(uid, true)
    return normalized
}

fun emitSeniorityLevelChanged(uid: String, oldLevel: String, newLevel: String) {
    if (oldLevel == newLevel) return
    launch { SeniorityLevelChangedEvent(uid, oldLevel, newLevel).emitAsync() }
}

fun isSeniorityLevelLocked(uid: String): Boolean =
    seniorityLockCache.getOrPut(uid) { MdtStorage.isSeniorityLevelLocked(uid) }

fun setSeniorityLevelLocked(uid: String, locked: Boolean): Boolean {
    MdtStorage.setSeniorityLevelLocked(uid, locked)
    seniorityLockCache[uid] = locked
    launch { SeniorityLevelLockChangedEvent(setOf(uid)).emitAsync() }
    return locked
}

fun toggleSeniorityLevelLocked(uid: String): Boolean = setSeniorityLevelLocked(uid, !isSeniorityLevelLocked(uid))

fun playMillis(uid: String): Long =
    ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.ownsUid(uid) }?.getPlayMillis(uid)
        ?: MdtStorage.getPlayMillis(uid)
fun playHours(uid: String): Double = playMillis(uid).toDouble() / HOUR_MILLIS.toDouble()

fun formatPlayHours(millis: Long): String = String.format(Locale.ROOT, "%.1f小时", millis.toDouble() / HOUR_MILLIS.toDouble())
fun formatPlayHours(uid: String): String = formatPlayHours(playMillis(uid))

private fun addPlayMillis(uid: String, millis: Long): Long {
    val added = ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.ownsUid(uid) }?.addPlayMillis(uid, millis)
        ?: MdtStorage.addPlayMillis(uid, millis)
    markSeniorityDirty(uid)
    return added
}

private fun enqueuePlayMillis(uid: String, millis: Long) {
    if (millis <= 0L) return
    synchronized(pendingPlayLock) {
        pendingPlayMillis[uid] = (pendingPlayMillis[uid] ?: 0L) + millis
    }
    markSeniorityDirty(uid)
}

private fun drainPendingPlayMillis(): Map<String, Long> = synchronized(pendingPlayLock) {
    val batch = pendingPlayMillis.toMap()
    pendingPlayMillis.clear()
    batch
}

private fun flushPendingPlayMillis(reason: String = "auto") {
    val batch = drainPendingPlayMillis()
    if (batch.isEmpty()) return
    val testMode = ServerTestMode.getOrNull()?.takeIf { it.isEnabled() }
    if (testMode != null) {
        val testBatch = batch.filterKeys { testMode.ownsUid(it) }
        if (testBatch.isNotEmpty()) testMode.addPlayMillisBatch(testBatch)
        markSeniorityDirty(testBatch.keys)
        return
    }
    launch(Dispatchers.IO) {
        runCatching {
            val startedAt = System.currentTimeMillis()
            MdtStorage.addPlayMillisBatch(batch)
            val cost = System.currentTimeMillis() - startedAt
            if (cost >= 250L) logger.warning("资历系统在线时长批量写入耗时 ${cost}ms(reason=$reason, size=${batch.size})")
            markSeniorityDirty(batch.keys)
        }.onFailure {
            synchronized(pendingPlayLock) {
                batch.forEach { (uid, millis) ->
                    pendingPlayMillis[uid] = (pendingPlayMillis[uid] ?: 0L) + millis
                }
            }
            logger.warning("资历系统在线时长批量写入失败(reason=$reason, size=${batch.size}): ${it.message}")
        }
    }
}

private fun flushPendingPlayMillisBlocking(reason: String = "disable") {
    val batch = drainPendingPlayMillis()
    if (batch.isEmpty()) return
    val testMode = ServerTestMode.getOrNull()?.takeIf { it.isEnabled() }
    val normalBatch = if (testMode != null) {
        val testBatch = batch.filterKeys { testMode.ownsUid(it) }
        if (testBatch.isNotEmpty()) testMode.addPlayMillisBatch(testBatch)
        emptyMap()
    } else batch
    if (normalBatch.isEmpty()) return
    runCatching {
        MdtStorage.addPlayMillisBatch(normalBatch)
        markSeniorityDirty(normalBatch.keys)
    }.onFailure {
        logger.warning("资历系统在线时长最终写入失败(reason=$reason, size=${batch.size}): ${it.message}")
    }
}

private fun setPlayHours(uid: String, hours: Double): Long {
    val fixed = (hours.coerceAtLeast(0.0) * HOUR_MILLIS).toLong()
    val value = ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.ownsUid(uid) }?.let {
        val current = it.getPlayMillis(uid)
        it.addPlayMillis(uid, fixed - current)
    } ?: MdtStorage.setPlayMillis(uid, fixed)
    markSeniorityDirty(uid)
    return value
}

private fun addPlayHours(uid: String, hours: Double): Long {
    val delta = (hours * HOUR_MILLIS).toLong().coerceAtLeast(0L)
    val value = ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.ownsUid(uid) }?.addPlayMillis(uid, delta)
        ?: MdtStorage.addPlayMillis(uid, delta)
    markSeniorityDirty(uid)
    return value
}

fun targetSeniorityLevelCode(uid: String, player: Player? = null): String {
    player?.let { p ->
        ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.isTestSession(p) }?.let {
            return if (isTrustLevel4(uid, p)) "4" else "3"
        }
    }
    if (player != null && !isSessionAuthedForSeniority(player)) return "0"
    if (isTrustLevel4(uid, player)) return "4"
    val millis = playMillis(uid)
    val totalMdc = with(trustPoint) { getTotalTrustPoints(uid) }
    return when {
        millis >= 64L * HOUR_MILLIS && totalMdc >= 2000 -> "3"
        millis >= 16L * HOUR_MILLIS && totalMdc >= 600 -> "2"
        millis >= 1L * HOUR_MILLIS && totalMdc >= 100 -> "1"
        else -> "0"
    }
}

fun markSeniorityDirty(uid: String) {
    synchronized(dirtyLock) { dirtyUids += uid }
}

fun markSeniorityDirty(uids: Iterable<String>) {
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

private fun clearDirtyUids() {
    synchronized(dirtyLock) { dirtyUids.clear() }
}

private fun onlinePlayerByUid(uid: String): Player? = Groups.player.find { PlayerData[it].id == uid }

private fun dirtyUidForAutoCheck(player: Player): String? {
    val data = PlayerData[player]
    return data.id.takeIf { data.authed }
}

private fun isOnlineGuestUid(uid: String): Boolean =
    onlinePlayerByUid(uid)?.let { !PlayerData[it].authed } == true

private fun displayName(uid: String, player: Player?): String =
    player?.name ?: PlayerData.findByShortId(uid)?.name ?: uid

fun checkSeniorityLevel(uid: String) {
    ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.ownsUid(uid) }?.let { return }
    val player = onlinePlayerByUid(uid)
    val stats = MdtStorage.getSeniorityAutoCheckStats(uid)
    val stored = stats.seniority.levelCode?.let { normalizeSeniorityLevelCode(it) } ?: "0"
    seniorityLevelCache[uid] = stored
    seniorityLockCache[uid] = stats.seniority.levelLocked

    val sessionAuthed = player?.let { isSessionAuthedForSeniority(it) } ?: true
    val trust4 = sessionAuthed && (player?.admin == true || normalizeSeniorityLevelCode(stats.trustManualLevelCode.orEmpty()) == "4")
    val oldLevel = when {
        !sessionAuthed -> "0"
        trust4 -> "4"
        else -> stored
    }
    if (!trust4 && stats.seniority.levelLocked) return

    val newLevel = when {
        !sessionAuthed -> "0"
        trust4 -> "4"
        stats.seniority.playMillis >= 64L * HOUR_MILLIS && stats.trustPoints.total >= 2000 -> "3"
        stats.seniority.playMillis >= 16L * HOUR_MILLIS && stats.trustPoints.total >= 600 -> "2"
        stats.seniority.playMillis >= 1L * HOUR_MILLIS && stats.trustPoints.total >= 100 -> "1"
        else -> "0"
    }
    if (oldLevel == newLevel) return

    if (!trust4) {
        setSeniorityLevel(uid, newLevel)
    } else {
        // 信任4级只是有效资历覆盖，不写入资历表，避免以后取消4级后残留资历4。
        seniorityLevelCache.remove(uid)
    }

    val name = displayName(uid, player)
    emitSeniorityLevelChanged(uid, oldLevel, newLevel)
    if (seniorityLevelOrder(newLevel) > seniorityLevelOrder(oldLevel)) {
        broadcast("[gold]恭喜[white]$name[gold]资历等级提升到了[yellow]${newLevel}级[gold]！".with())
    } else {
        broadcast("[yellow]$name 的资历等级调整为了[orange]${newLevel}级[yellow]。".with())
    }
}

private fun tickPlayTime(player: Player, now: Long = System.currentTimeMillis()) {
    val key = player.uuid()
    val data = PlayerData[player]
    val currentAuthed = data.authed
    val wasAuthed = playTickAuthed.put(key, currentAuthed) ?: currentAuthed
    val last = playTickAt.put(key, now) ?: now
    val delta = (now - last).coerceAtLeast(0L)
    if (delta > 0L && currentAuthed && wasAuthed) {
        enqueuePlayMillis(data.id, delta)
    }
}

private fun safeTickPlayTime(player: Player, reason: String = "auto") {
    runCatching { tickPlayTime(player) }
        .onFailure { logger.warning("资历系统在线时长写入失败(reason=$reason, player=${player.plainName()}, uuid=${player.uuid()}): ${it.message}") }
}

private fun safeCheckSeniorityLevel(uid: String, reason: String = "auto") {
    if (isOnlineGuestUid(uid)) return
    runCatching {
        val startedAt = System.currentTimeMillis()
        checkSeniorityLevel(uid)
        val cost = System.currentTimeMillis() - startedAt
        if (cost >= 200L) logger.warning("资历系统自动检测耗时 ${cost}ms(reason=$reason, uid=$uid)")
    }.onFailure { logger.warning("资历系统自动检测失败(reason=$reason, uid=$uid): ${it.message}") }
}

private fun safeMarkPlayerDirty(player: Player, reason: String = "auto") {
    runCatching { dirtyUidForAutoCheck(player)?.let(::markSeniorityDirty) }
        .onFailure { logger.warning("资历系统标记待检测失败(reason=$reason, player=${player.plainName()}, uuid=${player.uuid()}): ${it.message}") }
}

private fun resolveSeniorityTarget(text: String): SeniorityTarget {
    val data = PlayerData.findByShortId(text)
    val uid = data?.id ?: text
    val name = data?.player?.name ?: data?.name ?: text
    return SeniorityTarget(uid, name, data?.player)
}

private fun canManageSeniority(operator: Player?): Boolean {
    if (operator == null) return true
    return with(trustLevel) { isTrustAdmin(operator) }
}

private fun nextRequirementText(uid: String, levelCode: String): String {
    val level = (levelCode.toIntOrNull() ?: 0).coerceIn(0, 4)
    if (level >= 4) return "已拥有资历4级"
    if (level >= 3) return "已达到自然资历最高级；资历4级仅由信任4级/admin或管理员手动设置获得。"
    val req = requirements.firstOrNull { it.level.toInt() == level + 1 } ?: return "无下一等级条件"
    val currentMillis = playMillis(uid)
    val currentTotalMdc = with(trustPoint) { getTotalTrustPoints(uid) }
    val leftMillis = (req.playHours * HOUR_MILLIS - currentMillis).coerceAtLeast(0L)
    val leftMdc = (req.totalMdc - currentTotalMdc).coerceAtLeast(0)
    return "下一级资历${req.level}：需要在线${req.playHours}小时/累计${req.totalMdc}MDC；还差 ${formatPlayHours(leftMillis)} / ${leftMdc}MDC"
}

private fun seniorityStatusText(uid: String, name: String, player: Player?): String {
    val trust = with(trustLevel) { getTrustLevelDisplayCode(uid, player) }
    val stored = storedSeniorityLevelCode(uid)
    val effective = getSeniorityLevelCode(uid, player)
    val millis = playMillis(uid)
    val currentMdc = with(trustPoint) { getTrustPoints(uid) }
    val totalMdc = with(trustPoint) { getTotalTrustPoints(uid) }
    val overrideText = if (effective == "4" && stored != "4" && isTrustLevel4(uid, player)) " [gray](信任4级/admin覆盖)" else ""
    return """
        |[cyan]玩家：[white]$name
        |[cyan]UID：[white]$uid
        |[cyan]信任等级：[white]$trust
        |[cyan]资历等级：[white]$effective[gray]（${seniorityLevelName(effective)}）$overrideText
        |[cyan]资历锁定：[white]${if (isSeniorityLevelLocked(uid)) "是" else "否"}
        |[cyan]累计在线：[white]${formatPlayHours(millis)}
        |[cyan]当前/累计MDC：[white]$currentMdc/$totalMdc
        |[gray]${nextRequirementText(uid, effective)}
    """.trimMargin()
}

listenTo<TrustPointChangedEvent> { markSeniorityDirty(uids) }
listenTo<TrustLevelChangedEvent> { markSeniorityDirty(uid) }
listenTo<SeniorityLevelLockChangedEvent> { markSeniorityDirty(uids) }

listen<EventType.PlayerJoin> {
    playTickAt[it.player.uuid()] = System.currentTimeMillis()
    playTickAuthed[it.player.uuid()] = PlayerData[it.player].authed
    safeMarkPlayerDirty(it.player, "join")
}

listen<EventType.PlayerLeave> {
    safeTickPlayTime(it.player, "leave")
    playTickAt.remove(it.player.uuid())
    playTickAuthed.remove(it.player.uuid())
}

listen<EventType.GameOverEvent> {
    Groups.player.forEach {
        safeTickPlayTime(it, "gameover")
        safeMarkPlayerDirty(it, "gameover")
    }
    flushPendingPlayMillis("gameover")
}

listen<EventType.ResetEvent> {
    Groups.player.forEach {
        safeTickPlayTime(it, "reset")
        safeMarkPlayerDirty(it, "reset")
    }
    flushPendingPlayMillis("reset")
}

onEnable {
    val now = System.currentTimeMillis()
    Groups.player.forEach {
        playTickAt[it.uuid()] = now
        playTickAuthed[it.uuid()] = PlayerData[it].authed
        safeMarkPlayerDirty(it, "enable")
    }
    launch(Dispatchers.game) {
        while (true) {
            delay(PLAY_TICK_MILLIS.coerceAtLeast(10_000L))
            Groups.player.toList().forEach { safeTickPlayTime(it, "periodic") }
            flushPendingPlayMillis("periodic")
        }
    }
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
            dirty.take(limit).forEach { safeCheckSeniorityLevel(it, "dirty") }
            if (dirty.size > limit) requeueDirtyUids(dirty.drop(limit))
        }
    }
}

onDisable {
    Groups.player.forEach { safeTickPlayTime(it, "disable") }
    flushPendingPlayMillisBlocking("disable")
    playTickAt.clear()
    playTickAuthed.clear()
    seniorityLevelCache.clear()
    seniorityLockCache.clear()
    clearDirtyUids()
}

command("seniority", "查看资历等级/在线时长") {
    aliases = listOf("资历", "playtime", "在线时长")
    usage = "[玩家id/3位id]|check [玩家]|set <玩家> <0|1|2|3|4>|lock <玩家> [on|off|toggle]|settime <玩家> <小时>|addtime <玩家> <小时>"
    body {
        val sub = arg.getOrNull(0)?.lowercase()
        when (sub) {
            null, "status", "状态" -> {
                val p = player ?: returnReply("[red]控制台请指定玩家：/seniority <uid>".with())
                val uid = PlayerData[p].id
                reply(seniorityStatusText(uid, p.name, p).with())
            }
            "check", "查询" -> {
                val target = arg.getOrNull(1)?.let(::resolveSeniorityTarget) ?: run {
                    val p = player ?: return@run null
                    SeniorityTarget(PlayerData[p].id, p.name, p)
                } ?: replyUsage()
                checkSeniorityLevel(target.uid)
                reply(seniorityStatusText(target.uid, target.name, target.player).with())
            }
            "set", "设置" -> {
                if (!canManageSeniority(player)) returnReply("[red]权限不足：只有4级用户或admin可以设置资历等级".with())
                val target = resolveSeniorityTarget(arg.getOrNull(1) ?: replyUsage())
                val level = normalizeSeniorityLevelCode(arg.getOrNull(2) ?: replyUsage()) ?: replyUsage()
                val oldLevel = getSeniorityLevelCode(target.uid, target.player)
                setManualSeniorityLevel(target.uid, level)
                val newLevel = getSeniorityLevelCode(target.uid, target.player)
                emitSeniorityLevelChanged(target.uid, oldLevel, newLevel)
                reply("[green]已设置并锁定 [white]{name}[green] 的资历等级：[yellow]{old}[green] -> [yellow]{new}[gray]；如需恢复自动资历请用 /lockseniority {uid} off".with("name" to target.name, "uid" to target.uid, "old" to oldLevel, "new" to newLevel))
            }
            "lock", "锁定", "unlock", "解锁" -> {
                if (!canManageSeniority(player)) returnReply("[red]权限不足：只有4级用户或admin可以锁定资历等级".with())
                val target = resolveSeniorityTarget(arg.getOrNull(1) ?: replyUsage())
                val locked = when (arg.getOrNull(2)?.lowercase() ?: if (sub == "unlock" || sub == "解锁") "off" else "toggle") {
                    "toggle", "切换" -> toggleSeniorityLevelLocked(target.uid)
                    "on", "true", "1", "lock", "locked", "开", "开启", "锁定" -> setSeniorityLevelLocked(target.uid, true)
                    "off", "false", "0", "unlock", "unlocked", "关", "关闭", "解锁" -> setSeniorityLevelLocked(target.uid, false)
                    else -> replyUsage()
                }
                reply(
                    (if (locked) "[green]已锁定 [white]{name}[green] 的资历等级：[yellow]{level}[green]，自动晋升将不再调整此玩家资历"
                    else "[green]已解除 [white]{name}[green] 的资历等级锁定，稍后会按在线时长与累计MDC重新检测")
                        .with("name" to target.name, "level" to getSeniorityLevelCode(target.uid, target.player))
                )
            }
            "settime", "setplaytime", "设置时长" -> {
                if (!canManageSeniority(player)) returnReply("[red]权限不足：只有4级用户或admin可以设置在线时长".with())
                val target = resolveSeniorityTarget(arg.getOrNull(1) ?: replyUsage())
                val hours = arg.getOrNull(2)?.toDoubleOrNull() ?: returnReply("[red]请输入小时数，例如 1 或 1.5".with())
                val millis = setPlayHours(target.uid, hours)
                reply("[green]已设置 [white]{name}[green] 的累计在线时长为 [yellow]{hours}".with("name" to target.name, "hours" to formatPlayHours(millis)))
            }
            "addtime", "addplaytime", "增加时长" -> {
                if (!canManageSeniority(player)) returnReply("[red]权限不足：只有4级用户或admin可以增加在线时长".with())
                val target = resolveSeniorityTarget(arg.getOrNull(1) ?: replyUsage())
                val hours = arg.getOrNull(2)?.toDoubleOrNull() ?: returnReply("[red]请输入小时数，例如 1 或 1.5".with())
                val millis = addPlayHours(target.uid, hours)
                reply("[green]已增加 [white]{name}[green] 的累计在线时长，当前 [yellow]{hours}".with("name" to target.name, "hours" to formatPlayHours(millis)))
            }
            else -> {
                val target = resolveSeniorityTarget(arg[0])
                reply(seniorityStatusText(target.uid, target.name, target.player).with())
            }
        }
    }
}

command("setseniority", "管理指令：设置并锁定玩家资历等级") {
    usage = "<uuid/3位id> <0|1|2|3|4>"
    aliases = listOf("设置资历", "调整资历", "setplaylevel")
    permission = "wayzer.admin.seniority"
    body {
        if (!canManageSeniority(player)) returnReply("[red]权限不足：只有4级用户或admin可以设置资历等级".with())
        if (arg.size < 2) replyUsage()
        val target = resolveSeniorityTarget(arg[0])
        val level = normalizeSeniorityLevelCode(arg[1]) ?: replyUsage()
        val oldLevel = getSeniorityLevelCode(target.uid, target.player)
        setManualSeniorityLevel(target.uid, level)
        val newLevel = getSeniorityLevelCode(target.uid, target.player)
        emitSeniorityLevelChanged(target.uid, oldLevel, newLevel)
        reply("[green]已设置并锁定 [white]{name}[green] 的资历等级：[yellow]{old}[green] -> [yellow]{new}[gray]；如需恢复自动资历请用 /lockseniority {uid} off".with("name" to target.name, "uid" to target.uid, "old" to oldLevel, "new" to newLevel))
    }
}

command("lockseniority", "管理指令：锁定/解除玩家资历等级自动调整") {
    usage = "<uuid/3位id> [on|off|toggle]"
    aliases = listOf("senioritylock", "资历锁", "锁资历")
    permission = "wayzer.admin.seniority"
    body {
        if (!canManageSeniority(player)) returnReply("[red]权限不足：只有4级用户或admin可以锁定资历等级".with())
        if (arg.isEmpty()) replyUsage()
        val target = resolveSeniorityTarget(arg[0])
        val locked = when (arg.getOrNull(1)?.lowercase()) {
            null, "toggle", "切换" -> toggleSeniorityLevelLocked(target.uid)
            "on", "true", "1", "lock", "locked", "开", "开启", "锁定" -> setSeniorityLevelLocked(target.uid, true)
            "off", "false", "0", "unlock", "unlocked", "关", "关闭", "解锁" -> setSeniorityLevelLocked(target.uid, false)
            else -> replyUsage()
        }
        reply(
            (if (locked) "[green]已锁定 [white]{name}[green] 的资历等级：[yellow]{level}[green]，自动晋升将不再调整此玩家资历"
            else "[green]已解除 [white]{name}[green] 的资历等级锁定，稍后会按在线时长与累计MDC重新检测")
                .with("name" to target.name, "level" to getSeniorityLevelCode(target.uid, target.player))
        )
    }
}

command("setplaytime", "管理指令：设置玩家累计在线时长(小时)") {
    usage = "<uuid/3位id> <小时>"
    aliases = listOf("设置在线时长")
    permission = "wayzer.admin.seniority"
    body {
        if (!canManageSeniority(player)) returnReply("[red]权限不足：只有4级用户或admin可以设置在线时长".with())
        if (arg.size < 2) replyUsage()
        val target = resolveSeniorityTarget(arg[0])
        val hours = arg[1].toDoubleOrNull() ?: returnReply("[red]请输入小时数，例如 1 或 1.5".with())
        val millis = setPlayHours(target.uid, hours)
        reply("[green]已设置 [white]{name}[green] 的累计在线时长为 [yellow]{hours}".with("name" to target.name, "hours" to formatPlayHours(millis)))
    }
}

command("addplaytime", "管理指令：增加玩家累计在线时长(小时)") {
    usage = "<uuid/3位id> <小时>"
    aliases = listOf("增加在线时长")
    permission = "wayzer.admin.seniority"
    body {
        if (!canManageSeniority(player)) returnReply("[red]权限不足：只有4级用户或admin可以增加在线时长".with())
        if (arg.size < 2) replyUsage()
        val target = resolveSeniorityTarget(arg[0])
        val hours = arg[1].toDoubleOrNull() ?: returnReply("[red]请输入小时数，例如 1 或 1.5".with())
        val millis = addPlayHours(target.uid, hours)
        reply("[green]已增加 [white]{name}[green] 的累计在线时长，当前 [yellow]{hours}".with("name" to target.name, "hours" to formatPlayHours(millis)))
    }
}

PermissionApi.registerDefault("wayzer.admin.seniority", group = "@admin")

registerVarForType<Player>().apply {
    registerChild("seniorityLevel", "MDT资历等级") { getSeniorityLevelCode(it) }
    registerChild("seniorityLevelName", "MDT资历等级名称") { seniorityLevelName(getSeniorityLevelCode(it)) }
    registerChild("playHours", "累计在线小时") { formatPlayHours(PlayerData[it].id) }
}
