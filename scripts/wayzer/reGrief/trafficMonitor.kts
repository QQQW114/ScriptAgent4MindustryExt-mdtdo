@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.reGrief

import mindustry.net.ArcNetProvider
import mindustry.net.Net
import mindustry.net.NetConnection
import mindustry.net.Packet
import mindustry.net.Streamable
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.MdtStorage
import wayzer.user.TrustLevel
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.time.Duration
import kotlin.math.max

name = "上行流量估算"

private val trustLevel = contextScript<TrustLevel>()

private val TRAFFIC_BUDGET_MBPS_KEY = "trafficMonitor.budgetMbps"
private val defaultBudgetMbps by config.key(18.0, "实验性上行预算(Mbps)")
private val sampleMillis by config.key(1_000L, "上行统计采样间隔(ms)")
private val historySamples by config.key(30, "上行统计保留采样数")
private val averageSamples by config.key(5, "上行显示滑动平均采样数")
private val serializationWarningCooldownMillis by config.key(60_000L, "包大小序列化失败同类警告最小间隔(ms)")

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
    val totalBytes: Long,
    val syncBytes: Long,
    val transferBytes: Long,
    val packets: Long,
    val durationMillis: Long,
    val topPackets: List<Pair<String, Long>>,
    val network: NetworkTransferSnapshot,
) {
    val totalMbps: Double get() = mbps(totalBytes)
    val syncMbps: Double get() = mbps(syncBytes)
    val transferMbps: Double get() = mbps(transferBytes)
    private fun mbps(bytes: Long): Double =
        if (durationMillis <= 0) 0.0 else bytes * 8.0 / durationMillis / 1000.0
}

private data class ActiveStream(
    val packet: Streamable,
    val con: NetConnection?,
    val input: ByteArrayInputStream,
    val totalBytes: Int,
    var previousRemaining: Int,
    val connectedAtStart: Boolean,
    val startedAt: Long = System.currentTimeMillis(),
)

private val lock = Any()
private val serializerLock = Any()
private val samples = ArrayDeque<TrafficSample>()
private val activeStreams = mutableListOf<ActiveStream>()
private val serializer = ArcNetProvider.PacketSerializer()
private val counterByteBuffer = ByteBuffer.allocate(2 * 1024 * 1024)
private var currentPacketBytes = 0L
private var currentSyncBytes = 0L
private var currentTransferBytes = 0L
private var currentPackets = 0L
private var currentPacketTypes = mutableMapOf<String, Long>()
private var lastPacket: Packet? = null
private var lastPacketAtNanos = 0L
private var lastSampleMillis = System.currentTimeMillis()
private val serializationWarningAt = mutableMapOf<String, Long>()
private val sendPacketEventClass: Class<*>? = runCatching { Class.forName("mindustryX.events.SendPacketEvent") }.getOrNull()
private val hasBatchTargetCount = sendPacketEventClass?.let { clazz ->
    runCatching {
        clazz.getField("targetCount").type == Int::class.javaPrimitiveType &&
            clazz.getField("connections") != null
    }.getOrDefault(false)
} == true
@Volatile private var resyncCoordinator = ResyncCoordinatorSnapshot()

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

fun currentTrafficMbps(): Double = synchronized(lock) { samples.lastOrNull()?.totalMbps ?: 0.0 }
fun currentSyncTrafficMbps(): Double = synchronized(lock) { samples.lastOrNull()?.syncMbps ?: 0.0 }
fun currentTransferTrafficMbps(): Double = synchronized(lock) { samples.lastOrNull()?.transferMbps ?: 0.0 }

fun averageTrafficMbps(count: Int = averageSamples): Double = synchronized(lock) {
    samples.takeLast(count.coerceAtLeast(1)).map { it.totalMbps }.averageOrZero()
}

fun averageSyncTrafficMbps(count: Int = averageSamples): Double = synchronized(lock) {
    samples.takeLast(count.coerceAtLeast(1)).map { it.syncMbps }.averageOrZero()
}

fun averageTransferTrafficMbps(count: Int = averageSamples): Double = synchronized(lock) {
    samples.takeLast(count.coerceAtLeast(1)).map { it.transferMbps }.averageOrZero()
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

fun lastTrafficPackets(): Long = synchronized(lock) { samples.lastOrNull()?.packets ?: 0L }

fun topTrafficPackets(limit: Int = 5): List<Pair<String, Long>> = synchronized(lock) {
    samples.lastOrNull()?.topPackets?.take(limit.coerceAtLeast(1)) ?: emptyList()
}

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
    val sync = averageSyncTrafficMbps()
    val transfer = averageTransferTrafficMbps()
    val network = networkTransferSnapshot()
    val coordinator = resyncCoordinator
    val recoveryLeft = (coordinator.recoveryUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val coordinatorState = when {
        !coordinator.enabled -> "[gray]未启用"
        coordinator.activeReason != null -> "[yellow]执行中：[white]${coordinator.activeReason}"
        recoveryLeft > 0L -> "[cyan]恢复间隔 [white]${(recoveryLeft + 999L) / 1000L}s"
        else -> "[green]空闲"
    }
    val top = topTrafficPackets(8).takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "  [gray]${it.first}: [white]${formatBytes(it.second)}/s" }
        ?: "  [gray]暂无数据"
    return """
        |[cyan]总上行：[white]${formatMbps(current)} Mbps[]（${formatMbps(avg)} Mbps 平均）
        |[cyan]同步上行：[white]${formatMbps(sync)} Mbps[] / 世界与资产流 [white]${formatMbps(transfer)} Mbps
        |[cyan]上行预算：[white]${formatMbps(budget)} Mbps[] / 恢复线 [white]${formatMbps(trafficRecoverMbps())} Mbps
        |[cyan]待加入/活动流：[white]${network.joiningConnections}/${network.activeStreams}[]，最老等待 [white]${network.oldestJoiningMillis / 1000}s
        |[cyan]TCP待发：[white]${formatBytes(network.tcpQueuedBytes)}[]，最大单连接 [white]${formatBytes(network.maxTcpQueuedBytes.toLong())}[]，拥塞连接 [white]${network.congestedConnections}
        |[cyan]内部完整同步：$coordinatorState[]，队列 [white]${coordinator.queuedTasks}
        |[cyan]最近包数：[white]${lastTrafficPackets()} / s
        |[cyan]最近主要包类型：
        |$top
    """.trimMargin()
}

private fun warnSerializationFailure(packetName: String, message: String?) {
    val now = System.currentTimeMillis()
    val last = serializationWarningAt[packetName] ?: 0L
    if (now - last < serializationWarningCooldownMillis.coerceAtLeast(1_000L)) return
    serializationWarningAt[packetName] = now
    logger.warning("上行统计序列化包失败: $packetName: $message")
}

private fun Packet.countBytesSafeLocked(): Int = try {
    counterByteBuffer.clear()
    serializer.write(counterByteBuffer, this)
    counterByteBuffer.position()
} catch (e: Throwable) {
    warnSerializationFailure(javaClass.simpleName, e.message)
    0
}

private fun eventMember(event: Any, name: String): Any? =
    runCatching { event.javaClass.getField(name).get(event) }.getOrNull()

private fun packetRate(event: Any): Int {
    val explicitTargetCount = (eventMember(event, "targetCount") as? Number)?.toInt() ?: -1
    if (explicitTargetCount >= 0) return explicitTargetCount
    val connections = net.connections.count()
    return when {
        eventMember(event, "con") != null -> 1
        eventMember(event, "except") == null -> connections
        else -> max(0, connections - 1)
    }
}

private fun isGameplaySyncPacket(packet: Packet): Boolean {
    val name = packet.javaClass.simpleName.lowercase()
    // 性能优化只能由明确的游戏状态同步触发。使用白名单而不是“除了UI都算同步”，
    // 防止连接握手、插件控制包、欢迎信息和资产协商被误计为单位压力。
    val gameplayMarkers = listOf(
        "snapshot", "unit", "building", "buildhealth", "block", "tile", "playerstate",
        "playerspawn", "setposition", "setrotation", "sethealth", "destroy", "death",
        "bullet", "weather", "wave", "payload", "plansync", "staterequest"
    )
    val excludedMarkers = listOf(
        "worlddata", "asset", "stream", "connect", "handshake", "kick", "message", "menu",
        "label", "toast", "textinput", "info", "music", "sound", "effect", "trace", "admin"
    )
    return excludedMarkers.none { it in name } && gameplayMarkers.any { it in name }
}

private fun trackStream(event: Any, stream: Streamable) {
    val input = stream.stream ?: return
    val remaining = runCatching { input.available() }.getOrDefault(0)
    if (remaining <= 0) return
    val con = eventMember(event, "con") as? NetConnection
    synchronized(lock) {
        if (activeStreams.any { it.packet === stream }) return
        activeStreams += ActiveStream(stream, con, input, remaining, remaining, con?.hasConnected == true)
    }
}

/** 兼容旧同步路由脚本；当前快照保护不再取消或重发原生同步包。 */
fun recordRoutedPacket(packet: Packet, targets: Int, gameplaySync: Boolean = true) {
    if (targets <= 0) return
    val packetBytes = synchronized(serializerLock) { packet.countBytesSafeLocked() }
    val bytes = packetBytes.toLong() * targets
    if (bytes <= 0L) return
    val name = "${Net.getPacketId(packet).toInt() and 0xff}:${packet.javaClass.simpleName}"
    synchronized(lock) {
        currentPacketBytes += bytes
        if (gameplaySync) currentSyncBytes += bytes
        currentPackets += targets.toLong()
        currentPacketTypes[name] = (currentPacketTypes[name] ?: 0L) + bytes
    }
}

private fun handleSendPacketEvent(event: Any) {
    val outgoing = eventMember(event, "packet")
    if (outgoing is Streamable) {
        trackStream(event, outgoing)
        return
    }
    val packet = outgoing as? Packet ?: return
    val rate = packetRate(event)
    if (rate <= 0) return

    // 旧 MDX 广播可能在 Net.send 与每个 con.send 重复发事件；B477 批量发送只发一次。
    val nowNanos = System.nanoTime()
    val packetBytes = synchronized(serializerLock) {
        // 兼容旧MDX同一调用链的双事件，但不能永久按对象身份去重；部分生成包会复用实例。
        if (packet === lastPacket && nowNanos - lastPacketAtNanos <= 1_000_000L) null
        else {
            lastPacket = packet
            lastPacketAtNanos = nowNanos
            packet.countBytesSafeLocked()
        }
    } ?: return

    val bytes = packetBytes.toLong() * rate
    if (bytes <= 0L) return
    val name = "${Net.getPacketId(packet).toInt() and 0xff}:${packet.javaClass.simpleName}"
    synchronized(lock) {
        currentPacketBytes += bytes
        if (isGameplaySyncPacket(packet)) currentSyncBytes += bytes
        currentPackets += rate.toLong()
        currentPacketTypes[name] = (currentPacketTypes[name] ?: 0L) + bytes
    }
}

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
        activeStreams = synchronized(lock) { activeStreams.size },
        joiningConnections = joining.size,
        oldestJoiningMillis = oldest,
        tcpQueuedBytes = queues.sumOf { it.toLong() },
        maxTcpQueuedBytes = queues.maxOrNull() ?: 0,
        // B477 服务端 TCP write buffer 默认 32768B，超过 24KB 视为明显积压。
        congestedConnections = queues.count { it >= 24 * 1024 },
    )
}

private fun consumeStreamProgressLocked(): Long {
    var consumed = 0L
    val iterator = activeStreams.iterator()
    while (iterator.hasNext()) {
        val active = iterator.next()
        val remaining = runCatching { active.input.available() }.getOrDefault(0).coerceAtLeast(0)
        if (remaining < active.previousRemaining) consumed += (active.previousRemaining - remaining).toLong()
        active.previousRemaining = remaining
        val conDone = active.con?.let {
            it.hasDisconnected || !it.isConnected || (!active.connectedAtStart && it.hasConnected)
        } == true
        if (remaining <= 0 || conDone || System.currentTimeMillis() - active.startedAt > 10 * 60_000L) iterator.remove()
    }
    return consumed
}

private fun rotateSample() {
    val now = System.currentTimeMillis()
    val duration = (now - lastSampleMillis).coerceAtLeast(1L)
    val network = liveNetworkSnapshot()
    synchronized(lock) {
        currentTransferBytes += consumeStreamProgressLocked()
        val top = currentPacketTypes.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }
        val total = currentPacketBytes + currentTransferBytes
        samples.addLast(
            TrafficSample(total, currentSyncBytes, currentTransferBytes, currentPackets, duration, top, network)
        )
        while (samples.size > historySamples.coerceAtLeast(1)) samples.removeFirst()
        currentPacketBytes = 0L
        currentSyncBytes = 0L
        currentTransferBytes = 0L
        currentPackets = 0L
        currentPacketTypes = mutableMapOf()
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

if (sendPacketEventClass != null) {
    listen<Any>(sendPacketEventClass) { handleSendPacketEvent(it) }
} else {
    logger.warning("未检测到 MindustryX SendPacketEvent；官方端将禁用精确上行包统计。")
}

registerVar("scoreboard.ext.traffic", "MDT总上行/同步上行", DynamicVar {
    val total = averageTrafficMbps()
    val sync = averageSyncTrafficMbps()
    if (total <= 0.01 && Groups.player.size() == 0) return@DynamicVar null
    "{cK}总上行: {cV}${formatMbps(total)} Mbps[] / 同步: {cV}${formatMbps(sync)} Mbps".with()
})

onEnable {
    lastSampleMillis = System.currentTimeMillis()
    when {
        sendPacketEventClass == null -> Unit
        hasBatchTargetCount -> logger.info("B480批量发送统计已启用：SendPacketEvent.targetCount/connections 可用。")
        else -> logger.warning("SendPacketEvent 缺少B480批量目标字段；批量上行可能按广播兼容逻辑估算。")
    }
    launch(Dispatchers.game) {
        while (true) {
            delay(Duration.ofMillis(sampleMillis.coerceAtLeast(250L)).toMillis())
            rotateSample()
        }
    }
}

command("traffic", "查看/设置估算上行") {
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
                    activeStreams.clear()
                    currentPacketBytes = 0L
                    currentSyncBytes = 0L
                    currentTransferBytes = 0L
                    currentPackets = 0L
                    currentPacketTypes.clear()
                    lastSampleMillis = System.currentTimeMillis()
                }
                reply("[green]已重置上行统计窗口。".with())
            }
            else -> replyUsage()
        }
    }
}
