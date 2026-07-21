@file:Depends("wayzer", "入服前异步检查")
@file:Depends("wayzer/map/serverPressure", "服务器网络压力")
@file:Depends("wayzer/reGrief/trafficMonitor", "上行流量估算")
@file:Depends("wayzer/reGrief/worldResyncCoordinator", "内部世界重同步共享槽位")

package wayzer.reGrief

import mindustry.game.EventType
import mindustry.net.NetConnection
import wayzer.lib.ConnectAsyncEvent
import wayzer.map.ServerPressure

name = "网络压力入服同步门控"

private val pressure = contextScript<ServerPressure>()
private val traffic = contextScript<TrafficMonitor>()
private val coordinator = contextScript<WorldResyncCoordinator>()

private val enabled by config.key(true, "仅在网络压力时限制同时入服世界同步")
private val level1Concurrent by config.key(2, "网络压力等级1允许同时入服同步数")
private val level2Concurrent by config.key(1, "网络压力等级2+允许同时入服同步数")
private val maxWaitMillis by config.key(12_000L, "入服同步门控最长等待(ms)")
private val stalePressureFailOpenMillis by config.key(20_000L, "压力数据超时后自动放行(ms)")
private val pollMillis by config.key(250L, "入服同步门控检查间隔(ms)")
private val maxWaitingConnections by config.key(8, "入服同步门控最大等待连接数")

private val reservations = mutableSetOf<NetConnection>()
private val waitingConnections = mutableSetOf<NetConnection>()
private var guardRunning = true

fun reservedJoinCount(): Int = reservations.count { it.isConnected && !it.hasConnected }
fun waitingJoinCount(): Int = waitingConnections.count { it.isConnected && !it.hasConnected }

private fun cleanupReservations() {
    reservations.removeAll { !it.isConnected || it.hasConnected || it.hasDisconnected }
}

private fun activeReservationCount(): Int {
    cleanupReservations()
    // ConnectAsyncEvent 正在等待门控的连接同样已经出现在 net.connections 中；若把所有
    // hasBegunConnecting 连接都计入“活动同步”，等待者会占用自己的名额，使配置为2时
    // 实际仍退化成完全串行。这里只统计已经被本门控放行、确实进入世界/资产同步的连接。
    return reservations.count { it.isConnected && !it.hasConnected && !it.hasDisconnected }
}

private fun currentGateLimit(): Int {
    if (!enabled || !guardRunning) return Int.MAX_VALUE
    val snapshot = with(pressure) { currentPressure() }
    if (System.currentTimeMillis() - snapshot.updatedAtMillis > stalePressureFailOpenMillis.coerceAtLeast(5_000L)) {
        return Int.MAX_VALUE
    }
    return when {
        snapshot.networkLevel <= 0 -> Int.MAX_VALUE
        snapshot.networkLevel == 1 -> level1Concurrent.coerceAtLeast(1)
        else -> level2Concurrent.coerceAtLeast(1)
    }
}

listenTo<ConnectAsyncEvent> {
    if (!enabled || !guardRunning || cancelled || con.kicked) return@listenTo
    val firstLimit = currentGateLimit()
    if (firstLimit == Int.MAX_VALUE) return@listenTo
    if (waitingJoinCount() >= maxWaitingConnections.coerceAtLeast(1)) {
        reject("服务器上行同步队列已满，请稍后重试。")
        return@listenTo
    }

    waitingConnections += con
    val deadline = System.currentTimeMillis() + maxWaitMillis.coerceAtLeast(1_000L)
    try {
        while (guardRunning && con.isConnected && !con.kicked) {
            val limit = currentGateLimit()
            // 压力恢复、压力数据失效或脚本关闭时必须失效放行，不允许永久卡死新玩家。
            val occupied = activeReservationCount() + with(coordinator) { internalTransferSlotCount() }
            if (limit == Int.MAX_VALUE || occupied < limit) {
                reservations += con
                return@listenTo
            }
            if (System.currentTimeMillis() >= deadline) {
                val network = with(traffic) { networkTransferSnapshot() }
                reject(
                    "服务器上行繁忙，当前正在同步其他玩家" +
                        "（待加入${network.joiningConnections}人），请稍后重新进入。"
                )
                return@listenTo
            }
            delay(pollMillis.coerceIn(100L, 1_000L))
        }
    } catch (e: Throwable) {
        // 重要入服链路必须 fail-open：监控脚本异常不能导致全服永久无法加入。
        logger.warning("入服同步门控异常，本连接已自动放行：${e.message}")
    } finally {
        waitingConnections.remove(con)
    }
}

listen<EventType.PlayerConnectionConfirmed> {
    it.player.con?.let { con -> reservations.remove(con) }
}

listen<EventType.PlayerLeave> {
    it.player.con?.let { con -> reservations.remove(con) }
}

listen(EventType.Trigger.update) {
    cleanupReservations()
}

onEnable {
    guardRunning = true
}

onDisable {
    // 先标记停止并清空预留；所有正在等待的处理会在下一次轮询失效放行。
    guardRunning = false
    reservations.clear()
    waitingConnections.clear()
}
