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
private val extremeLowTpsMillis by config.key(120_000L, "TPS持续低于5后多久才允许换图(ms)")
private val ppsTrafficRatio by config.key(0.60, "实验性疑似PPS顶满检测上行预算倍率")
private val ppsWindowMillis by config.key(2_000L, "实验性疑似PPS顶满退出检测窗口(ms)")
private val ppsMinLeaves by config.key(2, "实验性疑似PPS顶满窗口内退出人数阈值")
private val ppsContinuousLeaves by config.key(3, "实验性疑似PPS顶满连续退出人数阈值")
private val ppsCleanupCooldownMillis by config.key(30_000L, "实验性疑似PPS顶满清理冷却(ms)")
private val severeTrafficRatio by config.key(2.0, "实验性严重上行超预算清理倍率")
private val severeTrafficCleanupCooldownMillis by config.key(30_000L, "实验性严重上行超预算清理冷却(ms)")
private val level4TopUnitCleanupCooldownMillis by config.key(30_000L, "实验性压力等级4数量前三单位清理冷却(ms)")
private val cleanupBroadcastCooldownMillis by config.key(30_000L, "普通压力清理广播聚合间隔(ms)")
private val stalePressureSnapshotMillis by config.key(30_000L, "压力快照过期后停止自动措施(ms)")

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
private var disabledLogicPositionsCache: MutableSet<Int>? = null
private val recentLeaveTimes = ArrayDeque<Long>()
private var continuousLeaveCount = 0
private var lastLeaveAt = 0L
private var lastPpsCleanupAt = 0L
private var lastSevereTrafficCleanupAt = 0L
private var lastLevel4TopUnitCleanupAt = 0L
private var lowTpsSinceMillis = 0L
private var pendingFireCleanupCount = 0
private var pendingBulletCleanupCount = 0
private var pendingUnitCleanupCount = 0
private var pendingProcessorDisableCount = 0
private var pendingUnitCleanupLevel = 0
private var lastCleanupLogAt = 0L
private var lastActionErrorLogMillis = 0L
private var lastBroadcastErrorLogMillis = 0L
private var roundRecoveryAnnounced = false
private var ppsCleanupAnnounced = false
private var severeTrafficCleanupAnnounced = false
private var level4TopCleanupAnnounced = false

/**
 * 广播属于提示链路，不能因为单次发送/格式化异常阻断规则恢复、单位清理或TPS措施循环。
 */
private fun safePressureBroadcast(label: String, action: () -> Unit): Boolean {
    return runCatching {
        action()
        true
    }.getOrElse {
        val now = System.currentTimeMillis()
        if (now - lastBroadcastErrorLogMillis >= 30_000L) {
            lastBroadcastErrorLogMillis = now
            logger.warning("压力措施广播失败($label)，措施仍会继续：${it.message}")
        }
        false
    }
}

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
    runCatching { Call.setRule(name, target.toString()) }.onFailure {
        // 规则本地状态已经生效；单次广播失败不能中断整个压力执行循环。
        logger.warning("同步压力规则失败 $name=$target：${it.message}")
    }
    return true
}

private fun syncIntRule(name: String, current: Int, target: Int, setter: (Int) -> Unit): Boolean {
    if (current == target) return false
    setter(target)
    runCatching { Call.setRule(name, target.toString()) }.onFailure {
        logger.warning("同步压力规则失败 $name=$target：${it.message}")
    }
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
    return fires.count { runCatching { it.remove() }.isSuccess }
}

private fun clearBullets(): Int {
    val bullets = Groups.bullet.toList()
    return bullets.count { runCatching { it.remove() }.isSuccess }
}

private fun disabledLogicPositions(): MutableSet<Int> {
    disabledLogicPositionsCache?.let { return it }
    val loaded = runCatching {
        MdtStorage.getSetting(DISABLED_LOGIC_POSITIONS_KEY)
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toMutableSet()
    }.onFailure {
        logger.warning("读取压力系统已关闭处理器记录失败：${it.message}")
    }.getOrDefault(mutableSetOf())
    disabledLogicPositionsCache = loaded
    mayHaveDisabledLogicPositions = loaded.isNotEmpty()
    return loaded
}

private fun saveDisabledLogicPositions(positions: Set<Int>) {
    val next = positions.toMutableSet()
    disabledLogicPositionsCache = next
    mayHaveDisabledLogicPositions = next.isNotEmpty()
    runCatching {
        MdtStorage.setSetting(
            DISABLED_LOGIC_POSITIONS_KEY,
            next.sorted().joinToString(",").takeIf { it.isNotBlank() }
        )
    }.onFailure {
        logger.warning("保存压力系统已关闭处理器记录失败：${it.message}")
    }
}

private fun disableLogicProcessors(): Int {
    val positions = disabledLogicPositions()
    var count = 0
    Groups.build.toList().forEach { build ->
        if (build is LogicBlock.LogicBuild && build.enabled) {
            runCatching {
                positions += build.tile.pos()
                build.enabled = false
                count++
            }
        }
    }
    // 压力持续期间每5秒都会检查一次；只有新关闭处理器时才落库，避免反复数据库IO拖低TPS。
    if (count > 0) saveDisabledLogicPositions(positions)
    return count
}

private fun restoreLogicProcessors(): Int {
    if (!mayHaveDisabledLogicPositions) return 0
    val positions = disabledLogicPositions()
    if (positions.isEmpty()) {
        mayHaveDisabledLogicPositions = false
        return 0
    }
    var count = 0
    Groups.build.toList().forEach { build ->
        if (build is LogicBlock.LogicBuild && build.tile.pos() in positions) {
            if (runCatching { build.enabled = true }.isSuccess) count++
        }
    }
    saveDisabledLogicPositions(emptySet())
    return count
}

private fun playerTeams(): Set<Team> = Groups.player.map { it.team() }.toSet()

// PPS异常退出清理保留用户指定的低阶辅助线：mono、T2/T3陆辅pulsar/quasar、
// T2/T3空辅poly/mega。它们也有战斗能力，但本清理规则按“辅助/挖矿用途”保护，
// 不能被普通压力清理的战斗分类覆盖。
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
    return candidates.count { unit -> runCatching { unit.kill() }.isSuccess }
}

private fun killPressureUnitsWhere(predicate: (MindustryUnit) -> Boolean): Int {
    val candidates = Groups.unit.toList()
        .asSequence()
        .filter(::isPressureUnitCandidate)
        .filter(predicate)
        .toList()
    return candidates.count { unit -> runCatching { unit.kill() }.isSuccess }
}

private fun removeScatheTurrets(): Int {
    val builds = Groups.build.toList()
        .filter { it.block.name == "scathe" }
    return builds.count { build -> runCatching { build.kill() }.isSuccess }
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

    if (!ppsCleanupAnnounced) {
        val announced = safePressureBroadcast("PPS清理") {
            broadcast(
                "[red][服务器提示] 服务器出现异常高负载，已自动清理部分单位以缓解卡顿；若持续出现，请适当减少单位数量。"
                    .with()
            )
        }
        if (announced) ppsCleanupAnnounced = true
    }
    logger.info("[压力措施] 疑似PPS顶满清理完成：单位 $units / scathe炮台 $turrets；估算上行 ${"%.2f".format(trafficMbps)} Mbps / 预算 ${"%.2f".format(budgetMbps)} Mbps")
}

private fun cleanupSevereTrafficIfNeeded(trafficMbps: Double, budgetMbps: Double) {
    if (!trafficOverRatio(trafficMbps, budgetMbps, severeTrafficRatio.coerceAtLeast(1.0))) return
    val now = System.currentTimeMillis()
    if (now - lastSevereTrafficCleanupAt < severeTrafficCleanupCooldownMillis.coerceAtLeast(1_000L)) return

    lastSevereTrafficCleanupAt = now
    val units = killPressureUnitsWhere { unitTier(it.type()) <= 4 }
    if (!severeTrafficCleanupAnnounced) {
        val announced = safePressureBroadcast("严重上行清理") {
            broadcast("[red][服务器提示] 服务器上行压力过高，已自动清理部分单位以保障流畅；如卡顿持续，请减少单位数量。".with())
        }
        if (announced) severeTrafficCleanupAnnounced = true
    }
    logger.info("[压力措施] 严重上行超预算清理完成：单位 $units；估算上行 ${"%.2f".format(trafficMbps)} Mbps / 预算 ${"%.2f".format(budgetMbps)} Mbps")
}

private fun cleanupTopUnitTypesIfNeeded(level: Int) {
    if (level < 4) return
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
    candidates.filter { it.type() in topSet }.forEach { unit ->
        if (runCatching { unit.kill() }.isSuccess) removed++
    }
    val summary = topTypes.joinToString("、") { "${it.key.localizedName}(${it.value.size})" }
    if (!level4TopCleanupAnnounced) {
        val announced = safePressureBroadcast("等级4前三单位清理") {
            broadcast("[red][服务器提示] 服务器压力已到较高等级，已自动清理部分单位；如卡顿持续，请减少单位数量。".with())
        }
        if (announced) level4TopCleanupAnnounced = true
    }
    logger.info("[压力措施] 等级4数量前三单位清理完成：$summary；移除 $removed 个")
}

private fun applyUnitCap(): Boolean {
    var changed = setDisableUnitCapRule(false)
    val target = level2UnitCap.coerceAtLeast(10)
    val next = if (state.rules.unitCap <= 0) target else minOf(state.rules.unitCap, target)
    changed = setUnitCapRule(next) || changed
    return changed
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

private fun maxTierForLevel(level: Int): Int = when (level.coerceIn(1, 4)) {
    1 -> level1MaxUnitTier
    2 -> level2MaxUnitTier
    3 -> level3MaxUnitTier
    else -> level4MaxUnitTier
}.coerceIn(1, 5)

private fun flushPendingCleanup(force: Boolean = false): Boolean {
    if (pendingFireCleanupCount <= 0 && pendingBulletCleanupCount <= 0 &&
        pendingUnitCleanupCount <= 0 && pendingProcessorDisableCount <= 0
    ) return true
    val now = System.currentTimeMillis()
    val cooldown = cleanupBroadcastCooldownMillis.coerceAtLeast(5_000L)
    if (!force && lastCleanupLogAt > 0L && now - lastCleanupLogAt < cooldown) return false

    val level = pendingUnitCleanupLevel.coerceIn(1, 4)
    val actions = mutableListOf<String>()
    if (pendingFireCleanupCount > 0) actions += "清理火焰${pendingFireCleanupCount}处"
    if (pendingBulletCleanupCount > 0) actions += "清理子弹${pendingBulletCleanupCount}发"
    if (pendingUnitCleanupCount > 0) {
        actions += "击杀清理T${maxTierForLevel(level)}及以下压力单位${pendingUnitCleanupCount}个"
    }
    if (pendingProcessorDisableCount > 0) actions += "关闭逻辑处理器${pendingProcessorDisableCount}个"
    // 持续介入清理属于高频动作，不再反复向玩家广播，只保留日志，避免刷屏。
    logger.info("[压力措施] 普通压力清理：level=$level ${actions.joinToString("、")}")
    pendingFireCleanupCount = 0
    pendingBulletCleanupCount = 0
    pendingUnitCleanupCount = 0
    pendingProcessorDisableCount = 0
    pendingUnitCleanupLevel = 0
    lastCleanupLogAt = now
    return true
}

private fun recordCleanup(level: Int, fires: Int, bullets: Int, units: Int, processors: Int) {
    if (fires <= 0 && bullets <= 0 && units <= 0 && processors <= 0) return
    pendingFireCleanupCount += fires.coerceAtLeast(0)
    pendingBulletCleanupCount += bullets.coerceAtLeast(0)
    pendingUnitCleanupCount += units.coerceAtLeast(0)
    pendingProcessorDisableCount += processors.coerceAtLeast(0)
    pendingUnitCleanupLevel = maxOf(pendingUnitCleanupLevel, level.coerceIn(1, 4))
    flushPendingCleanup()
}

private fun clearPendingCleanup() {
    pendingFireCleanupCount = 0
    pendingBulletCleanupCount = 0
    pendingUnitCleanupCount = 0
    pendingProcessorDisableCount = 0
    pendingUnitCleanupLevel = 0
}

private fun pressureLevelName(level: Int): String = when (level.coerceIn(1, 4)) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    else -> "四"
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
    }
    if (level >= 2) {
        setWaveTimerRule(false)
        setWaveSendingRule(false)
        state.wavetime = maxOf(state.wavetime, 60f * 60f * 10f)
        applyUnitCap()
    }
    if (level >= 3) {
        setDisableWorldProcessorsRule(true)
        processors = disableLogicProcessors()
    }
    // 每轮只使用当前等级的总清理预算；高等级的maxTier本身已包含低阶单位，
    // 不再累加L1+L2+L3+L4导致等级4单轮最多误清750个单位。
    units = when (level) {
        1 -> removePressureUnits(level1RemovePerPass, includePlayerTeams = false, maxTier = level1MaxUnitTier)
        2 -> removePressureUnits(level2RemovePerPass, includePlayerTeams = false, maxTier = level2MaxUnitTier)
        3 -> removePressureUnits(level3RemovePerPass, includePlayerTeams = true, maxTier = level3MaxUnitTier)
        else -> removePressureUnits(level4RemovePerPass, includePlayerTeams = true, maxTier = level4MaxUnitTier)
    }

    if (level > announcedLevel) {
        // 本局首次进入该等级才向玩家播报一次；已播报过的等级重新进入不再重复提示。
        // 播报前先补发上一段冷却窗口中累计的清理日志，避免吞掉清理数量。
        flushPendingCleanup(force = true)
        val broadcasted = safePressureBroadcast("进入等级$level") {
            broadcast(
                ("[yellow][服务器提示] 服务器负载过高（{reason}），已自动开启[white]{level}[yellow]级性能保护：临时暂停部分玩法并清理压力单位，以缓解卡顿。请适当减少单位数量，帮助服务器恢复流畅。").with(
                    "level" to pressureLevelName(level),
                    "reason" to reason,
                )
            )
        }
        if (broadcasted) {
            announcedLevel = level
        } else {
            // 进入等级提示失败时保留本轮实际清理数量，下一轮重试时不会丢失事实。
            recordCleanup(level, fires, bullets, units, processors)
        }
    } else {
        recordCleanup(level, fires, bullets, units, processors)
    }

    if (previousLevel > level) {
        // 已播报等级标记保持单调：本局内重新进入已播报过的等级不再重复提示。
        if (restoredProcessors > 0) {
            safePressureBroadcast("降档恢复处理器") {
                broadcast("[green][服务器提示] 服务器压力有所缓解，已自动恢复部分处理器功能。".with())
            }
        } else {
            logger.info("[压力措施] 压力等级降至 $level")
        }
    }
    flushPendingCleanup()
    activeLevel = level
}

private fun restorePressureRules(reason: String = "压力恢复", silent: Boolean = false) {
    if (silent) clearPendingCleanup() else flushPendingCleanup(force = true)
    if (snapshot == null && activeLevel <= 0 && !autoPaused && !mayHaveDisabledLogicPositions) return
    val hadAutoPause = autoPaused
    restoreLogicProcessors()
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
    level4Samples = 0
    lastCleanupLogAt = 0L
    if (autoPaused && state.isPaused) {
        state.set(GameState.State.playing)
    }
    autoPaused = false
    logger.info("[压力措施] 压力规则恢复：$reason")
    // 每局只向玩家播报一次恢复，避免 L2↔0 抖动时反复刷屏；内容合并游戏恢复与玩法恢复。
    if (!silent && !roundRecoveryAnnounced) {
        val text = if (hadAutoPause)
            "[green][服务器提示] 服务器压力已恢复正常，游戏已解除暂停，性能保护自动关闭。"
        else
            "[green][服务器提示] 服务器压力已恢复正常，性能保护已自动解除。"
        val recovered = safePressureBroadcast("恢复") { broadcast(text.with()) }
        if (recovered) roundRecoveryAnnounced = true
    }
}

private var lastRestoreRequestVersion = 0L

private fun checkExternalRestoreRequest() {
    val version = with(perfGuard) { pressureRestoreRequestVersion() }
    if (version == lastRestoreRequestVersion) return
    lastRestoreRequestVersion = version
    restorePressureRules("性能优化状态被手动恢复")
    lowTpsSinceMillis = 0L
    level4Samples = 0
}

fun setGamePaused(paused: Boolean, reason: String = "手动操作", operator: String = "系统") {
    if (paused) {
        if (!state.isPaused) {
            state.set(GameState.State.paused)
            safePressureBroadcast("暂停游戏") {
                broadcast("[yellow][服务器提示] {operator} 已暂停当前游戏：{reason}".with("operator" to operator, "reason" to reason))
            }
        }
    } else {
        if (state.isPaused) {
            state.set(GameState.State.playing)
            autoPaused = false
            safePressureBroadcast("手动恢复游戏") {
                broadcast("[green][服务器提示] {operator} 已恢复当前游戏：{reason}".with("operator" to operator, "reason" to reason))
            }
        }
    }
}

private suspend fun forceChangeMapFallback(): Boolean {
    if (forceMapInProgress) return false
    forceMapInProgress = true
    try {
        val maps = MapRegistry.searchMaps().filter { it != MapManager.current }
        val next = maps.randomOrNull()
        if (next == null) {
            safePressureBroadcast("兜底换图无地图") {
                broadcast("[red][服务器提示] 服务器持续严重卡顿，但未找到可切换的地图。".with())
            }
            return false
        }
        safePressureBroadcast("开始兜底换图") {
            broadcast("[red][服务器提示] 服务器持续严重卡顿，已自动切换地图以恢复流畅：[white]{map.name}[]（[yellow]{map.id}[]）".with("map" to next))
        }
        MdtStorage.setSetting(FORCE_MAP_BYPASS_KEY, "true")
        val ok = try {
            MapManager.loadMapSync(next)
        } finally {
            MdtStorage.setSetting(FORCE_MAP_BYPASS_KEY, null)
        }
        if (ok) {
            restorePressureRules("兜底换图完成", silent = true)
            safePressureBroadcast("兜底换图完成") {
                broadcast("[green][服务器提示] 地图切换完成，服务器恢复正常。".with())
            }
        }
        return ok
    } finally {
        forceMapInProgress = false
    }
}

private suspend fun handleLevel4() {
    level4Samples++
    if (lowTpsSinceMillis <= 0L || System.currentTimeMillis() - lowTpsSinceMillis < extremeLowTpsMillis.coerceAtLeast(60_000L)) {
        if (level4Samples >= 3 && !state.isPaused) {
            autoPaused = true
            setGamePaused(true, "TPS持续过低，触发性能保护暂停", "性能优化系统")
        }
        return
    }
    if (!forceMapInProgress) {
        forceChangeMapFallback()
    }
}

private fun updateExtremeTpsTimer(currentTps: Int, averageTps: Double) {
    // “连续2分钟均低于5”按每次采样的当前TPS和滑动均值同时满足计算；
    // 任一当前采样恢复到5及以上都立即清零，宁可晚换图，不能被低均值提前触发。
    if (currentTps < 5 && averageTps < 5.0) {
        if (lowTpsSinceMillis <= 0L) lowTpsSinceMillis = System.currentTimeMillis()
    } else {
        lowTpsSinceMillis = 0L
    }
}

private suspend fun tickActions() {
    checkExternalRestoreRequest()
    val modeNow = with(perfGuard) { performanceMode() }
    if (modeNow == "off") {
        restorePressureRules("性能优化关闭", silent = true)
        level4Samples = 0
        lowTpsSinceMillis = 0L
        return
    }

    val s = with(pressure) { currentPressure() }
    if (System.currentTimeMillis() - s.updatedAtMillis > stalePressureSnapshotMillis.coerceAtLeast(5_000L)) {
        // 判断脚本数据过期时宁可恢复可逆措施，也不能依据旧的高压快照继续清理单位。
        restorePressureRules("压力判断快照已过期", silent = true)
        level4Samples = 0
        lowTpsSinceMillis = 0L
        return
    }
    if (s.mode == "off") {
        restorePressureRules("性能优化关闭", silent = true)
        level4Samples = 0
        lowTpsSinceMillis = 0L
        return
    }

    // 世界流/音乐/CP同步不能触发清理，只用游戏同步上行判断。
    val trafficMbps = maxOf(s.currentSyncTrafficMbps, s.averageSyncTrafficMbps)
    // 严重超量要求已进入游戏同步上行的最高压力级；避免压力采样尚未暖机时，
    // 单个普通包尖峰就触发 T4 以下全场清理。世界/资产流仍不会进入该等级。
    if (s.trafficLevel >= 3) cleanupSevereTrafficIfNeeded(trafficMbps, s.trafficBudgetMbps)

    if (s.level <= 0) {
        restorePressureRules("TPS/游戏同步上行恢复")
        return
    }

    applyLevel(s.level, s.reason)
    cleanupTopUnitTypesIfNeeded(s.level)
    updateExtremeTpsTimer(s.currentTps, s.averageTps)
    if (s.level >= 4) handleLevel4() else {
        level4Samples = 0
        if (s.averageTps >= 5.0) lowTpsSinceMillis = 0L
    }
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
    if (with(perfGuard) { performanceMode() } == "off") return@listen

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
    if (now - s.updatedAtMillis > stalePressureSnapshotMillis.coerceAtLeast(5_000L)) return@listen
    // 玩家超时与断开不应因世界流瞬时上行触发单位清理。
    val trafficMbps = maxOf(s.currentSyncTrafficMbps, s.averageSyncTrafficMbps)
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
    roundRecoveryAnnounced = false
    ppsCleanupAnnounced = false
    severeTrafficCleanupAnnounced = false
    level4TopCleanupAnnounced = false
    level4Samples = 0
    autoPaused = false
    recentLeaveTimes.clear()
    continuousLeaveCount = 0
    lastLeaveAt = 0L
    lowTpsSinceMillis = 0L
    clearPendingCleanup()
    lastCleanupLogAt = 0L
    saveDisabledLogicPositions(emptySet())
}

listen<EventType.ResetEvent> {
    snapshot = null
    activeLevel = 0
    announcedLevel = 0
    roundRecoveryAnnounced = false
    ppsCleanupAnnounced = false
    severeTrafficCleanupAnnounced = false
    level4TopCleanupAnnounced = false
    level4Samples = 0
    autoPaused = false
    recentLeaveTimes.clear()
    continuousLeaveCount = 0
    lastLeaveAt = 0L
    lowTpsSinceMillis = 0L
    clearPendingCleanup()
    lastCleanupLogAt = 0L
    saveDisabledLogicPositions(emptySet())
}

onDisable {
    restorePressureRules("脚本卸载", silent = true)
}

onEnable {
    disabledLogicPositionsCache = null
    disabledLogicPositions()

    launch(Dispatchers.game) {
        while (true) {
            delay(Duration.ofMillis(actionIntervalMillis.coerceAtLeast(1000L)).toMillis())
            try {
                tickActions()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 单个实体、规则同步或数据库状态异常不能让TPS措施永久停止。
                val now = System.currentTimeMillis()
                if (now - lastActionErrorLogMillis >= 30_000L) {
                    lastActionErrorLogMillis = now
                    logger.warning("服务器压力措施执行异常，下一轮将继续：${e.message}")
                }
            }
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
