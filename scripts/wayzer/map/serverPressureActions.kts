@file:Depends("wayzer/map/serverPressure", "服务器压力判断")
@file:Depends("wayzer/map/performanceGuard", "性能优化模式")
@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/vote", "投票服务")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.map

import mindustry.core.GameState
import mindustry.content.UnitTypes
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.BuildingTetherc
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.TimedKillc
import mindustry.gen.Unit as MindustryUnit
import mindustry.type.UnitType
import mindustry.world.blocks.logic.LogicBlock
import wayzer.MapManager
import wayzer.MapRegistry
import wayzer.VoteEvent
import wayzer.lib.MdtStorage
import wayzer.user.TrustLevel
import java.time.Duration

name = "服务器压力措施执行"

private val pressure = contextScript<ServerPressure>()
private val perfGuard = contextScript<PerformanceGuard>()
private val trustLevel = contextScript<TrustLevel>()

private val PREVIOUS_MODE_KEY = "performanceGuard.experimental.previousMode"
private val FORCE_MAP_BYPASS_KEY = "performanceGuard.experimental.forceChangingMap"
private val DISABLED_LOGIC_POSITIONS_KEY = "performanceGuard.experimental.disabledLogicPositions"

private val actionIntervalMillis by config.key(5_000L, "压力措施执行间隔(ms)")
private val level2UnitCap by config.key(100, "压力等级2临时单位上限")
private val level1RemovePerPass by config.key(30, "压力等级1每轮最多清理单位")
private val level2RemovePerPass by config.key(80, "压力等级2每轮最多清理单位")
private val level3RemovePerPass by config.key(240, "压力等级3每轮最多清理单位")
private val level4RemovePerPass by config.key(400, "压力等级4每轮最多清理单位")
private val level1MaxUnitTier by config.key(1, "压力等级1最高清理单位阶级")
private val level2MaxUnitTier by config.key(2, "压力等级2最高清理单位阶级")
private val level3MaxUnitTier by config.key(3, "压力等级3最高清理单位阶级")
private val level4MaxUnitTier by config.key(5, "压力等级4最高清理单位阶级")
private val level4FallbackSamples by config.key(3, "等级4连续多少轮后触发兜底")
private val ppsTrafficRatio by config.key(0.60, "实验性疑似PPS顶满检测上行预算倍率")
private val ppsWindowMillis by config.key(2_000L, "实验性疑似PPS顶满退出检测窗口(ms)")
private val ppsMinLeaves by config.key(2, "实验性疑似PPS顶满窗口内退出人数阈值")
private val ppsContinuousLeaves by config.key(3, "实验性疑似PPS顶满连续退出人数阈值")
private val ppsCleanupCooldownMillis by config.key(30_000L, "实验性疑似PPS顶满清理冷却(ms)")
private val severeTrafficRatio by config.key(2.0, "实验性严重上行超预算清理倍率")
private val severeTrafficCleanupCooldownMillis by config.key(30_000L, "实验性严重上行超预算清理冷却(ms)")
private val level4TopUnitCleanupCooldownMillis by config.key(30_000L, "实验性压力等级4数量前三单位清理冷却(ms)")

private data class RuleSnapshot(
    val fire: Boolean,
    val waveTimer: Boolean,
    val waveSending: Boolean,
    val unitCap: Int,
    val disableUnitCap: Boolean,
    val disableWorldProcessors: Boolean,
    val wavetime: Float,
)

private var snapshot: RuleSnapshot? = null
private var activeLevel = 0
private var announcedLevel = 0
private var forceMapInProgress = false
private var level4Samples = 0
private var autoPaused = false
private var mayHaveDisabledLogicPositions = false
private val recentLeaveTimes = ArrayDeque<Long>()
private var continuousLeaveCount = 0
private var lastLeaveAt = 0L
private var lastPpsCleanupAt = 0L
private var lastSevereTrafficCleanupAt = 0L
private var lastLevel4TopUnitCleanupAt = 0L

private fun canManagePause(operator: Player?): Boolean {
    if (operator == null) return true
    return with(trustLevel) { hasTrustLevel(operator, "3+") }
}

private fun ensureSnapshot() {
    if (snapshot != null) return
    snapshot = RuleSnapshot(
        fire = state.rules.fire,
        waveTimer = state.rules.waveTimer,
        waveSending = state.rules.waveSending,
        unitCap = state.rules.unitCap,
        disableUnitCap = state.rules.disableUnitCap,
        disableWorldProcessors = state.rules.disableWorldProcessors,
        wavetime = state.wavetime,
    )
}

private fun syncBooleanRule(name: String, current: Boolean, target: Boolean, setter: (Boolean) -> Unit): Boolean {
    if (current == target) return false
    setter(target)
    // 不使用 Call.setRules(state.rules)：它会把完整 Rules 发给客户端，并覆盖客户端本地只改显示用的 fog/staticFog 等字段。
    Call.setRule(name, target.toString())
    return true
}

private fun syncIntRule(name: String, current: Int, target: Int, setter: (Int) -> Unit): Boolean {
    if (current == target) return false
    setter(target)
    Call.setRule(name, target.toString())
    return true
}

private fun setFireRule(value: Boolean) =
    syncBooleanRule("fire", state.rules.fire, value) { state.rules.fire = it }

private fun setWaveTimerRule(value: Boolean) =
    syncBooleanRule("waveTimer", state.rules.waveTimer, value) { state.rules.waveTimer = it }

private fun setWaveSendingRule(value: Boolean) =
    syncBooleanRule("waveSending", state.rules.waveSending, value) { state.rules.waveSending = it }

private fun setDisableUnitCapRule(value: Boolean) =
    syncBooleanRule("disableUnitCap", state.rules.disableUnitCap, value) { state.rules.disableUnitCap = it }

private fun setDisableWorldProcessorsRule(value: Boolean) =
    syncBooleanRule("disableWorldProcessors", state.rules.disableWorldProcessors, value) { state.rules.disableWorldProcessors = it }

private fun setUnitCapRule(value: Int) =
    syncIntRule("unitCap", state.rules.unitCap, value) { state.rules.unitCap = it }

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

private fun disabledLogicPositionsFromDb(): MutableSet<Int> =
    MdtStorage.getSetting(DISABLED_LOGIC_POSITIONS_KEY)
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .toMutableSet()

private fun saveDisabledLogicPositions(positions: Set<Int>) {
    mayHaveDisabledLogicPositions = positions.isNotEmpty()
    MdtStorage.setSetting(
        DISABLED_LOGIC_POSITIONS_KEY,
        positions.sorted().joinToString(",").takeIf { it.isNotBlank() }
    )
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
    if (!mayHaveDisabledLogicPositions) return 0
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

private fun playerTeams(): Set<Team> = Groups.player.map { it.team() }.toSet()

private val ppsProtectedUnitNames = setOf("mono", "pulsar", "quasar", "poly", "mega")
private val missileLauncherUnitNames = setOf("quell", "disrupt", "anthicus")

private val knownUnitTiers: Map<UnitType, Int> = mapOf(
    // Serpulo ground/naval/air/support trees
    UnitTypes.dagger to 1, UnitTypes.crawler to 1, UnitTypes.nova to 1, UnitTypes.flare to 1,
    UnitTypes.mono to 1, UnitTypes.risso to 1, UnitTypes.retusa to 1, UnitTypes.alpha to 1,
    UnitTypes.mace to 2, UnitTypes.atrax to 2, UnitTypes.pulsar to 2, UnitTypes.horizon to 2,
    UnitTypes.poly to 2, UnitTypes.minke to 2, UnitTypes.oxynoe to 2, UnitTypes.beta to 2,
    UnitTypes.fortress to 3, UnitTypes.spiroct to 3, UnitTypes.quasar to 3, UnitTypes.zenith to 3,
    UnitTypes.mega to 3, UnitTypes.bryde to 3, UnitTypes.cyerce to 3, UnitTypes.gamma to 3,
    UnitTypes.scepter to 4, UnitTypes.arkyid to 4, UnitTypes.vela to 4, UnitTypes.antumbra to 4,
    UnitTypes.quad to 4, UnitTypes.sei to 4, UnitTypes.aegires to 4,
    UnitTypes.reign to 5, UnitTypes.toxopid to 5, UnitTypes.corvus to 5, UnitTypes.eclipse to 5,
    UnitTypes.oct to 5, UnitTypes.omura to 5, UnitTypes.navanax to 5,

    // Erekir trees. Do not compare these by raw health/cost with Serpulo units: Erekir low-tier
    // units are intentionally heavy, and health-based sorting used to sacrifice Serpulo high-tier units first.
    UnitTypes.stell to 1, UnitTypes.merui to 1, UnitTypes.elude to 1, UnitTypes.evoke to 1,
    UnitTypes.locus to 2, UnitTypes.cleroi to 2, UnitTypes.avert to 2, UnitTypes.incite to 2,
    UnitTypes.precept to 3, UnitTypes.anthicus to 3, UnitTypes.obviate to 3, UnitTypes.emanate to 3,
    UnitTypes.vanquish to 4, UnitTypes.tecta to 4, UnitTypes.quell to 4,
    UnitTypes.conquer to 5, UnitTypes.collaris to 5, UnitTypes.disrupt to 5,
)

private fun fallbackUnitTier(type: UnitType): Int = when {
    !type.useUnitCap -> 1
    type.health <= 800f && type.hitSize <= 12f -> 1
    type.health <= 2500f && type.hitSize <= 22f -> 2
    type.health <= 9000f && type.hitSize <= 36f -> 3
    type.health <= 30000f -> 4
    else -> 5
}

private fun unitTier(type: UnitType): Int = (knownUnitTiers[type] ?: fallbackUnitTier(type)).coerceIn(1, 5)

private fun isPressureUnitCandidate(unit: MindustryUnit): Boolean =
    unit.player == null &&
            !unit.dead() &&
            unit.killable() &&
            !unit.spawnedByCore &&
            unit !is TimedKillc &&
            unit !is BuildingTetherc

private fun unitPriority(unit: MindustryUnit, playerTeams: Set<Team>): Int = when {
    unit.team() == state.rules.waveTeam -> 0
    unit.team() !in playerTeams -> 1
    state.rules.pvp -> 3
    else -> 2
}

private fun removePressureUnits(maxRemove: Int, includePlayerTeams: Boolean = false, maxTier: Int = 1): Int {
    if (maxRemove <= 0) return 0
    val playerTeams = playerTeams()
    val tierLimit = maxTier.coerceIn(1, 5)
    val candidates = Groups.unit.toList()
        .asSequence()
        .filter(::isPressureUnitCandidate)
        .filter { includePlayerTeams || it.team() !in playerTeams || it.team() == state.rules.waveTeam }
        .filter { unitTier(it.type()) <= tierLimit }
        .sortedWith(
            compareBy<MindustryUnit> { unitPriority(it, playerTeams) }
                .thenBy { unitTier(it.type()) }
                .thenBy { it.health }
                .thenBy { it.type().hitSize }
                .thenBy { it.id() }
        )
        .take(maxRemove)
        .toList()

    // 使用 kill() 而不是 remove()：kill() 会走原版 UnitDeath/UnitDestroy 同步链路；
    // 直接 remove() 容易让处于同步限制中的客户端错过单位消失，形成幽灵单位/不同步。
    candidates.forEach { it.kill() }
    return candidates.size
}

private fun killPressureUnitsWhere(predicate: (MindustryUnit) -> Boolean): Int {
    val candidates = Groups.unit.toList()
        .asSequence()
        .filter(::isPressureUnitCandidate)
        .filter(predicate)
        .toList()
    candidates.forEach { it.kill() }
    return candidates.size
}

private fun removeScatheTurrets(): Int {
    val builds = Groups.build.toList()
        .filter { it.block.name == "scathe" }
    builds.forEach { it.kill() }
    return builds.size
}

private fun trafficOverRatio(trafficMbps: Double, budgetMbps: Double, ratio: Double): Boolean =
    budgetMbps > 0.0 && trafficMbps >= budgetMbps * ratio

private fun cleanupPpsOverload(now: Long, trafficMbps: Double, budgetMbps: Double) {
    lastPpsCleanupAt = now
    recentLeaveTimes.clear()
    continuousLeaveCount = 0

    val units = killPressureUnitsWhere { unit ->
        val type = unit.type()
        val name = type.name
        (unitTier(type) <= 3 && name !in ppsProtectedUnitNames) || name in missileLauncherUnitNames
    }
    val turrets = removeScatheTurrets()

    broadcast("[red]检测到异常超时，已清理除mono外的t4以下的非辅助可挖矿单位与发射导弹单位的单位与炮台，如继续出现此类情况，请主动清理过量单位".with())
    logger.info("[压力措施] 疑似PPS顶满清理完成：单位 $units / scathe炮台 $turrets；估算上行 ${"%.2f".format(trafficMbps)} Mbps / 预算 ${"%.2f".format(budgetMbps)} Mbps")
}

private fun cleanupSevereTrafficIfNeeded(trafficMbps: Double, budgetMbps: Double) {
    if (!trafficOverRatio(trafficMbps, budgetMbps, severeTrafficRatio.coerceAtLeast(1.0))) return
    val now = System.currentTimeMillis()
    if (now - lastSevereTrafficCleanupAt < severeTrafficCleanupCooldownMillis.coerceAtLeast(1_000L)) return

    lastSevereTrafficCleanupAt = now
    val units = killPressureUnitsWhere { unitTier(it.type()) <= 4 }
    broadcast("[red]上行需求量严重超量（>200%），已清理t5以下所有单位".with())
    logger.info("[压力措施] 严重上行超预算清理完成：单位 $units；估算上行 ${"%.2f".format(trafficMbps)} Mbps / 预算 ${"%.2f".format(budgetMbps)} Mbps")
}

private fun cleanupTopUnitTypesIfNeeded(level: Int, tpsLevel: Int, trafficLevel: Int) {
    if (level < 4 || tpsLevel <= 0 || trafficLevel <= 0) return
    val now = System.currentTimeMillis()
    if (now - lastLevel4TopUnitCleanupAt < level4TopUnitCleanupCooldownMillis.coerceAtLeast(1_000L)) return

    val candidates = Groups.unit.toList().filter(::isPressureUnitCandidate)
    val topTypes = candidates
        .groupBy { it.type() }
        .entries
        .sortedByDescending { it.value.size }
        .take(3)
    if (topTypes.isEmpty()) return

    lastLevel4TopUnitCleanupAt = now
    val topSet = topTypes.map { it.key }.toSet()
    var removed = 0
    candidates.filter { it.type() in topSet }.forEach {
        it.kill()
        removed++
    }
    val summary = topTypes.joinToString("、") { "${it.key.localizedName}(${it.value.size})" }
    broadcast("[red][压力措施] TPS与上行同时超限达到等级4，已清理数量前三单位：[white]$summary[red]。".with())
    logger.info("[压力措施] 等级4数量前三单位清理完成：$summary；移除 $removed 个")
}

private fun applyUnitCap() {
    setDisableUnitCapRule(false)
    val target = level2UnitCap.coerceAtLeast(10)
    val next = if (state.rules.unitCap <= 0) target else minOf(state.rules.unitCap, target)
    setUnitCapRule(next)
}

private fun restoreMeasuresAbove(targetLevel: Int): Int {
    val saved = snapshot ?: return 0
    var restoredProcessors = 0

    if (targetLevel < 3 && activeLevel >= 3) {
        restoredProcessors = restoreLogicProcessors()
        setDisableWorldProcessorsRule(saved.disableWorldProcessors)
    }

    if (targetLevel < 2 && activeLevel >= 2) {
        setWaveTimerRule(saved.waveTimer)
        setWaveSendingRule(saved.waveSending)
        setUnitCapRule(saved.unitCap)
        setDisableUnitCapRule(saved.disableUnitCap)
        if (state.wavetime > saved.wavetime && saved.wavetime > 0f) state.wavetime = saved.wavetime
    }

    return restoredProcessors
}

private fun applyLevel(level: Int, reason: String) {
    if (level <= 0) return
    ensureSnapshot()
    val previousLevel = activeLevel
    val restoredProcessors = restoreMeasuresAbove(level)

    var fires = 0
    var bullets = 0
    var units = 0
    var processors = 0

    if (level >= 1) {
        setFireRule(false)
        fires = clearFires()
        bullets = clearBullets()
        units += removePressureUnits(level1RemovePerPass, includePlayerTeams = false, maxTier = level1MaxUnitTier)
    }
    if (level >= 2) {
        setWaveTimerRule(false)
        setWaveSendingRule(false)
        state.wavetime = maxOf(state.wavetime, 60f * 60f * 10f)
        applyUnitCap()
        units += removePressureUnits(level2RemovePerPass, includePlayerTeams = false, maxTier = level2MaxUnitTier)
    }
    if (level >= 3 && with(perfGuard) { performanceMode() } == "experimental") {
        setDisableWorldProcessorsRule(true)
        processors = disableLogicProcessors()
        units += removePressureUnits(level3RemovePerPass, includePlayerTeams = true, maxTier = level3MaxUnitTier)
    }
    if (level >= 4 && with(perfGuard) { performanceMode() } == "experimental") {
        units += removePressureUnits(level4RemovePerPass, includePlayerTeams = true, maxTier = level4MaxUnitTier)
    }

    if (level > announcedLevel) {
        broadcast(
            ("[yellow][压力措施] 进入等级 [white]{level}[yellow]（{reason}）。" +
                    " 本轮处理：火焰{fires}/子弹{bullets}/单位{units}/处理器{processors}").with(
                "level" to level,
                "reason" to reason,
                "fires" to fires,
                "bullets" to bullets,
                "units" to units,
                "processors" to processors,
            )
        )
        announcedLevel = level
    } else if (previousLevel > level && restoredProcessors > 0) {
        logger.info("[压力措施] 压力等级降至 $level，已恢复处理器 $restoredProcessors 个")
    }
    activeLevel = level
}

private fun restorePressureRules(reason: String = "压力恢复", silent: Boolean = false) {
    if (snapshot == null && activeLevel <= 0 && !autoPaused && !mayHaveDisabledLogicPositions) return
    val oldLevel = activeLevel
    val restoredProcessors = restoreLogicProcessors()
    val hadAutoPause = autoPaused
    snapshot?.let {
        setFireRule(it.fire)
        setWaveTimerRule(it.waveTimer)
        setWaveSendingRule(it.waveSending)
        setUnitCapRule(it.unitCap)
        setDisableUnitCapRule(it.disableUnitCap)
        setDisableWorldProcessorsRule(it.disableWorldProcessors)
        if (state.wavetime > it.wavetime && it.wavetime > 0f) state.wavetime = it.wavetime
    }
    snapshot = null
    activeLevel = 0
    announcedLevel = 0
    level4Samples = 0
    if (autoPaused && state.isPaused) {
        state.set(GameState.State.playing)
        if (!silent) {
            broadcast("[green][游戏继续] 性能优化系统 已恢复当前游戏：{reason}".with("reason" to reason))
        }
    }
    autoPaused = false
    if (!silent && (oldLevel > 0 || restoredProcessors > 0 || hadAutoPause)) {
        broadcast("[green][压力措施] 已恢复玩法规则：{reason}".with("reason" to reason))
    }
}

fun setGamePaused(paused: Boolean, reason: String = "手动操作", operator: String = "系统") {
    if (paused) {
        if (!state.isPaused) {
            state.set(GameState.State.paused)
            broadcast("[yellow][游戏暂停] {operator} 已暂停当前游戏：{reason}".with("operator" to operator, "reason" to reason))
        }
    } else {
        if (state.isPaused) {
            state.set(GameState.State.playing)
            autoPaused = false
            broadcast("[green][游戏继续] {operator} 已恢复当前游戏：{reason}".with("operator" to operator, "reason" to reason))
        }
    }
}

private fun previousMode(): String =
    MdtStorage.getSetting(PREVIOUS_MODE_KEY)?.takeIf { it.isNotBlank() && it != "experimental" } ?: "normal"

private suspend fun forceChangeMapFallback(): Boolean {
    if (forceMapInProgress) return false
    forceMapInProgress = true
    try {
        val maps = MapRegistry.searchMaps().filter { it != MapManager.current }
        val next = maps.randomOrNull()
        if (next == null) {
            broadcast("[red][压力措施] 已达到实验性兜底换图阶段，但没有找到可用地图。".with())
            return false
        }
        broadcast("[red][压力措施] 实验性优化仍无法恢复，强制换图到：[white]{map.name}[]([yellow]{map.id}[])".with("map" to next))
        MdtStorage.setSetting(FORCE_MAP_BYPASS_KEY, "true")
        val ok = try {
            MapManager.loadMapSync(next)
        } finally {
            MdtStorage.setSetting(FORCE_MAP_BYPASS_KEY, null)
        }
        if (ok) {
            restorePressureRules("兜底换图完成", silent = true)
            val modeToRestore = previousMode()
            with(perfGuard) { setPerformanceMode(modeToRestore) }
            MdtStorage.setSetting(PREVIOUS_MODE_KEY, null)
            broadcast("[green][压力措施] 兜底换图完成，实验性优化已退出。".with())
        }
        return ok
    } finally {
        forceMapInProgress = false
    }
}

private suspend fun handleLevel4(mode: String) {
    level4Samples++
    if (level4Samples < level4FallbackSamples.coerceAtLeast(1)) return
    if (mode == "experimental") {
        forceChangeMapFallback()
    } else if (!state.isPaused) {
        autoPaused = true
        setGamePaused(true, "TPS长期过低，触发标准兜底暂停", "性能优化系统")
    }
}

private suspend fun tickActions() {
    val modeNow = with(perfGuard) { performanceMode() }
    if (modeNow == "off") {
        restorePressureRules("性能优化关闭")
        level4Samples = 0
        return
    }

    val s = with(pressure) { currentPressure() }
    if (s.mode == "off") {
        restorePressureRules("性能优化关闭")
        level4Samples = 0
        return
    }

    val trafficMbps = maxOf(s.currentTrafficMbps, s.averageTrafficMbps)
    if (modeNow == "experimental") {
        cleanupSevereTrafficIfNeeded(trafficMbps, s.trafficBudgetMbps)
    }

    if (s.level <= 0) {
        restorePressureRules("TPS/上行恢复")
        return
    }

    applyLevel(s.level, s.reason)
    if (modeNow == "experimental") {
        cleanupTopUnitTypesIfNeeded(s.level, s.tpsLevel, s.trafficLevel)
    }
    if (s.level >= 4) handleLevel4(modeNow) else level4Samples = 0
}

private suspend fun startPauseVote(starter: Player, paused: Boolean): Boolean {
    val desc = if (paused) "暂停当前游戏" else "继续当前游戏"
    val event = VoteEvent(
        thisScript,
        starter,
        voteDesc = desc.with(),
        extDesc = if (paused)
            "[yellow]通过后会将当前游戏状态切换为暂停。"
        else
            "[yellow]通过后会从暂停状态恢复。常驻性能优化的标准兜底暂停也可用此投票解除。",
        supportSingle = true,
    )
    if (!event.awaitResult()) return false
    setGamePaused(paused, "投票通过", starter.name)
    return true
}

listen<EventType.PlayerLeave> {
    if (with(perfGuard) { performanceMode() } != "experimental") return@listen

    val now = System.currentTimeMillis()
    val window = ppsWindowMillis.coerceAtLeast(500L)
    while (recentLeaveTimes.isNotEmpty() && now - recentLeaveTimes.first() > window) {
        recentLeaveTimes.removeFirst()
    }
    recentLeaveTimes.addLast(now)

    continuousLeaveCount = if (lastLeaveAt > 0L && now - lastLeaveAt <= window) continuousLeaveCount + 1 else 1
    lastLeaveAt = now

    if (now - lastPpsCleanupAt < ppsCleanupCooldownMillis.coerceAtLeast(1_000L)) return@listen
    val s = with(pressure) { currentPressure() }
    val trafficMbps = maxOf(s.currentTrafficMbps, s.averageTrafficMbps)
    if (!trafficOverRatio(trafficMbps, s.trafficBudgetMbps, ppsTrafficRatio.coerceIn(0.0, 10.0))) return@listen
    if (recentLeaveTimes.size >= ppsMinLeaves.coerceAtLeast(1) ||
        continuousLeaveCount >= ppsContinuousLeaves.coerceAtLeast(1)
    ) {
        cleanupPpsOverload(now, trafficMbps, s.trafficBudgetMbps)
    }
}

listen<EventType.WorldLoadEvent> {
    snapshot = null
    activeLevel = 0
    announcedLevel = 0
    level4Samples = 0
    autoPaused = false
    recentLeaveTimes.clear()
    continuousLeaveCount = 0
    lastLeaveAt = 0L
    saveDisabledLogicPositions(emptySet())
}

listen<EventType.ResetEvent> {
    snapshot = null
    activeLevel = 0
    announcedLevel = 0
    level4Samples = 0
    autoPaused = false
    recentLeaveTimes.clear()
    continuousLeaveCount = 0
    lastLeaveAt = 0L
    saveDisabledLogicPositions(emptySet())
}

onDisable {
    restorePressureRules("脚本卸载", silent = true)
}

onEnable {
    mayHaveDisabledLogicPositions = !MdtStorage.getSetting(DISABLED_LOGIC_POSITIONS_KEY).isNullOrBlank()

    launch(Dispatchers.game) {
        while (true) {
            delay(Duration.ofMillis(actionIntervalMillis.coerceAtLeast(1000L)).toMillis())
            tickActions()
        }
    }

    val script = this
    VoteEvent.VoteCommands += CommandInfo(script, "pause", "[yellow]暂停当前游戏[gray]（需50%同意）") {
        aliases = listOf("暂停", "pausegame")
        permission = "wayzer.vote.gamepause"
        body {
            startPauseVote(player!!, true)
        }
    }
    VoteEvent.VoteCommands += CommandInfo(script, "resume", "[yellow]继续当前游戏[gray]（需50%同意）") {
        aliases = listOf("继续", "unpause", "continue")
        permission = "wayzer.vote.gamepause"
        body {
            startPauseVote(player!!, false)
        }
    }
}

command("gamepause", "暂停/继续当前游戏") {
    usage = "<on|off|status>"
    aliases = listOf("pausegame", "pause", "resume")
    body {
        val op = arg.getOrNull(0)?.lowercase()
            ?: if (state.isPaused) "status" else "status"
        when (op) {
            "status", "状态" -> reply(
                (if (state.isPaused) "[yellow]当前游戏：已暂停" else "[green]当前游戏：运行中")
                    .with()
            )
            "on", "true", "1", "pause", "暂停" -> {
                if (!canManagePause(player)) returnReply("[red]权限不足：只有 3+级、4级/admin 或控制台可以直接暂停游戏。".with())
                setGamePaused(true, "管理员指令", player?.name ?: "控制台")
            }
            "off", "false", "0", "resume", "continue", "继续" -> {
                if (!canManagePause(player)) returnReply("[red]权限不足：只有 3+级、4级/admin 或控制台可以直接继续游戏。".with())
                setGamePaused(false, "管理员指令", player?.name ?: "控制台")
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.vote.gamepause")
