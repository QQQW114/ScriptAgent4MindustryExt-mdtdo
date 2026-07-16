@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/user/trustLevel", "信任等级")

package wayzer.cmds

import arc.Events
import arc.util.Time
import kotlinx.coroutines.Dispatchers
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
    val wavetime: Float,
)

private var wavePauseSnapshot: WavePauseSnapshot? = null
private var wavePauseToken = 0
private val superChatCooldowns = mutableMapOf<String, Long>()
private val trustLevel = contextScript<TrustLevel>()

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

private fun sendSuperChat(player: Player, text: String) {
    val safe = sanitizeSuperChatText(text)
    val display = """
        |[gold]✦ SuperChat ✦
        |[cyan]${player.plainName()}[white]：
        |[yellow]$safe
    """.trimMargin()
    Call.announce(display)
    Call.sendMessage("[gold][SC][cyan] ${player.name}[white]：[yellow]$safe")
    logger.info("[SuperChat] ${player.plainName()}: $safe")
}

private fun pauseWaves(durationMillis: Long, operatorName: String) {
    val durationTicks = durationMillis / 1000f * 60f
    val token = ++wavePauseToken
    if (wavePauseSnapshot == null) {
        wavePauseSnapshot = WavePauseSnapshot(
            waveTimer = state.rules.waveTimer,
            waveSending = state.rules.waveSending,
            wavetime = state.wavetime,
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
        if (snapshot.wavetime > 0f && state.wavetime > snapshot.wavetime) state.wavetime = snapshot.wavetime
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
    if (snapshot.wavetime > 0f && state.wavetime > snapshot.wavetime) state.wavetime = snapshot.wavetime
    Call.setRules(state.rules)
    wavePauseSnapshot = null
    broadcast("[green]投票已通过：[white]$operatorName[green] 取消了当前波次暂停，已恢复暂停前波次规则。".with())
    return true
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
    addSubVote("投降或结束该局游戏，进行结算", "", "gameOver", "投降", "结算") {
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
    addSubVote("快速出波(默认10波,最高50)", "[波数]", "skipWave", "跳波") {
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
    addSubVote("暂停波次计时(默认300秒,最高1800)", "[秒数]", "pauseWave", "暂停波次") {
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
            supportSingle = true
        ) {
            pauseWaves(seconds * 1000L, player!!.plainName())
        }
    }
    addSubVote("调整当前波次到目标波次", "<目标波次>", "setWave", "wave", "调整波次") {
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
    addSubVote("取消当前暂停波次", "", "resumeWave", "unpauseWave", "取消暂停波次", "恢复波次") {
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
    addSubVote("清理本队建筑记录", "", "clear", "清理", "清理记录") {
        val team = player!!.team()
        start(
            player!!, "清理建筑记录({team.colorizeName}[yellow]队|需要2/5同意)".with("team" to team),
            canVote = { it.team() == team }, requireNum = { ceil(it * 0.4).toInt() }
        ) {
            team.data().plans.clear()
        }
    }
    addSubVote("自定义投票", "<内容>", "text", "文本", "t") {
        if (arg.isEmpty()) returnReply("[red]请输入投票内容".with())
        start(player!!, "自定义([green]{text}[yellow])".with("text" to arg.joinToString(" "))) {}
    }
    addSubVote("发送SuperChat公告(1级信任以上,2分钟冷却,不发起实际投票)", "<内容>", "sc", "superchat", "醒目留言") {
        val player = player!!
        if (!with(trustLevel) { hasTrustLevel(player, "1") }) {
            returnReply("[red]SuperChat 需要 1级信任及以上。".with())
        }
        val text = sanitizeSuperChatText(arg.joinToString(" "))
        if (text.isBlank()) returnReply("[red]请输入 SuperChat 内容".with())
        val left = superChatCooldownLeft(player)
        if (left > 0L) {
            returnReply("[yellow]SuperChat 冷却中，还需 ${((left + 999) / 1000)} 秒。".with())
        }
        markSuperChatCooldown(player)
        sendSuperChat(player, text)
    }
}

onEnable {
    VoteService.register()
}
