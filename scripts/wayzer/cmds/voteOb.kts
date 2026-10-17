@file:Depends("wayzer/cmds/voteKick", "功能控制，使用util，覆盖votekick")
@file:Depends("wayzer/map/betterTeam", "强制观察者")
@file:Depends("wayzer/user/trustLevel", "3++协管目标层级保护")
@file:Depends("wayzer/ext/playerMute", "惩罚规则/事件/共享状态")

package wayzer.cmds

import mindustry.gen.Groups
import wayzer.VoteEvent
import wayzer.ext.PlayerMute
import wayzer.ext.PlayerMute.PunishmentLiftedEvent
import wayzer.map.BetterTeam
import java.time.Duration
import java.time.Instant

val teams = contextScript<BetterTeam>()
val voteKick = contextScript<VoteKick>()
val trustLevel = contextScript<wayzer.user.TrustLevel>()

private val playerMute by lazy { contextScript<PlayerMute>() }

@Savable(false)
val limitPlayers = mutableMapOf<String, Pair<String, Instant>>()//profile -> reason,time
customLoad(::limitPlayers) { limitPlayers.putAll(it) }

// 额外记录强制观战来源与操作者主体 UID；旧版遗留记录没有元数据，按 legacy 处理。
@Savable(false)
val forceObSources = mutableMapOf<String, String>()//profile -> direct/pluginAdmin/admin/vote/system
customLoad(::forceObSources) { forceObSources.putAll(it) }
@Savable(false)
val forceObOperatorUids = mutableMapOf<String, String>()//profile -> operator uid
customLoad(::forceObOperatorUids) { forceObOperatorUids.putAll(it) }

private val directForceObCooldownMillis by config.key(120_000L, "非管理员面板直接强制观战成功后冷却(ms)")
@Savable(false)
val directForceObCooldownUntil = mutableMapOf<String, Long>()//operator uid -> until millis
customLoad(::directForceObCooldownUntil) { directForceObCooldownUntil.putAll(it) }

// 非管理员层级施加的强制观战没有“时长”参数，按用户口径 Q3/R8 统一补 24h 到期自动解除；
// 管理员层级（3++/4）与系统施加保持无期限。会话态即可（强制观战记录本身也不跨重启）。
@Savable(false)
val forceObExpireAt = mutableMapOf<String, Long>()//profile -> expire millis
customLoad(::forceObExpireAt) { forceObExpireAt.putAll(it) }

fun isForceOb(target: Player): Boolean =
    PlayerData[target].ids.any { it in limitPlayers }

fun forceObInfo(target: Player): Pair<String, Instant>? =
    PlayerData[target].ids
        .mapNotNull { limitPlayers[it] }
        .minByOrNull { it.second }

private fun applyForceObRecord(
    target: Player,
    reason: String,
    source: String,
    operatorUid: String? = null,
    untilMillis: Long? = null,
) {
    val now = Instant.now()
    PlayerData[target].ids.forEach {
        limitPlayers[it] = reason to now
        forceObSources[it] = source
        if (operatorUid == null) forceObOperatorUids.remove(it)
        else forceObOperatorUids[it] = operatorUid
        // untilMillis 非空 = 非管理员层级的限制，到点由定时清扫自动解除。
        if (untilMillis == null) forceObExpireAt.remove(it)
        else forceObExpireAt[it] = untilMillis
    }
}

fun forceObPlayer(
    target: Player,
    reason: String,
    operator: Player? = null,
    directOwnerUid: String? = null,
    sourceOverride: String? = null,
) {
    val source = sourceOverride ?: when {
        directOwnerUid != null -> "direct"
        operator != null -> "admin"
        else -> "system"
    }
    val operatorUid = directOwnerUid ?: operator?.let { PlayerData[it].id }
    val operatorLevel = operator?.let { with(trustLevel) { getTrustLevelOrder(it) } } ?: -1
    val operatorUids = operator?.let { PlayerData[it].ids } ?: emptySet()

    // 应用前检查（仅玩家直接操作时）：检举封禁 + (施罚者,目标) 24h 冷却（用户口径 Q2/Q5）。
    if (operator != null) {
        val decision = with(playerMute) {
            preparePunishment(operatorUids, operatorLevel, PlayerData[target].ids, null, false)
        }
        decision.blockReason?.let { reasonText ->
            operator.sendMessage(reasonText)
            return
        }
        decision.note?.let { operator.sendMessage(it) }
    }

    // 非管理员层级施加的强制观战没有时长参数 → 按“带时间惩罚”补 24h 到期（用户口径 Q3）。
    val cappedMinutes = with(playerMute) { nonAdminMaxPunishMinutesValue() }
    val nonAdminApplied = operator != null && !with(playerMute) { isAdminTierLevel(operatorLevel) }
    val untilMillis = if (nonAdminApplied) System.currentTimeMillis() + cappedMinutes * 60_000L else null

    applyForceObRecord(target, reason, source, operatorUid, untilMillis)
    teams.changeTeam(target, teams.spectateTeam)
    val operatorName = operator?.name ?: "系统"
    broadcast(
        "[red]{operator}[red] 强制 {target.name}[red] 成为观察者, 原因: [yellow]{reason}"
            .with("operator" to operatorName, "target" to target, "reason" to reason)
    )
    if (nonAdminApplied) {
        broadcast(
            "[yellow][提示] [white]{target.name}[yellow] 的强制观战将在 {minutes} 分钟后自动解除（非管理员层级最长 1 天）。".with(
                "target" to target, "minutes" to cappedMinutes
            )
        )
    }
    if (operator != null) {
        with(playerMute) {
            markPunishmentAppliedIfNeeded(operatorUids, operatorLevel, PlayerData[target].ids)
        }
    }
    with(playerMute) {
        emitPunishmentApplied(
            kind = "ob",
            target = target,
            durationMinutes = if (nonAdminApplied) cappedMinutes else null,
            operatorUid = operatorUid,
            operatorName = operator?.name,
            operatorLevelOrder = operatorLevel,
            operatorUids = operatorUids,
            source = source,
            reason = reason,
        )
    }
}

fun forceObSource(target: Player): String {
    val activeIds = PlayerData[target].ids.filter { it in limitPlayers }
    if (activeIds.isEmpty()) return "none"
    val sources = activeIds.map { forceObSources[it] ?: "legacy" }.toSet()
    return sources.singleOrNull() ?: "mixed"
}

fun forceObOperatorUid(target: Player): String? {
    val activeIds = PlayerData[target].ids.filter { it in limitPlayers }
    if (activeIds.isEmpty()) return null
    val operatorUids = activeIds.map { forceObOperatorUids[it] }.toSet()
    return operatorUids.singleOrNull()
}

fun isForceObOwnedBy(target: Player, operatorUid: String): Boolean =
    forceObSource(target) == "direct" && forceObOperatorUid(target) == operatorUid

fun directForceObCooldownLeft(operatorUid: String): Long {
    val now = System.currentTimeMillis()
    val until = directForceObCooldownUntil[operatorUid] ?: return 0L
    val left = until - now
    if (left <= 0L) directForceObCooldownUntil.remove(operatorUid)
    return left.coerceAtLeast(0L)
}

fun markDirectForceObCooldown(operatorUid: String) {
    val now = System.currentTimeMillis()
    directForceObCooldownUntil.entries.removeIf { it.value <= now }
    val cooldown = directForceObCooldownMillis.coerceAtLeast(0L)
    if (cooldown <= 0L) directForceObCooldownUntil.remove(operatorUid)
    else directForceObCooldownUntil[operatorUid] = now + cooldown
}

fun releaseForceObPlayer(target: Player): Boolean {
    val ids = PlayerData[target].ids
    var removed = false
    ids.forEach {
        if (limitPlayers.remove(it) != null) removed = true
        forceObSources.remove(it)
        forceObOperatorUids.remove(it)
        forceObExpireAt.remove(it)
    }
    if (removed) teams.changeTeam(target)
    return removed
}

/** 检举通过后按 uid 解除强制观战（目标可能已离线）；返回是否确实解除了限制。 */
fun releaseForceObByUids(uids: Set<String>, targetUuid: String): Boolean {
    var removed = false
    uids.filter { it.isNotBlank() }.forEach { uid ->
        if (limitPlayers.remove(uid) != null) removed = true
        forceObSources.remove(uid)
        forceObOperatorUids.remove(uid)
        forceObExpireAt.remove(uid)
    }
    val player = Groups.player.find { it.uuid() == targetUuid }
    if (removed && player != null) {
        teams.changeTeam(player)
        player.sendMessage("[green]你受到的强制观战惩罚已被检举投票解除。")
    }
    return removed
}

/** 到点自动解除非管理员层级的强制观战（无时长惩罚按 1 天处理）。 */
private fun releaseExpiredForceOb() {
    if (forceObExpireAt.isEmpty()) return
    val now = System.currentTimeMillis()
    Groups.player.toList().forEach { player ->
        val data = PlayerData[player]
        val expired = (data.ids + data.id + data.uuid)
            .any { id -> forceObExpireAt[id]?.let { it <= now } == true }
        if (!expired) return@forEach
        if (releaseForceObPlayer(player)) {
            player.sendMessage("[green]你受到的强制观战限制已到期（非管理员层级最长 1 天），已自动解除。")
            broadcast(
                "[yellow][强制观战] [white]{target.name}[yellow] 的限制已到期，已自动解除。".with("target" to player)
            )
        }
    }
}

listenTo<PunishmentLiftedEvent> {
    if (kind != "ob") return@listenTo
    val lifted = this
    launch(Dispatchers.game) {
        runCatching { releaseForceObByUids(lifted.targetUids, lifted.targetUuid) }
            .onFailure { logger.warning("检举解除强制观战失败: ${it.message}") }
    }
}

fun releaseOwnForceObPlayer(target: Player, operatorUid: String): Boolean {
    if (!isForceObOwnedBy(target, operatorUid)) return false
    return releaseForceObPlayer(target)
}

suspend fun startObVote(starter: Player, target: Player, reason: String): Boolean {
    // 发起者信息在投票开始前捕获：投票期间发起者可能离线，事后 PlayerData 取不到。
    val starterData = runCatching { PlayerData[starter] }.getOrNull() ?: run {
        broadcast("[yellow]投票已取消：发起者状态异常。".with())
        return false
    }
    val starterUid = starterData.id
    val starterUids = (starterData.ids + starterUid).filter { it.isNotBlank() }.toSet()
    val starterLevel = with(trustLevel) { getTrustLevelOrder(starter) }
    val starterName = starter.plainName()
    val targetUids = PlayerData[target].ids

    // 惩罚类投票发起前检查：检举封禁 + (施罚者,目标) 24h 冷却（用户口径 Q2/Q5，投票类同样受约束 Q4）。
    val decision = with(playerMute) {
        preparePunishment(starterUids, starterLevel, targetUids, null, false)
    }
    decision.blockReason?.let { reasonText ->
        starter.sendMessage(reasonText)
        return false
    }

    val event = VoteEvent(
        thisScript, starter,
        voteDesc = "强制观战(目标[red]{target.name}[yellow])".with("target" to target),
        extDesc = "[red]理由: [yellow]${reason}",
        // 作用于他人：禁止游客发起（2026-09-27 用户要求）
        guestForbidden = true,
    )
    if (!event.awaitResult()) return false
    if (target.hasPermission("wayzer.admin.skipKick")) {
        broadcast("[red]错误：[white]${target.name}[red]为管理员，如有问题请与服主联系".with())
        return false
    }
    // 非管理员层级发起的投票观战同样补 24h 到期（Q4）。
    val cappedMinutes = with(playerMute) { nonAdminMaxPunishMinutesValue() }
    val nonAdminApplied = !with(playerMute) { isAdminTierLevel(starterLevel) }
    val untilMillis = if (nonAdminApplied) System.currentTimeMillis() + cappedMinutes * 60_000L else null
    applyForceObRecord(target, reason, "vote", starterUid, untilMillis)
    teams.changeTeam(target, teams.spectateTeam)
    broadcast(
        "[yellow][提示][green]如目标用户继续捣乱，可以使用[gold]/vote kick {player.shortID}[]投票踢出".with(
            "player" to target
        )
    )
    if (nonAdminApplied) {
        broadcast(
            "[yellow][提示] [white]{target.name}[yellow] 的强制观战将在 {minutes} 分钟后自动解除（非管理员层级最长 1 天）。".with(
                "target" to target, "minutes" to cappedMinutes
            )
        )
    }
    with(playerMute) { markPunishmentAppliedIfNeeded(starterUids, starterLevel, targetUids) }
    with(playerMute) {
        emitPunishmentApplied(
            kind = "ob",
            target = target,
            durationMinutes = if (nonAdminApplied) cappedMinutes else null,
            operatorUid = starterUid,
            operatorName = starterName,
            operatorLevelOrder = starterLevel,
            operatorUids = starterUids,
            source = "vote",
            reason = reason,
        )
    }
    return true
}

onEnable {
    val script = this
    // 只作用于自己：quitOb（解除自己的观战限制）不受"禁止发起投票"拦截，给被禁者留自救入口（2026-09-27 用户要求）。
    VoteEvent.registerStartBanExemptVote("quitOb", "解除观战")
    VoteEvent.registerDenyVotePredicate("wayzer.cmds.voteOb.forceOb") { isForceOb(it) }
    onDisable { VoteEvent.unregisterDenyVotePredicate("wayzer.cmds.voteOb.forceOb") }
    VoteEvent.VoteCommands += CommandInfo(script, "ob", "[cyan]强制玩家观战[gray]（需50%同意）") {
        aliases = listOf("观战")
        usage = "<玩家名/id> <理由>"
        permission = "wayzer.vote.ob"
        body {
            // 作用于他人的投票禁止游客发起：先拦一次，免得游客先选人/填理由再被拒（2026-09-27 用户要求）。
            VoteEvent.guestVoteBlockReason(player!!)?.let { returnReply(it.with()) }
            val target = with(voteKick) { getTarget() }
            val reason = with(voteKick) { getInput("限制观战理由", "[red]投票限制他人需要理由".with()) }
            val player = player!!
            startObVote(player, target, reason)
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "quitOb", "[yellow]解除自己的观战限制[gray]（需50%同意）") {
        // 别名与 playerMute 的“被禁言者自救投票豁免”白名单对齐（quob/退出观战/解除观战）。
        aliases = listOf("quob", "解除观战", "退出观战")
        body {
            val player = player!!
            val (reason, time) = forceObInfo(player)
                ?: returnReply("[yellow]你未被限制游戏，无需解除".with())
            val delta = Duration.between(time, Instant.now())
            val event = VoteEvent(
                script, player,
                voteDesc = "解除强制(已持续{delta 分钟})".with("delta" to delta),
                extDesc = "[yellow]被限制时的理由: $reason",
                bypassDenyVote = { it == player },
                // 自救入口：即使被管理员禁止发起投票也允许发起（2026-09-27 用户要求）
                ignoreStartBan = true,
            )
            if (event.awaitResult()) {
                releaseForceObPlayer(player)
            }
        }
    }
    // 非管理员层级的强制观战到期自动解除（每 30 秒扫一次；管理员层级不受影响）。
    launch(Dispatchers.game) {
        while (true) {
            delay(30_000L)
            runCatching { releaseExpiredForceOb() }
                .onFailure { logger.warning("清理到期强制观战失败: ${it.message}") }
        }
    }
}

listenTo<BetterTeam.AssignTeamEvent>(Event.Priority.Intercept) {
    // 非管理员层级的强制观战已到期的，先解除再决定是否继续强制观战。
    val expired = (PlayerData[player].ids + player.uuid())
        .any { id -> forceObExpireAt[id]?.let { it <= System.currentTimeMillis() } == true }
    if (expired) {
        releaseForceObPlayer(player)
        return@listenTo
    }
    forceObInfo(player)?.let { (reason, time) ->
        val delta = Duration.between(time, Instant.now())
        player.sendMessage(
            """
                [red]你已被限制强制观战.
                [yellow]投票原因: [white]{reason}({delta 分钟}前)
                [yellow]如有疑问，请在聊天区交流
                [green]可通过[gold]/vote quitOb[]投票，取消限制
            """.trimIndent().with("reason" to reason, "delta" to delta),
            MsgType.InfoMessage
        )
        team = teams.spectateTeam
    }
}
command("votekick", "(弃用)投票踢人") {
    this.usage = "<player...>"
    attr(ClientOnly)
    body {
        //Redirect
        arg = listOf("ob", *arg.toTypedArray())
        VoteEvent.VoteCommands.handle()
    }
}
command("forceOB", "管理指令：使某人强制观战") {
    usage = "<玩家名/id>"
    permission = "wayzer.admin.forceOb"
    body {
        val target = with(voteKick) { getTarget() }
        player?.let { operator ->
            if (!with(trustLevel) { canDirectRestrictTrustTarget(operator, target) }) {
                returnReply("[red]你不能处理同级或更高等级的玩家。".with())
            }
        }
        if (isForceOb(target)) {
            releaseForceObPlayer(target)
            returnReply("[green]已解除目标限制".with())
        }
        val reason = with(voteKick) { getInput("限制观战理由", "[red]投票限制他人需要理由".with()) }
        val source = player?.let { if (with(trustLevel) { isPluginAdmin(it) }) "pluginAdmin" else "admin" } ?: "system"
        forceObPlayer(target, reason, player, sourceOverride = source)
    }
}
PermissionApi.registerDefault("wayzer.admin.forceOb", group = "@admin")
