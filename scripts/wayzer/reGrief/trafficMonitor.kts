@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.reGrief

import mindustry.net.NetConnection
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.MdtStorage
import wayzer.user.TrustLevel
import java.time.Duration
import mindustry.game.EventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

name = "上行流量统计"

private val trustLevel = contextScript<TrustLevel>()

private val TRAFFIC_BUDGET_MBPS_KEY = "trafficMonitor.budgetMbps"
private val defaultBudgetMbps by config.key(18.0, "实验性上行预算(Mbps)")
private val sampleMillis by config.key(1_000L, "上行统计采样间隔(ms)")
private val historySamples by config.key(30, "上行统计保留采样数")
private val averageSamples by config.key(5, "上行显示滑动平均采样数")

// 上行统计来源：Windows 网卡累计 Sent 字节（netstat -e）。
// 本系统仅支持 Windows（生产与开发均为 Windows，不支持 Linux）。
private val NIC_COUNTER_COMMAND = "netstat -e"

data class NetworkTransferSnapshot(
    val activeStreams: Int = 0,
    val joiningConnections: Int = 0,
    val oldestJoiningMillis: Long = 0L,
    val tcpQueuedBytes: Long = 0L,
    val maxTcpQueuedBytes: Int = 0,
    val congestedConnections: Int = 0,
)

data class ResyncCoordinatorSnapshot(
    val activeReason: String? = null,
    val queuedTasks: Int = 0,
    val recoveryUntilMillis: Long = 0L,
    val enabled: Boolean = false,
)

private data class TrafficSample(
    val bytes: Long,
    val durationMillis: Long,
    val network: NetworkTransferSnapshot,
) {
    val mbps: Double get() = if (durationMillis <= 0) 0.0 else bytes * 8.0 / durationMillis / 1000.0
}

private val lock = Any()
private val samples = ArrayDeque<TrafficSample>()
private var lastNicSentBytes: Long = -1L
private var lastSampleMillis = System.currentTimeMillis()
// 由 serverPressure 使用；放在较长期存活的流量监控脚本中，避免单独热重载
// serverPressure/syncThrottle 在同一回合重复播报同步限制介入提示。
private var throttleRestrictionAnnouncementSentState = false
@Volatile private var resyncCoordinator = ResyncCoordinatorSnapshot()

fun throttleRestrictionAnnouncementSent(): Boolean = synchronized(lock) {
    throttleRestrictionAnnouncementSentState
}

fun markThrottleRestrictionAnnouncementSent() = synchronized(lock) {
    throttleRestrictionAnnouncementSentState = true
}

fun resetThrottleRestrictionAnnouncement() = synchronized(lock) {
    throttleRestrictionAnnouncementSentState = false
}

listen<EventType.WorldLoadEvent> { resetThrottleRestrictionAnnouncement() }
listen<EventType.ResetEvent> { resetThrottleRestrictionAnnouncement() }

fun updateResyncCoordinatorStatus(
    activeReason: String?,
    queuedTasks: Int,
    recoveryUntilMillis: Long,
    enabled: Boolean,
) {
    resyncCoordinator = ResyncCoordinatorSnapshot(activeReason, queuedTasks, recoveryUntilMillis, enabled)
}

fun trafficBudgetMbps(): Double =
    MdtStorage.getSetting(TRAFFIC_BUDGET_MBPS_KEY)?.toDoubleOrNull()?.takeIf { it > 0.0 }
        ?: defaultBudgetMbps

fun trafficRecoverMbps(): Double = trafficBudgetMbps() * 0.9

fun setTrafficBudgetMbps(value: Double) {
    MdtStorage.setSetting(TRAFFIC_BUDGET_MBPS_KEY, value.coerceAtLeast(0.1).toString())
}

fun currentTrafficMbps(): Double = synchronized(lock) { samples.lastOrNull()?.mbps ?: 0.0 }
fun currentSyncTrafficMbps(): Double = synchronized(lock) { samples.lastOrNull()?.mbps ?: 0.0 }
fun currentTransferTrafficMbps(): Double = synchronized(lock) { samples.lastOrNull()?.mbps ?: 0.0 }

fun averageTrafficMbps(count: Int = averageSamples): Double = synchronized(lock) {
    samples.takeLast(count.coerceAtLeast(1)).map { it.mbps }.averageOrZero()
}

fun averageSyncTrafficMbps(count: Int = averageSamples): Double = synchronized(lock) {
    samples.takeLast(count.coerceAtLeast(1)).map { it.mbps }.averageOrZero()
}

fun averageTransferTrafficMbps(count: Int = averageSamples): Double = synchronized(lock) {
    samples.takeLast(count.coerceAtLeast(1)).map { it.mbps }.averageOrZero()
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

// 网卡聚合统计无法区分包类型/包数，以下两项仅为兼容旧调用方保留。
fun lastTrafficPackets(): Long = 0L

fun topTrafficPackets(limit: Int = 5): List<Pair<String, Long>> = emptyList()

fun networkTransferSnapshot(): NetworkTransferSnapshot = synchronized(lock) {
    samples.lastOrNull()?.network ?: liveNetworkSnapshot()
}

fun activeWorldTransferCount(): Int = networkTransferSnapshot().activeStreams
fun pendingJoinCount(): Int = networkTransferSnapshot().joiningConnections
fun oldestPendingJoinMillis(): Long = networkTransferSnapshot().oldestJoiningMillis
fun tcpQueuedBytes(): Long = networkTransferSnapshot().tcpQueuedBytes
fun congestedConnectionCount(): Int = networkTransferSnapshot().congestedConnections

fun trafficStatusText(): String {
    val budget = trafficBudgetMbps()
    val current = currentTrafficMbps()
    val avg = averageTrafficMbps()
    val network = networkTransferSnapshot()
    val coordinator = resyncCoordinator
    val recoveryLeft = (coordinator.recoveryUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val coordinatorState = when {
        !coordinator.enabled -> "[gray]未启用"
        coordinator.activeReason != null -> "[yellow]执行中：[white]${coordinator.activeReason}"
        recoveryLeft > 0L -> "[cyan]恢复间隔 [white]${(recoveryLeft + 999L) / 1000L}s"
        else -> "[green]空闲"
    }
    return """
        |[cyan]网卡上行：[white]${formatMbps(current)} Mbps[]（${formatMbps(avg)} Mbps 平均）
        |[cyan]上行预算：[white]${formatMbps(budget)} Mbps[] / 恢复线 [white]${formatMbps(trafficRecoverMbps())} Mbps
        |[cyan]待加入连接：[white]${network.joiningConnections}[]，最老等待 [white]${network.oldestJoiningMillis / 1000}s
        |[cyan]TCP待发：[white]${formatBytes(network.tcpQueuedBytes)}[]，最大单连接 [white]${formatBytes(network.maxTcpQueuedBytes.toLong())}[]，拥塞连接 [white]${network.congestedConnections}
        |[cyan]内部完整同步：$coordinatorState[]，队列 [white]${coordinator.queuedTasks}
    """.trimMargin()
}

/**
 * 读取 Windows 网卡累计 Sent 字节。netstat -e 输出按系统语言本地化，行标签不可靠；
 * 这里按结构取第一个「一个标签 + 两个整数」的行（即 Bytes 行），第二列整数为累计 Sent 字节。
 */
private fun readNicSentBytes(): Long = runCatching {
    val parts = NIC_COUNTER_COMMAND.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return 0L
    val process = ProcessBuilder(parts).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    process.waitFor()
    text.lineSequence()
        .map { it.trim() }
        .mapNotNull { Regex("""^(\S+)\s+(\d+)\s+(\d+)\s*$""").find(it) }
        .firstOrNull()
        ?.let { it.groupValues[3].toLongOrNull() }
        ?: 0L
}.getOrDefault(0L)

private fun tcpQueueBytes(con: NetConnection): Int = runCatching {
    val arcConnection = con.javaClass.getField("connection").get(con)
    (arcConnection.javaClass.getMethod("getTcpWriteBufferSize").invoke(arcConnection) as Number).toInt()
}.getOrDefault(0)

private fun liveNetworkSnapshot(): NetworkTransferSnapshot {
    val now = System.currentTimeMillis()
    val connections = net.connections.toList()
    val queues = connections.map(::tcpQueueBytes)
    val joining = connections.filter { it.hasBegunConnecting && !it.hasConnected && !it.hasDisconnected }
    val oldest = joining.maxOfOrNull { (now - it.connectTime).coerceAtLeast(0L) } ?: 0L
    return NetworkTransferSnapshot(
        activeStreams = 0,
        joiningConnections = joining.size,
        oldestJoiningMillis = oldest,
        tcpQueuedBytes = queues.sumOf { it.toLong() },
        maxTcpQueuedBytes = queues.maxOrNull() ?: 0,
        // B477 服务端 TCP write buffer 默认 32768B，超过 24KB 视为明显积压。
        congestedConnections = queues.count { it >= 24 * 1024 },
    )
}

private suspend fun rotateSample() {
    val now = System.currentTimeMillis()
    val duration = (now - lastSampleMillis).coerceAtLeast(1L)
    val network = liveNetworkSnapshot()
    val sent = withContext(Dispatchers.IO) { readNicSentBytes() }
    val bytes = if (lastNicSentBytes >= 0L && sent >= lastNicSentBytes) sent - lastNicSentBytes else 0L
    lastNicSentBytes = sent
    synchronized(lock) {
        samples.addLast(TrafficSample(bytes, duration, network))
        while (samples.size > historySamples.coerceAtLeast(1)) samples.removeFirst()
        lastSampleMillis = now
    }
}

private fun canChangeBudget(operator: Player?): Boolean =
    operator == null || with(trustLevel) { isTrustAdmin(operator) }

private fun formatMbps(value: Double): String = "%.2f".format(value)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

registerVar("scoreboard.ext.traffic", "MDT总上行", DynamicVar {
    val total = averageTrafficMbps()
    if (total <= 0.01 && Groups.player.size() == 0) return@DynamicVar null
    "{cK}总上行: {cV}${formatMbps(total)} Mbps".with()
})

onEnable {
    lastSampleMillis = System.currentTimeMillis()
    lastNicSentBytes = -1L
    logger.info("上行流量统计已启用：Windows 网卡累计字节（netstat -e），仅支持 Windows。")
    launch(Dispatchers.game) {
        while (true) {
            delay(Duration.ofMillis(sampleMillis.coerceAtLeast(250L)).toMillis())
            rotateSample()
        }
    }
}

command("traffic", "查看/设置上行") {
    usage = "[status|budget <Mbps>|reset]"
    aliases = listOf("上行", "流量")
    body {
        when (arg.getOrNull(0)?.lowercase() ?: "status") {
            "status", "状态" -> reply(trafficStatusText().with())
            "budget", "预算" -> {
                if (!canChangeBudget(player)) returnReply("[red]权限不足：只有 4级/admin 或控制台可以修改上行预算。".with())
                val value = arg.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: replyUsage()
                setTrafficBudgetMbps(value)
                reply("[green]已设置上行预算为 [white]${formatMbps(value)} Mbps".with())
            }
            "reset", "重置" -> {
                synchronized(lock) {
                    samples.clear()
                    lastSampleMillis = System.currentTimeMillis()
                }
                lastNicSentBytes = -1L
                reply("[green]已重置上行统计窗口。".with())
            }
            else -> replyUsage()
        }
    }
}
