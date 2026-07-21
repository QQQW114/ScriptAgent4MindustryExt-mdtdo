@file:Depends("wayzer/map/serverPressure", "网络压力状态")
@file:Depends("wayzer/reGrief/trafficMonitor", "上行状态展示")

package wayzer.reGrief

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.net.NetConnection
import wayzer.map.ServerPressure

name = "v159世界与资产重同步协调器"

private val pressure = contextScript<ServerPressure>()
private val trafficMonitor = contextScript<TrafficMonitor>()
private val defaultTimeoutMillis by config.key(120_000L, "等待客户端重新确认世界的默认超时(ms)")
private val queueTimeoutMillis by config.key(180_000L, "内部重同步最长排队时间(ms)")
private val orphanHoldMillis by config.key(120_000L, "确认超时后继续占用同步槽位的最长时间(ms)")
private val recoveryMillis by config.key(2_500L, "每次完整同步后的统一上行恢复间隔(ms)")
private val stalePressureFailOpenMillis by config.key(20_000L, "压力状态过期后自动放行(ms)")
private val maxQueuedTasks by config.key(32, "内部完整重同步全局队列上限")

private data class QueueTask(
    val player: Player,
    val connection: NetConnection,
    val reason: String,
    val priority: Int,
    val sequence: Long,
    val epoch: Long,
    val gate: CompletableDeferred<Boolean> = CompletableDeferred(),
)

private data class Waiter(
    val task: QueueTask,
    val ready: CompletableDeferred<Boolean> = CompletableDeferred(),
)

private val queue = mutableListOf<QueueTask>()
private var active: Waiter? = null
private var reserved: QueueTask? = null
private var worldEpoch = 0L
private var sequence = 0L
private var recoveryUntil = 0L
private var enabled = true
private var warnedNoAssetApi = false

private fun publishStatus() = with(trafficMonitor) {
    updateResyncCoordinatorStatus(
        activeReason = active?.task?.reason ?: reserved?.reason,
        queuedTasks = queue.size,
        recoveryUntilMillis = recoveryUntil,
        enabled = enabled,
    )
}

private fun priorityFor(reason: String): Int = when {
    "CP管理" in reason || "外部CP" in reason -> 300
    "杂交" in reason -> 200
    "点歌" in reason -> 100
    "音效" in reason -> 50
    else -> 150
}

private fun pendingInitialJoins(): Int = net.connections.count {
    it.isConnected && it.hasBegunConnecting && !it.hasConnected && !it.hasDisconnected &&
        active?.task?.connection !== it
}

private fun pressureFresh(): Boolean {
    val snapshot = with(pressure) { currentPressure() }
    return System.currentTimeMillis() - snapshot.updatedAtMillis <= stalePressureFailOpenMillis.coerceAtLeast(5_000L)
}

private fun canStartInternal(): Boolean {
    if (!pressureFresh()) return true
    val level = with(pressure) { currentPressure().networkLevel }
    val initial = pendingInitialJoins()
    return when {
        level <= 0 -> true
        level == 1 -> initial < 2 // L1共享总槽位2，内部任务自身占1个。
        else -> initial == 0     // L2+全服最多一个完整世界/资产流，首次加入优先。
    }
}

private fun cancelQueued(predicate: (QueueTask) -> Boolean) {
    val removed = queue.filter(predicate)
    queue.removeAll(removed.toSet())
    removed.forEach { it.gate.complete(false) }
}

private fun pumpQueue() {
    publishStatus()
    if (!enabled || active != null || reserved != null || queue.isEmpty()) return
    if (System.currentTimeMillis() < recoveryUntil || !canStartInternal()) return
    cancelQueued { it.epoch != worldEpoch || !it.connection.isConnected || it.connection.hasDisconnected }
    val next = queue.sortedWith(compareByDescending<QueueTask> { it.priority }.thenBy { it.sequence }).firstOrNull() ?: return
    queue.remove(next)
    reserved = next
    next.gate.complete(true)
    publishStatus()
}

fun isWorldResyncActive(): Boolean = active != null || reserved != null
fun internalTransferSlotCount(): Int = if (isWorldResyncActive() || System.currentTimeMillis() < recoveryUntil) 1 else 0
fun queuedResyncCount(): Int = queue.size
fun activeResyncReason(): String? = active?.task?.reason ?: reserved?.reason

suspend fun resyncWorldAndAssets(
    player: Player,
    reason: String,
    timeoutMillis: Long = defaultTimeoutMillis,
    postConfirmDelayMillis: Long = 0L,
): Boolean {
    val con = player.con ?: return false
    if (!enabled || player.isLocal || !con.isConnected) return false
    if (queue.size >= maxQueuedTasks.coerceAtLeast(1)) {
        logger.warning("内部完整同步队列已满，拒绝任务: player=${player.plainName()} reason=$reason")
        return false
    }

    val task = QueueTask(player, con, reason, priorityFor(reason), ++sequence, worldEpoch)
    queue += task
    publishStatus()
    pumpQueue()
    val admitted = withTimeoutOrNull(queueTimeoutMillis.coerceAtLeast(5_000L)) { task.gate.await() } == true
    if (!admitted || !enabled || task.epoch != worldEpoch || !con.isConnected) {
        queue.remove(task)
        if (reserved === task) reserved = null
        task.gate.cancel()
        pumpQueue()
        return false
    }

    val waiter = Waiter(task)
    reserved = null
    active = waiter
    publishStatus()
    val method = Vars.netServer.javaClass.methods.firstOrNull {
        it.name == "sendWorldAndAssets" && it.parameterTypes.size == 1
    }
    try {
        Call.worldDataBegin(con)
        if (method != null) {
            method.invoke(Vars.netServer, player)
        } else {
            if (!warnedNoAssetApi) {
                warnedNoAssetApi = true
                logger.warning("sendWorldAndAssets 不可用，协调器已fail-open到sendWorldData。")
            }
            Vars.netServer.sendWorldData(player)
        }

        var confirmed = withTimeoutOrNull(timeoutMillis.coerceAtLeast(1_000L)) { waiter.ready.await() } == true
        if (!confirmed && con.isConnected && !con.hasDisconnected &&
            (con.determiningAssets || con.receivingAssets || !con.hasConnected)
        ) {
            logger.warning("完整同步确认超时，继续保留槽位等待底层传输结束: player=${player.plainName()} reason=$reason")
            val orphanDeadline = System.currentTimeMillis() + orphanHoldMillis.coerceAtLeast(10_000L)
            while (enabled && con.isConnected && !con.hasDisconnected && System.currentTimeMillis() < orphanDeadline) {
                if (con.hasConnected && !con.determiningAssets && !con.receivingAssets) {
                    confirmed = true
                    break
                }
                delay(250L)
            }
            if (!confirmed && con.isConnected && !con.hasDisconnected) {
                con.kick("内部世界同步超时，请重新连接服务器。")
            }
        }
        if (confirmed && postConfirmDelayMillis > 0L) delay(postConfirmDelayMillis)
        return confirmed
    } catch (error: Throwable) {
        logger.warning("完整世界/资产同步失败($reason): ${error.cause?.message ?: error.message}")
        return false
    } finally {
        if (active === waiter) active = null
        waiter.ready.cancel()
        recoveryUntil = System.currentTimeMillis() + recoveryMillis.coerceAtLeast(0L)
        publishStatus()
        pumpQueue()
    }
}

listen<EventType.PlayerConnectionConfirmed> { event ->
    val waiter = active ?: return@listen
    val task = waiter.task
    val con = event.player.con ?: return@listen
    if (con !== task.connection || event.player !== task.player || task.epoch != worldEpoch) return@listen
    if (event.player.unit() == null && event.player.bestCore() != null) {
        runCatching { event.player.checkSpawn() }.onFailure {
            logger.warning("内部重同步完成后恢复核心机失败(${task.reason}): ${it.message}")
        }
    }
    con.hasConnected = true
    waiter.ready.complete(true)
}

listen<EventType.PlayerLeave> { event ->
    val con = event.player.con
    active?.takeIf { it.task.player === event.player || it.task.connection === con }?.ready?.complete(false)
    cancelQueued { it.player === event.player || it.connection === con }
    if (reserved?.player === event.player || reserved?.connection === con) {
        reserved?.gate?.complete(false)
        reserved = null
    }
    pumpQueue()
}

listen<EventType.WorldLoadEvent> {
    worldEpoch++
    active?.ready?.complete(false)
    reserved?.gate?.complete(false)
    reserved = null
    cancelQueued { true }
    recoveryUntil = 0L
    publishStatus()
}

listen(EventType.Trigger.update) { pumpQueue() }

onEnable {
    enabled = true
    publishStatus()
}
onDisable {
    enabled = false
    active?.ready?.complete(false)
    reserved?.gate?.complete(false)
    active = null
    reserved = null
    cancelQueued { true }
    recoveryUntil = 0L
    publishStatus()
}
