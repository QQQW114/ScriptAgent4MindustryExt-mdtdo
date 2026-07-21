@file:Depends("wayzer", "159核心机生成与重同步兼容")

package wayzer.reGrief

import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Player
import mindustry.gen.PlayerSpawnCallPacket
import mindustry.gen.Syncc
import mindustry.gen.Unit
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

name = "159核心机重生兼容"

private val lastControlledUnit = ConcurrentHashMap<String, Unit>()
private val respawnGeneration = ConcurrentHashMap<String, Int>()
private val retryDelays = longArrayOf(0L, 1_000L, 3_000L, 8_000L)
private val reliableCustomSnapshotMethod = Vars.netServer.javaClass.methods.firstOrNull {
    it.name == "writeCustomEntitySnapshot" && it.parameterTypes.size == 3
}

private fun nextGeneration(player: Player): Int =
    respawnGeneration.merge(player.uuid(), 1, Int::plus) ?: 1

private fun syncSpawnedCoreUnit(player: Player, unit: Unit, reliableSnapshot: Boolean) {
    val con = player.con ?: return
    if (con.kicked || con.hasDisconnected || !unit.isValid || unit.dead || !unit.spawnedByCore) return
    val core = player.bestCore() ?: return

    try {
        // PlayerSpawn 是可靠包，用于保证客户端最终收到“该玩家已从核心生成”的关键状态。
        con.send(PlayerSpawnCallPacket().apply {
            tile = core.tile
            this.player = player
        }, true)
        val entities = listOf<Syncc>(unit, player)
        // B480自定义补丁允许首份Unit -> Player实体快照走可靠TCP，后续仍用UDP快速重试。
        // 旧JAR缺少接口时安全回退原版不可靠快照，不影响脚本加载。
        if (reliableSnapshot && reliableCustomSnapshotMethod != null) {
            reliableCustomSnapshotMethod.invoke(Vars.netServer, player, entities, true)
        } else {
            Vars.netServer.writeCustomEntitySnapshot(player, entities)
        }
    } catch (e: Throwable) {
        logger.log(Level.WARNING, "159核心机定向同步失败 player=${player.plainName()} unit=${unit.type.name}", e)
    }
}

private fun scheduleCoreRepair(player: Player, allowSpawn: Boolean, reason: String) {
    val key = player.uuid()
    val generation = nextGeneration(player)
    launch(Dispatchers.game) {
        var previousDelay = 0L
        for (delayAt in retryDelays) {
            delay((delayAt - previousDelay).coerceAtLeast(0L))
            previousDelay = delayAt
            if (respawnGeneration[key] != generation) return@launch
            val con = player.con ?: return@launch
            if (!con.isConnected || con.kicked || con.hasDisconnected) return@launch

            var unit = player.unit()
            if ((unit == null || !unit.isValid || unit.dead) && allowSpawn && player.bestCore() != null) {
                runCatching { player.checkSpawn() }.onFailure {
                    logger.warning("核心机恢复 checkSpawn 失败 player=${player.plainName()} reason=$reason: ${it.message}")
                }
                unit = player.unit()
            }
            if (unit != null && unit.isValid && !unit.dead && unit.spawnedByCore) {
                syncSpawnedCoreUnit(player, unit, reliableSnapshot = delayAt == 0L)
            } else if (!allowSpawn) {
                return@launch
            }
        }
    }
}

listen<EventType.UnitChangeEvent> { event ->
    val player = event.player ?: return@listen
    val key = player.uuid()
    val current = event.unit
    if (current != null) {
        lastControlledUnit[key] = current
        if (current.spawnedByCore) scheduleCoreRepair(player, allowSpawn = false, reason = "核心单位变化")
        return@listen
    }

    val previous = lastControlledUnit.remove(key) ?: return@listen
    // 仅在原附身单位仍存活时把 null 视为主动取消附身；正常死亡仍遵循原版死亡延迟。
    if (previous.isValid && !previous.dead) {
        scheduleCoreRepair(player, allowSpawn = true, reason = "主动取消附身")
    }
}

listen<EventType.PlayerConnectionConfirmed> {
    // 同时覆盖首次进服与音乐/杂交/CP触发的内部完整重同步。
    scheduleCoreRepair(it.player, allowSpawn = true, reason = "世界同步确认")
}

listen<EventType.PlayerJoin> {
    scheduleCoreRepair(it.player, allowSpawn = true, reason = "玩家加入")
}

listen<EventType.PlayerLeave> {
    val key = it.player.uuid()
    lastControlledUnit.remove(key)
    respawnGeneration.remove(key)
}

listen<EventType.ResetEvent> {
    lastControlledUnit.clear()
    respawnGeneration.clear()
}

onDisable {
    lastControlledUnit.clear()
    respawnGeneration.clear()
}
