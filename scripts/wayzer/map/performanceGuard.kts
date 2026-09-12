@file:Depends("wayzer/vote", "投票服务")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.map

import arc.Core
import mindustry.game.EventType
import mindustry.gen.Fire
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Unit
import wayzer.VoteEvent
import wayzer.lib.MdtStorage
import wayzer.user.TrustLevel
import kotlin.math.roundToInt

name = "统一性能优化系统"

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
private var roundDisabledByVote = false
private var restoreRequestVersion = 0L

fun pressureRestoreRequestVersion(): Long = restoreRequestVersion
private fun requestPressureRestore() { restoreRequestVersion++ }

fun performanceMode(): String {
    if (roundDisabledByVote) return "off"
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
    // 160 起原版移除了 Groups.fire 分组（火焰改为按 tile 存储），这里从 Groups.all 中筛选 Fire 实体清理。
    var removed = 0
    Groups.all.each { entity ->
        if (entity is Fire) runCatching { entity.remove() }.onSuccess { removed++ }
    }
    return removed
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
        // 压力期间把下一次出波推后（单位：游戏刻）。原来的 60*60*10（=36000 刻 = 10 分钟）
        // 太长：一旦守卫条件不满足就再也退不回来（详见 pushWaveTimeFloor 注释），这里收敛为 30 秒。
        pushWaveTimeFloor(60f * 30f)
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

/**
 * 记录"由我们人为抬高"的 wavetime。
 *
 * 背景（2026-09-12 定位的 bug）：恢复时原本写的是
 * `if (state.wavetime > it.wavetime && it.wavetime > 0f) state.wavetime = it.wavetime`。
 * 当快照里的 wavetime 是 0（合法的"马上就要出波"状态，`Logic` 在 wavetime<=0 时直接 runWave）
 * 或快照没赶上时，这个守卫**永不成立**，于是压力期间塞进去的巨大 wavetime 会一直留着，
 * 表现为"波次间隔被改成很久、无法自动复原"。
 *
 * 现在改为哨兵判定：只有当 wavetime 仍然等于我们塞进去的那个值（说明期间没有别的来源改过它）
 * 才回写快照值；任何合法的 wavetime 变化（例如其它脚本/投票修改）都会让哨兵失效，不覆盖。
 */
private var artificialWaveTime: Float? = null

private fun pushWaveTimeFloor(floor: Float) {
    if (floor <= 0f) return
    if (state.wavetime >= floor) return // 本来就更靠后，不记录哨兵、也不改
    state.wavetime = floor
    artificialWaveTime = floor
}

private fun restoreWaveTimeFrom(saved: Float) {
    val artificial = artificialWaveTime
    artificialWaveTime = null
    // 只有当 wavetime 还是我们塞进去的那个值时，才恢复快照值（0 也照常恢复，让原版按原节奏出波）。
    if (artificial != null && state.wavetime >= artificial) {
        state.wavetime = saved.coerceAtLeast(0f)
    }
}

fun restoreConservative(reason: String = "TPS已恢复", silent: Boolean = false) {
    val oldLevel = activeLevel
    snapshot?.let {
        state.rules.fire = it.fire
        state.rules.waveTimer = it.waveTimer
        state.rules.waveSending = it.waveSending
        state.rules.unitBuildSpeedMultiplier = it.unitBuildSpeedMultiplier
        restoreWaveTimeFrom(it.wavetime)
    }
    artificialWaveTime = null
    snapshot = null
    activeLevel = 0
    recoverSamples = 0
    if (!silent && oldLevel > 0) {
        broadcast("[green][性能优化] 已退出保守优化：{reason}".with("reason" to reason))
    }
}

fun enableConservative(operatorName: String = "系统") {
    roundDisabledByVote = false
    setPerformanceMode("normal")
    broadcast("[green]{operator}[green] 已开启统一性能优化系统。".with("operator" to operatorName))
}

fun disablePerformanceGuard(operatorName: String = "系统") {
    restoreConservative("性能优化被关闭", silent = true)
    setPerformanceMode("off")
    requestPressureRestore()
    broadcast("[yellow]{operator}[yellow] 已关闭性能优化系统。".with("operator" to operatorName))
}

fun disableForCurrentRound(operatorName: String = "投票") {
    restoreConservative("本局性能优化被投票关闭", silent = true)
    roundDisabledByVote = true
    requestPressureRestore()
    broadcast("[yellow]{operator}[yellow] 已关闭本局性能优化；下局将恢复服务器默认设置。".with("operator" to operatorName))
}

fun switchToExperimental(operatorName: String = "系统") {
    restoreConservative("实验性性能优化接管", silent = true)
    // v159 后标准/实验性两套执行器已统一由 serverPressureActions 接管，
    // 保留旧指令只作兼容别名，不再设置独立 experimental 模式。
    setPerformanceMode("normal")
    broadcast("[yellow]{operator}[yellow] 已启用统一性能优化系统（旧实验性指令已兼容）。".with("operator" to operatorName))
}

fun conservativeStatusText(): String {
    val mode = performanceMode()
    val avg = if (tpsSamples.isEmpty()) currentTps().toDouble() else tpsSamples.sum().toDouble() / tpsSamples.size
    return """
        |[cyan]性能优化模式：[white]$mode
        |[cyan]性能优化等级：[white]$activeLevel
        |[cyan]TPS均值：[white]${avg.roundToInt()}[] / 当前：[white]${currentTps()}
        |[cyan]PVP自动介入：[white]开启[]（标准性能优化也会介入PVP，但优先清理火焰/子弹/非玩家单位）
        |[gray]标准/旧实验性模式已合并；自动检测/执行由 serverPressure + serverPressureActions 统一负责。
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
    val desc = if (enable) "开启统一性能优化" else "关闭本局性能优化系统"
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = desc.with(),
        extDesc = if (enable)
            "[yellow]通过后，TPS/游戏同步上行过高时会按等级执行统一优化；世界/音乐/CP流不会触发清单位。"
        else
            "[yellow]通过后本局不再自动清理单位/关闭处理器/兜底换图，已生效的可逆规则会恢复。",
        supportSingle = true,
    )
    if (!event.awaitResult()) return false
    if (enable) enableConservative(starter.name) else disableForCurrentRound(starter.name)
    return true
}

listen<EventType.WorldLoadEvent> {
    roundDisabledByVote = false
    snapshot = null
    activeLevel = 0
    recoverSamples = 0
    tpsSamples.clear()
}

listen<EventType.ResetEvent> {
    roundDisabledByVote = false
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
    if (storedMode.isNullOrBlank() || storedMode == "experimental") {
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
    VoteEvent.VoteCommands += CommandInfo(script, "perf", "[cyan]常驻性能保护[gray]（需50%同意）") {
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
                requestPressureRestore()
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.vote.perf")
