@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/cmds/voteKick", "复用目标选择/文本输入/理由收集")
@file:Depends("wayzer/ext/playerMute", "禁言实现")

package wayzer.cmds

import coreMindustry.lib.broadcast
import wayzer.VoteEvent

private val voteKick = contextScript<VoteKick>()
private val playerMute = contextScript<wayzer.ext.PlayerMute>()

/** 投票禁言时长上限：3 天（用户 2026-09-27 要求"最多 3 天"）。脚本顶层不允许 const，故用 val。 */
private val MAX_MUTE_MINUTES = 72 * 60

private val muteDurationRegex = Regex("""^(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?$""")

/**
 * 解析禁言时长：`30`（按分钟）、`30m`、`2h`、`1d`、`1d12h`、`1d12h30m`。
 * 结果裁剪到 1 分钟 ~ [MAX_MUTE_MINUTES]（3 天）；无法识别返回 null。
 */
private fun parseMuteMinutes(raw: String?): Int? {
    val text = raw?.trim()?.lowercase()?.replace(" ", "")?.takeIf { it.isNotEmpty() } ?: return null
    // 纯数字按分钟处理：与 /vote pauseWave 的"纯数字=秒"口径一致，少记一种写法。
    text.toIntOrNull()?.let { return it.coerceIn(1, MAX_MUTE_MINUTES) }
    val m = muteDurationRegex.matchEntire(text) ?: return null
    val days = m.groupValues[1].toIntOrNull() ?: 0
    val hours = m.groupValues[2].toIntOrNull() ?: 0
    val minutes = m.groupValues[3].toIntOrNull() ?: 0
    val total = days * 24 * 60 + hours * 60 + minutes
    if (total <= 0) return null
    return total.coerceIn(1, MAX_MUTE_MINUTES)
}

private fun formatMuteMinutes(minutes: Int): String = buildString {
    val days = minutes / (24 * 60)
    val hours = minutes % (24 * 60) / 60
    val mins = minutes % 60
    if (days > 0) append("${days}天")
    if (hours > 0) append("${hours}小时")
    if (mins > 0) append("${mins}分钟")
}.ifEmpty { "${minutes}分钟" }

/**
 * 取禁言时长：先用命令里的第一个参数；没给、或给的不是合法时长时，弹文本输入框让玩家填
 * （用户要求"需要输入时间"）。取消输入或格式错误一律 returnReply 结束。
 */
private suspend fun CommandContext.askMuteMinutes(player: Player): Int {
    parseMuteMinutes(arg.firstOrNull())?.let {
        arg = arg.drop(1)
        return it
    }
    val input = with(voteKick) {
        textInput.textInput(player, "请在60s内输入禁言时长（如 30 / 30m / 2h / 1d / 1d12h，上限 3 天）")
    } ?: returnReply("[yellow]已取消输入，投票未发起。".with())
    return parseMuteMinutes(input)
        ?: returnReply("[red]无法识别的时长：$input（可用 30/30m/2h/1d/1d12h，最多 3 天）".with())
}

private suspend fun startMuteVote(starter: Player, target: Player, minutes: Int, reason: String): Boolean {
    val duration = formatMuteMinutes(minutes)
    val event = VoteEvent(
        thisScript, starter,
        voteDesc = "投票禁言(目标[red]{target.name}[yellow]|[gold]{duration})".with(
            "target" to target, "duration" to duration,
        ),
        extDesc = "[red]理由: [yellow]$reason\n[gray]时长：$duration（上限 3 天）",
        // 作用于他人：禁止游客发起（2026-09-27 用户要求，与踢出/观战同一口径）
        guestForbidden = true,
    )
    if (!event.awaitResult()) return false
    if (target.hasPermission("wayzer.admin.skipKick")) {
        broadcast("[red]错误：[white]${target.name}[red]为管理员，如有问题请与服主联系".with())
        return false
    }
    // operator 传 null：投票不是"某个协管在动手"，避免撞上协管的目标等级边界（与 /vote kick 的口径一致）。
    val ok = with(playerMute) {
        mutePlayerTemporary(target, minutes, "投票禁言（发起者 ${starter.plainName()}）：$reason", null)
    }
    if (!ok) {
        broadcast("[red]投票已通过，但禁言未能生效：目标可能已离线。".with())
        return false
    }
    broadcast(
        "[yellow]投票已通过：[white]${target.name}[yellow] 被禁言 [gold]$duration[yellow]，理由：[white]$reason".with()
    )
    return true
}

onEnable {
    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "mute", "[red]投票禁言玩家[gray]（需50%同意，最多3天）") {
        aliases = listOf("禁言投票", "votemute", "投票禁言")
        usage = "<玩家名/id> [时长: 30/30m/2h/1d/1d12h] [理由]"
        permission = "wayzer.vote.mute"
        body {
            // 作用于他人的投票禁止游客发起：先拦一次，免得游客先选人/填理由再被拒（2026-09-27 用户要求）。
            VoteEvent.guestVoteBlockReason(player!!)?.let { returnReply(it.with()) }
            val starter = player!!
            val target = with(voteKick) { getTarget() }
            if (target == starter) returnReply("[red]不能投票禁言自己。".with())
            if (with(playerMute) { isMuted(target) }) {
                returnReply("[yellow]目标当前已被禁言，无需重复投票。".with())
            }
            val minutes = askMuteMinutes(starter)
            val reason = with(voteKick) { getInput("投票禁言理由", "[red]投票禁言需要理由".with()) }
            startMuteVote(starter, target, minutes, reason)
        }
    }
}
