@file:Depends("coreMindustry/menu", "技能分类菜单")
@file:Depends("wayzer/user/seniorityLevel", "资历等级")
@file:Depends("wayzer/user/trustLevel", "信任等级")
@file:Depends("wayzer/user/trustPoint", "MDC")
@file:Depends("wayzer/user/playerTitle", "称号")
@file:Depends("wayzer/map/funRuleModes", "临时玩法规则工具")
@file:Depends("coreMindustry/contentsTweaker", "临时内容补丁")
@file:Depends("coreMindustry/utilTextInput", "神权菜单输入")

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
import coreMindustry.MenuBuilder
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
import mindustry.entities.Fires
import mindustry.entities.abilities.Ability
import mindustry.entities.abilities.UnitSpawnAbility
import mindustry.entities.units.StatusEntry
import mindustry.entities.Damage
import mindustry.entities.Effect
import mindustry.entities.bullet.BulletType
import mindustry.entities.bullet.ContinuousBulletType
import mindustry.entities.bullet.LiquidBulletType
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
import mindustry.world.blocks.defense.turrets.PowerTurret
import mindustry.world.blocks.defense.turrets.Turret
import mindustry.world.blocks.defense.turrets.LiquidTurret
import mindustry.world.blocks.environment.StaticWall
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import java.lang.reflect.Modifier
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.random.Random

val seniorityLevel = contextScript<wayzer.user.SeniorityLevel>()
val trustLevel = contextScript<wayzer.user.TrustLevel>()
val trustPoint = contextScript<wayzer.user.TrustPoint>()
val playerTitle = contextScript<wayzer.user.PlayerTitle>()
val funRules = contextScript<wayzer.map.FunRuleModes>()
val contentsTweaker = contextScript<coreMindustry.ContentsTweaker>()
val textInput = contextScript<coreMindustry.UtilTextInput>()

fun levelOrder(player: Player): Int {
    val uid = PlayerData[player].id
    return with(seniorityLevel) { getSeniorityLevelOrder(uid, player) }
}

fun requiredOrder(code: String): Int = with(seniorityLevel) { seniorityLevelOrder(code) }
fun skillAdmin(player: Player): Boolean = with(seniorityLevel) { isSeniorityAdmin(player) }
fun godMenuAllowed(player: Player): Boolean = with(trustLevel) { isTrustAdmin(player) }

fun levelError(player: Player, required: String): String? =
    if (levelOrder(player) >= requiredOrder(required)) null else "需要至少资历 ${required} 级才能使用该技能"

fun spendSkillCost(player: Player, cost: Int, code: String): Boolean {
    if (cost <= 0) return true
    SkillCostManager.freeCostSponsor()?.let { sponsor ->
        player.sendMessage("[yellow]本局技能消费由 [white]$sponsor[yellow] 买单，本次免除 [white]$cost MDC")
        return true
    }
    return with(trustPoint) { spendTrustPoints(PlayerData[player].id, cost, "Skill:$code") }
}

fun canAffordSkillCost(player: Player, cost: Int): Boolean {
    if (cost <= 0) return true
    if (SkillCostManager.freeCostSponsor() != null) return true
    return with(trustPoint) { getCachedTrustPoints(PlayerData[player].id) } >= cost
}

fun categoryVisible(player: Player, category: SkillMenuCategory): Boolean {
    val order = levelOrder(player)
    return when (category) {
        SkillMenuCategory.Common -> true
        SkillMenuCategory.Level2 -> order >= requiredOrder("2")
        SkillMenuCategory.Level3 -> order >= requiredOrder("3")
        SkillMenuCategory.Shop -> true
        SkillMenuCategory.Admin -> skillAdmin(player)
    }
}

suspend fun openSkillMainMenu(player: Player) {
    val categories = SkillMenuCategory.values().filter { categoryVisible(player, it) }
    val extraEntries = SkillMainMenuRegistry.visibleEntries(player)
    MenuBuilder<Unit>("技能菜单") {
        msg = """
            |[cyan]请选择技能分类。
            |[gray]2级/3级/管理员技能按资历等级开放；信任4级/已登录admin 默认视为资历4级。
            |[gray]技能仍通过 /skill <技能名> 使用；菜单按钮只是快捷入口。
        """.trimMargin()
        categories.forEach { category ->
            option("${skillCategoryMenuTitle(category)}\n[gray]${category.description}") {
                openSkillCategoryMenu(player, category)
            }
            newRow()
        }
        extraEntries.forEach { entry ->
            option(entry.optionText) { entry.action(player) }
            newRow()
        }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

fun skillMenuNameColor(category: SkillMenuCategory): String = when (category) {
    SkillMenuCategory.Common -> "[cyan]"
    SkillMenuCategory.Level2 -> "[green]"
    SkillMenuCategory.Level3 -> "[purple]"
    SkillMenuCategory.Shop -> "[cyan]"
    SkillMenuCategory.Admin -> "[yellow]"
}

val readableSkillCategories = setOf(
    SkillMenuCategory.Common,
    SkillMenuCategory.Level2,
    SkillMenuCategory.Level3,
    SkillMenuCategory.Shop,
)

private val skillCategoryPageSize = 8

fun skillCategoryMenuTitle(category: SkillMenuCategory): String =
    if (category in readableSkillCategories) {
        "${skillMenuNameColor(category)}${category.displayName}[]"
    } else category.displayName

fun skillMenuOptionText(entry: SkillMenuEntry): String {
    if (entry.category !in readableSkillCategories) {
        return "${entry.displayName}\n[gray]${entry.description}\n[accent]${entry.command}"
    }
    val parts = entry.description.split("；", limit = 2)
    val effect = parts.getOrNull(0)?.trim().orEmpty()
    val rule = parts.getOrNull(1)?.trim().orEmpty()
    return buildString {
        append("${skillMenuNameColor(entry.category)}${entry.displayName}[]")
        if (effect.isNotBlank()) append("\n[gray]效果：$effect")
        if (rule.isNotBlank()) append("\n[gold]规则：$rule")
    }
}

suspend fun openSkillCategoryMenu(player: Player, category: SkillMenuCategory, selectedPage: Int = 1) {
    val entries = SkillMenuRegistry.visibleEntries(player, category)
    MenuBuilder<Unit>(skillCategoryMenuTitle(category)) {
        if (entries.isEmpty()) {
            msg = "[yellow]当前分类暂无你可用的技能。"
            option("返回") { openSkillMainMenu(player) }
            option("关闭") {}
        } else {
            val totalPage = ((entries.size + skillCategoryPageSize - 1) / skillCategoryPageSize).coerceAtLeast(1)
            val page = selectedPage.coerceIn(1, totalPage)
            val pageItems = entries.drop((page - 1) * skillCategoryPageSize).take(skillCategoryPageSize)
            msg = if (category in readableSkillCategories) {
                "[acid]点击技能可直接执行；按钮按“效果 / 规则”分行显示。\n[gray]每页最多 $skillCategoryPageSize 项，当前 ${pageItems.size}/${entries.size}。"
            } else {
                "[cyan]点击技能可直接执行。\n[gray]每页最多 $skillCategoryPageSize 项，当前 ${pageItems.size}/${entries.size}。"
            }
            pageItems.forEach { entry ->
                option(skillMenuOptionText(entry)) {
                    RootCommands.handleInput(entry.command, player, "/")
                }
                newRow()
            }
            option("<-") { openSkillCategoryMenu(player, category, page - 1) }
            option("$page/$totalPage") { openSkillCategoryMenu(player, category, page) }
            option("->") { openSkillCategoryMenu(player, category, page + 1) }
            newRow()
            option("返回") { openSkillMainMenu(player) }
            option("关闭") {}
        }
    }.sendTo(player, 60_000)
}

fun registerSkillMenu(
    code: String,
    displayName: String,
    category: SkillMenuCategory,
    description: String,
    command: String = "/skill $code",
    visible: suspend (Player) -> Boolean = { true },
) {
    SkillMenuRegistry.register(SkillMenuEntry(code, displayName, category, description, command, visible))
}

fun unitTypeByName(name: String): UnitType? =
    Vars.content.getByName<UnitType>(ContentType.unit, name)?.takeIf { it.constructor != null }

fun spawnAround(type: UnitType, player: Player, count: Int, radius: Float = 56f, configure: (mindustry.gen.Unit) -> Unit = {}) {
    val center = player.unit() ?: return
    repeat(count) {
        val angle = Random.nextFloat() * 360f
        val distance = Random.nextFloat() * radius
        val x = center.x + arc.math.Mathf.cosDeg(angle) * distance
        val y = center.y + arc.math.Mathf.sinDeg(angle) * distance
        type.create(player.team()).apply {
            set(x, y)
            configure(this)
            add()
        }
    }
}

fun clampedAimTarget(unit: mindustry.gen.Unit, maxDistance: Float): Vec2 {
    val target = Vec2(unit.aimX(), unit.aimY())
    val delta = target.sub(unit.x, unit.y)
    if (delta.len() > maxDistance) delta.setLength(maxDistance)
    return delta.add(unit.x, unit.y)
}

fun spawnBlitzWave(player: Player, count: Int, target: Vec2) {
    val source = player.unit() ?: return
    repeat(count) { index ->
        val angle = index * (360f / count.coerceAtLeast(1)) + Random.nextFloat() * 18f
        val spawnDistance = 10f + Random.nextFloat() * 18f
        val unit = UnitTypes.zenith.create(player.team()).apply {
            set(source.x + Mathf.cosDeg(angle) * spawnDistance, source.y + Mathf.sinDeg(angle) * spawnDistance)
            rotation(angleTo(target.x, target.y))
            addItem(Items.blastCompound, 60)
            apply(StatusEffects.fast, 60f * 5f)
            apply(StatusEffects.overdrive, 60f * 5f)
            apply(StatusEffects.shielded, 60f * 5f)
            add()
        }
        launch(Dispatchers.game) {
            val vec = Vec2()
            repeat(120) {
                if (!unit.isValid || unit.dead) return@launch
                val jitterX = target.x + Random.nextFloat() * 24f - 12f
                val jitterY = target.y + Random.nextFloat() * 24f - 12f
                if (Mathf.dst(unit.x, unit.y, jitterX, jitterY) <= 14f) {
                    unit.kill()
                    return@launch
                }
                vec.set(jitterX - unit.x, jitterY - unit.y).limit(unit.speed() * 3.2f)
                unit.moveAt(vec)
                unit.lookAt(jitterX, jitterY)
                delay(33L)
            }
            if (unit.isValid && !unit.dead) unit.kill()
        }
    }
}

fun setCoreZone(player: Player, range: IntRange) {
    val unit = player.unit() ?: return
    for (x in range) for (y in range) {
        Vars.world.tile(unit.tileX() + x, unit.tileY() + y)?.setFloorNet(Blocks.coreZone)
    }
}

fun setBlockSquare(player: Player, block: mindustry.world.Block, range: IntRange) {
    val unit = player.unit() ?: return
    for (x in range) for (y in range) {
        Vars.world.tile(unit.tileX() + x, unit.tileY() + y)?.setNet(block, player.team(), 0)
    }
}

fun randomWeather() = arrayOf(Weathers.rain, Weathers.snow, Weathers.sandstorm, Weathers.sporestorm, Weathers.fog).random()

fun randomFunUnit(): UnitType = arrayOf(
    UnitTypes.dagger, UnitTypes.mace, UnitTypes.fortress, UnitTypes.nova, UnitTypes.pulsar, UnitTypes.quasar,
    UnitTypes.crawler, UnitTypes.atrax, UnitTypes.spiroct, UnitTypes.poly, UnitTypes.mega, UnitTypes.flare,
    UnitTypes.horizon, UnitTypes.zenith, UnitTypes.antumbra, UnitTypes.elude, UnitTypes.avert, UnitTypes.obviate,
    UnitTypes.stell, UnitTypes.locus, UnitTypes.precept, UnitTypes.merui, UnitTypes.cleroi, UnitTypes.anthicus
).random()

fun placeBlockAtPlayer(player: Player, block: mindustry.world.Block, requireAir: Boolean = false): Boolean {
    val tile = player.unit()?.tileOn() ?: return false
    if (requireAir && tile.block() != Blocks.air) return false
    tile.setNet(block, player.team(), 0)
    return true
}

data class PrefabBlock(
    val dx: Int,
    val dy: Int,
    val block: Block,
    val rotation: Int = 0,
)

fun prefabAreaError(player: Player, range: IntRange, displayName: String): String? {
    val unit = player.unit() ?: return "[red]无法获取当前单位"
    val size = range.last - range.first + 1
    var outOfWorld = 0
    var occupied = 0
    for (x in range) for (y in range) {
        val tile = Vars.world.tile(unit.tileX() + x, unit.tileY() + y)
        if (tile == null) {
            outOfWorld++
        } else if (tile.block() != Blocks.air) {
            occupied++
        }
    }
    return when {
        outOfWorld > 0 -> "[red]$displayName 需要以脚下为锚点的 ${size}x${size} 空地，范围超出地图边界 ${outOfWorld} 格，无法释放。"
        occupied > 0 -> "[red]$displayName 需要以脚下为锚点的 ${size}x${size} 空地，范围内已有 ${occupied} 个方块，无法释放。"
        else -> null
    }
}

suspend fun placePrefabStage(
    team: Team,
    anchorX: Int,
    anchorY: Int,
    blocks: List<PrefabBlock>,
    delayMillis: Long = 120L,
) {
    blocks.forEach { entry ->
        val tile = Vars.world.tile(anchorX + entry.dx, anchorY + entry.dy) ?: return@forEach
        if (tile.block() != Blocks.air) return@forEach
        tile.setNet(entry.block, team, entry.rotation)
        Call.effect(Fx.placeBlock, tile.worldx(), tile.worldy(), entry.block.size.toFloat(), entry.block.mapColor)
        delay(delayMillis)
    }
}

suspend fun buildBasicPrefabDefense(player: Player) {
    val unit = player.unit() ?: return
    val team = player.team()
    val anchorX = unit.tileX()
    val anchorY = unit.tileY()
    placePrefabStage(team, anchorX, anchorY, listOf(PrefabBlock(0, 0, Blocks.arc)))
    placePrefabStage(
        team,
        anchorX,
        anchorY,
        listOf(
            PrefabBlock(1, 0, Blocks.solarPanel),
            PrefabBlock(0, 1, Blocks.solarPanel),
            PrefabBlock(1, 1, Blocks.battery),
        )
    )
    val walls = mutableListOf<PrefabBlock>()
    for (x in -1..2) for (y in -1..2) {
        if (x in 0..1 && y in 0..1) continue
        walls += PrefabBlock(x, y, Blocks.copperWall)
    }
    placePrefabStage(team, anchorX, anchorY, walls)
}

suspend fun buildStandardPrefabDefense(player: Player) {
    val unit = player.unit() ?: return
    val team = player.team()
    // 蓝瑟是 2x2 方块，setNet 需要以左下坐标作为锚点；这里直接使用玩家脚下格作为蓝瑟左下角。
    val anchorX = unit.tileX()
    val anchorY = unit.tileY()
    placePrefabStage(team, anchorX, anchorY, listOf(PrefabBlock(0, 0, Blocks.lancer)))

    val powerBlocks = mutableListOf<PrefabBlock>()
    for (x in -1..2) for (y in -1..2) {
        if (x in 0..1 && y in 0..1) continue
        powerBlocks += PrefabBlock(x, y, if (y == -1 && x in 0..1) Blocks.battery else Blocks.solarPanel)
    }
    placePrefabStage(team, anchorX, anchorY, powerBlocks)

    val walls = mutableListOf<PrefabBlock>()
    for (x in -2..3) for (y in -2..3) {
        if (x != -2 && x != 3 && y != -2 && y != 3) continue
        walls += PrefabBlock(x, y, Blocks.plastaniumWall)
    }
    placePrefabStage(team, anchorX, anchorY, walls)
}

fun smashWalls(player: Player, range: IntRange): Int {
    val unit = player.unit() ?: return 0
    var removed = 0
    for (x in range) for (y in range) {
        val tile = Vars.world.tile(unit.tileX() + x, unit.tileY() + y) ?: continue
        val block = tile.block()
        if (block is Wall || block is StaticWall) {
            tile.setNet(Blocks.air)
            removed++
        }
    }
    return removed
}

fun damageAllUnits(percent: Float) {
    Groups.unit.toList().forEach { unit ->
        if (unit.isValid && !unit.dead) unit.damage((unit.health * percent).coerceAtLeast(1f))
    }
}

val extinguishColor = Color.valueOf("80f8ff")
val tietieColor = Color.valueOf("ff77cc")
val nukeColor = Color.valueOf("d8b4ff")
val skillHealColor = Color.valueOf("84f491")
val extinguishWaterBullet by lazy {
    // 优先使用原版海啸(tsunami)水弹，确保客户端同步与 LiquidBulletType 原版灭火逻辑一致。
    (Blocks.tsunami as? LiquidTurret)?.ammoTypes?.get(Liquids.water) ?: LiquidBulletType(Liquids.water).apply {
        damage = 0.2f
        speed = 4f
        lifetime = 49f
        drag = 0.001f
        knockback = 1.7f
        puddleSize = 8f
        orbSize = 4f
        ammoMultiplier = 0.4f
        collidesTeam = false
        status = StatusEffects.wet
        statusDuration = 60f * 4f
    }
}

fun emitTsunamiWaterScatter(player: Player, bulletCount: Int = 72) {
    val unit = player.unit() ?: return
    val bullet = extinguishWaterBullet
    val base = Random.nextFloat() * 360f
    val step = 360f / bulletCount.coerceAtLeast(1)
    repeat(bulletCount.coerceAtLeast(1)) { index ->
        val angle = base + index * step + Random.nextFloat() * 4f - 2f
        bullet.createNet(player.team(), unit.x, unit.y, angle, bullet.damage, 1f, 1f)
    }
}


data class ExamResult(
    val uid: String,
    val name: String,
    val score: Int,
    val reward: Int,
)

fun gaokaoReward(score: Int): Int = (score / 10.0).roundToInt().coerceAtLeast(0)

fun resolveOnlinePlayer(text: String): Player? {
    val fixed = text.trim()
    if (fixed.isBlank()) return null
    if (fixed.startsWith("#")) {
        fixed.substring(1).toIntOrNull()?.let { id -> Groups.player.getByID(id)?.let { return it } }
    }
    PlayerData.findByShortId(fixed)?.player?.let { return it }
    val plain = fixed.replace(" ", "")
    return Groups.player.find {
        it.uuid() == fixed ||
                PlayerData[it].id == fixed ||
                PlayerData[it].shortId.equals(fixed, ignoreCase = true) ||
                it.name.replace(" ", "") == plain ||
                it.plainName().replace(" ", "") == plain
    }
}

private val statusFieldCache = mutableMapOf<Class<*>, java.lang.reflect.Field?>()

@Suppress("UNCHECKED_CAST")
fun statusEntries(unit: mindustry.gen.Unit): Seq<StatusEntry> {
    val field = statusFieldCache.getOrPut(unit.javaClass) {
        generateSequence(unit.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz -> runCatching { clazz.getDeclaredField("statuses") }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
    } ?: error("当前单位类型 ${unit.javaClass.name} 没有 statuses 字段，无法直接叠加状态")
    return field.get(unit) as Seq<StatusEntry>
}

fun applyStackedStatus(unit: mindustry.gen.Unit, effect: StatusEffect, duration: Float, stacks: Int = 1): Int {
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

fun clearNearbyFires(player: Player, radiusTiles: Int = 10): Int {
    val unit = player.unit() ?: return 0
    val radius = radiusTiles * 8f
    val centerX = unit.tileX()
    val centerY = unit.tileY()
    val removedTiles = mutableSetOf<Int>()
    var removed = 0

    for (x in centerX - radiusTiles..centerX + radiusTiles) {
        for (y in centerY - radiusTiles..centerY + radiusTiles) {
            if (Mathf.dst(x.toFloat(), y.toFloat(), centerX.toFloat(), centerY.toFloat()) > radiusTiles + 0.5f) continue
            val tile = Vars.world.tile(x, y) ?: continue
            if (!Fires.has(tile.x.toInt(), tile.y.toInt())) continue
            Fires.extinguish(tile, 1f)
            Fires.remove(tile)
            if (removedTiles.add(tile.pos())) removed++
            Call.effect(Fx.fireRemove, tile.worldx(), tile.worldy(), 0f, extinguishColor)
        }
    }

    Groups.fire.toList().forEach { fire ->
        if (Mathf.dst(fire.x, fire.y, unit.x, unit.y) > radius) return@forEach
        val tile = fire.tile
        if (tile != null && !removedTiles.add(tile.pos())) return@forEach
        Call.effect(Fx.fireRemove, fire.x, fire.y, 0f, extinguishColor)
        fire.remove()
        removed++
    }
    emitTsunamiWaterScatter(player)
    Call.effect(Fx.pointShockwave, unit.x, unit.y, radius, extinguishColor)
    return removed
}





listen<EventType.ResetEvent> {
    SkillCommands.allCooldown.forEach { it.reset() }
    SkillCostManager.disableFreeCost()
    Vars.state.rules.tags.remove("@doubleMdcReward")
}

onEnable {
    listOf(
        SkillMenuEntry("clearself", "紫砂", SkillMenuCategory.Common, "杀死自己；消耗0 MDC，冷却120秒", "/skill clearSelf"),
        SkillMenuEntry("kill", "自爆", SkillMenuCategory.Common, "杀死自己，并伤害周围敌方单位/建筑；消耗0 MDC，冷却120秒"),
        SkillMenuEntry("cola", "紫薇", SkillMenuCategory.Common, "以10%最大生命换取超频/护盾/加速；消耗0 MDC，冷却120秒"),
        SkillMenuEntry("heal", "自疗", SkillMenuCategory.Common, "治疗当前附身单位；消耗0 MDC，冷却120秒"),
        SkillMenuEntry("copper", "生锈的铜", SkillMenuCategory.Common, "给当前队伍核心添加114铜；消耗0 MDC，冷却120秒"),
        SkillMenuEntry("summonpoly", "召唤poly", SkillMenuCategory.Common, "召唤一只建造机 poly；消耗0 MDC，冷却120秒", "/skill summonpoly"),
        SkillMenuEntry("summonunloader", "召唤装卸器", SkillMenuCategory.Common, "在脚下空地放置 duct-unloader；消耗0 MDC，冷却300秒", "/skill summonunloader"),
        SkillMenuEntry("illuminator", "照明器", SkillMenuCategory.Common, "在脚下空地放置 illuminator；消耗0 MDC，冷却120秒"),
        SkillMenuEntry("basicdefense", "初级预制防线", SkillMenuCategory.Common, "需4x4空地；按炮台→供电/电池→墙体顺序建造铜墙电弧防线；消耗0 MDC，一局一次", "/skill basicdefense"),
        SkillMenuEntry("extinguish", "灭火", SkillMenuCategory.Common, "从自身向外散射原版海啸水弹，并直接清理周围10格火焰兜底；消耗0 MDC，冷却120秒"),
        SkillMenuEntry("disarm", "缴械", SkillMenuCategory.Common, "给当前单位添加5分钟缴械效果；消耗0 MDC，冷却300秒"),
        SkillMenuEntry("shield", "护盾", SkillMenuCategory.Level2, "获得等于最大血量的护盾；消耗2 MDC，冷却120秒"),
        SkillMenuEntry("health", "范围治愈", SkillMenuCategory.Level2, "治疗周围友方单位与建筑20%最大生命；消耗2 MDC，冷却120秒"),
        SkillMenuEntry("fullheal", "完全痊愈", SkillMenuCategory.Level2, "完全治愈当前附身单位；消耗5 MDC，冷却120秒", "/skill fullheal"),
        SkillMenuEntry("fortune", "查看今日运势", SkillMenuCategory.Level2, "随机查看今日运势，大吉时获得[gold][无不利！][]称号；消耗0 MDC，每天一次"),
        SkillMenuEntry("monomother", "递归mono", SkillMenuCategory.Level2, "召唤可短时间生成 mono 的递归 mono；消耗2 MDC，冷却300秒", "/skill monoMother"),
        SkillMenuEntry("lowwallkiller", "墙壁粉碎者", SkillMenuCategory.Level2, "粉碎脚下一格墙壁/天然墙；消耗0 MDC，冷却120秒", "/skill lowwallKiller"),
        SkillMenuEntry("sourcelottery", "欧皇物品源", SkillMenuCategory.Level2, "脚下空地抽取方块，1%为物品源，否则分类器；消耗2 MDC，冷却120秒", "/skill sourcelottery"),
        SkillMenuEntry("coreshard", "小伙子,来点读品？", SkillMenuCategory.Level2, "在脚下放置一个处理器；消耗2 MDC，冷却120秒"),
        SkillMenuEntry("corezone", "核心区", SkillMenuCategory.Level2, "生成3x3核心区地板；消耗2 MDC，冷却120秒", "/skill coreZone"),
        SkillMenuEntry("flying", "飞起", SkillMenuCategory.Level2, "让当前单位切换为飞行状态；消耗2 MDC，冷却120秒"),
        SkillMenuEntry("landing", "坠机", SkillMenuCategory.Level2, "让当前单位切换为降落/地面状态；消耗10 MDC，冷却120秒"),
        SkillMenuEntry("runfaster", "你跑不过我你信不信", SkillMenuCategory.Level2, "为当前单位实际叠加3层 fast；消耗20 MDC，120秒后当前单位猝死"),
        SkillMenuEntry("boundmega", "绑定mega", SkillMenuCategory.Level2, "召唤专属 mega 并强制自己附身，其他玩家无法附身；消耗5 MDC，一局一次", "/skill boundmega"),
        SkillMenuEntry("laststand", "拼死一搏", SkillMenuCategory.Level2, "当前单位获得最大血量护盾与5层超频，20秒后死亡；消耗5 MDC，冷却300秒", "/skill laststand"),
        SkillMenuEntry("rocket", "空对地导弹", SkillMenuCategory.Level2, "将玩家附身单位切换为 scathe-missile-phase；消耗2 MDC，无冷却"),
        SkillMenuEntry("decisivesquad", "决胜中队", SkillMenuCategory.Level2, "召唤携带爆炸混合物的决胜中队；消耗6 MDC，冷却120秒", "/skill decisiveSquad"),
        SkillMenuEntry("anvilsquad", "铁砧小队", SkillMenuCategory.Level2, "呼叫不可附身雷霆(quad)运输机抵达后投放铁砧小队；死亡/40秒超时则取消且不返还MDC；消耗6 MDC，冷却120秒", "/skill anvilSquad"),
        SkillMenuEntry("hammersquad", "铁锤小队", SkillMenuCategory.Level2, "呼叫不可附身雷霆(quad)运输机抵达后投放铁锤小队；死亡/40秒超时则取消且不返还MDC；消耗6 MDC，冷却120秒", "/skill hammerSquad"),
        SkillMenuEntry("blitz", "骇人空袭", SkillMenuCategory.Level3, "在玩家位置快速召唤三波自爆空军冲向光标附近；消耗10 MDC，一局一次"),
        SkillMenuEntry("antiarmor", "反装甲炮击", SkillMenuCategory.Level3, "在鼠标位置标记20格炮击区，30秒内锁定区域敌方单位并每1.5秒造成10%最大生命+800伤害；消耗15 MDC，冷却300秒"),
        SkillMenuEntry("pddcut", "拼夕夕砍一刀", SkillMenuCategory.Level3, "使全场单位当前血量减少90%；消耗10 MDC，一局一次", "/skill pddCut"),
        SkillMenuEntry("disaster", "天灾", SkillMenuCategory.Level3, "触发随机天气并持续削减全场单位血量；消耗10 MDC，一局一次"),
        SkillMenuEntry("redlightgreenlight", "123木头人", SkillMenuCategory.Level3, "停止全场单位并随机击杀10个单位；消耗10 MDC，一局一次", "/skill redLightGreenLight"),
        SkillMenuEntry("gaokao", "参加高考", SkillMenuCategory.Level3, "随机生成0-750高考分数并按分数结算MDC；消耗20 MDC，每天一次"),
        SkillMenuEntry("firetruck", "消防车", SkillMenuCategory.Level3, "6秒内每秒从自身向外散射海啸水弹灭火；消耗0 MDC，冷却300秒"),
        SkillMenuEntry("omg", "omg", SkillMenuCategory.Level3, "omg"),
        SkillMenuEntry("standarddefense", "标准预制防线", SkillMenuCategory.Level3, "需6x6空地；按炮台→供电/电池→墙体顺序建造塑钢墙蓝瑟防线；消耗15 MDC，一局一次", "/skill standarddefense"),
        SkillMenuEntry("missilevolley", "导弹齐射", SkillMenuCategory.Level3, "在玩家位置召唤10个 scathe-missile-surge；消耗10 MDC，无冷却", "/skill missileVolley"),
        SkillMenuEntry("supplyitem", "物资补给", SkillMenuCategory.Level3, "选择当前所有物资中的一种，为本队核心添加100个；消耗10 MDC", "/skill supplyitem"),
        SkillMenuEntry("randommaga", "随机maga", SkillMenuCategory.Level3, "召唤一只携带随机有效建筑方块载荷的 mega；仍可突破载荷上限；消耗20 MDC，一局一次", "/skill randommaga"),
        SkillMenuEntry("nuke", "核弹打击", SkillMenuCategory.Level3, "5秒后在光标坐标触发钍反应堆爆炸；消耗20 MDC，一局一次"),
        SkillMenuEntry("refreshskills", "刷新技能", SkillMenuCategory.Level3, "清除自己当前所有技能冷却；消耗100 MDC，冷却300秒", "/skill refreshskills"),
        SkillMenuEntry("tietie", "贴贴", SkillMenuCategory.Level3, "向目标发送贴贴请求，接受后双方贴贴并生成随机单位；消耗100 MDC，无冷却"),
        SkillMenuEntry("examtime", "考试时间！", SkillMenuCategory.Admin, "管理员技能：全员依次生成高考成绩，仅前三获得MDC奖励", "/skill examtime"),
        SkillMenuEntry("source", "物品源", SkillMenuCategory.Admin, "管理员技能：放置3x3物品源"),
        SkillMenuEntry("ecore", "E星核心", SkillMenuCategory.Admin, "管理员技能：放置3x3核心"),
        SkillMenuEntry("invincible", "无敌", SkillMenuCategory.Admin, "管理员技能：给予强力状态"),
        SkillMenuEntry("freeskillcost", "全场技能买单", SkillMenuCategory.Admin, "管理员技能：本局技能使用不消耗MDC", "/skill freeSkillCost"),
        SkillMenuEntry("doublemdcreward", "本局结算MDC翻倍", SkillMenuCategory.Admin, "管理员技能：本局贡献结算获得MDC翻倍", "/skill doublemdcreward"),
        SkillMenuEntry("killallunits", "击杀所有单位", SkillMenuCategory.Admin, "管理员技能：清空当前所有单位", "/skill killallunits"),
        SkillMenuEntry("infinitefire", "无限火力promax", SkillMenuCategory.Admin, "管理员技能：为当前地图开启120秒无限火力promax", "/skill infinitefire"),
        SkillMenuEntry("wallkillerpro", "墙体粉碎者pro", SkillMenuCategory.Admin, "管理员技能：破开周围5x5墙壁/天然墙", "/skill wallkillerpro"),
        SkillMenuEntry("daoshengyi", "道生一....", SkillMenuCategory.Admin, "管理员技能：每秒召唤mono，持续20秒", "/skill daoshengyi"),
        SkillMenuEntry("powersource", "现在的发电量是1m！电力，轻而易举啊", SkillMenuCategory.Admin, "管理员技能：脚下召唤 power-source", "/skill powersource"),
        SkillMenuEntry("floodon", "开启洪水脚本", SkillMenuCategory.Admin, "管理员技能：尝试启用 @flood 地图脚本", "/skill floodon"),
        SkillMenuEntry("floodoff", "关闭洪水脚本", SkillMenuCategory.Admin, "管理员技能：尝试关闭 @flood 地图脚本", "/skill floodoff"),
        SkillMenuEntry("lordon", "开启Lord脚本", SkillMenuCategory.Admin, "管理员技能：尝试加载 mapScript/14668", "/skill lordon"),
        SkillMenuEntry("lordoff", "关闭Lord脚本", SkillMenuCategory.Admin, "管理员技能：尝试关闭 mapScript/14668", "/skill lordoff"),
        SkillMenuEntry("addnoskill", "开启noskill限制", SkillMenuCategory.Admin, "管理员技能：为当前地图添加 @noSkills 标签", "/skill addnoskill"),
        SkillMenuEntry("removenoskill", "解除noskill限制", SkillMenuCategory.Admin, "管理员技能：移除当前地图 @noSkills 标签", "/skill removenoskill"),
    ).forEach { SkillMenuRegistry.register(it) }
}

onDisable {
    listOf(
        "clearself", "kill", "cola", "heal", "copper", "shield", "health", "fullheal", "monomother", "lowwallkiller",
        "summonpoly", "summonunloader", "illuminator", "basicdefense", "extinguish", "disarm", "fortune", "sourcelottery", "coreshard", "corezone", "flying", "landing", "runfaster", "boundmega", "laststand", "rocket",
        "decisivesquad", "anvilsquad", "hammersquad", "blitz", "antiarmor", "pddcut", "disaster", "redlightgreenlight",
        "gaokao", "firetruck", "omg", "standarddefense", "missilevolley", "supplyitem", "randommaga", "nuke", "refreshskills", "tietie", "examtime", "godmenu", "source", "ecore", "invincible", "freeskillcost", "doublemdcreward", "killallunits", "infinitefire",
        "wallkillerpro", "daoshengyi", "powersource", "floodon", "floodoff", "lordon", "lordoff", "addnoskill", "removenoskill"
    ).forEach { SkillMenuRegistry.unregister(it) }
}

command("skill", "技能菜单") {
    aliases = listOf("技能", "skills")
    attr(ClientOnly)
    body {
        if (arg.isEmpty()) openSkillMainMenu(player!!)
        else SkillCommands.handle()
    }
}

command("common", "打开通用技能分类".with(), commands = SkillCommands) {
    aliases = listOf("通用", "通用技能")
    attr(ClientOnly)
    body { openSkillCategoryMenu(player!!, SkillMenuCategory.Common) }
}

command("level2", "打开2级技能分类".with(), commands = SkillCommands) {
    aliases = listOf("2", "二级", "2级", "2级技能")
    attr(ClientOnly)
    body {
        if (!categoryVisible(player!!, SkillMenuCategory.Level2)) returnReply("[red]需要至少资历 2 级才能查看该分类".with())
        openSkillCategoryMenu(player!!, SkillMenuCategory.Level2)
    }
}

command("level3", "打开3级技能分类".with(), commands = SkillCommands) {
    aliases = listOf("3", "三级", "3级", "3级技能")
    attr(ClientOnly)
    body {
        if (!categoryVisible(player!!, SkillMenuCategory.Level3)) returnReply("[red]需要至少资历 3 级才能查看该分类".with())
        openSkillCategoryMenu(player!!, SkillMenuCategory.Level3)
    }
}

command("shop", "打开特殊/商店技能分类".with(), commands = SkillCommands) {
    aliases = listOf("special", "store", "特殊", "商店", "特殊技能", "商店技能")
    attr(ClientOnly)
    body { openSkillCategoryMenu(player!!, SkillMenuCategory.Shop) }
}

command("admin", "打开管理员技能分类".with(), commands = SkillCommands) {
    aliases = listOf("管理员", "管理", "管理员技能")
    attr(ClientOnly)
    body {
        if (!categoryVisible(player!!, SkillMenuCategory.Admin)) returnReply("[red]需要资历 4 级/信任4级/已登录admin 才能查看该分类".with())
        openSkillCategoryMenu(player!!, SkillMenuCategory.Admin)
    }
}

