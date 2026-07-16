@file:Depends("wayzer/user/ext/skills", "技能系统核心")
@file:Depends("wayzer/ext/soundEffectMenu", "服务器小音效菜单")

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
import mindustry.gen.Payloadc
import mindustry.gen.Player
import mindustry.gen.Sounds
import mindustry.type.Item
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
import mindustry.world.blocks.payloads.BuildPayload
import wayzer.lib.PlayerData
import java.lang.reflect.Modifier
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import kotlin.random.Random

private val skillsCore = contextScript<Skills>()
private val soundEffects = contextScript<wayzer.ext.SoundEffectMenu>()
private fun levelError(player: Player, required: String): String? = skillsCore.levelError(player, required)
private fun spendSkillCost(player: Player, cost: Int, code: String): Boolean = skillsCore.spendSkillCost(player, cost, code)
private val trustPoint get() = skillsCore.trustPoint
private val playerTitle get() = skillsCore.playerTitle
private val tietieColor: Color get() = skillsCore.tietieColor
private val nukeColor: Color get() = skillsCore.nukeColor
private fun clampedAimTarget(unit: mindustry.gen.Unit, maxDistance: Float): Vec2 = skillsCore.clampedAimTarget(unit, maxDistance)
private fun spawnBlitzWave(player: Player, count: Int, target: Vec2) = skillsCore.spawnBlitzWave(player, count, target)
private fun damageAllUnits(percent: Float) = skillsCore.damageAllUnits(percent)
private fun randomWeather() = skillsCore.randomWeather()
private fun clearNearbyFires(player: Player, radiusTiles: Int = 10): Int = skillsCore.clearNearbyFires(player, radiusTiles)
private fun prefabAreaError(player: Player, range: IntRange, displayName: String): String? = skillsCore.prefabAreaError(player, range, displayName)
private suspend fun buildStandardPrefabDefense(player: Player) = skillsCore.buildStandardPrefabDefense(player)
private fun unitTypeByName(name: String): UnitType? = skillsCore.unitTypeByName(name)
private fun spawnAround(type: UnitType, player: Player, count: Int, radius: Float = 56f, configure: (mindustry.gen.Unit) -> Unit = {}) =
    skillsCore.spawnAround(type, player, count, radius, configure)
private fun gaokaoReward(score: Int): Int = skillsCore.gaokaoReward(score)
private fun resolveOnlinePlayer(text: String): Player? = skillsCore.resolveOnlinePlayer(text)

private fun availableSupplyItems(): List<Item> =
    Vars.content.items()
        .filter { !it.hidden }
        .sortedWith(compareBy<Item> { it.localizedName }.thenBy { it.name })

private fun resolveSupplyItem(text: String): Item? {
    val fixed = text.trim()
    if (fixed.isBlank()) return null
    return availableSupplyItems().firstOrNull {
        it.name.equals(fixed, ignoreCase = true) ||
                it.localizedName.equals(fixed, ignoreCase = true) ||
                it.emoji().equals(fixed, ignoreCase = true)
    }
}

private fun validRandomMegaPayloadBlock(block: Block): Boolean {
    if (block.isAir || block.isFloor || block.isOverlay) return false
    if (block.category == null) return false
    if (!block.canBeBuilt() || !block.hasBuilding() || block.buildType == null) return false

    // 只验证该方块能作为 BuildPayload 构造；实际塞入 mega 时仍直接 addPayload，
    // 不调用 canPickup/canPickupPayload，从而保留原本“可突破载荷上限”的效果。
    return runCatching { BuildPayload(block, Team.derelict) }.isSuccess
}

private fun randomMegaPayloadBlock(): Block? =
    Vars.content.blocks()
        .filter(::validRandomMegaPayloadBlock)
        .randomOrNull()

private fun summonPayloadMega(player: Player, block: Block): mindustry.gen.Unit {
    val source = player.unit()
    val mega = UnitTypes.mega.create(player.team()).apply {
        set(source?.x ?: player.x, source?.y ?: player.y)
        elevation = 1f
        rotation(source?.rotation ?: 0f)
        add()
    }
    (mega as? Payloadc)?.addPayload(BuildPayload(block, player.team()))
    return mega
}

private suspend fun openSupplyItemMenu(caster: Player) {
    val items = availableSupplyItems()
    if (items.isEmpty()) {
        caster.sendMessage("[yellow]当前内容列表中没有可用物资。")
        return
    }
    PagedMenuBuilder(items) { item ->
        option("${item.emoji()} ${item.localizedName}\n[gray]${item.name}；消耗10 MDC，为本队核心+100") {
            RootCommands.handleInput("/skill supplyitem ${item.name}", caster, "/")
        }
    }.apply {
        title = "选择物资补给"
        msg = "[cyan]选择一种当前内容列表中的物资；确认后消耗 [gold]10 MDC[cyan]，为你所在队伍核心添加 [gold]100[cyan] 该物资。"
        sendTo(caster, 60_000)
    }
}

private val randomT1Units = listOf(
    UnitTypes.dagger, UnitTypes.crawler, UnitTypes.nova, UnitTypes.flare, UnitTypes.mono,
    UnitTypes.risso, UnitTypes.retusa, UnitTypes.stell, UnitTypes.merui, UnitTypes.elude
)
private val randomT2Units = listOf(
    UnitTypes.mace, UnitTypes.atrax, UnitTypes.pulsar, UnitTypes.horizon, UnitTypes.poly,
    UnitTypes.minke, UnitTypes.oxynoe, UnitTypes.locus, UnitTypes.cleroi, UnitTypes.avert
)
private val randomT3Units = listOf(
    UnitTypes.fortress, UnitTypes.spiroct, UnitTypes.quasar, UnitTypes.zenith, UnitTypes.mega,
    UnitTypes.bryde, UnitTypes.cyerce, UnitTypes.precept, UnitTypes.anthicus, UnitTypes.obviate
)
private val randomT4Units = listOf(
    UnitTypes.scepter, UnitTypes.arkyid, UnitTypes.vela, UnitTypes.antumbra, UnitTypes.quad,
    UnitTypes.sei, UnitTypes.aegires, UnitTypes.vanquish, UnitTypes.tecta, UnitTypes.quell
)

private fun randomTierUnit(maxTier: Int): UnitType = when (maxTier.coerceIn(1, 4)) {
    1 -> randomT1Units
    2 -> randomT1Units + randomT2Units
    3 -> randomT1Units + randomT2Units + randomT3Units
    else -> randomT1Units + randomT2Units + randomT3Units + randomT4Units
}.random()

private fun spawnAt(type: UnitType, team: mindustry.game.Team, x: Float, y: Float, radius: Float = 24f) {
    val angle = Random.nextFloat() * 360f
    val distance = Random.nextFloat() * radius
    type.create(team).apply {
        set(x + Mathf.cosDeg(angle) * distance, y + Mathf.sinDeg(angle) * distance)
        add()
    }
}

private fun spawnRandomUnitsAt(player: Player, maxTier: Int, count: Int) {
    val unit = player.unit() ?: return
    repeat(count) {
        spawnAt(randomTierUnit(maxTier), player.team(), unit.x, unit.y)
    }
}


private suspend fun openTietieTargetMenu(caster: Player) {
    val targets = Groups.player.toList().filter { it !== caster }.sortedBy { it.plainName() }
    if (targets.isEmpty()) {
        caster.sendMessage("[yellow]当前没有可贴贴的在线目标。")
        return
    }
    PagedMenuBuilder(targets) { target ->
        val data = PlayerData[target]
        option("${target.name} [gray](${data.shortId})\n[gray]点击后发送贴贴请求") {
            RootCommands.handleInput("/skill tietie ${data.shortId}", caster, "/")
        }
    }.apply {
        title = "选择贴贴对象"
        msg = "[cyan]选择一个在线玩家发送贴贴请求；目标拒绝或超时会触发失败效果。"
        sendTo(caster, 60_000)
    }
}

private suspend fun askTietieAccept(caster: Player, target: Player): Boolean? {
    var accepted: Boolean? = null
    MenuBuilder<Unit>("贴贴请求") {
        msg = "[pink]\"${caster.plainName()}\"[white]想要和你贴贴，是否接受？"
        option("接受") { accepted = true }
        option("拒绝") { accepted = false }
    }.sendTo(target, 15_000)
    return accepted
}

private fun startTietieSuccess(caster: Player, target: Player) {
    val casterUnit = caster.unit()
    val targetUnit = target.unit()
    if (casterUnit == null || targetUnit == null || casterUnit.dead || targetUnit.dead) {
        caster.sendMessage("[yellow]贴贴失败：双方都需要拥有有效单位。")
        target.sendMessage("[yellow]贴贴失败：双方都需要拥有有效单位。")
        return
    }
    val tile = casterUnit.tileOn()
    val x = tile?.worldx() ?: casterUnit.x
    val y = tile?.worldy() ?: casterUnit.y
    casterUnit.apply(StatusEffects.unmoving, 10f * 60f)
    targetUnit.apply(StatusEffects.unmoving, 10f * 60f)
    broadcast("[pink]{caster.name}[white]和[pink]{target.name}[white]正在贴贴！".with("caster" to caster, "target" to target), quite = true)
    launch(Dispatchers.game) {
        repeat(20) {
            listOf(caster, target).forEach { p ->
                if (!p.dead()) {
                    val unit = p.unit() ?: return@forEach
                    if (unit.isValid && !unit.dead) {
                        unit.set(x, y)
                        unit.snapInterpolation()
                        Call.effect(Fx.healBlock, unit.x, unit.y, 1.2f, tietieColor)
                    }
                }
            }
            delay(500L)
        }
    }
    launch(Dispatchers.game) {
        delay(4_000L)
        repeat(6) {
            spawnRandomUnitsAt(caster, 4, 1)
            spawnRandomUnitsAt(target, 4, 1)
            delay(1_000L)
        }
    }
}

private fun startTietieFailure(caster: Player, target: Player) {
    broadcast(
        "[pink]{target.name}[white]拒绝了[pink]{caster.name}[white]的贴贴请求！ {caster.name}[white]只能自交力！（悲）"
            .with("caster" to caster, "target" to target),
        quite = true
    )
    spawnRandomUnitsAt(caster, 3, 10)
}

private fun triggerThoriumExplosion(x: Float, y: Float) {
    Call.effect(Fx.reactorExplosion, x, y, 0f, nukeColor)
    Damage.damage(x, y, 160f, 5000f)
}

private val antiArmorBarrageColor: Color = Color.valueOf("ffc22c")
private val ANTI_ARMOR_BARRAGE_RADIUS = 160f
private val ANTI_ARMOR_BARRAGE_DURATION_MILLIS = 30_000L
private val ANTI_ARMOR_BARRAGE_INTERVAL_MILLIS = 1_500L

private fun validAntiArmorTarget(
    target: mindustry.gen.Unit?,
    team: Team,
    x: Float,
    y: Float,
    radius: Float,
): Boolean {
    if (target == null || !target.isValid || target.dead) return false
    if (target.team == team || target.team == Team.derelict) return false
    return Mathf.dst(target.x, target.y, x, y) <= radius
}

private fun findAntiArmorTarget(
    team: Team,
    x: Float,
    y: Float,
    radius: Float,
    locked: mindustry.gen.Unit?,
): mindustry.gen.Unit? {
    if (validAntiArmorTarget(locked, team, x, y, radius)) return locked
    var best: mindustry.gen.Unit? = null
    var bestMaxHealth = -1f
    Groups.unit.forEach {
        if (validAntiArmorTarget(it, team, x, y, radius) && it.maxHealth > bestMaxHealth) {
            best = it
            bestMaxHealth = it.maxHealth
        }
    }
    return best
}

private fun showAntiArmorBarrageArea(x: Float, y: Float, radius: Float, label: String? = null) {
    Call.effect(Fx.dynamicWave, x, y, radius, antiArmorBarrageColor)
    if (label != null) Call.label(label, 2.2f, x, y)
}

private fun strikeAntiArmorTarget(team: Team, target: mindustry.gen.Unit) {
    val damage = target.maxHealth * 0.10f + 800f
    val x = target.x
    val y = target.y
    Call.effect(Fx.colorSparkBig, x, y, 1.5f, antiArmorBarrageColor)
    Call.effect(Fx.colorSparkBig, x, y, 1.5f, antiArmorBarrageColor)
    Call.effect(Fx.dynamicWave, x, y, 18f, antiArmorBarrageColor)
    Call.logicExplosion(team, x, y, 12f, 0f, true, true, false, true)
    target.damage(damage)
    Call.label("[orange]反装甲炮击\n[red]-${damage.roundToInt()}[white]", 1.15f, x, y)
}

private suspend fun runAntiArmorBarrage(team: Team, x: Float, y: Float) {
    showAntiArmorBarrageArea(x, y, ANTI_ARMOR_BARRAGE_RADIUS, "[acid]反装甲炮击[white]\n[yellow]锁定区域")
    repeat(4) {
        delay(180L)
        Call.effect(Fx.dynamicWave, x, y, ANTI_ARMOR_BARRAGE_RADIUS, antiArmorBarrageColor)
    }

    var locked: mindustry.gen.Unit? = null
    val endAt = System.currentTimeMillis() + ANTI_ARMOR_BARRAGE_DURATION_MILLIS
    while (System.currentTimeMillis() < endAt) {
        showAntiArmorBarrageArea(x, y, ANTI_ARMOR_BARRAGE_RADIUS)
        locked = findAntiArmorTarget(team, x, y, ANTI_ARMOR_BARRAGE_RADIUS, locked)
        locked?.takeIf { validAntiArmorTarget(it, team, x, y, ANTI_ARMOR_BARRAGE_RADIUS) }?.let {
            strikeAntiArmorTarget(team, it)
            if (!it.isValid || it.dead || it.health <= 0f) locked = null
        }
        delay(ANTI_ARMOR_BARRAGE_INTERVAL_MILLIS)
    }
    Call.label("[acid]反装甲炮击[white]\n[gray]炮击结束", 1.5f, x, y)
}

// 3级技能：不受 noskill 影响，但 PVP 禁用。
command("blitz", "3级技能：骇人空袭".with(), commands = SkillCommands) {
    aliases = listOf("骇人空袭")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown())
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        val source = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 10, "blitz")) returnReply("[red]MDC不足：骇人空袭需要 10 MDC".with())
        val team = player.team()
        val target = clampedAimTarget(source, 360f)
        launch(Dispatchers.game) {
            team.rules().unitCrashDamageMultiplier *= 2f
            repeat(3) {
                spawnBlitzWave(player, 5, target)
                delay(200L)
            }
            delay(8_000L)
            team.rules().unitCrashDamageMultiplier /= 2f
        }
        broadcastSkill("骇人空袭")
    }
}

command("antiarmor", "3级技能：反装甲炮击".with(), commands = SkillCommands) {
    aliases = listOf("反装甲炮击", "反装甲集束炮击", "antiArmor", "antiArmorBarrage")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown(300_000))
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 15, "antiarmor")) returnReply("[red]MDC不足：反装甲炮击需要 15 MDC".with())
        val x = player.mouseX
        val y = player.mouseY
        val team = player.team()
        val tileX = (x / Vars.tilesize).roundToInt()
        val tileY = (y / Vars.tilesize).roundToInt()
        launch(Dispatchers.game) {
            runAntiArmorBarrage(team, x, y)
        }
        broadcast(
            "[acid]{player.name}[white]呼叫了[acid]反装甲炮击[white]，坐标：[accent]{x}, {y}[white]，持续30秒！".with(
                "player" to player,
                "x" to tileX,
                "y" to tileY
            ),
            quite = true
        )
    }
}

command("pddCut", "3级技能：拼夕夕砍一刀".with(), commands = SkillCommands) {
    aliases = listOf("拼夕夕砍一刀", "砍一刀")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown())
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 10, "pddCut")) returnReply("[red]MDC不足：拼夕夕砍一刀需要 10 MDC".with())
        damageAllUnits(0.9f)
        broadcastSkill("拼夕夕砍一刀")
    }
}

command("disaster", "3级技能：天灾".with(), commands = SkillCommands) {
    aliases = listOf("天灾")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown())
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 10, "disaster")) returnReply("[red]MDC不足：天灾需要 10 MDC".with())
        val weather = randomWeather()
        Call.createWeather(weather, 1f, 120f * 60f, 0f, 0f)
        launch(Dispatchers.game) {
            repeat(12) {
                delay(10_000)
                damageAllUnits(0.05f)
            }
        }
        broadcastSkill("天灾")
    }
}

command("redLightGreenLight", "3级技能：123木头人".with(), commands = SkillCommands) {
    aliases = listOf("123木头人", "木头人")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown())
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 10, "redLightGreenLight")) returnReply("[red]MDC不足：123木头人需要 10 MDC".with())
        Groups.unit.toList().forEach {
            it.apply(StatusEffects.disarmed, 5f * 60f)
            it.apply(StatusEffects.unmoving, 5f * 60f)
        }
        Groups.unit.toList().shuffled().take(10).forEach { it.kill() }
        broadcastSkill("123木头人")
    }
}

command("gaokao", "3级技能：参加高考".with(), commands = SkillCommands) {
    aliases = listOf("参加高考", "高考")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp)
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        val uid = PlayerData[player].id
        val today = LocalDate.now().toString()
        val key = "skill.gaokao.$uid.lastDate"
        if (MdtStorage.getSetting(key) == today) {
            returnReply("[yellow]今天已经参加过高考了，明天再来吧。".with())
        }
        if (!spendSkillCost(player, 20, "gaokao")) returnReply("[red]MDC不足：参加高考需要 20 MDC".with())
        MdtStorage.setSetting(key, today)

        val score = Random.nextInt(0, 751)
        val reward = gaokaoReward(score)
        if (score < 400) {
            with(trustPoint) { addTrustPoints(uid, -reward, "Skill:gaokao") }
            broadcast(
                "[yellow]{player.name}[white]参加了高考并取得了[accent]{score}分[white]的傲人成绩！ta获得了：[gold]扣除{reward}MDC[white]的奖励！"
                    .with("player" to player, "score" to score, "reward" to reward),
                quite = true
            )
            return@skillBody
        }

        with(trustPoint) { addTrustPoints(uid, reward, "Skill:gaokao") }
        if (score >= 700) {
            val granted = with(playerTitle) { grantTitle(uid, "gaokao_top", "[gold]高考状元", "高考技能奖励") }
            broadcast(
                ("[yellow]{player.name}[white]参加了高考并取得了[accent]{score}分[white]的傲人成绩！" +
                        "ta获得了[gold]{reward}MDC[white]的奖励" +
                        (if (granted) "并取得了[gold][高考状元][white]称号！" else "并再次证明了[gold][高考状元][white]的实力！"))
                    .with("player" to player, "score" to score, "reward" to reward),
                quite = true
            )
        } else {
            broadcast(
                "[yellow]{player.name}[white]参加了高考并取得了[accent]{score}分[white]的成绩！ta获得了[gold]{reward}MDC[white]的奖励！"
                    .with("player" to player, "score" to score, "reward" to reward),
                quite = true
            )
        }
    }
}

command("firetruck", "3级技能：消防车".with(), commands = SkillCommands) {
    aliases = listOf("消防车")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown(300_000))
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        launch(Dispatchers.game) {
            repeat(6) {
                clearNearbyFires(player, 10)
                delay(1_000L)
            }
        }
        broadcastSkill("消防车")
    }
}

command("omg", "3级技能：omg".with(), commands = SkillCommands) {
    attr(SkillPrecheckLevel3); attr(SkillNoPvp)
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        if (!soundEffects.hasFixedSoundEffect("omg")) {
            returnReply("[red]未找到小音效 omg.ogg，请检查服务器 assets/sounds。".with())
        }
        if (!spendSkillCost(player, 15, "omg")) returnReply("[red]MDC不足：omg需要 15 MDC".with())
        val result = soundEffects.playFixedSoundEffect("omg", player.name, ignoreInterval = true)
        if (!result.startsWith("[green]")) player.sendMessage(result)
        broadcastSkill("omg")
    }
}

command("standarddefense", "3级技能：标准预制防线".with(), commands = SkillCommands) {
    aliases = listOf("标准预制防线", "标准防线", "standardDefense")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown())
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        prefabAreaError(player, -2..3, "标准预制防线")?.let { returnReply(it.with()) }
        if (!spendSkillCost(player, 15, "standarddefense")) returnReply("[red]MDC不足：标准预制防线需要 15 MDC".with())
        launch(Dispatchers.game) { buildStandardPrefabDefense(player) }
        broadcastSkill("标准预制防线")
    }
}

command("missileVolley", "3级技能：导弹齐射".with(), commands = SkillCommands) {
    aliases = listOf("导弹齐射", "导弹雨", "missilevolley")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp)
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        val missileType = unitTypeByName("scathe-missile-surge")
            ?: returnReply("[red]未找到单位 scathe-missile-surge，无法释放导弹齐射".with())
        if (!spendSkillCost(player, 10, "missilevolley")) returnReply("[red]MDC不足：导弹齐射需要 10 MDC".with())
        spawnAround(missileType, player, 10, 24f) { it.rotation(Random.nextFloat() * 360f) }
        broadcastSkill("导弹齐射")
    }
}

command("supplyitem", "3级技能：物资补给".with(), commands = SkillCommands) {
    usage = "[物资英文名/本地名]"
    aliases = listOf("物资补给", "指定物资", "itemSupply", "supplyItem")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp)
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        if (arg.isEmpty()) {
            openSupplyItemMenu(player)
            return@skillBody
        }
        val itemText = arg.joinToString(" ")
        val item = resolveSupplyItem(itemText)
            ?: returnReply("[red]未找到物资：$itemText。请直接打开菜单选择当前可用物资。".with())
        val core = player.team().core() ?: returnReply("[red]当前队伍没有核心，无法添加物资。".with())
        if (!spendSkillCost(player, 10, "supplyitem")) returnReply("[red]MDC不足：物资补给需要 10 MDC".with())
        core.items.add(item, 100)
        player.sendMessage("[green]已为本队核心添加 ${item.emoji()} [white]${item.localizedName} [gold]x100[white]。")
        broadcastSkill("物资补给(${item.localizedName}x100)")
    }
}

command("randommaga", "3级技能：随机maga".with(), commands = SkillCommands) {
    aliases = listOf("随机maga", "随机mega", "随机载荷")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown())
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        val block = randomMegaPayloadBlock()
            ?: returnReply("[red]当前内容列表中没有有效建筑方块，无法召唤随机maga。".with())
        if (!spendSkillCost(player, 20, "randommaga")) returnReply("[red]MDC不足：随机maga需要 20 MDC".with())
        summonPayloadMega(player, block)
        broadcast(
            "[cyan]{player.name}[white]召唤了随机maga，载荷为：[accent]{block}[white]！".with(
                "player" to player,
                "block" to block.localizedName
            ),
            quite = true
        )
    }
}

command("nuke", "3级技能：核弹打击".with(), commands = SkillCommands) {
    aliases = listOf("核弹打击", "核弹", "nuclear")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown())
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 20, "nuke")) returnReply("[red]MDC不足：核弹打击需要 20 MDC".with())
        val x = player.mouseX
        val y = player.mouseY
        val tileX = (x / Vars.tilesize).roundToInt()
        val tileY = (y / Vars.tilesize).roundToInt()
        broadcast(
            "[red]{player.name}[white]呼叫了[red]核弹打击[white]，坐标：[accent]{x}, {y}[white]，5秒后命中目标区域！".with(
                "player" to player,
                "x" to tileX,
                "y" to tileY
            ),
            quite = true
        )
        launch(Dispatchers.game) {
            delay(5_000L)
            triggerThoriumExplosion(x, y)
            broadcast("[red]核打击已经抵达！".with(), quite = true)
        }
    }
}

command("refreshskills", "3级技能：刷新技能".with(), commands = SkillCommands) {
    aliases = listOf("刷新技能", "刷新cd", "refreshSkills")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp); attr(SkillCooldown(300_000))
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 100, "refreshskills")) returnReply("[red]MDC不足：刷新技能需要 100 MDC".with())
        SkillCommands.allCooldown.forEach { it.reset(player) }
        broadcastSkill("刷新技能")
    }
}

command("tietie", "3级技能：贴贴".with(), commands = SkillCommands) {
    usage = "[目标3位ID/uuid/名字]"
    aliases = listOf("贴贴")
    attr(SkillPrecheckLevel3); attr(SkillNoPvp)
    skillBody {
        levelError(player, "3")?.let { returnReply("[red]$it".with()) }
        if (arg.isEmpty()) {
            openTietieTargetMenu(player)
            return@skillBody
        }
        val target = resolveOnlinePlayer(arg.joinToString(" "))
            ?: returnReply("[red]未找到在线目标，请使用目标3位ID/uuid/名字，或直接在菜单中选择。".with())
        if (target === player || PlayerData[target].id == PlayerData[player].id) {
            returnReply("[yellow]贴贴至少需要两个人，不能只和自己贴贴。".with())
        }
        if (target.dead()) returnReply("[red]目标处于死亡状态，无法贴贴。".with())
        if (!spendSkillCost(player, 100, "tietie")) returnReply("[red]MDC不足：贴贴需要 100 MDC".with())
        broadcast("[pink]{caster.name}[white]向[pink]{target.name}[white]发送了贴贴请求！".with("caster" to player, "target" to target), quite = true)
        val accepted = askTietieAccept(player, target)
        if (accepted == true) startTietieSuccess(player, target) else startTietieFailure(player, target)
    }
}
