package mapScript.tags

import arc.Events
import arc.math.Mathf
import arc.util.Align
import coreLibrary.lib.util.loop
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.content.UnitTypes
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Building
import mindustry.gen.Bullet
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Unit
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.defense.ForceProjector
import mindustry.world.blocks.environment.Cliff
import mindustry.world.blocks.environment.StaticWall
import mindustry.world.blocks.storage.CoreBlock
import kotlin.math.max
import kotlin.math.min

/**
 * 洪水模式（MDT 完整兼容实现）
 *
 * 说明：
 * - 参考外部 mindustry_flood 的玩法与数值：泉眼/充能泉眼、压制/净化、孢子炮击、震爆塔、雷达、力墙/单位特性。
 * - 外部工程原始脚本不是当前 ScriptAgent mapScript 标签格式，且存在旧版本 API/Java 转 Kotlin 遗留错误；
 *   因此这里重写为一份可加载、带性能上限的标签脚本。
 * - 兼容地图标签 `@flood` 与 `@floodV2`。当前没有完整 floodV2 私有脚本时，`@floodV2` 会落到本脚本。
 */

registerMapTag("@flood")
registerMapTag("@floodV2")

modeIntroduce(
    "洪水模式", """
    创意来源：mindustry_flood / Lemon 私有 floodV2
    MDT完整兼容实现：
    1. 洪水队伍的核心/发射台/加速器类建筑会作为泉眼。
    2. 泉眼持续生成洪水，洪水会向可放置地面扩散。
    3. 洪水会伤害非洪水队伍建筑与地面单位。
    4. 普通泉眼受到持续火力会被短暂压制，压制期间不再产生洪水；被高水位包围会升级。
    5. 在普通泉眼附近启动满功率冲击反应堆可净化/摧毁该泉眼。
    6. 充能泉眼会周期性充能、爆发产水、溢出回血/升级。
    7. 钍反应堆/孢子发射器、震爆塔、雷达会提供远程投洪水/定向堆洪水/锁定单位打击。
    8. 力墙与单位护盾会吸收洪水并过载爆洪；洪水爬虫接触建筑会爆洪；天垠免疫洪水且无法压制洪水建筑。
    9. 同时压制/净化全部普通泉眼，且充能泉眼已被摧毁时，会判定通关。
    10. 当前 `@floodV2` 标签会兼容启用本服 `@flood` 逻辑。
""".trimIndent()
)

private data class RegularEmitterKind(
    val block: Block,
    val amount: Float,
    val intervalSeconds: Int,
    val upgradeThreshold: Float,
    val nextBlock: Block? = null,
)

private data class ChargedEmitterKind(
    val block: Block,
    val intervalSeconds: Int,
    val amount: Float,
    val chargePulse: Float,
    val chargeCap: Float,
    val nextBlock: Block? = null,
)

private data class FloodEmitterState(
    val tileKey: Int,
    var suppressedUntil: Long = 0L,
    var damageWindowStart: Long = 0L,
    var damageAccum: Float = 0f,
    var emitCounter: Int = 0,
    var upgradeProgress: Float = 0f,
)

private data class ChargedEmitterState(
    val tileKey: Int,
    var counter: Int = 0,
    var charge: Float = 0f,
    var overflow: Float = 0f,
    var emitting: Boolean = false,
    var burstLeftMillis: Long = 0L,
)

private data class RadarState(
    val tileKey: Int,
    var targetId: Int = -1,
    var chargeMillis: Long = 0L,
)

private data class PendingFloodStrike(
    val sourceX: Float,
    val sourceY: Float,
    val targetX: Float,
    val targetY: Float,
    val radius: Float,
    val amount: Float,
    val arriveAt: Long,
)

private val floodLevels = linkedMapOf<Int, Float>()
private val floodVisualTiles = linkedSetOf<Int>()
private val emitterTiles = linkedSetOf<Int>()
private val regularEmitterStates = linkedMapOf<Int, FloodEmitterState>()
private val chargedEmitterStates = linkedMapOf<Int, ChargedEmitterState>()
private val sporeCooldowns = linkedMapOf<Int, Long>()
private val towerCooldowns = linkedMapOf<Int, Long>()
private val radarStates = linkedMapOf<Int, RadarState>()
private val pendingFloodStrikes = mutableListOf<PendingFloodStrike>()
private val floodBlocks = listOf(
    Blocks.scrapWall,
    Blocks.titaniumWall,
    Blocks.thoriumWall,
    Blocks.plastaniumWall,
    Blocks.phaseWall,
    Blocks.surgeWall,
    Blocks.reinforcedSurgeWall,
    Blocks.berylliumWall,
    Blocks.tungstenWall,
    Blocks.carbideWall,
)
private val floodBlockSet = floodBlocks.toSet()
private val directions = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

private lateinit var floodTeam: Team
private var updateCount = 0
private var allControlledSince = 0L
private var gameOverTriggered = false
private var hadAnyEmitter = false
private var lastSporeLauncherCount = 0
private var lastFloodTowerCount = 0
private var lastRadarCount = 0
private var lastForceProjectorCount = 0
private val floodTickIntervalMillis by config.key(1_000L, "洪水主循环间隔(ms)")
private val floodSlowTickWarnMillis by config.key(120L, "洪水单次计算超过该耗时时打印警告(ms)")
private var lastFloodRuntimeErrorAt = 0L
private var lastFloodSlowWarnAt = 0L

private fun hasTag(name: String): Boolean = state.rules.tags.keys().contains(name)

private fun tagValue(name: String): String = state.rules.tags.get(name).orEmpty()

private fun tagOff(value: String): Boolean {
    val v = value.trim().lowercase()
    return v == "off" || v == "false" || v == "0" || v == "disable" || v == "disabled"
}

private fun parseTeam(raw: String): Team {
    val value = raw.trim().lowercase()
    return when (value) {
        "", "blue" -> Team.blue
        "wave" -> state.rules.waveTeam
        "default" -> state.rules.defaultTeam
        "sharded" -> Team.sharded
        "crux" -> Team.crux
        "green" -> Team.green
        "purple", "malis" -> Team.malis
        "derelict" -> Team.derelict
        else -> value.toIntOrNull()?.let { Team.get(it) } ?: Team.blue
    }
}

private val maxLevel: Float get() = state.rules.tags.getFloat("@floodMaxLevel", 10.5f)
private val emitAmount: Float get() = state.rules.tags.getFloat("@floodEmit", 2.4f)
private val spreadRate: Float get() = state.rules.tags.getFloat("@floodSpread", 0.24f).coerceIn(0.02f, 0.45f)
private val evaporation: Float get() = state.rules.tags.getFloat("@floodEvaporation", 0.995f).coerceIn(0.90f, 1.0f)
private val buildDamage: Float get() = state.rules.tags.getFloat("@floodBuildDamage", 0.28f)
private val unitDamage: Float get() = state.rules.tags.getFloat("@floodUnitDamage", 1.6f)
private val maxFloodTiles: Int get() = state.rules.tags.getInt("@floodMaxTiles", 9000).coerceAtLeast(100)
private val suppressDamage: Float get() = state.rules.tags.getFloat("@floodSuppressDamage", 1500f).coerceAtLeast(1f)
private val suppressWindowMillis: Long get() = (state.rules.tags.getFloat("@floodSuppressWindow", 3f) * 1000f).toLong().coerceAtLeast(500L)
private val suppressTimeoutMillis: Long get() = (state.rules.tags.getFloat("@floodSuppressTime", 3f) * 1000f).toLong().coerceAtLeast(1000L)
private val nullificationPeriodMillis: Long get() = (state.rules.tags.getFloat("@floodNullifyPeriod", 10f) * 1000f).toLong().coerceAtLeast(1000L)
private val impactNullifyRange: Float get() = state.rules.tags.getFloat("@floodNullifyRange", 16f * 8f).coerceAtLeast(8f)
private val impactWarmupRequired: Float get() = state.rules.tags.getFloat("@floodImpactWarmup", 0.98f).coerceIn(0.5f, 1f)
private val impactPowerRequired: Float get() = state.rules.tags.getFloat("@floodImpactPower", 0.99f).coerceIn(0.1f, 1f)
private val regularUpgradeShardThreshold: Float get() = state.rules.tags.getFloat("@floodUpgradeShard", 30f).coerceAtLeast(1f)
private val regularUpgradeFoundationThreshold: Float get() = state.rules.tags.getFloat("@floodUpgradeFoundation", 3000f).coerceAtLeast(1f)
private val regularUpgradePressure: Float get() = state.rules.tags.getFloat("@floodUpgradePressure", maxLevel * 0.90f).coerceIn(1f, maxLevel)
private val chargedChargeScale: Float get() = state.rules.tags.getFloat("@floodChargedChargeScale", 33.33f).coerceAtLeast(1f)
private val chargedBurstScale: Float get() = state.rules.tags.getFloat("@floodChargedBurstScale", 0.55f).coerceAtLeast(0.05f)
private val chargedBurstSecondFactor: Float get() = state.rules.tags.getFloat("@floodChargedBurstSecondFactor", 0.03f).coerceIn(0.005f, 0.2f)
private val chargedHealPerOverflow: Float get() = state.rules.tags.getFloat("@floodChargedHealPerOverflow", 0.08f).coerceIn(0f, 1f)
private val sporeCooldownMillis: Long get() = (state.rules.tags.getFloat("@floodSporeCooldown", 18f) * 1000f).toLong().coerceAtLeast(2000L)
private val sporeRadius: Float get() = state.rules.tags.getFloat("@floodSporeRadius", 5f).coerceIn(1f, 20f)
private val sporeAmount: Float get() = state.rules.tags.getFloat("@floodSporeAmount", 20f).coerceAtLeast(1f)
private val sporeTargetOffset: Float get() = state.rules.tags.getFloat("@floodSporeTargetOffset", 256f).coerceAtLeast(0f)
private val sporeTravelMillis: Long get() = (state.rules.tags.getFloat("@floodSporeTravel", 3f) * 1000f).toLong().coerceAtLeast(500L)
private val sporeInterceptRange: Float get() = state.rules.tags.getFloat("@floodSporeInterceptRange", 120f).coerceAtLeast(8f)
private val towerCooldownMillis: Long get() = (state.rules.tags.getFloat("@floodTowerInterval", 2f) * 1000f).toLong().coerceAtLeast(500L)
private val towerRange: Float get() = state.rules.tags.getFloat("@floodTowerRange", 300f).coerceAtLeast(16f)
private val towerDeposit: Float get() = state.rules.tags.getFloat("@floodTowerDeposit", 1.2f).coerceAtLeast(0.05f)
private val towerRadius: Float get() = state.rules.tags.getFloat("@floodTowerRadius", 2.5f).coerceIn(1f, 12f)
private val radarRange: Float get() = state.rules.tags.getFloat("@floodRadarRange", 320f).coerceAtLeast(16f)
private val radarChargeMillis: Long get() = (state.rules.tags.getFloat("@floodRadarCharge", 4f) * 1000f).toLong().coerceAtLeast(500L)
private val radarBeamDamage: Float get() = state.rules.tags.getFloat("@floodRadarDamage", 300f).coerceAtLeast(1f)
private val radarFloodAmount: Float get() = state.rules.tags.getFloat("@floodRadarFlood", 3f).coerceAtLeast(0f)
private val forceProjectorAbsorbPerTick: Float get() = state.rules.tags.getFloat("@floodShieldAbsorb", 2.5f).coerceAtLeast(0.1f)
private val buildShieldDamageMultiplier: Float get() = state.rules.tags.getFloat("@floodBuildShieldDamageMultiplier", 28f).coerceAtLeast(0.1f)
private val unitShieldDamageMultiplier: Float get() = state.rules.tags.getFloat("@floodUnitShieldDamageMultiplier", 20f).coerceAtLeast(0.1f)
private val shieldBoostProtectionMultiplier: Float get() = state.rules.tags.getFloat("@floodShieldBoostProtection", 0.5f).coerceIn(0.05f, 1f)
private val shieldCreeperDropAmount: Float get() = state.rules.tags.getFloat("@floodShieldDropAmount", 7f).coerceAtLeast(0f)
private val shieldCreeperDropRadius: Float get() = state.rules.tags.getFloat("@floodShieldDropRadius", 4f).coerceIn(1f, 12f)
private val creepUnitAmount: Float get() = state.rules.tags.getFloat("@floodCreepAmount", 14f).coerceAtLeast(1f)
private val creepUnitRadius: Float get() = state.rules.tags.getFloat("@floodCreepRadius", 4f).coerceIn(1f, 16f)
private val creepUnitTriggerRange: Float get() = state.rules.tags.getFloat("@floodCreepTriggerRange", 14f).coerceAtLeast(4f)

private fun Tile.key(): Int = x.toInt() + y.toInt() * world.width()

private fun tileOf(key: Int): Tile? {
    val w = world.width().coerceAtLeast(1)
    return world.tile(key % w, key / w)
}

private fun isFloodVisual(block: Block): Boolean = block in floodBlockSet

private fun regularKind(block: Block): RegularEmitterKind? = when (block) {
    Blocks.coreShard -> RegularEmitterKind(Blocks.coreShard, 3f, 1, regularUpgradeShardThreshold, Blocks.coreFoundation)
    Blocks.coreFoundation -> RegularEmitterKind(Blocks.coreFoundation, 5f, 1, regularUpgradeFoundationThreshold, Blocks.coreNucleus)
    Blocks.coreNucleus -> RegularEmitterKind(Blocks.coreNucleus, 7f, 1, -1f, null)
    else -> null
}

private fun chargedKind(block: Block): ChargedEmitterKind? = when (block) {
    Blocks.coreBastion -> ChargedEmitterKind(Blocks.coreBastion, 2, 9f, 0.4f, 600f, Blocks.coreCitadel)
    Blocks.coreCitadel -> ChargedEmitterKind(Blocks.coreCitadel, 1, 10f, 0.7f, 1800f, Blocks.coreAcropolis)
    Blocks.coreAcropolis -> ChargedEmitterKind(Blocks.coreAcropolis, 1, 10f, 0.99f, 2000f, null)
    else -> null
}

private fun isChargedEmitter(build: Building): Boolean {
    if (!::floodTeam.isInitialized) return false
    if (build.team != floodTeam || build.health <= 0f) return false
    return chargedKind(build.block) != null
}

private fun isLaunchOrAccelerator(block: Block): Boolean {
    val name = block.name.lowercase()
    return name.contains("launch-pad") || name.contains("accelerator")
}

private fun isRegularEmitter(build: Building): Boolean {
    if (!::floodTeam.isInitialized) return false
    if (build.team != floodTeam || build.health <= 0f || isChargedEmitter(build)) return false
    val block = build.block
    if (regularKind(block) != null) return true
    if (block is CoreBlock) return true
    return isLaunchOrAccelerator(block)
}

private fun isEmitter(build: Building): Boolean = isRegularEmitter(build) || isChargedEmitter(build)

private fun isSporeLauncher(build: Building): Boolean {
    if (build.team != floodTeam || build.health <= 0f || !build.enabled) return false
    val name = build.block.name.lowercase()
    return build.block == Blocks.thoriumReactor || (name.contains("spore") && name.contains("launcher"))
}

private fun isFloodTower(build: Building): Boolean {
    if (build.team != floodTeam || build.health <= 0f || !build.enabled) return false
    val name = build.block.name.lowercase()
    return build.block == Blocks.shockwaveTower || name.contains("flood-projector") || name.contains("creep-tower")
}

private fun isFloodRadar(build: Building): Boolean {
    if (build.team != floodTeam || build.health <= 0f || !build.enabled) return false
    return build.block == Blocks.radar || build.block.name.lowercase().contains("flood-radar")
}

private fun isFloodCreepUnit(unit: Unit): Boolean = unit.type == UnitTypes.crawler ||
    unit.type == UnitTypes.atrax ||
    unit.type == UnitTypes.spiroct ||
    unit.type == UnitTypes.arkyid ||
    unit.type == UnitTypes.toxopid

private fun isSuppressed(state: FloodEmitterState, now: Long = System.currentTimeMillis()): Boolean =
    now < state.suppressedUntil

private fun activeRegularEmitterBuild(state: FloodEmitterState): Building? {
    val build = tileOf(state.tileKey)?.build ?: return null
    return if (isRegularEmitter(build)) build else null
}

private fun activeChargedEmitterBuild(state: ChargedEmitterState): Building? {
    val build = tileOf(state.tileKey)?.build ?: return null
    return if (isChargedEmitter(build)) build else null
}

private fun emitterPower(build: Building): Float {
    val name = build.block.name.lowercase()
    return when {
        name.contains("acropolis") -> 4.5f
        name.contains("citadel") -> 4.0f
        name.contains("bastion") -> 3.5f
        name.contains("nucleus") -> 3.0f
        name.contains("foundation") -> 2.4f
        name.contains("launch-pad") || name.contains("accelerator") -> 2.0f
        else -> 1.8f
    }
}

private fun canHoldFlood(tile: Tile?): Boolean {
    if (tile == null) return false
    val floor = tile.floor()
    if (floor == null || floor.isLiquid) return false
    val block = tile.block()
    if (block is StaticWall || block is Cliff) return false
    return true
}

private fun addFlood(tile: Tile?, amount: Float) {
    if (!canHoldFlood(tile) || amount <= 0f) return
    tile ?: return
    val key = tile.key()
    floodLevels[key] = ((floodLevels[key] ?: 0f) + amount).coerceAtMost(maxLevel)
}

private fun removeFlood(tile: Tile?, amount: Float): Float {
    tile ?: return 0f
    val key = tile.key()
    val level = floodLevels[key] ?: return 0f
    val removed = min(level, amount.coerceAtLeast(0f))
    val next = level - removed
    if (next < 0.12f) floodLevels.remove(key) else floodLevels[key] = next
    return removed
}

private fun depositFloodCircle(center: Tile?, radius: Float, amount: Float) {
    center ?: return
    val cx = center.x.toInt()
    val cy = center.y.toInt()
    val r = radius.toInt().coerceAtLeast(1)
    val r2 = r * r
    for (dx in -r..r) {
        for (dy in -r..r) {
            if (dx * dx + dy * dy > r2) continue
            addFlood(world.tile(cx + dx, cy + dy), amount)
        }
    }
}

private fun clearFloodAround(center: Tile, radius: Int) {
    val cx = center.x.toInt()
    val cy = center.y.toInt()
    val r = radius.coerceAtLeast(1)
    val r2 = r * r
    for (dx in -r..r) {
        for (dy in -r..r) {
            if (dx * dx + dy * dy > r2) continue
            val tile = world.tile(cx + dx, cy + dy) ?: continue
            val key = tile.key()
            floodLevels.remove(key)
            floodVisualTiles.remove(key)
            if (isFloodVisual(tile.block())) {
                tile.setNet(Blocks.air, Team.derelict, 0)
            }
        }
    }
}

private fun floodBlockFor(level: Float): Block {
    val index = Mathf.clamp(level.toInt(), 1, floodBlocks.size) - 1
    return floodBlocks[index]
}

private fun drawFlood(tile: Tile, level: Float) {
    if (level < 1f) return
    val block = tile.block()
    if (block == Blocks.air || block.alwaysReplace || isFloodVisual(block)) {
        val next = floodBlockFor(level)
        if (block != next || tile.build?.team != floodTeam) {
            tile.setNet(next, floodTeam, Mathf.random(0, 3))
            floodVisualTiles += tile.key()
        }
    }
}

private fun damageTile(tile: Tile, level: Float) {
    val build = tile.build ?: return
    if (build.team == floodTeam || isFloodVisual(build.block)) return
    build.damage(buildDamage * level)
    floodLevels[tile.key()] = (level * state.rules.tags.getFloat("@floodDamageEvaporation", 0.98f)).coerceAtMost(maxLevel)
    if (Mathf.chance(0.02)) {
        Call.effect(Fx.bubble, build.x, build.y, 0f, floodTeam.color)
    }
}

private fun linkedFloodPressure(tile: Tile): Float {
    var sum = floodLevels[tile.key()] ?: 0f
    var count = 1
    tile.getLinkedTiles { linked ->
        sum += floodLevels[linked.key()] ?: 0f
        count++
    }
    return sum / count.coerceAtLeast(1)
}

private fun refreshEmittersAndSpecials() {
    emitterTiles.clear()
    val seenRegular = linkedSetOf<Int>()
    val seenCharged = linkedSetOf<Int>()
    var spores = 0
    var towers = 0
    var radars = 0
    var shields = 0

    Groups.build.forEach { build ->
        val tile = build.tile ?: return@forEach
        val key = tile.key()

        if (isEmitter(build)) {
            emitterTiles += key
            hadAnyEmitter = true
            if (isChargedEmitter(build)) {
                seenCharged += key
                chargedEmitterStates.getOrPut(key) { ChargedEmitterState(key) }
            } else {
                seenRegular += key
                regularEmitterStates.getOrPut(key) { FloodEmitterState(key) }
            }
        }

        if (isSporeLauncher(build)) spores++
        if (isFloodTower(build)) towers++
        if (isFloodRadar(build)) radars++
        if (build.team != floodTeam && build.block is ForceProjector && build.health > 0f) shields++
    }

    regularEmitterStates.keys.retainAll(seenRegular)
    chargedEmitterStates.keys.retainAll(seenCharged)
    lastSporeLauncherCount = spores
    lastFloodTowerCount = towers
    lastRadarCount = radars
    lastForceProjectorCount = shields
}

private fun regularEmitAmount(build: Building): Float {
    val kind = regularKind(build.block)
    return if (kind != null) kind.amount else emitAmount * emitterPower(build)
}

private fun processRegularEmitterUpgrade(state: FloodEmitterState, build: Building, now: Long) {
    val kind = regularKind(build.block) ?: return
    val next = kind.nextBlock ?: return
    if (isSuppressed(state, now)) {
        state.upgradeProgress = (state.upgradeProgress - 2f).coerceAtLeast(0f)
        return
    }
    val tile = build.tile ?: return
    val pressure = linkedFloodPressure(tile)
    if (pressure < regularUpgradePressure) {
        state.upgradeProgress = (state.upgradeProgress - 1f).coerceAtLeast(0f)
        return
    }
    state.upgradeProgress += pressure
    val percent = (state.upgradeProgress * 100f / kind.upgradeThreshold).coerceIn(0f, 100f).toInt()
    Call.label("[green]*[white] UPGRADING []$percent% *[]", 1.05f, build.x, build.y)
    if (state.upgradeProgress >= kind.upgradeThreshold) {
        val x = tile.x.toInt()
        val y = tile.y.toInt()
        tile.setNet(next, floodTeam, 0)
        state.upgradeProgress = 0f
        Call.effect(Fx.placeBlock, tile.worldx(), tile.worldy(), next.size.toFloat(), floodTeam.color)
        Call.sendMessage("[scarlet]洪水泉眼 [accent]($x,$y)[] 已升级为 [accent]${next.localizedName}[]！")
    }
}

private fun processRegularEmitters(now: Long) {
    activeRegularEmitterStates().forEach { state ->
        val build = activeRegularEmitterBuild(state) ?: return@forEach
        if (isSuppressed(state, now)) return@forEach
        val interval = regularKind(build.block)?.intervalSeconds ?: 1
        state.emitCounter++
        if (state.emitCounter >= interval) {
            state.emitCounter = 0
            val amount = regularEmitAmount(build)
            val tile = build.tile ?: return@forEach
            addFlood(tile, amount)
            tile.getLinkedTiles { linked -> addFlood(linked, amount * 0.55f) }
        }
        processRegularEmitterUpgrade(state, build, now)
    }
}

private fun processChargedEmitters(now: Long) {
    activeChargedEmitterStates().forEach { state ->
        val build = activeChargedEmitterBuild(state) ?: return@forEach
        val kind = chargedKind(build.block) ?: return@forEach
        val tile = build.tile ?: return@forEach

        if (build.health < build.maxHealth && state.overflow > 0f) {
            build.heal(build.maxHealth * chargedHealPerOverflow)
            state.overflow = (state.overflow - kind.chargePulse * chargedChargeScale).coerceAtLeast(0f)
            Call.effect(Fx.healBlock, build.x, build.y, build.block.size.toFloat(), floodTeam.color)
        }

        if (state.emitting) {
            state.counter++
            if (state.counter >= kind.intervalSeconds) {
                state.counter = 0
                val amount = emitAmount * kind.amount * chargedBurstScale
                addFlood(tile, amount)
                tile.getLinkedTiles { linked -> addFlood(linked, amount) }
                Call.effect(Fx.launch, build.x, build.y, build.block.size.toFloat(), floodTeam.color)
            }
            state.burstLeftMillis -= 1000L
            if (state.burstLeftMillis <= 0L) {
                state.emitting = false
                state.charge = 0f
                state.overflow = min(kind.chargeCap, state.overflow + (floodLevels[tile.key()] ?: 0f) * 10f)
            }
        } else {
            state.charge += kind.chargePulse * chargedChargeScale
            if (state.charge >= kind.chargeCap) {
                state.emitting = true
                state.burstLeftMillis = (kind.chargeCap * chargedBurstSecondFactor * 1000f).toLong().coerceAtLeast(3000L)
                Call.sendMessage("[scarlet]充能泉眼 [accent](${tile.x.toInt()},${tile.y.toInt()})[] 开始爆发洪水！")
            }
        }

        if (!state.emitting) {
            val percent = (state.charge * 100f / kind.chargeCap).coerceIn(0f, 100f).toInt()
            val overflowText = if (state.overflow > 0f) "\n[green]溢出 ${(state.overflow * 100f / kind.chargeCap).coerceIn(0f, 100f).toInt()}%[]" else ""
            Call.label("[red]⚠[] [stat]$percent%[]$overflowText", 1.05f, build.x, build.y)
        }

        val next = kind.nextBlock
        if (next != null && state.overflow >= kind.chargeCap) {
            tile.setNet(next, floodTeam, 0)
            state.overflow = 0f
            state.charge = 0f
            state.emitting = false
            state.burstLeftMillis = 0L
            Call.effect(Fx.placeBlock, tile.worldx(), tile.worldy(), next.size.toFloat(), floodTeam.color)
            Call.sendMessage("[scarlet]充能泉眼 [accent](${tile.x.toInt()},${tile.y.toInt()})[] 已升级为 [accent]${next.localizedName}[]！")
        }
    }
}

private fun trimFloodTiles() {
    val overflow = floodLevels.size - maxFloodTiles
    if (overflow <= 0) return
    floodLevels.entries
        .sortedBy { it.value }
        .take(overflow)
        .forEach { floodLevels.remove(it.key) }
}

private fun tickFlood() {
    if (!state.isPlaying || state.isPaused) return
    updateCount++
    if (updateCount % 5 == 1) refreshEmittersAndSpecials()
    val now = System.currentTimeMillis()
    processImpactReactors()
    processRegularEmitters(now)
    processChargedEmitters(now)
    processSpecialBuildings(now)
    processForceProjectors()
    processFloodCreepUnits()

    val snapshot = floodLevels.entries.toList()
    val additions = linkedMapOf<Int, Float>()

    for ((key, levelRaw) in snapshot) {
        val tile = tileOf(key)
        if (!canHoldFlood(tile)) {
            floodLevels.remove(key)
            continue
        }
        tile ?: continue
        val level = levelRaw.coerceAtMost(maxLevel)
        damageTile(tile, level)
        if (updateCount % 2 == 0) drawFlood(tile, level)

        var retained = level * evaporation
        for ((dx, dy) in directions) {
            val target = tile.nearby(dx, dy) ?: continue
            if (!canHoldFlood(target)) continue
            val targetKey = target.key()
            val targetLevel = max(floodLevels[targetKey] ?: 0f, additions[targetKey] ?: 0f)
            if (level <= targetLevel + 0.12f) continue
            val delta = ((level - targetLevel) * spreadRate).coerceAtMost(level * 0.22f)
            if (delta <= 0.04f) continue
            additions[targetKey] = (targetLevel + delta).coerceAtMost(maxLevel)
            retained -= delta * 0.22f
        }

        if (retained < 0.12f) floodLevels.remove(key)
        else floodLevels[key] = retained.coerceAtMost(maxLevel)
    }

    additions.forEach { (key, level) ->
        floodLevels[key] = max(floodLevels[key] ?: 0f, level).coerceAtMost(maxLevel)
    }

    damageUnitsOnFlood()

    trimFloodTiles()
    showSuppressedEmitters()
    checkFloodVictory()
}

private fun showFloodStatus() {
    if (!state.isPlaying) return
    val activeRegular = activeRegularEmitterStates()
    val now = System.currentTimeMillis()
    val suppressed = activeRegular.count { isSuppressed(it, now) }
    val charged = activeChargedEmitterCount()
    val countdown = if (allControlledSince > 0L) {
        val left = ((nullificationPeriodMillis - (now - allControlledSince) + 999L) / 1000L).coerceAtLeast(0L)
        "\n[green]通关倒计时: [accent]${left}s[]"
    } else ""
    Call.infoPopup(
        "[cyan]洪水模式[]\n[white]普通泉眼压制: [accent]$suppressed/${activeRegular.size}[]  充能泉眼: [accent]$charged[]\n[white]孢子/钍: [accent]$lastSporeLauncherCount[]  震爆塔: [accent]$lastFloodTowerCount[]  雷达: [accent]$lastRadarCount[]\n[white]防洪力场: [accent]$lastForceProjectorCount[]  洪水格: [accent]${floodLevels.size}[]$countdown",
        5.013f,
        Align.topLeft,
        250,
        0,
        0,
        0
    )
}

private fun bulletDamageAmount(source: Bullet?): Float {
    source ?: return suppressDamage * 0.05f
    var amount = source.damage
    val type = source.type
    if (type != null) {
        amount = max(amount, type.damage)
        amount = max(amount, type.splashDamage)
        amount = max(amount, type.lightningDamage)
        amount = max(amount, type.continuousDamage())
    }
    return amount.coerceAtLeast(1f)
}

private fun isHorizonSource(source: Bullet?): Boolean {
    val owner = source?.owner as? Unit ?: return false
    return owner.type == UnitTypes.horizon
}

private fun handleEmitterDamage(build: Building, source: Bullet?) {
    if (!state.isPlaying || state.gameOver || !isRegularEmitter(build)) return
    if (source != null && source.team == floodTeam) return
    if (isHorizonSource(source)) return
    val tile = build.tile ?: return
    val key = tile.key()
    val now = System.currentTimeMillis()
    val state = regularEmitterStates.getOrPut(key) { FloodEmitterState(key) }

    if (now - state.damageWindowStart > suppressWindowMillis) {
        state.damageWindowStart = now
        state.damageAccum = 0f
    }
    state.damageAccum += bulletDamageAmount(source)

    if (state.damageAccum >= suppressDamage) {
        val wasSuppressed = isSuppressed(state, now)
        state.suppressedUntil = now + suppressTimeoutMillis
        state.damageWindowStart = now
        state.damageAccum = 0f
        state.upgradeProgress = (state.upgradeProgress * 0.25f).coerceAtLeast(0f)
        Call.effect(Fx.placeBlock, build.x, build.y, build.block.size.toFloat(), floodTeam.color)
        Call.label("[red]*[] SUSPENDED [red]*[]", 1.2f, build.x, build.y)
        if (!wasSuppressed) {
            Call.sendMessage("[cyan]洪水泉眼 [accent](${tile.x.toInt()},${tile.y.toInt()})[] 已被火力压制！")
        }
    }
}

private fun activeRegularEmitterStates(): List<FloodEmitterState> {
    val active = mutableListOf<FloodEmitterState>()
    val stale = mutableListOf<Int>()
    regularEmitterStates.values.forEach { state ->
        if (activeRegularEmitterBuild(state) != null) active += state else stale += state.tileKey
    }
    stale.forEach {
        regularEmitterStates.remove(it)
        emitterTiles.remove(it)
    }
    return active
}

private fun activeChargedEmitterStates(): List<ChargedEmitterState> {
    val active = mutableListOf<ChargedEmitterState>()
    val stale = mutableListOf<Int>()
    chargedEmitterStates.values.forEach { state ->
        if (activeChargedEmitterBuild(state) != null) active += state else stale += state.tileKey
    }
    stale.forEach {
        chargedEmitterStates.remove(it)
        emitterTiles.remove(it)
    }
    return active
}

private fun activeChargedEmitterCount(): Int = activeChargedEmitterStates().size

private fun showSuppressedEmitters() {
    val now = System.currentTimeMillis()
    regularEmitterStates.values.forEach { state ->
        if (!isSuppressed(state, now)) return@forEach
        val build = activeRegularEmitterBuild(state) ?: return@forEach
        val left = ((state.suppressedUntil - now + 999L) / 1000L).coerceAtLeast(0L)
        Call.label("[red]*[] SUSPENDED [red]*[]\n[white]${left}s", 1.05f, build.x, build.y)
        if (updateCount % 2 == 0) {
            Call.effect(Fx.placeBlock, build.x, build.y, build.block.size.toFloat(), floodTeam.color)
        }
    }
}

private fun canImpactNullify(reactor: Building): Boolean {
    if (reactor.block != Blocks.impactReactor || reactor.team == floodTeam || reactor.health <= 0f || reactor.dead) return false
    if (!reactor.enabled) return false
    val powerStatus = reactor.power?.status ?: 0f
    return reactor.efficiency >= 0.99f &&
        powerStatus >= impactPowerRequired &&
        reactor.warmup() >= impactWarmupRequired
}

private fun nearestRegularEmitter(reactor: Building, excluded: Set<Int>): Building? {
    var best: Building? = null
    var bestDst = impactNullifyRange
    activeRegularEmitterStates().forEach { state ->
        if (state.tileKey in excluded) return@forEach
        val build = activeRegularEmitterBuild(state) ?: return@forEach
        val dst = Mathf.dst(reactor.x, reactor.y, build.x, build.y)
        if (dst <= bestDst) {
            bestDst = dst
            best = build
        }
    }
    return best
}

private fun processImpactReactors() {
    val actions = mutableListOf<Pair<Building, Building>>()
    val selectedTargets = linkedSetOf<Int>()
    Groups.build.forEach { reactor ->
        if (!canImpactNullify(reactor)) return@forEach
        val target = nearestRegularEmitter(reactor, selectedTargets) ?: return@forEach
        val targetTile = target.tile ?: return@forEach
        selectedTargets += targetTile.key()
        actions += reactor to target
    }

    actions.forEach { (reactor, target) ->
        if (!canImpactNullify(reactor) || !isRegularEmitter(target)) return@forEach
        val targetTile = target.tile ?: return@forEach
        val targetKey = targetTile.key()
        val targetBlock = target.block
        val targetX = targetTile.x.toInt()
        val targetY = targetTile.y.toInt()

        Call.effect(Fx.massiveExplosion, reactor.x, reactor.y, 2f, reactor.team.color)
        Call.effect(Fx.shockwave, target.x, target.y, impactNullifyRange / 8f, reactor.team.color)
        clearFloodAround(targetTile, targetBlock.size + 4)

        target.kill()
        regularEmitterStates.remove(targetKey)
        emitterTiles.remove(targetKey)

        if (state.rules.coreCapture) {
            targetTile.setNet(targetBlock, reactor.team, 0)
            Call.effect(Fx.placeBlock, targetTile.worldx(), targetTile.worldy(), targetBlock.size.toFloat(), reactor.team.color)
        }

        reactor.kill()
        Call.sendMessage("[green]冲击反应堆已净化洪水泉眼 [accent]($targetX,$targetY)[]！")
    }
}

private fun randomPlayerTargetTile(): Tile? {
    val candidates = mutableListOf<Unit>()
    Groups.player.forEach { player ->
        val unit = player.unit() ?: return@forEach
        if (unit.dead || unit.team == floodTeam) return@forEach
        candidates += unit
    }
    if (candidates.isEmpty()) return null
    val unit = candidates[Mathf.random(candidates.size - 1)]
    val x = unit.x + Mathf.random(-sporeTargetOffset, sporeTargetOffset)
    val y = unit.y + Mathf.random(-sporeTargetOffset, sporeTargetOffset)
    return world.tileWorld(x, y) ?: unit.tileOn()
}

private fun isSporeIntercepted(strike: PendingFloodStrike): Boolean {
    var intercepted = false
    Groups.build.forEach { build ->
        if (intercepted) return@forEach
        if (build.block != Blocks.segment || build.team == floodTeam || build.health <= 0f || !build.enabled) return@forEach
        if (Mathf.dst(build.x, build.y, strike.targetX, strike.targetY) <= sporeInterceptRange) {
            intercepted = true
            Call.effect(Fx.pointHit, strike.targetX, strike.targetY, 2f, build.team.color)
            Call.effect(Fx.shootSmall, build.x, build.y, 0f, build.team.color)
        }
    }
    return intercepted
}

private fun processPendingFloodStrikes(now: Long) {
    val iterator = pendingFloodStrikes.iterator()
    while (iterator.hasNext()) {
        val strike = iterator.next()
        if (strike.arriveAt > now) continue
        iterator.remove()
        if (isSporeIntercepted(strike)) {
            Call.sendMessage("[cyan]Segment 成功拦截了一枚洪水孢子炮弹！")
            continue
        }
        val tile = world.tileWorld(strike.targetX, strike.targetY)
        Call.effect(Fx.sapExplosion, strike.targetX, strike.targetY, strike.radius, floodTeam.color)
        depositFloodCircle(tile, strike.radius, strike.amount)
    }
}

private fun processSporeLaunchers(now: Long) {
    val seen = linkedSetOf<Int>()
    var count = 0
    Groups.build.forEach { build ->
        if (!isSporeLauncher(build)) return@forEach
        val tile = build.tile ?: return@forEach
        val key = tile.key()
        seen += key
        count++
        if ((sporeCooldowns[key] ?: 0L) > now) return@forEach
        val target = randomPlayerTargetTile() ?: return@forEach
        val distance = Mathf.dst(build.x, build.y, target.worldx(), target.worldy())
        val travel = max(sporeTravelMillis, (distance * 8f).toLong())
        pendingFloodStrikes += PendingFloodStrike(
            build.x,
            build.y,
            target.worldx(),
            target.worldy(),
            sporeRadius,
            sporeAmount,
            now + travel,
        )
        sporeCooldowns[key] = now + sporeCooldownMillis
        Call.effect(Fx.thoriumShoot, build.x, build.y, 0f, floodTeam.color)
        Call.label("[scarlet]孢子炮击[]", 1.2f, build.x, build.y)
    }
    sporeCooldowns.keys.retainAll(seen)
    lastSporeLauncherCount = count
}

private fun nearestEnemyBuilding(x: Float, y: Float, range: Float): Building? {
    var best: Building? = null
    var bestDst = range
    Groups.build.forEach { build ->
        if (build.team == floodTeam || build.team == Team.derelict || build.health <= 0f) return@forEach
        if (isFloodVisual(build.block)) return@forEach
        val dst = Mathf.dst(x, y, build.x, build.y)
        if (dst <= bestDst) {
            bestDst = dst
            best = build
        }
    }
    return best
}

private fun processFloodTowers(now: Long) {
    val seen = linkedSetOf<Int>()
    var count = 0
    Groups.build.forEach { build ->
        if (!isFloodTower(build)) return@forEach
        val tile = build.tile ?: return@forEach
        val key = tile.key()
        seen += key
        count++
        if ((towerCooldowns[key] ?: 0L) > now) return@forEach
        val target = nearestEnemyBuilding(build.x, build.y, towerRange) ?: return@forEach
        towerCooldowns[key] = now + towerCooldownMillis
        depositFloodCircle(target.tile, towerRadius, towerDeposit)
        Call.effect(Fx.pointShockwave, target.x, target.y, towerRadius, floodTeam.color)
        Call.effect(Fx.dynamicSpikes, build.x, build.y, build.block.size.toFloat(), floodTeam.color)
    }
    towerCooldowns.keys.retainAll(seen)
    lastFloodTowerCount = count
}

private fun unitById(id: Int): Unit? {
    if (id < 0) return null
    var result: Unit? = null
    Groups.unit.forEach { unit ->
        if (unit.id == id && !unit.dead) result = unit
    }
    return result
}

private fun nearestEnemyUnit(x: Float, y: Float, range: Float): Unit? {
    var best: Unit? = null
    var bestDst = range
    Groups.unit.forEach { unit ->
        if (unit.team == floodTeam || unit.dead) return@forEach
        val dst = Mathf.dst(x, y, unit.x, unit.y)
        if (dst <= bestDst) {
            bestDst = dst
            best = unit
        }
    }
    return best
}

private fun processFloodRadars() {
    val seen = linkedSetOf<Int>()
    var count = 0
    Groups.build.forEach { build ->
        if (!isFloodRadar(build)) return@forEach
        val tile = build.tile ?: return@forEach
        val key = tile.key()
        seen += key
        count++
        val radar = radarStates.getOrPut(key) { RadarState(key) }
        var target = unitById(radar.targetId)
        if (target == null || target.team == floodTeam || target.dead || Mathf.dst(build.x, build.y, target.x, target.y) > radarRange) {
            target = nearestEnemyUnit(build.x, build.y, radarRange)
            radar.targetId = target?.id ?: -1
            radar.chargeMillis = 0L
        }
        target ?: return@forEach
        radar.chargeMillis += 1000L
        val percent = (radar.chargeMillis * 100L / radarChargeMillis).coerceIn(0L, 100L)
        Call.label("[scarlet]雷达锁定[] [stat]$percent%", 1.05f, build.x, build.y)
        if (radar.chargeMillis >= radarChargeMillis) {
            radar.chargeMillis = 0L
            target.damage(radarBeamDamage)
            if (radarFloodAmount > 0f) depositFloodCircle(target.tileOn(), 2f, radarFloodAmount)
            Call.effect(Fx.chainLightning, target.x, target.y, 0f, floodTeam.color)
            Call.effect(Fx.pointBeam, build.x, build.y, 0f, floodTeam.color)
        }
    }
    radarStates.keys.retainAll(seen)
    lastRadarCount = count
}

private fun processSpecialBuildings(now: Long) {
    processPendingFloodStrikes(now)
    processSporeLaunchers(now)
    processFloodTowers(now)
    processFloodRadars()
}

private fun processForceProjectors() {
    var count = 0
    Groups.build.forEach { build ->
        if (build.team == floodTeam || build.health <= 0f || build.block !is ForceProjector) return@forEach
        val projector = build as? ForceProjector.ForceBuild ?: return@forEach
        if (projector.broken || build.dead) return@forEach
        count++
        val radius = (projector.realRadius() / 8f).toInt().coerceAtLeast(1)
        val center = build.tile ?: return@forEach
        var absorbed = 0f
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                if (dx * dx + dy * dy > radius * radius) continue
                val tile = world.tile(center.x.toInt() + dx, center.y.toInt() + dy) ?: continue
                absorbed += removeFlood(tile, forceProjectorAbsorbPerTick)
            }
        }
        if (absorbed <= 0f) return@forEach
        val protection = if (projector.phaseHeat > 0.1f) shieldBoostProtectionMultiplier else 1f
        projector.buildup += absorbed * buildShieldDamageMultiplier * protection
        Call.effect(Fx.absorb, build.x, build.y, build.block.size.toFloat(), build.team.color)
        val shieldHealth = (build.block as ForceProjector).shieldHealth.coerceAtLeast(1f)
        val percent = (projector.buildup * 100f / shieldHealth).coerceIn(0f, 100f).toInt()
        Call.label("[cyan]防洪力场[] [stat]${100 - percent}%", 1.05f, build.x, build.y)
        if (projector.buildup >= shieldHealth) {
            val tile = build.tile
            build.kill()
            Call.effect(Fx.massiveExplosion, build.x, build.y, shieldCreeperDropRadius, floodTeam.color)
            depositFloodCircle(tile, shieldCreeperDropRadius, shieldCreeperDropAmount)
        }
    }
    lastForceProjectorCount = count
}

private fun processFloodCreepUnits() {
    val toExplode = mutableListOf<Unit>()
    Groups.unit.forEach { unit ->
        if (unit.team != floodTeam || unit.dead || !isFloodCreepUnit(unit)) return@forEach
        var found = false
        Groups.build.forEach { build ->
            if (found) return@forEach
            if (build.team == floodTeam || build.team == Team.derelict || build.health <= 0f) return@forEach
            if (Mathf.dst(unit.x, unit.y, build.x, build.y) <= creepUnitTriggerRange + build.block.size * 4f) found = true
        }
        if (found) toExplode += unit
    }
    toExplode.forEach { unit ->
        val tile = unit.tileOn()
        Call.effect(Fx.sapExplosion, unit.x, unit.y, creepUnitRadius, floodTeam.color)
        depositFloodCircle(tile, creepUnitRadius, creepUnitAmount)
        unit.kill()
    }
}

private fun damageUnitsOnFlood() {
    Groups.unit.forEach { unit ->
        if (unit.team == floodTeam || unit.type.flying || unit.dead) return@forEach
        if (unit.type == UnitTypes.horizon) return@forEach
        val tile = unit.tileOn() ?: return@forEach
        val key = tile.key()
        var level = floodLevels[key] ?: return@forEach
        if (level <= 0.5f) return@forEach

        if (unit.shield > 0f) {
            val absorb = min(level, unit.shield / unitShieldDamageMultiplier)
            if (absorb > 0f) {
                unit.shield = (unit.shield - absorb * unitShieldDamageMultiplier).coerceAtLeast(0f)
                level -= absorb
                if (level < 0.12f) floodLevels.remove(key) else floodLevels[key] = level
                Call.effect(Fx.absorb, unit.x, unit.y, absorb, unit.team.color)
                if (unit.shield <= 0f) {
                    Call.effect(Fx.sapExplosion, unit.x, unit.y, shieldCreeperDropRadius, floodTeam.color)
                    depositFloodCircle(tile, shieldCreeperDropRadius, shieldCreeperDropAmount * 0.5f)
                }
            }
        }

        level = floodLevels[key] ?: 0f
        if (level > 0.5f) unit.damage(unitDamage * level)
    }
}

private fun checkFloodVictory() {
    if (gameOverTriggered || state.gameOver || !state.isPlaying) return
    val now = System.currentTimeMillis()
    val activeRegular = activeRegularEmitterStates()
    val activeCharged = activeChargedEmitterCount()
    val suppressedRegular = activeRegular.count { isSuppressed(it, now) }
    val allRegularControlled = activeRegular.isEmpty() || suppressedRegular == activeRegular.size
    val allControlled = hadAnyEmitter && activeCharged == 0 && allRegularControlled

    if (!allControlled) {
        allControlledSince = 0L
        return
    }

    if (allControlledSince == 0L) {
        allControlledSince = now
        Call.sendMessage("[cyan]全部洪水泉眼已被压制/净化！保持 ${nullificationPeriodMillis / 1000L} 秒即可通关！")
        return
    }

    if (now - allControlledSince >= nullificationPeriodMillis) {
        gameOverTriggered = true
        Call.sendMessage("[green]洪水泉眼已全部失效，地图通关！")
        state.gameOver = true
        Events.fire(EventType.GameOverEvent(state.rules.defaultTeam))
    }
}

private fun clearFloodVisualBlocks() {
    floodVisualTiles.toList().forEach { key ->
        val tile = tileOf(key) ?: return@forEach
        if (isFloodVisual(tile.block()) && tile.build?.team == floodTeam) {
            tile.setNet(Blocks.air, Team.derelict, 0)
        }
    }
    floodVisualTiles.clear()
}

private fun clearFloodRuntime(clearVisualBlocks: Boolean = false) {
    if (clearVisualBlocks) clearFloodVisualBlocks()
    floodLevels.clear()
    floodVisualTiles.clear()
    emitterTiles.clear()
    regularEmitterStates.clear()
    chargedEmitterStates.clear()
    sporeCooldowns.clear()
    towerCooldowns.clear()
    radarStates.clear()
    pendingFloodStrikes.clear()
    updateCount = 0
    allControlledSince = 0L
    gameOverTriggered = false
    hadAnyEmitter = false
    lastSporeLauncherCount = 0
    lastFloodTowerCount = 0
    lastRadarCount = 0
    lastForceProjectorCount = 0
}

onEnable {
    if (tagOff(tagValue("@flood")) || tagOff(tagValue("@floodV2"))) {
        return@onEnable ScriptManager.disableScript(this, "flood tag is disabled")
    }

    val floodV2Compat = hasTag("@floodV2") && !hasTag("@flood")
    if (floodV2Compat) {
        delayBroadcast("[yellow]本服不存在floodV2，已启用flood用于兼容".with())
    }

    floodTeam = parseTeam(state.rules.tags.get("@floodTeam").orEmpty())
    clearFloodRuntime()
    refreshEmittersAndSpecials()

    delayBroadcast(
        "[cyan]洪水模式已启用[] [lightgray](队伍: ${floodTeam.name}, 普通泉眼: ${regularEmitterStates.size}, 充能泉眼: ${chargedEmitterStates.size})".with()
    )

    loop(Dispatchers.game) {
        delay(floodTickIntervalMillis.coerceAtLeast(250L))
        val startedAt = System.currentTimeMillis()
        runCatching { tickFlood() }.onFailure { error ->
            val now = System.currentTimeMillis()
            if (now - lastFloodRuntimeErrorAt >= 30_000L) {
                lastFloodRuntimeErrorAt = now
                logger.warning("洪水主循环异常，已隔离并继续下一轮: ${error.stackTraceToString()}")
            }
        }
        val cost = System.currentTimeMillis() - startedAt
        val now = System.currentTimeMillis()
        if (cost >= floodSlowTickWarnMillis.coerceAtLeast(20L) && now - lastFloodSlowWarnAt >= 30_000L) {
            lastFloodSlowWarnAt = now
            logger.warning(
                "洪水单次计算耗时 ${cost}ms: floodTiles=${floodLevels.size}, emitters=${emitterTiles.size}, " +
                    "builds=${Groups.build.size()}, units=${Groups.unit.size()}"
            )
        }
    }
    loop(Dispatchers.game) {
        delay(10_000)
        runCatching { showFloodStatus() }.onFailure { error ->
            logger.warning("洪水状态显示失败，玩法主循环不受影响: ${error.message}")
        }
    }
}

onDisable {
    clearFloodRuntime(clearVisualBlocks = true)
}

listen<EventType.GameOverEvent> {
    clearFloodRuntime()
}

listen<EventType.BuildDamageEvent> {
    val build = it.build ?: return@listen
    if (isHorizonSource(it.source) && build.team == floodTeam) {
        build.heal(bulletDamageAmount(it.source))
        return@listen
    }
    handleEmitterDamage(build, it.source)
}
