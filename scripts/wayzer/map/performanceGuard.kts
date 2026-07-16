@file:Depends("wayzer/vote", "投票服务")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.map

import arc.Core
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Unit
import wayzer.VoteEvent
import wayzer.lib.MdtStorage
import wayzer.user.TrustLevel
import kotlin.math.roundToInt

name = "常驻性能优化系统"

private val trustLevel = contextScript<TrustLevel>()

private val PERFORMANCE_MODE_KEY = "performanceGuard.mode"

private val checkIntervalMillis by config.key(5_000L, "TPS检测间隔(ms)")
private val sampleSize by config.key(6, "TPS滑动平均采样数")
private val level1Tps by config.key(45, "轻度优化TPS阈值")
private val level2Tps by config.key(35, "中度优化TPS阈值")
private val level3Tps by config.key(25, "紧急优化TPS阈值")
private val recoverTps by config.key(55, "恢复TPS阈值")
private val recoverSamplesRequired by config.key(6, "连续恢复采样数")
private val maxUnitRemovePerPass by config.key(60, "每轮最多移除单位数")
private val minUnitsBeforeRemove by config.key(80, "单位数超过该值才分批清理")
private val legacyLocalLoopEnabled by config.key(false, "兼容旧版本地TPS清理循环(默认关闭，实际措施由serverPressureActions执行)")

private data class RuleSnapshot(
    val fire: Boolean,
    val waveTimer: Boolean,
    val waveSending: Boolean,
    val unitBuildSpeedMultiplier: Float,
    val wavetime: Float,
)

private val tpsSamples = ArrayDeque<Int>()
private var activeLevel = 0
private var recoverSamples = 0
private var snapshot: RuleSnapshot? = null
private var performanceModeCache: String? = null

fun performanceMode(): String {
    performanceModeCache?.let { return it }
    val mode = MdtStorage.getSetting(PERFORMANCE_MODE_KEY)?.lowercase() ?: "normal"
    performanceModeCache = mode
    return mode
}

fun isConservativeMode(): Boolean = performanceMode() == "normal"

fun setPerformanceMode(mode: String) {
    val normalized = mode.lowercase()
    MdtStorage.setSetting(PERFORMANCE_MODE_KEY, normalized)
    performanceModeCache = normalized
}

private fun canManagePerf(operator: Player?): Boolean {
    if (operator == null) return true
    return with(trustLevel) { hasTrustLevel(operator, "3+") }
}

private fun currentTps(): Int = Core.graphics.framesPerSecond.coerceIn(0, 255)

private fun averageTps(): Double {
    val sampleLimit = sampleSize.coerceAtLeast(1)
    while (tpsSamples.size >= sampleLimit) tpsSamples.removeFirst()
    tpsSamples.addLast(currentTps())
    return tpsSamples.sum().toDouble() / tpsSamples.size
}

private fun ensureSnapshot() {
    if (snapshot != null) return
    snapshot = RuleSnapshot(
        fire = state.rules.fire,
        waveTimer = state.rules.waveTimer,
        waveSending = state.rules.waveSending,
        unitBuildSpeedMultiplier = state.rules.unitBuildSpeedMultiplier,
        wavetime = state.wavetime,
    )
}

private fun clearFires(): Int {
    val fires = Groups.fire.toList()
    fires.forEach { it.remove() }
    return fires.size
}

private fun clearBullets(): Int {
    val bullets = Groups.bullet.toList()
    bullets.forEach { it.remove() }
    return bullets.size
}

private fun conservativeUnitPriority(unit: Unit, playerTeams: Set<mindustry.game.Team>): Int = when {
    unit.team() == state.rules.waveTeam -> 0
    unit.team() !in playerTeams -> 1
    else -> 2
}

private fun removeConservativeUnits(): Int {
    if (Groups.unit.size() <= minUnitsBeforeRemove) return 0
    val playerTeams = Groups.player.map { it.team() }.toSet()
    val candidates = Groups.unit.toList()
        .filter { it.player == null }
        .sortedWith(
            compareBy<Unit> { conservativeUnitPriority(it, playerTeams) }
                .thenBy { it.type().health }
                .thenBy { it.health }
                .thenBy { it.type().hitSize }
        )
        .take(maxUnitRemovePerPass.coerceAtLeast(0))

    // 使用 kill() 而不是 remove()，让单位销毁走原版同步/事件链路；
    // 在同步频率限制开启时，直接 remove() 更容易让客户端错过单位消失。
    candidates.forEach { it.kill() }
    return candidates.size
}

private fun applyConservativeLevel(level: Int, avg: Double) {
    ensureSnapshot()
    val effectiveLevel = maxOf(activeLevel, level)

    var fires = 0
    var bullets = 0
    var units = 0

    if (effectiveLevel >= 1) {
        state.rules.fire = false
        fires = clearFires()
        bullets = clearBullets()
    }

    if (effectiveLevel >= 2) {
        state.rules.waveTimer = false
        state.rules.waveSending = false
        state.wavetime = maxOf(state.wavetime, 60f * 60f * 10f)
        state.rules.unitBuildSpeedMultiplier = 0f
    }

    if (effectiveLevel >= 3) {
        units = removeConservativeUnits()
    }

    if (effectiveLevel > activeLevel) {
        broadcast(
            ("[yellow][性能优化] TPS均值 [white]{tps}[yellow]，进入保守优化等级 [white]{level}[yellow]。" +
                    " 清理: 火焰{fires}/子弹{bullets}/单位{units}").with(
                "tps" to avg.roundToInt(),
                "level" to effectiveLevel,
                "fires" to fires,
                "bullets" to bullets,
                "units" to units,
            )
        )
    }
    activeLevel = effectiveLevel
}

fun restoreConservative(reason: String = "TPS已恢复", silent: Boolean = false) {
    val oldLevel = activeLevel
    snapshot?.let {
        state.rules.fire = it.fire
        state.rules.waveTimer = it.waveTimer
        state.rules.waveSending = it.waveSending
        state.rules.unitBuildSpeedMultiplier = it.unitBuildSpeedMultiplier
        if (state.wavetime > it.wavetime && it.wavetime > 0f) state.wavetime = it.wavetime
    }
    snapshot = null
    activeLevel = 0
    recoverSamples = 0
    if (!silent && oldLevel > 0) {
        broadcast("[green][性能优化] 已退出保守优化：{reason}".with("reason" to reason))
    }
}

fun enableConservative(operatorName: String = "系统") {
    setPerformanceMode("normal")
    broadcast("[green]{operator}[green] 已开启常驻保守性能优化。".with("operator" to operatorName))
}

fun disablePerformanceGuard(operatorName: String = "系统") {
    restoreConservative("性能优化被关闭", silent = true)
    setPerformanceMode("off")
    broadcast("[yellow]{operator}[yellow] 已关闭性能优化系统。".with("operator" to operatorName))
}

fun switchToExperimental(operatorName: String = "系统") {
    restoreConservative("实验性性能优化接管", silent = true)
    setPerformanceMode("experimental")
    broadcast("[yellow]{operator}[yellow] 已启用实验性性能优化，常驻保守优化暂停。".with("operator" to operatorName))
}

fun conservativeStatusText(): String {
    val mode = performanceMode()
    val avg = if (tpsSamples.isEmpty()) currentTps().toDouble() else tpsSamples.sum().toDouble() / tpsSamples.size
    return """
        |[cyan]性能优化模式：[white]$mode
        |[cyan]保守优化等级：[white]$activeLevel
        |[cyan]TPS均值：[white]${avg.roundToInt()}[] / 当前：[white]${currentTps()}
        |[cyan]PVP自动介入：[white]开启[]（标准性能优化也会介入PVP，但优先清理火焰/子弹/非玩家单位）
        |[gray]当前脚本主要负责模式、指令与投票兼容；实际自动检测/执行由 serverPressure + serverPressureActions 负责。
    """.trimMargin()
}

private fun desiredLevel(avg: Double): Int = when {
    avg < level3Tps -> 3
    avg < level2Tps -> 2
    avg < level1Tps -> 1
    else -> 0
}

private fun tickConservative() {
    val mode = performanceMode()
    if (mode != "normal") return

    val avg = averageTps()
    if (tpsSamples.size < sampleSize.coerceAtLeast(1)) return

    val targetLevel = desiredLevel(avg)
    if (targetLevel > 0) {
        recoverSamples = 0
        applyConservativeLevel(targetLevel, avg)
        return
    }

    if (activeLevel > 0 && avg >= recoverTps) {
        recoverSamples++
        if (recoverSamples >= recoverSamplesRequired.coerceAtLeast(1)) {
            restoreConservative("TPS均值恢复到 ${avg.roundToInt()}")
        }
    } else {
        recoverSamples = 0
    }
}

private suspend fun startPerfVote(starter: Player, enable: Boolean): Boolean {
    val desc = if (enable) "开启常驻保守性能优化" else "关闭性能优化系统"
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = desc.with(),
        extDesc = if (enable)
            "[yellow]通过后，TPS/压力过高时会自动执行保守优化；PVP地图也会介入，但会尽量避免破坏玩法。"
        else
            "[yellow]通过后，常驻与实验性性能优化都不会自动介入，已生效的保守措施会恢复。",
        supportSingle = true,
    )
    if (!event.awaitResult()) return false
    if (enable) enableConservative(starter.name) else disablePerformanceGuard(starter.name)
    return true
}

listen<EventType.WorldLoadEvent> {
    snapshot = null
    activeLevel = 0
    recoverSamples = 0
    tpsSamples.clear()
}

listen<EventType.ResetEvent> {
    snapshot = null
    activeLevel = 0
    recoverSamples = 0
    tpsSamples.clear()
}

onDisable {
    restoreConservative("脚本卸载", silent = true)
}

onEnable {
    val storedMode = MdtStorage.getSetting(PERFORMANCE_MODE_KEY)?.lowercase()
    if (storedMode.isNullOrBlank()) {
        setPerformanceMode("normal")
    } else {
        performanceModeCache = storedMode
    }
    if (legacyLocalLoopEnabled) {
        launch(Dispatchers.game) {
            while (true) {
                delay(checkIntervalMillis.coerceAtLeast(1000L))
                tickConservative()
            }
        }
    }

    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "perf", "[cyan]性能优化开关[gray]（需50%同意）") {
        aliases = listOf("性能优化", "性能保护")
        usage = "<on|off|status>"
        permission = "wayzer.vote.perf"
        body {
            when (arg.getOrNull(0)?.lowercase()) {
                "on", "enable", "开启" -> startPerfVote(player!!, true)
                "off", "disable", "关闭" -> startPerfVote(player!!, false)
                "status", "状态" -> player!!.sendMessage(conservativeStatusText())
                else -> replyUsage()
            }
        }
    }
}

command("perf", "性能优化系统") {
    usage = "[status|on|off|reset]"
    aliases = listOf("性能优化", "性能保护")
    body {
        val op = arg.getOrNull(0)?.lowercase() ?: "status"
        when (op) {
            "status", "状态" -> reply(conservativeStatusText().with())
            "on", "enable", "开启" -> {
                if (!canManagePerf(player)) returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以直接开启性能优化。".with())
                enableConservative(player?.name ?: "控制台")
            }
            "off", "disable", "关闭" -> {
                if (!canManagePerf(player)) returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以直接关闭性能优化。".with())
                disablePerformanceGuard(player?.name ?: "控制台")
            }
            "reset", "恢复" -> {
                if (!canManagePerf(player)) returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以恢复性能优化状态。".with())
                restoreConservative("管理员手动恢复")
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.vote.perf")
