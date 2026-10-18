@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/cmds/voteKick", "复用目标选择/文本输入/理由收集")
@file:Depends("wayzer/ext/playerMute", "禁言实现与惩罚规则")

package wayzer.cmds

import coreMindustry.lib.broadcast
import wayzer.VoteEvent
import wayzer.lib.PlayerData

private val voteKick = contextScript<VoteKick>()
private val playerMute = contextScript<wayzer.ext.PlayerMute>()

/**
 * 投票禁言时长上限：3 天（用户 2026-09-27 要求"最多 3 天"）。
 *
 * 注意：管理员层级（3++/4）发起时保持 3 天上限；3+ 及以下发起时还会被 playerMute 的
 * "非管理员层级单次最多 1440 分钟"规则钳制（用户 2026-10-17 口径 Q4），并在发起前提示。
 * 脚本顶层不允许 const，故用 val。
 */
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
        textInput.textInput(player, "请在60s内输入禁言时长（如 30 / 30m / 2h / 1d / 1d12h，上限 3 天；非管理员层级最长 1 天）")
    } ?: returnReply("[yellow]已取消输入，投票未发起。".with())
    return parseMuteMinutes(input)
        ?: returnReply("[red]无法识别的时长：$input（可用 30/30m/2h/1d/1d12h，最多 3 天）".with())
}

private suspend fun startMuteVote(starter: Player, target: Player, requestedMinutes: Int, reason: String): Boolean {
    // 发起者信息在投票开始前捕获：投票期间发起者可能离线，事后 PlayerData 取不到。
    val starterData = runCatching { PlayerData[starter] }.getOrNull() ?: run {
        starter.sendMessage("[yellow]你的玩家状态异常，投票未发起。")
        return false
    }
    val starterUid = starterData.id
    val starterUids = (starterData.ids + starterUid).filter { it.isNotBlank() }.toSet()
    val starterName = starter.plainName()
    val starterLevel = with(playerMute) { applierLevelOrder(starter) }
    val targetUids = PlayerData[target].ids

    // 被检举封禁“发起限制类投票”时拦截（2026-10-18 用户口径：投票类限制被检举成功额外封此权限）。
    // 自救类投票（quitMute）走独立入口，不受这里影响。
    if (!with(playerMute) { isAdminTierLevel(starterLevel) }) {
        with(playerMute) { restrictVoteStartBlockReason(starterUids) }?.let { blockReason ->
            starter.sendMessage(blockReason)
            return false
        }
    }

    // 发起前统一检查：检举封禁 → 24h 冷却 → 非管理员 1 天上限（用户口径 Q2/Q4，避免投票白跑）。
    val decision = with(playerMute) {
        preparePunishment(starterUids, starterLevel, targetUids, requestedMinutes, permanentRequest = false)
    }
    decision.blockReason?.let { reasonText ->
        starter.sendMessage(reasonText)
        return false
    }
    decision.note?.let { starter.sendMessage(it) }
    val minutes = (decision.allowedMinutes ?: requestedMinutes).coerceAtLeast(1)
    val duration = formatMuteMinutes(minutes)
    val adminTier = with(playerMute) { isAdminTierLevel(starterLevel) }
    val limitNote = if (adminTier) "上限 3 天" else "非管理员层级上限 1 天（1440 分钟）"

    val event = VoteEvent(
        thisScript, starter,
        voteDesc = "投票禁言(目标[red]{target.name}[yellow]|[gold]{duration})".with(
            "target" to target, "duration" to duration,
        ),
        extDesc = "[red]理由: [yellow]$reason\n[gray]时长：$duration（$limitNote）",
        // 作用于他人：禁止游客发起（2026-09-27 用户要求，与踢出/观战同一口径）
        guestForbidden = true,
    )
    if (!event.awaitResult()) return false
    if (target.hasPermission("wayzer.admin.skipKick")) {
        broadcast("[red]错误：[white]${target.name}[red]为管理员，如有问题请与服主联系".with())
        return false
    }
    // 投票通过后走专用入口：施罚者归因为发起者，非管理员同样受 1 天/24h 冷却/检举封禁约束。
    // operator 不能直接传 starter：那会撞上 canManagePlayerMute 的目标等级边界（原本传 null 的原因）。
    val ok = with(playerMute) {
        mutePlayerTemporaryByVote(
            target,
            minutes,
            "投票禁言（发起者 $starterName）：$reason",
            starterUid,
            starterName,
            starterUids,
        )
    }
    if (!ok) {
        broadcast("[red]投票已通过，但禁言未能生效：目标可能已离线，或触发了时限/冷却/检举封禁规则。".with())
        return false
    }
    broadcast(
        "[yellow]投票已通过：[white]${target.name}[yellow] 被禁言 [gold]$duration[yellow]，理由：[white]$reason".with()
    )
    return true
}

/**
 * 被禁言者自救投票：解除自己的禁言（对称 `/vote quitOb`）。
 *
 * 口径（用户 2026-10-17 需求）：
 * - 仅"当前确实被禁言"的玩家可发起，未禁言直接拒绝并提示；
 * - 只解除**临时禁言**；永久禁言（数据库无期限记录）拒绝并提示联系管理员，
 *   避免自救投票反向解掉管理员处罚（若为管理员层级以下玩家施加，提示可用 /punishreport 检举）；
 * - 自救入口：`ignoreStartBan = true`（即使被禁止发起投票也可用），
 *   `bypassDenyVote = { it == player }`（本人可投自己，不受强制观战/教程等 deny predicate 影响）；
 * - 阈值沿用 VoteEvent 默认（50% 同意、fastSuccess），通过后调用 playerMute.unmutePlayer 解禁；
 *   玩家投票期间离线时按投票前捕获的 uid 兜底（liftMuteByUids）。
 */
private suspend fun startQuitMuteVote(player: Player): Boolean {
    val muteReasonText = with(playerMute) { runCatching { muteReason(player) }.getOrNull() }
        ?: run {
            player.sendMessage("[yellow]你当前没有被禁言，无需发起解除禁言投票。".with())
            return false
        }
    if (with(playerMute) { runCatching { isPermanentMuted(player) }.getOrDefault(false) }) {
        player.sendMessage(
            (
                "[red]你当前是永久禁言（无期限），自救投票只能解除临时禁言。\n" +
                    "[gray]永久禁言需联系管理员处理；若该惩罚由管理员层级以下玩家施加，可用 [white]/punishreport[] 发起检举。"
                ).with()
        )
        return false
    }
    val data = runCatching { PlayerData[player] }.getOrNull() ?: run {
        player.sendMessage("[yellow]你的玩家状态异常，投票未发起。".with())
        return false
    }
    val targetUid = data.id
    val targetUids = (data.ids + targetUid).filter { it.isNotBlank() }.toSet()
    val targetUuid = player.uuid()
    val playerName = player.plainName()

    val event = VoteEvent(
        thisScript, player,
        voteDesc = "解除自己的禁言".with(),
        extDesc = "[yellow]禁言原因: $muteReasonText",
        bypassDenyVote = { it == player },
        // 自救入口：即使被管理员禁止发起投票也允许发起（与 quitOb 同口径）。
        ignoreStartBan = true,
    )
    if (!event.awaitResult()) return false

    val removed = runCatching { with(playerMute) { unmutePlayer(player) } }.getOrDefault(false) ||
        runCatching { with(playerMute) { liftMuteByUids(targetUids, targetUuid) } }.getOrDefault(false)
    if (removed) {
        broadcast("[yellow]投票已通过：[white]$playerName[yellow] 的禁言已被解除。".with())
    } else {
        broadcast("[yellow]投票已通过，但禁言记录已不存在（可能已到期或已被管理员解除）。".with())
    }
    return removed
}

onEnable {
    val script = this
    // 只作用于自己：quitMute（解除自己的禁言）不受"禁止发起投票"拦截，给被禁言者留自救入口。
    VoteEvent.registerStartBanExemptVote("quitMute", "解除禁言", "解禁")
    VoteEvent.VoteCommands += CommandInfo(
        script,
        "quitMute",
        "[yellow]解除自己的禁言[gray]（被禁言者自救，需50%同意；仅临时禁言，永久禁言需联系管理员）"
    ) {
        aliases = listOf("解除禁言", "解禁")
        body {
            startQuitMuteVote(player!!)
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "mute", "[red]投票禁言玩家[gray]（需50%同意，最多3天；非管理员层级最多1天）") {
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
