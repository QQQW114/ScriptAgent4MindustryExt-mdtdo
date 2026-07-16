@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/map/betterTeam", "风险IP未登录观战")
@file:Depends("coreMindustry/utilNextChat", "风险IP未登录禁言")
@file:Depends("wayzer/user/shortID", "同IP小号广播短ID")

package wayzer.user

import coreMindustry.UtilNextChat.OnChat
import wayzer.lib.ConnectAsyncEvent
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.map.BetterTeam
import java.time.Duration

name = "账号IP防熊"

private val guardEnabled by config.key(true, "是否启用账号/IP防熊")
private val ignoreLocalIp by config.key(true, "是否忽略本地/内网IP，方便测试服本机调试")
private val recentWindowMillis by config.key(10 * 60_000L, "短时间多身份观察窗口，毫秒；仅统计不拒绝入服")
private val riskIpDurationMillis by config.key(24 * 60 * 60_000L, "IP出现封禁/踢出记录后的风险限制时长，毫秒")
private val riskTipIntervalMillis by config.key(30_000L, "风险IP未登录玩家提示间隔，毫秒")
private val altBroadcastEnabled by config.key(true, "同IP更换账号/UUID入服时是否广播可能小号提示")
private val altBroadcastWindowMillis by config.key(24 * 60 * 60_000L, "同IP上一个身份触发小号提示的有效窗口，毫秒")
private val altBroadcastDelayMillis by config.key(3_000L, "玩家入服后延迟多少毫秒再检查同IP小号提示，等待账号自动登录状态稳定")
private val kickRiskThreshold by config.key(2, "单IP踢出统计窗口内达到多少次后标记风险IP")
private val kickRecordWindowMillis by config.key(24 * 60 * 60_000L, "单IP踢出统计窗口，毫秒")

private val RISK_IP_INDEX_KEY = "account.ipRisk.index"

private data class RecentIdentity(
    val subjectKey: String,
    val usid: String,
    val uuid: String,
    val at: Long,
)

private data class IpIdentityRecord(
    val subjectKey: String,
    val displayId: String,
    val name: String,
    val uuid: String,
    val at: Long,
)

private data class IpKickRecord(
    val subjectKey: String,
    val displayId: String,
    val reason: String,
    val at: Long,
)

private data class RiskIpRecord(
    val ip: String,
    val until: Long,
    val reason: String,
    val operatorUid: String?,
)

private val teams = contextScript<BetterTeam>()
private val ipv4Regex = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
private val recentByIp = mutableMapOf<String, MutableList<RecentIdentity>>()
private val lastIdentityByIp = mutableMapOf<String, IpIdentityRecord>()
private val kickRecordsByIp = mutableMapOf<String, MutableList<IpKickRecord>>()
private val riskCacheByIp = mutableMapOf<String, RiskIpRecord>()
private var riskIndexLoaded = false
private var riskIndexCache = emptySet<String>()
private val lastRiskTips = mutableMapOf<String, Long>()
private val riskGuestUuidsByIp = mutableMapOf<String, String>() // player uuid -> ip
private val scheduledAltChecks = mutableMapOf<String, Long>()
private val recentAltBroadcasts = mutableMapOf<String, Long>()
private val guardLock = Any()
private var altCheckSequence = 0L

private fun normalizeIp(raw: String?): String =
    raw?.let { ipv4Regex.find(it)?.value ?: it.trim().substringBefore('%') }
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"

private fun isLocalOrPrivateIp(ip: String): Boolean {
    if (ip == "unknown" || ip == "localhost" || ip == "0:0:0:0:0:0:0:1" || ip == "::1") return true
    val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return ip.startsWith("steam:")
    val a = parts[0]
    val b = parts[1]
    return a == 10 ||
            a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
}

private fun shouldIgnoreIp(ip: String): Boolean =
    !guardEnabled || (ignoreLocalIp && isLocalOrPrivateIp(ip))

private fun formatLeftMillis(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(1)
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}小时${minutes % 60}分"
        minutes > 0 -> "${minutes}分${seconds % 60}秒"
        else -> "${seconds}秒"
    }
}

private fun sanitizeField(text: String?, max: Int = 160): String =
    text.orEmpty()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
        .trim()
        .take(max)

private fun sanitizeOneLine(text: String, max: Int = 160): String =
    sanitizeField(text, max).ifBlank { "未填写原因" }

private fun compactSettingKey(prefix: String, ip: String): String {
    val safe = ip.replace(Regex("""[^0-9A-Za-z_.:-]"""), "_")
    val direct = "$prefix.$safe"
    return if (direct.length <= 96) direct else "$prefix.${java.lang.Integer.toUnsignedString(ip.hashCode(), 36)}"
}

private fun riskSettingKey(ip: String): String = compactSettingKey("account.ipRisk", ip)

private fun lastIdentitySettingKey(ip: String): String = compactSettingKey("account.ipLast", ip)

private fun kickRecordsSettingKey(ip: String): String = compactSettingKey("account.ipKick", ip)

private fun encodeRisk(record: RiskIpRecord): String =
    listOf(
        record.until.toString(),
        sanitizeOneLine(record.reason),
        sanitizeField(record.operatorUid, 128),
    ).joinToString("\t")

private fun decodeRisk(ip: String, raw: String?): RiskIpRecord? {
    val text = raw?.takeIf { it.isNotBlank() } ?: return null
    val parts = text.split('\t')
    val until = parts.getOrNull(0)?.toLongOrNull() ?: return null
    return RiskIpRecord(
        ip = ip,
        until = until,
        reason = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "风险IP限制",
        operatorUid = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
    )
}

private fun loadRiskIndex(): Set<String> =
    MdtStorage.getSetting(RISK_IP_INDEX_KEY)
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

private fun saveRiskIndex(ips: Set<String>) {
    val fixed = ips.filter { it.isNotBlank() }.toSet()
    synchronized(guardLock) {
        riskIndexCache = fixed
        riskIndexLoaded = true
    }
    MdtStorage.setSetting(RISK_IP_INDEX_KEY, fixed.sorted().joinToString("\n"))
}

private fun encodeIdentity(record: IpIdentityRecord): String =
    listOf(
        sanitizeField(record.subjectKey, 128),
        sanitizeField(record.displayId, 32),
        sanitizeField(record.name, 64),
        sanitizeField(record.uuid, 128),
        record.at.toString(),
    ).joinToString("\t")

private fun decodeIdentity(raw: String?): IpIdentityRecord? {
    val text = raw?.takeIf { it.isNotBlank() } ?: return null
    val parts = text.split('\t')
    val subjectKey = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
    val at = parts.getOrNull(4)?.toLongOrNull() ?: return null
    return IpIdentityRecord(
        subjectKey = subjectKey,
        displayId = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: subjectKey.take(18),
        name = parts.getOrNull(2).orEmpty(),
        uuid = parts.getOrNull(3).orEmpty(),
        at = at,
    )
}

private fun loadLastIdentity(ip: String): IpIdentityRecord? {
    synchronized(guardLock) { lastIdentityByIp[ip]?.let { return it } }
    val loaded = decodeIdentity(MdtStorage.getSetting(lastIdentitySettingKey(ip))) ?: return null
    synchronized(guardLock) { lastIdentityByIp[ip] = loaded }
    return loaded
}

private fun saveLastIdentity(ip: String, record: IpIdentityRecord) {
    synchronized(guardLock) { lastIdentityByIp[ip] = record }
    MdtStorage.setSetting(lastIdentitySettingKey(ip), encodeIdentity(record))
}

private fun encodeKickRecords(records: List<IpKickRecord>): String =
    records.joinToString("\n") { record ->
        listOf(
            record.at.toString(),
            sanitizeField(record.subjectKey, 128),
            sanitizeField(record.displayId, 32),
            sanitizeOneLine(record.reason, 160),
        ).joinToString("\t")
    }

private fun decodeKickRecords(raw: String?): MutableList<IpKickRecord> =
    raw.orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val subjectKey = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            IpKickRecord(
                subjectKey = subjectKey,
                displayId = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: subjectKey.take(18),
                reason = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "投票踢出",
                at = at,
            )
        }
        .toMutableList()

private fun loadKickRecords(ip: String): MutableList<IpKickRecord> {
    synchronized(guardLock) { kickRecordsByIp[ip]?.let { return it } }
    val loaded = decodeKickRecords(MdtStorage.getSetting(kickRecordsSettingKey(ip)))
    synchronized(guardLock) {
        return kickRecordsByIp.getOrPut(ip) { loaded }
    }
}

private fun saveKickRecords(ip: String, records: List<IpKickRecord>) {
    synchronized(guardLock) { kickRecordsByIp[ip] = records.toMutableList() }
    MdtStorage.setSetting(kickRecordsSettingKey(ip), records.takeIf { it.isNotEmpty() }?.let { encodeKickRecords(it) })
}

private fun recentKickRecords(ip: String): List<IpKickRecord> {
    if (shouldIgnoreIp(ip)) return emptyList()
    val now = System.currentTimeMillis()
    val list = loadKickRecords(ip)
    var changed = false
    var snapshot = emptyList<IpKickRecord>()
    synchronized(guardLock) {
        changed = list.removeIf { now - it.at > kickRecordWindowMillis }
        snapshot = list.toList()
    }
    if (changed) saveKickRecords(ip, snapshot)
    return snapshot
}

private fun recordRecentIdentity(ip: String, subjectKey: String, usid: String, uuid: String) {
    val now = System.currentTimeMillis()
    synchronized(guardLock) {
        val list = recentByIp.getOrPut(ip) { mutableListOf() }
        list.removeIf { now - it.at > recentWindowMillis }
        list += RecentIdentity(subjectKey, usid, uuid, now)
    }
}

private fun identityForPlayer(player: Player): IpIdentityRecord {
    val data = PlayerData[player]
    val short = data.shortId.takeIf { it.isNotBlank() } ?: player.uuid().take(8)
    return IpIdentityRecord(
        subjectKey = data.id,
        displayId = short,
        name = player.plainName(),
        uuid = player.uuid(),
        at = System.currentTimeMillis(),
    )
}

private fun rememberIpIdentity(player: Player, broadcastAlt: Boolean) {
    val ip = playerIp(player)
    if (shouldIgnoreIp(ip)) return
    val current = identityForPlayer(player)
    val previous = loadLastIdentity(ip)
    val recentEnough = previous != null &&
            previous.at <= current.at &&
            current.at - previous.at <= altBroadcastWindowMillis.coerceAtLeast(0L)

    val sameGameUuid = previous != null &&
            previous.uuid.isNotBlank() &&
            current.uuid.isNotBlank() &&
            previous.uuid == current.uuid
    if (broadcastAlt && altBroadcastEnabled && recentEnough && previous!!.subjectKey != current.subjectKey && !sameGameUuid) {
        broadcast(
            "[yellow]{player.name}[white]进入了游戏，可能为 [yellow]{previousName}[white] 的小号[gray]（同IP上次身份：UUID={previousUuid}，短ID={previousId}）".with(
                "player" to player,
                "previousId" to previous.displayId,
                "previousName" to previous.name.ifBlank { "未知玩家" },
                "previousUuid" to previous.uuid.ifBlank { "未知" },
            )
        )
        logger.info(
            "[账号IP防熊] 同IP身份切换: ip=$ip, current=${current.subjectKey}/${current.displayId}, " +
                    "previous=${previous.subjectKey}/${previous.displayId}/${previous.name}"
        )
    }
    saveLastIdentity(ip, current)
}

private fun claimAltBroadcast(ip: String, previous: IpIdentityRecord, current: IpIdentityRecord): Boolean {
    val now = System.currentTimeMillis()
    val key = "$ip\t${previous.subjectKey}\t${current.subjectKey}\t${current.uuid}"
    var claimed = false
    synchronized(guardLock) {
        recentAltBroadcasts.entries.removeIf { now - it.value > 60_000L }
        val last = recentAltBroadcasts[key]
        if (last == null || now - last > 60_000L) {
            recentAltBroadcasts[key] = now
            claimed = true
        }
    }
    return claimed
}

private fun rememberIpIdentityAsync(player: Player, broadcastAlt: Boolean) {
    val ip = playerIp(player)
    if (shouldIgnoreIp(ip)) return
    val current = identityForPlayer(player)
    val playerUuid = player.uuid()
    val playerName = player.name
    launch(Dispatchers.IO) {
        val previous = loadLastIdentity(ip)
        val recentEnough = previous != null &&
                previous.at <= current.at &&
                current.at - previous.at <= altBroadcastWindowMillis.coerceAtLeast(0L)
        val sameGameUuid = previous != null &&
                previous.uuid.isNotBlank() &&
                current.uuid.isNotBlank() &&
                previous.uuid == current.uuid
        val shouldBroadcast = broadcastAlt && altBroadcastEnabled && recentEnough &&
                previous != null && previous.subjectKey != current.subjectKey && !sameGameUuid &&
                claimAltBroadcast(ip, previous, current)
        saveLastIdentity(ip, current)
        if (shouldBroadcast && previous != null) {
            withContext(Dispatchers.game) {
                val online = Groups.player.find { it.uuid() == playerUuid } ?: return@withContext
                broadcast(
                    "[yellow]{player.name}[white]进入了游戏，可能为 [yellow]{previousName}[white] 的小号[gray]（同IP上次身份：UUID={previousUuid}，短ID={previousId}）".with(
                        "player" to online,
                        "previousId" to previous.displayId,
                        "previousName" to previous.name.ifBlank { "未知玩家" },
                        "previousUuid" to previous.uuid.ifBlank { "未知" },
                    )
                )
                logger.info(
                    "[账号IP防熊] 同IP身份切换: ip=$ip, current=${current.subjectKey}/${current.displayId}, " +
                            "previous=${previous.subjectKey}/${previous.displayId}/${previous.name}, joinName=$playerName"
                )
            }
        }
    }
}

private fun scheduleRememberIpIdentity(player: Player, broadcastAlt: Boolean, delayMillis: Long = altBroadcastDelayMillis) {
    val uuid = player.uuid()
    val token = synchronized(guardLock) {
        altCheckSequence += 1
        scheduledAltChecks[uuid] = altCheckSequence
        altCheckSequence
    }
    launch(Dispatchers.game) {
        delay(delayMillis.coerceAtLeast(0L))
        val shouldRun = synchronized(guardLock) { scheduledAltChecks[uuid] == token }
        if (!shouldRun) return@launch
        synchronized(guardLock) { scheduledAltChecks.remove(uuid) }
        val online = Groups.player.find { it.uuid() == uuid } ?: return@launch
        rememberIpIdentityAsync(online, broadcastAlt)
    }
}

private fun riskRecord(ip: String): RiskIpRecord? {
    if (shouldIgnoreIp(ip)) return null
    val now = System.currentTimeMillis()
    synchronized(guardLock) {
        if (riskIndexLoaded && ip !in riskIndexCache) return null
        riskCacheByIp[ip]?.let { cached ->
            if (cached.until > now) return cached
            riskCacheByIp.remove(ip)
        }
    }

    val loaded = decodeRisk(ip, MdtStorage.getSetting(riskSettingKey(ip))) ?: return null
    if (loaded.until <= now) {
        MdtStorage.setSetting(riskSettingKey(ip), null)
        synchronized(guardLock) { riskCacheByIp.remove(ip) }
        launch(Dispatchers.game) { releaseRiskGuestsForIp(ip, "风险IP限制已过期") }
        return null
    }
    synchronized(guardLock) {
        riskCacheByIp[ip] = loaded
        if (riskIndexLoaded) riskIndexCache = riskIndexCache + ip
    }
    return loaded
}

fun isRiskIp(ip: String): Boolean = riskRecord(normalizeIp(ip)) != null

private fun playerIp(player: Player): String = normalizeIp(player.con?.address)

private fun isRiskGuest(player: Player): Boolean =
    !PlayerData[player].authed && riskRecord(playerIp(player)) != null

private fun tipRiskGuest(player: Player, force: Boolean = false) {
    val now = System.currentTimeMillis()
    val key = player.uuid()
    if (!force && (lastRiskTips[key] ?: 0L) + riskTipIntervalMillis > now) return
    val risk = riskRecord(playerIp(player)) ?: return
    lastRiskTips[key] = now
    player.sendMessage(
        "[yellow]此IP近24小时内出现封禁/踢出记录，未登录状态已被临时强制观战并禁言。" +
                "可使用 [gold]/login[] 登录已有账号；暂不能注册新账号。" +
                "[gray]剩余：${formatLeftMillis(risk.until - now)}，原因：${risk.reason}"
    )
}

private fun forceRiskGuestToOb(player: Player) {
    if (!isRiskGuest(player)) return
    synchronized(guardLock) { riskGuestUuidsByIp[player.uuid()] = playerIp(player) }
    if (player.team() != teams.spectateTeam) teams.changeTeam(player, teams.spectateTeam)
    tipRiskGuest(player)
}

private fun releaseRiskGuestsForIp(ip: String, reason: String, includeUntracked: Boolean = false): Int {
    val tracked = synchronized(guardLock) {
        val ids = riskGuestUuidsByIp.filterValues { it == ip }.keys.toSet()
        ids.forEach { riskGuestUuidsByIp.remove(it) }
        ids
    }
    var released = 0
    Groups.player.forEach { player ->
        if (PlayerData[player].authed) return@forEach
        if (playerIp(player) != ip) return@forEach
        if (player.team() != teams.spectateTeam) return@forEach
        if (player.uuid() !in tracked && !includeUntracked) return@forEach
        teams.changeTeam(player)
        player.sendMessage("[green]$reason，已解除风险IP造成的临时观战。")
        released++
    }
    return released
}

private fun activeRiskIps(): List<RiskIpRecord> {
    val now = System.currentTimeMillis()
    val index = loadRiskIndex()
    val active = mutableListOf<RiskIpRecord>()
    val expired = mutableSetOf<String>()
    index.forEach { ip ->
        val record = if (guardEnabled) {
            riskRecord(ip)
        } else {
            decodeRisk(ip, MdtStorage.getSetting(riskSettingKey(ip)))?.takeIf { it.until > now }
        }
        if (record != null && record.until > now) active += record else expired += ip
    }
    if (guardEnabled && expired.isNotEmpty()) saveRiskIndex(index - expired)
    return active.sortedBy { it.until }
}

fun markRiskIp(ipRaw: String?, reason: String, operatorPlayer: Player? = null): Boolean {
    val ip = normalizeIp(ipRaw)
    if (shouldIgnoreIp(ip)) return false
    val record = RiskIpRecord(
        ip = ip,
        until = System.currentTimeMillis() + riskIpDurationMillis,
        reason = sanitizeOneLine(reason),
        operatorUid = operatorPlayer?.let { PlayerData[it].id },
    )
    MdtStorage.setSetting(riskSettingKey(ip), encodeRisk(record))
    saveRiskIndex(loadRiskIndex() + ip)
    synchronized(guardLock) { riskCacheByIp[ip] = record }
    logger.warning("[账号IP防熊] 标记风险IP $ip，时长=${formatLeftMillis(riskIpDurationMillis)}，原因=${record.reason}")
    Groups.player.forEach { p -> if (playerIp(p) == ip) forceRiskGuestToOb(p) }
    return true
}

fun markRiskIpForPlayer(player: Player, reason: String, operatorPlayer: Player? = null): Boolean =
    markRiskIp(player.con?.address, reason, operatorPlayer)

private fun storedIpForPlayerData(data: PlayerData): String? =
    (data.ids + data.id).asSequence()
        .mapNotNull { uid -> MdtStorage.getSubjectLastIp(uid) }
        .map { normalizeIp(it) }
        .firstOrNull { !shouldIgnoreIp(it) }

fun markRiskIpForPlayerData(data: PlayerData, reason: String, operatorPlayer: Player? = null): Boolean {
    data.player?.let { player ->
        val onlineIp = playerIp(player)
        if (onlineIp != "unknown") return markRiskIp(onlineIp, reason, operatorPlayer)
    }
    val storedIp = storedIpForPlayerData(data) ?: return false
    return markRiskIp(storedIp, reason, operatorPlayer)
}

private fun recordKickForIp(
    ipRaw: String?,
    subjectKey: String,
    displayId: String,
    reason: String,
    operatorPlayer: Player? = null,
): Int {
    val ip = normalizeIp(ipRaw)
    if (shouldIgnoreIp(ip)) return 0
    val now = System.currentTimeMillis()
    val list = loadKickRecords(ip)
    var count = 0
    var snapshot = emptyList<IpKickRecord>()
    synchronized(guardLock) {
        list.removeIf { now - it.at > kickRecordWindowMillis }
        list += IpKickRecord(
            subjectKey = sanitizeField(subjectKey, 128).ifBlank { "unknown" },
            displayId = sanitizeField(displayId, 32).ifBlank { subjectKey.take(18) },
            reason = sanitizeOneLine(reason),
            at = now,
        )
        count = list.size
        snapshot = list.toList()
    }
    saveKickRecords(ip, snapshot)

    val threshold = kickRiskThreshold.coerceAtLeast(1)
    logger.info("[账号IP防熊] 记录IP $ip 踢出次数 $count/$threshold，目标=$displayId，原因=${sanitizeOneLine(reason)}")
    if (count >= threshold) {
        val hours = Duration.ofMillis(kickRecordWindowMillis).toHours().coerceAtLeast(1)
        markRiskIp(ip, "同IP ${hours}小时内账号被踢出 ${count} 次：${sanitizeOneLine(reason)}", operatorPlayer)
    }
    return count
}

fun recordKickForPlayerData(data: PlayerData, reason: String, operatorPlayer: Player? = null): Boolean {
    val ip = data.player?.let { playerIp(it) }?.takeIf { it != "unknown" }
        ?: storedIpForPlayerData(data)
        ?: return false
    val count = recordKickForIp(
        ipRaw = ip,
        subjectKey = data.id,
        displayId = data.shortId,
        reason = reason,
        operatorPlayer = operatorPlayer,
    )
    return count >= kickRiskThreshold.coerceAtLeast(1)
}

fun clearRiskIp(ipRaw: String?): Boolean {
    val ip = normalizeIp(ipRaw)
    val existed = riskRecord(ip) != null || MdtStorage.getSetting(riskSettingKey(ip)) != null
    MdtStorage.setSetting(riskSettingKey(ip), null)
    saveRiskIndex(loadRiskIndex() - ip)
    synchronized(guardLock) { riskCacheByIp.remove(ip) }
    releaseRiskGuestsForIp(ip, "风险IP限制已由管理员解除", includeUntracked = true)
    return existed
}

fun allowAccountLogin(player: Player, account: MdtStorage.AccountRecord): Boolean {
    // 新版规则允许同一IP登录多个已有账号；IP风险只限制“未登录态”和“注册新账号”，不拦截登录。
    recordRecentIdentity(playerIp(player), "account:${account.id}", player.usid(), player.uuid())
    return true
}

fun onAccountAuthed(player: Player) {
    val ip = playerIp(player)
    val tracked = synchronized(guardLock) { riskGuestUuidsByIp.remove(player.uuid()) != null }
    if (player.team() == teams.spectateTeam && (tracked || riskRecord(ip) != null)) {
        teams.changeTeam(player)
        player.sendMessage("[green]你已登录账号，风险IP的未登录观战/禁言限制不再作用于你。")
    }
    scheduleRememberIpIdentity(player, broadcastAlt = true, delayMillis = altBroadcastDelayMillis.coerceAtMost(500L))
}

fun allowAccountRegister(player: Player): Boolean {
    val ip = playerIp(player)
    val risk = riskRecord(ip) ?: return true
    player.sendMessage(
        "[red]此IP近24小时内出现封禁/踢出记录，暂不能注册新账号。" +
                "你仍可使用 [gold]/login[] 登录已有账号。" +
                "[gray]剩余：${formatLeftMillis(risk.until - System.currentTimeMillis())}，原因：${risk.reason}"
    )
    logger.warning("[账号IP防熊] 拒绝 ${player.plainName()} 在风险IP $ip 注册新账号，原因=${risk.reason}")
    return false
}

private fun shouldBlockRiskGuestInput(text: String): Boolean {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return true
    val commandName = trimmed.removePrefix("/")
        .substringBefore(" ")
        .substringBefore("\t")
        .lowercase()
    return commandName in setOf("t", "a", "vote", "投票", "votekick", "kick", "踢出")
}

listenTo<ConnectAsyncEvent> {
    val ip = normalizeIp(con.address)
    if (shouldIgnoreIp(ip)) return@listenTo

    // 这里只记录短时间内同IP身份变化的轻量观察数据，不再额外查询自动登录数据库。
    // 真正的小号广播在 PlayerJoin 后延迟执行，等待 accountAuth 的自动登录状态稳定，避免把已登录玩家误记为游客。
    val data = PlayerData.forAuth(packet)
    val subjectKey = if (data.authed) data.id else "guest:${packet.uuid}"
    recordRecentIdentity(ip, subjectKey, packet.usid, packet.uuid)
}

listenTo<BetterTeam.AssignTeamEvent>(Event.Priority.Intercept) {
    if (!isRiskGuest(player)) return@listenTo
    team = teams.spectateTeam
    synchronized(guardLock) { riskGuestUuidsByIp[player.uuid()] = playerIp(player) }
    tipRiskGuest(player)
}

listen<EventType.PlayerJoin> {
    val p = it.player
    scheduleRememberIpIdentity(p, broadcastAlt = true)
    if (isRiskGuest(p)) {
        launch(Dispatchers.game) { forceRiskGuestToOb(p) }
    }
}

onEnable {
    launch(Dispatchers.IO) {
        val index = loadRiskIndex()
        synchronized(guardLock) {
            riskIndexCache = index
            riskIndexLoaded = true
        }
    }
}

listenTo<OnChat> {
    if (!shouldBlockRiskGuestInput(text)) return@listenTo
    if (!isRiskGuest(player)) return@listenTo
    received = true
    launch(Dispatchers.game) { tipRiskGuest(player, force = true) }
}

listen<EventType.PlayerLeave> {
    val key = it.player.uuid()
    lastRiskTips.remove(key)
    synchronized(guardLock) {
        riskGuestUuidsByIp.remove(key)
        scheduledAltChecks.remove(key)
    }
}

listen<EventType.ResetEvent> {
    synchronized(guardLock) {
        recentByIp.clear()
        lastIdentityByIp.clear()
        kickRecordsByIp.clear()
        riskCacheByIp.clear()
        lastRiskTips.clear()
        riskGuestUuidsByIp.clear()
        scheduledAltChecks.clear()
        recentAltBroadcasts.clear()
    }
}

command("ipguard", "管理指令: IP防熊") {
    usage = "status|check <ip>|risk <ip> [原因]|unrisk <ip>|unblock <ip>|release <ip>|unbind <ip>"
    requirePermission("wayzer.admin.ipguard")
    body {
        if (player != null && !player!!.hasPermission("wayzer.admin.ipguard")) {
            returnReply("[red]权限不足：需要IP防熊管理权限".with())
        }
        when (arg.firstOrNull()?.lowercase()) {
            null, "status" -> {
                val risks = activeRiskIps()
                reply(
                    """
                    |[cyan]IP防熊状态:
                    |[white]启用: [yellow]$guardEnabled
                    |[white]本地/内网忽略: [yellow]$ignoreLocalIp
                    |[white]单IP多账号: [green]允许
                    |[white]风险IP限制时长: [yellow]${Duration.ofMillis(riskIpDurationMillis).toHours()}小时
                    |[white]踢出触发风险: [yellow]${kickRiskThreshold.coerceAtLeast(1)}次/${Duration.ofMillis(kickRecordWindowMillis).toHours().coerceAtLeast(1)}小时
                    |[white]同IP小号广播: [yellow]$altBroadcastEnabled[] / 窗口 [yellow]${Duration.ofMillis(altBroadcastWindowMillis).toHours().coerceAtLeast(1)}小时[] / 延迟 [yellow]${altBroadcastDelayMillis.coerceAtLeast(0L)}ms
                    |[white]当前风险IP数: [yellow]${risks.size}
                    |[white]本脚本临时观战游客: [yellow]${synchronized(guardLock) { riskGuestUuidsByIp.size }}
                    |[gray]风险IP只限制注册与未登录态；登录已有账号/USID自动登录不受影响。
                    """.trimMargin().with()
                )
            }

            "check" -> {
                val ip = normalizeIp(arg.getOrNull(1) ?: returnReply("[red]请输入IP".with()))
                val binding = MdtStorage.getIpAccountBinding(ip)
                val recent = synchronized(guardLock) {
                    recentByIp[ip]?.filter { System.currentTimeMillis() - it.at <= recentWindowMillis }.orEmpty()
                }
                val risk = riskRecord(ip)
                val kicks = recentKickRecords(ip)
                val lastIdentity = loadLastIdentity(ip)
                reply(
                    """
                    |[cyan]IP检查：[white]$ip
                    |[white]旧IP绑定记录: [yellow]${binding?.accountQq ?: binding?.accountId ?: "无"}
                    |[white]上一个身份: [yellow]${lastIdentity?.displayId ?: "无"}${lastIdentity?.name?.takeIf { it.isNotBlank() }?.let { "[gray]($it)" }.orEmpty()}
                    |[white]最近身份数: [yellow]${recent.map { it.subjectKey }.distinct().size}
                    |[white]最近USID数: [yellow]${recent.map { it.usid }.distinct().size}
                    |[white]踢出记录: [yellow]${kicks.size}/${kickRiskThreshold.coerceAtLeast(1)}[] [gray]${kicks.joinToString("，") { it.displayId }.take(80)}
                    |[white]风险状态: ${if (risk == null) "[green]无" else "[red]剩余 ${formatLeftMillis(risk.until - System.currentTimeMillis())}，原因：${risk.reason}"}
                    |[white]本脚本观战记录: [yellow]${synchronized(guardLock) { riskGuestUuidsByIp.count { it.value == ip } }}
                    """.trimMargin().with()
                )
            }

            "risk" -> {
                val ip = normalizeIp(arg.getOrNull(1) ?: returnReply("[red]请输入IP".with()))
                val reason = arg.drop(2).joinToString(" ").ifBlank { "管理员手动标记风险IP" }
                if (markRiskIp(ip, reason, player)) {
                    reply("[green]已标记风险IP：[white]$ip [gray]原因：$reason".with())
                } else {
                    reply("[yellow]未标记：IP被忽略或防熊未启用：[white]$ip".with())
                }
            }

            "unrisk", "unblock" -> {
                val ip = normalizeIp(arg.getOrNull(1) ?: returnReply("[red]请输入IP".with()))
                if (clearRiskIp(ip)) {
                    reply("[green]已解除风险IP限制：[white]$ip".with())
                } else {
                    reply("[yellow]该IP没有风险限制记录：[white]$ip".with())
                }
            }

            "release", "释放", "解除观战" -> {
                val ip = normalizeIp(arg.getOrNull(1) ?: returnReply("[red]请输入IP".with()))
                if (riskRecord(ip) != null) {
                    returnReply("[yellow]该IP仍处于风险限制中，请先使用 [gold]/ipguard unrisk $ip[] 解除风险限制；否则会立刻重新观战。".with())
                }
                val count = releaseRiskGuestsForIp(ip, "管理员手动释放风险IP观战残留", includeUntracked = true)
                reply("[green]已尝试释放风险IP观战残留：[white]$ip [green]，处理 [yellow]$count [green]名玩家。".with())
            }

            "unbind" -> {
                val ip = normalizeIp(arg.getOrNull(1) ?: returnReply("[red]请输入IP".with()))
                if (MdtStorage.deleteIpAccountBinding(ip)) {
                    reply("[green]已删除旧版IP账号绑定：[white]$ip".with())
                } else {
                    reply("[yellow]未找到旧版IP账号绑定：[white]$ip".with())
                }
            }

            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.ipguard", group = "@admin")
