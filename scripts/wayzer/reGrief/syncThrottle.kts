@file:Depends("wayzer/map/serverPressure", "服务器压力判断")

package wayzer.reGrief

import arc.Core
import arc.struct.IntSeq
import arc.util.Time
import arc.util.io.ReusableByteOutStream
import arc.util.io.Writes
import coreLibrary.lib.util.reflectDelegate
import mindustry.Vars.state
import mindustry.Vars.universe
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.UnitDestroyCallPacket
import mindustry.logic.GlobalVars
import mindustry.net.NetConnection
import mindustry.world.modules.ItemModule
import wayzer.map.ServerPressure
import java.io.DataOutputStream

name = "实验性同步频率限制"

private val pressure = contextScript<ServerPressure>()

private val hackOffset = 1_000_000L
private val syncThrottleEnabled by config.key(true, "启用实验性实体同步频率限制；仅在服务器压力同步限制等级>0时实际接管实体同步")
private val minIntervalMillis by config.key(120L, "最低同步限制间隔(ms)")
private val maxIntervalMillis by config.key(650L, "最高同步限制间隔(ms)")
private val stateBroadcastCooldownMillis by config.key(60_000L, "同步限制状态播报最小间隔(ms)")
private val unitChangeSnapshotCooldownMillis by config.key(120L, "单位生成/销毁触发额外同步最小间隔(ms)")
private val pendingUnitDestroyLimit by config.key(512, "同步限制保留单位销毁事件上限")
private val hiddenSnapshotRefreshMillis by config.key(1_000L, "同步限制下隐藏实体列表最小刷新间隔(ms)")
private val sendCostWarnMillis by config.key(120L, "同步限制单玩家快照发送耗时告警阈值(ms)")
private val sendCostWarnCooldownMillis by config.key(30_000L, "同步限制耗时告警冷却(ms)")

private data class HiddenSnapshotCache(
    val ids: Set<Int>,
    val sentAt: Long,
)

private val syncStream = ReusableByteOutStream()
private val dataStream = DataOutputStream(syncStream)
private val throttledPlayers = mutableSetOf<String>()
private val pendingDestroyedUnitIds = mutableSetOf<Int>()
private val hiddenSnapshotCache = mutableMapOf<String, HiddenSnapshotCache>()
private var warnedLevel = 0
private var lastStateBroadcastMillis = 0L
private var forceUnitChangeSnapshot = false
private var lastUnitChangeSnapshotMillis = 0L
private var lastLoggedInterval = 0L
private var lastSendCostWarnMillis = 0L

private val ItemModule.items: IntArray by reflectDelegate()
private val syncTimeField = runCatching { NetConnection::class.java.getField("syncTime") }.getOrNull()
private val snapshotsSentField = runCatching { NetConnection::class.java.getField("snapshotsSent") }.getOrNull()
private val stateSnapshotWithConnection = Call::class.java.methods.firstOrNull { method ->
    method.name == "stateSnapshot" &&
            method.parameterTypes.size == 11 &&
            method.parameterTypes.firstOrNull() == NetConnection::class.java
}
private val syncHiddenAcceptsPlayer = runCatching {
    Class.forName("mindustry.gen.Syncc").methods.any { method ->
        method.name == "isSyncHidden" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Player::class.java
    }
}.getOrDefault(false)
private val throttleRuntimeSupported =
    syncTimeField != null && snapshotsSentField != null && stateSnapshotWithConnection != null && syncHiddenAcceptsPlayer

private fun getSyncTimeCompat(con: NetConnection): Long = syncTimeField?.getLong(con) ?: 0L
private fun setSyncTimeCompat(con: NetConnection, value: Long) {
    syncTimeField?.setLong(con, value)
}
private fun addSyncTimeCompat(con: NetConnection, delta: Long) {
    syncTimeField?.setLong(con, getSyncTimeCompat(con) + delta)
}
private fun incrementSnapshotsSentCompat(con: NetConnection) {
    snapshotsSentField?.setInt(con, snapshotsSentField.getInt(con) + 1)
}

private fun targetInterval(): Long =
    if (!syncThrottleEnabled || !throttleRuntimeSupported) 0L else with(pressure) { throttleIntervalMillis() }
        .let { if (it <= 0L) 0L else it.coerceIn(minIntervalMillis, maxIntervalMillis) }

private fun ensurePlayerThrottled(player: Player) {
    val con = player.con ?: return
    if (!con.isConnected) return
    val id = player.uuid()
    if (throttledPlayers.add(id)) {
        addSyncTimeCompat(con, hackOffset)
    }
}

private fun restorePlayer(player: Player) {
    val con = player.con ?: return
    if (throttledPlayers.remove(player.uuid())) {
        addSyncTimeCompat(con, -hackOffset)
    }
    hiddenSnapshotCache.remove(player.uuid())
}

private fun restoreAll() {
    Groups.player.forEach { restorePlayer(it) }
    throttledPlayers.clear()
    warnedLevel = 0
    pendingDestroyedUnitIds.clear()
    hiddenSnapshotCache.clear()
    forceUnitChangeSnapshot = false
}

private fun broadcastState(message: String, force: Boolean = false) {
    val now = Time.millis()
    if (!force && now - lastStateBroadcastMillis < stateBroadcastCooldownMillis.coerceAtLeast(0L)) return
    lastStateBroadcastMillis = now
    broadcast(message.with())
}

private fun sendState(player: Player) {
    syncStream.reset()
    state.teams.present.select { it.cores.size > 0 }.run {
        dataStream.writeByte(size)
        val dataWrites = Writes.get(dataStream)
        forEach { team ->
            val core = team.cores.first()
            dataStream.writeByte(team.team.id)
            if (!state.rules.pvp || team.team == player.team() || player.team() == Team.get(255)) {
                core.items.write(dataWrites)
            } else {
                val items = core.items.items
                dataStream.writeShort(items.count { it > 0 })
                for (i in items.indices) {
                    if (items[i] > 0) {
                        dataStream.writeShort(i)
                        dataStream.writeInt(items[i].coerceAtMost(100))
                    }
                }
            }
        }
    }
    dataStream.flush()

    val tps = Core.graphics.framesPerSecond.coerceAtMost(255).toByte()
    val con = player.con ?: return
    with(state) {
        stateSnapshotWithConnection?.invoke(
            null, con, wavetime, wave, enemies, isPaused, gameOver, universe.seconds(),
            tps, GlobalVars.rand.seed0, GlobalVars.rand.seed1, syncStream.toByteArray()
        )
    }
    syncStream.reset()
}

private var lastWarn = -1
private var lastDrop = -1

private fun requestUnitChangeSnapshot() {
    if (targetInterval() <= 0L) return
    val now = Time.millis()
    val cooldown = unitChangeSnapshotCooldownMillis.coerceAtLeast(0L)
    if (lastUnitChangeSnapshotMillis > 0L && now - lastUnitChangeSnapshotMillis < cooldown) return
    lastUnitChangeSnapshotMillis = now
    forceUnitChangeSnapshot = true
}

private fun rememberDestroyedUnit(id: Int) {
    if (targetInterval() <= 0L || id < 0) return
    pendingDestroyedUnitIds += id
    val limit = pendingUnitDestroyLimit.coerceAtLeast(1)
    while (pendingDestroyedUnitIds.size > limit) {
        pendingDestroyedUnitIds.firstOrNull()?.let { pendingDestroyedUnitIds.remove(it) } ?: break
    }
}

private fun sendPendingDestroyedUnits(player: Player, ids: List<Int>) {
    val con = player.con ?: return
    if (!con.isConnected) return
    ids.forEach { id ->
        con.send(UnitDestroyCallPacket().also { it.uid = id }, true)
    }
}

private fun IntSeq.toIdSet(): Set<Int> {
    val result = LinkedHashSet<Int>(size)
    for (i in 0 until size) result += items[i]
    return result
}

private fun sendHiddenSnapshotIfNeeded(player: Player, hiddenIds: IntSeq) {
    val key = player.uuid()
    if (hiddenIds.size <= 0) {
        hiddenSnapshotCache.remove(key)
        return
    }
    val now = Time.millis()
    val current = hiddenIds.toIdSet()
    val cached = hiddenSnapshotCache[key]
    val unchanged = cached?.ids == current
    val refreshDue = cached == null || now - cached.sentAt >= hiddenSnapshotRefreshMillis.coerceAtLeast(100L)
    if (!unchanged || refreshDue) {
        Call.hiddenSnapshot(player.con, hiddenIds)
        hiddenSnapshotCache[key] = HiddenSnapshotCache(current, now)
    }
}

private fun isSyncHiddenCompat(entity: Any, player: Player): Boolean {
    val method = entity.javaClass.methods.firstOrNull { method ->
        method.name == "isSyncHidden" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == Player::class.java
    } ?: return false
    return (method.invoke(entity, player) as? Boolean) == true
}

private fun sendSync(player: Player) {
    sendState(player)
    var sent = 0

    fun trySend(last: Boolean) {
        if ((!last && syncStream.size() <= 800) || sent == 0) return
        dataStream.flush()
        if (syncStream.size() > 2000 && syncStream.size() != lastWarn) {
            if (lastDrop != syncStream.size()) {
                logger.warning("同步限制: Too Big Packet ${syncStream.size()}B, Drop it")
            }
            lastDrop = syncStream.size()
        } else {
            Call.entitySnapshot(player.con, sent.toShort(), syncStream.toByteArray())
        }
        sent = 0
        syncStream.reset()
    }

    val hiddenIds = IntSeq()
    Groups.sync.forEach {
        val before = syncStream.size()
        if (isSyncHiddenCompat(it, player)) {
            hiddenIds.add(it.id())
            return@forEach
        }
        dataStream.writeInt(it.id())
        dataStream.writeByte(it.classId())
        it.beforeWrite()
        it.writeSync(Writes.get(dataStream))
        sent++
        trySend(false)
        if (syncStream.size() >= 1500 && syncStream.size() != lastWarn) {
            logger.warning("同步限制: Big packet ${syncStream.size()}; last entity ${it.javaClass.canonicalName}, use ${syncStream.size() - before} bytes")
            lastWarn = syncStream.size()
        }
    }
    trySend(true)
    sendHiddenSnapshotIfNeeded(player, hiddenIds)
}

listen<EventType.PlayerJoin> {
    if (targetInterval() > 0L) ensurePlayerThrottled(it.player)
}

listen<EventType.PlayerLeave> {
    throttledPlayers.remove(it.player.uuid())
    hiddenSnapshotCache.remove(it.player.uuid())
}

listen<EventType.UnitCreateEvent> {
    requestUnitChangeSnapshot()
}

listen<EventType.UnitSpawnEvent> {
    requestUnitChangeSnapshot()
}

listen<EventType.UnitDestroyEvent> {
    rememberDestroyedUnit(it.unit.id())
}

listen(EventType.Trigger.update) {
    val interval = targetInterval()
    if (interval != lastLoggedInterval) {
        if (interval > 0L) {
            logger.warning("同步频率限制已启用：interval=${interval}ms。若出现“聊天正常但单位/世界卡住后回弹”，请关闭 syncThrottleEnabled。")
        } else if (lastLoggedInterval > 0L) {
            logger.info("同步频率限制已解除，恢复原版实体同步。")
        }
        lastLoggedInterval = interval
    }
    if (interval <= 0L) {
        if (throttledPlayers.isNotEmpty()) {
            restoreAll()
            broadcastState("[green][上行优化] 上行需求已恢复，已解除同步频率限制。")
        }
        pendingDestroyedUnitIds.clear()
        forceUnitChangeSnapshot = false
        return@listen
    }

    val level = with(pressure) { currentPressure().throttleLevel }
    if (level > warnedLevel) {
        warnedLevel = level
        broadcastState("[yellow][上行优化] 上行超限，已限制同步频率为约 [white]${interval}ms[yellow]，并启用压力挂机检测。")
    }

    val forceSnapshot = forceUnitChangeSnapshot
    val destroyedIds = pendingDestroyedUnitIds.toList()
    val flushDestroyedIds = destroyedIds.isNotEmpty()
    Groups.player.forEach { player ->
        val con = player.con ?: return@forEach
        if (!con.isConnected) return@forEach
        ensurePlayerThrottled(player)
        try {
            if (flushDestroyedIds) sendPendingDestroyedUnits(player, destroyedIds)
            if (!forceSnapshot && Time.timeSinceMillis(getSyncTimeCompat(con) - hackOffset) < interval) return@forEach
            setSyncTimeCompat(con, hackOffset + Time.millis())
            val started = Time.millis()
            sendSync(player)
            val cost = Time.timeSinceMillis(started)
            val now = Time.millis()
            if (cost >= sendCostWarnMillis.coerceAtLeast(1L) && now - lastSendCostWarnMillis >= sendCostWarnCooldownMillis.coerceAtLeast(1000L)) {
                lastSendCostWarnMillis = now
                logger.warning("同步限制发送单玩家快照耗时 ${cost}ms：player=${player.plainName()} syncEntities=${Groups.sync.size()} units=${Groups.unit.size()} bullets=${Groups.bullet.size()} interval=${interval}ms")
            }
            incrementSnapshotsSentCompat(con)
        } catch (e: Throwable) {
            logger.warning("同步限制发送快照失败: ${player.name}: ${e.message}")
            e.printStackTrace()
        }
    }
    if (flushDestroyedIds) {
        pendingDestroyedUnitIds.clear()
    }
    if (forceSnapshot) {
        forceUnitChangeSnapshot = false
    }
}

listen<EventType.WorldLoadEvent> {
    pendingDestroyedUnitIds.clear()
    hiddenSnapshotCache.clear()
    forceUnitChangeSnapshot = false
    lastUnitChangeSnapshotMillis = 0L
}

listen<EventType.ResetEvent> {
    pendingDestroyedUnitIds.clear()
    hiddenSnapshotCache.clear()
    forceUnitChangeSnapshot = false
    lastUnitChangeSnapshotMillis = 0L
}

onEnable {
    if (!throttleRuntimeSupported) {
        logger.warning("当前 MindustryX v159/B477 已移除或变更旧版单连接快照接口，实验性同步频率限制已安全降级为关闭。")
    }
}

onDisable {
    launch(Dispatchers.game) {
        restoreAll()
    }
}
