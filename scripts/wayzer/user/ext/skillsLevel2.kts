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
import mindustry.gen.UnitControlCallPacket
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
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlin.math.roundToInt
import kotlin.random.Random

private val skillsCore = contextScript<Skills>()
private fun levelError(player: Player, required: String): String? = skillsCore.levelError(player, required)
private fun spendSkillCost(player: Player, cost: Int, code: String): Boolean = skillsCore.spendSkillCost(player, cost, code)
private val playerTitle get() = skillsCore.playerTitle
private val skillHealColor: Color get() = skillsCore.skillHealColor
private fun setCoreZone(player: Player, range: IntRange) = skillsCore.setCoreZone(player, range)
private fun unitTypeByName(name: String): UnitType? = skillsCore.unitTypeByName(name)
private fun spawnAround(type: UnitType, player: Player, count: Int, radius: Float = 56f, configure: (mindustry.gen.Unit) -> Unit = {}) =
    skillsCore.spawnAround(type, player, count, radius, configure)
private fun applyStackedStatus(unit: mindustry.gen.Unit, effect: StatusEffect, duration: Float, stacks: Int = 1): Int =
    skillsCore.applyStackedStatus(unit, effect, duration, stacks)

private fun notifyHealthChangedCompat(target: Any) {
    runCatching {
        target.javaClass.methods.firstOrNull { it.name == "healthChanged" && it.parameterCount == 0 }
            ?.invoke(target)
    }
}

private val BOUND_MEGA_TICK_MILLIS = 500L
private val SQUAD_DELIVERY_TIMEOUT_MILLIS = 40_000L
private val boundMegaOwners = ConcurrentHashMap<Int, String>()
private val ownerBoundMegas = ConcurrentHashMap<String, Int>()
private val deliveryQuadIds = ConcurrentHashMap<Int, Boolean>()
private val DAILY_FORTUNE_TITLE_CODE = "daily_fortune_no_disadvantage"
private val DAILY_FORTUNE_TITLE_DISPLAY = "[gold][无不利！]"

private data class SquadDropSpec(
    val type: UnitType,
    val count: Int,
    val configure: (mindustry.gen.Unit) -> kotlin.Unit,
)

private data class DailyFortune(
    val name: String,
    val message: String,
    val grantTitle: Boolean = false,
)

private val dailyFortunes = listOf(
    DailyFortune(
        "大凶",
        "[red]今日你的运势是：大凶！[white]建议今天坐路由器上喵"
    ),
    DailyFortune(
        "凶",
        "[red]今日你的运势是：凶！[white]建议出门佩戴凶兆喵(~"
    ),
    DailyFortune(
        "中吉",
        "[yellow]今日你的运势是：中吉！[white]万事尚可，没什么是过不去的"
    ),
    DailyFortune(
        "吉",
        "[green]今日你的运势是：吉！[white]打开随机变换形态，当个招财猫~"
    ),
    DailyFortune(
        "大吉",
        "[gold]今日你的运势是：大吉！[white]这句话送给你：[gold]自天佑之，吉无不利！",
        grantTitle = true
    ),
)

private fun findUnitById(id: Int): mindustry.gen.Unit? =
    Groups.unit.toList().firstOrNull { it.id() == id }

private fun clearBoundMega(unitId: Int) {
    val ownerUuid = boundMegaOwners.remove(unitId) ?: return
    if (ownerBoundMegas[ownerUuid] == unitId) ownerBoundMegas.remove(ownerUuid)
}

private fun killBoundMega(ownerUuid: String) {
    val unitId = ownerBoundMegas.remove(ownerUuid) ?: return
    boundMegaOwners.remove(unitId)
    findUnitById(unitId)?.takeIf { it.isValid && !it.dead }?.kill()
}

private fun boundMegaOf(ownerUuid: String): mindustry.gen.Unit? =
    ownerBoundMegas[ownerUuid]?.let(::findUnitById)?.takeIf { it.isValid && !it.dead }

private fun summonBoundMega(player: Player): mindustry.gen.Unit {
    val center = player.unit()
    val unit = UnitTypes.mega.create(player.team()).apply {
        set(center?.x ?: player.x, center?.y ?: player.y)
        elevation = 1f
        rotation(center?.rotation ?: 0f)
        add()
    }
    boundMegaOwners[unit.id()] = player.uuid()
    ownerBoundMegas[player.uuid()] = unit.id()
    return unit
}

private fun updateBoundMegas() {
    boundMegaOwners.toList().forEach { (unitId, ownerUuid) ->
        val mega = findUnitById(unitId)
        if (mega == null || !mega.isValid || mega.dead) {
            clearBoundMega(unitId)
            return@forEach
        }
        val owner = Groups.player.toList().firstOrNull { it.uuid() == ownerUuid }
        if (owner == null) {
            clearBoundMega(unitId)
            mega.kill()
            return@forEach
        }
        val otherController = Groups.player.toList().firstOrNull { it.uuid() != ownerUuid && it.unit() === mega }
        if (otherController != null) {
            clearBoundMega(unitId)
            mega.kill()
            owner.sendMessage("[yellow]你的绑定 mega 已被其他玩家抢先附身，已自动销毁。")
            otherController.sendMessage("[yellow]该 mega 已绑定给其他玩家，异常附身已被清理。")
            return@forEach
        }
        if (mega.team() != owner.team()) mega.team(owner.team())
        if (!owner.dead() && owner.unit() !== mega) {
            owner.unit(mega)
        }
    }
}

private fun spawnSquadUnitsAt(team: Team, x: Float, y: Float, specs: List<SquadDropSpec>, radius: Float = 32f) {
    specs.forEach { spec ->
        repeat(spec.count) {
            val angle = Random.nextFloat() * 360f
            val distance = Random.nextFloat() * radius
            spec.type.create(team).apply {
                set(
                    x + Mathf.cosDeg(angle) * distance,
                    y + Mathf.sinDeg(angle) * distance
                )
                spec.configure(this)
                add()
            }
        }
    }
}

private fun launchSquadDeliveryQuad(player: Player, skillName: String, specs: List<SquadDropSpec>) {
    val source = player.unit() ?: return
    val team = player.team()
    val targetX = source.x
    val targetY = source.y
    val angle = Random.nextFloat() * 360f
    val distance = 220f + Random.nextFloat() * 180f
    val maxX = Vars.world.width() * Vars.tilesize.toFloat() - 8f
    val maxY = Vars.world.height() * Vars.tilesize.toFloat() - 8f
    val spawnX = Mathf.clamp(targetX + Mathf.cosDeg(angle) * distance, 8f, maxX.coerceAtLeast(8f))
    val spawnY = Mathf.clamp(targetY + Mathf.sinDeg(angle) * distance, 8f, maxY.coerceAtLeast(8f))
    val quad = UnitTypes.quad.create(team).apply {
        set(spawnX, spawnY)
        elevation = 1f
        rotation(angleTo(targetX, targetY))
        apply(StatusEffects.disarmed, 60f * 60f)
        apply(StatusEffects.shielded, 60f * 10f)
        add()
    }
    deliveryQuadIds[quad.id()] = true

    launch(Dispatchers.game) {
        var delivered = false
        var failReason = "运输机失效"
        var cancelled = false
        try {
            val deadline = System.currentTimeMillis() + SQUAD_DELIVERY_TIMEOUT_MILLIS
            while (quad.isValid && !quad.dead && System.currentTimeMillis() < deadline && Mathf.dst(quad.x, quad.y, targetX, targetY) > 14f) {
                val dist = Mathf.dst(quad.x, quad.y, targetX, targetY)
                if (dist > 0.01f) {
                    quad.vel.set(targetX - quad.x, targetY - quad.y).setLength(10f.coerceAtMost(dist))
                    quad.rotation(quad.angleTo(targetX, targetY))
                }
                delay(50L)
            }
            val alive = quad.isValid && !quad.dead
            val arrived = alive && Mathf.dst(quad.x, quad.y, targetX, targetY) <= 24f
            failReason = when {
                arrived -> failReason
                !alive -> "运输机已死亡"
                System.currentTimeMillis() >= deadline -> "运输机超时未抵达"
                else -> "运输机未能抵达"
            }
            if (arrived) {
                quad.vel.set(0f, 0f)
                spawnSquadUnitsAt(team, quad.x, quad.y, specs)
                Call.effect(Fx.spawn, quad.x, quad.y, 0f, team.color)
                delivered = true
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            deliveryQuadIds.remove(quad.id())
            if (quad.isValid && !quad.dead) {
                quad.elevation = 0f
                quad.kill()
            }
            val con = player.con
            if (!delivered && !cancelled && con != null && !con.hasDisconnected) {
                player.sendMessage("[yellow]$skillName 投放取消：$failReason；MDC 不返还。")
            }
        }
    }
}

// 2级技能：均受 noskill/PVP 影响。
command("shield", "2级技能：护盾".with(), commands = SkillCommands) {
    aliases = listOf("护盾", "血盾")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 2, "shield")) returnReply("[red]MDC不足：护盾需要 2 MDC".with())
        unit.shield(unit.maxHealth)
        broadcastSkill("护盾")
    }
}

command("health", "2级技能：范围治愈".with(), commands = SkillCommands) {
    aliases = listOf("范围治愈")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 2, "health")) returnReply("[red]MDC不足：范围治愈需要 2 MDC".with())
        val radius = 200f
        var healedUnits = 0
        var healedBuilds = 0
        Groups.unit.toList()
            .filter { it.team == unit.team && it.within(unit, radius) && it.damaged() }
            .forEach {
                it.heal((it.maxHealth * 0.2f).coerceAtLeast(25f))
                notifyHealthChangedCompat(it)
                Call.effect(Fx.heal, it.x, it.y, 0f, skillHealColor)
                healedUnits++
            }
        Groups.build.toList()
            .filter { it.team == unit.team && it.dst(unit) < radius && it.damaged() }
            .forEach {
                it.heal((it.maxHealth * 0.2f).coerceAtLeast(25f))
                notifyHealthChangedCompat(it)
                Call.effect(Fx.healBlock, it.x, it.y, it.block.size.toFloat(), skillHealColor)
                healedBuilds++
            }
        Call.effect(Fx.healWave, unit.x, unit.y, radius, skillHealColor)
        player.sendMessage("[green]范围治愈完成：单位 [white]$healedUnits[green]，建筑 [white]$healedBuilds")
        broadcastSkill("范围治愈")
    }
}

command("fullheal", "2级技能：完全痊愈".with(), commands = SkillCommands) {
    aliases = listOf("完全痊愈", "完全治愈", "fullHeal")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 5, "fullheal")) returnReply("[red]MDC不足：完全痊愈需要 5 MDC".with())
        unit.health = unit.maxHealth
        unit.heal(unit.maxHealth)
        Call.effect(Fx.heal, unit.x, unit.y, 0f, skillHealColor)
        player.sendMessage("[green]完全痊愈完成：当前单位已恢复至满血。")
        broadcastSkill("完全痊愈")
    }
}

command("fortune", "2级技能：查看今日运势".with(), commands = SkillCommands) {
    aliases = listOf("查看今日运势", "今日运势", "运势", "dailyFortune")
    attr(SkillPrecheck); attr(SkillNoPvp)
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val uid = PlayerData[player].id
        val today = LocalDate.now().toString()
        val key = "skill.fortune.$uid.lastDate"
        if (MdtStorage.getSetting(key) == today) {
            returnReply("[yellow]今天已经查看过今日运势了，明天再来吧。".with())
        }

        MdtStorage.setSetting(key, today)
        val fortune = dailyFortunes.random()
        player.sendMessage(fortune.message)
        if (fortune.grantTitle) {
            val titleCode = DAILY_FORTUNE_TITLE_CODE
            val titleDisplay = DAILY_FORTUNE_TITLE_DISPLAY
            val granted = with(playerTitle) {
                grantTitle(uid, titleCode, titleDisplay, "今日运势大吉奖励")
            }
            player.sendMessage(
                if (granted) "[gold]你获得了 ${titleDisplay}[gold] 称号，可在 /title 中佩戴。"
                else "[gold]你再次抽到了大吉，${titleDisplay}[gold] 称号已经属于你了。"
            )
        }
        broadcast(
            "[yellow]{player.name}[white]查看了今日运势：[accent]{fortune}[white]。".with(
                "player" to player,
                "fortune" to fortune.name
            ),
            quite = true
        )
    }
}

command("monoMother", "2级技能：递归mono".with(), commands = SkillCommands) {
    aliases = listOf("递归mono", "矿机之母")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(300_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        if (Vars.state.rules.bannedBlocks.contains(Blocks.airFactory)) returnReply("[red]该地图空军工厂已禁封，禁止召唤mono".with())
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 2, "monoMother")) returnReply("[red]MDC不足：递归mono需要 2 MDC".with())
        UnitTypes.mono.create(player.team()).apply {
            abilities = arrayOf(UnitSpawnAbility(UnitTypes.mono, 100f, x, y))
            set(unit.x, unit.y)
            add()
            launch(Dispatchers.game) {
                delay(50_000)
                if (isValid) kill()
            }
        }
        broadcast("[yellow]{player.name}[white]召唤了一个[green]递归mono[white]！".with("player" to player), quite = true)
    }
}

command("lowwallKiller", "2级技能：墙壁粉碎者".with(), commands = SkillCommands) {
    aliases = listOf("墙壁粉碎者", "削弱粉碎者")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val tile = player.unit()?.tileOn() ?: returnReply("[red]无法获取脚下位置".with())
        val block = tile.block()
        if (block !is Wall && block !is StaticWall) returnReply("[yellow]脚下不是墙壁/天然墙".with())
        tile.setNet(Blocks.air)
        broadcastSkill("墙壁粉碎者")
    }
}

command("sourcelottery", "2级技能：欧皇物品源".with(), commands = SkillCommands) {
    aliases = listOf("欧皇物品源", "物品源抽奖", "sourceLottery")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val tile = player.unit()?.tileOn() ?: returnReply("[red]无法获取脚下位置".with())
        if (tile.block() != Blocks.air) returnReply("[red]脚下已有方块，不能抽取物品源".with())
        if (!spendSkillCost(player, 2, "sourcelottery")) returnReply("[red]MDC不足：欧皇物品源需要 2 MDC".with())
        val block = if (Random.nextInt(100) == 0) Blocks.itemSource else Blocks.sorter
        tile.setNet(block, player.team(), 0)
        broadcastSkill(if (block == Blocks.itemSource) "欧皇物品源" else "分类器安慰奖")
    }
}

command("coreshard", "2级技能：小伙子,来点读品？".with(), commands = SkillCommands) {
    aliases = listOf("小伙子来点读品", "读品")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val tile = player.unit()?.tileOn() ?: returnReply("[red]无法获取脚下位置".with())
        if (tile.block() != Blocks.air) returnReply("[red]脚下已有方块".with())
        if (!spendSkillCost(player, 2, "coreshard")) returnReply("[red]MDC不足：读品需要 2 MDC".with())
        tile.setNet(Blocks.microProcessor, player.team(), 0)
        broadcastSkill("小伙子,来点读品？")
    }
}

command("coreZone", "2级技能：核心区".with(), commands = SkillCommands) {
    aliases = listOf("核心区")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 2, "coreZone")) returnReply("[red]MDC不足：核心区需要 2 MDC".with())
        setCoreZone(player, -1..1)
        broadcastSkill("核心区")
    }
}

command("flying", "2级技能：飞起".with(), commands = SkillCommands) {
    aliases = listOf("飞起")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 2, "flying")) returnReply("[red]MDC不足：飞起需要 2 MDC".with())
        unit.apply { elevation = 1f; update() }
        broadcastSkill("飞起")
    }
}

command("landing", "2级技能：坠机".with(), commands = SkillCommands) {
    aliases = listOf("坠机", "降落", "落地")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 10, "landing")) returnReply("[red]MDC不足：坠机需要 10 MDC".with())
        unit.apply { elevation = 0f; update() }
        broadcastSkill("坠机")
    }
}

command("runfaster", "2级技能：你跑不过我你信不信".with(), commands = SkillCommands) {
    aliases = listOf("你跑不过我你信不信", "跑不过我")
    attr(SkillPrecheck); attr(SkillNoPvp)
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 20, "runfaster")) returnReply("[red]MDC不足：你跑不过我你信不信需要 20 MDC".with())
        // 原版 apply 会合并同名 StatusEffect；这里直接追加 StatusEntry，让 fast 实际叠 3 层。
        applyStackedStatus(unit, StatusEffects.fast, 120f * 60f, stacks = 3)
        val name = player.name
        broadcast("[yellow]{player.name}[white]：你跑不过我你信不信".with("player" to player), quite = true)
        launch(Dispatchers.game) {
            delay(120_000L)
            if (unit.isValid && !unit.dead) {
                unit.kill()
                broadcast("[yellow]$name[white]心脏不太好，猝死力（悲）".with(), quite = true)
            }
        }
    }
}

command("boundmega", "2级技能：绑定mega".with(), commands = SkillCommands) {
    aliases = listOf("绑定mega", "绑定maga", "专属mega")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown())
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 5, "boundmega")) returnReply("[red]MDC不足：绑定mega需要 5 MDC".with())
        boundMegaOf(player.uuid())?.let {
            player.unit(it)
            player.sendMessage("[cyan]你的绑定 mega 仍然存活，已重新尝试附身。")
            return@skillBody
        }
        val mega = summonBoundMega(player)
        player.unit(mega)
        broadcast("[cyan]{player.name}[white]召唤并绑定了一只专属 [cyan]mega[white]！".with("player" to player), quite = true)
    }
}

command("laststand", "2级技能：拼死一搏".with(), commands = SkillCommands) {
    aliases = listOf("拼死一搏", "殊死一搏")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(300_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 5, "laststand")) returnReply("[red]MDC不足：拼死一搏需要 5 MDC".with())
        unit.shield(unit.maxHealth)
        applyStackedStatus(unit, StatusEffects.overclock, 20f * 60f, stacks = 5)
        val name = player.name
        broadcast("[yellow]{player.name}[white]发动了[red]拼死一搏[white]，20秒后当前单位将死亡！".with("player" to player), quite = true)
        launch(Dispatchers.game) {
            delay(20_000L)
            if (unit.isValid && !unit.dead) {
                unit.kill()
                broadcast("[red]$name[white]的拼死一搏结束了。".with(), quite = true)
            }
        }
    }
}

command("rocket", "2级技能：空对地导弹".with(), commands = SkillCommands) {
    aliases = listOf("空对地导弹", "导弹")
    attr(SkillPrecheck); attr(SkillNoPvp)
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        val old = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        if (!spendSkillCost(player, 2, "rocket")) returnReply("[red]MDC不足：空对地导弹需要 2 MDC".with())
        val missileType = unitTypeByName("scathe-missile-phase") ?: UnitTypes.elude
        if (missileType == UnitTypes.elude) player.sendMessage("[yellow]未找到单位 scathe-missile-phase，已临时使用 elude。")
        val missile = missileType.create(player.team()).apply {
            set(old.x, old.y)
            apply(StatusEffects.overdrive, 20f * 60f)
            add()
        }
        player.unit(missile)
        broadcastSkill("空对地导弹")
    }
}

command("decisiveSquad", "2级技能：决胜中队".with(), commands = SkillCommands) {
    aliases = listOf("决胜中队")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 6, "decisiveSquad")) returnReply("[red]MDC不足：决胜中队需要 6 MDC".with())
        spawnAround(UnitTypes.zenith, player, 5) {
            it.apply(StatusEffects.shielded, 60f * 10f)
            it.addItem(Items.blastCompound, 60)
        }
        broadcastSkill("决胜中队")
    }
}

command("anvilSquad", "2级技能：铁砧小队".with(), commands = SkillCommands) {
    aliases = listOf("铁砧小队")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 6, "anvilSquad")) returnReply("[red]MDC不足：铁砧小队需要 6 MDC".with())
        launchSquadDeliveryQuad(
            player,
            "铁砧小队",
            listOf(
                SquadDropSpec(UnitTypes.locus, 2) { it.apply(StatusEffects.shielded, 60f * 10f) },
                SquadDropSpec(UnitTypes.stell, 5) { it.apply(StatusEffects.shielded, 60f * 10f) },
            )
        )
        broadcastSkill("铁砧小队")
    }
}

command("hammerSquad", "2级技能：铁锤小队".with(), commands = SkillCommands) {
    aliases = listOf("铁锤小队")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        levelError(player, "2")?.let { returnReply("[red]$it".with()) }
        if (!spendSkillCost(player, 6, "hammerSquad")) returnReply("[red]MDC不足：铁锤小队需要 6 MDC".with())
        launchSquadDeliveryQuad(
            player,
            "铁锤小队",
            listOf(
                SquadDropSpec(UnitTypes.spiroct, 3) { it.apply(StatusEffects.overdrive, 60f * 10f) },
                SquadDropSpec(UnitTypes.mace, 5) { it.apply(StatusEffects.overdrive, 60f * 10f) },
                SquadDropSpec(UnitTypes.atrax, 5) { it.apply(StatusEffects.overdrive, 60f * 10f) },
            )
        )
        broadcastSkill("铁锤小队")
    }
}

onEnable {
    launch(Dispatchers.game) {
        while (true) {
            delay(BOUND_MEGA_TICK_MILLIS)
            updateBoundMegas()
        }
    }
}

listenPacket2Server<UnitControlCallPacket> { con, packet ->
    val unit = packet.unit ?: return@listenPacket2Server true
    if (deliveryQuadIds.containsKey(unit.id())) {
        Call.announce(con, "[cyan]这是投放小队运输机，无法附身。")
        return@listenPacket2Server false
    }
    val ownerUuid = boundMegaOwners[unit.id()] ?: return@listenPacket2Server true
    val controller = con.player ?: return@listenPacket2Server false
    if (controller.uuid() == ownerUuid) {
        true
    } else {
        Call.announce(con, "[cyan]这只 mega 已经绑定给其他玩家，无法附身。")
        false
    }
}

listen<EventType.PlayerLeave> {
    killBoundMega(it.player.uuid())
}

listen<EventType.UnitDestroyEvent> {
    clearBoundMega(it.unit.id())
    deliveryQuadIds.remove(it.unit.id())
}

listen<EventType.WorldLoadEvent> {
    boundMegaOwners.clear()
    ownerBoundMegas.clear()
    deliveryQuadIds.clear()
}

listen<EventType.ResetEvent> {
    boundMegaOwners.clear()
    ownerBoundMegas.clear()
    deliveryQuadIds.clear()
}

onDisable {
    boundMegaOwners.keys.toList().forEach { id -> findUnitById(id)?.takeIf { it.isValid && !it.dead }?.kill() }
    deliveryQuadIds.keys.toList().forEach { id -> findUnitById(id)?.takeIf { it.isValid && !it.dead }?.kill() }
    boundMegaOwners.clear()
    ownerBoundMegas.clear()
    deliveryQuadIds.clear()
}
