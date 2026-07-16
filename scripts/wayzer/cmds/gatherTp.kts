@file:Depends("wayzer/user/ext/skills", "Gather也算技能")
@file:Depends("wayzer/cmds/unitSelector", "管理单位选择器")

package wayzer.cmds

import arc.math.geom.Geometry
import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.Vars
import mindustry.entities.Units
import mindustry.gen.Player
import mindustry.gen.Unit
import mindustry.world.Tile
import wayzer.user.ext.SkillCooldown
import wayzer.user.ext.SkillNoPvp
import wayzer.user.ext.SkillPrecheck
import wayzer.user.ext.skillBody
import java.time.Duration
import java.time.Instant

PermissionApi.registerDefault("wayzer.ext.gather")
PermissionApi.registerDefault("wayzer.ext.tp", group = "@admin")

var lastPos: Tile? = null
var lastTime: Instant = Instant.MIN

private data class TeleportPoint(
    val x: Float,
    val y: Float,
    val label: String,
)

private fun safePlayerUnit(player: Player): Unit? =
    runCatching { player.unit() }.getOrNull()?.takeIf { it.isValid && !it.dead }

private fun resolveTeleportPoint(input: String, executor: Player?): TeleportPoint? {
    input.split(',', '，').takeIf { it.size == 2 }?.let { parts ->
        parseTeleportCoordinate(parts[0], parts[1], executor)?.let { return it }
    }
    val selection = resolveUnitSelection(input, executor) ?: return null
    val unit = selection.units.firstOrNull { it.isValid && !it.dead } ?: return null
    val suffix = if (selection.units.size > 1) "中的第一个单位" else ""
    return TeleportPoint(unit.x, unit.y, "${selection.label}$suffix")
}

private fun parseTeleportAxis(input: String, axis: Char, executor: Player?): Float? {
    val text = input.trim()
    if (text == "~") {
        val unit = executor?.let { safePlayerUnit(it) } ?: return null
        return (if (axis == 'x') unit.tileX() else unit.tileY()).toFloat()
    }
    return text.toFloatOrNull()
}

private fun parseTeleportCoordinate(xText: String, yText: String, executor: Player?): TeleportPoint? {
    val tileX = parseTeleportAxis(xText, 'x', executor) ?: return null
    val tileY = parseTeleportAxis(yText, 'y', executor) ?: return null
    if (tileX < 0f || tileY < 0f || tileX >= Vars.world.width() || tileY >= Vars.world.height()) return null
    return TeleportPoint(
        tileX * Vars.tilesize,
        tileY * Vars.tilesize,
        "坐标(${tileX}, ${tileY})"
    )
}

private fun teleportUnits(units: List<Unit>, point: TeleportPoint): Int {
    var count = 0
    units.filter { it.isValid && !it.dead }.forEach {
        it.set(point.x, point.y)
        it.snapInterpolation()
        count++
    }
    return count
}

command("gather", "发出集合请求") {
    usage = "[可选说明]"
    aliases = listOf("集合")
    attr(SkillPrecheck)
    attr(SkillNoPvp)
    attr(SkillCooldown(30_000))
    requirePermission("wayzer.ext.gather")
    skillBody {
        if (player.dead() || !player.unit().type.targetable)
            returnReply("[red]当前单位无法使用 集合".with())
        if (Duration.between(lastTime, Instant.now()) < Duration.ofSeconds(10)) {
            returnReply("[red]刚刚有人发起请求,请稍等10s再试".with())
        }
        val message = "[white]\"${arg.firstOrNull() ?: ""}[white]\""
        val tile = player.tileOn() ?: returnReply("[red]请在地图内使用".with())
        lastPos = tile
        lastTime = Instant.now()
        broadcastSkill("集合(${tile.x},${tile.y})")
        broadcast("可输入\"[gold]go[white]\"前往：{message}".with("message" to message), quite = true)
    }
}

private fun teleportSelfToPoint(player: Player, point: TeleportPoint): Boolean {
    val unit = safePlayerUnit(player) ?: return false
    unit.set(point.x, point.y)
    unit.snapInterpolation()
    return true
}

command("tp", "传送到鼠标坐标/地图坐标/玩家/选择器") {
    usage = "空=传送自己到鼠标；<x> <y>=传送自己到地图格坐标，~表示自己当前单位所在轴；<玩家UUID/短ID/名字>=传送自己到玩家；<来源选择器/玩家> <目标玩家/选择器|x y>；选择器支持@e[team=2,unit=mono]"
    attr(ClientOnly)
    requirePermission("wayzer.ext.tp")
    body {
        val player = player!!
        when (arg.size) {
            0 -> {
                if (!teleportSelfToPoint(player, TeleportPoint(player.mouseX, player.mouseY, "鼠标位置"))) {
                    returnReply("[red]你当前没有可传送的有效单位。".with())
                }
                reply("[green]已将自己传送到鼠标位置。".with())
            }

            1 -> {
                val token = arg[0].trim()
                token.split(',', '，').takeIf { it.size == 2 }?.let { parts ->
                    parseTeleportCoordinate(parts[0], parts[1], player)?.let { point ->
                        if (!teleportSelfToPoint(player, point)) {
                            returnReply("[red]你当前没有可传送的有效单位。".with())
                        }
                        reply("[green]已将自己传送到 [white]${point.label}[green]。".with())
                        return@body
                    }
                }
                if (token.startsWith("@")) {
                    val selection = resolveUnitSelection(token, player)
                        ?: returnReply("[red]找不到选择器目标：[white]$token[red]。\n[gray]选择器：${unitSelectorHelpText()}".with())
                    val point = TeleportPoint(player.mouseX, player.mouseY, "鼠标位置")
                    val count = teleportUnits(selection.units, point)
                    if (count == 0) returnReply("[yellow]目标 [white]${selection.label}[yellow] 没有可传送的有效单位。".with())
                    reply("[green]已将 [white]${selection.label}[green] 的 [gold]${count}[green] 个单位传送到鼠标位置。".with())
                } else {
                    val target = findOnlinePlayer(token)
                        ?: returnReply("[red]找不到在线玩家：[white]$token[red]。".with())
                    val targetUnit = safePlayerUnit(target)
                        ?: returnReply("[yellow]目标玩家 [white]{target.name}[yellow] 当前没有有效单位。".with("target" to target))
                    val selfUnit = safePlayerUnit(player) ?: returnReply("[red]你当前没有可传送的有效单位。".with())
                    selfUnit.set(targetUnit.x, targetUnit.y)
                    selfUnit.snapInterpolation()
                    reply("[green]已将自己传送到 [white]{target.name}[green] 身边。".with("target" to target))
                }
            }

            2 -> {
                parseTeleportCoordinate(arg[0], arg[1], player)?.let { point ->
                    if (!teleportSelfToPoint(player, point)) {
                        returnReply("[red]你当前没有可传送的有效单位。".with())
                    }
                    reply("[green]已将自己传送到 [white]${point.label}[green]。".with())
                    return@body
                }

                val sourceText = arg[0].trim()
                val destText = arg[1].trim()
                val source = resolveUnitSelection(sourceText, player)
                    ?: returnReply("[red]找不到传送对象：[white]$sourceText[red]。可用玩家UUID/短ID/#游戏ID/名字或选择器。\n[gray]选择器：${unitSelectorHelpText()}".with())
                val point = resolveTeleportPoint(destText, player)
                    ?: returnReply("[red]找不到传送目标位置：[white]$destText[red]。请使用在线玩家或选择器。".with())
                val count = teleportUnits(source.units, point)
                if (count == 0) returnReply("[yellow]传送对象 [white]${source.label}[yellow] 没有可传送的有效单位。".with())
                reply("[green]已将 [white]${source.label}[green] 的 [gold]${count}[green] 个单位传送到 [white]${point.label}[green]。".with())
            }

            else -> {
                val point = parseTeleportCoordinate(arg[arg.size - 2], arg[arg.size - 1], player)
                    ?: returnReply("[red]无法解析坐标：[white]${arg.takeLast(2).joinToString(" ")}[red]。坐标应为地图格坐标 x y，~ 表示自己当前单位所在轴。".with())
                val sourceText = arg.dropLast(2).joinToString(" ").trim()
                val source = resolveUnitSelection(sourceText, player)
                    ?: returnReply("[red]找不到传送对象：[white]$sourceText[red]。可用玩家UUID/短ID/#游戏ID/名字或选择器。\n[gray]选择器：${unitSelectorHelpText()}".with())
                val count = teleportUnits(source.units, point)
                if (count == 0) returnReply("[yellow]传送对象 [white]${source.label}[yellow] 没有可传送的有效单位。".with())
                reply("[green]已将 [white]${source.label}[green] 的 [gold]${count}[green] 个单位传送到 [white]${point.label}[green]。".with())
            }
        }
    }
}

private fun isSafeGatherTile(unit: Unit, tile: Tile): Boolean {
    if (unit.type.flying) return true
    return unit.canPass(tile.x.toInt(), tile.y.toInt()) &&
            Units.count(tile.worldx(), tile.worldy(), unit.physicSize()) { other ->
                other !== unit && other.isValid && !other.dead && other.isGrounded && other.hitSize > 14.0F
            } == 0
}

private fun findSafeGatherTile(unit: Unit, target: Tile): Tile? {
    if (isSafeGatherTile(unit, target)) return target
    if (unit.type.flying) return target

    var best: Tile? = null
    var bestDistance = Float.MAX_VALUE
    Geometry.circle(target.x.toInt(), target.y.toInt(), Vars.world.width(), Vars.world.height(), 6) { x, y ->
        val tile = Vars.world.tile(x, y) ?: return@circle
        if (!isSafeGatherTile(unit, tile)) return@circle
        val dx = x - target.x.toInt()
        val dy = y - target.y.toInt()
        val distance = (dx * dx + dy * dy).toFloat()
        if (distance < bestDistance) {
            best = tile
            bestDistance = distance
        }
    }
    return best
}

listen<EventType.PlayerChatEvent> {
    val tile = lastPos ?: return@listen
    if (it.message.equals("go", true)) {
        it.player.unit()?.apply {
            val target = findSafeGatherTile(this, tile)
            if (target == null) {
                it.player.sendMessage("[yellow]目标位置无法安全传送")
                return@listen
            }
            set(target)
            snapInterpolation()
        }
    }
}

listen<EventType.ResetEvent> {
    lastPos = null
}
