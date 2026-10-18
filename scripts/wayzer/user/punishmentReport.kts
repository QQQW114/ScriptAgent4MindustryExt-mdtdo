@file:Depends("wayzer/vote", "检举投票实现")
@file:Depends("wayzer/user/trustLevel", "管理员层级判定")
@file:Depends("wayzer/ext/playerMute", "惩罚规则/事件/共享状态")
@file:Depends("coreMindustry/menu", "检举提示弹窗")
@file:Depends("coreMindustry/utilTextInput", "检举理由自定义输入")

package wayzer.user

import coreMindustry.MenuBuilder
import coreMindustry.lib.broadcast
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.VoteEvent
import wayzer.ext.PlayerMute
import wayzer.ext.PlayerMute.PunishmentAppliedEvent
import wayzer.ext.PlayerMute.PunishmentLiftedEvent
import wayzer.lib.PlayerData

/**
 * 惩罚检举系统（2026-10-17 初版；2026-10-18 第二阶段改造）。
 *
 * 两种检举入口（用户 2026-10-18 需求）：
 * a. 直接限制（菜单/命令/面板施加禁言/观战/禁建）→ 弹窗提示"你被 xx … 是否发起检举？"；
 * b. 投票限制成功（/vote ob、/vote mute 通过后）→ 同样弹窗，但点"发起检举投票"后**先弹第二个菜单选检举理由**
 *    （恶意投票/投错了/判断错了/自定义输入），确认后再发起检举投票。
 *
 * 检举投票通过后（规则在 wayzer/ext/playerMute.kts，跨重启持久化）：
 * - 解除被检举的那条惩罚（PunishmentLiftedEvent）；
 * - 禁止施罚者"直接限制他人" 1 天；累计 ≥3 次 → 永久禁止直接限制；
 * - 若被检举行为来自投票限制：额外禁止其"发起限制类投票" 1 天；累计 ≥6 次 → 永久禁止发起限制类投票。
 *
 * 本脚本单向依赖 playerMute（不反向依赖任何惩罚脚本）；性能：事件回调/投票发起检查均为内存查询，
 * 状态持久化在 playerMute 里走"启用时 IO 预加载 + 异步串行落库"。
 */
name = "惩罚检举系统"

private val trustLevel = contextScript<TrustLevel>()

// playerMute 用懒加载解析：本脚本大量逻辑失败时只影响检举功能，不拖垮惩罚入口脚本。
private val playerMute by lazy { contextScript<PlayerMute>() }
private val textInput by lazy { contextScript<coreMindustry.UtilTextInput>() }

// ---------- 配置 ----------

private val enabled by config.key(true, "是否启用惩罚检举系统（弹窗/检举投票/检举封禁）")
private val passRatePercent by config.key(70, "检举投票通过所需赞成率(%)")
private val popupDelayMillis by config.key(3000L, "惩罚生效后延迟多久弹出检举提示(ms)，避免与惩罚提示同帧刷屏")
private val popupMinIntervalMillis by config.key(60_000L, "同一玩家两次检举弹窗的最小间隔(ms)")
private val minOnlinePlayers by config.key(3, "在线人数低于该值时跳过检举弹窗/投票（人数不足无法形成有效投票）")
private val contextValidMinutes by config.key(30, "/punishreport report 允许检举最近一次惩罚的有效期(分钟)")
private val voteReportReasonOptions by config.key(
    listOf("对方恶意投票", "对方投错了", "对方判断错了"),
    "投票类限制的检举理由选项（玩家还可选择自定义文本输入）"
)

// ---------- 运行态 ----------

private data class StoredPunishment(val event: PunishmentAppliedEvent, val appliedAt: Long)

/** 每个被罚者最近一次可检举的惩罚（按目标会话 uuid）。 */
private val lastPunishments = java.util.concurrent.ConcurrentHashMap<String, StoredPunishment>()
private val lastPopupAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

/** 正在检举中的目标（targetUuid → 开始时间），防止重复开票。 */
private val activeReportTargets = java.util.concurrent.ConcurrentHashMap<String, Long>()

/** 已处理过的惩罚记录 id（防止投票成功后重复解罚/计数）。 */
private val handledRecords = java.util.concurrent.ConcurrentHashMap<Long, Long>()

private val HANDLED_RECORD_TTL_MILLIS = 6 * 3_600_000L

// ---------- 小工具 ----------

private fun kindDisplay(kind: String): String = when (kind) {
    "mute" -> "禁言"
    "ob" -> "强制观战"
    "buildban" -> "禁止建造/拆除"
    else -> kind
}

private fun playerUids(player: Player): Set<String> =
    runCatching { (PlayerData[player].ids + PlayerData[player].id).filter { it.isNotBlank() }.toSet() }
        .getOrNull() ?: setOf(player.uuid())

private fun isSameAccount(player: Player, uid: String): Boolean =
    runCatching { PlayerData[player].ids.contains(uid) || PlayerData[player].id == uid }.getOrNull() ?: false

private fun hasStaffAccess(player: Player?): Boolean =
    player == null || with(trustLevel) { isPluginAdminOrHigher(player) }

private fun hasAdminAccess(player: Player?): Boolean =
    player == null || with(trustLevel) { isTrustAdmin(player) }

private fun formatLeft(millis: Long): String =
    runCatching { with(playerMute) { formatPunishLeft(millis) } }.getOrNull() ?: "${millis / 60_000}分钟"

private fun pruneHandledRecords() {
    val now = System.currentTimeMillis()
    handledRecords.entries.removeIf { now - it.value > HANDLED_RECORD_TTL_MILLIS }
}

/** 当前封禁规则的一句话说明（弹窗/投票/UI 共用；数值随配置实时变化）。 */
private fun ruleSummaryText(): String {
    val directStrikes = runCatching { with(playerMute) { permanentDirectBanStrikesValue() } }.getOrNull() ?: 3
    val voteStrikes = runCatching { with(playerMute) { permanentVoteStartBanStrikesValue() } }.getOrNull() ?: 6
    val banHours = runCatching { with(playerMute) { reportBanHoursValue() } }.getOrNull() ?: 24
    val voteBanHours = runCatching { with(playerMute) { reportVoteStartBanHoursValue() } }.getOrNull() ?: 24
    return "每次检举成功：禁止对方 ${banHours} 小时直接限制他人；投票类限制被检举成功还会额外禁止其 " +
            "${voteBanHours} 小时发起限制类投票。累计成功检举 ≥${directStrikes} 次永久禁止直接限制，" +
            "≥${voteStrikes} 次永久禁止发起限制类投票。"
}

/** 被检举者当前累计被成功检举次数。 */
private fun operatorStrikeCount(ev: PunishmentAppliedEvent): Int =
    runCatching { with(playerMute) { strikeCount(ev.operatorUids) } }.getOrNull() ?: 0

// ---------- 惩罚生效 → 弹窗 ----------

listenTo<PunishmentAppliedEvent> {
    if (!enabled) return@listenTo
    val event = this
    launch(Dispatchers.game) {
        runCatching { handlePunishmentApplied(event) }
            .onFailure { logger.warning("处理惩罚生效事件失败: ${it.message}") }
    }
}

private fun handlePunishmentApplied(ev: PunishmentAppliedEvent) {
    // 只处理“人施加给人看”的惩罚：系统/自动清理不触发检举（B12/T06）。
    val operatorUid = ev.operatorUid
    if (operatorUid.isNullOrBlank() || ev.source == "system") return
    // 管理员层级（3++/4）不受检举（R2/T04）。
    val adminTier = runCatching { with(playerMute) { isAdminTierLevel(ev.operatorLevelOrder) } }.getOrNull() ?: false
    if (adminTier) return
    // 游客不受保护（R1/T05）。
    if (!ev.targetAuthed) return

    val target = Groups.player.find { it.uuid() == ev.targetUuid } ?: return
    // 防御：自己惩罚自己不可能，但事件被误发时也不弹窗（B6）。
    if (isSameAccount(target, operatorUid)) return

    lastPunishments[ev.targetUuid] = StoredPunishment(ev, System.currentTimeMillis())

    // 同一目标已有检举进行中：不再弹窗，避免重复开票（B7/T12）。
    if (activeReportTargets.containsKey(ev.targetUuid)) return

    // 在线人数不足时无法形成有效投票：不弹窗，只给文字提示（需求原文“没有玩家在线时跳过”）。
    val online = Groups.player.size()
    if (online < minOnlinePlayers.coerceAtLeast(2)) {
        target.sendMessage(
            "[yellow]你被 [white]${ev.operatorName ?: "未知玩家"}[yellow] ${kindDisplay(ev.kind)}了；当前在线人数不足，暂时无法发起检举投票。"
        )
        return
    }

    val now = System.currentTimeMillis()
    val lastPopup = lastPopupAt[ev.targetUuid] ?: 0L
    if (now - lastPopup < popupMinIntervalMillis.coerceAtLeast(0L)) return
    lastPopupAt[ev.targetUuid] = now

    launch(Dispatchers.game) {
        delay(popupDelayMillis.coerceAtLeast(0L))
        runCatching { showReportPrompt(target, ev) }
            .onFailure { logger.warning("打开检举弹窗失败: ${it.message}") }
    }
}

private suspend fun showReportPrompt(reporter: Player, ev: PunishmentAppliedEvent) {
    val passRate = passRatePercent.coerceIn(1, 100)
    val strikes = operatorStrikeCount(ev)
    val msgText = buildString {
        appendLine("[red]你被 [white]${ev.operatorName ?: "未知玩家"}[red] ${kindDisplay(ev.kind)}了！")
        appendLine("[yellow]惩罚原因：[white]${ev.reason}")
        if (ev.fromVote) appendLine("[yellow]施加方式：[white]由玩家投票通过（发起者：${ev.operatorName ?: "未知玩家"}）")
        appendLine()
        appendLine("[gray]如果你认为该惩罚不合理，可以发起检举投票（需要全服 $passRate% 赞成）。")
        appendLine("[yellow]检举说明：[white]本检举投票的发起者认为对方滥用权力，如果检举成功会禁止其一天使用权力。")
        appendLine("[yellow]此人已被成功检举了 [white]${strikes}[yellow] 次。")
        appendLine("[gray]${ruleSummaryText()}")
        append("[gray]被检举者本人不能参与投票；关闭后可用 [gold]/punishreport[] 重新打开本提示。")
    }
    var start = false
    MenuBuilder<Unit>("[gold]惩罚检举") {
        msg = msgText
        option("[green]发起检举投票\n[gray]需要全服 $passRate% 同意") { start = true }
        option("[gray]暂不检举") {}
    }.sendTo(reporter, 90_000)
    if (!start) return
    // 入口 b（投票限制）：先让被罚者选检举理由（第二个菜单），再发起检举投票。
    val reportReason = if (ev.fromVote) {
        askVoteReportReason(reporter) ?: return
    } else {
        "认为该直接限制不合理"
    }
    startReportVote(reporter, ev, reportReason)
}

/** 投票类限制的检举理由菜单（固定选项 + 自定义文本输入）；取消返回 null。 */
private suspend fun askVoteReportReason(reporter: Player): String? {
    val options = voteReportReasonOptions.filter { it.isNotBlank() }
    var choice: String? = null
    var custom = false
    MenuBuilder<Unit>("[gold]选择检举理由") {
        msg = buildString {
            appendLine("[yellow]请选择你对这次投票限制的检举理由：")
            append("[gray]（选择“自定义理由”后输入文本；也可以取消本次检举）")
        }
        options.forEach { option(it) { choice = it } }
        newRow()
        option("[orange]自定义理由（文本输入）") { custom = true }
        option("[gray]取消") {}
    }.sendTo(reporter, 60_000)
    if (custom) {
        val input = runCatching {
            with(textInput) { textInput(reporter, "请输入检举理由（60 秒内）", "检举理由", lengthLimit = 48) }
        }.getOrNull()?.trim()
        if (input.isNullOrEmpty()) {
            reporter.sendMessage("[yellow]未输入检举理由，已取消。")
            return null
        }
        return input
    }
    if (choice == null) {
        reporter.sendMessage("[yellow]已取消检举。")
        return null
    }
    return choice
}

// ---------- 检举投票 ----------

private suspend fun startReportVote(reporter: Player, ev: PunishmentAppliedEvent, reportReason: String): Boolean {
    if (!enabled) return false
    if (reporter.uuid() != ev.targetUuid) {
        reporter.sendMessage("[yellow]只有被惩罚的玩家本人可以发起检举。")
        return false
    }
    val operatorUid = ev.operatorUid ?: return false
    pruneHandledRecords()
    if (handledRecords.containsKey(ev.recordId)) {
        reporter.sendMessage("[yellow]该惩罚已被处理过，无需重复检举。")
        return false
    }
    if (VoteEvent.current() != null) {
        reporter.sendMessage("[yellow]当前已有其他投票进行中，请稍后再发起检举。")
        return false
    }
    val now = System.currentTimeMillis()
    if (activeReportTargets.putIfAbsent(ev.targetUuid, now) != null) {
        reporter.sendMessage("[yellow]你的检举投票已在处理中，请稍候。")
        return false
    }
    try {
        val rate = passRatePercent.coerceIn(1, 100)
        val strikes = operatorStrikeCount(ev)
        val extDesc = buildString {
            append("[red]被惩罚者: [white]${ev.targetName}\n")
            append("[red]惩罚类型: [white]${kindDisplay(ev.kind)}\n")
            append("[red]惩罚原因: [white]${ev.reason}\n")
            if (ev.fromVote) append("[red]检举理由: [white]$reportReason\n")
            append("[yellow]检举说明: [white]本检举投票的发起者认为对方滥用权力，如果检举成功会禁止其一天使用权力。\n")
            append("[yellow]此人已被成功检举了 [white]${strikes}[yellow] 次。\n")
            append("[gray]${ruleSummaryText()}\n")
            append("[gray]通过后：解除该惩罚，并按规则封禁被检举者的相应权限。")
        }
        val event = VoteEvent(
            thisScript, reporter,
            voteDesc = "检举限制惩罚(被检举者[red]{operator}[yellow])".with(
                "operator" to (ev.operatorName ?: "未知玩家")
            ),
            extDesc = extDesc,
            // 被检举者若在线不得参与投票；其余在线玩家可投（同 IP 只计一票由投票框架保证）。
            canVote = { p -> !isSameAccount(p, operatorUid) },
            requireNum = { all -> kotlin.math.ceil(all * rate / 100.0).toInt().coerceAtLeast(1) },
            guestForbidden = true,
            // 被罚者可能正被强制观战（voteOb 注册了 denyVotePredicate），本人必须能投/能发起。
            bypassDenyVote = { it.uuid() == reporter.uuid() },
            // 自救入口：即使被管理员“禁止发起投票”也允许发起检举。
            ignoreStartBan = true,
        )
        if (!event.awaitResult()) {
            reporter.sendMessage("[yellow]检举投票未通过，惩罚继续有效。")
            return false
        }
        applyReportSuccess(ev, operatorUid)
        return true
    } catch (e: Exception) {
        logger.warning("发起检举投票失败: ${e.message}")
        reporter.sendMessage("[red]检举投票发起失败，请稍后再试。")
        return false
    } finally {
        activeReportTargets.remove(ev.targetUuid)
    }
}

private fun applyReportSuccess(ev: PunishmentAppliedEvent, operatorUid: String) {
    runCatching {
        handledRecords[ev.recordId] = System.currentTimeMillis()
        // 1) 解罚：广播事件，由 playerMute / voteOb / playerBuildBan 各自清理自己的状态。
        launch {
            runCatching {
                PunishmentLiftedEvent(
                    kind = ev.kind,
                    recordId = ev.recordId,
                    targetUuid = ev.targetUuid,
                    targetUids = ev.targetUids,
                    targetName = ev.targetName,
                    reason = "检举投票通过",
                ).emitAsync()
            }.onFailure { logger.warning("发送解罚事件失败: ${it.message}") }
        }
        // 2) 施罚者累计成功检举 +1，按规则封禁权限（投票来源会额外封“发起限制类投票”）。
        val strike = runCatching {
            with(playerMute) {
                if (ev.fromVote) {
                    applyReportStrikeForVote(operatorUid, ev.operatorName ?: operatorUid, ev.operatorUids)
                } else {
                    applyReportStrike(operatorUid, ev.operatorName ?: operatorUid, ev.operatorUids)
                }
            }
        }.getOrNull()
        val banHours = runCatching { with(playerMute) { reportBanHoursValue() } }.getOrNull() ?: 24
        val voteBanHours = runCatching { with(playerMute) { reportVoteStartBanHoursValue() } }.getOrNull() ?: 24
        val banParts = mutableListOf<String>()
        if (strike != null) {
            banParts += if (strike.permanent) "永久禁止其直接限制他人"
            else "禁止其在 ${banHours} 小时内直接限制他人"
            if (strike.voteStartBanned) {
                banParts += if (strike.voteStartPermanent) "永久禁止其发起限制类投票"
                else "额外禁止其在 ${voteBanHours} 小时内发起限制类投票"
            }
        }
        val banText = banParts.joinToString("；").ifBlank { "按规则封禁其相应权限" }
        broadcast(
            "[yellow][检举] [white]{target}[yellow] 的检举投票通过：已解除其{vkind}惩罚；[white]{operator}[yellow] ${banText}（累计被检举成功 {count} 次）。"
                .with(
                    "target" to ev.targetName,
                    "vkind" to kindDisplay(ev.kind),
                    "operator" to (ev.operatorName ?: "未知玩家"),
                    "count" to (strike?.strikeCount ?: 1),
                )
        )
        // 3) 通知被检举者（在线时）。
        if (strike != null) {
            val operatorOnline = runCatching {
                with(playerMute) { findOnlinePlayerByUids(ev.operatorUids + operatorUid) }
            }.getOrNull()
            operatorOnline?.sendMessage(
                buildString {
                    append("[red]你因被检举成功（${kindDisplay(ev.kind)}惩罚）")
                    if (strike.permanent) append("，已被永久禁止直接限制他人。")
                    else append("，已被禁止在 ${banHours} 小时内直接限制他人。")
                    if (strike.voteStartBanned) {
                        if (strike.voteStartPermanent) append("并被永久禁止发起限制类投票。")
                        else append("并被额外禁止在 ${voteBanHours} 小时内发起限制类投票。")
                    }
                    append("（累计被成功检举 ${strike.strikeCount} 次）")
                }
            )
        }
    }.onFailure { logger.warning("处理检举通过结果失败: ${it.message}") }
}

// ---------- 供 UI / 其它脚本使用的公开查询（task-27 菜单/教程可直接用；全部内存查询） ----------

/**
 * 玩家的检举/封禁状态快照。
 * UI 用它显示“累计被成功检举次数”，并据 canDirectRestrict/canStartRestrictVote 决定按钮显隐/禁用。
 */
data class ReportPermissionStatus(
    val strikeCount: Int,
    /** 直接限制权封禁剩余毫秒；0=未封；Long.MAX_VALUE=永久。 */
    val directBanLeftMillis: Long,
    val directPermanent: Boolean,
    /** 发起限制类投票权封禁剩余毫秒；0=未封；Long.MAX_VALUE=永久。 */
    val voteStartBanLeftMillis: Long,
    val voteStartPermanent: Boolean,
    /** 直接限制封禁的可读文案（“无”/“剩余 X”/“永久”）。 */
    val directBanText: String,
    /** 发起限制类投票封禁的可读文案（“无”/“剩余 X”/“永久”）。 */
    val voteStartBanText: String,
) {
    val canDirectRestrict: Boolean get() = directBanLeftMillis <= 0L
    val canStartRestrictVote: Boolean get() = voteStartBanLeftMillis <= 0L
}

private fun banTextOf(leftMillis: Long, permanent: Boolean): String = when {
    permanent -> "永久"
    leftMillis <= 0L -> "无"
    else -> formatLeft(leftMillis)
}

fun reportPermissionStatus(player: Player): ReportPermissionStatus = reportPermissionStatus(playerUids(player))

fun reportPermissionStatus(uids: Iterable<String>): ReportPermissionStatus = runCatching {
    val strike = with(playerMute) { strikeCount(uids) }
    val directLeft = with(playerMute) { directRestrictBanLeftMillis(uids) }
    val directPermanent = with(playerMute) { isDirectRestrictPermanentlyBanned(uids) }
    val voteLeft = with(playerMute) { restrictVoteStartBanLeftMillis(uids) }
    val votePermanent = with(playerMute) { isRestrictVoteStartPermanentlyBanned(uids) }
    ReportPermissionStatus(
        strikeCount = strike,
        directBanLeftMillis = directLeft,
        directPermanent = directPermanent,
        voteStartBanLeftMillis = voteLeft,
        voteStartPermanent = votePermanent,
        directBanText = banTextOf(directLeft, directPermanent),
        voteStartBanText = banTextOf(voteLeft, votePermanent),
    )
}.getOrNull() ?: ReportPermissionStatus(0, 0L, false, 0L, false, "无", "无")

/** UI 显示的简版检举/封禁状态文本（换行分隔）。 */
fun permissionStatusText(player: Player): String {
    val status = reportPermissionStatus(player)
    val directText = if (status.canDirectRestrict) "[green]可用"
    else "[red]被封禁（${status.directBanText}）"
    val voteText = if (status.canStartRestrictVote) "[green]可用"
    else "[red]被封禁（${status.voteStartBanText}）"
    return "[cyan]累计被成功检举：[yellow]${status.strikeCount} 次\n" +
            "[white]直接限制权限：$directText\n" +
            "[white]发起限制类投票：$voteText"
}

/** 当前检举封禁规则说明（UI/教程可直接引用，数值随配置实时变化）。 */
fun punishmentRuleSummaryText(): String = ruleSummaryText()

// ---------- 管理命令 / 玩家自救入口 ----------

private fun resolveUids(text: String): List<String> {
    val fixed = text.trim()
    if (fixed.isEmpty()) return emptyList()
    Groups.player.find {
        it.uuid() == fixed || it.name.equals(fixed, true) || it.plainName().equals(fixed, true)
    }?.let { return (PlayerData[it].ids + PlayerData[it].id).toList() }
    PlayerData.findByShortId(fixed)?.let { return (it.ids + it.id).toList() }
    return listOf(fixed)
}

private fun bansText(): String {
    val bans = runCatching { with(playerMute) { activeRestrictionBans() } }.getOrNull() ?: emptyList()
    if (bans.isEmpty()) return "[green]当前没有被检举封禁（限制性权限）的玩家。"
    val now = System.currentTimeMillis()
    return buildString {
        append("[cyan]当前被检举封禁的玩家：${bans.size} 人\n")
        bans.forEach { ban ->
            append("[white]${ban.name}[gray](${ban.uid}) ")
            append("[gray]直接限制: ")
            append(
                when {
                    ban.permanent -> "[red]永久"
                    !ban.active(now) -> "[gray]无"
                    else -> "[yellow]剩余 ${formatLeft(ban.untilMillis - now)}"
                }
            )
            append("[gray] / 发起限制类投票: ")
            append(
                when {
                    ban.voteStartPermanent -> "[red]永久"
                    !ban.voteStartActive(now) -> "[gray]无"
                    else -> "[yellow]剩余 ${formatLeft(ban.voteStartUntilMillis - now)}"
                }
            )
            append("[gray] 累计被检举成功 ${ban.strikeCount} 次\n")
        }
    }
}

private fun statusText(text: String): String {
    val uids = resolveUids(text)
    val ban = runCatching { with(playerMute) { restrictionBanInfo(uids) } }.getOrNull()
    val strikes = ban?.strikeCount ?: 0
    val now = System.currentTimeMillis()
    val directActive = ban != null && ban.active(now)
    val voteActive = ban != null && ban.voteStartActive(now)
    val directLeft = (ban?.untilMillis ?: 0L) - now
    val voteLeft = (ban?.voteStartUntilMillis ?: 0L) - now
    return buildString {
        append("[cyan]检举状态 [white]${text}[gray]：\n")
        append("[white]累计被成功检举：[yellow]${strikes} 次\n")
        append("[white]直接限制权限：")
        append(
            when {
                directActive && ban!!.permanent -> "[red]永久封禁"
                directActive -> "[red]封禁中（剩余 ${formatLeft(directLeft)}）"
                else -> "[green]正常"
            }
        )
        append("\n[white]发起限制类投票：")
        append(
            when {
                voteActive && ban!!.voteStartPermanent -> "[red]永久封禁"
                voteActive -> "[red]封禁中（剩余 ${formatLeft(voteLeft)}）"
                else -> "[green]正常"
            }
        )
        append("\n[gray]规则：${ruleSummaryText()}")
    }
}

private fun resetText(text: String): String {
    val uids = resolveUids(text)
    val removed = runCatching { with(playerMute) { clearRestrictionBan(uids) } }.getOrNull() ?: false
    return if (removed) "[green]已清除 [white]${text}[green] 的检举封禁记录与成功检举次数。"
    else "[yellow]没有找到 [white]${text}[yellow] 的检举封禁记录。"
}

private suspend fun reportLatestPunishment(reporter: Player) {
    val stored = lastPunishments[reporter.uuid()]
    if (stored == null) {
        reporter.sendMessage("[yellow]最近没有可检举的惩罚记录：只有被管理员层级以下（低于 3++）的玩家施加限制性惩罚时才能检举。")
        return
    }
    val validMillis = contextValidMinutes.coerceAtLeast(1).toLong() * 60_000L
    if (System.currentTimeMillis() - stored.appliedAt > validMillis) {
        reporter.sendMessage("[yellow]最近一次惩罚已超过 ${contextValidMinutes} 分钟，无法再通过命令发起检举。")
        return
    }
    val ev = stored.event
    val reportReason = if (ev.fromVote) {
        askVoteReportReason(reporter) ?: return
    } else {
        "认为该直接限制不合理"
    }
    startReportVote(reporter, ev, reportReason)
}

command("punishreport", "惩罚检举：玩家检举最近一次限制性惩罚；协管查看/清理检举封禁") {
    usage = "[report] | bans | status <玩家> | reset <玩家>"
    aliases = listOf("检举", "jubao", "reportpunish")
    body {
        when (val op = arg.getOrNull(0)?.lowercase() ?: "report") {
            "report", "检举", "发起" -> {
                val reporter = player
                    ?: returnReply("[red]玩家检举需要在游戏内执行；控制台可用 bans/status/reset。".with())
                reportLatestPunishment(reporter)
            }

            "bans", "list", "列表", "封禁列表" -> {
                if (!hasStaffAccess(player)) returnReply("[red]权限不足：需要 3++ 协管及以上。".with())
                reply(bansText().with())
            }

            "status", "状态", "info" -> {
                if (!hasStaffAccess(player)) returnReply("[red]权限不足：需要 3++ 协管及以上。".with())
                val target = arg.getOrNull(1) ?: returnReply("[yellow]用法：/punishreport status <玩家名/uid>".with())
                reply(statusText(target).with())
            }

            "reset", "清除", "unban" -> {
                if (!hasAdminAccess(player)) returnReply("[red]权限不足：只有 4 级管理员可以清除检举封禁。".with())
                val target = arg.getOrNull(1) ?: returnReply("[yellow]用法：/punishreport reset <玩家名/uid>".with())
                reply(resetText(target).with())
            }

            else -> replyUsage()
        }
    }
}

listen<EventType.PlayerLeave> {
    val uuid = it.player.uuid()
    lastPunishments.remove(uuid)
    lastPopupAt.remove(uuid)
}
