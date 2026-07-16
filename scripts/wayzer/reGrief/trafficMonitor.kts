@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.reGrief

import mindustry.net.ArcNetProvider
import mindustry.net.Net
import mindustry.net.Packet
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.user.TrustLevel
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

private data class TrafficSample(
    val bytes: Long,
    val packets: Long,
    val durationMillis: Long,
    val topPackets: List<Pair<String, Long>>,
) {
    val mbps: Double
        get() = if (durationMillis <= 0) 0.0 else bytes * 8.0 / durationMillis / 1000.0
}

private val lock = Any()
private val serializerLock = Any()
private val samples = ArrayDeque<TrafficSample>()
private val serializer = ArcNetProvider.PacketSerializer()
private val counterByteBuffer = ByteBuffer.allocate(2 * 1024 * 1024)
private var currentBytes = 0L
private var currentPackets = 0L
private var currentPacketBytes = mutableMapOf<String, Long>()
private var lastPacket: Packet? = null
private var lastSampleMillis = System.currentTimeMillis()
private val serializationWarningAt = mutableMapOf<String, Long>()
private val sendPacketEventClass: Class<*>? = runCatching { Class.forName("mindustryX.events.SendPacketEvent") }.getOrNull()

fun trafficBudgetMbps(): Double =
    MdtStorage.getSetting(TRAFFIC_BUDGET_MBPS_KEY)?.toDoubleOrNull()?.takeIf { it > 0.0 }
        ?: defaultBudgetMbps

fun trafficRecoverMbps(): Double = trafficBudgetMbps() * 0.9

fun setTrafficBudgetMbps(value: Double) {
    MdtStorage.setSetting(TRAFFIC_BUDGET_MBPS_KEY, value.coerceAtLeast(0.1).toString())
}

fun currentTrafficMbps(): Double = synchronized(lock) {
    samples.lastOrNull()?.mbps ?: 0.0
}

fun averageTrafficMbps(count: Int = averageSamples): Double = synchronized(lock) {
    val list = samples.takeLast(count.coerceAtLeast(1))
    if (list.isEmpty()) 0.0 else list.map { it.mbps }.average()
}

fun lastTrafficPackets(): Long = synchronized(lock) {
    samples.lastOrNull()?.packets ?: 0L
}

fun topTrafficPackets(limit: Int = 5): List<Pair<String, Long>> = synchronized(lock) {
    samples.lastOrNull()?.topPackets?.take(limit.coerceAtLeast(1)) ?: emptyList()
}

fun trafficStatusText(): String {
    val budget = trafficBudgetMbps()
    val recover = trafficRecoverMbps()
    val current = currentTrafficMbps()
    val avg = averageTrafficMbps()
    val top = topTrafficPackets(8).takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "  [gray]${it.first}: [white]${formatBytes(it.second)}/s" }
        ?: "  [gray]暂无数据"
    return """
        |[cyan]估算上行：[white]${formatMbps(current)} Mbps[]（${formatMbps(avg)} Mbps 平均）
        |[cyan]实验性预算：[white]${formatMbps(budget)} Mbps[] / 恢复线：[white]${formatMbps(recover)} Mbps
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

private fun Packet.countBytesSafeLocked(): Int {
    return try {
        counterByteBuffer.clear()
        serializer.write(counterByteBuffer, this)
        counterByteBuffer.position()
    } catch (e: Throwable) {
        // 极端大包或未知包不应拖垮统计脚本；按 0 处理，并对同类失败做限频告警。
        warnSerializationFailure(this.javaClass.simpleName, e.message)
        0
    }
}

private fun eventMember(event: Any, name: String): Any? =
    runCatching { event.javaClass.getField(name).get(event) }.getOrNull()

private fun packetRate(event: Any): Int {
    val connections = (net.connections as List<*>).size
    return when {
        eventMember(event, "con") != null -> 1
        eventMember(event, "except") == null -> connections
        else -> max(0, connections - 1)
    }
}

private fun rotateSample() {
    val now = System.currentTimeMillis()
    val duration = (now - lastSampleMillis).coerceAtLeast(1L)
    val sample = synchronized(lock) {
        val top = currentPacketBytes.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key to it.value }
        val sample = TrafficSample(currentBytes, currentPackets, duration, top)
        samples.addLast(sample)
        while (samples.size > historySamples.coerceAtLeast(1)) samples.removeFirst()
        currentBytes = 0L
        currentPackets = 0L
        currentPacketBytes = mutableMapOf()
        lastSampleMillis = now
        sample
    }
    if (sample.bytes < 0) logger.warning("上行统计出现异常负数，将在下一轮自动恢复")
}

private fun canChangeBudget(operator: Player?): Boolean {
    if (operator == null) return true
    return with(trustLevel) { isTrustAdmin(operator) }
}

private fun formatMbps(value: Double): String = "%.2f".format(value)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun handleSendPacketEvent(event: Any) {
    val packet = eventMember(event, "packet")
    if (packet !is Packet) return

    val rate = packetRate(event)
    if (rate <= 0) return

    // MindustryX 广播同一个包时可能多次触发事件；沿用柠檬 trafficAnalysis 的去重方式。
    // 同时把 PacketSerializer + 复用 ByteBuffer 放进同一把锁，避免网络事件并发触发时互相覆盖 position。
    val packetBytes = synchronized(serializerLock) {
        if (packet == lastPacket) null
        else {
            lastPacket = packet
            packet.countBytesSafeLocked()
        }
    } ?: return

    val bytes = packetBytes.toLong() * rate
    if (bytes <= 0) return
    val name = "${Net.getPacketId(packet).toInt() and 0xff}:${packet.javaClass.simpleName}"

    synchronized(lock) {
        currentBytes += bytes
        currentPackets += rate.toLong()
        currentPacketBytes[name] = (currentPacketBytes[name] ?: 0L) + bytes
    }
}

if (sendPacketEventClass != null) {
    listen<Any>(sendPacketEventClass) { event ->
        handleSendPacketEvent(event)
    }
} else {
    logger.warning("未检测到 MindustryX SendPacketEvent；官方端将禁用精确上行包统计，仅保留预算配置与0值降级。")
}

registerVar("scoreboard.ext.traffic", "MDT估算上行显示", DynamicVar {
    val avg = averageTrafficMbps()
    if (avg <= 0.01 && Groups.player.size() == 0) return@DynamicVar null
    "{cK}估算上行: {cV}${formatMbps(avg)} Mbps".with()
})

onEnable {
    lastSampleMillis = System.currentTimeMillis()
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
                if (!canChangeBudget(player)) {
                    returnReply("[red]权限不足：只有 4级/admin 或控制台可以修改上行预算。".with())
                }
                val value = arg.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: replyUsage()
                setTrafficBudgetMbps(value)
                reply("[green]已设置实验性上行预算为 [white]${formatMbps(value)} Mbps[green]，恢复线约 [white]${formatMbps(trafficRecoverMbps())} Mbps".with())
            }
            "reset", "重置" -> {
                synchronized(lock) {
                    samples.clear()
                    currentBytes = 0L
                    currentPackets = 0L
                    currentPacketBytes.clear()
                    lastSampleMillis = System.currentTimeMillis()
                }
                reply("[green]已重置上行统计窗口。".with())
            }
            else -> replyUsage()
        }
    }
}

