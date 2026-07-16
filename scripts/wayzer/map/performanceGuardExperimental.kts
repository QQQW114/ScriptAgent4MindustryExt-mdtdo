@file:Depends("wayzer/map/performanceGuard", "常驻性能优化系统")
@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/vote", "投票服务")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.map

import arc.Core
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Unit
import mindustry.world.blocks.logic.LogicBlock
import wayzer.MapManager
import wayzer.MapRegistry
import wayzer.VoteEvent
import wayzer.lib.MdtStorage
import wayzer.user.TrustLevel
import kotlin.math.roundToInt

name = "实验性性能优化系统"

private val perfGuard = contextScript<PerformanceGuard>()
private val trustLevel = contextScript<TrustLevel>()

private val PREVIOUS_MODE_KEY = "performanceGuard.experimental.previousMode"
private val FORCE_MAP_BYPASS_KEY = "performanceGuard.experimental.forceChangingMap"
private val DISABLED_LOGIC_POSITIONS_KEY = "performanceGuard.experimental.disabledLogicPositions"

private val checkIntervalMillis by config.key(5_000L, "实验性TPS检测间隔(ms)")
private val sampleSize by config.key(6, "实验性TPS滑动平均采样数")
private val stage1Tps by config.key(45, "实验性基础优化TPS阈值")
private val stage2Tps by config.key(35, "实验性关闭处理器TPS阈值")
private val stage3Tps by config.key(30, "实验性清理非玩家单位TPS阈值")
private val stage4Tps by config.key(25, "实验性清理所有单位TPS阈值")
private val fallbackTps by config.key(25, "实验性兜底换图TPS阈值")
private val recoverTps by config.key(55, "实验性恢复TPS阈值")
private val recoverSamplesRequired by config.key(6, "实验性连续恢复采样数")
private val stage2BadSamples by config.key(3, "连续低TPS多少轮后关闭处理器")
private val stage3BadSamples by config.key(6, "连续低TPS多少轮后清理非玩家单位")
private val stage4BadSamples by config.key(9, "连续低TPS多少轮后清理所有单位")
private val fallbackBadSamples by config.key(12, "所有措施后仍低TPS多少轮后强制换图")
private val legacyLocalLoopEnabled by config.key(false, "兼容旧版实验性TPS清理循环(默认关闭，实际措施由serverPressureActions执行)")

private data class RuleSnapshot(
    val fire: Boolean,
    val waveTimer: Boolean,
    val waveSending: Boolean,
    val unitBuildSpeedMultiplier: Float,
    val disableWorldProcessors: Boolean,
    val wavetime: Float,
)

private val tpsSamples = ArrayDeque<Int>()
private var activeStage = 0
private var badSamples = 0
private var recoverSamples = 0
private var snapshot: RuleSnapshot? = null
private var forceMapInProgress = false
private var mayHaveDisabledLogicPositions = false

private fun currentTps(): Int = Core.graphics.framesPerSecond.coerceIn(0, 255)

private fun averageTps(): Double {
    val sampleLimit = sampleSize.coerceAtLeast(1)
    while (tpsSamples.size >= sampleLimit) tpsSamples.removeFirst()
    tpsSamples.addLast(currentTps())
    return tpsSamples.sum().toDouble() / tpsSamples.size
}

private fun canManageExperimental(operator: Player?): Boolean {
    if (operator == null) return true
    return with(trustLevel) { hasTrustLevel(operator, "3+") }
}

private fun isExperimentalMode(): Boolean = with(perfGuard) { performanceMode() == "experimental" }

private fun ensureSnapshot() {
    if (snapshot != null) return
    snapshot = RuleSnapshot(
        fire = state.rules.fire,
        waveTimer = state.rules.waveTimer,
        waveSending = state.rules.waveSending,
        unitBuildSpeedMultiplier = state.rules.unitBuildSpeedMultiplier,
        disableWorldProcessors = state.rules.disableWorldProcessors,
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

private fun removeUnits(includePlayerControlled: Boolean): Int {
    val units = Groups.unit.toList()
        .filter { includePlayerControlled || it.player == null }
        .filter { !it.dead() }
        .sortedWith(
            compareBy<Unit> { if (it.team() == state.rules.waveTeam) 0 else 1 }
                .thenBy { it.type().health }
                .thenBy { it.health }
                .thenBy { it.type().hitSize }
        )
    // 使用 kill() 而不是 remove()，让 UnitDeath/UnitDestroy 和同步限制中的销毁补包正常触发；
    // 直接 remove() 在高压同步限制下可能让客户端残留幽灵单位。
    units.forEach { it.kill() }
    return units.size
}

private fun disabledLogicPositionsFromDb(): MutableSet<Int> =
    MdtStorage.getSetting(DISABLED_LOGIC_POSITIONS_KEY)
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .toMutableSet()

private fun saveDisabledLogicPositions(positions: Set<Int>) {
    val value = positions.sorted().joinToString(",").takeIf { it.isNotBlank() }
    MdtStorage.setSetting(DISABLED_LOGIC_POSITIONS_KEY, value)
    mayHaveDisabledLogicPositions = positions.isNotEmpty()
}

private fun disableLogicProcessors(): Int {
    val positions = disabledLogicPositionsFromDb()
    var count = 0
    Groups.build.toList().forEach { build ->
        if (build is LogicBlock.LogicBuild && build.enabled) {
            positions += build.tile.pos()
            build.enabled = false
            count++
        }
    }
    saveDisabledLogicPositions(positions)
    return count
}

private fun restoreLogicProcessors(): Int {
    val positions = disabledLogicPositionsFromDb()
    if (positions.isEmpty()) {
        mayHaveDisabledLogicPositions = false
        return 0
    }
    var count = 0
    Groups.build.toList().forEach { build ->
        if (build is LogicBlock.LogicBuild && build.tile.pos() in positions) {
            build.enabled = true
            count++
        }
    }
    saveDisabledLogicPositions(emptySet())
    return count
}

private fun applyStage(stage: Int, avg: Double, forced: Boolean = false) {
    ensureSnapshot()

    var fires = 0
    var bullets = 0
    var processors = 0
    var units = 0

    if (stage >= 1) {
        state.rules.fire = false
        state.rules.waveTimer = false
        state.rules.waveSending = false
        state.wavetime = maxOf(state.wavetime, 60f * 60f * 10f)
        state.rules.unitBuildSpeedMultiplier = 0f
        fires = clearFires()
        bullets = clearBullets()
    }

    if (stage >= 2) {
        state.rules.disableWorldProcessors = true
        processors = disableLogicProcessors()
    }

    if (stage >= 3) {
        units = removeUnits(includePlayerControlled = false)
    }

    if (stage >= 4) {
        units += removeUnits(includePlayerControlled = true)
    }

    if (stage > activeStage || forced) {
        broadcast(
            ("[red][实验性性能优化] TPS均值 [white]{tps}[red]，进入阶段 [white]{stage}[red]。" +
                    " 清理/关闭: 火焰{fires}/子弹{bullets}/处理器{processors}/单位{units}").with(
                "tps" to avg.roundToInt(),
                "stage" to stage,
                "fires" to fires,
                "bullets" to bullets,
                "processors" to processors,
                "units" to units,
            )
        )
    }
    activeStage = maxOf(activeStage, stage)
}

private fun restoreExperimentalRules(reason: String = "TPS恢复", silent: Boolean = false) {
    val oldStage = activeStage
    val restoredProcessors = restoreLogicProcessors()
    snapshot?.let {
        state.rules.fire = it.fire
        state.rules.waveTimer = it.waveTimer
        state.rules.waveSending = it.waveSending
        state.rules.unitBuildSpeedMultiplier = it.unitBuildSpeedMultiplier
        state.rules.disableWorldProcessors = it.disableWorldProcessors
        if (state.wavetime > it.wavetime && it.wavetime > 0f) state.wavetime = it.wavetime
    }
    snapshot = null
    activeStage = 0
    badSamples = 0
    recoverSamples = 0
    if (!silent && (oldStage > 0 || restoredProcessors > 0)) {
        broadcast("[green][实验性性能优化] 已恢复玩法规则：{reason}".with("reason" to reason))
    }
}

private fun previousMode(): String =
    MdtStorage.getSetting(PREVIOUS_MODE_KEY)?.takeIf { it.isNotBlank() && it != "experimental" } ?: "normal"

private fun rememberPreviousMode() {
    val mode = with(perfGuard) { performanceMode() }
    if (mode != "experimental") {
        MdtStorage.setSetting(PREVIOUS_MODE_KEY, mode)
    } else if (MdtStorage.getSetting(PREVIOUS_MODE_KEY).isNullOrBlank()) {
        MdtStorage.setSetting(PREVIOUS_MODE_KEY, "normal")
    }
}

private fun restorePreviousMode() {
    val mode = previousMode()
    with(perfGuard) { setPerformanceMode(mode) }
    MdtStorage.setSetting(PREVIOUS_MODE_KEY, null)
}

private fun clearRuntimeForWorldChange() {
    snapshot = null
    activeStage = 0
    badSamples = 0
    recoverSamples = 0
    tpsSamples.clear()
    saveDisabledLogicPositions(emptySet())
}

private suspend fun forceChangeMapFallback(): Boolean {
    if (forceMapInProgress) return false
    forceMapInProgress = true
    try {
        val maps = MapRegistry.searchMaps().filter { it != MapManager.current }
        val next = maps.randomOrNull()
        if (next == null) {
            broadcast("[red][实验性性能优化] 已达到兜底换图阶段，但没有找到可用地图。".with())
            return false
        }

        broadcast("[red][实验性性能优化] 所有措施后TPS仍未恢复，强制换图到：[white]{map.name}[]([yellow]{map.id}[])".with("map" to next))
        MdtStorage.setSetting(FORCE_MAP_BYPASS_KEY, "true")
        val ok = try {
            MapManager.loadMapSync(next)
        } finally {
            MdtStorage.setSetting(FORCE_MAP_BYPASS_KEY, null)
        }
        if (ok) {
            clearRuntimeForWorldChange()
            restorePreviousMode()
            broadcast("[green][实验性性能优化] 兜底换图完成，实验性优化已退出。".with())
        }
        return ok
    } finally {
        forceMapInProgress = false
    }
}

fun experimentalStatusText(): String {
    val mode = with(perfGuard) { performanceMode() }
    val avg = if (tpsSamples.isEmpty()) currentTps().toDouble() else tpsSamples.sum().toDouble() / tpsSamples.size
    return """
        |[cyan]性能优化模式：[white]$mode
        |[cyan]实验性阶段：[white]$activeStage
        |[cyan]TPS均值：[white]${avg.roundToInt()}[] / 当前：[white]${currentTps()}
        |[cyan]连续低TPS轮数：[white]$badSamples
        |[gray]实验性不受PVP/地图标签限制；实际自动检测/执行由 serverPressure + serverPressureActions 接管。
    """.trimMargin()
}

private suspend fun enableExperimental(operatorName: String) {
    rememberPreviousMode()
    with(perfGuard) { switchToExperimental(operatorName) }
}

private fun disableExperimental(operatorName: String, restoreMode: Boolean = true) {
    restoreExperimentalRules("实验性优化被关闭")
    if (restoreMode) restorePreviousMode()
    broadcast("[green]{operator}[green] 已关闭实验性性能优化。".with("operator" to operatorName))
}

private suspend fun tickExperimental() {
    if (!isExperimentalMode()) {
        if (activeStage > 0 || snapshot != null || mayHaveDisabledLogicPositions) {
            restoreExperimentalRules("实验性优化模式已关闭")
        }
        tpsSamples.clear()
        badSamples = 0
        recoverSamples = 0
        return
    }

    val avg = averageTps()
    if (tpsSamples.size < sampleSize.coerceAtLeast(1)) return

    if (avg >= recoverTps && activeStage > 0) {
        recoverSamples++
        if (recoverSamples >= recoverSamplesRequired.coerceAtLeast(1)) {
            restoreExperimentalRules("TPS均值恢复到 ${avg.roundToInt()}")
        }
        return
    }

    recoverSamples = 0
    if (avg < stage1Tps) {
        badSamples++
        applyStage(1, avg)
    } else {
        badSamples = 0
        return
    }

    when {
        badSamples >= fallbackBadSamples && avg < fallbackTps && activeStage >= 4 -> {
            forceChangeMapFallback()
        }
        badSamples >= stage4BadSamples && avg < stage4Tps -> {
            applyStage(4, avg)
        }
        badSamples >= stage3BadSamples && avg < stage3Tps -> {
            applyStage(3, avg)
        }
        badSamples >= stage2BadSamples && avg < stage2Tps -> {
            applyStage(2, avg)
        }
    }
}

private suspend fun startExperimentalVote(starter: Player, enable: Boolean): Boolean {
    val desc = if (enable) "开启实验性性能优化" else "关闭实验性性能优化"
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = desc.with(),
        extDesc = if (enable)
            "[red]实验性优化不受PVP/地图标签限制，会在极端低TPS时关闭处理器、清单位并兜底换图。"
        else
            "[yellow]通过后会恢复实验性优化修改过的规则，并回到此前性能优化模式。",
        supportSingle = true,
    )
    if (!event.awaitResult()) return false
    if (enable) enableExperimental(starter.name) else disableExperimental(starter.name)
    return true
}

listen<EventType.WorldLoadEvent> {
    clearRuntimeForWorldChange()
}

listen<EventType.ResetEvent> {
    clearRuntimeForWorldChange()
}

onDisable {
    restoreExperimentalRules("脚本卸载", silent = true)
}

onEnable {
    mayHaveDisabledLogicPositions = !MdtStorage.getSetting(DISABLED_LOGIC_POSITIONS_KEY).isNullOrBlank()

    if (legacyLocalLoopEnabled) {
        launch(Dispatchers.game) {
            while (true) {
                delay(checkIntervalMillis.coerceAtLeast(1000L))
                tickExperimental()
            }
        }
    }

    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "xperf", "[cyan]实验性性能优化[gray]（需50%同意）") {
        aliases = listOf("实验性性能优化", "强力性能优化")
        usage = "<on|off|status>"
        permission = "wayzer.vote.xperf"
        body {
            when (arg.getOrNull(0)?.lowercase()) {
                "on", "enable", "开启" -> startExperimentalVote(player!!, true)
                "off", "disable", "关闭" -> startExperimentalVote(player!!, false)
                "status", "状态" -> player!!.sendMessage(experimentalStatusText())
                else -> replyUsage()
            }
        }
    }
}

command("xperf", "实验性性能优化系统") {
    usage = "[status|on|off|stage <1-4>|fallback]"
    aliases = listOf("实验性性能优化", "强力性能优化")
    body {
        val op = arg.getOrNull(0)?.lowercase() ?: "status"
        when (op) {
            "status", "状态" -> reply(experimentalStatusText().with())
            "on", "enable", "开启" -> {
                if (!canManageExperimental(player)) returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以直接开启实验性性能优化。".with())
                enableExperimental(player?.name ?: "控制台")
            }
            "off", "disable", "关闭" -> {
                if (!canManageExperimental(player)) returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以直接关闭实验性性能优化。".with())
                disableExperimental(player?.name ?: "控制台")
            }
            "stage", "阶段" -> {
                if (!canManageExperimental(player)) returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以直接调整实验性阶段。".with())
                val stage = arg.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 4) ?: replyUsage()
                if (!isExperimentalMode()) enableExperimental(player?.name ?: "控制台")
                applyStage(stage, currentTps().toDouble(), forced = true)
            }
            "fallback", "换图" -> {
                if (!canManageExperimental(player)) returnReply("[red]权限不足：只有 3+级 和 4级/admin 可以直接执行兜底换图。".with())
                if (!isExperimentalMode()) enableExperimental(player?.name ?: "控制台")
                forceChangeMapFallback()
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.vote.xperf")
