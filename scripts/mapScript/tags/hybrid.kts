@file:Depends("coreMindustry/menu", "地图特色杂交菜单")
@file:Depends("coreMindustry/contentsTweaker", "运行时CP兼容")
@file:Depends("wayzer/reGrief/worldResyncCoordinator", "世界重同步串行协调")

package mapScript.tags

import arc.audio.Sound
import arc.graphics.Color
import arc.math.Interp
import arc.struct.Seq
import arc.util.pooling.Pools
import arc.util.serialization.Jval
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.content.StatusEffects
import mindustry.content.UnitTypes
import mindustry.ctype.ContentType
import mindustry.ctype.MappableContent
import mindustry.entities.Effect
import mindustry.entities.abilities.Ability
import mindustry.entities.bullet.BulletType
import mindustry.entities.bullet.ContinuousBulletType
import mindustry.entities.units.StatusEntry
import mindustry.game.Team
import mindustry.gen.BlockUnitc
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Sounds
import mindustry.gen.Unit as MindustryUnit
import mindustry.type.StatusEffect
import mindustry.type.UnitType
import mindustry.type.Weapon
import mindustry.world.blocks.defense.turrets.ContinuousTurret
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.defense.turrets.PowerTurret
import mindustry.world.blocks.defense.turrets.Turret
import java.lang.reflect.Modifier
import kotlin.random.Random

/**
 * 地图特色杂交玩法。
 *
 * 地图标签：[@hybrid]
 * 指令：/hybrid
 *
 * 设计目标：
 * - 独立于本服账号/MDC/资历/权限系统，方便作为地图标签玩法迁移。
 * - 复用技能系统中较稳定的单位杂交与 DataPatcher 基因杂交思路。
 * - 地图内杂交不消耗本服 MDC；若 CP 解析失败会回滚并提示，强度主要由队伍共享冷却控制。
 */

registerMapTag("@hybrid")

name = "地图特色杂交"

private val contentsTweaker = contextScript<coreMindustry.ContentsTweaker>()
private val worldResync = contextScript<wayzer.reGrief.WorldResyncCoordinator>()

private val statusFieldCache = mutableMapOf<Class<*>, java.lang.reflect.Field?>()

@Suppress("UNCHECKED_CAST")
private fun statusEntries(unit: MindustryUnit): Seq<StatusEntry> {
    val field = statusFieldCache.getOrPut(unit.javaClass) {
        generateSequence(unit.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz -> runCatching { clazz.getDeclaredField("statuses") }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
    } ?: error("当前单位类型 ${unit.javaClass.name} 没有 statuses 字段，无法直接叠加状态")
    return field.get(unit) as Seq<StatusEntry>
}

modeIntroduce(
    "地图特色杂交", """
    输入 [gold]/hybrid[] 打开杂交菜单，与其他人进行杂交，增强你的单位！
    [green]单位杂交[]：双方普通单位生成一个随机子单位；队伍共享冷却 10 秒，父母单位概率死亡。
    [accent]基因杂交[]：从目标当前单位/炮塔随机获得一个武器或能力；队伍共享冷却 120 秒。
    [gold]自选基因杂交[]：目标同意后手动选择目标当前可提供的武器/能力；队伍共享冷却 300 秒，会击杀双方。
    [gray]基因杂交仅当前地图有效；新生成单位生效，若客户端显示异常可手动 /sync 或重进。
""".trimIndent()
)

private enum class MapHybridMode(val label: String, val cooldownMillis: Long) {
    UnitHybrid("单位杂交", 10_000L),
    GeneHybrid("基因杂交", 120_000L),
    SelectGeneHybrid("自选基因杂交", 300_000L),
}

private data class HybridCategory(
    val code: String,
    val label: String,
    val tiers: Map<Int, List<UnitType>>,
    val allowMissileChild: Boolean = false,
)

private data class HybridStatusSnapshot(
    val effect: StatusEffect,
    val duration: Float,
    val stacks: Int = 1,
)

private data class HybridQuality(
    val plainName: String,
    val coloredName: String,
    val weight: Int,
    val shieldMultiplier: Float = 0f,
    val effects: List<StatusEffect> = emptyList(),
    val invincibleSeconds: Float = 0f,
    val hopeless: Boolean = false,
)

private data class HybridChildChoice(
    val type: UnitType?,
    val error: String? = null,
    val detail: String = "",
)

private enum class GeneKind(val label: String) {
    UnitGene("单位"),
    TurretGene("炮塔"),
}

private data class GeneTemplate(
    val sourceId: String,
    val displayName: String,
    val kind: GeneKind,
    val entries: List<String>,
    val sourceDebug: String? = null,
)

private data class GeneRecord(
    val unitGenes: MutableList<String> = mutableListOf(),
    var turretGene: String? = null,
)

private val hybridKnownEffects = listOf(
    StatusEffects.burning,
    StatusEffects.freezing,
    StatusEffects.unmoving,
    StatusEffects.slow,
    StatusEffects.fast,
    StatusEffects.wet,
    StatusEffects.muddy,
    StatusEffects.melting,
    StatusEffects.sapped,
    StatusEffects.tarred,
    StatusEffects.overdrive,
    StatusEffects.overclock,
    StatusEffects.shielded,
    StatusEffects.shocked,
    StatusEffects.blasted,
    StatusEffects.corroded,
    StatusEffects.boss,
    StatusEffects.sporeSlowed,
    StatusEffects.disarmed,
    StatusEffects.electrified,
    StatusEffects.invincible,
)

private val hybridEffectNames = mapOf(
    StatusEffects.burning to "燃烧",
    StatusEffects.freezing to "冻结",
    StatusEffects.unmoving to "静止",
    StatusEffects.slow to "减速",
    StatusEffects.fast to "加速",
    StatusEffects.wet to "潮湿",
    StatusEffects.muddy to "泥泞",
    StatusEffects.melting to "熔化",
    StatusEffects.sapped to "虚弱",
    StatusEffects.tarred to "焦油",
    StatusEffects.overdrive to "超速",
    StatusEffects.overclock to "超频",
    StatusEffects.shielded to "护盾",
    StatusEffects.shocked to "震击",
    StatusEffects.blasted to "爆破",
    StatusEffects.corroded to "腐蚀",
    StatusEffects.boss to "Boss",
    StatusEffects.sporeSlowed to "孢子减速",
    StatusEffects.disarmed to "缴械",
    StatusEffects.electrified to "电击",
    StatusEffects.invincible to "无敌",
)

private val hybridCategories = listOf(
    HybridCategory(
        "groundAssault", "陆军突击",
        mapOf(
            1 to listOf(UnitTypes.dagger),
            2 to listOf(UnitTypes.mace),
            3 to listOf(UnitTypes.fortress),
            4 to listOf(UnitTypes.scepter),
            5 to listOf(UnitTypes.reign),
        )
    ),
    HybridCategory(
        "groundSupport", "陆辅",
        mapOf(
            1 to listOf(UnitTypes.nova),
            2 to listOf(UnitTypes.pulsar),
            3 to listOf(UnitTypes.quasar),
            4 to listOf(UnitTypes.vela),
            5 to listOf(UnitTypes.corvus),
        )
    ),
    HybridCategory(
        "spider", "蜘蛛/虫",
        mapOf(
            1 to listOf(UnitTypes.crawler),
            2 to listOf(UnitTypes.atrax),
            3 to listOf(UnitTypes.spiroct),
            4 to listOf(UnitTypes.arkyid),
            5 to listOf(UnitTypes.toxopid),
        )
    ),
    HybridCategory(
        "airAssault", "空军突击",
        mapOf(
            1 to listOf(UnitTypes.flare),
            2 to listOf(UnitTypes.horizon),
            3 to listOf(UnitTypes.zenith),
            4 to listOf(UnitTypes.antumbra),
            5 to listOf(UnitTypes.eclipse),
        )
    ),
    HybridCategory(
        "airSupport", "空辅/建造",
        mapOf(
            1 to listOf(UnitTypes.mono),
            2 to listOf(UnitTypes.poly),
            3 to listOf(UnitTypes.mega),
            4 to listOf(UnitTypes.quad),
            5 to listOf(UnitTypes.oct),
        )
    ),
    HybridCategory(
        "navalAssault", "海军突击",
        mapOf(
            1 to listOf(UnitTypes.risso),
            2 to listOf(UnitTypes.minke),
            3 to listOf(UnitTypes.bryde),
            4 to listOf(UnitTypes.sei),
            5 to listOf(UnitTypes.omura),
        )
    ),
    HybridCategory(
        "navalSupport", "海辅",
        mapOf(
            1 to listOf(UnitTypes.retusa),
            2 to listOf(UnitTypes.oxynoe),
            3 to listOf(UnitTypes.cyerce),
            4 to listOf(UnitTypes.aegires),
            5 to listOf(UnitTypes.navanax),
        )
    ),
    HybridCategory(
        "erekirTank", "埃里克尔坦克",
        mapOf(
            1 to listOf(UnitTypes.stell),
            2 to listOf(UnitTypes.locus),
            3 to listOf(UnitTypes.precept),
            4 to listOf(UnitTypes.vanquish),
            5 to listOf(UnitTypes.conquer),
        )
    ),
    HybridCategory(
        "erekirMech", "埃里克尔机甲",
        mapOf(
            1 to listOf(UnitTypes.merui),
            2 to listOf(UnitTypes.cleroi),
            3 to listOf(UnitTypes.anthicus),
            4 to listOf(UnitTypes.tecta),
            5 to listOf(UnitTypes.collaris),
        )
    ),
    HybridCategory(
        "erekirAir", "埃里克尔空军",
        mapOf(
            1 to listOf(UnitTypes.elude),
            2 to listOf(UnitTypes.avert),
            3 to listOf(UnitTypes.obviate),
            4 to listOf(UnitTypes.quell),
            5 to listOf(UnitTypes.disrupt),
        ),
        allowMissileChild = true
    ),
)

private val hybridQualityPool = listOf(
    HybridQuality("传奇的", "[gold]传奇的", 3, 3f, listOf(StatusEffects.boss, StatusEffects.fast, StatusEffects.shielded, StatusEffects.overdrive, StatusEffects.overclock)),
    HybridQuality("史诗的", "[purple]史诗的", 7, 2f, listOf(StatusEffects.boss, StatusEffects.fast)),
    HybridQuality("精良的", "[blue]精良的", 18, 1f, listOf(StatusEffects.overdrive, StatusEffects.overclock)),
    HybridQuality("一般的", "[white]一般的", 32, 0f, listOf(StatusEffects.overclock)),
    HybridQuality("拉跨的", "[gray]拉跨的", 18, 0f, listOf(StatusEffects.sapped)),
    HybridQuality("废物的", "[scarlet]废物的", 12, 0f, listOf(StatusEffects.electrified, StatusEffects.slow, StatusEffects.sapped)),
    HybridQuality("无药可救的", "[red]无药可救的", 7, hopeless = true),
    HybridQuality("无敌的", "[accent]无敌的", 3, invincibleSeconds = 30f),
)

private val geneRecords = linkedMapOf<String, GeneRecord>()
private val genePatchStrings = linkedSetOf<String>()
private val teamCooldowns = mutableMapOf<String, Long>()
private var geneSyncGeneration = 0
private val autoWorldSyncDelayMillis = 350L
private val autoWorldSyncDebounceMillis = 1500L

private val hybridTurretWeaponRegionOverrides = mapOf(
    "disperse" to "disperse-mid",
    "lustre" to "lustre-mid",
    "scathe" to "scathe-mid",
    "smite" to "smite-mid",
    "malign" to "malign-main",
)

private fun unitTypeByName(name: String): UnitType? =
    Vars.content.getByName(ContentType.unit, name) as? UnitType

private fun hybridCategoryOf(type: UnitType): HybridCategory? =
    hybridCategories.firstOrNull { category -> category.tiers.values.any { type in it } }

private fun hybridTierOf(type: UnitType): Int? =
    hybridCategoryOf(type)?.tiers?.entries?.firstOrNull { type in it.value }?.key

private fun hybridUnitError(type: UnitType): String? = null

private fun geneHybridUnitError(type: UnitType): String? = null

private fun hybridPairError(fatherType: UnitType, motherType: UnitType): String? {
    hybridUnitError(fatherType)?.let { return it }
    hybridUnitError(motherType)?.let { return it }
    return null
}

private fun availableHybridMissiles(): List<UnitType> =
    listOf("anthicus-missile", "quell-missile", "disrupt-missile", "scathe-missile", "scathe-missile-phase", "scathe-missile-surge", "scathe-missile-surge-split")
        .mapNotNull(::unitTypeByName)
        .filter { it.constructor != null && it.health > 0f }

private fun <T> weightedRandom(items: List<T>, weight: (T) -> Int): T {
    val total = items.sumOf { weight(it).coerceAtLeast(0) }.coerceAtLeast(1)
    var roll = Random.nextInt(total)
    items.forEach { item ->
        roll -= weight(item).coerceAtLeast(0)
        if (roll < 0) return item
    }
    return items.last()
}

private fun hybridRandomTier(baseTier: Int, category: HybridCategory): Int {
    val offset = weightedRandom(listOf(-1 to 25, 0 to 50, 1 to 25)) { it.second }.first
    val tiers = category.tiers.keys
    return (baseTier + offset).coerceIn(tiers.minOrNull() ?: 1, tiers.maxOrNull() ?: 5)
}

private fun randomHybridMutationUnit(): UnitType? =
    Vars.content.units()
        .filter { !it.hidden && !it.internal && it.constructor != null && it.health > 0f }
        .randomOrNull()

private fun chooseHybridChildType(fatherType: UnitType, motherType: UnitType): HybridChildChoice {
    val fatherCategory = hybridCategoryOf(fatherType)
    val motherCategory = hybridCategoryOf(motherType)
    val inheritFather = if (fatherCategory != null && fatherCategory == motherCategory) Random.nextBoolean() else Random.nextInt(100) < 60
    val chosenCategory = if (inheritFather) fatherCategory else motherCategory
    val baseType = if (inheritFather) fatherType else motherType
    val side = if (inheritFather) "父方" else "母方"

    if (chosenCategory == null) {
        val child = if (baseType.constructor != null && baseType.health > 0f) baseType else randomHybridMutationUnit()
        return HybridChildChoice(child, detail = "遗传${side}的特殊血统")
    }

    val baseTier = hybridTierOf(baseType) ?: 1

    if (chosenCategory.allowMissileChild && Random.nextInt(100) < 12) {
        availableHybridMissiles().randomOrNull()?.let {
            return HybridChildChoice(it, detail = "遗传${side}的${chosenCategory.label}血统，并突变出导弹分支")
        }
    }

    val tier = hybridRandomTier(baseTier, chosenCategory)
    val tierText = when {
        tier > baseTier -> "升阶+${tier - baseTier}"
        tier < baseTier -> "降阶-${baseTier - tier}"
        else -> "同阶"
    }
    return HybridChildChoice(
        (chosenCategory.tiers[tier] ?: chosenCategory.tiers.values.flatten()).randomOrNull(),
        detail = "遗传${side}的${chosenCategory.label}血统（$tierText）"
    )
}

private fun applyStackedStatus(unit: MindustryUnit, effect: StatusEffect, duration: Float, stacks: Int = 1): Int {
    if (effect == StatusEffects.none || effect == StatusEffects.dynamic || unit.isImmune(effect)) return 0
    var applied = 0
    repeat(stacks.coerceAtLeast(1)) {
        val entry = Pools.obtain(StatusEntry::class.java, ::StatusEntry)
        entry.damageTime = 0f
        entry.set(effect, duration)
        statusEntries(unit).add(entry)
        effect.applied(unit, duration, false)
        applied++
    }
    return applied
}

private fun snapshotHybridStatuses(unit: MindustryUnit): List<HybridStatusSnapshot> {
    val grouped = linkedMapOf<StatusEffect, MutableList<Float>>()
    statusEntries(unit).forEach { entry ->
        val effect = entry.effect ?: return@forEach
        if (effect == StatusEffects.dynamic || effect !in hybridKnownEffects || entry.time <= 0f) return@forEach
        grouped.getOrPut(effect) { mutableListOf() } += entry.time
    }
    return grouped.map { (effect, times) ->
        HybridStatusSnapshot(effect, (times.maxOrNull() ?: 0f).coerceAtLeast(60f), times.size.coerceAtLeast(1))
    }
}

private fun applyHybridSnapshots(unit: MindustryUnit, snapshots: List<HybridStatusSnapshot>): List<StatusEffect> {
    val applied = mutableListOf<StatusEffect>()
    snapshots.forEach { snapshot ->
        repeat(applyStackedStatus(unit, snapshot.effect, snapshot.duration, snapshot.stacks)) {
            applied += snapshot.effect
        }
    }
    return applied
}

private fun applyHybridQuality(unit: MindustryUnit, quality: HybridQuality): List<StatusEffect> {
    val applied = mutableListOf<StatusEffect>()
    if (quality.shieldMultiplier > 0f) unit.shield += unit.maxHealth * quality.shieldMultiplier
    quality.effects.forEach {
        repeat(applyStackedStatus(unit, it, Float.POSITIVE_INFINITY)) { _ -> applied += it }
    }
    if (quality.invincibleSeconds > 0f) {
        repeat(applyStackedStatus(unit, StatusEffects.invincible, quality.invincibleSeconds * 60f)) {
            applied += StatusEffects.invincible
        }
    }
    if (quality.hopeless) {
        unit.statusMaxHealth(1f)
        unit.health = 1f
        launch(Dispatchers.game) {
            delay(60_000L)
            if (unit.isValid && !unit.dead) unit.kill()
        }
    }
    return applied
}

private fun randomHybridSpiritCount(): Int =
    weightedRandom(listOf(2 to 28, 3 to 23, 4 to 18, 5 to 13, 6 to 9, 7 to 6, 8 to 3)) { it.second }.first

private fun applyHybridSpirit(unit: MindustryUnit): List<StatusEffect> {
    val pool = hybridKnownEffects
        .filter { it != StatusEffects.invincible && it != StatusEffects.dynamic }
    val applied = mutableListOf<StatusEffect>()
    repeat(randomHybridSpiritCount()) {
        val effect = pool.random()
        repeat(applyStackedStatus(unit, effect, 60f * Random.nextInt(20, 121))) {
            applied += effect
        }
    }
    return applied
}

private fun hybridBuffText(effects: List<StatusEffect>): String {
    if (effects.isEmpty()) return "无额外buff"
    return effects
        .groupingBy { it }
        .eachCount()
        .entries
        .joinToString("，") { (effect, count) ->
            val name = hybridEffectNames[effect] ?: effect.localizedName
            if (count > 1) "${name}x$count" else name
        }
}

private fun destroyHybridThing(unit: MindustryUnit?): Boolean {
    if (unit == null) return false
    if (unit is BlockUnitc) {
        val build = runCatching { unit.tile() }.getOrNull() ?: return false
        return runCatching {
            build.tile.setNet(Blocks.air)
            true
        }.getOrDefault(false)
    }
    if (unit.isValid && !unit.dead) {
        unit.kill()
        return true
    }
    return false
}

private fun executeUnitHybrid(caster: Player, target: Player): String? {
    val father = caster.unit() ?: return "单位杂交失败：你没有有效单位。"
    val mother = target.unit() ?: return "单位杂交失败：目标没有有效单位。"
    if (father is BlockUnitc || mother is BlockUnitc) return "单位杂交仅支持双方附身普通单位。"
    if (father.dead || mother.dead || !father.isValid || !mother.isValid) return "单位杂交失败：双方都需要拥有存活单位。"
    hybridPairError(father.type, mother.type)?.let { return "单位杂交失败：$it" }

    val fatherBuffs = snapshotHybridStatuses(father)
    val motherBuffs = snapshotHybridStatuses(mother)
    val childChoice = chooseHybridChildType(father.type, mother.type).also {
        if (it.error != null) return "单位杂交失败：${it.error}"
    }
    var childType = childChoice.type ?: return "单位杂交失败：未能生成子单位类型。"
    val details = mutableListOf<String>()
    childChoice.detail.takeIf { it.isNotBlank() }?.let { details += it }

    var geneticMutation = false
    var spirited = false
    if (Random.nextInt(100) < 20) {
        val specialCount = if (Random.nextInt(100) < 35) 2 else 1
        val specials = listOf("spirit", "mutation").shuffled().take(specialCount)
        spirited = "spirit" in specials
        if ("mutation" in specials) {
            randomHybridMutationUnit()?.let {
                childType = it
                geneticMutation = true
                details += "触发基因突变"
            }
        }
    }
    if (spirited) details += "触发抖擞精神"

    val x = (father.x + mother.x) / 2f
    val y = (father.y + mother.y) / 2f
    val child = childType.create(caster.team()).apply {
        set(x, y)
        rotation(father.rotation)
        add()
    }
    Call.effect(Fx.spawn, child.x, child.y, 0f, Color.valueOf("ff77cc"))

    val shownBuffs = mutableListOf<StatusEffect>()
    if ((fatherBuffs.isNotEmpty() || motherBuffs.isNotEmpty()) && Random.nextInt(100) < 50) {
        val picked = listOf("父方" to fatherBuffs, "母方" to motherBuffs)
            .filter { it.second.isNotEmpty() }
            .randomOrNull()
        if (picked != null) {
            details += "遗传${picked.first}buff的"
            shownBuffs += applyHybridSnapshots(child, picked.second)
        }
    } else {
        details += if (fatherBuffs.isEmpty() && motherBuffs.isEmpty()) "父母无可遗传buff" else "未遗传父母buff"
    }

    val quality = if (Random.nextInt(100) < 60) weightedRandom(hybridQualityPool) { it.weight } else null
    if (quality != null) {
        details += "品质：${quality.plainName}"
        shownBuffs += applyHybridQuality(child, quality)
    } else {
        details += "品质：普通"
    }
    if (spirited) shownBuffs += applyHybridSpirit(child)

    val fatherDeathChance = Random.nextInt(20, 51)
    val motherDeathChance = Random.nextInt(20, 51)
    val fatherDeadByHybrid = Random.nextInt(100) < fatherDeathChance && father.isValid && !father.dead
    val motherDeadByHybrid = Random.nextInt(100) < motherDeathChance && mother.isValid && !mother.dead
    if (fatherDeadByHybrid) destroyHybridThing(father)
    if (motherDeadByHybrid) destroyHybridThing(mother)
    details += "父方死亡判定${fatherDeathChance}%：${if (fatherDeadByHybrid) "死亡" else "幸存"}"
    details += "母方死亡判定${motherDeathChance}%：${if (motherDeadByHybrid) "死亡" else "幸存"}"

    val manner = if (spirited) "[accent]抖擞精神地[white]" else ""
    val mutationPrefix = if (geneticMutation) "[accent]基因突变的[white]" else ""
    val qualityText = quality?.coloredName ?: "[white]普通的"
    val buffText = hybridBuffText(shownBuffs)
    Call.sendMessage(
        "[pink]${caster.name}[white]和[pink]${target.name}[white]${manner}单位杂交出来一个：${mutationPrefix}${qualityText}[white]${childType.localizedName}[white]（$buffText）！\n[gray]详情：${details.joinToString("；")}"
    )
    return null
}

private fun teamCooldownKey(team: Team, mode: MapHybridMode): String = "${team.id}:${mode.name}"

private fun checkTeamCooldown(team: Team, mode: MapHybridMode): String? {
    if (mode.cooldownMillis <= 0L) return null
    val until = teamCooldowns[teamCooldownKey(team, mode)] ?: 0L
    val now = System.currentTimeMillis()
    if (until <= now) return null
    return "[red]${mode.label}队伍共享冷却中，还剩 ${(until - now + 999L) / 1000L} 秒"
}

private fun setTeamCooldown(team: Team, mode: MapHybridMode) {
    if (mode.cooldownMillis <= 0L) return
    teamCooldowns[teamCooldownKey(team, mode)] = System.currentTimeMillis() + mode.cooldownMillis
}

private fun turretHasNoValidAmmoType(turret: Turret): Boolean = when (turret) {
    is ItemTurret -> turret.ammoTypes.entries().none { it.value != null }
    is LiquidTurret -> turret.ammoTypes.entries().none { it.value != null }
    is PowerTurret -> turret.shootType == null
    is ContinuousTurret -> turret.shootType == null
    else -> false
}

private fun sanitizeInvalidTurretAmmo(build: Turret.TurretBuild): Boolean {
    val turret = build.block as? Turret
    var changed = false
    var total = 0

    if (!build.ammo.isEmpty) {
        var i = build.ammo.size - 1
        while (i >= 0) {
            val entry = build.ammo.get(i)
            val bullet = runCatching { entry.type() }.getOrNull()
            if (entry.amount <= 0 || bullet == null) {
                build.ammo.remove(i)
                changed = true
            } else {
                total += entry.amount.coerceAtLeast(0)
            }
            i--
        }
    }

    if (changed) {
        build.totalAmmo = total.coerceAtMost(turret?.maxAmmo ?: total)
    }

    val unsafeNullAmmo = runCatching { build.hasAmmo() && build.peekAmmo() == null }.getOrDefault(false)
    val noValidAmmoType = turret?.let(::turretHasNoValidAmmoType) ?: false
    if (unsafeNullAmmo || noValidAmmoType) {
        runCatching { build.tile.setNet(Blocks.air) }
            .onFailure { logger.warning("@hybrid 基因杂交炮塔弹药保护：移除异常炮塔失败 ${build.block.name}: ${it.message}") }
        return true
    }

    return changed
}

private fun sanitizeInvalidTurretAmmo(reason: String = ""): Int {
    var fixed = 0
    Groups.build.toList().forEach { build ->
        val turret = build as? Turret.TurretBuild ?: return@forEach
        if (sanitizeInvalidTurretAmmo(turret)) fixed++
    }
    if (fixed > 0) {
        logger.warning("@hybrid 基因杂交炮塔弹药保护：清理 $fixed 个无效弹药炮塔${if (reason.isBlank()) "" else " ($reason)"}")
    }
    return fixed
}

private fun hybridTurretWeaponRegionName(turret: Turret): String =
    hybridTurretWeaponRegionOverrides[turret.name] ?: turret.name

private fun hybridSafeId(text: String): String =
    text.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').ifBlank { "gene" }

private fun hybridClassName(value: Any): String {
    val raw = value.javaClass.let { if (it.isAnonymousClass) it.superclass else it }
    return raw.simpleName
}

private fun jsonString(text: String): String = buildString {
    append('"')
    text.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}

private fun finite(value: Float): Boolean = !value.isNaN() && !value.isInfinite()
private fun finite(value: Double): Boolean = !value.isNaN() && !value.isInfinite()

private val hybridSerializeMaxDepth = 14

private fun hybridStaticFieldName(holder: Class<*>, value: Any): String? =
    holder.fields.firstOrNull { field ->
        Modifier.isStatic(field.modifiers) && runCatching { field.get(null) === value }.getOrDefault(false)
    }?.name

private fun hybridEffectName(effect: Effect): String? = hybridStaticFieldName(Fx::class.java, effect)

private fun hybridSoundName(sound: Sound): String? = hybridStaticFieldName(Sounds::class.java, sound)

private fun hybridInterpName(interp: Interp): String? = hybridStaticFieldName(Interp::class.java, interp)

private fun hybridSerializeEffect(effect: Effect, depth: Int = 0): String? {
    hybridEffectName(effect)?.let { return jsonString(it) }
    val cls = effect.javaClass.let { if (it.isAnonymousClass) it.superclass else it }
    return if (cls.`package`?.name == "mindustry.entities.effect") {
        hybridSerializeObject(effect, forceType = true, depth = depth + 1)
    } else {
        jsonString("none")
    }
}

private val hybridSkipFieldNames = setOf(
    "region", "heatRegion", "cellRegion", "outlineRegion", "fullIcon", "uiIcon", "editorIcon",
    "mountType", "parts", "drawer", "icons", "stats", "clipSize", "outlineRadius",
    "activeSound", "loopSound",
    "parent", "data", "timer", "wasHealed", "wasBroken", "id", "renderer",
    "unitFilter", "buildingFilter", "unitSort", "controller", "aiController", "constructor", "otherSide",
)

private fun hybridSerializableValue(value: Any?, depth: Int = 0): String? {
    if (depth > hybridSerializeMaxDepth) return null
    return when (value) {
        null -> null
        is String -> jsonString(value)
        is Char -> jsonString(value.toString())
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is Float -> if (finite(value)) value.toString() else null
        is Double -> if (finite(value)) value.toString() else null
        is Enum<*> -> jsonString(value.name)
        is Color -> jsonString(value.toString())
        is Effect -> hybridSerializeEffect(value, depth)
        is Sound -> hybridSoundName(value)?.let(::jsonString)
        is Interp -> hybridInterpName(value)?.let(::jsonString)
        is MappableContent -> jsonString(value.name)
        is Weapon -> hybridSerializeWeapon(value)
        is BulletType -> hybridSerializeBullet(value, depth + 1)
        is Ability -> hybridSerializeObject(value, forceType = true, depth = depth + 1)
        is Iterable<*> -> value.mapNotNull { hybridSerializableValue(it, depth + 1) }
            .takeIf { it.isNotEmpty() }?.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> value.mapNotNull { hybridSerializableValue(it, depth + 1) }
            .takeIf { it.isNotEmpty() }?.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> if (value.all { finite(it) }) value.joinToString(prefix = "[", postfix = "]") else null
        is DoubleArray -> if (value.all { finite(it) }) value.joinToString(prefix = "[", postfix = "]") else null
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
        is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        else -> {
            val cls = value.javaClass.let { if (it.isAnonymousClass) it.superclass else it }
            val pkg = cls.`package`?.name.orEmpty()
            when {
                pkg.startsWith("arc.func") -> null
                pkg.startsWith("arc.audio") -> null
                cls.name.contains("TextureRegion") -> null
                pkg.startsWith("mindustry.entities.pattern") -> hybridSerializeObject(value, forceType = cls.simpleName != "ShootPattern", depth = depth + 1)
                pkg.startsWith("mindustry.entities.part") -> null
                else -> null
            }
        }
    }
}

private fun hybridSerializeBullet(bullet: BulletType, depth: Int = 0): String? {
    val overrides = mutableMapOf<String, String>()
    val className = hybridClassName(bullet)
    val complexLineBullet = className.contains("Laser", ignoreCase = true) ||
            className.contains("Rail", ignoreCase = true) ||
            className.contains("Shrapnel", ignoreCase = true)

    if (bullet.fragBullet != null || bullet.splashDamageRadius > 0f || bullet.lightning > 0) {
        overrides["despawnHit"] = bullet.despawnHit.toString()
    }
    if (complexLineBullet && bullet.fragBullet != null) {
        overrides["fragOnHit"] = bullet.fragOnHit.toString()
        overrides["fragOnDespawn"] = bullet.fragOnDespawn.toString()
        overrides["fragOnAbsorb"] = bullet.fragOnAbsorb.toString()
        overrides["delayFrags"] = bullet.delayFrags.toString()
        overrides["pierceFragCap"] = bullet.pierceFragCap.toString()
        overrides["collidesTiles"] = bullet.collidesTiles.toString()
        overrides["collidesGround"] = bullet.collidesGround.toString()
    }
    return hybridSerializeObject(bullet, forceType = true, depth = depth, overrides = overrides)
}

private fun hybridSerializeObject(
    value: Any,
    forceType: Boolean = false,
    depth: Int = 0,
    overrides: Map<String, String> = emptyMap(),
): String? {
    if (depth > hybridSerializeMaxDepth) return null
    val cls = value.javaClass.let { if (it.isAnonymousClass) it.superclass else it }
    val fields = linkedMapOf<String, String>()
    if (forceType) fields["type"] = jsonString(hybridClassName(value))
    cls.fields
        .filter { field ->
            val mods = field.modifiers
            Modifier.isPublic(mods) && !Modifier.isStatic(mods) && !Modifier.isTransient(mods) && !field.isSynthetic &&
                    field.name !in hybridSkipFieldNames
        }
        .sortedBy { it.name }
        .forEach { field ->
            val raw = runCatching { field.get(value) }.getOrNull() ?: return@forEach
            hybridSerializableValue(raw, depth + 1)?.let { fields[field.name] = it }
        }
    overrides.forEach { (key, v) -> fields[key] = v }
    if (fields.isEmpty()) return null
    return fields.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "${jsonString(k)}:$v" }
}

private fun hybridSerializeWeapon(weapon: Weapon): String? {
    val overrides = mutableMapOf<String, String>()
    overrides["mirror"] = "false"
    if (weapon.recoilTime <= 0f) overrides["recoilTime"] = weapon.reload.coerceAtLeast(1f).toString()
    if (weapon.recoilTime <= 0f && weapon.recoil > 0f) overrides["recoil"] = "0"
    val cls = weapon.javaClass.let { if (it.isAnonymousClass) it.superclass else it }
    return hybridSerializeObject(
        weapon,
        forceType = cls != Weapon::class.java,
        depth = 0,
        overrides = overrides
    )
}

private fun turretBulletCandidates(turret: Turret, build: Turret.TurretBuild): List<Pair<String, BulletType>> {
    val out = mutableListOf<Pair<String, BulletType>>()
    when (turret) {
        is ItemTurret -> {
            for (entry in turret.ammoTypes.entries()) {
                val bullet = entry.value ?: continue
                out += (entry.key.localizedName to bullet)
            }
        }
        is LiquidTurret -> {
            for (entry in turret.ammoTypes.entries()) {
                val bullet = entry.value ?: continue
                out += (entry.key.localizedName to bullet)
            }
        }
        is PowerTurret -> turret.shootType?.let { out += ("能量" to it) }
        is ContinuousTurret -> turret.shootType?.let { out += ("持续" to it) }
        else -> runCatching { build.peekAmmo() }.getOrNull()?.let { out += ("当前弹药" to it) }
    }
    return out.filter { it.second.damage >= 0f || it.second.heals() }.distinctBy { it.second }
}

private fun turretWeaponEntry(turret: Turret, bullet: BulletType): String? {
    val bulletJson = hybridSerializableValue(bullet) ?: return null
    val reload = turret.reload.coerceAtLeast(1f)
    val shootY = if (finite(turret.shootY)) turret.shootY else 4f
    val shootJson = hybridSerializableValue(turret.shoot)
    val shootSound = hybridSerializableValue(turret.shootSound)
    val weaponRegion = hybridTurretWeaponRegionName(turret)
    return buildString {
        append("\"weapons.+\":[{")
        append("\"name\":${jsonString(weaponRegion)},")
        append("\"x\":0,\"y\":0,\"shootX\":${turret.shootX},\"shootY\":$shootY,")
        append("\"mirror\":false,\"rotate\":true,\"rotateSpeed\":${turret.rotateSpeed},")
        append("\"reload\":$reload,\"recoil\":0,\"recoilTime\":$reload,")
        append("\"inaccuracy\":${turret.inaccuracy},\"shootCone\":${turret.shootCone},")
        if (bullet is ContinuousBulletType) append("\"continuous\":true,\"alwaysContinuous\":true,")
        if (shootJson != null) append("\"shoot\":$shootJson,")
        if (shootSound != null) append("\"shootSound\":$shootSound,\"shootSoundVolume\":${turret.shootSoundVolume},\"soundPitchMin\":${turret.soundPitchMin},\"soundPitchMax\":${turret.soundPitchMax},")
        append("\"bullet\":$bulletJson")
        append("}]")
    }
}

private fun canHealEntry(enabled: Boolean): Array<String> =
    if (enabled) arrayOf("\"canHeal\":true") else emptyArray()

private fun geneTemplate(
    sourceId: String,
    displayName: String,
    kind: GeneKind,
    vararg entries: String,
): GeneTemplate = GeneTemplate(
    sourceId = sourceId,
    displayName = displayName,
    kind = kind,
    entries = entries.map { it.trimIndent() },
)

private fun geneTemplatesFromTurret(player: Player): List<GeneTemplate> {
    val blockUnit = player.unit() as? BlockUnitc ?: return emptyList()
    val build = blockUnit.tile() as? Turret.TurretBuild ?: return emptyList()
    sanitizeInvalidTurretAmmo(build)
    val turret = build.block as? Turret ?: return emptyList()
    return turretBulletCandidates(turret, build).mapIndexedNotNull { index, (ammoName, bullet) ->
        val entry = turretWeaponEntry(turret, bullet) ?: return@mapIndexedNotNull null
        geneTemplate(
            hybridSafeId("${turret.name}-$index-$ammoName-${hybridClassName(bullet)}"),
            "${turret.localizedName}${ammoName}武器",
            GeneKind.TurretGene,
            entry,
            *canHealEntry(bullet.heals())
        ).copy(sourceDebug = "turret=${turret.name}, ammo=$ammoName")
    }
}

private fun geneTemplatesFromUnit(type: UnitType): List<GeneTemplate> {
    val candidates = mutableListOf<GeneTemplate>()
    type.weapons.forEachIndexed { index, weapon ->
        val entry = hybridSerializeWeapon(weapon)?.let { "\"weapons.+\":[$it]" } ?: return@forEachIndexed
        val display = if (weapon.name.isNullOrBlank()) "${type.localizedName}武器${index + 1}" else "${type.localizedName}:${weapon.name}"
        candidates += geneTemplate(
            hybridSafeId("${type.name}-weapon-$index-${weapon.name ?: "weapon"}"),
            display,
            GeneKind.UnitGene,
            entry,
            *canHealEntry(weapon.bullet.heals())
        ).copy(sourceDebug = "unit=${type.name}, weaponIndex=$index, weapon=${weapon.name ?: "weapon"}")
    }
    type.abilities.forEachIndexed { index, ability ->
        val entry = hybridSerializableValue(ability)?.let { "\"abilities.+\":[$it]" } ?: return@forEachIndexed
        candidates += geneTemplate(
            hybridSafeId("${type.name}-ability-$index-${hybridClassName(ability)}"),
            "${type.localizedName}:${hybridClassName(ability)}",
            GeneKind.UnitGene,
            entry,
            *canHealEntry(hybridClassName(ability).contains("Repair", ignoreCase = true))
        ).copy(sourceDebug = "unit=${type.name}, abilityIndex=$index, ability=${hybridClassName(ability)}")
    }
    return candidates
}

private fun geneTemplatesFromTarget(target: Player): List<GeneTemplate> {
    val targetUnit = target.unit() ?: return emptyList()
    return if (targetUnit is BlockUnitc) geneTemplatesFromTurret(target) else geneTemplatesFromUnit(targetUnit.type)
}

private fun sourceCanAcceptGeneTemplate(sourceType: UnitType, template: GeneTemplate): Boolean {
    val record = geneRecords[sourceType.name]
    return when (template.kind) {
        GeneKind.TurretGene -> record?.turretGene == null
        GeneKind.UnitGene -> true
    }
}

private fun availableGeneTemplatesFor(caster: Player, target: Player): List<GeneTemplate> {
    val sourceType = caster.unit()?.type ?: return emptyList()
    return geneTemplatesFromTarget(target).filter { sourceCanAcceptGeneTemplate(sourceType, it) }
}

private fun geneTemplateKindName(template: GeneTemplate): String =
    if (template.kind == GeneKind.TurretGene) "炮塔武器" else if (template.entries.any { it.contains("\"abilities.+\"") }) "单位能力" else "单位武器"

private fun geneTemplateOptionText(template: GeneTemplate): String {
    val kind = geneTemplateKindName(template)
    val color = when (kind) {
        "炮塔武器" -> "[accent]"
        "单位能力" -> "[pink]"
        else -> "[cyan]"
    }
    return "$color$kind[]：[white]${template.displayName}\n[gray]成功后击杀双方并进入队伍冷却"
}

private fun geneHybridSourceError(caster: Player, target: Player, label: String = "基因杂交"): String? {
    if (caster.dead()) return "死亡状态无法${label}。"
    if (target === caster || target.uuid() == caster.uuid()) return "${label}至少需要两名不同玩家。"
    if (target.dead()) return "目标处于死亡状态，无法${label}。"
    val source = caster.unit() ?: return "${label}失败：你没有有效单位。"
    if (source is BlockUnitc) return "炮塔/建筑不能作为${label}发起者。"
    geneHybridUnitError(source.type)?.let { return "发起者单位不支持${label}：$it" }

    val targetUnit = target.unit() ?: return "${label}失败：目标没有有效单位。"
    if (targetUnit is BlockUnitc) {
        if (geneTemplatesFromTurret(target).isEmpty()) return "目标控制的炮塔暂未找到可序列化弹药/武器。"
    } else {
        geneHybridUnitError(targetUnit.type)?.let { return "目标单位不支持${label}：$it" }
        if (geneTemplatesFromUnit(targetUnit.type).isEmpty()) return "目标单位暂未找到可序列化武器/能力。"
    }

    val record = geneRecords[source.type.name]
    if (targetUnit is BlockUnitc) {
        if (record?.turretGene != null) return "${source.type.localizedName} 已经进行过炮塔基因杂交。"
    }
    return null
}

private fun geneTemplatePrecheck(caster: Player, template: GeneTemplate, label: String, expectedSourceName: String? = null): String? {
    if (caster.dead()) return "死亡状态无法${label}。"
    val sourceUnit = caster.unit() ?: return "${label}失败：你没有有效单位。"
    if (sourceUnit is BlockUnitc) return "炮塔/建筑不能作为${label}发起者。"
    val sourceType = sourceUnit.type
    if (expectedSourceName != null && sourceType.name != expectedSourceName) {
        val expectedDisplay = unitTypeByName(expectedSourceName)?.localizedName ?: expectedSourceName
        return "${label}菜单已绑定到 $expectedDisplay；你当前单位已变化，请重新发起。"
    }
    geneHybridUnitError(sourceType)?.let { return "发起者单位不支持${label}：$it" }
    if (!sourceCanAcceptGeneTemplate(sourceType, template)) {
        return "${sourceType.localizedName} 已经进行过炮塔基因杂交。"
    }
    return null
}

private fun buildGenePatch(sourceType: UnitType, template: GeneTemplate): String {
    val entries = template.entries.joinToString(",\n")
    return """
    {
      "unit": {
        "${sourceType.name}": {
          $entries
        }
      }
    }
    """.trimIndent()
}

private fun currentAppliedPatchStrings(): List<String> = with(contentsTweaker) { currentPatchStrings() }

private fun applyPatchStringsAndSanitize(patches: List<String>, reason: String) {
    with(contentsTweaker) { applyPatchStrings(patches) }
    sanitizeInvalidTurretAmmo(reason)
}

private fun rebuildHybridRuntimeUnits(types: Collection<UnitType>) {
    val targetNames = types.map { it.name }.toSet()
    if (targetNames.isEmpty()) return
    Groups.unit.each { unit ->
        if (unit.type.name !in targetNames) return@each
        val type = unit.type
        runCatching {
            unit.mounts = Array(type.weapons.size) { index ->
                val weapon = type.weapons[index]
                weapon.mountType.get(weapon)
            }
            unit.abilities = Array(type.abilities.size) { index ->
                type.abilities[index].copy()
            }
        }.onFailure {
            logger.warning("@hybrid 基因杂交刷新现有单位失败: ${type.name} ${it.message}")
        }
    }
}

private fun rebuildAllGeneHybridUnits() {
    rebuildHybridRuntimeUnits(geneRecords.keys.mapNotNull(::unitTypeByName))
}

private suspend fun sendWorldDataCompat(player: Player) {
    with(worldResync) { resyncWorldAndAssets(player, "地图杂交基因变更") }
}

private fun syncHybridContentToClients() {
    val generation = ++geneSyncGeneration
    val players = Groups.player.toList().filter { it.con != null && !it.isLocal }
    launch(Dispatchers.game) {
        delay(autoWorldSyncDebounceMillis)
        if (generation != geneSyncGeneration) return@launch
        players.forEachIndexed { index, player ->
            if (generation != geneSyncGeneration) return@launch
            if (index > 0) delay(autoWorldSyncDelayMillis.coerceAtLeast(0L))
            if (generation != geneSyncGeneration) return@launch
            runCatching {
                if (player.con != null) {
                    sendWorldDataCompat(player)
                }
            }.onFailure {
                logger.warning("@hybrid 基因杂交同步玩家 ${player.plainName()} 失败: ${it.message}")
            }
        }
    }
}

private fun applyGenePatch(
    patchName: String,
    patch: String,
    affectedUnitNames: Collection<String>,
    verifyApplied: () -> String? = { null },
) {
    val readPatch = Jval.read(patch).toString(Jval.Jformat.plain)
    val before = currentAppliedPatchStrings()
    val previousHybridPatches = genePatchStrings.toList()
    val activeWithoutHybrid = before.filterNot { it in genePatchStrings }
    val startedAt = System.currentTimeMillis()
    try {
        applyPatchStringsAndSanitize(activeWithoutHybrid + previousHybridPatches + readPatch, "@hybrid 基因杂交应用")
        val patchSet = with(contentsTweaker) { patchInfoFor(readPatch) }
        if (patchSet == null || patchSet.error) {
            val warnings = patchSet?.warnings?.joinToString("; ").orEmpty().ifBlank { "补丁未进入当前patcher或解析失败" }
            throw IllegalStateException("@hybrid 基因杂交CP解析失败：$patchName：$warnings")
        }
        verifyApplied()?.let { issue ->
            throw IllegalStateException("@hybrid 基因杂交CP未实际生效：$patchName：$issue")
        }
        genePatchStrings += readPatch
        rebuildAllGeneHybridUnits()
        rebuildHybridRuntimeUnits(affectedUnitNames.mapNotNull(::unitTypeByName))
        syncHybridContentToClients()
        val cost = System.currentTimeMillis() - startedAt
        if (cost >= 250L) {
            logger.warning("@hybrid 基因杂交CP应用耗时 ${cost}ms，当前地图杂交补丁数=${genePatchStrings.size}，基础补丁数=${activeWithoutHybrid.size}")
        }
    } catch (t: Throwable) {
        runCatching {
            genePatchStrings.clear()
            genePatchStrings.addAll(previousHybridPatches)
            applyPatchStringsAndSanitize(before, "@hybrid 基因杂交回滚")
        }.onFailure { logger.warning("@hybrid 基因杂交CP回滚失败: ${it.message}") }
        rebuildAllGeneHybridUnits()
        rebuildHybridRuntimeUnits(affectedUnitNames.mapNotNull(::unitTypeByName))
        syncHybridContentToClients()
        throw t
    }
}

private fun executeGeneTemplate(
    caster: Player,
    target: Player,
    template: GeneTemplate,
    label: String,
    expectedSourceName: String? = null,
    killParticipants: Boolean = false,
): String? {
    geneTemplatePrecheck(caster, template, label, expectedSourceName)?.let { return it }
    val sourceUnit = caster.unit() ?: return "${label}失败：你没有有效单位。"
    val sourceType = sourceUnit.type

    val record = geneRecords.getOrPut(sourceType.name) { GeneRecord() }
    if (template.kind == GeneKind.TurretGene && record.turretGene != null) {
        return "${sourceType.localizedName} 已经进行过炮塔基因杂交。"
    }

    val targetUnit = target.unit() ?: return "${label}失败：目标没有有效单位。"
    val patchName = "$" + "map-hybrid-${sourceType.name}-${template.kind.name.lowercase()}-${template.sourceId}"
    val patch = buildGenePatch(sourceType, template)
    val sourceTypeName = sourceType.name
    val sourceDisplayName = sourceType.localizedName
    val beforeType = unitTypeByName(sourceTypeName) ?: sourceType
    val beforeWeapons = beforeType.weapons.size
    val beforeAbilities = beforeType.abilities.size
    val expectsWeapon = template.entries.any { it.contains("\"weapons.+\"") }
    val expectsAbility = template.entries.any { it.contains("\"abilities.+\"") }

    applyGenePatch(patchName, patch, affectedUnitNames = listOf(sourceTypeName)) {
        val currentType = unitTypeByName(sourceTypeName) ?: return@applyGenePatch "源单位类型在CP应用后不存在"
        if (expectsWeapon && currentType.weapons.size <= beforeWeapons) return@applyGenePatch "武器数量未增加"
        if (expectsAbility && currentType.abilities.size <= beforeAbilities) return@applyGenePatch "能力数量未增加"
        null
    }

    if (template.kind == GeneKind.TurretGene) record.turretGene = template.displayName else record.unitGenes += template.displayName
    if (killParticipants) {
        destroyHybridThing(sourceUnit)
        destroyHybridThing(targetUnit)
    }

    setTeamCooldown(caster.team(), if (label == MapHybridMode.SelectGeneHybrid.label) MapHybridMode.SelectGeneHybrid else MapHybridMode.GeneHybrid)
    val consequence = if (killParticipants) "双方已被杂交反噬。" else ""
    Call.sendMessage("[accent]${label}已应用：新生成的 ${sourceDisplayName} 将获得 ${template.displayName} 基因。${consequence}若客户端显示异常，请手动执行 /sync 或重进服务器。")
    return null
}

private fun executeRandomGeneHybrid(caster: Player, target: Player): String? {
    geneHybridSourceError(caster, target, MapHybridMode.GeneHybrid.label)?.let { return it }
    checkTeamCooldown(caster.team(), MapHybridMode.GeneHybrid)?.let { return it }
    val templates = availableGeneTemplatesFor(caster, target)
    val template = templates.randomOrNull() ?: return "目标当前没有可获取的武器/能力，或你的当前单位对应基因槽位已用完。"
    return executeGeneTemplate(caster, target, template, MapHybridMode.GeneHybrid.label)
}

private fun resolveOnlinePlayer(text: String): Player? {
    val fixed = text.trim()
    if (fixed.isBlank()) return null
    if (fixed.startsWith("#")) {
        fixed.substring(1).toIntOrNull()?.let { id -> Groups.player.getByID(id)?.let { return it } }
    }
    fixed.toIntOrNull()?.let { id -> Groups.player.getByID(id)?.let { return it } }
    val plain = fixed.replace(" ", "")
    return Groups.player.find {
        it.uuid() == fixed || it.plainName().replace(" ", "").equals(plain, ignoreCase = true)
    }
}

private suspend fun askHybridAccept(caster: Player, target: Player, label: String): Boolean? {
    var accepted: Boolean? = null
    val consequence = when (label) {
        MapHybridMode.UnitHybrid.label -> "单位杂交成功后父母单位会分别概率死亡。"
        MapHybridMode.SelectGeneHybrid.label -> "自选基因杂交成功后会击杀杂交双方。"
        else -> "基因杂交成功后不会击杀双方。"
    }
    MenuBuilder<Unit>("${label}请求") {
        msg = "[pink]\"${caster.plainName()}\"[white]想要和你进行${label}，是否接受？\n[gray]$consequence"
        option("接受") { accepted = true }
        option("拒绝") { accepted = false }
    }.sendTo(target, 15_000)
    return accepted
}

private suspend fun openHybridModeMenu(player: Player) {
    MenuBuilder<Unit>("地图特色杂交") {
        msg = """
            |[cyan]请选择杂交方式。
            |[green]单位杂交[gray]：队伍共享冷却 10 秒；生成一个子单位，父母单位概率死亡。
            |[accent]基因杂交[gray]：随机获取目标一个武器/能力，队伍冷却 120 秒。
            |[gold]自选基因杂交[gray]：手动选择目标武器/能力，队伍冷却 300 秒，会击杀双方。
        """.trimMargin()
        option("[green]单位杂交\n[gray]选择目标玩家，目标同意后生成子单位") { openHybridTargetMenu(player, MapHybridMode.UnitHybrid) }
        option("[accent]基因杂交\n[gray]随机抽取目标当前武器/能力") { openHybridTargetMenu(player, MapHybridMode.GeneHybrid) }
        newRow()
        option("[gold]自选基因杂交\n[gray]目标同意后列出可获取武器/能力") { openHybridTargetMenu(player, MapHybridMode.SelectGeneHybrid) }
        newRow()
        option("[cyan]查看基因杂交状态") { showGeneHybridStatus(player) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openHybridTargetMenu(caster: Player, mode: MapHybridMode) {
    val targets = Groups.player.toList().filter { it !== caster }.sortedBy { it.plainName() }
    if (targets.isEmpty()) {
        caster.sendMessage("[yellow]当前没有可杂交的在线目标。")
        return
    }
    PagedMenuBuilder(targets) { target ->
        option("${target.name} [gray](#${target.id})\n[gray]点击后发送${mode.label}请求") {
            launch(Dispatchers.game) { requestHybrid(caster, target, mode) }
        }
    }.apply {
        title = "选择${mode.label}对象"
        msg = "[cyan]目标拒绝/超时会取消本次杂交。"
        sendTo(caster, 60_000)
    }
}

private suspend fun requestHybrid(caster: Player, target: Player, mode: MapHybridMode) {
    checkTeamCooldown(caster.team(), mode)?.let {
        caster.sendMessage(it)
        return
    }
    val precheck = when (mode) {
        MapHybridMode.UnitHybrid -> {
            val father = caster.unit()
            val mother = target.unit()
            when {
                caster.dead() -> "死亡状态无法单位杂交。"
                target === caster || target.uuid() == caster.uuid() -> "单位杂交至少需要两名不同玩家。"
                target.dead() -> "目标处于死亡状态，无法单位杂交。"
                father == null -> "单位杂交失败：你没有有效单位。"
                mother == null -> "单位杂交失败：目标没有有效单位。"
                father is BlockUnitc || mother is BlockUnitc -> "单位杂交仅支持双方附身普通单位。"
                else -> hybridPairError(father.type, mother.type)
            }
        }
        MapHybridMode.GeneHybrid -> geneHybridSourceError(caster, target, mode.label)
        MapHybridMode.SelectGeneHybrid -> geneHybridSourceError(caster, target, mode.label)
    }
    if (precheck != null) {
        caster.sendMessage("[red]$precheck")
        return
    }

    Call.sendMessage("[pink]${caster.name}[white]向[pink]${target.name}[white]发送了${mode.label}请求！")
    val accepted = askHybridAccept(caster, target, mode.label)
    if (accepted != true) {
        Call.sendMessage("[yellow]${target.name}[white]拒绝了[pink]${caster.name}[white]的${mode.label}请求！")
        return
    }

    when (mode) {
        MapHybridMode.UnitHybrid -> {
            val cooldownTeam = caster.team()
            checkTeamCooldown(cooldownTeam, mode)?.let {
                caster.sendMessage(it)
                return
            }
            val error = executeUnitHybrid(caster, target)
            if (error != null) caster.sendMessage("[red]$error") else setTeamCooldown(cooldownTeam, mode)
        }
        MapHybridMode.GeneHybrid -> {
            checkTeamCooldown(caster.team(), mode)?.let {
                caster.sendMessage(it)
                return
            }
            try {
                executeRandomGeneHybrid(caster, target)?.let { caster.sendMessage("[red]$it") }
            } catch (t: Throwable) {
                logger.warning("@hybrid 基因杂交CP应用失败: ${t.message}")
                caster.sendMessage("[red]基因杂交CP应用失败，已回滚；请查看服务端日志。")
            }
        }
        MapHybridMode.SelectGeneHybrid -> {
            checkTeamCooldown(caster.team(), mode)?.let {
                caster.sendMessage(it)
                return
            }
            openSelectGeneTemplateMenu(caster, target)
        }
    }
}

private suspend fun openSelectGeneTemplateMenu(caster: Player, target: Player) {
    geneHybridSourceError(caster, target, MapHybridMode.SelectGeneHybrid.label)?.let {
        caster.sendMessage("[red]$it")
        return
    }
    val sourceType = caster.unit()?.type
    val sourceTypeName = sourceType?.name
    val sourceName = sourceType?.localizedName ?: "当前单位"
    val targetName = target.plainName()
    val templates = availableGeneTemplatesFor(caster, target)
    if (templates.isEmpty()) {
        caster.sendMessage("[yellow]当前没有可获取的武器/能力，或你的当前单位对应基因槽位已用完。")
        return
    }
    PagedMenuBuilder(templates) { template ->
        option(geneTemplateOptionText(template)) {
            checkTeamCooldown(caster.team(), MapHybridMode.SelectGeneHybrid)?.let {
                caster.sendMessage(it)
                return@option
            }
            geneTemplatePrecheck(caster, template, MapHybridMode.SelectGeneHybrid.label, sourceTypeName)?.let {
                caster.sendMessage("[red]$it")
                return@option
            }
            if (target.dead() || target.unit() == null) {
                caster.sendMessage("[red]目标已经没有有效单位/炮塔，请重新发起。")
                return@option
            }
            try {
                executeGeneTemplate(caster, target, template, MapHybridMode.SelectGeneHybrid.label, sourceTypeName, killParticipants = true)?.let {
                    caster.sendMessage("[red]$it")
                }
            } catch (t: Throwable) {
                logger.warning("@hybrid 自选基因杂交CP应用失败: ${t.message}")
                caster.sendMessage("[red]自选基因杂交CP应用失败，已回滚；请查看服务端日志。")
            }
        }
    }.apply {
        title = "自选基因杂交：选择基因"
        msg = """
            |[cyan]目标：[white]$targetName
            |[cyan]你的当前单位类型：[white]$sourceName
            |[gold]选择一个武器/能力后应用到该单位类型；成功后击杀双方并进入队伍冷却。
            |[gray]单位基因可多次叠加；同一单位类型最多 1 个炮塔基因；仅当前地图有效。
        """.trimMargin()
        sendTo(caster, 60_000)
    }
}

private fun showGeneHybridStatus(player: Player) {
    if (geneRecords.isEmpty()) {
        player.sendMessage("[yellow]当前地图暂无基因杂交记录。")
        return
    }
    val text = buildString {
        appendLine("[cyan]当前地图基因杂交记录：")
        geneRecords.forEach { (unitName, record) ->
            val type = unitTypeByName(unitName)
            val display = type?.localizedName ?: unitName
            append("[white]$display[gray]：")
            val unitGeneText = record.unitGenes.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "无"
            append("单位基因=[accent]$unitGeneText[]")
            append("，炮塔基因=[accent]${record.turretGene ?: "无"}[]")
            appendLine()
        }
    }
    player.sendMessage(text)
}

private suspend fun handleHybridCommand(player: Player, args: List<String>) {
    if (args.isEmpty()) {
        openHybridModeMenu(player)
        return
    }
    val mode = when (args[0].lowercase()) {
        "unit", "normal", "普通", "普通杂交", "单位", "单位杂交" -> MapHybridMode.UnitHybrid
        "gene", "advanced", "adv", "高级", "高级杂交", "基因", "基因杂交" -> MapHybridMode.GeneHybrid
        "select", "selectgene", "ultimate", "ult", "终极", "终极杂交", "自选", "自选基因", "自选基因杂交" -> MapHybridMode.SelectGeneHybrid
        "status", "状态" -> {
            showGeneHybridStatus(player)
            return
        }
        else -> {
            player.sendMessage("[yellow]用法：/hybrid [unit|gene|select] <玩家名或#id>")
            return
        }
    }
    val targetArg = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
    if (targetArg.isNullOrBlank()) {
        openHybridTargetMenu(player, mode)
        return
    }
    val target = resolveOnlinePlayer(targetArg)
    if (target == null) {
        player.sendMessage("[red]找不到目标玩家：$targetArg")
        return
    }
    requestHybrid(player, target, mode)
}

private fun restoreMapHybridPatches() {
    if (genePatchStrings.isEmpty()) return
    val keep = currentAppliedPatchStrings().filterNot { it in genePatchStrings }
    runCatching {
        applyPatchStringsAndSanitize(keep, "@hybrid 卸载")
    }.onFailure {
        logger.warning("@hybrid 卸载时移除动态基因补丁失败: ${it.message}")
    }
}

onEnable {
    launch(Dispatchers.game) {
        delay(5_000L)
        while (true) {
            Call.sendMessage("[pink][杂交模式][white] 输入[gold]/hybrid[white]打开杂交菜单，与其他人进行杂交，增强你的单位！")
            delay(300_000L)
        }
    }
}

onDisable {
    restoreMapHybridPatches()
    geneRecords.clear()
    genePatchStrings.clear()
    teamCooldowns.clear()
    geneSyncGeneration++
}

command("hybrid", "地图特色杂交菜单") {
    type = CommandType.Client
    aliases = listOf("杂交")
    usage = "[unit|gene|select|status] <玩家名或#id>"
    body {
        val p = player ?: return@body
        launch(Dispatchers.game) {
            handleHybridCommand(p, arg.toList())
        }
    }
}
