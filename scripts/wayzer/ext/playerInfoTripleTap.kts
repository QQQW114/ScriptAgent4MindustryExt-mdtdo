@file:Depends("coreMindustry/menu", "调用标准菜单弹窗")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/ext/playerReputation", "玩家口碑/赞踩数据与额度控制")
@file:Depends("wayzer/ext/playerRecognition", "玩家认可")
@file:Depends("wayzer/ext/playerRandomForm", "随机形态")
@file:Depends("wayzer/ext/playerMute", "玩家禁言")
@file:Depends("wayzer/ext/playerBuildBan", "玩家禁建")
@file:Depends("wayzer/user/trustPoint", "MDC")
@file:Depends("wayzer/user/trustPromotion", "信任等级晋升检测")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/user/seniorityLevel", "MDT资历等级")
@file:Depends("wayzer/user/achievement", "成就系统")
@file:Depends("wayzer/user/shopList", "商店列表")
@file:Depends("wayzer/user/ban", "禁封实现")
@file:Depends("wayzer/cmds/voteKick", "投票踢出接口")
@file:Depends("wayzer/cmds/voteOb", "投票/强制观战接口")
@file:Depends("wayzer/security/securityGuard", "IP封禁接口")
@file:Depends("coreMindustry/utilTextInput", "理由输入")

package wayzer.ext

import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.lib.RootCommands
import coreLibrary.lib.PermissionApi
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.PlayerTitleChangedEvent
import wayzer.lib.RecognitionChangedEvent
import wayzer.lib.ReputationChangedEvent
import wayzer.lib.SeniorityLevelChangedEvent
import wayzer.lib.SeniorityLevelLockChangedEvent
import wayzer.lib.TrustLevelChangedEvent
import wayzer.lib.TrustLevelLockChangedEvent
import wayzer.lib.TrustPointChangedEvent
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private data class TapState(
    var x: Int,
    var y: Int,
    var count: Int,
    var lastTapAt: Long,
)

private data class PlayerProfileView(
    val name: String,
    val title: String,
    val level: String,
    val levelOrder: Int,
    val seniorityLevel: String,
    val playHours: String,
    val liked: Int,
    val disliked: Int,
    val givenLikes: Int,
    val givenDislikes: Int,
    val effectiveDisliked: Int,
    val receivedRecognitions: Int,
    val givenRecognitions: Int,
    val points: Int,
    val totalPoints: Int,
)

private data class CachedProfileView(
    val value: PlayerProfileView,
    val loadedAt: Long,
    var lastAccessAt: Long,
)

private data class RecentPlayerRecord(
    val uid: String,
    val uuid: String,
    val shortId: String,
    val name: String,
    val ip: String,
    val lastSeen: Long,
)

private data class ModerationDuration(val minutes: Int?)

private val playerReputation = contextScript<PlayerReputation>()
private val playerRecognition = contextScript<PlayerRecognition>()
private val playerRandomForm = contextScript<PlayerRandomForm>()
private val playerMute = contextScript<PlayerMute>()
private val playerBuildBan = contextScript<PlayerBuildBan>()
private val trustPoint = contextScript<wayzer.user.TrustPoint>()
private val trustPromotion = contextScript<wayzer.user.TrustPromotion>()
private val trustLevel = contextScript<wayzer.user.TrustLevel>()
private val seniorityLevel = contextScript<wayzer.user.SeniorityLevel>()
private val voteKick = contextScript<wayzer.cmds.VoteKick>()
private val voteOb = contextScript<wayzer.cmds.VoteOb>()
private val banImpl = contextScript<wayzer.user.Ban>()
private val securityGuard = contextScript<wayzer.security.SecurityGuard>()
private val textInput = contextScript<coreMindustry.UtilTextInput>()
private val tapStates = mutableMapOf<Player, TapState>()
private val profileCache = ConcurrentHashMap<String, CachedProfileView>()
private val lastInfoOpenAt = mutableMapOf<String, Long>()
private val RECENT_PLAYERS_KEY = "playerInfo.recentPlayers.v1"
private val EFFECTIVE_DISLIKE_RECENT_DAYS = 7L
private val TAP_CHAIN_TIMEOUT_MILLIS = 1_200L
private val PROFILE_CACHE_TTL_MILLIS by config.key(60_000L, "玩家信息面板资料缓存兜底刷新时间(ms)")
private val PROFILE_PANEL_OPEN_COOLDOWN_MILLIS by config.key(500L, "玩家信息面板重复打开限频(ms)")

private data class PlayerSnapshot(
    val uid: String,
    val uuid: String,
    val shortId: String,
    val name: String,
    val plainName: String,
    val ip: String,
    val authed: Boolean,
    val admin: Boolean,
)

private val recentPlayersLock = Any()
private var recentPlayersCache: MutableList<RecentPlayerRecord>? = null
private var recentSaveJob: kotlinx.coroutines.Job? = null

private fun nearestPlayerIn3x3(x: Int, y: Int): Player? {
    return Groups.player
        .filter { !it.dead() && kotlin.math.abs(it.tileX() - x) <= 1 && kotlin.math.abs(it.tileY() - y) <= 1 }
        .minByOrNull {
            val dx = it.tileX() - x
            val dy = it.tileY() - y
            dx * dx + dy * dy
        }
}

private fun onlinePlayerByUid(uid: String): Player? =
    Groups.player.find { PlayerData[it].id == uid }

private fun snapshotPlayer(player: Player): PlayerSnapshot {
    val data = PlayerData[player]
    val plainName = player.plainName().ifBlank { player.name }
    return PlayerSnapshot(
        uid = data.id,
        uuid = data.uuid,
        shortId = data.shortId,
        name = player.name,
        plainName = plainName,
        ip = with(securityGuard) { playerIpForAdmin(player) },
        authed = data.authed,
        admin = player.admin,
    )
}

private fun allowOpenInfoPanel(viewer: Player): Boolean {
    val now = System.currentTimeMillis()
    val key = viewer.uuid()
    val last = lastInfoOpenAt[key] ?: 0L
    if (now - last < PROFILE_PANEL_OPEN_COOLDOWN_MILLIS) return false
    lastInfoOpenAt[key] = now
    return true
}

private fun normalizeTrustCodeForProfile(level: String?): String? = when (level?.trim()?.lowercase()) {
    "0" -> "0"
    "1" -> "1"
    "2" -> "2"
    "3" -> "3"
    "3+", "3p", "3plus" -> "3+"
    "3++", "3pp", "3plusplus" -> "3++"
    "4", "admin", "4+admin", "4admin" -> "4"
    else -> null
}

private fun normalizeSeniorityCodeForProfile(level: String?): String? = when (level?.trim()?.lowercase()) {
    "0" -> "0"
    "1" -> "1"
    "2" -> "2"
    "3" -> "3"
    "4", "admin", "4+admin", "4admin" -> "4"
    else -> null
}

private fun normalizeTitleDisplayForProfile(displayName: String): String {
    val trimmed = displayName.replace('\n', ' ').replace('\r', ' ').trim().take(80)
    if (trimmed.isEmpty()) return ""
    return if ('[' !in trimmed && ']' !in trimmed) "[white][$trimmed][]" else trimmed
}

private fun loadTitleNameForProfile(uid: String, guest: Boolean): String {
    if (guest) return "[green][游客][]"
    val code = MdtStorage.getEquippedTitle(uid)?.trim()?.takeIf { it.isNotEmpty() } ?: return "暂无"
    val definition = MdtStorage.getTitleDefinition(code)
    return definition?.displayName ?: normalizeTitleDisplayForProfile(code)
}

private fun recentReputationSinceDate(): String =
    LocalDate.now().minusDays(EFFECTIVE_DISLIKE_RECENT_DAYS - 1).toString()

private fun calculateEffectiveDisliked(
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

private fun trustLevelTextForProfile(uid: String, authed: Boolean?, admin: Boolean): String {
    if (authed == false) return "0"
    if (admin) return if (authed == true) "4+admin" else "4"
    MdtStorage.getManualLevelCode(uid)?.let { normalizeTrustCodeForProfile(it) }?.let { return it }
    return if (authed == true) "1" else "0"
}

private fun seniorityLevelTextForProfile(uid: String, authed: Boolean?, trustCode: String): String {
    if (authed == false) return "0"
    if (trustOrder(trustCode) >= trustOrder("4")) return "4"
    return normalizeSeniorityCodeForProfile(MdtStorage.getSeniorityLevelCode(uid)) ?: "0"
}

private fun loadPlayerProfileViewFromStorage(uid: String, name: String, authed: Boolean?, admin: Boolean): PlayerProfileView {
    val levelText = trustLevelTextForProfile(uid, authed, admin)
    val reputation = MdtStorage.getReputation(uid)
    val recognition = MdtStorage.getRecognition(uid)
    val recentReputation = MdtStorage.getRecentReceivedReputation(uid, recentReputationSinceDate())
    val points = MdtStorage.getTrustPointCounts(uid)
    val effectiveDisliked = calculateEffectiveDisliked(reputation, recognition, recentReputation)
    return PlayerProfileView(
        name = name,
        title = loadTitleNameForProfile(uid, authed == false),
        level = levelText,
        levelOrder = trustOrder(levelText),
        seniorityLevel = seniorityLevelTextForProfile(uid, authed, levelText),
        playHours = with(seniorityLevel) { formatPlayHours(MdtStorage.getPlayMillis(uid)) },
        liked = reputation.receivedLikes,
        disliked = reputation.receivedDislikes,
        givenLikes = reputation.givenLikes,
        givenDislikes = reputation.givenDislikes,
        effectiveDisliked = effectiveDisliked,
        receivedRecognitions = recognition.received,
        givenRecognitions = recognition.given,
        points = points.current,
        totalPoints = points.total,
    )
}

private fun loadPlayerProfileView(snapshot: PlayerSnapshot): PlayerProfileView =
    loadPlayerProfileViewFromStorage(snapshot.uid, snapshot.name, snapshot.authed, snapshot.admin)

private suspend fun refreshProfileCache(snapshot: PlayerSnapshot): PlayerProfileView {
    val now = System.currentTimeMillis()
    val loaded = withContext(Dispatchers.IO) { loadPlayerProfileView(snapshot) }
    profileCache[snapshot.uid] = CachedProfileView(loaded, now, now)
    return loaded
}

private fun refreshProfileCacheAsync(snapshot: PlayerSnapshot) {
    launch(Dispatchers.IO) {
        val loaded = loadPlayerProfileView(snapshot)
        val now = System.currentTimeMillis()
        withContext(Dispatchers.game) {
            val online = onlinePlayerByUid(snapshot.uid)
            if (online != null && playerUid(online) == snapshot.uid) {
                profileCache[snapshot.uid] = CachedProfileView(loaded, now, now)
            }
        }
    }
}

private fun invalidateProfileCache(uid: String) {
    profileCache.remove(uid)
}

private fun invalidateProfileCache(uids: Iterable<String>) {
    uids.forEach(::invalidateProfileCache)
}

private suspend fun playerProfileView(player: Player): PlayerProfileView {
    val snapshot = snapshotPlayer(player)
    val uid = snapshot.uid
    val now = System.currentTimeMillis()
    profileCache[uid]?.takeIf { now - it.loadedAt <= PROFILE_CACHE_TTL_MILLIS }?.let {
        it.lastAccessAt = now
        // 名字是在线态字段，允许使用最新名字；统计字段仍走缓存。
        return it.value.copy(name = player.name)
    }
    return refreshProfileCache(snapshot)
}

private fun playerUid(player: Player): String = with(playerReputation) { playerReputationUid(player) }

private fun trustOrder(levelCode: String): Int = with(trustLevel) { trustLevelOrder(levelCode) }

private fun encodeRecentField(text: String): String =
    Base64.getUrlEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

private fun decodeRecentField(text: String): String =
    String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)

private fun loadRecentPlayers(): MutableList<RecentPlayerRecord> =
    MdtStorage.getSetting(RECENT_PLAYERS_KEY)
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            runCatching {
                RecentPlayerRecord(
                    uid = decodeRecentField(parts[0]),
                    uuid = decodeRecentField(parts[1]),
                    shortId = decodeRecentField(parts[2]),
                    name = decodeRecentField(parts[3]),
                    ip = decodeRecentField(parts[4]),
                    lastSeen = parts[5].toLongOrNull() ?: 0L,
                )
            }.getOrNull()
        }
        .sortedByDescending { it.lastSeen }
        .take(80)
        .toMutableList()

private fun recentPlayersSnapshot(): List<RecentPlayerRecord>? =
    synchronized(recentPlayersLock) { recentPlayersCache?.toList() }

private suspend fun loadRecentPlayersCached(): List<RecentPlayerRecord> {
    recentPlayersSnapshot()?.let { return it }
    val loaded = withContext(Dispatchers.IO) { loadRecentPlayers() }
    synchronized(recentPlayersLock) {
        if (recentPlayersCache == null) recentPlayersCache = loaded.toMutableList()
        return recentPlayersCache!!.toList()
    }
}

private fun saveRecentPlayers(records: List<RecentPlayerRecord>) {
    val text = records
        .sortedByDescending { it.lastSeen }
        .take(80)
        .joinToString("\n") { record ->
            listOf(
                encodeRecentField(record.uid),
                encodeRecentField(record.uuid),
                encodeRecentField(record.shortId),
                encodeRecentField(record.name),
                encodeRecentField(record.ip),
                record.lastSeen.toString(),
            ).joinToString("\t")
        }
    MdtStorage.setSetting(RECENT_PLAYERS_KEY, text.takeIf { it.isNotBlank() })
}

private fun scheduleRecentPlayersSave() {
    synchronized(recentPlayersLock) {
        recentSaveJob?.cancel()
        recentSaveJob = launch(Dispatchers.IO) {
            delay(2_000L)
            val snapshot = synchronized(recentPlayersLock) { recentPlayersCache?.toList().orEmpty() }
            saveRecentPlayers(snapshot)
        }
    }
}

private fun recordRecentPlayerAsync(snapshot: PlayerSnapshot) {
    val record = RecentPlayerRecord(
        uid = snapshot.uid,
        uuid = snapshot.uuid,
        shortId = snapshot.shortId,
        name = snapshot.plainName.ifBlank { snapshot.name },
        ip = snapshot.ip,
        lastSeen = System.currentTimeMillis(),
    )
    launch(Dispatchers.IO) {
        val records = synchronized(recentPlayersLock) {
            recentPlayersCache?.toMutableList()
        } ?: run {
            val loaded = loadRecentPlayers()
            synchronized(recentPlayersLock) {
                if (recentPlayersCache == null) recentPlayersCache = loaded.toMutableList()
                recentPlayersCache!!.toMutableList()
            }
        }
        val updated = records
            .filterNot { it.uid == record.uid || it.uuid == record.uuid }
            .toMutableList()
        updated.add(0, record)
        val trimmed = updated.sortedByDescending { it.lastSeen }.take(80).toMutableList()
        synchronized(recentPlayersLock) {
            recentPlayersCache = trimmed
        }
        scheduleRecentPlayersSave()
    }
}

private fun RecentPlayerRecord.toPlayerData(): PlayerData {
    val ids = mutableSetOf(uuid)
    if (uid.isNotBlank()) ids += uid
    return PlayerData(name, uuid, ids).also {
        if (uid.isNotBlank() && uid != uuid) it.addId(uid, asPrimary = true)
    }
}

private fun RecentPlayerRecord.onlinePlayer(): Player? =
    Groups.player.find { it.uuid() == uuid || PlayerData[it].id == uid }

private fun formatAgo(millis: Long): String {
    val diff = (System.currentTimeMillis() - millis).coerceAtLeast(0L) / 1000
    val minutes = diff / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}天前"
        hours > 0 -> "${hours}小时前"
        minutes > 0 -> "${minutes}分钟前"
        else -> "刚刚"
    }
}

private fun resolveOnlineTarget(text: String): Player? {
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

private suspend fun chooseOnlineTarget(viewer: Player): Player? {
    var result: Player? = null
    PagedMenuBuilder(Groups.player.toList().sortedBy { it.plainName() }) {
        option(it.name) { result = it }
    }.apply {
        title = "选择玩家"
        msg = "选择要打开信息面板的玩家"
        sendTo(viewer, 60_000)
    }
    return result
}

private suspend fun askReason(viewer: Player, title: String, targetName: String): String? {
    return with(textInput) {
        textInput(
            viewer,
            title,
            "目标：$targetName\n请输入理由，留空或取消则不执行。",
            lengthLimit = 80,
            timeoutMillis = 60_000
        )
    }?.trim()?.takeIf { it.isNotEmpty() } ?: run {
        viewer.sendMessage("[yellow]已取消操作")
        null
    }
}

private suspend fun askReason(viewer: Player, title: String, target: Player): String? =
    askReason(viewer, title, target.plainName())

private suspend fun askBanMinutes(viewer: Player, title: String, targetName: String, maxMinutes: Int? = null): Int? {
    val maxTip = maxMinutes?.let { "\n你的协管单次封禁上限为 $it 分钟。" }.orEmpty()
    val text = with(textInput) {
        textInput(
            viewer,
            title,
            "目标：$targetName\n请输入封禁时长（分钟），例如 60、1440。$maxTip\n留空或取消则不执行。",
            lengthLimit = 8,
            isNumeric = true,
            timeoutMillis = 60_000,
        )
    }?.trim()
    val minutes = text?.toIntOrNull()?.takeIf { it > 0 }
    if (minutes == null) {
        viewer.sendMessage("[yellow]已取消操作")
        return null
    }
    if (maxMinutes != null && minutes > maxMinutes) {
        viewer.sendMessage("[red]封禁时长超过协管上限：最多 [white]$maxMinutes[red] 分钟。")
        return null
    }
    return minutes
}

private suspend fun askModerationDuration(viewer: Player, title: String, targetName: String): ModerationDuration? {
    val text = with(textInput) {
        textInput(
            viewer,
            title,
            "目标：$targetName\n请输入限制时长（分钟），例如 10、60、1440。\n留空=永久；取消=取消操作。",
            lengthLimit = 8,
            isNumeric = false,
            timeoutMillis = 60_000,
        )
    } ?: run {
        viewer.sendMessage("[yellow]已取消操作")
        return null
    }
    val fixed = text.trim()
    if (fixed.isBlank()) return ModerationDuration(null)
    val minutes = fixed.toIntOrNull()?.takeIf { it > 0 }
    if (minutes == null) {
        viewer.sendMessage("[red]时长格式不正确，请输入大于0的分钟数，或留空表示永久。")
        return null
    }
    return ModerationDuration(minutes)
}

private suspend fun quickCommand(viewer: Player, command: String) {
    RootCommands.handleInput(command, viewer, "/")
}

private fun canStaffBanTarget(viewer: Player, data: PlayerData): Boolean =
    with(trustLevel) { canModerateTrustTarget(viewer, data.id, data.player) }

private fun staffBanMaxMinutes(viewer: Player): Int? = with(trustLevel) {
    pluginAdminMaxBanMinutes().takeIf { isPluginAdmin(viewer) }
}

private suspend fun banAccountFlow(viewer: Player, targetName: String, data: PlayerData) {
    if (!canStaffBanTarget(viewer, data)) {
        viewer.sendMessage("[red]你不能封禁同级或更高等级的玩家。")
        return
    }
    val minutes = askBanMinutes(viewer, "封禁账号时长", targetName, staffBanMaxMinutes(viewer)) ?: return
    val reason = askReason(viewer, "封禁账号理由", targetName) ?: return
    if (!canStaffBanTarget(viewer, data)) {
        viewer.sendMessage("[red]目标等级已变化，本次封禁已取消。")
        return
    }
    with(banImpl) { ban(data, minutes, reason, viewer) }
    viewer.sendMessage("[green]已封禁 [white]$targetName[green] 的账号/主体 [yellow]${minutes}分钟[green]。")
}

private suspend fun banIpFlow(viewer: Player, targetName: String, ip: String, data: PlayerData, targetUuid: String? = null) {
    if (!canStaffBanTarget(viewer, data)) {
        viewer.sendMessage("[red]你不能封禁同级或更高等级玩家的IP。")
        return
    }
    val minutes = askBanMinutes(viewer, "封禁IP时长", targetName, staffBanMaxMinutes(viewer)) ?: return
    val reason = askReason(viewer, "封禁IP理由", targetName) ?: return
    if (!canStaffBanTarget(viewer, data)) {
        viewer.sendMessage("[red]目标等级已变化，本次IP封禁已取消。")
        return
    }
    val bannedIp = with(securityGuard) { manualBanIp(ip, minutes.toLong(), reason, viewer, targetUuid, targetName) }
    if (bannedIp == null) {
        viewer.sendMessage("[red]无法封禁目标IP（可能是本地/内网IP，或安全风控已关闭）。")
        return
    }
    viewer.sendMessage("[green]已封禁 [white]$targetName[green] 的IP：[white]$bannedIp [yellow]${minutes}分钟[green]。")
}

private suspend fun showPlayerInfo(viewer: Player, target: Player) {
    if (!allowOpenInfoPanel(viewer)) return
    val targetUid = playerUid(target)
    val viewerUid = playerUid(viewer)
    val isSelf = viewer === target || viewerUid == targetUid
    val profile = playerProfileView(target)
    val viewerLevelCode = with(trustLevel) { getTrustLevelCode(viewerUid, viewer) }
    val viewerOrder = trustOrder(viewerLevelCode)
    val order1 = trustOrder("1")
    val order2 = trustOrder("2")
    val order3 = trustOrder("3")
    val order3PlusPlus = trustOrder("3++")
    val order4 = trustOrder("4")
    val canVotePunishZero = !isSelf && profile.level == "0" && viewerOrder >= order1
    val canDirectRestrictTarget = !isSelf && with(trustLevel) { canDirectRestrictTrustTarget(viewer, target) }
    val canDirectForceOb = !isSelf && (
            (!PlayerData[target].authed && viewerOrder >= order3) ||
                    canDirectRestrictTarget
            )
    val canModerateTarget = !isSelf && with(trustLevel) { canModerateTrustTarget(viewer, target) }
    val canAdminBan = !isSelf && viewerOrder >= order3PlusPlus && canModerateTarget
    val targetForceOb = with(voteOb) { isForceOb(target) }
    val canReleaseForceOb = !isSelf && targetForceOb && (
            viewerOrder >= order4 ||
                    (viewerOrder >= order3PlusPlus && canModerateTarget) ||
                    with(voteOb) { isForceObOwnedBy(target, viewerUid) }
            )
    // 禁言/强制观战属于即时状态，不放入资料缓存，避免管理按钮显示滞后。
    val targetMuted = canDirectRestrictTarget && with(playerMute) { isMuted(target) }
    val canBuildBanTarget = !isSelf && with(playerBuildBan) { canManageBuildBan(viewer, target) }
    val targetBuildBanned = canBuildBanTarget && with(playerBuildBan) { isBuildBanned(target) }

    MenuBuilder<Unit>("玩家信息") {
        msg = """
            |[cyan]名字：[white]${profile.name}
            |[cyan]称号：[white]${profile.title}
            |[cyan]信任等级：[white]${profile.level}
            |[cyan]资历等级：[white]${profile.seniorityLevel}
            |[cyan]累计在线：[white]${profile.playHours}
            |[cyan]被赞数：[white]${profile.liked}
            |[cyan]被踩数：[white]${profile.disliked}
            |[cyan]给别人的赞：[white]${profile.givenLikes}
            |[cyan]踩别人的数：[white]${profile.givenDislikes}
            |[cyan]有效被踩：[white]${profile.effectiveDisliked}
            |[cyan]被认可：[white]${profile.receivedRecognitions}
            |[cyan]认可他人：[white]${profile.givenRecognitions}
            |[cyan]当前MDC：[white]${profile.points}
            |[cyan]累计MDC：[white]${profile.totalPoints}
        """.trimMargin()

        if (isSelf) {
            option("打开称号面板") { quickCommand(viewer, "/title") }
            option("打开技能面板") { quickCommand(viewer, "/skills") }
            newRow()
            option("打开商店列表") { quickCommand(viewer, "/shop") }
            option("打开成就系统") { quickCommand(viewer, "/achievements") }
            newRow()
            option("随机变换形态") { with(playerRandomForm) { toggleRandomForm(viewer) } }
            option("打开投票页面") { quickCommand(viewer, "/vote") }
            newRow()
            option("打开/help页面") { quickCommand(viewer, "/help") }
        } else {
            option("赞ta") { with(playerReputation) { likePlayer(viewer, targetUid, profile.name) } }
            option("踩ta") { with(playerReputation) { dislikePlayer(viewer, targetUid, profile.name) } }
            if (viewerOrder >= order2) {
                option("认可ta") { with(playerRecognition) { recognizePlayer(viewer, targetUid, profile.name) } }
            }
            if (canVotePunishZero) {
                newRow()
                option("投票踢出此玩家") {
                    val reason = askReason(viewer, "投票踢出理由", target) ?: return@option
                    with(voteKick) { startKickVote(viewer, target, reason) }
                }
                option("投票强制观战此玩家") {
                    val reason = askReason(viewer, "投票强制观战理由", target) ?: return@option
                    with(voteOb) { startObVote(viewer, target, reason) }
                }
            }
            if ((!targetForceOb && canDirectForceOb) || canReleaseForceOb) {
                newRow()
                if (targetForceOb) {
                    option("解除ta强制观战") {
                        val currentViewerUid = playerUid(viewer)
                        val currentViewerOrder = with(trustLevel) { getTrustLevelOrder(currentViewerUid, viewer) }
                        val released = with(voteOb) {
                            if (currentViewerOrder >= order4 ||
                                (currentViewerOrder >= order3PlusPlus && with(trustLevel) { canModerateTrustTarget(viewer, target) })
                            ) releaseForceObPlayer(target)
                            else releaseOwnForceObPlayer(target, currentViewerUid)
                        }
                        if (released)
                            viewer.sendMessage("[green]已解除 [white]${target.name}[green] 的强制观战")
                        else
                            viewer.sendMessage("[yellow]目标当前不在你可解除的强制观战状态")
                    }
                } else {
                    option("强制观战此玩家") {
                        var currentViewerUid = playerUid(viewer)
                        var currentViewerOrder = with(trustLevel) { getTrustLevelOrder(currentViewerUid, viewer) }
                        var currentAllowed = viewer !== target && currentViewerUid != playerUid(target) && (
                                (!PlayerData[target].authed && currentViewerOrder >= order3) ||
                                        with(trustLevel) { canDirectRestrictTrustTarget(viewer, target) }
                                )
                        if (!currentAllowed) {
                            viewer.sendMessage("[yellow]操作者权限或目标状态已变化，无法直接强制观战。")
                            return@option
                        }
                        if (currentViewerOrder < order4) {
                            val left = with(voteOb) { directForceObCooldownLeft(currentViewerUid) }
                            if (left > 0L) {
                                viewer.sendMessage("[yellow]直接强制观战冷却中，还需 [white]${(left + 999L) / 1000L}[yellow] 秒。")
                                return@option
                            }
                        }
                        val reason = askReason(viewer, "强制观战理由", target) ?: return@option

                        // 理由输入期间玩家可能完成登录、等级变化或已被其他人处理，执行前必须再次校验。
                        currentViewerUid = playerUid(viewer)
                        currentViewerOrder = with(trustLevel) { getTrustLevelOrder(currentViewerUid, viewer) }
                        currentAllowed = viewer !== target && currentViewerUid != playerUid(target) && (
                                (!PlayerData[target].authed && currentViewerOrder >= order3) ||
                                        with(trustLevel) { canDirectRestrictTrustTarget(viewer, target) }
                                )
                        if (!currentAllowed) {
                            viewer.sendMessage("[yellow]操作者权限或目标状态已变化，本次强制观战已取消。")
                            return@option
                        }
                        if (with(voteOb) { isForceOb(target) }) {
                            viewer.sendMessage("[yellow]目标已处于强制观战状态。")
                            return@option
                        }
                        if (currentViewerOrder < order4) {
                            val left = with(voteOb) { directForceObCooldownLeft(currentViewerUid) }
                            if (left > 0L) {
                                viewer.sendMessage("[yellow]直接强制观战冷却中，还需 [white]${(left + 999L) / 1000L}[yellow] 秒。")
                                return@option
                            }
                        }

                        val directOwnerUid = currentViewerUid.takeIf { currentViewerOrder < order4 }
                        with(voteOb) { forceObPlayer(target, reason, viewer, directOwnerUid) }
                        if (directOwnerUid != null) with(voteOb) { markDirectForceObCooldown(directOwnerUid) }
                    }
                }
            }
            if (canDirectRestrictTarget) {
                newRow()
                if (targetMuted) {
                    option("解除ta禁言") {
                        if (!with(playerMute) { canManagePlayerMute(viewer, target) }) {
                            viewer.sendMessage("[yellow]操作者权限或目标等级已变化，无法解除禁言。")
                            return@option
                        }
                        if (!with(playerMute) { unmutePlayer(target, viewer) })
                            viewer.sendMessage("[yellow]目标当前未被禁言")
                    }
                } else {
                    option("禁言此玩家") {
                        val duration = askModerationDuration(viewer, "禁言时长", target.plainName()) ?: return@option
                        val reason = askReason(viewer, "禁言理由", target) ?: return@option
                        val success = if (duration.minutes == null) {
                            with(playerMute) { mutePlayer(target, reason, viewer) }
                        } else {
                            with(playerMute) { mutePlayerTemporary(target, duration.minutes, reason, viewer) }
                        }
                        if (!success) viewer.sendMessage("[yellow]操作者权限或目标等级已变化，本次禁言已取消。")
                    }
                }
            }
            if (canBuildBanTarget) {
                newRow()
                if (targetBuildBanned) {
                    option("解除ta禁建") {
                        if (!with(playerBuildBan) { canManageBuildBan(viewer, target) }) {
                            viewer.sendMessage("[yellow]操作者权限或目标等级已变化，无法解除禁建。")
                            return@option
                        }
                        if (!with(playerBuildBan) { enableBuild(target, viewer) })
                            viewer.sendMessage("[yellow]目标当前未被禁止建造/拆除")
                    }
                } else {
                    option("禁止ta建造") {
                        val duration = askModerationDuration(viewer, "禁止建造时长", target.plainName()) ?: return@option
                        val reason = askReason(viewer, "禁止建造理由", target) ?: return@option
                        val success = if (duration.minutes == null) {
                            with(playerBuildBan) { disableBuild(target, reason, viewer) }
                        } else {
                            with(playerBuildBan) { disableBuildTemporary(target, duration.minutes, reason, viewer) }
                        }
                        if (!success) viewer.sendMessage("[yellow]操作者权限或目标等级已变化，本次禁建已取消。")
                    }
                }
            }
            if (canAdminBan) {
                newRow()
                option("ban掉ta") {
                    banAccountFlow(viewer, target.plainName(), PlayerData[target])
                }
                option("ban掉ta的ip") {
                    banIpFlow(viewer, target.plainName(), with(securityGuard) { playerIpForAdmin(target) }, PlayerData[target], target.uuid())
                }
            }
        }
        newRow()
        option("返回") { }
    }.sendTo(viewer, 60_000)
}

private fun canUseRecentPlayerPanel(viewer: Player): Boolean {
    val uid = playerUid(viewer)
    return trustOrder(with(trustLevel) { getTrustLevelCode(uid, viewer) }) >= trustOrder("3++")
}

private suspend fun showRecentPlayerPanel(viewer: Player, record: RecentPlayerRecord) {
    record.onlinePlayer()?.let {
        showPlayerInfo(viewer, it)
        return
    }

    val profile = withContext(Dispatchers.IO) {
        loadPlayerProfileViewFromStorage(record.uid, record.name, authed = null, admin = false)
    }
    MenuBuilder<Unit>("最近玩家信息") {
        msg = """
            |[cyan]名字：[white]${profile.name}
            |[cyan]短ID：[white]${record.shortId}
            |[cyan]UID：[white]${record.uid}
            |[cyan]UUID：[white]${record.uuid}
            |[cyan]IP：[white]${record.ip}
            |[cyan]最后在线：[white]${formatAgo(record.lastSeen)}
            |[cyan]称号：[white]${profile.title}
            |[cyan]信任等级：[white]${profile.level}
            |[cyan]资历等级：[white]${profile.seniorityLevel}
            |[cyan]累计在线：[white]${profile.playHours}
            |[cyan]被赞/踩：[white]${profile.liked}/${profile.disliked}
            |[cyan]有效被踩：[white]${profile.effectiveDisliked}
            |[cyan]被认可：[white]${profile.receivedRecognitions}
            |[cyan]当前/累计MDC：[white]${profile.points}/${profile.totalPoints}
        """.trimMargin()
        val targetData = record.toPlayerData()
        if (canStaffBanTarget(viewer, targetData)) {
            option("ban掉ta") {
                banAccountFlow(viewer, record.name, targetData)
            }
            option("ban掉ta的ip") {
                banIpFlow(viewer, record.name, record.ip, targetData, record.uuid)
            }
        }
        newRow()
        option("返回最近玩家") { openRecentPlayersMenu(viewer) }
        option("关闭") {}
    }.sendTo(viewer, 60_000)
}

private suspend fun openRecentPlayersMenu(viewer: Player) {
    if (!canUseRecentPlayerPanel(viewer)) {
        viewer.sendMessage("[red]权限不足：只有3++协管、4级/admin可以查看最近玩家管理面板。")
        return
    }
    val records = loadRecentPlayersCached()
    if (records.isEmpty()) {
        viewer.sendMessage("[yellow]暂无最近玩家记录。")
        return
    }
    PagedMenuBuilder(records) { record ->
        val online = record.onlinePlayer() != null
        val line = "[white]${record.name} [gray](${record.shortId})\n" +
                "[gray]${record.ip} | ${formatAgo(record.lastSeen)} | ${if (online) "[green]在线" else "[light_gray]离线"}"
        option(line) { showRecentPlayerPanel(viewer, record) }
    }.apply {
        title = "最近玩家(80)"
        msg = "点击可打开玩家面板；离线玩家也可继续封禁账号或IP。"
        sendTo(viewer, 60_000)
    }
}

listen<EventType.PlayerLeave> {
    val snapshot = snapshotPlayer(it.player)
    recordRecentPlayerAsync(snapshot)
    tapStates.remove(it.player)
    profileCache.remove(snapshot.uid)
    lastInfoOpenAt.remove(snapshot.uuid)
}

listen<EventType.PlayerJoin> {
    val snapshot = snapshotPlayer(it.player)
    recordRecentPlayerAsync(snapshot)
    refreshProfileCacheAsync(snapshot)
}

listen<EventType.ResetEvent> {
    tapStates.clear()
    lastInfoOpenAt.clear()
}

onDisable {
    tapStates.clear()
    profileCache.clear()
    lastInfoOpenAt.clear()
    recentSaveJob?.cancel()
    recentSaveJob = null
}

listenTo<ReputationChangedEvent> { invalidateProfileCache(uids) }
listenTo<RecognitionChangedEvent> { invalidateProfileCache(uids) }
listenTo<TrustPointChangedEvent> { invalidateProfileCache(uids) }
listenTo<TrustLevelChangedEvent> { invalidateProfileCache(uid) }
listenTo<TrustLevelLockChangedEvent> { invalidateProfileCache(uids) }
listenTo<SeniorityLevelChangedEvent> { invalidateProfileCache(uid) }
listenTo<SeniorityLevelLockChangedEvent> { invalidateProfileCache(uids) }
listenTo<PlayerTitleChangedEvent> { invalidateProfileCache(uids) }

onEnable {
    launch(Dispatchers.IO) {
        val loaded = loadRecentPlayers()
        synchronized(recentPlayersLock) {
            val current = recentPlayersCache.orEmpty()
            recentPlayersCache = (current + loaded)
                .distinctBy { it.uid.ifBlank { it.uuid } }
                .sortedByDescending { it.lastSeen }
                .take(80)
                .toMutableList()
        }
    }
    Groups.player.forEach { refreshProfileCacheAsync(snapshotPlayer(it)) }
}

listen<EventType.TapEvent> { event ->
    val player = event.player ?: return@listen
    val tile = event.tile ?: return@listen
    val x = tile.x.toInt()
    val y = tile.y.toInt()
    val now = System.currentTimeMillis()

    val state = tapStates[player]
    if (state != null && state.x == x && state.y == y && now - state.lastTapAt <= TAP_CHAIN_TIMEOUT_MILLIS) {
        state.count += 1
        state.lastTapAt = now
    } else {
        tapStates[player] = TapState(x, y, 1, now)
        return@listen
    }

    if (state.count < 2) return@listen
    tapStates.remove(player)

    val target = nearestPlayerIn3x3(x, y) ?: return@listen
    launch(Dispatchers.game) {
        showPlayerInfo(player, target)
    }
}

command("playerinfo", "打开玩家信息/交互面板") {
    usage = "[玩家id/3位id/#游戏id]"
    aliases = listOf("pinfo", "玩家信息")
    attr(ClientOnly)
    body {
        val viewer = player!!
        val target = if (arg.isEmpty()) {
            chooseOnlineTarget(viewer) ?: returnReply("[yellow]已取消选择".with())
        } else {
            resolveOnlineTarget(arg.joinToString("")) ?: returnReply("[red]未找到在线玩家".with())
        }
        showPlayerInfo(viewer, target)
    }
}

command("recentplayers", "管理指令：最近游玩玩家面板") {
    aliases = listOf("recent", "最近玩家", "最近游玩")
    attr(ClientOnly)
    requirePermission("wayzer.admin.recentPlayers")
    body {
        openRecentPlayersMenu(player!!)
    }
}

PermissionApi.registerDefault("wayzer.admin.recentPlayers", group = "@admin")
