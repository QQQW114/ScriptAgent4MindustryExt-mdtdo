@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/user/trustLevel", "信任等级")

package wayzer.cmds

import arc.Events
import arc.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mindustry.game.EventType.GameOverEvent
import mindustry.gen.Call
import mindustry.gen.Player
import wayzer.VoteService
import wayzer.lib.PlayerData
import wayzer.user.TrustLevel
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

private data class WavePauseSnapshot(
    val waveTimer: Boolean,
    val waveSending: Boolean,
    /** 暂停前的 wavetime（原始值，恢复目标）。 */
    val wavetime: Float,
    /** 暂停时由我们塞进去的 wavetime；恢复时只有当当前值仍等于它才回退。 */
    val pushedWavetime: Float,
)

private var wavePauseSnapshot: WavePauseSnapshot? = null
private var wavePauseToken = 0
private val superChatCooldowns = mutableMapOf<String, Long>()
private val trustLevel = contextScript<TrustLevel>()

/** SuperChat 中屏显示秒数上限（用户要求最多 5 秒）。注意：脚本顶层不允许 const。 */
private val MAX_SUPER_CHAT_SECONDS = 5f

/** SuperChat 中屏文本刷新间隔：小于客户端淡出速度即可保持常显。 */
private val SUPER_CHAT_REFRESH_MILLIS = 250L

/** 并发令牌：只允许最新的 SC 维持中屏文本。 */
private var superChatToken = 0

private fun sanitizeSuperChatText(text: String): String =
    text.replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .take(160)

private fun superChatCooldownLeft(player: Player): Long {
    val key = PlayerData[player].id
    val until = superChatCooldowns[key] ?: return 0L
    val left = (until - System.currentTimeMillis()).coerceAtLeast(0L)
    if (left <= 0L) superChatCooldowns.remove(key)
    return left
}

private fun markSuperChatCooldown(player: Player) {
    superChatCooldowns[PlayerData[player].id] = System.currentTimeMillis() + 120_000L
}

/**
 * 解析 `/vote sc [文字] [秒数]` 参数：
 * - 末位参数是纯数字时视为"中屏显示秒数"，其余部分为文字；
 * - 秒数上限 5 秒，缺省 3 秒；小于 1 秒按 1 秒处理。
 */
private fun parseSuperChatArgs(args: List<String>): Pair<String, Float> {
    val defaultSeconds = 3f
    if (args.isEmpty()) return "" to defaultSeconds
    val last = args.last().trim()
    val seconds = last.toFloatOrNull()
    // 只把"末位纯数字"当秒数，避免把正文里的数字（如 "1v1"、"2026"）误判；
    // 同时要求秒数在合理范围内，超出则按上限截断而不是当成正文。
    if (seconds != null && args.size >= 2 && seconds > 0f) {
        val text = args.dropLast(1).joinToString(" ")
        return text to seconds.coerceIn(1f, MAX_SUPER_CHAT_SECONDS)
    }
    return args.joinToString(" ") to defaultSeconds
}

/**
 * SuperChat 中屏显示：
 * 原版 `Call.announce` 的显示时长由客户端控制，服务端无法指定，因此这里改用
 * `Call.setHudText` 主动维持：每 250ms 刷新一次（客户端有淡入淡出插值，间隔刷新可保持常显），
 * 到达指定秒数后 `Call.hideHudText()` 收起。上限 5 秒，避免长时间占用屏幕中央。
 */
private fun sendSuperChat(player: Player, text: String, seconds: Float) {
    val safe = sanitizeSuperChatText(text)
    val display = """
        |[gold]✦ SuperChat ✦
        |[cyan]${player.plainName()}[white]：
        |[yellow]$safe
    """.trimMargin()
    val durationMillis = (seconds.coerceIn(1f, MAX_SUPER_CHAT_SECONDS) * 1000f).toLong()
    val token = ++superChatToken
    Call.announce(display)
    Call.sendMessage("[gold][SC][cyan] ${player.name}[white]：[yellow]$safe")
    logger.info("[SuperChat] ${player.plainName()}: $safe（中屏 ${durationMillis / 1000f} 秒）")
    launch(Dispatchers.game) {
        val deadline = System.currentTimeMillis() + durationMillis
        while (System.currentTimeMillis() < deadline) {
            Call.setHudText(display)
            delay(SUPER_CHAT_REFRESH_MILLIS)
            // 有更新的 SC 时让位给它，旧任务自行退出，避免互相刷屏。
            if (token != superChatToken) return@launch
        }
        if (token == superChatToken) {
            runCatching { Call.hideHudText() }
                .onFailure { logger.warning("SuperChat 收起中屏文本失败: $it") }
        }
    }
}

private fun pauseWaves(durationMillis: Long, operatorName: String) {
    val durationTicks = durationMillis / 1000f * 60f
    val token = ++wavePauseToken
    if (wavePauseSnapshot == null) {
        wavePauseSnapshot = WavePauseSnapshot(
            waveTimer = state.rules.waveTimer,
            waveSending = state.rules.waveSending,
            wavetime = state.wavetime,
            // 记录"本次暂停把 wavetime 推到了多少"，恢复时用它判断这段时间有没有被别的来源改过。
            pushedWavetime = max(state.wavetime, durationTicks),
        )
    }

    state.rules.waveTimer = false
    state.rules.waveSending = false
    state.wavetime = max(state.wavetime, durationTicks)
    Call.setRules(state.rules)
    broadcast("[yellow]投票已通过：[white]$operatorName[yellow] 暂停波次计时 [gold]${durationMillis / 1000}[yellow] 秒。".with())

    launch(Dispatchers.game) {
        delay(durationMillis)
        if (token != wavePauseToken) return@launch
        val snapshot = wavePauseSnapshot ?: return@launch
        state.rules.waveTimer = snapshot.waveTimer
        state.rules.waveSending = snapshot.waveSending
        restorePausedWaveTime(snapshot)
        Call.setRules(state.rules)
        wavePauseSnapshot = null
        broadcast("[green]波次计时暂停已结束，已恢复暂停前波次规则。".with())
    }
}

private fun resumePausedWaves(operatorName: String): Boolean {
    val snapshot = wavePauseSnapshot ?: return false
    wavePauseToken++
    state.rules.waveTimer = snapshot.waveTimer
    state.rules.waveSending = snapshot.waveSending
    restorePausedWaveTime(snapshot)
    Call.setRules(state.rules)
    wavePauseSnapshot = null
    broadcast("[green]投票已通过：[white]$operatorName[green] 取消了当前波次暂停，已恢复暂停前波次规则。".with())
    return true
}

/**
 * 恢复暂停前的 wavetime。
 *
 * 旧写法是 `if (snapshot.wavetime > 0f && state.wavetime > snapshot.wavetime) ...`：
 * 当暂停前 wavetime 本来就是 0（合法的"马上出波"状态）时该守卫不成立，暂停结束后
 * wavetime 会停留在被推后的值上，表现为"波次间隔一直很久、无法复原"。
 * 现在改为哨兵判定：只有当前值仍等于暂停时我们塞进去的值，才回退到快照值（含 0）。
 */
private fun restorePausedWaveTime(snapshot: WavePauseSnapshot) {
    if (state.wavetime >= snapshot.pushedWavetime) {
        state.wavetime = snapshot.wavetime.coerceAtLeast(0f)
    }
}

private fun setCurrentWave(target: Int, operatorName: String) {
    val old = state.wave
    state.wave = target
    // 不强制立即出波，不清理已生成敌人；只调整服务端当前波次计数与后续刷波依据。
    broadcast("[yellow]投票已通过：[white]$operatorName[yellow] 将当前波次从 [gold]$old[yellow] 调整为 [gold]$target[yellow]。".with())
}

listen<EventType.ResetEvent> {
    wavePauseToken++
    wavePauseSnapshot = null
}

fun VoteService.register() {
    addSubVote("结束本局并结算", "", "gameOver", "投降", "结算") {
        // 部分特殊地图会把 canGameOver 置为 false 来阻止原版自动结算。
        // 投票投降是玩家主动结算入口，不再被该标记拦截；PVP 仍走本队投降逻辑，只摧毁本队核心。
        if (state.rules.pvp) {
            val team = player!!.team()
            if (!state.teams.isActive(team) || state.teams.get(team)!!.cores.isEmpty)
                returnReply("[red]队伍已输,无需投降".with())

            start(
                player!!, "投降({team.colorizeName}[yellow]队|要求80%同意)".with("player" to player!!, "team" to team),
                canVote = { it.team() == team }, requireNum = { ceil(it * 0.8).toInt() }
            ) {
                team.data().cores.toArray().forEach {
                    if (it.team == team) it.kill()
                }
            }
            return@addSubVote
        }
        start(player!!, "投降".with(), supportSingle = true) {
            player!!.team().cores().toArray().forEach { Time.run(Random.nextFloat() * 60 * 3, it::kill) }
            Events.fire(GameOverEvent(state.rules.waveTeam))
        }
    }
    addSubVote("快速出波（默认10，最多50）", "[波数]", "skipWave", "跳波") {
        if (Groups.player.any { it.team() == state.rules.waveTeam })
            returnReply("[red]当前模式禁止跳波".with())
        val lastResetTime by PlaceHold.reference<Instant>("state.startTime")
        val t = (arg.firstOrNull()?.toIntOrNull() ?: 10).coerceIn(1, 50)
        start(player!!, "跳波({t}波)".with("t" to t), supportSingle = true) {
            val startTime = Instant.now()
            repeat(t) {
                if (lastResetTime > startTime) return@start //Have change map
                val before = state.enemies
                logic.runWave()
                while (spawner.isSpawning) delay(1000L)
                val after = state.enemies
                while (state.enemies > max(before, (after - before) * 3 / 10)) {
                    delay(1000L)
                }
                delay(3000L)
            }
        }
    }
    addSubVote("暂停波次计时（默认300秒）", "[秒数]", "pauseWave", "暂停波次") {
        if (!state.rules.waves) returnReply("[red]当前地图未启用波次。".with())
        if (state.rules.pvp) returnReply("[red]PVP模式禁止投票暂停波次。".with())
        if (Groups.player.any { it.team() == state.rules.waveTeam })
            returnReply("[red]当前模式禁止调整波次".with())
        val seconds = (arg.firstOrNull()?.toLongOrNull() ?: 300L).coerceIn(10L, 1800L)
        start(
            player!!,
            "暂停波次(${seconds}秒)".with(),
            extDesc = """
                |[cyan]玩法分类：[white]波次控制
                |[yellow]通过后会临时暂停当前地图的波次计时/出波约 [white]${seconds}秒[yellow]。
                |[gray]此操作不清理已生成敌人，结束后恢复暂停前波次规则。
            """.trimMargin(),
            supportSingle = true,
            requireNum = { ceil(it * 0.7).toInt() }
        ) {
            pauseWaves(seconds * 1000L, player!!.plainName())
        }
    }
    addSubVote("调整当前波次", "<目标波次>", "setWave", "wave", "调整波次") {
        if (!state.rules.waves) returnReply("[red]当前地图未启用波次。".with())
        if (state.rules.pvp) returnReply("[red]PVP模式禁止投票调整波次。".with())
        if (Groups.player.any { it.team() == state.rules.waveTeam })
            returnReply("[red]当前模式禁止调整波次".with())
        val target = arg.firstOrNull()?.toIntOrNull()
            ?: returnReply("[red]用法：/vote setWave <目标波次>".with())
        val maxWave = maxOf(999, state.rules.winWave.takeIf { it > 0 } ?: 0)
        val fixed = target.coerceIn(1, maxWave)
        start(
            player!!,
            "调整波次(${state.wave}→${fixed})".with(),
            extDesc = """
                |[cyan]玩法分类：[white]波次控制
                |[yellow]通过后会把服务端当前波次计数调整到 [white]$fixed[yellow]。
                |[gray]此操作不立即出波、不清理已生成敌人；后续刷波会按新的波次继续。
            """.trimMargin(),
            supportSingle = true
        ) {
            setCurrentWave(fixed, player!!.plainName())
        }
    }
    addSubVote("恢复波次计时", "", "resumeWave", "unpauseWave", "取消暂停波次", "恢复波次") {
        if (!state.rules.waves) returnReply("[red]当前地图未启用波次。".with())
        if (state.rules.pvp) returnReply("[red]PVP模式禁止投票调整波次。".with())
        if (wavePauseSnapshot == null) returnReply("[yellow]当前没有正在生效的波次暂停。".with())
        start(
            player!!,
            "取消暂停波次".with(),
            extDesc = """
                |[cyan]玩法分类：[white]波次控制
                |[yellow]通过后会立即恢复暂停前的波次计时/出波规则。
            """.trimMargin(),
            supportSingle = true
        ) {
            resumePausedWaves(player!!.plainName())
        }
    }
    addSubVote("清空本队建筑记录", "", "clear", "清理", "清理记录") {
        val team = player!!.team()
        start(
            player!!, "清理建筑记录({team.colorizeName}[yellow]队|需要2/5同意)".with("team" to team),
            canVote = { it.team() == team }, requireNum = { ceil(it * 0.4).toInt() }
        ) {
            team.data().plans.clear()
        }
    }
    addSubVote("自定义文字投票", "<内容>", "text", "文本", "t") {
        if (arg.isEmpty()) returnReply("[red]请输入投票内容".with())
        start(player!!, "自定义([green]{text}[yellow])".with("text" to arg.joinToString(" "))) {}
    }
    addSubVote(
        "发送醒目留言（1级，冷却2分钟）",
        "[文字] [中屏秒数,最多5秒,默认3秒]",
        "sc", "superchat", "醒目留言"
    ) {
        val player = player!!
        if (!with(trustLevel) { hasTrustLevel(player, "1") }) {
            returnReply("[red]SuperChat 需要 1级信任及以上。".with())
        }
        val (rawText, seconds) = parseSuperChatArgs(arg)
        val text = sanitizeSuperChatText(rawText)
        if (text.isBlank()) returnReply("[red]请输入 SuperChat 内容".with())
        val left = superChatCooldownLeft(player)
        if (left > 0L) {
            returnReply("[yellow]SuperChat 冷却中，还需 ${((left + 999) / 1000)} 秒。".with())
        }
        markSuperChatCooldown(player)
        sendSuperChat(player, text, seconds)
        reply("[green]已发送 SuperChat[gray]（中屏停留 ${seconds.coerceIn(1f, MAX_SUPER_CHAT_SECONDS)} 秒，最多 5 秒）".with())
    }
}

onEnable {
    VoteService.register()
}
