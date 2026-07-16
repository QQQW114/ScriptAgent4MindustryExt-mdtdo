@file:Depends("wayzer/cmds/voteOb", "强制观战状态")
@file:Depends("wayzer/map/betterTeam", "观察者队伍")
@file:Depends("wayzer/user/trustLevel", "信任等级权限")

package wayzer.security

import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.cmds.VoteOb
import wayzer.lib.PlayerData
import wayzer.map.BetterTeam
import wayzer.user.TrustLevel
import java.time.Instant

name = "强制观战玩家自动清理"

private val voteOb = contextScript<VoteOb>()
private val teams = contextScript<BetterTeam>()
private val trustLevel = contextScript<TrustLevel>()

private val enabledDefault by config.key(true, "是否默认启用高人数强制观战清理")
private val playerThreshold by config.key(14, "在线人数达到/超过该值时触发清理")
private val targetPlayerCount by config.key(12, "清理到该在线人数附近")
private val maxCleanPerRun by config.key(2, "每轮最多清理多少名强制观战玩家")
private val sameIpCleanupEnabled by config.key(true, "在线人数超过阈值时是否同时清理同IP多账号玩家")
private val sameIpProtectAdmins by config.key(true, "清理同IP多账号时是否保护信任4级/admin玩家")
private val checkIntervalMillis by config.key(60_000L, "检查间隔(ms)")
private val warnDelayMillis by config.key(30_000L, "清理前提醒管理等待时间(ms)")

private var cleanerEnabled = enabledDefault
private var pendingCleanAt = 0L

private data class Candidate(
    val player: Player,
    val reason: String,
    val since: Instant,
)

private data class OnlineIdentity(
    val player: Player,
    val ip: String,
    val uid: String,
    val trustOrder: Int,
    val admin: Boolean,
    val spectating: Boolean,
)

private data class SameIpCandidate(
    val player: Player,
    val ip: String,
    val keptName: String,
)

private val ipv4Regex = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

private fun normalizeIp(raw: String?): String =
    raw?.let { ipv4Regex.find(it)?.value ?: it.trim().substringBefore('%') }
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"

private fun playerIp(player: Player): String =
    normalizeIp(player.con?.address ?: player.ip())

private fun onlineForceObCandidates(): List<Candidate> =
    Groups.player.toList()
        .mapNotNull { player ->
            // 边界：只清理由 /vote ob 或 /forceOB 记录的“已登录/绑定玩家”的强制观战；
            // 不清理未绑定游客、普通玩家、主动观战者、今日游客观战/IP风险/安全风控造成的观战。
            if (!PlayerData[player].authed) return@mapNotNull null
            if (player.team() != teams.spectateTeam) return@mapNotNull null
            val (reason, since) = with(voteOb) { forceObInfo(player) } ?: return@mapNotNull null
            Candidate(player, reason, since)
        }
        .sortedBy { it.since }

private fun onlineSameIpDuplicateCandidates(excludeUuids: Set<String> = emptySet()): List<SameIpCandidate> {
    if (!sameIpCleanupEnabled) return emptyList()
    val identities = Groups.player.toList().mapNotNull { player ->
        if (player.uuid() in excludeUuids) return@mapNotNull null
        val data = PlayerData[player]
        if (!data.authed) return@mapNotNull null
        val ip = playerIp(player)
        if (ip == "unknown") return@mapNotNull null
        OnlineIdentity(
            player = player,
            ip = ip,
            uid = data.id,
            trustOrder = with(trustLevel) { getTrustLevelOrder(player) },
            admin = with(trustLevel) { isTrustAdmin(player) },
            spectating = player.team() == teams.spectateTeam,
        )
    }

    return identities
        .groupBy { it.ip }
        .values
        .flatMap { group ->
            // 只处理“同IP多个账号/主体”的情况；单账号重复连接不在本脚本扩张处理范围内。
            if (group.size <= 1 || group.map { it.uid }.distinct().size <= 1) return@flatMap emptyList<SameIpCandidate>()
            val kept = group.maxWithOrNull(
                compareBy<OnlineIdentity> { if (it.admin) 1 else 0 }
                    .thenBy { it.trustOrder }
                    .thenBy { if (it.spectating) 0 else 1 }
                    .thenBy { it.player.plainName() }
            ) ?: return@flatMap emptyList<SameIpCandidate>()
            val keptUuid = kept.player.uuid()
            group
                .filter { it.player.uuid() != keptUuid }
                .filter { !sameIpProtectAdmins || !it.admin }
                .map { SameIpCandidate(it.player, it.ip, kept.player.plainName()) }
        }
}

private fun desiredCleanCount(totalPlayers: Int, candidates: Int, requestedLimit: Int? = null): Int {
    if (candidates <= 0) return 0
    val overflow = (totalPlayers - targetPlayerCount.coerceAtLeast(1)).coerceAtLeast(0)
    val base = requestedLimit ?: overflow
    return base.coerceAtLeast(0).coerceAtMost(candidates).coerceAtMost(maxCleanPerRun.coerceAtLeast(1))
}

private fun notifyAdmins(message: String) {
    Groups.player.forEach { player ->
        if (canManageCleaner(player)) player.sendMessage(message)
    }
    logger.info(message.replace(Regex("\\[[^\\]]*]"), ""))
}

private fun canManageCleaner(player: Player): Boolean =
    with(trustLevel) { isTrustAdmin(player) }

private fun cleanupNow(limit: Int? = null, manual: Boolean = false): Int {
    val candidates = onlineForceObCandidates()
    val total = Groups.player.size()
    val count = if (manual) {
        (limit ?: maxCleanPerRun).coerceAtLeast(1).coerceAtMost(candidates.size)
    } else {
        desiredCleanCount(total, candidates.size, limit)
    }
    val selected = candidates.take(count)
    val selectedUuids = selected.map { it.player.uuid() }.toSet()
    selected.forEach { candidate ->
        candidate.player.kick(
            "[yellow]服务器当前人数较多，你因处于强制观战状态被暂时移出以释放位置。\n[gray]强制观战原因：${candidate.reason}",
            0
        )
    }
    if (selected.isNotEmpty()) {
        notifyAdmins(
            "[yellow][强制观战清理] 已清理 [white]${selected.size}[yellow] 名强制观战玩家：${
                selected.joinToString("、") { it.player.plainName() }
            }"
        )
    }

    val sameIpCandidates = onlineSameIpDuplicateCandidates(selectedUuids)
    sameIpCandidates.forEach { candidate ->
        candidate.player.kick(
            "[yellow]服务器当前人数较多，同IP多账号在线时仅保留一个账号。\n[gray]本次保留：${candidate.keptName}，IP：${candidate.ip}",
            0
        )
    }
    if (sameIpCandidates.isNotEmpty()) {
        notifyAdmins(
            "[yellow][强制观战清理] 已清理 [white]${sameIpCandidates.size}[yellow] 名同IP多账号玩家：${
                sameIpCandidates.joinToString("、") { "${it.player.plainName()}(${it.ip})" }
            }"
        )
    }

    return selected.size + sameIpCandidates.size
}

private fun statusText(): String {
    val total = Groups.player.size()
    val candidates = onlineForceObCandidates()
    val sameIpCandidates = onlineSameIpDuplicateCandidates()
    val next = if (pendingCleanAt > 0L) {
        "${((pendingCleanAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1000)}秒后"
    } else {
        "-"
    }
    return """
        |[cyan]强制观战自动清理
        |[white]状态：[yellow]${if (cleanerEnabled) "启用" else "关闭"}
        |[white]在线人数：[yellow]$total[] / 触发阈值：[yellow]$playerThreshold[] / 目标人数：[yellow]$targetPlayerCount
        |[white]候选强制观战玩家：[yellow]${candidates.size}[] / 每轮最多：[yellow]$maxCleanPerRun
        |[white]同IP多账号清理：[yellow]${if (sameIpCleanupEnabled) "启用" else "关闭"}[] / 候选：[yellow]${sameIpCandidates.size}
        |[white]待执行：[yellow]$next
        |[gray]边界：强制观战清理只踢出已登录且处于 /vote ob 或 /forceOB 记录中的玩家；同IP清理只在超过阈值时处理已登录的同IP多账号，默认保护信任4级/admin。
    """.trimMargin()
}

private suspend fun tickAutoCleaner() {
    if (!cleanerEnabled) {
        pendingCleanAt = 0L
        return
    }
    val total = Groups.player.size()
    if (total < playerThreshold) {
        pendingCleanAt = 0L
        return
    }
    val candidates = onlineForceObCandidates()
    val cleanCount = desiredCleanCount(total, candidates.size)
    val sameIpCount = onlineSameIpDuplicateCandidates().size
    if (cleanCount <= 0 && sameIpCount <= 0) {
        pendingCleanAt = 0L
        return
    }

    val now = System.currentTimeMillis()
    if (pendingCleanAt <= 0L) {
        pendingCleanAt = now + warnDelayMillis.coerceAtLeast(5_000L)
        val parts = mutableListOf<String>()
        if (cleanCount > 0) parts += "约 [white]$cleanCount[yellow] 名强制观战玩家"
        if (sameIpCount > 0) parts += "约 [white]$sameIpCount[yellow] 名同IP多账号玩家"
        notifyAdmins(
            "[yellow][强制观战清理] 当前在线 [white]$total[yellow] 人，达到/超过阈值 [white]$playerThreshold[yellow]；将在 [white]${warnDelayMillis / 1000}[yellow] 秒后清理${parts.joinToString("、")}。可用 [gold]/forceobclean off[] 关闭。"
        )
        return
    }
    if (now < pendingCleanAt) return

    pendingCleanAt = 0L
    cleanupNow(manual = false)
}

command("forceobclean", "管理指令：高人数时清理强制观战玩家") {
    usage = "[status|on|off|run [数量]]"
    permission = "wayzer.admin.forceObClean"
    aliases = listOf("强制观战清理", "obclean")
    body {
        when (arg.getOrNull(0)?.lowercase() ?: "status") {
            "status", "状态" -> reply(statusText().with())
            "on", "enable", "开启" -> {
                cleanerEnabled = true
                reply("[green]已启用强制观战自动清理。".with())
            }
            "off", "disable", "关闭" -> {
                cleanerEnabled = false
                pendingCleanAt = 0L
                reply("[yellow]已关闭强制观战自动清理。".with())
            }
            "run", "now", "执行", "清理" -> {
                val count = cleanupNow(arg.getOrNull(1)?.toIntOrNull(), manual = true)
                reply("[green]本次清理完成，清理人数：[yellow]$count".with())
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.forceObClean", group = "@admin")

onEnable {
    cleanerEnabled = enabledDefault
    launch(Dispatchers.game) {
        while (true) {
            delay(checkIntervalMillis.coerceAtLeast(5_000L))
            tickAutoCleaner()
        }
    }
}
