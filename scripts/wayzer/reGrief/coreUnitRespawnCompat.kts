@file:Depends("wayzer", "159取消附身与确认后核心机引用兼容")

package wayzer.reGrief

import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Syncc
import mindustry.gen.Unit
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

name = "159核心机重生兼容"

private val lastControlledUnit = ConcurrentHashMap<String, Unit>()
private val respawnGeneration = ConcurrentHashMap<String, Int>()
private val repairStartDelayMillis by config.key(120L, "异常缺失核心机首次检查延迟(ms)")
private val secondSnapshotDelayMillis by config.key(380L, "核心机UDP定向快照第二次间隔(ms)")

private fun nextGeneration(key: String): Int =
    respawnGeneration.merge(key, 1, Int::plus) ?: 1

private fun connectionReady(player: Player): Boolean {
    val con = player.con ?: return false
    return con.isConnected && con.hasConnected && !con.kicked && !con.hasDisconnected &&
            !con.determiningAssets && !con.receivingAssets
}

/**
 * 只用原版 UDP 快照按 Unit -> Player 顺序修复引用。
 *
 * 不能把 EntitySnapshotCallPacket 改为可靠 TCP：TCP 与新的 UDP 快照没有跨通道顺序，
 * 上行拥塞时延迟的可靠旧快照会在玩家已附身其他单位后才到达，造成回核心/位置闪回。
 */
private fun syncCoreReference(player: Player, expected: Unit, generation: Int, reason: String): Boolean {
    val key = player.uuid()
    if (respawnGeneration[key] != generation || !connectionReady(player)) return false
    if (player.unit() !== expected || !expected.isValid || expected.dead || !expected.spawnedByCore) return false

    return try {
        Vars.netServer.writeCustomEntitySnapshot(player, listOf<Syncc>(expected, player))
        true
    } catch (e: Throwable) {
        logger.log(
            Level.WARNING,
            "159核心机UDP定向同步失败 player=${player.plainName()} unit=${expected.type.name}#${expected.id()} reason=$reason",
            e,
        )
        false
    }
}

private fun scheduleCoreRepair(player: Player, generation: Int, reason: String) {
    val key = player.uuid()
    launch(Dispatchers.game) {
        delay(repairStartDelayMillis.coerceAtLeast(0L))
        if (respawnGeneration[key] != generation || !connectionReady(player)) return@launch

        var unit = player.unit()
        if (unit == null || !unit.isValid || unit.dead) {
            if (player.bestCore() == null) return@launch
            runCatching { player.checkSpawn() }.onFailure {
                logger.warning("159核心机恢复 checkSpawn 失败 player=${player.plainName()} reason=$reason: ${it.message}")
            }
            // checkSpawn() 会同步触发 UnitChangeEvent。核心单位变化不会自动开新修复轮，
            // 但仍需在发包前再次检查 generation 和当前单位身份。
            if (respawnGeneration[key] != generation) return@launch
            unit = player.unit()
        }

        val expected = unit?.takeIf { it.isValid && !it.dead && it.spawnedByCore } ?: return@launch
        if (!syncCoreReference(player, expected, generation, reason)) return@launch

        // 再补一份小型 UDP 快照以容忍丢包；如果玩家已换成任何其他单位就立即取消。
        delay(secondSnapshotDelayMillis.coerceAtLeast(0L))
        syncCoreReference(player, expected, generation, reason)
    }
}

listen<EventType.UnitChangeEvent> { event ->
    val player = event.player ?: return@listen
    val key = player.uuid()
    val current = event.unit
    if (current != null) {
        lastControlledUnit[key] = current
        // 附身任何非核心单位都立即作废旧修复，防止旧的核心机快照继续发送。
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
    // 确认世界后，如果服务端已有有效核心机，也补发两份小型 UDP 快照；这能复原客户端刚清空实体后丢失的核心引用。
    // 仅对当前核心单位发包，且发包前会复核单位身份；附身其他单位后不会把旧核心状态发出。
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
