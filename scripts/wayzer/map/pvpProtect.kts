@file:Depends("wayzer/maps", "地图管理")

package wayzer.map

import arc.math.geom.Geometry
import arc.math.geom.Point2
import mindustry.Vars
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.gen.Unit
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import wayzer.MapManager
import wayzer.MapLoadFinishedEvent
import java.time.Duration
import kotlin.math.ceil

val time by config.key(600, "pvp保护时间(单位秒,小于等于0关闭)")

private val warnCooldown = mutableMapOf<String, Long>()
private fun isPvpLike(): Boolean = state.rules.pvp || MapManager.current.mode == Gamemode.pvp

private fun inlineTagValue(name: String, description: String?): String? {
    val withAt = Regex.escape(name)
    val withoutAt = Regex.escape(name.removePrefix("@"))
    val regex = Regex("""\[(?:$withAt|$withoutAt)(?:=([^\]]*))?]""", RegexOption.IGNORE_CASE)
    val match = regex.find(description.orEmpty()) ?: return null
    return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "true"
}

private fun mapTagValue(name: String): String? {
    inlineTagValue(name, runCatching { state.map.description() }.getOrNull())?.let { return it }
    inlineTagValue(name, MapManager.current.description)?.let { return it }
    val aliases = listOf(name, name.removePrefix("@")).distinct()
    aliases.forEach { key ->
        state.rules.tags.get(key)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

private fun configuredProtectSeconds(): Int {
    val raw = mapTagValue("@pvpProtect") ?: return time
    val value = raw.trim().trim('"', '\'').lowercase()
    return when (value) {
        "", "true", "on", "yes", "enable", "enabled" -> time
        "0", "false", "off", "no", "none", "disable", "disabled" -> 0
        else -> value.toIntOrNull()?.coerceAtLeast(0) ?: time
    }
}

private fun coreDst2(core: CoreBuild, x: Float, y: Float): Float {
    val dx = core.x - x
    val dy = core.y - y
    return dx * dx + dy * dy
}

private fun closestCoreTo(x: Float, y: Float): CoreBuild? {
    return state.teams.active
        .mapNotNull { it.cores.minByOrNull { core -> coreDst2(core, x, y) } }
        .minByOrNull { coreDst2(it, x, y) }
}

private fun isEnemyAreaAt(team: Team, x: Float, y: Float): Boolean {
    val closestCore = closestCoreTo(x, y) ?: return false
    val radius = state.rules.enemyCoreBuildRadius
    return closestCore.team != team && (
            state.rules.polygonCoreProtection || coreDst2(closestCore, x, y) < radius * radius
            )
}

val Unit.inEnemyArea: Boolean
    get() = isEnemyAreaAt(team(), x, y)

private fun Unit.ownClosestCore(): CoreBuild? {
    val unit = this
    return team().cores().minByOrNull { unit.dst2(it) }
}

private fun Unit.findSafeTileAround(core: CoreBuild): Point2? {
    for (radius in listOf(8, 12, 16, 24, 32)) {
        val valid = mutableListOf<Point2>()
        Geometry.circle(core.tileX(), core.tileY(), Vars.world.width(), Vars.world.height(), radius) { x, y ->
            val tile = Vars.world.tile(x, y) ?: return@circle
            val wx = x * Vars.tilesize.toFloat()
            val wy = y * Vars.tilesize.toFloat()
            if (canPass(x, y)
                && (!canDrown() || tile.floor().isDeep == false)
                && !isEnemyAreaAt(team(), wx, wy)
            ) {
                valid.add(Point2(x, y))
            }
        }
        valid.randomOrNull()?.let { return it }
    }
    return null
}

private fun Unit.moveBackToOwnArea(): Boolean {
    val core = ownClosestCore() ?: return false
    val tile = findSafeTileAround(core) ?: return false
    set(tile.x * Vars.tilesize.toFloat(), tile.y * Vars.tilesize.toFloat())
    snapInterpolation()
    resetController()
    return true
}

private fun warnPlayer(unit: Unit) {
    val player = unit.player ?: return
    val now = System.currentTimeMillis()
    val key = player.uuid()
    if (now - (warnCooldown[key] ?: 0L) >= 5_000L) {
        player.sendMessage("[red]PVP保护时间，禁止进入敌方区域".with())
        warnCooldown[key] = now
    }
}

private fun startPvpProtect() {
    warnCooldown.clear()
    coroutineContext.cancelChildren()

    var leftTime = configuredProtectSeconds()
    if (!isPvpLike() || leftTime <= 0) return
    loop(Dispatchers.game) {
        delay(1000)
        Groups.unit.forEach {
            if (it.inEnemyArea) {
                warnPlayer(it)
                it.moveBackToOwnArea()
                if (leftTime > 60)
                    it.apply(StatusEffects.unmoving, (leftTime - 60) * 60f)
//                    it.kill()
            }
        }
    }
    launch(Dispatchers.game) {
        broadcast(
            "[yellow]PVP保护时间,禁止在其他基地攻击(持续{time 分钟})".with(
                "time" to Duration.ofSeconds(leftTime.toLong())
            ),
            quite = true
        )
        repeat(leftTime / 60) {
            delay(60_000)
            leftTime -= 60
            broadcast("[yellow]PVP保护时间还剩 {time}分钟".with("time" to ceil(leftTime / 60f)), quite = true)
        }
        delay(leftTime * 1000L)
        broadcast("[yellow]PVP保护时间已结束, 全力进攻吧".with())
        thisScript.coroutineContext.cancelChildren()
    }
}

listen<MapLoadFinishedEvent> { startPvpProtect() }

listen<EventType.ResetEvent> {
    warnCooldown.clear()
    coroutineContext.cancelChildren()
}
