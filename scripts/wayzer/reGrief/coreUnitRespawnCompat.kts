@file:Depends("wayzer", "159取消附身与确认后核心机引用兼容")

package wayzer.reGrief

import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Unit
import java.util.concurrent.ConcurrentHashMap

name = "159核心机重生兼容"

private val lastControlledUnit = ConcurrentHashMap<String, Unit>()
private val respawnGeneration = ConcurrentHashMap<String, Int>()
private val repairStartDelayMillis by config.key(120L, "异常缺失核心机首次检查延迟(ms)")

private fun nextGeneration(key: String): Int =
    respawnGeneration.merge(key, 1, Int::plus) ?: 1

private fun connectionReady(player: Player): Boolean {
    val con = player.con ?: return false
    return con.isConnected && con.hasConnected && !con.kicked && !con.hasDisconnected &&
            !con.determiningAssets && !con.receivingAssets
}

/**
 * 主动取消附身 / 客户端完成世界确认后修复核心机引用。
 *
 * B485 起不再发送定向自定义实体快照（`NetServer.writeCustomEntitySnapshot` 已从 MindustryX 移除）；
 * 核心机引用由原版全局实体快照（`Administration.Config.snapshotInterval`）与 `checkSpawn()` 自然恢复。
 * 不把实体快照改成可靠 TCP：TCP 与 UDP 没有跨通道时序保证，拥塞后到达的旧可靠快照会把已附身的玩家拉回旧核心机/旧位置。
 */
private fun scheduleCoreRepair(player: Player, generation: Int, reason: String) {
    val key = player.uuid()
    launch(Dispatchers.game) {
        delay(repairStartDelayMillis.coerceAtLeast(0L))
        if (respawnGeneration[key] != generation || !connectionReady(player)) return@launch

        val unit = player.unit()
        if (unit == null || !unit.isValid || unit.dead) {
            if (player.bestCore() == null) return@launch
            runCatching { player.checkSpawn() }.onFailure {
                logger.warning("159核心机恢复 checkSpawn 失败 player=${player.plainName()} reason=$reason: ${it.message}")
            }
            // checkSpawn() 会同步触发 UnitChangeEvent；核心单位变化不会自动开新修复轮。
            if (respawnGeneration[key] != generation) return@launch
        }
    }
}

listen<EventType.UnitChangeEvent> { event ->
    val player = event.player ?: return@listen
    val key = player.uuid()
    val current = event.unit
    if (current != null) {
        lastControlledUnit[key] = current
        // 附身任何非核心单位都立即作废旧修复，防止旧的核心机恢复继续生效。
        if (!current.spawnedByCore) nextGeneration(key)
        return@listen
    }

    val generation = nextGeneration(key)
    val previous = lastControlledUnit.remove(key) ?: return@listen
    // 仅在原附身单位仍存活时把 null 视为主动取消附身；正常死亡仍遵循原版死亡延迟。
    if (previous.isValid && !previous.dead) {
        scheduleCoreRepair(player, generation, reason = "主动取消附身")
    }
}

listen<EventType.PlayerConnectionConfirmed> { event ->
    val player = event.player
    // 确认世界后，如果服务端单位为空或已有有效核心机，按需触发核心机恢复；
    // 核心引用由原版全局实体快照与 checkSpawn() 自然复原，附身其他单位后不会误恢复旧核心状态。
    val current = player.unit()
    val missing = current == null && player.bestCore() != null
    val validCore = current != null && current.isValid && !current.dead && current.spawnedByCore
    if (!missing && !validCore) return@listen
    val key = player.uuid()
    scheduleCoreRepair(
        player,
        nextGeneration(key),
        reason = if (missing) "世界确认后服务端单位为空" else "世界确认后核心机定向恢复",
    )
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

onEnable {
    // 支持在线热重载：预先记住当前控制单位，否则已在线玩家下一次取消附身时没有 previous 可用。
    Groups.player.forEach { player ->
        player.unit()?.let { lastControlledUnit[player.uuid()] = it }
    }
}

onDisable {
    lastControlledUnit.clear()
    respawnGeneration.clear()
}
