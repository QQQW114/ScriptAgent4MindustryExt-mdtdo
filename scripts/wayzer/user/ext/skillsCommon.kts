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
private fun spawnAround(type: UnitType, player: Player, count: Int, radius: Float = 56f, configure: (mindustry.gen.Unit) -> Unit = {}) =
    skillsCore.spawnAround(type, player, count, radius, configure)
private fun placeBlockAtPlayer(player: Player, block: mindustry.world.Block, requireAir: Boolean = false): Boolean =
    skillsCore.placeBlockAtPlayer(player, block, requireAir)
private fun prefabAreaError(player: Player, range: IntRange, displayName: String): String? =
    skillsCore.prefabAreaError(player, range, displayName)
private suspend fun buildBasicPrefabDefense(player: Player) = skillsCore.buildBasicPrefabDefense(player)
private fun clearNearbyFires(player: Player, radiusTiles: Int = 10): Int = skillsCore.clearNearbyFires(player, radiusTiles)

private fun coreOrReply(player: Player): mindustry.gen.Building? = player.team().core()

// 通用技能：均受 noskill/PVP 影响，无消耗，统一120秒冷却。
command("clearSelf", "通用技能：紫砂".with(), commands = SkillCommands) {
    aliases = listOf("紫砂")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        unit.kill()
        broadcastSkill("紫砂")
    }
}

command("kill", "通用技能：自爆".with(), commands = SkillCommands) {
    aliases = listOf("自爆")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        val damage = unit.maxHealth / 10f
        unit.kill()
        Groups.unit.filter { it.team != unit.team && it.dst(unit) < 80f }.forEach { it.damage(damage) }
        Groups.build.filter { it.team != unit.team && it.dst(unit) < 80f }.forEach { it.damage(damage) }
        broadcastSkill("自爆")
    }
}

command("cola", "通用技能：紫薇".with(), commands = SkillCommands) {
    aliases = listOf("紫薇", "生可乐")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        val duration = 10_000f
        unit.damage(unit.maxHealth * 0.1f)
        unit.apply(StatusEffects.overclock, duration)
        unit.apply(StatusEffects.shielded, duration)
        unit.apply(StatusEffects.fast, duration)
        broadcastSkill("紫薇")
    }
}

command("heal", "通用技能：自疗".with(), commands = SkillCommands) {
    aliases = listOf("自疗", "治疗")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        unit.heal(1000f)
        Call.effect(Fx.heal, unit.x, unit.y, 0f, Color.green)
        broadcastSkill("自疗")
    }
}

command("copper", "通用技能：生锈的铜".with(), commands = SkillCommands) {
    aliases = listOf("生锈的铜", "铜矿")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        val core = coreOrReply(player) ?: returnReply("[red]当前队伍没有核心".with())
        core.items.add(Items.copper, 114)
        broadcastSkill("生锈的铜")
    }
}

command("summonpoly", "通用技能：召唤poly".with(), commands = SkillCommands) {
    aliases = listOf("召唤poly", "poly")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        spawnAround(UnitTypes.poly, player, 1, 8f)
        broadcastSkill("召唤poly")
    }
}

command("summonunloader", "通用技能：召唤装卸器".with(), commands = SkillCommands) {
    aliases = listOf("召唤装卸器", "装卸器", "unloader")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(300_000))
    skillBody {
        if (!placeBlockAtPlayer(player, Blocks.ductUnloader, requireAir = true)) {
            returnReply("[red]脚下已有方块，无法放置装卸器".with())
        }
        broadcastSkill("召唤装卸器")
        player.sendMessage("[red]你不会真的以为我会给你一个赛普罗的装卸器吧")
    }
}

command("illuminator", "通用技能：照明器".with(), commands = SkillCommands) {
    aliases = listOf("照明器", "灯")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        if (!placeBlockAtPlayer(player, Blocks.illuminator, requireAir = true)) {
            returnReply("[red]脚下已有方块，无法放置照明器".with())
        }
        broadcastSkill("照明器")
    }
}

command("basicdefense", "通用技能：初级预制防线".with(), commands = SkillCommands) {
    aliases = listOf("初级预制防线", "初级防线", "basicDefense")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown())
    skillBody {
        prefabAreaError(player, -1..2, "初级预制防线")?.let { returnReply(it.with()) }
        launch(Dispatchers.game) { buildBasicPrefabDefense(player) }
        broadcastSkill("初级预制防线")
    }
}

command("extinguish", "通用技能：灭火".with(), commands = SkillCommands) {
    aliases = listOf("灭火", "putfire")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(120_000))
    skillBody {
        val count = clearNearbyFires(player, 10)
        player.sendMessage("[green]已扑灭周围10格火焰：[white]$count")
        broadcastSkill("灭火")
    }
}

command("disarm", "通用技能：缴械".with(), commands = SkillCommands) {
    aliases = listOf("缴械", "自缴械")
    attr(SkillPrecheck); attr(SkillNoPvp); attr(SkillCooldown(300_000))
    skillBody {
        val unit = player.unit() ?: returnReply("[red]无法获取当前单位".with())
        unit.apply(StatusEffects.disarmed, 5f * 60f * 60f)
        broadcastSkill("缴械")
    }
}
