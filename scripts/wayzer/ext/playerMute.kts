@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("coreMindustry/utilNextChat", "聊天包拦截")
@file:Depends("wayzer/user/trustLevel", "统一即时限制目标边界")

package wayzer.ext

import cf.wayzer.scriptAgent.Event
import coreMindustry.UtilNextChat.OnChat
import mindustry.gen.Groups
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.PlayerMuteChangedEvent
import wayzer.user.TrustLevel
import java.util.concurrent.atomic.AtomicLong

private val trustLevel = contextScript<TrustLevel>()

private val NO_MUTE = "\u0000"
private val muteReasonCache = java.util.concurrent.ConcurrentHashMap<String, String>()
private data class TemporaryMute(val reason: String, val untilMillis: Long)
private val temporaryMutes = java.util.concurrent.ConcurrentHashMap<String, TemporaryMute>()

private fun emitMuteChanged(uids: Set<String>) {
    launch { PlayerMuteChangedEvent(uids).emitAsync() }
}

// =====================================================================================
// 惩罚体系规则层（2026-10-17 用户需求：非管理员惩罚时限/冷却 + 检举系统基础）
//
// 说明：本可以新建 wayzer/lib/PunishmentEvents.kt 放事件、punishmentReport.kts 放规则，
// 但新增 wayzer/lib 下的 .kt 会强制 SA 重编整个 wayzer 模块；运行中重载模块会让
// wayzer.lib.PlayerData / MdtStorage 等单例按 classloader 分裂成两份，且本会话不允许重启，
// 风险过高。故把“事件 + 规则 + 持久化状态”统一放在本脚本（playerMute），其它脚本
// （voteMute/voteOb/playerBuildBan/punishmentReport）单向依赖本脚本，不会循环依赖。
//
// 口径（Lead 2026-10-17 确认）：
// - 管理员层级 = 3++ 与 4 级；3+ 及以下都受钳制/冷却/检举约束；
// - 非管理员带时间惩罚单次 ≤ 1440 分钟；非管理员不允许永久（无时长）惩罚；
// - (施罚者, 目标) 24h 内不能重复施加，跨惩罚类型共用，跨重启持久化；
// - 检举成功：解罚 + 禁止施罚者使用限制权限 1 天；累计成功检举超过阈值 → 永久禁止。
// =====================================================================================

/**
 * 限制性惩罚已生效事件：禁言 / 强制观战 / 禁建等惩罚真正生效后，由各应用入口发射。
 *
 * 字段均为稳定值（不带 Player 对象），供检举系统在任意时刻处理。source：
 * - "direct"：玩家直接操作（面板/命令），施罚者 = operatorUid；
 * - "vote"：投票通过后施加，施罚者归因为投票发起者；
 * - "system"：系统/脚本自动施加，operatorUid 为空 → 不触发检举。
 */
data class PunishmentAppliedEvent(
    /** 惩罚类型："mute" / "ob" / "buildban"（与 punishmentReport 的展示/解罚逻辑对应）。 */
    val kind: String,
    /** 本次应用记录的递增 id（同一进程内唯一，用于防重复检举/日志）。 */
    val recordId: Long,
    val source: String,
    val operatorUid: String?,
    val operatorName: String?,
    /** 施罚者信任等级序（3++=38、4=40 为管理员层级，不触发检举）。 */
    val operatorLevelOrder: Int,
    /** 施罚者的全部主体标识（uuid/账号 uid），用于封禁记录跨主体生效。 */
    val operatorUids: Set<String>,
    /** 目标会话 UUID（定位在线玩家）。 */
    val targetUuid: String,
    /** 目标的全部主体标识（解除惩罚时按 uid 清理）。 */
    val targetUids: Set<String>,
    val targetName: String,
    /** 目标当前会话是否已登录账号（游客=false，游客不受检举保护）。 */
    val targetAuthed: Boolean,
    /** 带时间惩罚的时长（分钟）；null = 无期限/永久。 */
    val durationMinutes: Int?,
    val reason: String,
) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

/**
 * 惩罚解除事件：检举投票通过后由 punishmentReport 发射，各惩罚实现脚本监听后
 * 只清理自己负责的那一类状态（按 uid 清理，目标离线也能处理）。
 */
data class PunishmentLiftedEvent(
    val kind: String,
    val recordId: Long,
    val targetUuid: String,
    val targetUids: Set<String>,
    val targetName: String,
    /** 解除原因（例如“检举投票通过”）。 */
    val reason: String,
) : Event {
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

// ---------- 配置 ----------

private val nonAdminMaxPunishMinutes by config.key(1440, "非管理员层级(低于3++)单次带时间限制性惩罚的最大时长(分钟)")
private val punishRepeatCooldownHours by config.key(24, "非管理员层级对同一玩家重复施加限制性惩罚的冷却(小时)，跨禁言/观战/禁建共用")
private val reportBanHours by config.key(24, "被检举成功后禁止施罚者使用限制权限的时长(小时)")
private val permanentReportStrikeThreshold by config.key(4, "被成功检举达到该次数后永久禁止其限制性权限（默认4=超过3次）")

/** 管理员层级的等级序（3++ = 38）。 */
private val adminTierOrder = with(trustLevel) { trustLevelOrder("3++") }

// 供其它脚本/弹窗读取的规则参数（本次改动的配置都从这里取，不在调用方重复定义）。
fun nonAdminMaxPunishMinutesValue(): Int = nonAdminMaxPunishMinutes.coerceAtLeast(1)
fun reportBanHoursValue(): Int = reportBanHours.coerceAtLeast(1)
fun permanentReportStrikeThresholdValue(): Int = permanentReportStrikeThreshold.coerceAtLeast(1)
fun punishRepeatCooldownMillisValue(): Long = punishRepeatCooldownHours.coerceAtLeast(0).toLong() * 3_600_000L

// ---------- 持久化状态：检举封禁 + 24h 冷却 ----------
// 用 MdtStorage 的 MdtSettings 键值表（与安全风控 IP 封禁同口径），跨重启保留。
// 不用新建 Exposed 表：新表需要在启动期注册/建表，热重载场景不可靠。

private val BAN_SETTING_KEY = "punishment.report.bans"
private val COOLDOWN_SETTING_KEY = "punishment.report.cooldowns"
private val PERMANENT_UNTIL = Long.MAX_VALUE
private val PUNISH_LOCK = Any()

/**
 * 检举封禁记录（限制性权限封禁）。
 * untilMillis = Long.MAX_VALUE 表示永久；strikeCount 为累计被成功检举次数（跨重启保留）。
 */
data class RestrictionBanInfo(
    val uid: String,
    val name: String,
    val untilMillis: Long,
    val strikeCount: Int,
) {
    val permanent: Boolean get() = untilMillis == PERMANENT_UNTIL

    fun active(now: Long = System.currentTimeMillis()): Boolean = permanent || untilMillis > now
}

private val restrictionBans = linkedMapOf<String, RestrictionBanInfo>()
private val punishCooldowns = linkedMapOf<String, Long>()
private var punishStateLoaded = false
private var punishStateLoadWarned = false

private fun sanitizePersistText(text: String): String =
    text.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim()

private fun ensurePunishStateLoaded() {
    if (punishStateLoaded) return
    synchronized(PUNISH_LOCK) {
        if (punishStateLoaded) return
        val banRaw = runCatching { MdtStorage.getSetting(BAN_SETTING_KEY) }
        if (banRaw.isFailure) {
            if (!punishStateLoadWarned) {
                punishStateLoadWarned = true
                logger.warning("加载检举封禁状态失败（数据库可能未就绪，稍后自动重试）: ${banRaw.exceptionOrNull()?.message}")
            }
            return
        }
        val cooldownRaw = runCatching { MdtStorage.getSetting(COOLDOWN_SETTING_KEY) }.getOrNull()
        banRaw.getOrNull()?.lineSequence()?.forEach { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@forEach
            val uid = parts[0].trim()
            if (uid.isEmpty()) return@forEach
            val until = parts[1].toLongOrNull() ?: return@forEach
            val strikes = parts[2].toIntOrNull() ?: 0
            val name = parts.drop(3).joinToString("|")
            restrictionBans[uid] = RestrictionBanInfo(uid, name, until, strikes)
        }
        cooldownRaw?.lineSequence()?.forEach { line ->
            val parts = line.split('|')
            if (parts.size < 3) return@forEach
            val until = parts[2].toLongOrNull() ?: return@forEach
            punishCooldowns["${parts[0]}|${parts[1]}"] = until
        }
        punishStateLoaded = true
    }
}

private fun persistPunishStateLocked() {
    val banText = restrictionBans.values.joinToString("\n") {
        "${it.uid}|${it.untilMillis}|${it.strikeCount}|${sanitizePersistText(it.name)}"
    }
    val cooldownText = punishCooldowns.entries.joinToString("\n") { "${it.key}|${it.value}" }
    runCatching {
        MdtStorage.setSetting(BAN_SETTING_KEY, banText)
        MdtStorage.setSetting(COOLDOWN_SETTING_KEY, cooldownText)
    }.onFailure { logger.warning("保存惩罚体系状态失败: ${it.message}") }
}

// ---------- 等级/时长/冷却/封禁 规则函数 ----------

/** 返回值：剩余毫秒；0 = 未被封禁；Long.MAX_VALUE = 永久封禁。 */
fun formatPunishLeft(millis: Long): String {
    if (millis >= PERMANENT_UNTIL) return "永久"
    val totalSeconds = (millis / 1000).coerceAtLeast(1)
    val days = totalSeconds / 86400
    val hours = totalSeconds % 86400 / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (days > 0) append("${days}天")
        if (hours > 0) append("${hours}小时")
        if (minutes > 0) append("${minutes}分钟")
        if (days == 0L && hours == 0L && minutes == 0L) append("${seconds}秒")
    }
}

fun isAdminTierLevel(levelOrder: Int): Boolean = levelOrder >= adminTierOrder

fun applierLevelOrder(player: Player?): Int =
    player?.let { p -> with(trustLevel) { getTrustLevelOrder(p) } } ?: -1

fun applierLevelOrderByUid(uid: String): Int =
    if (uid.isBlank()) -1 else with(trustLevel) { getTrustLevelOrder(uid) }

/** 该组 uid 是否处于“被检举封禁限制权限”状态；0 = 未封禁，Long.MAX_VALUE = 永久。 */
fun restrictionBanLeftMillis(uids: Iterable<String>): Long {
    val keys = uids.filter { it.isNotBlank() }.toSet()
    if (keys.isEmpty()) return 0L
    ensurePunishStateLoaded()
    val now = System.currentTimeMillis()
    var best = 0L
    synchronized(PUNISH_LOCK) {
        keys.forEach { uid ->
            val record = restrictionBans[uid] ?: return@forEach
            if (!record.active(now)) return@forEach
            val left = if (record.permanent) PERMANENT_UNTIL else (record.untilMillis - now).coerceAtLeast(0L)
            if (left > best) best = left
        }
    }
    return best
}

/** 该组 uid 命中的最佳封禁记录（优先永久/有效/次数多）。 */
fun restrictionBanInfo(uids: Iterable<String>): RestrictionBanInfo? {
    val keys = uids.filter { it.isNotBlank() }.toSet()
    if (keys.isEmpty()) return null
    ensurePunishStateLoaded()
    synchronized(PUNISH_LOCK) {
        return keys.mapNotNull { restrictionBans[it] }
            .maxWithOrNull(
                compareBy<RestrictionBanInfo> { if (it.permanent) 2 else if (it.active()) 1 else 0 }
                    .thenBy { it.strikeCount }
                    .thenBy { it.untilMillis }
            )
    }
}

fun restrictionBanBlockMessage(leftMillis: Long, action: String = "使用限制性权限"): String =
    if (leftMillis >= PERMANENT_UNTIL) "[red]你已被永久禁止${action}（多次被检举成功）。"
    else "[red]你当前被禁止${action}（剩余 [white]${formatPunishLeft(leftMillis)}[red]）。"

/** (施罚者, 目标) 24h 冷却剩余毫秒；0 = 可施加。 */
fun punishCooldownLeftMillis(operatorUids: Iterable<String>, targetUids: Iterable<String>): Long {
    val opKeys = operatorUids.filter { it.isNotBlank() }.toSet()
    val targetKeys = targetUids.filter { it.isNotBlank() }.toSet()
    if (opKeys.isEmpty() || targetKeys.isEmpty()) return 0L
    ensurePunishStateLoaded()
    val now = System.currentTimeMillis()
    var best = 0L
    synchronized(PUNISH_LOCK) {
        punishCooldowns.entries.removeIf { it.value <= now }
        opKeys.forEach { op ->
            targetKeys.forEach { target ->
                val until = punishCooldowns["$op|$target"] ?: 0L
                if (until - now > best) best = until - now
            }
        }
    }
    return best.coerceAtLeast(0L)
}

/** 记录一次真实生效的限制性惩罚的 24h 冷却（仅非管理员层级施罚者调用）。 */
private fun markPunishmentApplied(operatorUids: Iterable<String>, targetUids: Iterable<String>) {
    val opKeys = operatorUids.filter { it.isNotBlank() }.toSet()
    val targetKeys = targetUids.filter { it.isNotBlank() }.toSet()
    val cooldown = punishRepeatCooldownMillisValue()
    if (opKeys.isEmpty() || targetKeys.isEmpty() || cooldown <= 0L) return
    ensurePunishStateLoaded()
    val now = System.currentTimeMillis()
    synchronized(PUNISH_LOCK) {
        punishCooldowns.entries.removeIf { it.value <= now }
        opKeys.forEach { op ->
            targetKeys.forEach { target -> punishCooldowns["$op|$target"] = now + cooldown }
        }
        persistPunishStateLocked()
    }
}

/** 应用成功后记录冷却；管理员层级/系统施加不记录（用户口径：管理员不受限）。 */
fun markPunishmentAppliedIfNeeded(
    operatorUids: Iterable<String>,
    operatorLevelOrder: Int,
    targetUids: Iterable<String>,
) {
    if (operatorLevelOrder < 0 || isAdminTierLevel(operatorLevelOrder)) return
    markPunishmentApplied(operatorUids, targetUids)
}

/**
 * 应用前统一检查结果：
 * - blockReason 非空 = 拒绝施加（已含给操作者的中文提示）；
 * - allowedMinutes = 钳制后的实际时长（null = 无时长/未指定）；
 * - note 非空 = 需要提示操作者的说明（例如“已按 1 天上限钳制”）。
 */
data class PunishDecision(
    val blockReason: String?,
    val allowedMinutes: Int?,
    val note: String?,
)

/**
 * 限制性惩罚应用前检查（顺序固定：检举封禁 → 24h 冷却 → 时长钳制/永久拒绝）。
 *
 * @param operatorUids 施罚者全部主体 uid（投票类传发起者）；空集/系统归因不做限制
 * @param operatorLevelOrder 施罚者实时等级序（-1 = 无归因/系统）
 * @param permanentRequest true = 本次请求的是“永久/无期限”类惩罚（禁言留空、禁建留空）
 */
fun preparePunishment(
    operatorUids: Iterable<String>,
    operatorLevelOrder: Int,
    targetUids: Iterable<String>,
    requestedMinutes: Int?,
    permanentRequest: Boolean,
): PunishDecision {
    val operatorKeys = operatorUids.filter { it.isNotBlank() }.toSet()
    // 系统/无归因：保持旧行为，不做新限制。
    if (operatorKeys.isEmpty() || operatorLevelOrder < 0) return PunishDecision(null, requestedMinutes, null)
    // 管理员层级（3++/4）不受钳制/冷却/检举封禁约束；被检举者事后升到 3++ 也按实时等级豁免。
    if (isAdminTierLevel(operatorLevelOrder)) return PunishDecision(null, requestedMinutes, null)

    val banLeft = restrictionBanLeftMillis(operatorKeys)
    if (banLeft > 0L) return PunishDecision(restrictionBanBlockMessage(banLeft), null, null)

    val targetKeys = targetUids.filter { it.isNotBlank() }.toSet()
    val cooldownLeft = punishCooldownLeftMillis(operatorKeys, targetKeys)
    if (cooldownLeft > 0L) {
        return PunishDecision(
            "[yellow]24 小时内你已对该玩家施加过限制性惩罚（禁言/观战/禁建共用冷却），需等待 [white]${formatPunishLeft(cooldownLeft)}[yellow] 后才能再次施加。",
            null,
            null,
        )
    }

    if (permanentRequest) {
        return PunishDecision(
            "[red]非管理员层级不能施加永久/无期限的限制性惩罚；带时间的惩罚最长 ${nonAdminMaxPunishMinutesValue()} 分钟（1 天）。",
            null,
            null,
        )
    }

    if (requestedMinutes == null) return PunishDecision(null, null, null)
    val cap = nonAdminMaxPunishMinutesValue()
    if (requestedMinutes > cap) {
        return PunishDecision(
            null,
            cap,
            "[yellow]你是非管理员层级，单次带时间惩罚最多 ${cap} 分钟（1 天），时长已自动调整为 ${cap} 分钟。",
        )
    }
    return PunishDecision(null, requestedMinutes, null)
}

/**
 * 检举投票成功：给施罚者累计一次“成功检举”，并按阈值封禁其限制性权限（1 天/永久）。
 * 返回 null 表示施罚者 uid 无效。
 */
data class ReportStrikeResult(
    val uid: String,
    val name: String,
    val strikeCount: Int,
    val permanent: Boolean,
    /** 本次封禁时长（毫秒）；永久时为 Long.MAX_VALUE。 */
    val banLeftMillis: Long,
)

fun applyReportStrike(
    operatorUid: String,
    operatorName: String,
    operatorUids: Iterable<String>,
): ReportStrikeResult? {
    val primary = operatorUid.trim()
    if (primary.isEmpty()) return null
    val keys = (operatorUids.filter { it.isNotBlank() } + primary).distinct()
    ensurePunishStateLoaded()
    val now = System.currentTimeMillis()
    val threshold = permanentReportStrikeThresholdValue()
    synchronized(PUNISH_LOCK) {
        val existing = keys.mapNotNull { restrictionBans[it] }
            .maxWithOrNull(compareBy<RestrictionBanInfo> { it.strikeCount }.thenBy { if (it.permanent) 1 else 0 })
        val strikeCount = (existing?.strikeCount ?: 0) + 1
        val permanent = existing?.permanent == true || strikeCount >= threshold
        val banLeft = if (permanent) PERMANENT_UNTIL else reportBanHoursValue().toLong() * 3_600_000L
        val until = if (permanent) PERMANENT_UNTIL else now + banLeft
        val record = RestrictionBanInfo(primary, sanitizePersistText(operatorName.ifBlank { primary }), until, strikeCount)
        keys.forEach { restrictionBans[it] = record }
        persistPunishStateLocked()
        return ReportStrikeResult(record.uid, record.name, strikeCount, permanent, banLeft)
    }
}

/** 清空一组 uid 的检举封禁记录与成功检举次数（4 级管理用）；返回是否确实清除。 */
fun clearRestrictionBan(uids: Iterable<String>): Boolean {
    val keys = uids.filter { it.isNotBlank() }.toSet()
    if (keys.isEmpty()) return false
    ensurePunishStateLoaded()
    synchronized(PUNISH_LOCK) {
        var removed = false
        keys.forEach { if (restrictionBans.remove(it) != null) removed = true }
        if (removed) persistPunishStateLocked()
        return removed
    }
}

/** 当前生效的检举封禁列表（去重后按剩余时间排序，管理命令/菜单用）。 */
fun activeRestrictionBans(): List<RestrictionBanInfo> {
    ensurePunishStateLoaded()
    val now = System.currentTimeMillis()
    return synchronized(PUNISH_LOCK) {
        restrictionBans.values
            .filter { it.active(now) }
            .distinctBy { "${it.uid}|${it.untilMillis}|${it.strikeCount}" }
            .sortedWith(compareByDescending<RestrictionBanInfo> { it.permanent }.thenByDescending { it.untilMillis })
    }
}

/** 按全部主体 uid 查在线玩家（投票类归因对象可能已离线 → 返回 null）。 */
fun findOnlinePlayerByUids(uids: Iterable<String>): Player? {
    val keys = uids.filter { it.isNotBlank() }.toSet()
    if (keys.isEmpty()) return null
    return Groups.player.find { p ->
        runCatching { PlayerData[p].ids.any { it in keys } || PlayerData[p].id in keys }.getOrDefault(false)
    }
}

private val punishmentRecordSeq = AtomicLong(0L)

/**
 * 发射“惩罚已生效”事件；返回本次记录 id。发射失败不影响惩罚本身。
 *
 * 公开给 voteOb / playerBuildBan 等惩罚实现脚本调用（统一事件构造，避免各处重复）。
 */
fun emitPunishmentApplied(
    kind: String,
    target: Player,
    durationMinutes: Int?,
    operatorUid: String?,
    operatorName: String?,
    operatorLevelOrder: Int,
    operatorUids: Iterable<String>,
    source: String,
    reason: String,
): Long {
    val recordId = punishmentRecordSeq.incrementAndGet()
    runCatching {
        val data = PlayerData[target]
        val event = PunishmentAppliedEvent(
            kind = kind,
            recordId = recordId,
            source = source,
            operatorUid = operatorUid,
            operatorName = operatorName,
            operatorLevelOrder = operatorLevelOrder,
            operatorUids = operatorUids.filter { it.isNotBlank() }.toSet(),
            targetUuid = target.uuid(),
            targetUids = affectedKeys(data),
            targetName = target.plainName(),
            targetAuthed = data.authed,
            durationMinutes = durationMinutes,
            reason = reason.trim().ifEmpty { "未填写理由" },
        )
        launch { event.emitAsync() }
    }.onFailure { logger.warning("发射惩罚已生效事件失败: ${it.message}") }
    return recordId
}

/** 检举通过后按 uid 解除禁言（目标可能已离线）；返回是否确实清除了禁言状态。 */
fun liftMuteByUids(uids: Set<String>, targetUuid: String): Boolean {
    var removed = false
    uids.filter { it.isNotBlank() }.forEach { uid ->
        if (temporaryMutes.remove(uid) != null) removed = true
        if (MdtStorage.clearMute(uid)) removed = true
    }
    val player = Groups.player.find { it.uuid() == targetUuid }
        ?: Groups.player.find { p -> runCatching { PlayerData[p].ids.any { it in uids } }.getOrDefault(false) }
    if (player != null) {
        muteReasonCache.remove(player.uuid())
        if (removed) player.sendMessage("[green]你受到的禁言惩罚已被检举投票解除。")
    }
    if (removed) emitMuteChanged(uids)
    return removed
}

listenTo<PunishmentLiftedEvent> {
    if (kind != "mute") return@listenTo
    val lifted = this
    launch(Dispatchers.game) {
        runCatching { liftMuteByUids(lifted.targetUids, lifted.targetUuid) }
            .onFailure { logger.warning("检举解除禁言失败: ${it.message}") }
    }
}

// =====================================================================================
// 禁言实现
// =====================================================================================

private fun resolveOnlineTarget(text: String): Player? {
    if (text.startsWith("#")) {
        text.substring(1).toIntOrNull()?.let { id -> Groups.player.getByID(id)?.let { return it } }
    }
    PlayerData.findByShortId(text)?.player?.let { return it }
    val plain = text.replace(" ", "")
    return Groups.player.find { it.name.replace(" ", "") == plain || it.plainName().replace(" ", "") == plain }
}

private fun affectedKeys(data: PlayerData): Set<String> =
    (data.ids + data.id + data.uuid).filter { it.isNotBlank() }.toSet()

private fun loadMuteReason(data: PlayerData): String? {
    for (id in affectedKeys(data)) {
        MdtStorage.getMuteReason(id)?.let { return it }
    }
    return null
}

private fun temporaryMuteReason(data: PlayerData): String? {
    val now = System.currentTimeMillis()
    val keys = affectedKeys(data)
    var result: String? = null
    keys.forEach { key ->
        val mute = temporaryMutes[key] ?: return@forEach
        if (mute.untilMillis <= now) {
            temporaryMutes.remove(key, mute)
        } else if (result == null) {
            result = mute.reason
        }
    }
    return result
}

private fun cacheMuteReason(uuid: String, reason: String?) {
    muteReasonCache[uuid] = reason ?: NO_MUTE
}

fun muteReason(player: Player): String? {
    temporaryMuteReason(PlayerData[player])?.let { return it }
    muteReasonCache[player.uuid()]?.let { return it.takeIf { reason -> reason != NO_MUTE } }
    val reason = loadMuteReason(PlayerData[player])
    cacheMuteReason(player.uuid(), reason)
    return reason
}

fun isMuted(target: Player): Boolean = muteReason(target) != null

fun canManagePlayerMute(operator: Player, target: Player): Boolean =
    with(trustLevel) { canDirectRestrictTrustTarget(operator, target) }

fun mutePlayer(target: Player, reason: String, operator: Player? = null): Boolean {
    if (operator != null && !canManagePlayerMute(operator, target)) return false
    val operatorData = operator?.let { PlayerData[it] }
    val operatorUids = operatorData?.ids ?: emptySet()
    val operatorUid = operatorData?.id
    val operatorLevel = applierLevelOrder(operator)
    val targetData = PlayerData[target]
    val affectedUids = affectedKeys(targetData)

    // 永久禁言属于“无时长”惩罚：非管理员层级直接拒绝（用户口径 Q3）。
    val decision = preparePunishment(
        operatorUids = operatorUids,
        operatorLevelOrder = operatorLevel,
        targetUids = affectedUids,
        requestedMinutes = null,
        permanentRequest = true,
    )
    decision.blockReason?.let { reasonText ->
        operator?.sendMessage(reasonText)
        return false
    }

    val finalReason = reason.trim().ifEmpty { "未填写理由" }
    affectedUids.forEach { MdtStorage.setMute(it, finalReason, operatorUid) }

    val operatorName = operator?.name ?: "系统"
    target.sendMessage(
        """
            |[red]你已被禁言
            |[yellow]原因：[white]$finalReason
            |[yellow]可联系有权限的协管/管理进行解除
        """.trimMargin()
    )
    operator?.sendMessage("[green]已禁言 [white]${target.name}[green]，原因：[yellow]$finalReason")
    logger.info("$operatorName 禁言了 ${target.plainName()}，原因：$finalReason")
    cacheMuteReason(target.uuid(), finalReason)
    emitMuteChanged(affectedUids)
    markPunishmentAppliedIfNeeded(operatorUids, operatorLevel, affectedUids)
    emitPunishmentApplied(
        kind = "mute",
        target = target,
        durationMinutes = null,
        operatorUid = operatorUid,
        operatorName = operator?.name,
        operatorLevelOrder = operatorLevel,
        operatorUids = operatorUids,
        source = if (operator == null) "system" else "direct",
        reason = finalReason,
    )
    return true
}

private fun applyTemporaryMute(
    target: Player,
    minutes: Int,
    finalReason: String,
    operatorName: String,
): Set<String> {
    val until = System.currentTimeMillis() + minutes * 60_000L
    val targetData = PlayerData[target]
    val affectedUids = affectedKeys(targetData)
    val record = TemporaryMute(finalReason, until)
    affectedUids.forEach { temporaryMutes[it] = record }
    muteReasonCache.remove(target.uuid())

    target.sendMessage(
        """
            |[red]你已被临时禁言 [yellow]${minutes}分钟
            |[yellow]原因：[white]$finalReason
            |[yellow]可联系有权限的协管/管理进行解除
        """.trimMargin()
    )
    logger.info("$operatorName 临时禁言了 ${target.plainName()} ${minutes}分钟，原因：$finalReason")
    emitMuteChanged(affectedUids)
    return affectedUids
}

fun mutePlayerTemporary(target: Player, minutes: Int, reason: String, operator: Player? = null): Boolean {
    if (operator != null && !canManagePlayerMute(operator, target)) return false
    val operatorData = operator?.let { PlayerData[it] }
    val operatorUids = operatorData?.ids ?: emptySet()
    val operatorUid = operatorData?.id
    val operatorLevel = applierLevelOrder(operator)
    val targetKeys = affectedKeys(PlayerData[target])
    val decision = preparePunishment(
        operatorUids = operatorUids,
        operatorLevelOrder = operatorLevel,
        targetUids = targetKeys,
        requestedMinutes = minutes,
        permanentRequest = false,
    )
    decision.blockReason?.let { reasonText ->
        operator?.sendMessage(reasonText)
        return false
    }
    decision.note?.let { operator?.sendMessage(it) }
    val fixedMinutes = (decision.allowedMinutes ?: minutes).coerceAtLeast(1)
    val finalReason = reason.trim().ifEmpty { "未填写理由" }
    val operatorName = operator?.name ?: "系统"
    applyTemporaryMute(target, fixedMinutes, finalReason, operatorName)
    operator?.sendMessage("[green]已临时禁言 [white]${target.name}[green] [yellow]${fixedMinutes}分钟[green]，原因：[yellow]$finalReason")
    markPunishmentAppliedIfNeeded(operatorUids, operatorLevel, targetKeys)
    emitPunishmentApplied(
        kind = "mute",
        target = target,
        durationMinutes = fixedMinutes,
        operatorUid = operatorUid,
        operatorName = operator?.name,
        operatorLevelOrder = operatorLevel,
        operatorUids = operatorUids,
        source = if (operator == null) "system" else "direct",
        reason = finalReason,
    )
    return true
}

/**
 * 投票禁言的应用入口（voteMute 调用）：施罚者归因为投票发起者。
 *
 * 非管理员发起的投票同样受 1 天上限、24h 冷却、检举封禁约束（用户口径 Q4）。
 * 这里是**新增函数**而不是给 mutePlayerTemporary 加参数：加参数会改变编译签名，
 * 导致热重载后旧调用点（playerInfoTripleTap 的已编译代码）找不到方法。
 */
fun mutePlayerTemporaryByVote(
    target: Player,
    minutes: Int,
    reason: String,
    starterUid: String,
    starterName: String,
    starterUids: Set<String> = emptySet(),
): Boolean {
    val operatorKeys = (starterUids.filter { it.isNotBlank() } + starterUid).filter { it.isNotBlank() }.toSet()
    val operatorLevel = applierLevelOrderByUid(starterUid)
    val targetKeys = affectedKeys(PlayerData[target])
    val decision = preparePunishment(
        operatorUids = operatorKeys,
        operatorLevelOrder = operatorLevel,
        targetUids = targetKeys,
        requestedMinutes = minutes,
        permanentRequest = false,
    )
    decision.blockReason?.let { reasonText ->
        findOnlinePlayerByUids(operatorKeys)?.sendMessage(reasonText)
        return false
    }
    decision.note?.let { note -> findOnlinePlayerByUids(operatorKeys)?.sendMessage(note) }
    val fixedMinutes = (decision.allowedMinutes ?: minutes).coerceAtLeast(1)
    val finalReason = reason.trim().ifEmpty { "未填写理由" }
    applyTemporaryMute(target, fixedMinutes, finalReason, starterName)
    findOnlinePlayerByUids(operatorKeys)
        ?.sendMessage("[green]投票已通过：已临时禁言 [white]${target.name}[green] [yellow]${fixedMinutes}分钟[green]。")
    markPunishmentAppliedIfNeeded(operatorKeys, operatorLevel, targetKeys)
    emitPunishmentApplied(
        kind = "mute",
        target = target,
        durationMinutes = fixedMinutes,
        operatorUid = starterUid.ifBlank { null },
        operatorName = starterName.ifBlank { null },
        operatorLevelOrder = operatorLevel,
        operatorUids = operatorKeys,
        source = "vote",
        reason = finalReason,
    )
    return true
}

fun unmutePlayer(target: Player, operator: Player? = null): Boolean {
    if (operator != null && !canManagePlayerMute(operator, target)) return false
    val data = PlayerData[target]
    var removed = false
    affectedKeys(data).forEach {
        if (temporaryMutes.remove(it) != null) removed = true
        if (MdtStorage.clearMute(it)) removed = true
    }
    if (!removed) return false

    val operatorName = operator?.name ?: "系统"
    target.sendMessage("[green]你已被解除禁言")
    operator?.sendMessage("[green]已解除 [white]${target.name}[green] 的禁言")
    logger.info("$operatorName 解除了 ${target.plainName()} 的禁言")
    cacheMuteReason(target.uuid(), null)
    emitMuteChanged((data.ids + data.id).toSet())
    return true
}

// 被禁言者自救投票豁免（用户 2026-10-17 批准）：只放行"解除自己观战限制"的投票。
// 允许的写法 = quitOb 的真实命令名/别名（见 voteOb.kts 的 quitOb 注册），大小写不敏感。
private val SELF_RESCUE_VOTE_SUBCOMMANDS = setOf("quitob", "quob", "解除观战", "退出观战")

private fun shouldBlockMutedInput(text: String): Boolean {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return true

    val commandText = trimmed.removePrefix("/")
    val commandName = commandText.substringBefore(" ").substringBefore("\t").lowercase()

    // 被禁言者可能同时被强制观战：必须能用 /vote quitOb 自救，否则自救入口被禁言拦死。
    // 只豁免这一个自救子命令，其它 vote 子命令（ob/kick/mute 等）仍照常拦截。
    if (commandName == "vote" || commandName == "投票") {
        val rest = commandText.substringAfter(" ", "").trim()
        val subCommand = rest.substringBefore(" ").substringBefore("\t").lowercase()
        if (subCommand in SELF_RESCUE_VOTE_SUBCOMMANDS) return false
    }

    // 禁言玩家不允许继续通过聊天类指令或投票聊天入口绕过限制。
    return commandName in setOf("t", "a", "vote", "投票", "votekick")
}

listenTo<OnChat> {
    if (!shouldBlockMutedInput(text)) return@listenTo
    muteReason(player) ?: return@listenTo
    received = true
    launch(Dispatchers.game) {
        player.sendMessage("[yellow]你已被禁言，可联系有权限的协管/管理进行解除")
    }
}

listen<EventType.PlayerJoin> {
    val uuid = it.player.uuid()
    val data = PlayerData[it.player]
    launch(Dispatchers.IO) {
        runCatching { cacheMuteReason(uuid, loadMuteReason(data)) }
            .onFailure { logger.warning("预加载玩家禁言状态失败: ${data.name} ${it.message}") }
    }
}

listen<EventType.PlayerLeave> {
    muteReasonCache.remove(it.player.uuid())
}

command("playermute", "管理指令：禁言玩家") {
    usage = "<玩家id/3位id/#游戏id> [理由]"
    aliases = listOf("pmute", "mute", "禁言")
    permission = "wayzer.admin.playerMute"
    body {
        if (arg.isEmpty()) replyUsage()
        val target = resolveOnlineTarget(arg[0]) ?: returnReply("[red]未找到在线玩家".with())
        if (player != null && !canManagePlayerMute(player!!, target)) {
            returnReply("[red]权限不足：3+只能处理0/1/2级玩家，3++可处理低于3++的玩家，4级保留全局管理。".with())
        }
        val reason = arg.drop(1).joinToString(" ").ifBlank { "未填写理由" }
        if (!mutePlayer(target, reason, player)) returnReply("[yellow]操作者权限或目标等级已变化（或触发了时限/冷却/检举封禁规则），禁言已取消。".with())
        reply("[green]已禁言 [white]{target.name}".with("target" to target))
    }
}

command("playerunmute", "管理指令：解除玩家禁言") {
    usage = "<玩家id/3位id/#游戏id>"
    aliases = listOf("punmute", "unmute", "解除禁言")
    permission = "wayzer.admin.playerMute"
    body {
        if (arg.isEmpty()) replyUsage()
        val target = resolveOnlineTarget(arg[0]) ?: returnReply("[red]未找到在线玩家".with())
        if (player != null && !canManagePlayerMute(player!!, target)) {
            returnReply("[red]权限不足：3+只能处理0/1/2级玩家，3++可处理低于3++的玩家，4级保留全局管理。".with())
        }
        if (unmutePlayer(target, player)) {
            reply("[green]已解除 [white]{target.name}[green] 的禁言".with("target" to target))
        } else {
            reply("[yellow]目标当前未被禁言".with())
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.playerMute", group = "@admin")
