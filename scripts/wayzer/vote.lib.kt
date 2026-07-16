package wayzer

import cf.wayzer.scriptAgent.Event
import cf.wayzer.scriptAgent.define.Script
import cf.wayzer.scriptAgent.define.ScriptDsl
import cf.wayzer.scriptAgent.emitAsync
import cf.wayzer.scriptAgent.thisContextScript
import coreLibrary.lib.*
import coreMindustry.MenuBuilder
import coreMindustry.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.PlayerData
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

@Suppress("MemberVisibilityCanBePrivate")
class VoteEvent(
    scope: CoroutineScope,
    val starter: Player,
    val voteDesc: PlaceHoldContext,
    val extDesc: String = "",
    val supportSingle: Boolean = false,
    val canVote: (Player) -> Boolean = { true },
    val requireNum: (all: Int) -> Int = { ceil(it * 0.5).toInt() },
    val fastSuccess: Boolean = true,
    val bypassDenyVote: (Player) -> Boolean = { false },
) : Event, Event.Cancellable {
    enum class Action { Agree, Disagree, Ignore, Quit, Join }

    val voted = mutableMapOf<Player, Boolean?>()
    var succeed = false
    val endTime: Instant = Instant.now() + voteTime
    private val starterGuest = !PlayerData[starter].authed
    private val starterCoolDownKeys = coolDownKeys(starter)
    private var starterUnlimitedVote = false
    private var failureCoolDownMarked = false
    override var cancelled
        get() = !mainJob.isActive
        set(value) {
            if (value) mainJob.cancel()
        }

    private fun markFailureCoolDownOnce() {
        if (starterUnlimitedVote) return
        if (failureCoolDownMarked) return
        failureCoolDownMarked = true
        markStartCoolDown(starterCoolDownKeys, starterGuest)
    }

    suspend fun awaitResult(): Boolean {
        mainJob.join()
        return succeed
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val mainJob = scope.launch(Dispatchers.game + CoroutineName("Vote Service"), CoroutineStart.LAZY) main@{
        startBlockReason(starter)?.let { reason ->
            starter.sendMessage(reason)
            return@main
        }
        starterUnlimitedVote = starter.hasPermission(unlimitedVotePermission)
        if (!starterUnlimitedVote) {
            startCoolDown(starter)?.let { (reason, until) ->
                starter.sendMessage(
                    "[yellow]{reason}发起的投票失败，投票发起冷却中，剩余 [gold]{left}[]".with(
                        "reason" to reason,
                        "left" to formatLeft(until - System.currentTimeMillis())
                    )
                )
                return@main
            }
        }
        emitAsync {
            if (!canVoteNow(starter)) {
                starter.sendMessage("[red]你当前不能参与此投票，无法发起。".with())
                return@emitAsync
            }
            if (supportSingle && allCanVote().run { isEmpty() || singleOrNull() == starter }) {
                if (System.currentTimeMillis() - lastAction > 60_000) {
                    broadcast("[yellow]单人快速投票{type}成功".with("type" to voteDesc))
                    lastAction = System.currentTimeMillis()
                    succeed = true
                    return@emitAsync
                } else broadcast("[red]距离上一玩家离开或上一投票成功不足1分钟,快速投票失败".with())
            }
            if (!active.compareAndSet(null, this@VoteEvent)) {
                return@emitAsync cancel()
            }
            launch {
                try {
                    awaitCancellation()
                } finally {
                    active.compareAndSet(this@VoteEvent, null)
                }
            }
            //文字投票
            val delayTip = if (menuDelay <= 0) "".asPlaceHoldString()
            else "\n[green] (若未投票,{delay}秒后将弹窗提示)".with("delay" to menuDelay)
            val tip = """
                [gold]【投票】[white]{starter.name}[yellow] 发起了 {type}[yellow] 投票
                {ext}
                {requirement}
                [gray]文字投票：[green]赞成(y/1)[] [gray]中立(.)[] [red]反对(n/0)[] {delayTip}
                """.trimIndent()
                .with("starter" to starter, "type" to voteDesc, "ext" to extDesc, "requirement" to requirementInfo(), "delayTip" to delayTip)
            broadcast(tip)

            broadcast(tip, type = MsgType.Announce, players = allCanVote(), quite = true)
            vote(starter, Action.Agree)
            //弹窗投票
            if (menuDelay >= 0) allCanVote().forEach {
                launch(Dispatchers.game) {
                    delay(menuDelay * 1000L)
                    if (it in voted) return@launch
                    openMenu(it)
                }
            }
            //投票超时处理
            val actionHandler = launch { actionHandler() }
            select {
                actionHandler.onJoin {}
                onTimeout(voteTime.toMillis()) {
                    actionHandler.cancel()
                    withCheckVoted {
                        val minVoteNum = allCanVoteIpCount() / 2
                        if (effectiveVotes().size < minVoteNum && agree() < requireNum(minVoteNum)) {
                            broadcast("[yellow]投票参与人数过少".with())
                        } else {
                            succeed = agree() >= requireNum(agree() + disagree()).coerceAtLeast(1)
                        }
                    }
                }
            }

            if (!succeed) markFailureCoolDownOnce()
            val t = if (succeed) "[yellow]{starter.name}[yellow]发起{type}[yellow]投票成功. {status}"
            else "[yellow]{starter.name}[yellow]发起{type}[yellow]投票失败. {status}"
            broadcast(t.with("starter" to starter, "type" to voteDesc, "status" to status()))
        }
        coroutineContext.cancelChildren()
    }

    private fun canVoteNow(player: Player): Boolean =
        runCatching { canVote(player) }.getOrDefault(false) &&
                (runCatching { bypassDenyVote(player) }.getOrDefault(false) ||
                        runCatching { !denyVotePredicates.values.any { it(player) } }.getOrDefault(false))

    fun allCanVote() = Groups.player.filter(::canVoteNow)

    private fun voteIp(player: Player): String = normalizeVoteIp(player)

    private fun allCanVoteIpCount() = allCanVote().map { voteIp(it) }.distinct().size

    private fun effectiveVotes(): Map<String, Boolean?> {
        val result = linkedMapOf<String, Boolean?>()
        voted.forEach { (player, value) ->
            if (canVoteNow(player)) result[voteIp(player)] = value
        }
        return result
    }

    fun agree() = effectiveVotes().count { it.value == true }
    fun middle() = effectiveVotes().count { it.value == null }
    fun disagree() = effectiveVotes().count { it.value == false }
    fun notVote() = (allCanVoteIpCount() - effectiveVotes().size).coerceAtLeast(0)
    fun status() = withCheckVoted {
        "[green]\uE804${agree()} [yellow]\uE853${middle()} [red]\uE805${disagree()} [grey]\uE88F${notVote()}"
    }

    fun requirementInfo() = withCheckVoted {
        val nonNeutral = (agree() + disagree()).coerceAtLeast(1)
        val requiredNow = requireNum(nonNeutral).coerceAtLeast(1)
        val all = allCanVoteIpCount().coerceAtLeast(1)
        val requiredAll = requireNum(all).coerceAtLeast(1)
        "[cyan]通过要求：[white]当前赞/反票需 [green]$requiredNow[white]/$nonNeutral 赞成[gray]（全员参与约 $requiredAll/$all；中立票不计入赞反）"
    }

    inline fun <T> withCheckVoted(body: () -> T): T {
        return body()
    }

    fun vote(p: Player, action: Action) {
        handleAction.trySend(p to action)
    }

    suspend fun openMenu(p: Player) {
        MenuBuilder<Unit>("[gold]投票确认") {
            val extra = extDesc.toString().takeIf { it.isNotBlank() }
                ?.let { "\n\n[accent]【投票说明】[]\n$it" }
                ?: ""
            msg = """
                |[accent]【投票信息】
                |[cyan]发起者：[white]{starter.name}
                |[cyan]类型：[gold]{type}
                |
                |[accent]【当前票况】
                |{status}
                |{requirement}
                |
                |[gray]同 IP 只计一票，以该 IP 最后一次投票为准。
                |$extra
            """.trimMargin().with(
                "starter" to starter,
                "type" to voteDesc,
                "status" to status(),
                "requirement" to requirementInfo(),
            ).toPlayer(p)
            option("[green]赞成\n[gray]支持该投票") { vote(p, Action.Agree) }
            option("[gray]中立\n[gray]不计入赞成/反对") { vote(p, Action.Ignore) }
            option("[red]反对\n[gray]反对此投票") { vote(p, Action.Disagree) }
            newRow()
            option("[gray]待定\n[gray]20秒后再次提示") {
                p.sendMessage("[yellow]将在20秒后再次提示投票菜单")
                script.launch(Dispatchers.game) {
                    delay(20_000)
                    if (active.get() == this@VoteEvent && p !in voted) openMenu(p)
                }
            }
            option("[gray]关闭") {}
        }.sendTo(p, 60_000)
    }

    private val handleAction = Channel<Pair<Player, Action>>(Channel.UNLIMITED)
    private suspend fun actionHandler() = coroutineScope {
        for ((player, event) in handleAction) {
            when (event) {
                Action.Join -> if (canVoteNow(player)) {
                    launch(Dispatchers.gamePost) { openMenu(player) }
                }

                Action.Quit -> voted.remove(player)
                Action.Agree, Action.Disagree, Action.Ignore -> {
                    if (!canVoteNow(player)) {
                        player.sendMessage("[red]你不能对此投票".with())
                        continue
                    }
                    // mutableMapOf 在 JVM 下保持插入顺序；同一玩家改票时先移除再写入，
                    // 让 effectiveVotes 按“同IP最后一次投票”为准。
                    voted.remove(player)
                    voted[player] = when (event) {
                        Action.Agree -> true
                        Action.Disagree -> false
                        else -> null
                    }
                    val sameIpOthers = voted.keys.count { it != player && voteIp(it) == voteIp(player) }
                    player.sendMessage(
                        if (sameIpOthers > 0)
                            "[green]投票成功[gray]（同IP只计一票，以该IP最后一次投票为准）".with()
                        else "[green]投票成功".with()
                    )
                }
            }

            //fast path
            withCheckVoted {
                val all = allCanVoteIpCount() - middle()
                val required = this@VoteEvent.requireNum(all).coerceAtLeast(1)
                when {
                    fastSuccess && agree() >= required -> {
                        succeed = true;return@coroutineScope
                    }

                    all - disagree() < required -> {
                        succeed = false;return@coroutineScope
                    }

                    else -> {}
                }
            }
        }
        handleAction.close()
    }

    init {
        mainJob.start()
    }

    /**
     * 一票否决当前投票。
     *
     * 该能力只负责让当前 VoteEvent 以失败结果结束；权限/等级判断由调用脚本处理。
     */
    fun vetoBy(operator: Player, reason: String = ""): Boolean {
        if (cancelled) return false
        succeed = false
        markFailureCoolDownOnce()
        val reasonText = reason.takeIf { it.isNotBlank() }?.let { "[yellow] 原因: [white]$it" } ?: ""
        broadcast(
            "[red]{operator.name}[red] 使用一票否决权，否决了当前{type}[red]投票。{reason}"
                .with("operator" to operator, "type" to voteDesc, "reason" to reasonText)
        )
        mainJob.cancel()
        return true
    }

    object VoteCommands : Commands()

    companion object : Event.Handler() {
        internal val script = thisContextScript()
        private val voteTime by script.config.key(Duration.ofSeconds(60)!!, "投票时间")
        private val voteCoolDown by script.config.key(Duration.ofMinutes(5)!!, "投票失败冷却时间")
        private val guestVoteCoolDown by script.config.key(Duration.ofMinutes(30)!!, "游客投票失败冷却时间")
        private val menuDelay by script.config.key(20, "弹窗投票显示时间,单位秒", "0为立即显示，-1纯文字投票")
        private val ipv4Regex = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
        internal const val unlimitedVotePermission = "wayzer.admin.voteUnlimited"

        internal val active = AtomicReference<VoteEvent?>(null)
        internal var lastAction = 0L //最后一次玩家退出或投票成功时间,用于处理单人投票
        internal val coolDowns = mutableMapOf<String, Long>()
        private val denyVotePredicates = linkedMapOf<String, (Player) -> Boolean>()
        private val startBlockers = linkedMapOf<String, (Player) -> String?>()

        fun current(): VoteEvent? = active.get()

        fun vetoCurrent(operator: Player, reason: String = ""): Boolean =
            active.get()?.vetoBy(operator, reason) ?: false

        fun registerDenyVotePredicate(id: String, predicate: (Player) -> Boolean) {
            denyVotePredicates[id] = predicate
        }

        fun unregisterDenyVotePredicate(id: String) {
            denyVotePredicates.remove(id)
        }

        fun registerStartBlocker(id: String, blocker: (Player) -> String?) {
            startBlockers[id] = blocker
        }

        fun unregisterStartBlocker(id: String) {
            startBlockers.remove(id)
        }

        fun startBlockReason(player: Player): String? =
            startBlockers.values.firstNotNullOfOrNull { blocker ->
                runCatching { blocker(player) }.getOrNull()
            }

        private fun normalizeVoteIp(player: Player): String =
            player.con?.address
                ?.let { ipv4Regex.find(it)?.value ?: it.trim().substringBefore('%') }
                ?.takeIf { it.isNotBlank() }
                ?: "unknown:${player.uuid()}"

        internal fun coolDownKeys(player: Player): List<Pair<String, String>> =
            listOf(
                "你" to "uuid:${player.uuid()}",
                "同IP玩家" to "ip:${normalizeVoteIp(player)}"
            )

        private fun cleanExpiredCoolDowns(now: Long = System.currentTimeMillis()) {
            coolDowns.entries.removeIf { it.value <= now }
        }

        internal fun startCoolDown(player: Player): Pair<String, Long>? {
            val now = System.currentTimeMillis()
            cleanExpiredCoolDowns(now)
            return coolDownKeys(player)
                .mapNotNull { (reason, key) -> coolDowns[key]?.takeIf { it > now }?.let { reason to it } }
                .maxByOrNull { it.second }
        }

        internal fun markStartCoolDown(keys: List<Pair<String, String>>, isGuest: Boolean) {
            val duration = if (isGuest) guestVoteCoolDown else voteCoolDown
            val until = System.currentTimeMillis() + duration.toMillis()
            keys.forEach { (_, key) -> coolDowns[key] = until }
        }

        internal fun markStartCoolDown(player: Player, isGuest: Boolean = !PlayerData[player].authed) {
            markStartCoolDown(coolDownKeys(player), isGuest)
        }

        private fun formatLeft(millis: Long): String {
            val totalSeconds = (millis / 1000).coerceAtLeast(1)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return when {
                minutes > 0 && seconds > 0 -> "${minutes}分${seconds}秒"
                minutes > 0 -> "${minutes}分钟"
                else -> "${seconds}秒"
            }
        }
    }
}

@Deprecated("use VoteEvent")
object VoteService {
    private fun requirementHint(primaryAlias: String): String = when (primaryAlias.lowercase()) {
        "cp", "externalcp", "ecp" -> "[gray]（需70%同意）"
        "map", "nextmap" -> "[gray]（需51%同意）"
        "gameover" -> "[gray]（默认50%；PVP同队80%）"
        "clear" -> "[gray]（同队40%）"
        else -> "[gray]（需50%同意）"
    }

    private fun coloredVoteUsage(usage: String): String = if (usage.isBlank()) usage else "[yellow]$usage[]"

    private fun coloredVoteDesc(primaryAlias: String, desc: String): String {
        val color = when (primaryAlias.lowercase()) {
            "gameover" -> "[red]"
            "map", "nextmap", "rollback", "save" -> "[green]"
            "kick" -> "[red]"
            "ob", "quitob" -> "[cyan]"
            "skipwave" -> "[yellow]"
            "pausewave", "setwave", "resumewave", "unpausewave" -> "[yellow]"
            "clear" -> "[cyan]"
            "text" -> "[lightgray]"
            "killunits" -> "[red]"
            "infinitefire" -> "[orange]"
            "infinitefirepromax" -> "[scarlet]"
            "reactor" -> "[orange]"
            "pure" -> "[cyan]"
            "pureoff" -> "[yellow]"
            "cp" -> "[purple]"
            else -> "[white]"
        }
        return "$color$desc${requirementHint(primaryAlias)}"
    }

    @ScriptDsl
    fun Script.addSubVote(
        desc: String, usage: String, vararg aliases: String, body: suspend CommandContext.() -> Unit
    ) {
        VoteEvent.VoteCommands += CommandInfo(this, aliases.first(), coloredVoteDesc(aliases.first(), desc).with()) {
            this.usage = coloredVoteUsage(usage)
            this.aliases = aliases.toList()
            body(body)
            if (permission.isEmpty()) permission = "wayzer.vote." + aliases.first().lowercase()
        }
    }

    fun start(
        starter: Player,
        voteDesc: PlaceHoldContext,
        extDesc: String = "",
        supportSingle: Boolean = false,
        canVote: (Player) -> Boolean = { true },
        requireNum: (all: Int) -> Int = { ceil(it * .5).toInt() },
        fastSuccess: Boolean = true,
        onSuccess: suspend (Map<Player, Boolean?>) -> Unit
    ) {
        VoteEvent.script.launch(Dispatchers.game) {
            val event = VoteEvent(this, starter, voteDesc, extDesc, supportSingle, canVote, requireNum, fastSuccess)
            if (event.awaitResult()) onSuccess(event.voted)
        }
    }
}
