@file:Depends("wayzer/user/ext/skills", "技能系统核心")

package wayzer.user.ext

import arc.audio.Sound
import arc.graphics.Color
import arc.math.Interp
import arc.math.Mathf
import arc.math.geom.Vec2
import arc.struct.Seq
import arc.util.pooling.Pools
import arc.util.serialization.Jval
import cf.wayzer.placehold.PlaceHoldApi.with
import coreLibrary.lib.*
import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import coreMindustry.lib.ClientOnly
import coreMindustry.lib.RootCommands
import coreMindustry.lib.broadcast
import coreMindustry.lib.hasPermission
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.content.Items
import mindustry.content.Liquids
import mindustry.content.Planets
import mindustry.content.StatusEffects
import mindustry.content.UnitTypes
import mindustry.content.Weathers
import mindustry.ctype.ContentType
import mindustry.ctype.MappableContent
import mindustry.ctype.UnlockableContent
import mindustry.entities.Damage
import mindustry.entities.Effect
import mindustry.entities.Fires
import mindustry.entities.abilities.Ability
import mindustry.entities.abilities.UnitSpawnAbility
import mindustry.entities.bullet.BulletType
import mindustry.entities.bullet.ContinuousBulletType
import mindustry.entities.bullet.LiquidBulletType
import mindustry.entities.units.StatusEntry
import mindustry.game.Team
import mindustry.gen.BlockUnitc
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Sounds
import mindustry.type.StatusEffect
import mindustry.type.UnitType
import mindustry.type.Weapon
import mindustry.world.Block
import mindustry.world.blocks.defense.Wall
import mindustry.world.blocks.defense.turrets.ContinuousTurret
import mindustry.world.blocks.defense.turrets.ItemTurret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.defense.turrets.PowerTurret
import mindustry.world.blocks.defense.turrets.Turret
import mindustry.world.blocks.environment.StaticWall
import wayzer.lib.PlayerData
import java.lang.reflect.Modifier
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import kotlin.random.Random

private val skillsCore = contextScript<Skills>()
private val contentsTweaker get() = skillsCore.contentsTweaker
private val trustPoint get() = skillsCore.trustPoint
private fun levelOrder(player: Player): Int = skillsCore.levelOrder(player)
private fun requiredOrder(code: String): Int = skillsCore.requiredOrder(code)
private fun levelError(player: Player, required: String): String? = skillsCore.levelError(player, required)
private fun canAffordSkillCost(player: Player, cost: Int): Boolean = skillsCore.canAffordSkillCost(player, cost)
private fun spendSkillCost(player: Player, cost: Int, code: String): Boolean = skillsCore.spendSkillCost(player, cost, code)
private fun unitTypeByName(name: String): UnitType? = skillsCore.unitTypeByName(name)
private fun resolveOnlinePlayer(text: String): Player? = skillsCore.resolveOnlinePlayer(text)
private fun statusEntries(unit: mindustry.gen.Unit): Seq<StatusEntry> = skillsCore.statusEntries(unit)

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

private val hybridCoreUnits = setOf(
    UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma,
    UnitTypes.evoke, UnitTypes.incite, UnitTypes.emanate,
)

private val hybridSpecialNeoplasmUnits = setOf(UnitTypes.renale, UnitTypes.latum)

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

private val hybridRequiredSeniorityCode = "3"
private val unitHybridCost = 6
private val geneHybridCost = 25
private val selectGeneHybridCost = 78
private val hybridCooldown = mutableMapOf<String, Long>()

private enum class AdvancedHybridKind(val label: String) {
    UnitGene("单位"),
    TurretGene("炮塔"),
}

private data class AdvancedHybridTemplate(
    val sourceId: String,
    val displayName: String,
    val kind: AdvancedHybridKind,
    val entries: List<String>,
    val sourceDebug: String? = null,
    val sourceBulletSnapshot: HybridBulletSnapshot? = null,
)

private data class AdvancedHybridRecord(
    var unitGene: String? = null,
    var turretGene: String? = null,
    var unitPatch: String? = null,
    var turretPatch: String? = null,
)

private data class HybridBulletSnapshot(
    val type: String,
    val damage: Float,
    val splashDamage: Float,
    val splashDamageRadius: Float,
    val lightning: Int,
    val pierce: Boolean,
    val pierceBuilding: Boolean,
    val pierceCap: Int,
    val pierceFragCap: Int,
    val collides: Boolean,
    val collidesTiles: Boolean,
    val collidesAir: Boolean,
    val collidesGround: Boolean,
    val despawnHit: Boolean,
    val fragOnHit: Boolean,
    val fragOnDespawn: Boolean,
    val fragOnAbsorb: Boolean,
    val delayFrags: Boolean,
    val fragBullets: Int,
    val frag: HybridBulletSnapshot?,
    val intervalBullets: Int,
    val interval: HybridBulletSnapshot?,
    val spawnBulletCount: Int,
    val spawnBulletTypes: List<String>,
    val spawnUnit: String?,
    val despawnUnit: String?,
)

private val advancedHybridRecords = linkedMapOf<String, AdvancedHybridRecord>()
private var advancedHybridSyncGeneration = 0
private val hybridAutoWorldSync by config.key(
    true,
    "基因/自选基因杂交成功后是否自动给所有客户端下发完整世界数据；会触发客户端重载地图，默认开启，关闭后需玩家显示异常时手动 /sync"
)
private val hybridAutoWorldSyncDelayMillis by config.key(350L, "基因/自选基因杂交自动完整世界同步时每名玩家之间的间隔(ms)")

private fun clearHybridRuntimeState() {
    hybridCooldown.clear()
    advancedHybridRecords.clear()
    // 使仍在分批发送中的旧杂交完整同步协程尽快停止，避免换图/脚本卸载后继续持有旧玩家列表。
    advancedHybridSyncGeneration++
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
            .onFailure { logger.warning("基因杂交炮塔弹药保护：移除异常炮塔失败 ${build.block.name}: ${it.message}") }
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
        logger.warning("基因杂交炮塔弹药保护：清理 $fixed 个无效弹药炮塔${if (reason.isBlank()) "" else " ($reason)"}")
    }
    return fixed
}

private val hybridTurretWeaponRegionOverrides = mapOf(
    // 这些 Erekir 炮塔没有与方块 ID 完全同名的单张可旋转炮塔区域，原版 DrawTurret 由多张 part 组合。
    // 作为单位 Weapon 只能显示一张 region，因此选择最接近“炮塔主体/炮管”的区域做可见标识。
    "disperse" to "disperse-mid",
    "lustre" to "lustre-mid",
    "scathe" to "scathe-mid",
    "smite" to "smite-mid",
    "malign" to "malign-main",
)

private fun advancedTemplate(
    sourceId: String,
    displayName: String,
    kind: AdvancedHybridKind,
    vararg entries: String,
): AdvancedHybridTemplate = AdvancedHybridTemplate(
    sourceId = sourceId,
    displayName = displayName,
    kind = kind,
    entries = entries.map { it.trimIndent() },
)

private fun hybridTurretWeaponRegionName(turret: Turret): String =
    hybridTurretWeaponRegionOverrides[turret.name] ?: turret.name

private fun advancedHybridUnitError(type: UnitType): String? = when {
    type in hybridCoreUnits -> "核心机无法进行基因杂交"
    isHybridMissileType(type) -> "导弹单位无法进行基因杂交"
    else -> null
}

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

private fun hybridFmt(value: Float): String =
    if (finite(value)) ((value * 10f).roundToInt() / 10f).toString() else "?"

private fun hybridBulletSnapshot(bullet: BulletType?, depth: Int = 0): HybridBulletSnapshot? {
    if (bullet == null || depth > 4) return null
    val spawnBullets = runCatching { bullet.spawnBullets.toList() }.getOrDefault(emptyList())
    return HybridBulletSnapshot(
        type = hybridClassName(bullet),
        damage = bullet.damage,
        splashDamage = bullet.splashDamage,
        splashDamageRadius = bullet.splashDamageRadius,
        lightning = bullet.lightning,
        pierce = bullet.pierce,
        pierceBuilding = bullet.pierceBuilding,
        pierceCap = bullet.pierceCap,
        pierceFragCap = bullet.pierceFragCap,
        collides = bullet.collides,
        collidesTiles = bullet.collidesTiles,
        collidesAir = bullet.collidesAir,
        collidesGround = bullet.collidesGround,
        despawnHit = bullet.despawnHit,
        fragOnHit = bullet.fragOnHit,
        fragOnDespawn = bullet.fragOnDespawn,
        fragOnAbsorb = bullet.fragOnAbsorb,
        delayFrags = bullet.delayFrags,
        fragBullets = bullet.fragBullets,
        frag = hybridBulletSnapshot(bullet.fragBullet, depth + 1),
        intervalBullets = bullet.intervalBullets,
        interval = hybridBulletSnapshot(bullet.intervalBullet, depth + 1),
        spawnBulletCount = spawnBullets.size,
        spawnBulletTypes = spawnBullets.take(4).map { hybridClassName(it) },
        spawnUnit = bullet.spawnUnit?.name,
        despawnUnit = bullet.despawnUnit?.name,
    )
}

private fun HybridBulletSnapshot.describe(depth: Int = 0): String = buildString {
    append(type)
    append("(d=").append(hybridFmt(damage))
    if (splashDamage > 0f || splashDamageRadius > 0f) {
        append(", splash=").append(hybridFmt(splashDamage)).append("@").append(hybridFmt(splashDamageRadius))
    }
    if (pierce || pierceBuilding || pierceCap != -1) {
        append(", pierce=").append(pierce).append("/").append(pierceBuilding).append("#").append(pierceCap)
    }
    if (frag != null) {
        append(", frag=").append(fragBullets).append("x").append(frag.type)
        append("[hit=").append(fragOnHit).append(",despawn=").append(fragOnDespawn)
            .append(",absorb=").append(fragOnAbsorb).append(",cap=").append(pierceFragCap).append("]")
        if (depth < 2) append("->").append(frag.describe(depth + 1))
    }
    if (interval != null) append(", interval=").append(intervalBullets).append("x").append(interval.type)
    if (spawnBulletCount > 0) append(", spawnBullets=").append(spawnBulletCount).append(spawnBulletTypes)
    if (spawnUnit != null) append(", spawnUnit=").append(spawnUnit)
    if (despawnUnit != null) append(", despawnUnit=").append(despawnUnit)
    if (lightning > 0) append(", lightning=").append(lightning)
    append(", collide=").append(collides).append("/").append(collidesTiles).append("/").append(collidesAir).append("/").append(collidesGround)
    append(")")
}

private fun hybridCompareBulletSnapshot(source: HybridBulletSnapshot, actual: HybridBulletSnapshot?): List<String> {
    if (actual == null) return listOf("目标武器子弹丢失，源=${source.describe()}")
    val issues = mutableListOf<String>()
    if (source.frag != null) {
        if (actual.frag == null) {
            issues += "fragBullet丢失(${source.frag.type})"
        } else {
            if (actual.fragBullets < source.fragBullets) issues += "fragBullets减少(${source.fragBullets}->${actual.fragBullets})"
            if (source.fragOnHit && !actual.fragOnHit) issues += "fragOnHit丢失"
            if (source.fragOnDespawn && !actual.fragOnDespawn) issues += "fragOnDespawn丢失"
            if (source.fragOnAbsorb && !actual.fragOnAbsorb) issues += "fragOnAbsorb丢失"
            if (source.frag.damage > 0f && actual.frag.damage <= 0f) issues += "frag伤害丢失(${hybridFmt(source.frag.damage)}->${hybridFmt(actual.frag.damage)})"
            if (source.frag.splashDamage > 0f && actual.frag.splashDamage <= 0f) issues += "frag溅射伤害丢失(${hybridFmt(source.frag.splashDamage)}->${hybridFmt(actual.frag.splashDamage)})"
            if (source.frag.splashDamageRadius > 0f && actual.frag.splashDamageRadius <= 0f) issues += "frag溅射范围丢失(${hybridFmt(source.frag.splashDamageRadius)}->${hybridFmt(actual.frag.splashDamageRadius)})"
        }
    }
    if (source.splashDamage > 0f && actual.splashDamage <= 0f) issues += "主弹溅射伤害丢失(${hybridFmt(source.splashDamage)}->${hybridFmt(actual.splashDamage)})"
    if (source.splashDamageRadius > 0f && actual.splashDamageRadius <= 0f) issues += "主弹溅射范围丢失(${hybridFmt(source.splashDamageRadius)}->${hybridFmt(actual.splashDamageRadius)})"
    if (source.interval != null && actual.interval == null) issues += "intervalBullet丢失(${source.interval.type})"
    if (source.spawnBulletCount > 0 && actual.spawnBulletCount < source.spawnBulletCount) issues += "spawnBullets减少(${source.spawnBulletCount}->${actual.spawnBulletCount})"
    if (source.spawnUnit != null && actual.spawnUnit != source.spawnUnit) issues += "spawnUnit变化(${source.spawnUnit}->${actual.spawnUnit})"
    if (source.despawnUnit != null && actual.despawnUnit != source.despawnUnit) issues += "despawnUnit变化(${source.despawnUnit}->${actual.despawnUnit})"
    return issues
}

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
        // Base Effect often stores a runtime renderer lambda; DataPatcher cannot reconstruct it.
        // Explicitly disable unknown effects instead of allowing BulletType defaults (white hit circles) to leak in.
        jsonString("none")
    }
}

private val hybridSkipFieldNames = setOf(
    "region", "heatRegion", "cellRegion", "outlineRegion", "fullIcon", "uiIcon", "editorIcon",
    "mountType", "parts", "drawer", "icons", "stats", "clipSize", "outlineRadius",
    // loop/active sounds require continuous positional maintenance; shoot/charge/hit/break sounds are serialized when they are known Sounds fields.
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

    // 复杂激光/轨道类武器的“阻挡点散射/爆破”依赖这些开关。
    // 它们本来也是 public 字段，这里显式覆盖一次，避免后续跳过默认/初始化差异时丢掉关键行为。
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

private fun turretWeaponEntry(turret: Turret, ammoName: String, bullet: BulletType): String? {
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

private fun advancedTemplatesFromTurret(player: Player): List<AdvancedHybridTemplate> {
    val blockUnit = player.unit() as? BlockUnitc ?: return emptyList()
    val build = blockUnit.tile() as? Turret.TurretBuild ?: return emptyList()
    sanitizeInvalidTurretAmmo(build)
    val turret = build.block as? Turret ?: return emptyList()
    return turretBulletCandidates(turret, build).mapIndexedNotNull { index, (ammoName, bullet) ->
        val entry = turretWeaponEntry(turret, ammoName, bullet) ?: return@mapIndexedNotNull null
        advancedTemplate(
            hybridSafeId("${turret.name}-$index-$ammoName-${hybridClassName(bullet)}"),
            "${turret.localizedName}${ammoName}武器",
            AdvancedHybridKind.TurretGene,
            entry,
            *canHealEntry(bullet.heals())
        ).copy(
            sourceDebug = "turret=${turret.name}, ammo=$ammoName",
            sourceBulletSnapshot = hybridBulletSnapshot(bullet)
        )
    }
}

private fun advancedTemplateFromTurret(player: Player): AdvancedHybridTemplate? =
    advancedTemplatesFromTurret(player).randomOrNull()

private fun advancedTemplatesFromUnit(type: UnitType): List<AdvancedHybridTemplate> {
    val candidates = mutableListOf<AdvancedHybridTemplate>()
    type.weapons.forEachIndexed { index, weapon ->
        val entry = hybridSerializeWeapon(weapon)?.let { "\"weapons.+\":[$it]" } ?: return@forEachIndexed
        val display = if (weapon.name.isNullOrBlank()) "${type.localizedName}武器${index + 1}" else "${type.localizedName}:${weapon.name}"
        candidates += advancedTemplate(
            hybridSafeId("${type.name}-weapon-$index-${weapon.name ?: "weapon"}"),
            display,
            AdvancedHybridKind.UnitGene,
            entry,
            *canHealEntry(weapon.bullet.heals())
        ).copy(
            sourceDebug = "unit=${type.name}, weaponIndex=$index, weapon=${weapon.name ?: "weapon"}",
            sourceBulletSnapshot = hybridBulletSnapshot(weapon.bullet)
        )
    }
    type.abilities.forEachIndexed { index, ability ->
        val entry = hybridSerializableValue(ability)?.let { "\"abilities.+\":[$it]" } ?: return@forEachIndexed
        candidates += advancedTemplate(
            hybridSafeId("${type.name}-ability-$index-${hybridClassName(ability)}"),
            "${type.localizedName}:${hybridClassName(ability)}",
            AdvancedHybridKind.UnitGene,
            entry,
            *canHealEntry(hybridClassName(ability).contains("Repair", ignoreCase = true))
        ).copy(
            sourceDebug = "unit=${type.name}, abilityIndex=$index, ability=${hybridClassName(ability)}"
        )
    }
    return candidates
}

private fun advancedTemplateFromUnit(type: UnitType): AdvancedHybridTemplate? =
    advancedTemplatesFromUnit(type).randomOrNull()

private fun advancedTemplatesFromTarget(target: Player): List<AdvancedHybridTemplate> {
    val targetUnit = target.unit() ?: return emptyList()
    return if (targetUnit is BlockUnitc) advancedTemplatesFromTurret(target) else advancedTemplatesFromUnit(targetUnit.type)
}

private fun hybridCategoryOf(type: UnitType): HybridCategory? =
    hybridCategories.firstOrNull { category -> category.tiers.values.any { type in it } }

private fun hybridTierOf(type: UnitType): Int? =
    hybridCategoryOf(type)?.tiers?.entries?.firstOrNull { type in it.value }?.key

private fun isHybridMissileType(type: UnitType): Boolean {
    val name = type.name
    return name == "missile" || name.endsWith("-missile") || name.contains("missile")
}

private fun hybridUnitError(type: UnitType): String? = when {
    type in hybridCoreUnits -> "核心机无法进行杂交"
    type in hybridSpecialNeoplasmUnits -> null
    isHybridMissileType(type) -> "导弹单位无法进行杂交"
    hybridCategoryOf(type) == null -> "当前单位未进入杂交分类池，暂不支持杂交"
    else -> null
}

private fun hybridPairError(fatherType: UnitType, motherType: UnitType): String? {
    hybridUnitError(fatherType)?.let { return it }
    hybridUnitError(motherType)?.let { return it }
    if ((fatherType in hybridSpecialNeoplasmUnits || motherType in hybridSpecialNeoplasmUnits) && fatherType != motherType) {
        return "renale/latum 是特殊虫族，只能与同种单位杂交"
    }
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
        .filter { it !in hybridCoreUnits && it !in hybridSpecialNeoplasmUnits && !isHybridMissileType(it) }
        .randomOrNull()

private fun chooseHybridChildType(fatherType: UnitType, motherType: UnitType): HybridChildChoice {
    if (fatherType in hybridSpecialNeoplasmUnits || motherType in hybridSpecialNeoplasmUnits) {
        if (fatherType != motherType) return HybridChildChoice(null, "renale/latum 是特殊虫族，只能与同种单位杂交。")
        return HybridChildChoice(fatherType, detail = "特殊虫族同种遗传")
    }

    val fatherCategory = hybridCategoryOf(fatherType) ?: return HybridChildChoice(null, "父单位未进入杂交分类池。")
    val motherCategory = hybridCategoryOf(motherType) ?: return HybridChildChoice(null, "母单位未进入杂交分类池。")
    val inheritFather = if (fatherCategory == motherCategory) Random.nextBoolean() else Random.nextInt(100) < 60
    val chosenCategory = if (inheritFather) fatherCategory else motherCategory
    val baseType = if (inheritFather) fatherType else motherType
    val baseTier = hybridTierOf(baseType) ?: 1
    val side = if (inheritFather) "父方" else "母方"

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

private fun applyStackedStatus(unit: mindustry.gen.Unit, effect: StatusEffect, duration: Float, stacks: Int = 1): Int {
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

private fun snapshotHybridStatuses(unit: mindustry.gen.Unit): List<HybridStatusSnapshot> {
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

private fun applyHybridSnapshots(unit: mindustry.gen.Unit, snapshots: List<HybridStatusSnapshot>): List<StatusEffect> {
    val applied = mutableListOf<StatusEffect>()
    snapshots.forEach { snapshot ->
        repeat(applyStackedStatus(unit, snapshot.effect, snapshot.duration, snapshot.stacks)) {
            applied += snapshot.effect
        }
    }
    return applied
}

private fun applyHybridQuality(unit: mindustry.gen.Unit, quality: HybridQuality): List<StatusEffect> {
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

private fun applyHybridSpirit(unit: mindustry.gen.Unit): List<StatusEffect> {
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

private fun checkHybridCooldown(player: Player): String? {
    val until = (hybridCooldown[player.uuid()] ?: 0L) + 60_000L
    val now = System.currentTimeMillis()
    if (until <= now) return null
    return "[red]杂交技能冷却中，还剩 ${(until - now + 999L) / 1000L} 秒"
}

private fun setHybridCooldown(player: Player) {
    hybridCooldown[player.uuid()] = System.currentTimeMillis()
}

private suspend fun openHybridModeMenu(player: Player) {
    MenuBuilder<Unit>("杂交系统") {
        msg = """
            |[cyan]请选择杂交方式。
            |[green]单位杂交[gray]：${unitHybridCost} MDC，生成单个子单位，不修改CP。
            |[accent]基因杂交[gray]：成功后消耗 ${geneHybridCost} MDC，20%概率失败不扣费；仅当前地图有效。
            |[gold]自选基因杂交[gray]：成功后消耗 ${selectGeneHybridCost} MDC，目标同意后手动选择武器/能力。
            |[cyan]基因清洗[gray]：成功后消耗 10 MDC，清理当前附身单位类型的全部基因。
        """.trimMargin()
        option("[green]单位杂交\n[gray]选择目标玩家，目标同意后生成子单位") { openHybridTargetMenu(player, advanced = false) }
        option("[accent]基因杂交\n[gray]随机抽取目标当前武器/能力，仅新生成单位生效") { openHybridTargetMenu(player, advanced = true) }
        newRow()
        option("[gold]自选基因杂交\n[gray]目标同意后列出可获取武器/能力，手动选择") { openUltimateHybridTargetMenu(player) }
        newRow()
        option("[cyan]基因清洗\n[gray]清理当前附身单位类型的全部基因，消耗10 MDC") {
            RootCommands.handleInput("/skill hybrid clean", player, "/")
        }
        newRow()
        option("[cyan]查看基因杂交状态") { showAdvancedHybridStatus(player) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openHybridTargetMenu(caster: Player, advanced: Boolean) {
    val targets = Groups.player.toList().filter { it !== caster }.sortedBy { it.plainName() }
    if (targets.isEmpty()) {
        caster.sendMessage("[yellow]当前没有可杂交的在线目标。")
        return
    }
    val mode = if (advanced) "advanced" else "normal"
    val label = if (advanced) "基因杂交" else "单位杂交"
    PagedMenuBuilder(targets) { target ->
        val data = PlayerData[target]
        option("${target.name} [gray](${data.shortId})\n[gray]点击后发送${label}请求") {
            RootCommands.handleInput("/skill hybrid $mode ${data.shortId}", caster, "/")
        }
    }.apply {
        title = "选择${label}对象"
        msg = "[cyan]选择一个在线玩家发送${label}请求；目标拒绝/超时会取消本次杂交。"
        sendTo(caster, 60_000)
    }
}

private suspend fun openUltimateHybridTargetMenu(caster: Player) {
    val targets = Groups.player.toList().filter { it !== caster }.sortedBy { it.plainName() }
    if (targets.isEmpty()) {
        caster.sendMessage("[yellow]当前没有可自选基因杂交的在线目标。")
        return
    }
    PagedMenuBuilder(targets) { target ->
        val data = PlayerData[target]
        option("${target.name} [gray](${data.shortId})\n[gray]点击后发送自选基因杂交请求") {
            RootCommands.handleInput("/skill hybrid ultimate ${data.shortId}", caster, "/")
        }
    }.apply {
        title = "选择自选基因杂交对象"
        msg = "[cyan]目标同意后，会向你列出目标当前可获取的武器/能力。"
        sendTo(caster, 60_000)
    }
}

private suspend fun askHybridAccept(caster: Player, target: Player, label: String): Boolean? {
    var accepted: Boolean? = null
    MenuBuilder<Unit>("${label}请求") {
        msg = "[pink]\"${caster.plainName()}\"[white]想要和你进行${label}，是否接受？"
        option("接受") { accepted = true }
        option("拒绝") { accepted = false }
    }.sendTo(target, 15_000)
    return accepted
}

private suspend fun askHybridAccept(caster: Player, target: Player, advanced: Boolean): Boolean? =
    askHybridAccept(caster, target, if (advanced) "基因杂交" else "单位杂交")

private fun showAdvancedHybridStatus(player: Player) {
    if (advancedHybridRecords.isEmpty()) {
        player.sendMessage("[yellow]当前地图暂无基因杂交记录。")
        return
    }
    val text = buildString {
        appendLine("[cyan]当前地图基因杂交记录：")
        advancedHybridRecords.forEach { (unitName, record) ->
            val type = unitTypeByName(unitName)
            val display = type?.localizedName ?: unitName
            append("[white]$display[gray]：")
            append("单位基因=[accent]${record.unitGene ?: "无"}[]")
            append("，炮塔基因=[accent]${record.turretGene ?: "无"}[]")
            appendLine()
        }
    }
    player.sendMessage(text)
}

private fun normalHybridSourceError(player: Player, target: Player): String? {
    if (player.dead()) return "死亡状态无法杂交。"
    if (target === player || PlayerData[target].id == PlayerData[player].id) return "杂交至少需要两名不同玩家。"
    if (target.dead()) return "目标处于死亡状态，无法杂交。"
    val father = player.unit() ?: return "杂交失败：你没有有效单位。"
    val mother = target.unit() ?: return "杂交失败：目标没有有效单位。"
    if (father is BlockUnitc || mother is BlockUnitc) return "单位杂交仅支持双方附身普通单位。"
    return hybridPairError(father.type, mother.type)
}

private fun controlledTurretTemplate(player: Player): AdvancedHybridTemplate? {
    return advancedTemplateFromTurret(player)
}

private fun advancedHybridSourceError(player: Player, target: Player, label: String = "基因杂交"): String? {
    if (player.dead()) return "死亡状态无法${label}。"
    if (target === player || PlayerData[target].id == PlayerData[player].id) return "${label}至少需要两名不同玩家。"
    if (target.dead()) return "目标处于死亡状态，无法${label}。"
    val source = player.unit() ?: return "${label}失败：你没有有效单位。"
    if (source is BlockUnitc) return "炮塔/建筑不能作为${label}发起者。"
    advancedHybridUnitError(source.type)?.let { return "发起者单位不支持${label}：$it" }

    val targetUnit = target.unit() ?: return "${label}失败：目标没有有效单位。"
    if (targetUnit is BlockUnitc) {
        if (advancedTemplatesFromTurret(target).isEmpty()) return "目标控制的炮塔暂未找到可序列化弹药/武器。"
    } else {
        advancedHybridUnitError(targetUnit.type)?.let { return "目标单位不支持${label}：$it" }
        if (advancedTemplatesFromUnit(targetUnit.type).isEmpty()) return "目标单位暂未找到可序列化武器/能力。"
    }

    val record = advancedHybridRecords[source.type.name]
    if (targetUnit is BlockUnitc) {
        if (record?.turretGene != null) return "${source.type.localizedName} 已经进行过炮塔基因杂交。"
    } else {
        if (record?.unitGene != null) return "${source.type.localizedName} 已经进行过单位基因杂交。"
    }
    return null
}

private fun sourceCanAcceptAdvancedTemplate(sourceType: UnitType, template: AdvancedHybridTemplate): Boolean {
    val record = advancedHybridRecords[sourceType.name]
    return when (template.kind) {
        AdvancedHybridKind.TurretGene -> record?.turretGene == null
        AdvancedHybridKind.UnitGene -> record?.unitGene == null
    }
}

private fun ultimateHybridTemplatesFor(caster: Player, target: Player): List<AdvancedHybridTemplate> {
    val sourceType = caster.unit()?.type ?: return emptyList()
    return advancedTemplatesFromTarget(target).filter { sourceCanAcceptAdvancedTemplate(sourceType, it) }
}

private fun advancedTemplateKindName(template: AdvancedHybridTemplate): String =
    if (template.kind == AdvancedHybridKind.TurretGene) "炮塔武器" else if (template.entries.any { it.contains("\"abilities.+\"") }) "单位能力" else "单位武器"

private fun advancedTemplateOptionText(template: AdvancedHybridTemplate): String {
    val kind = advancedTemplateKindName(template)
    val color = when (kind) {
        "炮塔武器" -> "[accent]"
        "单位能力" -> "[pink]"
        else -> "[cyan]"
    }
    return "$color$kind[]：[white]${template.displayName}\n[gray]成功后消耗 ${selectGeneHybridCost} MDC，并添加给发起请求时的单位类型"
}

private fun advancedHybridTemplatePrecheck(caster: Player, template: AdvancedHybridTemplate, label: String, expectedSourceName: String? = null): String? {
    if (caster.dead()) return "死亡状态无法${label}。"
    val sourceUnit = caster.unit() ?: return "${label}失败：你没有有效单位。"
    if (sourceUnit is BlockUnitc) return "炮塔/建筑不能作为${label}发起者。"
    val sourceType = sourceUnit.type
    if (expectedSourceName != null && sourceType.name != expectedSourceName) {
        val expectedDisplay = unitTypeByName(expectedSourceName)?.localizedName ?: expectedSourceName
        return "${label}菜单已绑定到 $expectedDisplay；你当前单位已变化，请重新发起。"
    }
    advancedHybridUnitError(sourceType)?.let { return "发起者单位不支持${label}：$it" }
    if (!sourceCanAcceptAdvancedTemplate(sourceType, template)) {
        return if (template.kind == AdvancedHybridKind.TurretGene) {
            "${sourceType.localizedName} 已经进行过炮塔基因杂交。"
        } else {
            "${sourceType.localizedName} 已经进行过单位基因杂交。"
        }
    }
    return null
}

private suspend fun openUltimateHybridTemplateMenu(caster: Player, target: Player) {
    advancedHybridSourceError(caster, target, "自选基因杂交")?.let {
        caster.sendMessage("[red]$it")
        return
    }
    val templates = ultimateHybridTemplatesFor(caster, target)
    if (templates.isEmpty()) {
        caster.sendMessage("[yellow]当前没有可获取的武器/能力，或你的当前单位对应基因槽位已用完。")
        return
    }
    val sourceType = caster.unit()?.type
    val sourceTypeName = sourceType?.name
    val sourceName = sourceType?.localizedName ?: "当前单位"
    val targetName = target.plainName()
    PagedMenuBuilder(templates) { template ->
        option(advancedTemplateOptionText(template)) {
            if (Vars.state.rules.tags.getBool("@noSkills")) {
                caster.sendMessage("[red]当前地图禁用技能")
                return@option
            }
            advancedHybridTemplatePrecheck(caster, template, "自选基因杂交", sourceTypeName)?.let {
                caster.sendMessage("[red]$it")
                return@option
            }
            if (!canAffordSkillCost(caster, selectGeneHybridCost)) {
                caster.sendMessage("[red]MDC不足：自选基因杂交需要 ${selectGeneHybridCost} MDC")
                return@option
            }
            try {
                executeAdvancedHybridTemplate(caster, template, "自选基因杂交", sourceTypeName)?.let { error ->
                    caster.sendMessage("[red]$error，本次未消耗 MDC。")
                    return@option
                }
            } catch (t: Throwable) {
                logger.warning("自选基因杂交CP应用失败: ${t.message}")
                caster.sendMessage("[red]自选基因杂交CP应用失败，本次未消耗 MDC。请查看服务端日志。")
                return@option
            }
            if (!spendSkillCost(caster, selectGeneHybridCost, "ultimateHybrid")) {
                caster.sendMessage("[yellow]自选基因杂交已成功，但扣除 ${selectGeneHybridCost} MDC 失败，请联系管理员检查。")
            }
        }
    }.apply {
        title = "自选基因杂交：选择基因"
        msg = """
            |[cyan]目标：[white]$targetName
            |[cyan]你的当前单位类型：[white]$sourceName
            |[gold]选择一个武器/能力；应用成功后消耗 ${selectGeneHybridCost} MDC。
            |[gray]仍沿用基因杂交限制：同一单位类型最多 1 个单位基因 + 1 个炮塔基因。
        """.trimMargin()
        sendTo(caster, 60_000)
    }
}

private fun buildAdvancedHybridPatch(sourceType: UnitType, template: AdvancedHybridTemplate): String {
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

private fun restoreTweakerPatchList(previous: List<String>) {
    with(contentsTweaker) {
        contentPatches.clear()
        previous.forEach { contentPatches.add(it) }
    }
}

private fun applyPatchStringsAndSanitize(patches: List<String>, reason: String) {
    with(contentsTweaker) { applyPatchStrings(patches) }
    sanitizeInvalidTurretAmmo(reason)
}

private fun removeHybridRuntimePatches(patches: Set<String>) {
    if (patches.isEmpty()) return
    with(contentsTweaker) {
        val keep = contentPatches.toList().filterNot { it in patches }
        contentPatches.clear()
        keep.forEach { contentPatches.add(it) }
    }
}

private fun cleanGenesForCurrentUnit(player: Player): Pair<Boolean, String> {
    val unit = player.unit() ?: return false to "你没有有效单位。"
    val typeName = unit.type.name
    val typeDisplay = unit.type.localizedName
    val record = advancedHybridRecords[typeName] ?: return false to "${typeDisplay} 当前没有可清洗的基因。"
    val patches = setOfNotNull(record.unitPatch, record.turretPatch)
    if (patches.isEmpty() && record.unitGene == null && record.turretGene == null) {
        advancedHybridRecords.remove(typeName)
        return false to "${typeDisplay} 当前没有可清洗的基因。"
    }

    val before = currentAppliedPatchStrings()
    val remaining = before.filterNot { it in patches }
    removeHybridRuntimePatches(patches)
    applyPatchStringsAndSanitize(remaining, "基因清洗")
    advancedHybridRecords.remove(typeName)
    rebuildAllAdvancedHybridUnits()
    rebuildHybridRuntimeUnits(listOf(unit.type))
    syncHybridContentToClients()

    val details = listOfNotNull(
        record.unitGene?.let { "单位基因：$it" },
        record.turretGene?.let { "炮塔基因：$it" }
    ).joinToString("；").ifBlank { "已清除记录" }
    return true to "已清洗 ${typeDisplay} 的所有基因（$details）。新生成单位将恢复当前地图原始数据。"
}

private fun restoreHybridPatches(previousActivePatches: List<String>, previousTweakerPatches: List<String>) {
    restoreTweakerPatchList(previousTweakerPatches)
    applyPatchStringsAndSanitize(previousActivePatches, "基因杂交回滚")
}

private fun restoreHybridPatchesLegacy(previousActivePatches: List<String>, previousTweakerPatches: List<String>) {
    restoreTweakerPatchList(previousTweakerPatches)
    with(contentsTweaker) { applyPatchStrings(previousActivePatches) }
    sanitizeInvalidTurretAmmo("基因杂交旧式回滚")
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
            logger.warning("基因杂交刷新现有单位失败: ${type.name} ${it.message}")
        }
    }
}

private fun rebuildAllAdvancedHybridUnits() {
    rebuildHybridRuntimeUnits(advancedHybridRecords.keys.mapNotNull(::unitTypeByName))
}

private fun sendWorldDataCompat(player: Player) {
    val con = player.con ?: return
    Call.worldDataBegin(con)
    val sendWorldAndAssets = Vars.netServer.javaClass.methods.firstOrNull { method ->
        method.name == "sendWorldAndAssets" && method.parameterTypes.size == 1
    }
    if (sendWorldAndAssets != null) {
        sendWorldAndAssets.invoke(Vars.netServer, player)
    } else {
        Vars.netServer.sendWorldData(player)
    }
}

private fun syncHybridContentToClients() {
    if (!hybridAutoWorldSync) {
        logger.info("基因杂交已跳过自动完整世界同步；客户端如未显示新基因，可由玩家手动 /sync 或重进。")
        return
    }
    val generation = ++advancedHybridSyncGeneration
    val players = Groups.player.toList().filter { it.con != null && !it.isLocal }
    launch(Dispatchers.game) {
        players.forEachIndexed { index, player ->
            if (generation != advancedHybridSyncGeneration) return@launch
            if (index > 0) delay(hybridAutoWorldSyncDelayMillis.coerceAtLeast(0L))
            if (generation != advancedHybridSyncGeneration) return@launch
            runCatching {
                if (player.con != null) {
                    sendWorldDataCompat(player)
                }
            }.onFailure {
                logger.warning("基因杂交同步玩家 ${player.plainName()} 失败: ${it.message}")
            }
        }
    }
}

private fun applyAdvancedHybridPatch(
    patchName: String,
    patch: String,
    affectedUnitNames: Collection<String>,
    verifyApplied: () -> String? = { null },
) {
    val readPatch = Jval.read(patch).toString(Jval.Jformat.plain)
    val previousActivePatches = currentAppliedPatchStrings()
    val previousTweakerPatches = with(contentsTweaker) { contentPatches.toList() }
    try {
        with(contentsTweaker) {
            contentPatches.add(readPatch)
        }
        applyPatchStringsAndSanitize(previousActivePatches + readPatch, "基因杂交应用")
        val patchSet = with(contentsTweaker) { patchInfoFor(readPatch) }
        if (patchSet == null || patchSet.error) {
            val warnings = patchSet?.warnings?.joinToString("; ").orEmpty().ifBlank { "补丁未进入当前patcher或解析失败" }
            throw IllegalStateException("基因杂交CP解析失败：$patchName：$warnings")
        }
        verifyApplied()?.let { issue ->
            throw IllegalStateException("基因杂交CP未实际生效：$patchName：$issue")
        }
        rebuildAllAdvancedHybridUnits()
        rebuildHybridRuntimeUnits(affectedUnitNames.mapNotNull(::unitTypeByName))
        syncHybridContentToClients()
    } catch (t: Throwable) {
        runCatching { restoreHybridPatches(previousActivePatches, previousTweakerPatches) }
            .recoverCatching {
                logger.warning("基因杂交CP精确回滚失败，尝试旧式回滚: ${it.message}")
                restoreHybridPatchesLegacy(previousActivePatches, previousTweakerPatches)
            }
            .onFailure { logger.warning("基因杂交CP回滚失败: ${it.message}") }
        rebuildAllAdvancedHybridUnits()
        rebuildHybridRuntimeUnits(affectedUnitNames.mapNotNull(::unitTypeByName))
        syncHybridContentToClients()
        throw t
    }
}

private fun refundSkillCost(player: Player, amount: Int, reason: String) {
    with(trustPoint) { addTrustPoints(PlayerData[player].id, amount, reason) }
}

private fun advancedHybridTemplateVerifyIssue(
    template: AdvancedHybridTemplate,
    currentType: UnitType,
    beforeWeapons: Int,
    beforeAbilities: Int,
    expectsWeapon: Boolean,
    expectsAbility: Boolean,
): String? {
    if (expectsWeapon && currentType.weapons.size <= beforeWeapons) return "武器数量未增加"
    if (expectsAbility && currentType.abilities.size <= beforeAbilities) return "能力数量未增加"

    val sourceSnapshot = template.sourceBulletSnapshot ?: return null
    if (!expectsWeapon) return null
    val newWeapon = if (currentType.weapons.size > beforeWeapons) currentType.weapons[beforeWeapons] else return "新增武器不存在"
    val actualSnapshot = hybridBulletSnapshot(newWeapon.bullet)
    val issues = hybridCompareBulletSnapshot(sourceSnapshot, actualSnapshot)
    val sourceText = sourceSnapshot.describe()
    val actualText = actualSnapshot?.describe() ?: "null"
    val prefix = "${template.sourceDebug ?: template.displayName} -> ${currentType.name}.${newWeapon.name ?: "weapon"}"
    if (issues.isEmpty()) {
        logger.info("基因杂交武器诊断[$prefix] 源=$sourceText | 结果=$actualText")
        return null
    }
    val issueText = issues.joinToString("；")
    logger.warning("基因杂交武器诊断[$prefix] 关键字段不一致：$issueText | 源=$sourceText | 结果=$actualText")
    return issueText
}

private fun executeAdvancedHybridTemplate(caster: Player, template: AdvancedHybridTemplate, label: String = "基因杂交", expectedSourceName: String? = null): String? {
    advancedHybridTemplatePrecheck(caster, template, label, expectedSourceName)?.let { return it }
    val sourceUnit = caster.unit() ?: return "${label}失败：你没有有效单位。"
    val sourceType = sourceUnit.type

    val record = advancedHybridRecords.getOrPut(sourceType.name) { AdvancedHybridRecord() }
    if (template.kind == AdvancedHybridKind.TurretGene && record.turretGene != null) {
        return "${sourceType.localizedName} 已经进行过炮塔基因杂交。"
    }
    if (template.kind == AdvancedHybridKind.UnitGene && record.unitGene != null) {
        return "${sourceType.localizedName} 已经进行过单位基因杂交。"
    }

    val patchName = "$" + "hybrid-${sourceType.name}-${template.kind.name.lowercase()}-${template.sourceId}"
    val patch = buildAdvancedHybridPatch(sourceType, template)
    val sourceTypeName = sourceType.name
    val sourceDisplayName = sourceType.localizedName
    val beforeType = unitTypeByName(sourceTypeName) ?: sourceType
    val beforeWeapons = beforeType.weapons.size
    val beforeAbilities = beforeType.abilities.size
    val expectsWeapon = template.entries.any { it.contains("\"weapons.+\"") }
    val expectsAbility = template.entries.any { it.contains("\"abilities.+\"") }
    applyAdvancedHybridPatch(patchName, patch, affectedUnitNames = listOf(sourceTypeName)) {
        val currentType = unitTypeByName(sourceTypeName) ?: return@applyAdvancedHybridPatch "源单位类型在CP应用后不存在"
        advancedHybridTemplateVerifyIssue(template, currentType, beforeWeapons, beforeAbilities, expectsWeapon, expectsAbility)
    }
    if (template.kind == AdvancedHybridKind.TurretGene) {
        record.turretGene = template.displayName
        record.turretPatch = Jval.read(patch).toString(Jval.Jformat.plain)
    } else {
        record.unitGene = template.displayName
        record.unitPatch = Jval.read(patch).toString(Jval.Jformat.plain)
    }

    broadcast(
        "[accent]${label}已应用：新生成的 ${sourceDisplayName} 将获得 ${template.displayName} 基因。若客户端显示异常，请手动执行 /sync 或重进服务器。".with(),
        quite = true
    )
    return null
}

private fun executeAdvancedHybrid(caster: Player, target: Player): String? {
    advancedHybridSourceError(caster, target)?.let { return it }
    val targetUnit = target.unit() ?: return "基因杂交失败：目标没有有效单位。"
    val template = if (targetUnit is BlockUnitc) {
        controlledTurretTemplate(target) ?: return "目标控制的炮塔暂未找到可序列化弹药/武器。"
    } else {
        advancedTemplateFromUnit(targetUnit.type) ?: return "目标单位暂未找到可序列化武器/能力。"
    }
    return executeAdvancedHybridTemplate(caster, template, "基因杂交")
}

private fun executeHybrid(caster: Player, target: Player): String? {
    val father = caster.unit() ?: return "杂交失败：你没有有效单位。"
    val mother = target.unit() ?: return "杂交失败：目标没有有效单位。"
    if (father.dead || mother.dead || !father.isValid || !mother.isValid) return "杂交失败：双方都需要拥有存活单位。"
    hybridPairError(father.type, mother.type)?.let { return "杂交失败：$it" }

    val fatherBuffs = snapshotHybridStatuses(father)
    val motherBuffs = snapshotHybridStatuses(mother)
    val childChoice = chooseHybridChildType(father.type, mother.type).also {
        if (it.error != null) return "杂交失败：${it.error}"
    }
    var childType = childChoice.type ?: return "杂交失败：未能生成子单位类型。"
    val details = mutableListOf<String>()
    childChoice.detail.takeIf { it.isNotBlank() }?.let { details += it }

    var geneticMutation = false
    var spirited = false
    val forceFixedNeoplasm = father.type in hybridSpecialNeoplasmUnits || mother.type in hybridSpecialNeoplasmUnits
    if (!forceFixedNeoplasm && Random.nextInt(100) < 20) {
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
    if (fatherDeadByHybrid) father.kill()
    if (motherDeadByHybrid) mother.kill()
    details += "父方死亡判定${fatherDeathChance}%：${if (fatherDeadByHybrid) "死亡" else "幸存"}"
    details += "母方死亡判定${motherDeathChance}%：${if (motherDeadByHybrid) "死亡" else "幸存"}"

    val manner = if (spirited) "[accent]抖擞精神地[white]" else ""
    val mutationPrefix = if (geneticMutation) "[accent]基因突变的[white]" else ""
    val qualityText = quality?.coloredName ?: "[white]普通的"
    val unitName = childType.localizedName
    val buffText = hybridBuffText(shownBuffs)
    broadcast(
        "[pink]{caster.name}[white]和[pink]{target.name}[white]${manner}单位杂交出来一个：${mutationPrefix}${qualityText}[white]$unitName[white]（$buffText）！\n[gray]详情：${details.joinToString("；")}"
            .with("caster" to caster, "target" to target),
        quite = true
    )
    return null
}


listen<EventType.ResetEvent> { clearHybridRuntimeState() }

listen<EventType.WorldLoadEvent> { clearHybridRuntimeState() }

onEnable {
    SkillMainMenuRegistry.register(
        SkillMainMenuEntry(
            "hybrid",
            "[pink]杂交菜单[]\n[gray]资历3级解锁：单位/基因/自选基因杂交独立入口",
            visible = { levelOrder(it) >= requiredOrder(hybridRequiredSeniorityCode) },
            action = { openHybridModeMenu(it) },
        )
    )
}

onDisable {
    clearHybridRuntimeState()
    SkillMainMenuRegistry.unregister("hybrid")
}

command("hybrid", "3级技能：杂交系统".with(), commands = SkillCommands) {
    usage = "[unit|gene|selectgene|clean] [目标3位ID/uuid/名字]"
    aliases = listOf("杂交", "breed")
    attr(ClientOnly); attr(SkillNoPvp)
    skillBody {
        levelError(player, hybridRequiredSeniorityCode)?.let { returnReply("[red]$it".with()) }
        if (arg.isEmpty()) {
            openHybridModeMenu(player)
            return@skillBody
        }
        val modeArg = arg.first().lowercase()
        val clean = modeArg in setOf("clean", "clear", "wash", "purge", "清洗", "基因清洗", "清理基因")
        if (clean) {
            if (Vars.state.rules.tags.getBool("@noSkills")) returnReply("[red]当前地图禁用技能".with())
            if (!canAffordSkillCost(player, 10)) returnReply("[red]MDC不足：基因清洗需要 10 MDC".with())
            val result = try {
                cleanGenesForCurrentUnit(player)
            } catch (t: Throwable) {
                logger.warning("基因清洗失败: ${t.message}")
                returnReply("[red]基因清洗失败，本次未消耗 MDC，请查看服务端日志。".with())
            }
            val (success, message) = result
            if (success) {
                if (!spendSkillCost(player, 10, "hybridClean")) {
                    player.sendMessage("[yellow]基因清洗已成功，但扣除 10 MDC 失败，请联系管理员检查。")
                }
                player.sendMessage("[green]$message")
                broadcast("[cyan]{player.name}[white]进行了基因清洗。".with("player" to player), quite = true)
            } else {
                returnReply("[yellow]$message".with())
            }
            return@skillBody
        }
        val advanced = modeArg in setOf("gene", "advanced", "adv", "高级", "高级杂交", "基因", "基因杂交")
        val ultimate = modeArg in setOf("selectgene", "select", "ultimate", "ult", "终极", "终极杂交", "自选基因", "自选基因杂交")
        val explicitNormal = modeArg in setOf("unit", "normal", "普通", "普通杂交", "单位", "单位杂交")
        val targetText = if (advanced || ultimate || explicitNormal) arg.drop(1).joinToString(" ") else arg.joinToString(" ")
        if (targetText.isBlank()) {
            if (ultimate) openUltimateHybridTargetMenu(player) else openHybridTargetMenu(player, advanced = advanced)
            return@skillBody
        }
        if (Vars.state.rules.tags.getBool("@noSkills")) returnReply("[red]当前地图禁用技能".with())
        val target = resolveOnlinePlayer(targetText)
            ?: returnReply("[red]未找到在线目标，请使用目标3位ID/uuid/名字，或直接在菜单中选择。".with())

        val precheckError = when {
            ultimate -> advancedHybridSourceError(player, target, "自选基因杂交")
            advanced -> advancedHybridSourceError(player, target)
            else -> normalHybridSourceError(player, target)
        }
        precheckError?.let { returnReply("[red]$it".with()) }

        val label = when {
            ultimate -> "自选基因杂交"
            advanced -> "基因杂交"
            else -> "单位杂交"
        }
        broadcast("[pink]{caster.name}[white]向[pink]{target.name}[white]发送了${label}请求！".with("caster" to player, "target" to target), quite = true)
        val accepted = askHybridAccept(player, target, label)
        if (accepted != true) {
            broadcast("[pink]{target.name}[white]拒绝了[pink]{caster.name}[white]的${label}请求，本次杂交已取消。".with("caster" to player, "target" to target), quite = true)
            return@skillBody
        }

        if (ultimate) {
            advancedHybridSourceError(player, target, "自选基因杂交")?.let { returnReply("[red]$it".with()) }
            openUltimateHybridTemplateMenu(player, target)
        } else if (advanced) {
            advancedHybridSourceError(player, target)?.let { returnReply("[red]$it".with()) }
            if (!canAffordSkillCost(player, geneHybridCost)) returnReply("[red]MDC不足：基因杂交需要 ${geneHybridCost} MDC".with())
            if (Random.nextInt(100) < 20) {
                broadcast("[accent]{caster.name}[white]发起的基因杂交失败了，本次未消耗 MDC。".with("caster" to player), quite = true)
                return@skillBody
            }
            val error = try {
                executeAdvancedHybrid(player, target)
            } catch (t: Throwable) {
                logger.warning("基因杂交CP应用失败: ${t.message}")
                returnReply("[red]基因杂交CP应用失败，本次未消耗 MDC。请查看服务端日志。".with())
            }
            error?.let {
                returnReply("[red]$it，本次未消耗 MDC。".with())
            }
            if (!spendSkillCost(player, geneHybridCost, "advancedHybrid")) {
                player.sendMessage("[yellow]基因杂交已成功，但扣除 ${geneHybridCost} MDC 失败，请联系管理员检查。")
            }
        } else {
            normalHybridSourceError(player, target)?.let { returnReply("[red]$it".with()) }
            val freeCost = SkillCostManager.freeCostSponsor() != null
            if (!spendSkillCost(player, unitHybridCost, "hybrid")) returnReply("[red]MDC不足：单位杂交需要 ${unitHybridCost} MDC".with())
            executeHybrid(player, target)?.let { error ->
                if (!freeCost) refundSkillCost(player, unitHybridCost, "Skill:hybridRefund")
                val suffix = if (freeCost) "本次未扣 MDC。" else "已回补 ${unitHybridCost} MDC。"
                returnReply("[red]$error，$suffix".with())
            }
        }
    }
}
