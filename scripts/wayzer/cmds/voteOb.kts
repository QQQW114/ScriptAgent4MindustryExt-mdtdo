@file:Depends("wayzer/cmds/voteKick", "功能控制，使用util，覆盖votekick")
@file:Depends("wayzer/map/betterTeam", "强制观察者")
@file:Depends("wayzer/user/trustLevel", "3++协管目标层级保护")

package wayzer.cmds

import wayzer.VoteEvent
import wayzer.map.BetterTeam
import java.time.Duration
import java.time.Instant

val teams = contextScript<BetterTeam>()
val voteKick = contextScript<VoteKick>()
val trustLevel = contextScript<wayzer.user.TrustLevel>()

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
) {
    val now = Instant.now()
    PlayerData[target].ids.forEach {
        limitPlayers[it] = reason to now
        forceObSources[it] = source
        if (operatorUid == null) forceObOperatorUids.remove(it)
        else forceObOperatorUids[it] = operatorUid
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
    applyForceObRecord(target, reason, source, operatorUid)
    teams.changeTeam(target, teams.spectateTeam)
    val operatorName = operator?.name ?: "系统"
    broadcast(
        "[red]{operator}[red] 强制 {target.name}[red] 成为观察者, 原因: [yellow]{reason}"
            .with("operator" to operatorName, "target" to target, "reason" to reason)
    )
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
    }
    if (removed) teams.changeTeam(target)
    return removed
}

fun releaseOwnForceObPlayer(target: Player, operatorUid: String): Boolean {
    if (!isForceObOwnedBy(target, operatorUid)) return false
    return releaseForceObPlayer(target)
}

suspend fun startObVote(starter: Player, target: Player, reason: String): Boolean {
    val event = VoteEvent(
        thisScript, starter,
        voteDesc = "强制观战(目标[red]{target.name}[yellow])".with("target" to target),
        extDesc = "[red]理由: [yellow]${reason}"
    )
    if (!event.awaitResult()) return false
    if (target.hasPermission("wayzer.admin.skipKick")) {
        broadcast("[red]错误：[white]${target.name}[red]为管理员，如有问题请与服主联系".with())
        return false
    }
    applyForceObRecord(target, reason, "vote", PlayerData[starter].id)
    teams.changeTeam(target, teams.spectateTeam)
    broadcast(
        "[yellow][提示][green]如目标用户继续捣乱，可以使用[gold]/vote kick {player.shortID}[]投票踢出".with(
            "player" to target
        )
    )
    return true
}

onEnable {
    val script = this
    VoteEvent.registerDenyVotePredicate("wayzer.cmds.voteOb.forceOb") { isForceOb(it) }
    onDisable { VoteEvent.unregisterDenyVotePredicate("wayzer.cmds.voteOb.forceOb") }
    VoteEvent.VoteCommands += CommandInfo(script, "ob", "[cyan]强制观战[gray]（需50%同意）") {
        aliases = listOf("观战")
        usage = "<玩家名/id> <理由>"
        permission = "wayzer.vote.ob"
        body {
            val target = with(voteKick) { getTarget() }
            val reason = with(voteKick) { getInput("限制观战理由", "[red]投票限制他人需要理由".with()) }
            val player = player!!
            startObVote(player, target, reason)
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "quitOb", "[yellow]解除强行观战限制(限本人)[gray]（需50%同意）") {
        aliases = listOf("解除观战")
        body {
            val player = player!!
            val (reason, time) = forceObInfo(player)
                ?: returnReply("[yellow]你未被限制游戏，无需解除".with())
            val delta = Duration.between(time, Instant.now())
            val event = VoteEvent(
                script, player,
                voteDesc = "解除强制(已持续{delta 分钟})".with("delta" to delta),
                extDesc = "[yellow]被限制时的理由: $reason",
                bypassDenyVote = { it == player }
            )
            if (event.awaitResult()) {
                releaseForceObPlayer(player)
            }
        }
    }
}

listenTo<BetterTeam.AssignTeamEvent>(Event.Priority.Intercept) {
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
