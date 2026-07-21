@file:Depends("wayzer/map/performanceGuard", "性能优化模式")
@file:Depends("wayzer/reGrief/trafficMonitor", "上行流量估算")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.map

import arc.Core
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.MdtStorage
import wayzer.reGrief.TrafficMonitor
import wayzer.user.TrustLevel
import java.time.Duration
import kotlin.math.roundToInt

name = "服务器压力判断"

private val perfGuard = contextScript<PerformanceGuard>()
private val trafficMonitor = contextScript<TrafficMonitor>()
private val trustLevel = contextScript<TrustLevel>()

private val checkIntervalMillis by config.key(5_000L, "压力判断间隔(ms)")
private val sampleSize by config.key(6, "TPS滑动平均采样数")
private val defaultTpsLevel1 by config.key(45, "压力等级1 TPS阈值")
private val defaultTpsLevel2 by config.key(35, "压力等级2 TPS阈值")
private val defaultTpsLevel3 by config.key(30, "实验性压力等级3 TPS阈值")
private val defaultTpsLevel4 by config.key(25, "压力等级4 TPS阈值")
private val defaultRecoverTps by config.key(55, "TPS恢复阈值")
private val recoverSamplesRequired by config.key(2, "退出压力需要连续恢复采样数")
private val downgradeSamplesRequired by config.key(2, "压力降级需要连续稳定采样数")
private val trafficWarnRatio by config.key(1.0, "上行压力等级1预算倍率")
private val trafficLimitRatio by config.key(1.15, "上行压力等级2预算倍率")
private val trafficCriticalRatio by config.key(1.40, "上行压力等级3预算倍率")
private val throttleBaseInterval by config.key(240L, "v159网络压力同步限制等级1间隔(ms)")
private val throttleStepInterval by config.key(40L, "v159网络压力同步限制每级增加间隔(ms)")
private val throttleMaxLevel by config.key(3, "上行超限同步限制最高等级")
private val throttleRecoverSamplesRequired by config.key(1, "同步限制退出需要连续恢复采样数")
private val throttleDowngradeSamplesRequired by config.key(1, "同步限制降级需要连续稳定采样数")
private val throttleMinActiveMillis by config.key(5_000L, "同步限制启用后最短保持时间(ms)")
private val TPS_THRESHOLDS_KEY = "serverPressure.tpsThresholds"

private data class TpsThresholds(
    val level1: Int,
    val level2: Int,
    val level3: Int,
    val level4: Int,
    val recover: Int,
)

data class PressureSnapshot(
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val mode: String = "normal",
    val level: Int = 0,
    val tpsLevel: Int = 0,
    val trafficLevel: Int = 0,
    val networkLevel: Int = 0,
    val throttleLevel: Int = 0,
    val throttleIntervalMillis: Long = 0L,
    val currentTps: Int = 60,
    val averageTps: Double = 60.0,
    val currentTrafficMbps: Double = 0.0,
    val averageTrafficMbps: Double = 0.0,
    val currentSyncTrafficMbps: Double = 0.0,
    val averageSyncTrafficMbps: Double = 0.0,
    val averageTransferTrafficMbps: Double = 0.0,
    val trafficBudgetMbps: Double = 18.0,
    val pendingJoins: Int = 0,
    val activeStreams: Int = 0,
    val oldestPendingJoinMillis: Long = 0L,
    val tcpQueuedBytes: Long = 0L,
    val congestedConnections: Int = 0,
    val players: Int = 0,
    val units: Int = 0,
    val bullets: Int = 0,
    val syncEntities: Int = 0,
    val reason: String = "正常",
)

private val tpsSamples = ArrayDeque<Int>()
private var snapshot = PressureSnapshot()
private var throttleLevelState = 0
private var trafficOverBudgetSamples = 0
private var trafficRecoverSamples = 0
private var throttleDowngradeCandidateLevel = -1
private var throttleDowngradeSamples = 0
private var throttleActiveSinceMillis = 0L
private var lastBroadcastLevel = 0
private var recoverSamples = 0
private var downgradeCandidateLevel = -1
private var downgradeSamples = 0
private var tpsThresholdCache: TpsThresholds? = null

private fun currentTps(): Int = Core.graphics.framesPerSecond.coerceIn(0, 255)

private fun defaultTpsThresholds(): TpsThresholds =
    normalizeTpsThresholds(defaultTpsLevel1, defaultTpsLevel2, defaultTpsLevel3, defaultTpsLevel4, defaultRecoverTps)

private fun normalizeTpsThresholds(level1: Int, level2: Int, level3: Int, level4: Int, recover: Int): TpsThresholds {
    val ordered = listOf(level1, level2, level3, level4).map { it.coerceIn(1, 255) }
        .zipWithNext()
        .all { (a, b) -> a > b }
    if (!ordered) return TpsThresholds(45, 35, 30, 25, 55)
    return TpsThresholds(
        level1.coerceIn(1, 255),
        level2.coerceIn(1, 255),
        level3.coerceIn(1, 255),
        level4.coerceIn(1, 255),
        recover.coerceIn(1, 255),
    )
}

private fun parseTpsThresholds(raw: String?): TpsThresholds? {
    val parts = raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
    if (parts.size < 5) return null
    return normalizeTpsThresholds(parts[0], parts[1], parts[2], parts[3], parts[4])
}

private fun tpsThresholds(): TpsThresholds {
    tpsThresholdCache?.let { return it }
    val loaded = parseTpsThresholds(MdtStorage.getSetting(TPS_THRESHOLDS_KEY)) ?: defaultTpsThresholds()
    tpsThresholdCache = loaded
    return loaded
}

private fun saveTpsThresholds(thresholds: TpsThresholds?) {
    tpsThresholdCache = thresholds
    MdtStorage.setSetting(
        TPS_THRESHOLDS_KEY,
        thresholds?.let { "${it.level1},${it.level2},${it.level3},${it.level4},${it.recover}" }
    )
    tpsSamples.clear()
    recoverSamples = 0
    resetDowngradeCandidate()
}

private fun thresholdText(thresholds: TpsThresholds = tpsThresholds()): String =
    "L1<${thresholds.level1} / L2<${thresholds.level2} / L3<${thresholds.level3} / L4<${thresholds.level4} / 恢复≥${thresholds.recover}"

private fun canManagePressure(operator: Player?): Boolean {
    if (operator == null) return true
    return with(trustLevel) { hasTrustLevel(operator, "3+") }
}

private fun averageTps(): Double {
    val limit = sampleSize.coerceAtLeast(1)
    while (tpsSamples.size >= limit) tpsSamples.removeFirst()
    tpsSamples.addLast(currentTps())
    return tpsSamples.sum().toDouble() / tpsSamples.size
}

private fun tpsPressureLevel(mode: String, avg: Double): Int {
    val thresholds = tpsThresholds()
    if (mode == "off") return 0
    if (avg >= thresholds.level1) return 0
    return when {
        avg < thresholds.level4 -> 4
        avg < thresholds.level3 -> 3
        avg < thresholds.level2 -> 2
        else -> 1
    }
}

private fun resetThrottleStability(clearActiveSince: Boolean = true) {
    trafficRecoverSamples = 0
    throttleDowngradeCandidateLevel = -1
    throttleDowngradeSamples = 0
    if (clearActiveSince) throttleActiveSinceMillis = 0L
}

private fun resetThrottleState() {
    throttleLevelState = 0
    trafficOverBudgetSamples = 0
    resetThrottleStability(clearActiveSince = true)
}

private fun stableThrottleLevel(rawTarget: Int, trafficAvg: Double, recoverMbps: Double): Int {
    val maxLevel = throttleMaxLevel.coerceAtLeast(0)
    if (maxLevel <= 0) {
        resetThrottleState()
        return 0
    }

    val previous = throttleLevelState.coerceIn(0, maxLevel)
    if (previous != throttleLevelState) throttleLevelState = previous
    val target = rawTarget.coerceIn(0, maxLevel)
    val now = System.currentTimeMillis()

    if (target > previous) {
        throttleLevelState = target
        resetThrottleStability(clearActiveSince = false)
        throttleActiveSinceMillis = now
        return throttleLevelState
    }

    if (previous <= 0) {
        throttleLevelState = target
        if (target > 0 && throttleActiveSinceMillis <= 0L) throttleActiveSinceMillis = now
        if (target <= 0) resetThrottleStability(clearActiveSince = true)
        return throttleLevelState
    }

    if (target == previous) {
        resetThrottleStability(clearActiveSince = false)
        return previous
    }

    // 同步限制会反向降低估算上行；若一恢复就立刻解除，波次单位/快照又会把上行推回超限。
    // 因此同步限制单独使用“最短保持时间 + 连续采样”滞回，避免 0/1/2 频繁启停和刷屏。
    val minHold = throttleMinActiveMillis.coerceAtLeast(0L)
    if (throttleActiveSinceMillis > 0L && now - throttleActiveSinceMillis < minHold) {
        return previous
    }

    if (target <= 0) {
        throttleDowngradeCandidateLevel = -1
        throttleDowngradeSamples = 0
        if (trafficAvg > recoverMbps) {
            trafficRecoverSamples = 0
            return previous
        }
        trafficRecoverSamples++
        if (trafficRecoverSamples >= throttleRecoverSamplesRequired.coerceAtLeast(1)) {
            resetThrottleState()
            return 0
        }
        return previous
    }

    trafficRecoverSamples = 0
    if (throttleDowngradeCandidateLevel != target) {
        throttleDowngradeCandidateLevel = target
        throttleDowngradeSamples = 1
    } else {
        throttleDowngradeSamples++
    }
    if (throttleDowngradeSamples >= throttleDowngradeSamplesRequired.coerceAtLeast(1)) {
        throttleLevelState = target
        resetThrottleStability(clearActiveSince = false)
        return throttleLevelState
    }
    return previous
}

private fun updateThrottleLevel(rawTarget: Int, trafficAvg: Double, budget: Double): Int {
    if (budget <= 0.0) {
        resetThrottleState()
        return 0
    }

    val recoverMbps = with(trafficMonitor) { trafficRecoverMbps() }
    if (trafficAvg >= budget * trafficWarnRatio) {
        trafficOverBudgetSamples++
    } else if (trafficAvg <= recoverMbps) {
        trafficOverBudgetSamples = 0
    }

    var target = rawTarget
    if (trafficAvg <= recoverMbps && rawTarget <= 0) target = (throttleLevelState - 1).coerceAtLeast(0)
    if (trafficOverBudgetSamples >= 12) target = maxOf(target, 3)
    else if (trafficOverBudgetSamples >= 6) target = maxOf(target, 2)
    return stableThrottleLevel(target, trafficAvg, recoverMbps)
}

private fun syncTrafficPressureLevel(mode: String, syncTrafficAvg: Double, budget: Double): Int {
    if (mode == "off" || budget <= 0.0) return 0
    return when {
        syncTrafficAvg >= budget * trafficCriticalRatio -> 3
        syncTrafficAvg >= budget * trafficLimitRatio -> 2
        syncTrafficAvg >= budget * trafficWarnRatio -> 1
        else -> 0
    }
}

private fun networkPressureLevel(totalAvg: Double, budget: Double): Int {
    if (budget <= 0.0) return 0
    val n = with(trafficMonitor) { networkTransferSnapshot() }
    return when {
        totalAvg >= budget * trafficCriticalRatio || n.congestedConnections >= 2 || n.oldestJoiningMillis >= 60_000L -> 3
        totalAvg >= budget * trafficLimitRatio || n.congestedConnections >= 1 || n.oldestJoiningMillis >= 30_000L -> 2
        totalAvg >= budget * trafficWarnRatio || (n.activeStreams > 0 && n.oldestJoiningMillis >= 12_000L) -> 1
        else -> 0
    }
}

private fun buildReason(tpsLevel: Int, trafficLevel: Int): String = when {
    tpsLevel > 0 && trafficLevel > 0 -> "TPS与游戏同步上行同时超限"
    tpsLevel > 0 -> "TPS过低"
    trafficLevel > 0 -> "游戏同步上行超预算"
    else -> "正常"
}

private fun resetDowngradeCandidate() {
    downgradeCandidateLevel = -1
    downgradeSamples = 0
}

private fun stablePressureLevel(rawLevel: Int): Int {
    val previous = snapshot.level.coerceIn(0, 4)
    if (previous <= 0) {
        recoverSamples = 0
        resetDowngradeCandidate()
        return rawLevel
    }

    if (rawLevel > previous) {
        recoverSamples = 0
        resetDowngradeCandidate()
        return rawLevel
    }

    if (rawLevel == previous) {
        recoverSamples = 0
        resetDowngradeCandidate()
        return previous
    }

    // TPS和游戏同步上行已分别计算原始等级；两者都恢复后即可连续采样退出。
    // 不再用TPS>=55作为所有压力的共同恢复门槛，否则纯同步上行压力在TPS=50时永不退出。
    if (rawLevel == 0) {
        resetDowngradeCandidate()
        recoverSamples++
        return if (recoverSamples >= recoverSamplesRequired.coerceAtLeast(1)) 0 else previous
    }

    // 非零降级需要连续几轮保持目标等级，避免在阈值附近 1/2/3 来回切换措施。
    recoverSamples = 0
    if (downgradeCandidateLevel != rawLevel) {
        downgradeCandidateLevel = rawLevel
        downgradeSamples = 1
    } else {
        downgradeSamples++
    }
    return if (downgradeSamples >= downgradeSamplesRequired.coerceAtLeast(1)) {
        resetDowngradeCandidate()
        rawLevel
    } else {
        previous
    }
}

private fun updatePressureSnapshot() {
    val mode = with(perfGuard) { performanceMode() }
    val avgTps = averageTps()
    val trafficCurrent = with(trafficMonitor) { currentTrafficMbps() }
    val trafficAvg = with(trafficMonitor) { averageTrafficMbps() }
    val syncCurrent = with(trafficMonitor) { currentSyncTrafficMbps() }
    val syncAvg = with(trafficMonitor) { averageSyncTrafficMbps() }
    val transferAvg = with(trafficMonitor) { averageTransferTrafficMbps() }
    val budget = with(trafficMonitor) { trafficBudgetMbps() }
    val network = with(trafficMonitor) { networkTransferSnapshot() }

    val sampled = tpsSamples.size >= sampleSize.coerceAtLeast(1)
    val tpsLevel = if (sampled) tpsPressureLevel(mode, avgTps) else 0
    val trafficLevel = if (sampled) syncTrafficPressureLevel(mode, syncAvg, budget) else 0
    val networkLevel = networkPressureLevel(trafficAvg, budget)
    val throttleLevel = updateThrottleLevel(maxOf(trafficLevel.coerceAtMost(3), networkLevel), trafficAvg, budget)
    val rawLevel = if (mode == "off") 0 else maxOf(tpsLevel, trafficLevel)

    val level = if (mode == "off") {
        recoverSamples = 0
        resetDowngradeCandidate()
        0
    } else if (!sampled) 0 else stablePressureLevel(rawLevel)

    val interval = if (throttleLevel <= 0) 0L
    else throttleBaseInterval + (throttleLevel - 1) * throttleStepInterval

    snapshot = PressureSnapshot(
        mode = mode,
        level = level.coerceIn(0, 4),
        tpsLevel = tpsLevel,
        trafficLevel = trafficLevel,
        networkLevel = networkLevel,
        throttleLevel = throttleLevel,
        throttleIntervalMillis = interval,
        currentTps = currentTps(),
        averageTps = avgTps,
        currentTrafficMbps = trafficCurrent,
        averageTrafficMbps = trafficAvg,
        currentSyncTrafficMbps = syncCurrent,
        averageSyncTrafficMbps = syncAvg,
        averageTransferTrafficMbps = transferAvg,
        trafficBudgetMbps = budget,
        pendingJoins = network.joiningConnections,
        activeStreams = network.activeStreams,
        oldestPendingJoinMillis = network.oldestJoiningMillis,
        tcpQueuedBytes = network.tcpQueuedBytes,
        congestedConnections = network.congestedConnections,
        players = Groups.player.size(),
        units = Groups.unit.size(),
        bullets = Groups.bullet.size(),
        syncEntities = Groups.sync.size(),
        reason = if (mode == "off") "性能优化关闭" else if (!sampled) "采样中" else buildReason(tpsLevel, trafficLevel),
    )

    if (snapshot.level > lastBroadcastLevel) {
        broadcast(
            "[yellow][服务器压力] {reason}，性能等级升至 [white]{level}[yellow]；TPS均值 [white]{tps}[yellow]，同步上行 [white]{traffic} Mbps[yellow]。".with(
                "reason" to snapshot.reason,
                "level" to snapshot.level,
                "tps" to snapshot.averageTps.roundToInt(),
                "traffic" to "%.2f".format(snapshot.averageSyncTrafficMbps),
            )
        )
        lastBroadcastLevel = snapshot.level
    } else if (snapshot.level == 0 && lastBroadcastLevel > 0 && recoverSamples >= 2) {
        broadcast("[green][服务器压力] TPS/游戏同步上行已恢复，退出自动性能等级。".with())
        lastBroadcastLevel = 0
    }
}

fun currentPressure(): PressureSnapshot = snapshot
fun pressureLevel(): Int = snapshot.level
fun pressureMode(): String = snapshot.mode
fun trafficOverLimit(): Boolean = snapshot.trafficLevel > 0
fun networkOverLimit(): Boolean = snapshot.networkLevel > 0
fun throttleIntervalMillis(): Long = snapshot.throttleIntervalMillis

fun pressureStatusText(): String {
    val s = currentPressure()
    return """
        |[cyan]性能模式：[white]${s.mode}
        |[cyan]性能等级：[white]${s.level}[]（TPS:${s.tpsLevel} / 游戏同步:${s.trafficLevel}）
        |[cyan]网络保护：[white]${s.networkLevel}[] / 快照限制 ${s.throttleLevel}（${s.throttleIntervalMillis}ms）
        |[cyan]TPS：[white]${s.currentTps}[] / 均值 [white]${s.averageTps.roundToInt()}
        |[cyan]TPS阈值：[white]${thresholdText()}
        |[cyan]总上行：[white]${"%.2f".format(s.averageTrafficMbps)} Mbps[] / 同步 [white]${"%.2f".format(s.averageSyncTrafficMbps)}[] / 世界流 [white]${"%.2f".format(s.averageTransferTrafficMbps)}
        |[cyan]待加入/活动流/TCP拥塞：[white]${s.pendingJoins}/${s.activeStreams}/${s.congestedConnections}[]，最老等待 ${s.oldestPendingJoinMillis / 1000}s
        |[cyan]玩家/单位/子弹/同步实体：[white]${s.players}/${s.units}/${s.bullets}/${s.syncEntities}
        |[cyan]原因：[white]${s.reason}
    """.trimMargin()
}

registerVar("scoreboard.ext.pressure", "MDT服务器压力状态", DynamicVar {
    val s = currentPressure()
    if (s.level <= 0 && s.throttleLevel <= 0) return@DynamicVar null
    "{cK}压力等级: {cV}${s.level}[] / 同步限制 ${s.throttleLevel}".with()
})

listen<EventType.WorldLoadEvent> {
    tpsSamples.clear()
    snapshot = PressureSnapshot(mode = with(perfGuard) { performanceMode() })
    resetThrottleState()
    lastBroadcastLevel = 0
    recoverSamples = 0
    resetDowngradeCandidate()
}

listen<EventType.ResetEvent> {
    tpsSamples.clear()
    snapshot = PressureSnapshot(mode = with(perfGuard) { performanceMode() })
    resetThrottleState()
    lastBroadcastLevel = 0
    recoverSamples = 0
    resetDowngradeCandidate()
}

onEnable {
    launch(Dispatchers.game) {
        while (true) {
            delay(Duration.ofMillis(checkIntervalMillis.coerceAtLeast(1000L)).toMillis())
            updatePressureSnapshot()
        }
    }
}

command("pressure", "查看/管理服务器压力判断") {
    usage = "[status|tps|tps <L1> <L2> <L3> <L4> [恢复]|tps reset]"
    aliases = listOf("服务器压力", "压力")
    body {
        when (arg.getOrNull(0)?.lowercase() ?: "status") {
            "status", "状态" -> reply(pressureStatusText().with())
            "tps", "threshold", "thresholds", "阈值", "tps阈值" -> {
                val sub = arg.getOrNull(1)?.lowercase()
                if (sub == null || sub == "status" || sub == "状态") {
                    returnReply(("[cyan]当前TPS压力阈值：[white]${thresholdText()}\n" +
                            "[gray]用法：/pressure tps <L1> <L2> <L3> <L4> [恢复]\n" +
                            "[gray]示例：/pressure tps 45 35 30 25 55").with())
                }
                if (!canManagePressure(player)) {
                    returnReply("[red]权限不足：只有 3+级、4级/admin 或控制台可以修改TPS压力阈值。".with())
                }
                if (sub == "reset" || sub == "恢复默认" || sub == "默认") {
                    saveTpsThresholds(null)
                    returnReply(("[green]已恢复默认TPS压力阈值：[white]${thresholdText()}").with())
                }
                val nums = arg.drop(1).mapNotNull { it.toIntOrNull() }
                if (nums.size !in 4..5) {
                    returnReply("[red]请输入4到5个数字：/pressure tps <L1> <L2> <L3> <L4> [恢复]".with())
                }
                val recover = nums.getOrNull(4) ?: tpsThresholds().recover
                if (!(nums[0] > nums[1] && nums[1] > nums[2] && nums[2] > nums[3])) {
                    returnReply("[red]阈值必须从高到低排列，例如：45 35 30 25 55。".with())
                }
                val thresholds = normalizeTpsThresholds(nums[0], nums[1], nums[2], nums[3], recover)
                saveTpsThresholds(thresholds)
                broadcast("[yellow][服务器压力] {operator} 已设置TPS压力阈值：[white]{thresholds}".with(
                    "operator" to (player?.name ?: "控制台"),
                    "thresholds" to thresholdText(thresholds),
                ))
            }
            else -> replyUsage()
        }
    }
}
