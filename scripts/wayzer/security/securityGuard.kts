@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("coreMindustry/utilNextChat", "聊天拦截")
@file:Depends("coreMindustry/menu", "菜单风控接口")
@file:Depends("wayzer/map/betterTeam", "风险模式未登录观战")
@file:Depends("wayzer/user/accountAuth", "账号登录态")
@file:Depends("wayzer/user/accountGuestControl", "今日游客观战控制")
@file:Depends("wayzer/user/trustLevel", "信任等级权限")
@file:Depends("wayzer/reGrief/trafficMonitor", "上行流量估算")

package wayzer.security

import cf.wayzer.scriptAgent.Event
import coreMindustry.IMenuOpenGuard
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.UtilNextChat.OnChat
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.ConnectAsyncEvent
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.TrustLevelChangedEvent
import wayzer.map.BetterTeam
import wayzer.reGrief.TrafficMonitor
import wayzer.user.AccountGuestControl
import wayzer.user.TrustLevel
import java.time.Duration
import java.time.LocalDate
import kotlin.random.Random

name = "MDT安全风控"

private enum class SecurityMode { NORMAL, GUARD, ENHANCED }
private enum class PenaltyAction { NONE, WARN, KICK, BAN }

private data class ModeState(
    val mode: SecurityMode,
    val until: Long,
    val reason: String,
)

private data class IpBanRecord(
    val ip: String,
    val until: Long,
    val reason: String,
    val operatorUid: String?,
    val targetUuid: String? = null,
    val targetName: String? = null,
)

private data class RecentIdentity(
    val identity: String,
    val uuid: String,
    val usid: String,
    val at: Long,
)

private data class WindowCounter(
    val times: ArrayDeque<Long> = ArrayDeque(),
    var lastPenaltyAt: Long = 0L,
    var softPenaltyCount: Int = 0,
)

private data class StrikeState(
    var strikes: Int = 0,
    var firstAt: Long = 0L,
    var lastAt: Long = 0L,
)

private val teams = contextScript<BetterTeam>()
private val guestControl = contextScript<AccountGuestControl>()
private val trustLevel = contextScript<TrustLevel>()
private val trafficMonitor = contextScript<TrafficMonitor>()

private val guardEnabled by config.key(true, "是否启用MDT安全风控")
private val ignoreLocalIp by config.key(true, "是否忽略本地/内网IP，方便测试")
private val connectWindowMillis by config.key(60_000L, "连接频率统计窗口(ms)")
private val connectLimit by config.key(8, "单IP连接窗口内允许连接次数")
private val multiIdentityLimit by config.key(4, "单IP短时间允许出现的不同UUID/USID数量")
private val chatWindowMillis by config.key(5_000L, "聊天/指令限速窗口(ms)")
private val chatLimit by config.key(5, "单IP窗口内允许聊天/指令次数")
private val authedChatWindowMillis by config.key(5_000L, "已登录玩家聊天/指令限速窗口(ms)")
private val authedChatLimit by config.key(20, "已登录玩家窗口内允许聊天/指令次数")
private val commandLimitMultiplier by config.key(2, "指令输入限速相对聊天消息的倍率")
private val menuWindowMillis by config.key(5_000L, "菜单打开限速窗口(ms)")
private val menuLimit by config.key(8, "单IP窗口内允许打开菜单次数")
private val authedMenuWindowMillis by config.key(10_000L, "已绑定玩家菜单打开限速窗口(ms)")
private val authedMenuLimit by config.key(40, "已绑定玩家窗口内允许打开菜单次数")
private val helpMenuLimitMultiplier by config.key(3, "已绑定玩家Help/指令菜单翻页额外倍率")
private val repeatPenaltyCooldownMillis by config.key(2_000L, "同类限速连续处罚最小间隔(ms)")
private val strikeWindowMillis by config.key(60 * 60_000L, "异常处罚累计窗口(ms)")
private val ipBanMillis by config.key(24 * 60 * 60_000L, "自动IP封禁时长(ms)")
private val guardScoreThreshold by config.key(15, "进入风控模式异常分阈值")
private val enhancedScoreThreshold by config.key(30, "进入增强风控模式异常分阈值")
private val anomalyDecayMillis by config.key(10 * 60_000L, "异常分衰减间隔(ms)")
private val guardMinMillis by config.key(20 * 60_000L, "自动风控模式最短持续(ms)")
private val guardMaxMillis by config.key(60 * 60_000L, "自动风控模式最长持续(ms)")
private val enhancedMinMillis by config.key(20 * 60_000L, "自动增强风控最短持续(ms)")
private val enhancedMaxMillis by config.key(60 * 60_000L, "自动增强风控最长持续(ms)")
private val trafficCheckIntervalMillis by config.key(15_000L, "上行触发风控检查间隔(ms)")
private val trafficAutoSecurityEnabled by config.key(false, "是否允许上行超预算自动进入安全风控；默认关闭，避免与性能优化系统重复触发")
private val trafficGuardRatio by config.key(1.10, "上行超过预算多少倍进入风控")
private val trafficEnhancedRatio by config.key(1.35, "上行超过预算多少倍进入增强风控")
private val guestTipIntervalMillis by config.key(30_000L, "未登录玩家风控提示间隔(ms)")
private val guardRestrictNewGuests by config.key(true, "普通风控模式是否限制风控期间新进入的未登录玩家")
private val guardForceGuestSpectate by config.key(false, "普通风控模式是否强制所有游客观战；默认关闭，仅限制新进入游客")
private val notifyAdminsOnModeChange by config.key(true, "风控模式变化时是否私聊通知在线管理")

private val MODE_KEY = "security.mode"
private val MODE_UNTIL_KEY = "security.modeUntil"
private val MODE_REASON_KEY = "security.modeReason"
private val IP_BAN_INDEX_KEY = "security.ipBan.index"
private val ACCOUNT_RISK_INDEX_KEY = "account.ipRisk.index"
private val GUEST_FORCE_OB_DATE_KEY = "account.guestForceObDate"

private val ipv4Regex = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
private val lock = Any()
private var modeState = ModeState(SecurityMode.NORMAL, 0L, "")
private val bannedIps = mutableMapOf<String, IpBanRecord>()
private val recentByIp = mutableMapOf<String, MutableList<RecentIdentity>>()
private val connectCounters = mutableMapOf<String, WindowCounter>()
private val chatCounters = mutableMapOf<String, WindowCounter>()
private val menuCounters = mutableMapOf<String, WindowCounter>()
private val strikeStates = mutableMapOf<String, StrikeState>()
private val lastGuestTips = mutableMapOf<String, Long>()
private val securitySpectatedGuests = mutableSetOf<String>()
private val guardedGuestUuids = mutableSetOf<String>()
private val pendingGuardGuestUuids = mutableSetOf<String>()
private var anomalyScore = 0
private var lastAnomalyAt = 0L
private var trafficOverSamples = 0

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

private fun isGuestForceObTodayRaw(): Boolean =
    MdtStorage.getSetting(GUEST_FORCE_OB_DATE_KEY) == LocalDate.now().toString()

private fun sanitizeOneLine(text: String, max: Int = 180): String =
    text.replace('\n', ' ').replace('\r', ' ').trim().take(max).ifBlank { "未填写原因" }

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

private fun modeName(mode: SecurityMode): String = when (mode) {
    SecurityMode.NORMAL -> "normal"
    SecurityMode.GUARD -> "guard"
    SecurityMode.ENHANCED -> "enhanced"
}

private fun modeDisplay(mode: SecurityMode): String = when (mode) {
    SecurityMode.NORMAL -> "正常"
    SecurityMode.GUARD -> "风控"
    SecurityMode.ENHANCED -> "增强风控"
}

private fun parseMode(raw: String?): SecurityMode = when (raw?.trim()?.lowercase()) {
    "guard", "风控" -> SecurityMode.GUARD
    "enhanced", "strong", "增强", "增强风控" -> SecurityMode.ENHANCED
    else -> SecurityMode.NORMAL
}

private fun modeRank(mode: SecurityMode): Int = when (mode) {
    SecurityMode.NORMAL -> 0
    SecurityMode.GUARD -> 1
    SecurityMode.ENHANCED -> 2
}

private fun randomDuration(minMillis: Long, maxMillis: Long): Long {
    val min = minMillis.coerceAtLeast(60_000L)
    val max = maxMillis.coerceAtLeast(min)
    return if (max == min) min else Random.nextLong(min, max + 1)
}

private fun ipBanSettingKey(ip: String): String {
    val safe = ip.replace(Regex("""[^0-9A-Za-z_.:-]"""), "_")
    val direct = "security.ipBan.$safe"
    return if (direct.length <= 96) direct else "security.ipBan.${java.lang.Integer.toUnsignedString(ip.hashCode(), 36)}"
}

private fun accountRiskSettingKey(ip: String): String {
    val safe = ip.replace(Regex("""[^0-9A-Za-z_.:-]"""), "_")
    val direct = "account.ipRisk.$safe"
    return if (direct.length <= 96) direct else "account.ipRisk.${java.lang.Integer.toUnsignedString(ip.hashCode(), 36)}"
}

private fun isAccountRiskIpActive(ip: String): Boolean {
    val raw = MdtStorage.getSetting(accountRiskSettingKey(ip)) ?: return false
    val until = raw.split('\t').firstOrNull()?.toLongOrNull() ?: return false
    if (until > System.currentTimeMillis()) return true
    MdtStorage.setSetting(accountRiskSettingKey(ip), null)
    return false
}

private fun activeAccountRiskIps(): List<Pair<String, Long>> {
    val now = System.currentTimeMillis()
    return MdtStorage.getSetting(ACCOUNT_RISK_INDEX_KEY)
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { ip ->
            val until = MdtStorage.getSetting(accountRiskSettingKey(ip))
                ?.split('\t')
                ?.firstOrNull()
                ?.toLongOrNull()
            if (until != null && until > now) ip to until else null
        }
        .sortedBy { it.second }
        .toList()
}

private fun encodeBan(record: IpBanRecord): String =
    listOf(
        record.until.toString(),
        sanitizeOneLine(record.reason),
        record.operatorUid?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.take(128).orEmpty(),
        record.targetUuid?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.take(128).orEmpty(),
        record.targetName?.let { sanitizeOneLine(it, max = 80) }.orEmpty(),
    ).joinToString("\t")

private fun decodeBan(ip: String, raw: String?): IpBanRecord? {
    val text = raw?.takeIf { it.isNotBlank() } ?: return null
    val parts = text.split('\t')
    val until = parts.getOrNull(0)?.toLongOrNull() ?: return null
    return IpBanRecord(
        ip = ip,
        until = until,
        reason = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "安全风控IP封禁",
        operatorUid = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
        targetUuid = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
        targetName = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
    )
}

private fun loadIpBanIndex(): Set<String> =
    MdtStorage.getSetting(IP_BAN_INDEX_KEY)
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

private fun saveIpBanIndex(ips: Set<String>) {
    MdtStorage.setSetting(IP_BAN_INDEX_KEY, ips.sorted().joinToString("\n"))
}

private fun loadModeState() {
    val mode = parseMode(MdtStorage.getSetting(MODE_KEY))
    val until = MdtStorage.getSetting(MODE_UNTIL_KEY)?.toLongOrNull() ?: 0L
    val reason = MdtStorage.getSetting(MODE_REASON_KEY).orEmpty()
    val clearDisabledTrafficMode = !trafficAutoSecurityEnabled && reason.contains("上行")
    modeState = if (mode == SecurityMode.NORMAL || (until > 0 && until <= System.currentTimeMillis()) || clearDisabledTrafficMode) {
        ModeState(SecurityMode.NORMAL, 0L, "")
    } else {
        ModeState(mode, until, reason)
    }
    if (clearDisabledTrafficMode) {
        saveModeState(modeState)
        logger.info("[安全风控] 已清理旧的上行自动风控状态；当前 trafficAutoSecurityEnabled=false")
    }
}

private fun saveModeState(state: ModeState) {
    MdtStorage.setSetting(MODE_KEY, modeName(state.mode))
    MdtStorage.setSetting(MODE_UNTIL_KEY, state.until.takeIf { it > 0 }?.toString())
    MdtStorage.setSetting(MODE_REASON_KEY, state.reason.takeIf { it.isNotBlank() })
}

private fun setMode(mode: SecurityMode, durationMillis: Long, reason: String, manual: Boolean = false) {
    val now = System.currentTimeMillis()
    val until = if (mode == SecurityMode.NORMAL) 0L else now + durationMillis.coerceAtLeast(60_000L)
    val sanitized = sanitizeOneLine(reason)
    val old = modeState
    if (!manual && modeRank(mode) < modeRank(old.mode) && (old.until == 0L || old.until > now)) return
    if (!manual && mode == old.mode && until <= old.until) return
    modeState = ModeState(mode, until, if (mode == SecurityMode.NORMAL) "" else sanitized)
    saveModeState(modeState)
    logger.warning("[安全风控] 模式切换 ${modeName(old.mode)} -> ${modeName(mode)}，原因=$sanitized，持续=${if (until > 0) formatLeftMillis(until - now) else "永久/已关闭"}")
    if (mode == SecurityMode.NORMAL) {
        if (manual) {
            anomalyScore = 0
            trafficOverSamples = 0
        }
        releaseSecuritySpectatedGuests("风控模式已解除", includeAllGuests = manual)
    }
    notifySecurityManagers(
        "[yellow][安全风控] 模式切换：[white]${modeDisplay(old.mode)}[yellow] -> [white]${modeDisplay(mode)}[yellow]；原因：[gray]$sanitized" +
                if (until > 0) "；剩余：[white]${formatLeftMillis(until - now)}" else ""
    )
}

fun securityMode(): String = modeName(activeMode())

private fun activeMode(): SecurityMode {
    val now = System.currentTimeMillis()
    val current = modeState
    if (current.mode != SecurityMode.NORMAL && current.until > 0 && current.until <= now) {
        modeState = ModeState(SecurityMode.NORMAL, 0L, "")
        saveModeState(modeState)
        logger.info("[安全风控] 自动退出风控模式")
        releaseSecuritySpectatedGuests("风控模式已自动解除")
        notifySecurityManagers("[green][安全风控] 已自动退出风控模式。")
        return SecurityMode.NORMAL
    }
    return current.mode
}

private fun loadIpBans() {
    val now = System.currentTimeMillis()
    val active = mutableMapOf<String, IpBanRecord>()
    val expired = mutableSetOf<String>()
    loadIpBanIndex().forEach { ip ->
        val record = decodeBan(ip, MdtStorage.getSetting(ipBanSettingKey(ip)))
        if (record != null && record.until > now) active[ip] = record else expired += ip
    }
    synchronized(lock) {
        bannedIps.clear()
        bannedIps.putAll(active)
    }
    if (expired.isNotEmpty()) saveIpBanIndex(loadIpBanIndex() - expired)
}

private fun activeBan(ip: String): IpBanRecord? {
    if (shouldIgnoreIp(ip)) return null
    val now = System.currentTimeMillis()
    synchronized(lock) {
        val record = bannedIps[ip] ?: return null
        if (record.until > now) return record
        bannedIps.remove(ip)
    }
    MdtStorage.setSetting(ipBanSettingKey(ip), null)
    saveIpBanIndex(loadIpBanIndex() - ip)
    return null
}

fun manualBanIp(
    ipRaw: String?,
    minutes: Long?,
    reason: String,
    operator: Player? = null,
    targetUuid: String? = null,
    targetName: String? = null,
): String? {
    val ip = normalizeIp(ipRaw)
    if (shouldIgnoreIp(ip)) return null
    val durationMillis = minutes?.let { Duration.ofMinutes(it.coerceAtLeast(1)).toMillis() } ?: ipBanMillis
    val record = IpBanRecord(
        ip = ip,
        until = System.currentTimeMillis() + durationMillis,
        reason = sanitizeOneLine(reason),
        operatorUid = operator?.let { PlayerData[it].id },
        targetUuid = targetUuid?.takeIf { it.isNotBlank() },
        targetName = targetName?.takeIf { it.isNotBlank() }?.let { sanitizeOneLine(it, max = 80) },
    )
    synchronized(lock) { bannedIps[ip] = record }
    MdtStorage.setSetting(ipBanSettingKey(ip), encodeBan(record))
    saveIpBanIndex(loadIpBanIndex() + ip)
    logger.warning("[安全风控] 封禁IP $ip，原因=${record.reason}，时长=${formatLeftMillis(durationMillis)}")
    Groups.player.toList().forEach { p ->
        if (playerIp(p) == ip) {
            p.kick("[red]此IP已被安全风控临时封禁。\n[yellow]原因：${record.reason}\n[gray]剩余：${formatLeftMillis(record.until - System.currentTimeMillis())}", 0)
        }
    }
    return ip
}

private fun banIp(ipRaw: String?, reason: String, operator: Player? = null): Boolean =
    manualBanIp(ipRaw, null, reason, operator) != null

fun playerIpForAdmin(player: Player): String = playerIp(player)

private fun unbanIp(ipRaw: String?): Boolean {
    val ip = normalizeIp(ipRaw)
    val existed = synchronized(lock) { bannedIps.remove(ip) != null } || MdtStorage.getSetting(ipBanSettingKey(ip)) != null
    MdtStorage.setSetting(ipBanSettingKey(ip), null)
    saveIpBanIndex(loadIpBanIndex() - ip)
    return existed
}

private fun activeBans(): List<IpBanRecord> {
    val now = System.currentTimeMillis()
    val expired = mutableListOf<String>()
    val list = synchronized(lock) {
        bannedIps.values.filter {
            if (it.until <= now) {
                expired += it.ip
                false
            } else true
        }.sortedBy { it.until }
    }
    expired.forEach { unbanIp(it) }
    return list
}

private fun ipBanTargetUuid(record: IpBanRecord): String? =
    record.targetUuid
        ?: Groups.player.find { playerIp(it) == record.ip }?.uuid()
        ?: synchronized(lock) { recentByIp[record.ip]?.lastOrNull()?.uuid }

private fun ipBanTargetName(record: IpBanRecord): String? {
    record.targetName?.let { return it }
    Groups.player.find { playerIp(it) == record.ip }?.plainName()?.let { return it }
    ipBanTargetUuid(record)?.let { uuid ->
        PlayerData.history.getIfPresent(uuid)?.name?.let { return it }
    }
    return null
}

private fun ipBanSummary(record: IpBanRecord): String {
    val uuid = ipBanTargetUuid(record) ?: "未知"
    val name = ipBanTargetName(record) ?: "未知"
    return "[white]${record.ip} [gray]剩余 ${formatLeftMillis(record.until - System.currentTimeMillis())}\n" +
            "[gray]玩家: [white]$name [gray]UUID: [white]$uuid"
}

private fun ipBanDetail(record: IpBanRecord): String {
    val uuid = ipBanTargetUuid(record) ?: "未知"
    val name = ipBanTargetName(record) ?: "未知"
    return """
        |[cyan]IP：[white]${record.ip}
        |[cyan]玩家名：[white]$name
        |[cyan]UUID：[white]$uuid
        |[cyan]操作人UID：[white]${record.operatorUid ?: "未知/系统"}
        |[cyan]剩余时间：[white]${formatLeftMillis(record.until - System.currentTimeMillis())}
        |[cyan]原因：[white]${record.reason}
        |[gray]解除指令：/unbanip ${record.ip}
    """.trimMargin()
}

private suspend fun openIpBanDetailMenu(player: Player, record: IpBanRecord) {
    MenuBuilder<Unit>("IP封禁详情") {
        msg = ipBanDetail(record)
        option("解除此IP封禁") {
            if (unbanIp(record.ip)) player.sendMessage("[green]已解除IP封禁：[white]${record.ip}")
            else player.sendMessage("[yellow]该IP封禁已不存在：[white]${record.ip}")
            openIpBanListMenu(player)
        }
        option("返回列表") { openIpBanListMenu(player) }
        newRow()
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openIpBanListMenu(player: Player) {
    val bans = activeBans()
    if (bans.isEmpty()) {
        player.sendMessage("[green]当前没有安全风控IP封禁。")
        return
    }
    PagedMenuBuilder(bans) { record ->
        option(ipBanSummary(record)) { openIpBanDetailMenu(player, record) }
    }.apply {
        title = "已封禁IP列表"
        msg = "点击条目查看详情或解除封禁；每项会显示 IP、对应 UUID 与玩家名。"
        sendTo(player, 60_000)
    }
}

private fun ipBanListText(): String {
    val bans = activeBans()
    if (bans.isEmpty()) return "[green]当前没有安全风控IP封禁。"
    return bans.joinToString("\n", prefix = "[cyan]安全风控IP封禁：\n") { record ->
        val uuid = ipBanTargetUuid(record) ?: "未知"
        val name = ipBanTargetName(record) ?: "未知"
        "[white]${record.ip} [gray]剩余 ${formatLeftMillis(record.until - System.currentTimeMillis())}，玩家：$name，UUID：$uuid，原因：${record.reason}"
    }
}

private fun playerIp(player: Player): String = normalizeIp(player.con?.address)

private fun isGuest(player: Player): Boolean = !PlayerData[player].authed

private fun isAuthedNativeAdmin(player: Player): Boolean = player.admin && PlayerData[player].authed

private fun isSecurityRestrictedGuest(player: Player): Boolean {
    if (!isGuest(player)) return false
    return when (activeMode()) {
        SecurityMode.NORMAL -> false
        SecurityMode.ENHANCED -> true
        SecurityMode.GUARD -> guardForceGuestSpectate || synchronized(lock) {
            player.uuid() in guardedGuestUuids || player.uuid() in pendingGuardGuestUuids
        }
    }
}

private fun isHelpLikeMenu(title: String): Boolean {
    val text = title.trim().lowercase()
    return text == "mdt帮助" ||
            "help" in text ||
            "帮助" in text ||
            "指令" in text ||
            text.startsWith("完整指令列表") ||
            text.startsWith("指令列表")
}

private fun shouldForceGuestSpectate(mode: SecurityMode = activeMode()): Boolean =
    mode == SecurityMode.ENHANCED || (mode == SecurityMode.GUARD && guardForceGuestSpectate)

private fun forceGuestToSpectate(player: Player) {
    if (!isGuest(player)) return
    if (player.team() != teams.spectateTeam) {
        teams.changeTeam(player, teams.spectateTeam)
        synchronized(lock) { securitySpectatedGuests += player.uuid() }
    }
    tipGuest(player)
}

private fun restrictNewGuardGuest(player: Player) {
    if (!isGuest(player)) return
    synchronized(lock) {
        guardedGuestUuids += player.uuid()
        pendingGuardGuestUuids.remove(player.uuid())
    }
    if (player.team() != teams.spectateTeam) {
        teams.changeTeam(player, teams.spectateTeam)
        synchronized(lock) { securitySpectatedGuests += player.uuid() }
    }
    tipGuest(player, force = true)
}

private fun releaseSecuritySpectatedGuests(reason: String, includeAllGuests: Boolean = false) {
    if (isGuestForceObTodayRaw()) return
    val ids = synchronized(lock) {
        val copy = securitySpectatedGuests.toSet()
        securitySpectatedGuests.clear()
        guardedGuestUuids.clear()
        pendingGuardGuestUuids.clear()
        copy
    }
    if (ids.isEmpty() && !includeAllGuests) return
    Groups.player.forEach { player ->
        if (!includeAllGuests && player.uuid() !in ids) return@forEach
        if (!isGuest(player)) return@forEach
        if (player.team() == teams.spectateTeam) {
            teams.changeTeam(player)
            player.sendMessage("[green]$reason，已解除安全风控造成的临时观战。")
        }
    }
}

private fun releaseSecuritySpectatedPlayerIfAuthed(player: Player, reason: String) {
    if (!PlayerData[player].authed) return
    val removed = synchronized(lock) {
        val uuid = player.uuid()
        val changed = securitySpectatedGuests.remove(uuid) || guardedGuestUuids.remove(uuid) || pendingGuardGuestUuids.remove(uuid)
        changed
    }
    if (removed && player.team() == teams.spectateTeam) {
        teams.changeTeam(player)
        player.sendMessage("[green]$reason，已解除安全风控造成的临时观战。")
    }
}

private fun tipGuest(player: Player, force: Boolean = false) {
    val now = System.currentTimeMillis()
    val key = player.uuid()
    if (!force && (lastGuestTips[key] ?: 0L) + guestTipIntervalMillis > now) return
    lastGuestTips[key] = now
    val state = modeState
    player.sendMessage(
        "[yellow]服务器当前处于${modeDisplay(activeMode())}模式，未登录玩家的聊天/部分操作已被限制。" +
                "请使用 [gold]/login[] 登录已有账号。" +
                state.reason.takeIf { it.isNotBlank() }?.let { "[gray]原因：$it" }.orEmpty()
    )
}

private fun pruneDeque(times: ArrayDeque<Long>, now: Long, windowMillis: Long) {
    while (times.isNotEmpty() && now - times.first() > windowMillis) times.removeFirst()
}

private fun registerStrike(ip: String, reason: String, player: Player? = null): PenaltyAction {
    if (shouldIgnoreIp(ip)) return PenaltyAction.NONE
    val now = System.currentTimeMillis()
    val strike = synchronized(lock) {
        val s = strikeStates.getOrPut(ip) { StrikeState(firstAt = now) }
        if (now - s.firstAt > strikeWindowMillis) {
            s.strikes = 0
            s.firstAt = now
        }
        s.strikes += 1
        s.lastAt = now
        s.strikes
    }
    addAnomaly(2, "IP $ip $reason")
    return when (strike) {
        1 -> {
            player?.sendMessage("[yellow]操作过快/行为异常，已记录一次安全提示；继续触发将被踢出。")
            logger.warning("[安全风控] IP $ip 第1次异常：$reason")
            PenaltyAction.WARN
        }
        2 -> {
            logger.warning("[安全风控] IP $ip 第2次异常，踢出触发者：$reason")
            player?.kick("[yellow]操作过快/行为异常，已被安全风控踢出。\n[gray]原因：$reason", 0)
            PenaltyAction.KICK
        }
        else -> {
            banIp(ip, "多次触发安全风控：$reason", player)
            PenaltyAction.BAN
        }
    }
}

private fun hitLimiter(
    ip: String,
    counters: MutableMap<String, WindowCounter>,
    windowMillis: Long,
    limit: Int,
    reason: String,
    player: Player? = null,
    counterKey: String = ip,
): PenaltyAction {
    if (shouldIgnoreIp(ip)) return PenaltyAction.NONE
    val now = System.currentTimeMillis()
    var exceeded = false
    var cooling = false
    synchronized(lock) {
        val counter = counters.getOrPut(counterKey) { WindowCounter() }
        pruneDeque(counter.times, now, windowMillis)
        counter.times.addLast(now)
        if (counter.times.size > limit.coerceAtLeast(1)) {
            if (now - counter.lastPenaltyAt < repeatPenaltyCooldownMillis) {
                cooling = true
            } else {
                counter.lastPenaltyAt = now
                exceeded = true
            }
        }
    }
    return when {
        exceeded -> registerStrike(ip, reason, player)
        cooling -> PenaltyAction.WARN
        else -> PenaltyAction.NONE
    }
}

// 菜单打开过快只用于保护主线程：提示后最多踢出，不计入异常分，也不触发账号/IP封禁。
private fun hitMenuLimiter(
    ip: String,
    counters: MutableMap<String, WindowCounter>,
    windowMillis: Long,
    limit: Int,
    reason: String,
    player: Player,
    counterKey: String = ip,
): PenaltyAction {
    if (shouldIgnoreIp(ip)) return PenaltyAction.NONE
    val now = System.currentTimeMillis()
    var action = PenaltyAction.NONE
    var penaltyCount = 0
    synchronized(lock) {
        val counter = counters.getOrPut(counterKey) { WindowCounter() }
        pruneDeque(counter.times, now, windowMillis)
        if (counter.lastPenaltyAt > 0L && now - counter.lastPenaltyAt > (windowMillis * 2).coerceAtLeast(repeatPenaltyCooldownMillis)) {
            counter.softPenaltyCount = 0
        }
        if (counter.times.isEmpty() && now - counter.lastPenaltyAt > windowMillis) {
            counter.lastPenaltyAt = 0L
            counter.softPenaltyCount = 0
        }
        counter.times.addLast(now)
        if (counter.times.size > limit.coerceAtLeast(1)) {
            if (now - counter.lastPenaltyAt < repeatPenaltyCooldownMillis) {
                action = PenaltyAction.WARN
            } else {
                counter.lastPenaltyAt = now
                counter.softPenaltyCount += 1
                penaltyCount = counter.softPenaltyCount
                action = if (penaltyCount <= 1) PenaltyAction.WARN else PenaltyAction.KICK
            }
        }
    }
    return when (action) {
        PenaltyAction.WARN -> {
            if (penaltyCount == 1) {
                logger.warning("[安全风控] IP $ip 菜单打开过快：$reason（仅提示，不计入异常分）")
            }
            PenaltyAction.WARN
        }
        PenaltyAction.KICK -> {
            logger.warning("[安全风控] IP $ip 菜单打开过快，踢出触发者：$reason（不封禁、不计入异常分）")
            player.kick("[yellow]菜单打开过快，已被安全风控踢出。\n[gray]原因：$reason", 0)
            PenaltyAction.KICK
        }
        else -> PenaltyAction.NONE
    }
}

private fun addAnomaly(points: Int, reason: String) {
    if (!guardEnabled) return
    val now = System.currentTimeMillis()
    if (now - lastAnomalyAt > anomalyDecayMillis) {
        anomalyScore = (anomalyScore - 1).coerceAtLeast(0)
    }
    lastAnomalyAt = now
    anomalyScore += points.coerceAtLeast(0)
    when {
        anomalyScore >= enhancedScoreThreshold -> setMode(
            SecurityMode.ENHANCED,
            randomDuration(enhancedMinMillis, enhancedMaxMillis),
            "异常分过高：$reason",
        )
        anomalyScore >= guardScoreThreshold -> setMode(
            SecurityMode.GUARD,
            randomDuration(guardMinMillis, guardMaxMillis),
            "异常分升高：$reason",
        )
    }
}

private fun commandNameOf(text: String): String? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return null
    return trimmed.removePrefix("/")
        .substringBefore(" ")
        .substringBefore("\t")
        .lowercase()
}

private val allowedGuestCommands = setOf(
    "login", "登录", "register", "注册", "account", "账号",
    "help", "helps", "规则", "wiki", "status", "security", "风控", "安全风控",
)

private fun shouldDropGuestInput(text: String): Boolean {
    val cmd = commandNameOf(text) ?: return true
    return cmd !in allowedGuestCommands
}

private fun recordConnect(ip: String, uuid: String, usid: String): Boolean {
    if (shouldIgnoreIp(ip)) return false
    val now = System.currentTimeMillis()
    var abnormal = false
    synchronized(lock) {
        val list = recentByIp.getOrPut(ip) { mutableListOf() }
        list.removeIf { now - it.at > connectWindowMillis }
        list += RecentIdentity("$uuid|$usid", uuid, usid, now)
        val identities = list.map { it.identity }.distinct().size
        if (identities > multiIdentityLimit.coerceAtLeast(1) ||
            (list.size > connectLimit.coerceAtLeast(1) && identities > 1)
        ) abnormal = true
    }
    return abnormal
}

private fun canAutoLogin(uuid: String, usid: String): Boolean =
    MdtStorage.autoLoginByDevice(uuid, usid)?.account?.status == "normal"

private fun checkTrafficPressure() {
    if (!guardEnabled) return
    if (!trafficAutoSecurityEnabled) return
    val budget = with(trafficMonitor) { trafficBudgetMbps() }
    if (budget <= 0.0) return
    val avg = with(trafficMonitor) { averageTrafficMbps() }
    if (avg <= 0.01) return

    when {
        avg >= budget * trafficEnhancedRatio -> {
            trafficOverSamples++
            if (trafficOverSamples >= 2) {
                addAnomaly(3, "上行持续超预算：${"%.2f".format(avg)}/${"%.2f".format(budget)}Mbps")
                setMode(
                    SecurityMode.ENHANCED,
                    randomDuration(enhancedMinMillis, enhancedMaxMillis),
                    "上行持续超预算：${"%.2f".format(avg)} Mbps",
                )
            }
        }
        avg >= budget * trafficGuardRatio -> {
            trafficOverSamples++
            if (trafficOverSamples >= 3) {
                addAnomaly(1, "上行接近/超过预算：${"%.2f".format(avg)}/${"%.2f".format(budget)}Mbps")
                setMode(
                    SecurityMode.GUARD,
                    randomDuration(guardMinMillis, guardMaxMillis),
                    "上行接近/超过预算：${"%.2f".format(avg)} Mbps",
                )
            }
        }
        avg <= with(trafficMonitor) { trafficRecoverMbps() } -> trafficOverSamples = 0
    }
}

private fun canManageSecurity(operator: Player?): Boolean {
    if (operator == null) return true
    return with(trustLevel) { isTrustAdmin(operator) }
}

private fun notifySecurityManagers(message: String) {
    if (!notifyAdminsOnModeChange) return
    Groups.player.forEach { player ->
        if (canManageSecurity(player)) player.sendMessage(message)
    }
}

private fun resetTemporarySecurityState(resetMode: Boolean, reason: String) {
    synchronized(lock) {
        recentByIp.clear()
        connectCounters.clear()
        chatCounters.clear()
        menuCounters.clear()
        strikeStates.clear()
        lastGuestTips.clear()
        anomalyScore = 0
        trafficOverSamples = 0
    }
    if (resetMode) {
        setMode(SecurityMode.NORMAL, 0L, reason, manual = true)
    }
}

private fun securityStatusText(): String {
    val mode = activeMode()
    val now = System.currentTimeMillis()
    val bans = activeBans()
    val riskIps = activeAccountRiskIps()
    val trafficAvg = with(trafficMonitor) { averageTrafficMbps() }
    val trafficBudget = with(trafficMonitor) { trafficBudgetMbps() }
    val modeLeft = if (mode == SecurityMode.NORMAL || modeState.until <= 0) "-" else formatLeftMillis(modeState.until - now)
    return """
        |[cyan]MDT安全风控:
        |[white]启用: [yellow]$guardEnabled[] / 忽略本地IP: [yellow]$ignoreLocalIp
        |[white]当前模式: [yellow]${modeDisplay(mode)}[] / 剩余: [yellow]$modeLeft
        |[white]原因: [gray]${modeState.reason.ifBlank { "无" }}
        |[white]异常分: [yellow]$anomalyScore[] / 普通阈值: [yellow]$guardScoreThreshold[] / 增强阈值: [yellow]$enhancedScoreThreshold
        |[white]IP封禁数: [yellow]${bans.size}[] / 风险IP数: [yellow]${riskIps.size}[] / 风控观战游客: [yellow]${synchronized(lock) { securitySpectatedGuests.size }}
        |[white]上行自动风控: [yellow]$trafficAutoSecurityEnabled[] / 普通风控限制新游客: [yellow]$guardRestrictNewGuests
        |[white]普通风控强制所有游客观战: [yellow]$guardForceGuestSpectate
        |[white]今日游客观战投票: [yellow]${isGuestForceObTodayRaw()}
        |[white]游客聊天限速: [yellow]${chatLimit}/${chatWindowMillis / 1000}s[]，指令x$commandLimitMultiplier / 已登录聊天限速: [yellow]${authedChatLimit}/${authedChatWindowMillis / 1000}s[]，指令x$commandLimitMultiplier
        |[white]游客菜单限速: [yellow]${menuLimit}/${menuWindowMillis / 1000}s
        |[white]已绑定菜单限速: [yellow]${authedMenuLimit}/${authedMenuWindowMillis / 1000}s[] / Help额外倍率: [yellow]x$helpMenuLimitMultiplier
        |[white]连接限制: [yellow]${connectLimit}/${connectWindowMillis / 1000}s[]，不同身份上限 [yellow]$multiIdentityLimit
        |[white]自动持续时间: [yellow]${guardMinMillis / 60_000}-${guardMaxMillis / 60_000}分钟[] / 增强 [yellow]${enhancedMinMillis / 60_000}-${enhancedMaxMillis / 60_000}分钟
        |[white]估算上行: [yellow]${"%.2f".format(trafficAvg)} Mbps[] / 预算 [yellow]${"%.2f".format(trafficBudget)} Mbps
    """.trimMargin()
}

private fun spectatorDiagnosticsText(): String {
    val guestOb = isGuestForceObTodayRaw()
    val players = Groups.player
        .filter { it.team() == teams.spectateTeam }
        .take(20)
    if (players.isEmpty()) {
        return "[green]当前没有玩家处于观察者队伍。"
    }
    val lines = players.map { p ->
        val uuid = p.uuid()
        val sources = mutableListOf<String>()
        synchronized(lock) {
            if (uuid in securitySpectatedGuests || uuid in guardedGuestUuids || uuid in pendingGuardGuestUuids) {
                sources += "安全风控"
            }
        }
        if (isGuest(p) && guestOb) sources += "今日游客观战"
        if (!PlayerData[p].authed && isAccountRiskIpActive(playerIp(p))) sources += "风险IP"
        if (sources.isEmpty()) {
            sources += if (PlayerData[p].authed) "可能为主动观战/强制观战/管理换队" else "可能为主动观战/投票强制观战/其他脚本"
        }
        "[white]${p.name}[gray](${PlayerData[p].id.take(18)})[]：${sources.joinToString("、")}"
    }
    return """
        |[cyan]观察者来源速查：
        |${lines.joinToString("\n")}
        |[gray]说明：这里只能准确识别安全风控、今日游客观战、风险IP；投票/管理强制观战请用玩家信息面板或 /forceOB，主动观战请让玩家 /ob 退出。
    """.trimMargin()
}

private fun defaultManualModeMinutes(mode: SecurityMode): Long = when (mode) {
    SecurityMode.NORMAL -> 0L
    SecurityMode.GUARD -> Duration.ofMillis(guardMinMillis).toMinutes().coerceAtLeast(1)
    SecurityMode.ENHANCED -> Duration.ofMillis(enhancedMinMillis).toMinutes().coerceAtLeast(1)
}

private fun MenuBuilder<Unit>.securityMenuRow() {
    newRow()
}

private suspend fun openSecurityMenu(player: Player) {
    val mode = activeMode()
    MenuBuilder<Unit>("MDT安全风控") {
        msg = securityStatusText() + "\n[gray]普通风控：只限制风控后新进入的游客；增强风控：限制未登录连接并强制游客观战。"
        option("刷新") { openSecurityMenu(player) }
        option("查看文字状态") { player.sendMessage(securityStatusText()) }
        securityMenuRow()
        option("进入普通风控 20分钟") {
            setMode(SecurityMode.GUARD, Duration.ofMinutes(20).toMillis(), "管理菜单手动开启普通风控", manual = true)
            openSecurityMenu(player)
        }
        option("进入普通风控 60分钟") {
            setMode(SecurityMode.GUARD, Duration.ofMinutes(60).toMillis(), "管理菜单手动开启普通风控", manual = true)
            openSecurityMenu(player)
        }
        securityMenuRow()
        option("进入增强风控 20分钟") {
            setMode(SecurityMode.ENHANCED, Duration.ofMinutes(20).toMillis(), "管理菜单手动开启增强风控", manual = true)
            openSecurityMenu(player)
        }
        option("解除风控") {
            setMode(SecurityMode.NORMAL, 0L, "管理菜单解除风控", manual = true)
            openSecurityMenu(player)
        }
        securityMenuRow()
        option("重置临时计数/异常分") {
            resetTemporarySecurityState(resetMode = true, reason = "管理菜单重置")
            openSecurityMenu(player)
        }
        option("释放安全风控观战游客") {
            releaseSecuritySpectatedGuests("管理员手动释放安全风控观战限制", includeAllGuests = true)
            openSecurityMenu(player)
        }
        securityMenuRow()
        option("查看观战来源速查") {
            player.sendMessage(spectatorDiagnosticsText())
            openSecurityMenu(player)
        }
        option("查看风险IP") {
            val risks = activeAccountRiskIps()
            player.sendMessage(
                if (risks.isEmpty()) "[green]当前没有账号IP防熊风险IP。"
                else risks.take(20).joinToString("\n", prefix = "[cyan]账号IP防熊风险IP：\n") {
                    "[white]${it.first} [gray]剩余 ${formatLeftMillis(it.second - System.currentTimeMillis())}"
                } + "\n[gray]解除请用 /ipguard unrisk <ip>；释放残留请用 /ipguard release <ip>。"
            )
            openSecurityMenu(player)
        }
        securityMenuRow()
        if (isGuestForceObTodayRaw()) {
            option("关闭今日游客观战") {
                with(guestControl) { disableGuestForceObToday() }
                with(guestControl) { releaseOnlineGuestsFromOb() }
                releaseSecuritySpectatedGuests("今日游客观战已关闭，同时刷新安全风控观战限制", includeAllGuests = true)
                player.sendMessage("[green]已关闭今日游客观战。")
                openSecurityMenu(player)
            }
        } else {
            option("开启今日游客观战") {
                with(guestControl) { enableGuestForceObToday() }
                with(guestControl) { forceOnlineGuestsToOb() }
                player.sendMessage("[green]已开启今日游客观战。")
                openSecurityMenu(player)
            }
        }
        securityMenuRow()
        option("查看封禁IP") {
            openIpBanListMenu(player)
        }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

listenTo<ConnectAsyncEvent>(Event.Priority.Intercept) {
    val ip = normalizeIp(con.address)
    if (shouldIgnoreIp(ip)) return@listenTo

    activeBan(ip)?.let { ban ->
        reject("此IP已被安全风控临时封禁。原因：${ban.reason}，剩余：${formatLeftMillis(ban.until - System.currentTimeMillis())}")
        return@listenTo
    }

    val abnormalConnect = recordConnect(ip, packet.uuid, packet.usid)
    if (abnormalConnect) {
        val action = registerStrike(ip, "短时间多连接/多身份进入")
        if (action == PenaltyAction.BAN) {
            reject("此IP短时间连接/身份异常，已被安全风控临时封禁。")
            return@listenTo
        }
        if (action == PenaltyAction.KICK) {
            reject("此IP短时间连接/身份异常，请稍后再试。")
            return@listenTo
        }
    }

    if (activeMode() == SecurityMode.ENHANCED && !canAutoLogin(packet.uuid, packet.usid)) {
        val action = registerStrike(ip, "增强风控期间未登录/未自动登录连接")
        if (action == PenaltyAction.BAN) {
            reject("增强风控期间未登录连接过多，IP已临时封禁。")
        } else {
            reject("服务器处于增强风控模式：暂只允许已登录且可自动登录的玩家进入。")
        }
    } else if (activeMode() == SecurityMode.GUARD && guardRestrictNewGuests && !canAutoLogin(packet.uuid, packet.usid)) {
        synchronized(lock) { pendingGuardGuestUuids += packet.uuid }
    }
}

listenTo<BetterTeam.AssignTeamEvent>(Event.Priority.Intercept) {
    val mode = activeMode()
    if (!isGuest(player)) return@listenTo
    if (mode == SecurityMode.NORMAL) return@listenTo
    if (mode == SecurityMode.GUARD && !guardForceGuestSpectate && player.uuid() !in synchronized(lock) { guardedGuestUuids + pendingGuardGuestUuids }) {
        return@listenTo
    }
    team = teams.spectateTeam
    synchronized(lock) { securitySpectatedGuests += player.uuid() }
    tipGuest(player)
}

listen<EventType.PlayerJoin> {
    val player = it.player
    val ip = playerIp(player)
    activeBan(ip)?.let { ban ->
        player.kick("[red]此IP已被安全风控临时封禁。\n[yellow]原因：${ban.reason}\n[gray]剩余：${formatLeftMillis(ban.until - System.currentTimeMillis())}", 0)
        return@listen
    }
    val mode = activeMode()
    if (mode == SecurityMode.ENHANCED && isGuest(player)) {
        launch(Dispatchers.game) { forceGuestToSpectate(player) }
    } else if (mode == SecurityMode.GUARD && isGuest(player)) {
        if (guardForceGuestSpectate) {
            launch(Dispatchers.game) { forceGuestToSpectate(player) }
        } else if (guardRestrictNewGuests && synchronized(lock) { player.uuid() in pendingGuardGuestUuids }) {
            launch(Dispatchers.game) { restrictNewGuardGuest(player) }
        }
    }
}

listenTo<OnChat>(Event.Priority.Intercept) {
    if (!isGuest(player)) releaseSecuritySpectatedPlayerIfAuthed(player, "已登录账号")
    val ip = playerIp(player)
    if (activeBan(ip) != null) {
        received = true
        launch(Dispatchers.game) { player.kick("[red]此IP已被安全风控临时封禁。", 0) }
        return@listenTo
    }

    val guest = isGuest(player)
    val isCommandInput = commandNameOf(text) != null
    val commandMultiplier = commandLimitMultiplier.coerceAtLeast(1)
    val action = if (isAuthedNativeAdmin(player)) {
        PenaltyAction.NONE
    } else if (guest) {
        hitLimiter(
            ip,
            chatCounters,
            chatWindowMillis,
            if (isCommandInput) chatLimit * commandMultiplier else chatLimit,
            if (isCommandInput) "未登录玩家指令过快" else "未登录玩家聊天过快",
            player
        )
    } else {
        hitLimiter(
            ip,
            chatCounters,
            authedChatWindowMillis,
            if (isCommandInput) authedChatLimit * commandMultiplier else authedChatLimit,
            if (isCommandInput) "已登录玩家指令过快" else "已登录玩家聊天过快",
            player,
            counterKey = "authedChat:${player.uuid()}",
        )
    }
    if (action != PenaltyAction.NONE) {
        received = true
        return@listenTo
    }

    if (isSecurityRestrictedGuest(player) && shouldDropGuestInput(text)) {
        received = true
        launch(Dispatchers.game) {
            if (shouldForceGuestSpectate(activeMode())) forceGuestToSpectate(player)
            else tipGuest(player, force = true)
        }
    }
}

listen<EventType.PlayerLeave> {
    val uuid = it.player.uuid()
    lastGuestTips.remove(uuid)
    synchronized(lock) { pendingGuardGuestUuids.remove(uuid) }
}

listenTo<TrustLevelChangedEvent> {
    val p = Groups.player.find { PlayerData[it].id == uid } ?: return@listenTo
    releaseSecuritySpectatedPlayerIfAuthed(p, "已登录账号")
}

listen<EventType.PlayerJoin> {
    val player = it.player
    val mode = activeMode()
    if (mode != SecurityMode.NORMAL && canManageSecurity(player)) {
        player.sendMessage(
            "[yellow][安全风控] 当前服务器处于 [white]${modeDisplay(mode)}[yellow] 模式；原因：[gray]${modeState.reason.ifBlank { "无" }}[yellow]。可用 [gold]/security status[] 查看，或 [gold]/security mode normal[] 解除。"
        )
    }
}

listen<EventType.ResetEvent> {
    synchronized(lock) {
        recentByIp.clear()
        connectCounters.clear()
        chatCounters.clear()
        menuCounters.clear()
        lastGuestTips.clear()
        securitySpectatedGuests.clear()
        guardedGuestUuids.clear()
        pendingGuardGuestUuids.clear()
    }
}

onEnable {
    loadModeState()
    loadIpBans()
    if (activeMode() == SecurityMode.NORMAL || (activeMode() == SecurityMode.GUARD && !guardForceGuestSpectate)) {
        releaseSecuritySpectatedGuests("安全风控观战限制已刷新", includeAllGuests = true)
    }
    IMenuOpenGuard.provide(this, object : IMenuOpenGuard {
        override fun menuBlockReason(player: Player, title: String, msg: String, followup: Boolean): String? {
            if (!isGuest(player)) releaseSecuritySpectatedPlayerIfAuthed(player, "已登录账号")
            val ip = playerIp(player)
            activeBan(ip)?.let { ban ->
                player.kick("[red]此IP已被安全风控临时封禁。\n[yellow]原因：${ban.reason}", 0)
                return "[red]此IP已被安全风控临时封禁。"
            }

            val guest = isGuest(player)
            val action = if (isAuthedNativeAdmin(player)) {
                PenaltyAction.NONE
            } else if (guest) {
                // 未绑定玩家仍使用较低的 IP 维度限制，防止机器人刷菜单拖慢主线程。
                hitMenuLimiter(ip, menuCounters, menuWindowMillis, menuLimit, "未绑定玩家菜单打开过快", player)
            } else {
                // 已绑定玩家切换到“玩家 UUID 维度 + 更高上限”，避免同 IP 正常玩家互相误伤；
                // Help/指令菜单翻页属于正常高频操作，使用额外倍率，只拦截持续反复刷菜单的异常行为。
                val limit = if (isHelpLikeMenu(title)) authedMenuLimit * helpMenuLimitMultiplier.coerceAtLeast(1) else authedMenuLimit
                hitMenuLimiter(
                    ip,
                    menuCounters,
                    authedMenuWindowMillis,
                    limit,
                    if (isHelpLikeMenu(title)) "已绑定玩家Help菜单持续刷屏" else "已绑定玩家菜单持续打开过快",
                    player,
                    counterKey = "authed:${player.uuid()}:${if (isHelpLikeMenu(title)) "help" else "menu"}",
                )
            }
            if (action != PenaltyAction.NONE) {
                return if (action == PenaltyAction.KICK) {
                    "[yellow]菜单打开过快，已被安全风控踢出。"
                } else {
                    "[yellow]菜单打开过快，请稍后再试；继续触发将被踢出。"
                }
            }
            if (activeMode() == SecurityMode.ENHANCED && isGuest(player)) {
                forceGuestToSpectate(player)
                return "[yellow]服务器处于增强风控模式，未登录玩家暂不能打开菜单。请先 /login。"
            }
            return null
        }
    })
    launch(Dispatchers.game) {
        while (true) {
            delay(Duration.ofMillis(trafficCheckIntervalMillis.coerceAtLeast(5_000L)).toMillis())
            activeMode()
            checkTrafficPressure()
        }
    }
}

command("security", "管理指令：MDT安全风控") {
    usage = "menu|status|spectators|banips|mode <normal|guard|enhanced> [分钟] [原因]|banip <ip> [分钟] [原因]|unbanip <ip>|check <ip/3位ID>|release|reset"
    aliases = listOf("风控", "安全风控")
    requirePermission("wayzer.admin.security")
    body {
        if (!canManageSecurity(player)) {
            returnReply("[red]权限不足：只有4级/admin或控制台可以管理安全风控。".with())
        }
        when (arg.getOrNull(0)?.lowercase() ?: if (player != null) "menu" else "status") {
            "menu", "菜单", "panel", "面板" -> {
                val p = player ?: returnReply(securityStatusText().with())
                openSecurityMenu(p)
            }
            "status", "状态" -> reply(securityStatusText().with())
            "spectators", "观战", "观战来源", "ob" -> reply(spectatorDiagnosticsText().with())
            "banips", "ipbans", "封禁ip列表", "封ip列表", "列表" -> {
                val p = player
                if (p != null) openIpBanListMenu(p) else reply(ipBanListText().with())
            }
            "mode", "模式" -> {
                val target = parseMode(arg.getOrNull(1) ?: returnReply("[red]请输入模式 normal|guard|enhanced".with()))
                val minutes = arg.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 } ?: defaultManualModeMinutes(target)
                val reason = arg.drop(3).joinToString(" ").ifBlank { "管理员手动设置" }
                setMode(target, Duration.ofMinutes(minutes).toMillis(), reason, manual = true)
                reply("[green]已设置安全风控模式为 [yellow]${modeDisplay(target)}[green]。".with())
            }
            "banip", "封ip", "封禁ip" -> {
                val ip = arg.getOrNull(1) ?: returnReply("[red]请输入IP".with())
                val minutes = arg.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
                val reasonStart = if (minutes == null) 2 else 3
                val reason = arg.drop(reasonStart).joinToString(" ").ifBlank { "管理员手动封禁IP" }
                val bannedIp = manualBanIp(ip, minutes, reason, player)
                    ?: returnReply("[red]无法封禁该IP（可能是本地/内网IP，或安全风控已关闭）。".with())
                reply("[green]已封禁IP：[white]$bannedIp [gray]原因：$reason".with())
            }
            "unbanip", "解ip", "解除ip" -> {
                val ip = normalizeIp(arg.getOrNull(1) ?: returnReply("[red]请输入IP".with()))
                if (unbanIp(ip)) reply("[green]已解除IP封禁：[white]$ip".with())
                else reply("[yellow]未找到该IP封禁：[white]$ip".with())
            }
            "check", "查询" -> {
                val input = arg.getOrNull(1) ?: returnReply("[red]请输入IP或玩家3位ID".with())
                val targetPlayer = PlayerData.findByShortId(input)?.player
                val ip = targetPlayer?.let { playerIp(it) } ?: normalizeIp(input)
                val ban = activeBan(ip)
                val recent = synchronized(lock) { recentByIp[ip]?.filter { System.currentTimeMillis() - it.at <= connectWindowMillis }.orEmpty() }
                val strike = synchronized(lock) { strikeStates[ip] }
                reply(
                    """
                    |[cyan]安全风控查询：[white]$ip
                    |[white]在线玩家：[yellow]${targetPlayer?.name ?: "无/按IP查询"}
                    |[white]封禁状态：${if (ban == null) "[green]无" else "[red]剩余 ${formatLeftMillis(ban.until - System.currentTimeMillis())}，原因：${ban.reason}"}
                    |[white]近期连接数：[yellow]${recent.size}[] / 不同身份：[yellow]${recent.map { it.identity }.distinct().size}
                    |[white]异常次数：[yellow]${strike?.strikes ?: 0}
                    """.trimMargin().with()
                )
            }
            "reset", "重置" -> {
                resetTemporarySecurityState(resetMode = true, reason = "管理员重置")
                reply("[green]已重置安全风控临时状态，并退出风控模式。".with())
            }
            "release", "释放", "解除观战残留" -> {
                releaseSecuritySpectatedGuests("管理员手动释放安全风控观战限制", includeAllGuests = true)
                reply("[green]已尝试释放安全风控造成的游客观战限制；若今日游客观战/IP风险/强制观战仍生效，会继续保持观战。".with())
            }
            else -> replyUsage()
        }
    }
}

private fun resolveOnlineBanIpTarget(text: String): Player? {
    val fixed = text.trim()
    if (fixed.isBlank()) return null
    if (fixed.startsWith("#")) {
        fixed.substring(1).toIntOrNull()?.let { id -> Groups.player.getByID(id)?.let { return it } }
    }
    PlayerData.findByShortId(fixed)?.player?.let { return it }
    val plain = fixed.replace(" ", "")
    return Groups.player.find {
        it.uuid() == fixed ||
                PlayerData[it].id == fixed ||
                PlayerData[it].shortId.equals(fixed, ignoreCase = true) ||
                it.name.replace(" ", "") == plain ||
                it.plainName().replace(" ", "") == plain
    }
}

command("banip", "管理指令：根据在线玩家封禁其IP") {
    usage = "<玩家3位ID/#游戏ID/名字> [时间|分钟] [原因]"
    aliases = listOf("封ip", "封禁ip")
    requirePermission("wayzer.admin.banIp")
    body {
        if (arg.isEmpty()) replyUsage()
        val target = resolveOnlineBanIpTarget(arg[0])
            ?: returnReply("[red]未找到在线玩家，离线玩家可从 /recentplayers 最近玩家面板封禁IP。".with())
        val minutes = arg.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
        val reasonStart = if (minutes == null) 1 else 2
        val reason = arg.drop(reasonStart).joinToString(" ").ifBlank { "管理员根据玩家封禁IP: ${target.plainName()}" }
        val ip = manualBanIp(playerIp(target), minutes, reason, player, target.uuid(), target.plainName())
            ?: returnReply("[red]无法封禁目标IP（可能是本地/内网IP，或安全风控已关闭）。".with())
        reply("[green]已封禁 [white]{target.name}[green] 的IP：[white]$ip [gray]原因：$reason".with("target" to target))
    }
}

command("unbanip", "管理指令：解除IP封禁") {
    usage = "<ip>"
    aliases = listOf("解ip", "解除ip", "解除封ip")
    requirePermission("wayzer.admin.banIp")
    body {
        if (arg.isEmpty()) replyUsage()
        val ip = normalizeIp(arg[0])
        if (unbanIp(ip)) reply("[green]已解除IP封禁：[white]$ip".with())
        else reply("[yellow]未找到该IP封禁：[white]$ip".with())
    }
}

command("banips", "管理指令：查看当前IP封禁列表") {
    aliases = listOf("ipbans", "封禁ip列表", "封ip列表")
    requirePermission("wayzer.admin.banIp")
    body {
        val p = player
        if (p != null) openIpBanListMenu(p) else reply(ipBanListText().with())
    }
}

PermissionApi.registerDefault("wayzer.admin.security", "wayzer.admin.banIp", group = "@admin")
