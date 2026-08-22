@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/user/trustPoint", "MDC发放事件")

package wayzer.ext

import wayzer.lib.MdcGrantedEvent
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.TrustLevelChangedEvent
import wayzer.user.TrustLevel
import arc.Core
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 服务器状态统计（可分离设计）：
 * - 游戏线程事件只把事件投递到队列（绝不阻塞主线程）；
 * - 统计状态、数据库读写、JSON 文件写入全部在 Dispatchers.IO 的独立协程中完成；
 * - 计数全部增量维护（MdtSettings 键值 + StatsPlayers 主键登记），不做全表 COUNT；
 * - 开关独立（MdtSettings serverStats.enabled），关闭时仅保留最终文件；
 * - Web 侧独立：只读取本脚本输出的 JSON 文件，与统计开关互不依赖。
 */
name = "服务器状态统计"

private val trustLevel = contextScript<TrustLevel>()

private val enabledDefault by config.key(true, "服务器状态统计是否启用(默认开启)")
private val updateIntervalMillis by config.key(60_000L, "统计刷新/持久化/写文件间隔(ms)")
private val snapshotIntervalMillis by config.key(5_000L, "服务器状态快照刷新间隔(ms)")
private val statsOutputPath by config.key("stats/server-status.json", "统计JSON输出路径(相对config目录)")

private val ENABLED_KEY = MdtStorage.SERVER_STATS_ENABLED_KEY
private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

@Volatile private var statsEnabled: Boolean = false
@Volatile private var serverStartMillis: Long = System.currentTimeMillis()
@Volatile private var latestStatusText: String = "服务器状态统计加载中..."
@Volatile private var latestSnapshot = ServerInfoSnapshot()

private val statsEventQueue = ConcurrentLinkedQueue<StatsEvent>()

private sealed class StatsEvent
private data class StatsJoinEvent(val uid: String, val date: String, val levelCode: String) : StatsEvent()
private data class StatsLevelEvent(val uid: String, val newLevelCode: String) : StatsEvent()
private data class StatsMdcEvent(val uid: String, val amount: Int) : StatsEvent()

/** 只由统计 IO 协程单写者访问的内存状态（避免游戏线程读写竞争）。 */
private class StatsState {
    var date: String = ""
    var totalPlayers: Long = 0L
    var totalFlow: Long = 0L
    var totalMdcGranted: Long = 0L
    var todayPlayers: Long = 0L
    var todayFlow: Long = 0L
    var todayMdcGranted: Long = 0L
    var rank0: Long = 0L
    var rank1: Long = 0L
    var rank2: Long = 0L
    var rank3: Long = 0L
    var rank3plus: Long = 0L
}

private data class ServerInfoSnapshot(
    val map: String = "",
    val mode: String = "",
    val wave: Int = 0,
    val tps: Int = 0,
    val uptimeSeconds: Long = 0L,
    val onlinePlayers: Int = 0,
    val onlineByRank: Map<String, Int> = emptyMap(),
)

private val stats = StatsState()

private fun statsOutputFile(): File = File(Vars.dataDirectory.file(), statsOutputPath)

/** 等级代码规范化（兼容 TrustLevelChangedEvent 中的 4+admin 等显示代码）。 */
private fun normalizeStatsLevel(level: String): String = when (level.trim().lowercase()) {
    "0", "1", "2", "3" -> level.trim().lowercase()
    "3+", "3p", "3plus" -> "3+"
    "3++", "3pp", "3plusplus" -> "3++"
    "4", "admin", "4+admin", "4admin" -> "4"
    else -> "0"
}

/** 等级分布桶：0/1/2/3 各自独立，3+（含 3++、4、4+admin）合并。 */
private fun rankBucket(level: String): String = when (normalizeStatsLevel(level)) {
    "0" -> "0"
    "1" -> "1"
    "2" -> "2"
    "3" -> "3"
    else -> "3+"
}

private fun StatsState.bumpRank(bucket: String) {
    when (bucket) {
        "0" -> rank0++
        "1" -> rank1++
        "2" -> rank2++
        "3" -> rank3++
        else -> rank3plus++
    }
}

private fun StatsState.decRank(bucket: String) {
    when (bucket) {
        "0" -> rank0 = (rank0 - 1).coerceAtLeast(0L)
        "1" -> rank1 = (rank1 - 1).coerceAtLeast(0L)
        "2" -> rank2 = (rank2 - 1).coerceAtLeast(0L)
        "3" -> rank3 = (rank3 - 1).coerceAtLeast(0L)
        else -> rank3plus = (rank3plus - 1).coerceAtLeast(0L)
    }
}

private fun StatsState.moveRank(fromBucket: String, toBucket: String) {
    if (fromBucket == toBucket) return
    decRank(fromBucket)
    bumpRank(toBucket)
}

/** 跨天滚动：仅重置“今日”计数（总量的等级分布等为累计值不动）。 */
private fun rolloverTo(date: String) {
    if (stats.date == date) return
    stats.date = date
    stats.todayPlayers = 0L
    stats.todayFlow = 0L
    stats.todayMdcGranted = 0L
}

private fun StatsState.toRecord() = MdtStorage.ServerStatsRecord(
    date = date,
    totalPlayers = totalPlayers,
    totalFlow = totalFlow,
    totalMdcGranted = totalMdcGranted,
    todayPlayers = todayPlayers,
    todayFlow = todayFlow,
    todayMdcGranted = todayMdcGranted,
    rank0 = rank0,
    rank1 = rank1,
    rank2 = rank2,
    rank3 = rank3,
    rank3plus = rank3plus,
)

/** 返回 true 表示内存计数有变化（需要持久化）。 */
private fun processStatsEvent(event: StatsEvent): Boolean {
    when (event) {
        is StatsJoinEvent -> {
            rolloverTo(event.date)
            stats.totalFlow++
            stats.todayFlow++
            val mark = MdtStorage.markStatsPlayerSeen(event.uid, event.date, event.levelCode)
            if (mark.isNew) {
                stats.totalPlayers++
                stats.todayPlayers++
                stats.bumpRank(rankBucket(event.levelCode))
            } else {
                if (mark.countedToday) stats.todayPlayers++
                mark.prevLevel?.let { prev -> stats.moveRank(rankBucket(prev), rankBucket(event.levelCode)) }
            }
        }
        is StatsLevelEvent -> {
            val moved = MdtStorage.moveStatsPlayerLevel(event.uid, event.newLevelCode)
            if (moved != null) stats.moveRank(rankBucket(moved.first), rankBucket(event.newLevelCode))
        }
        is StatsMdcEvent -> {
            stats.todayMdcGranted += event.amount
            stats.totalMdcGranted += event.amount
        }
    }
    return true
}

// ---------- 事件钩子（游戏线程：只投递，不做事） ----------

listen<EventType.PlayerJoin> {
    if (!statsEnabled) return@listen
    val data = PlayerData[it.player]
    val level = with(trustLevel) { getTrustLevelCode(data.id, it.player) }
    statsEventQueue.offer(StatsJoinEvent(data.id, LocalDate.now().toString(), normalizeStatsLevel(level)))
}
listen<TrustLevelChangedEvent> {
    if (!statsEnabled) return@listen
    statsEventQueue.offer(StatsLevelEvent(it.uid, normalizeStatsLevel(it.newLevel)))
}

listen<MdcGrantedEvent> {
    if (!statsEnabled) return@listen
    statsEventQueue.offer(StatsMdcEvent(it.uid, it.amount))
}

// ---------- 快照（游戏线程每几秒刷新一次，IO 线程只读） ----------

private fun refreshServerSnapshot() {
    val counts = linkedMapOf("0" to 0, "1" to 0, "2" to 0, "3" to 0, "3+" to 0)
    val players = Groups.player.toList()
    players.forEach { p ->
        val level = with(trustLevel) { getTrustLevelCode(p) }
        val bucket = rankBucket(level)
        counts[bucket] = (counts[bucket] ?: 0) + 1
    }
    latestSnapshot = ServerInfoSnapshot(
        map = runCatching { Vars.state.map?.name() ?: "" }.getOrDefault(""),
        mode = runCatching { Vars.state.rules.mode().name }.getOrDefault(""),
        wave = runCatching { Vars.state.wave }.getOrDefault(0),
        tps = Core.graphics.framesPerSecond.coerceIn(0, 255),
        uptimeSeconds = (System.currentTimeMillis() - serverStartMillis) / 1000L,
        onlinePlayers = players.size,
        onlineByRank = counts,
    )
}

// ---------- JSON 文件输出 ----------

private fun jsonString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t") + "\""

private fun rankMapJson(r0: Long, r1: Long, r2: Long, r3: Long, r3plus: Long): String =
    """{"0": $r0, "1": $r1, "2": $r2, "3": $r3, "3+": $r3plus}"""

private fun rankMapIntJson(m: Map<String, Int>): String =
    """{"0": ${m["0"] ?: 0}, "1": ${m["1"] ?: 0}, "2": ${m["2"] ?: 0}, "3": ${m["3"] ?: 0}, "3+": ${m["3+"] ?: 0}}"""

private fun buildStatsJson(): String {
    val snap = latestSnapshot
    return buildString {
        appendLine("{")
        appendLine("  \"schema\": 1,")
        appendLine("  \"enabled\": $statsEnabled,")
        appendLine("  \"updatedAt\": ${jsonString(TIME_FORMATTER.format(Instant.now()))},")
        appendLine("  \"serverInfo\": {")
        appendLine("    \"map\": ${jsonString(snap.map)},")
        appendLine("    \"mode\": ${jsonString(snap.mode)},")
        appendLine("    \"wave\": ${snap.wave},")
        appendLine("    \"tps\": ${snap.tps},")
        appendLine("    \"uptimeSeconds\": ${snap.uptimeSeconds},")
        appendLine("    \"onlinePlayers\": ${snap.onlinePlayers},")
        appendLine("    \"onlineByRank\": ${rankMapIntJson(snap.onlineByRank)}")
        appendLine("  },")
        appendLine("  \"today\": {")
        appendLine("    \"date\": ${jsonString(stats.date)},")
        appendLine("    \"players\": ${stats.todayPlayers},")
        appendLine("    \"flow\": ${stats.todayFlow},")
        appendLine("    \"mdcGranted\": ${stats.todayMdcGranted}")
        appendLine("  },")
        appendLine("  \"total\": {")
        appendLine("    \"players\": ${stats.totalPlayers},")
        appendLine("    \"flow\": ${stats.totalFlow},")
        appendLine("    \"mdcGranted\": ${stats.totalMdcGranted}")
        appendLine("  },")
        appendLine("  \"totalByRank\": ${rankMapJson(stats.rank0, stats.rank1, stats.rank2, stats.rank3, stats.rank3plus)}")
        appendLine("}")
    }
}

private fun writeStatsFile() {
    val text = buildStatsJson()
    runCatching {
        val target = statsOutputFile()
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }.onFailure { logger.warning("服务器状态统计写文件失败: $it") }
}

private fun buildStatusText(): String {
    if (!statsEnabled) {
        return "[yellow]服务器状态统计已关闭[gray]（/serverstats on 开启；Web 仍可展示最后一次写入的数据）"
    }
    val snap = latestSnapshot
    return """
        |[green]服务器状态统计[]
        |[cyan]输出文件：[white]${statsOutputFile().path}
        |[cyan]统计日期：[white]${stats.date}
        |[cyan]今日：[white]${stats.todayPlayers}[cyan] 人游玩，人流量 [white]${stats.todayFlow}[cyan]，发放MDC [white]${stats.todayMdcGranted}
        |[cyan]总计：[white]${stats.totalPlayers}[cyan] 人游玩，人流量 [white]${stats.totalFlow}[cyan]，发放MDC [white]${stats.totalMdcGranted}
        |[cyan]等级分布(累计)：[white]0级 ${stats.rank0}[gray]|[white]1级 ${stats.rank1}[gray]|[white]2级 ${stats.rank2}[gray]|[white]3级 ${stats.rank3}[gray]|[white]3+级 ${stats.rank3plus}
        |[gray]在线：[white]${snap.onlinePlayers}[gray] 人，等级分布 ${snap.onlineByRank.entries.joinToString(" ") { "${it.key}:${it.value}" }}
    """.trimMargin()
}

private fun canManageStats(operator: Player?): Boolean =
    operator == null || with(trustLevel) { isTrustAdmin(operator) }

onEnable {
    serverStartMillis = System.currentTimeMillis()
    logger.info("服务器状态统计已加载：输出=${statsOutputFile().path}")

    launch(Dispatchers.game) {
        while (true) {
            delay(snapshotIntervalMillis.coerceAtLeast(1_000L))
            refreshServerSnapshot()
        }
    }

    launch(Dispatchers.IO) {
        // 所有数据库读写都在 IO 协程内完成，不占用游戏线程。
        statsEnabled = MdtStorage.getSetting(ENABLED_KEY)?.toBoolean() ?: enabledDefault
        val loaded = MdtStorage.loadServerStats()
        stats.date = loaded.date
        stats.totalPlayers = loaded.totalPlayers
        stats.totalFlow = loaded.totalFlow
        stats.totalMdcGranted = loaded.totalMdcGranted
        stats.todayPlayers = loaded.todayPlayers
        stats.todayFlow = loaded.todayFlow
        stats.todayMdcGranted = loaded.todayMdcGranted
        stats.rank0 = loaded.rank0
        stats.rank1 = loaded.rank1
        stats.rank2 = loaded.rank2
        stats.rank3 = loaded.rank3
        stats.rank3plus = loaded.rank3plus

        latestStatusText = buildStatusText()
        writeStatsFile()
        var dirty = false
        var lastWrittenEnabled = statsEnabled

        while (true) {
            delay(updateIntervalMillis.coerceAtLeast(5_000L))
            if (!statsEnabled) {
                // 关闭状态：只写一次“已关闭”的最终文件，供 Web 展示最后数据。
                if (lastWrittenEnabled) {
                    writeStatsFile()
                    latestStatusText = buildStatusText()
                    lastWrittenEnabled = false
                }
                continue
            }
            lastWrittenEnabled = true
            while (true) {
                val event = statsEventQueue.poll() ?: break
                runCatching { processStatsEvent(event) }
                    .onFailure { logger.warning("服务器状态统计事件处理失败: $it") }
                dirty = true
            }
            if (dirty) {
                runCatching { MdtStorage.saveServerStats(stats.toRecord()) }
                    .onFailure { logger.warning("服务器状态统计持久化失败: $it") }
                dirty = false
            }
            latestStatusText = buildStatusText()
            writeStatsFile()
        }
    }
}

command("serverstats", "管理指令：查看/开关服务器状态统计") {
    usage = "[status|on|off]"
    aliases = listOf("统计", "状态统计", "statusstats")
    body {
        when (arg.getOrNull(0)?.lowercase() ?: "status") {
            "status", "状态", "查看" -> reply(latestStatusText.with())
            "on", "开", "开启" -> {
                if (!canManageStats(player)) {
                    returnReply("[red]权限不足：只有4级/admin或控制台可以开关服务器状态统计。".with())
                }
                withContext(Dispatchers.IO) { MdtStorage.setSetting(ENABLED_KEY, "true") }
                statsEnabled = true
                reply("[green]已开启服务器状态统计[gray]（JSON 将在下个刷新周期写入）。".with())
            }
            "off", "关", "关闭" -> {
                if (!canManageStats(player)) {
                    returnReply("[red]权限不足：只有4级/admin或控制台可以开关服务器状态统计。".with())
                }
                withContext(Dispatchers.IO) { MdtStorage.setSetting(ENABLED_KEY, "false") }
                statsEnabled = false
                reply("[yellow]已关闭服务器状态统计[gray]（Web 仍可展示最后一次写入的数据）。".with())
            }
            else -> replyUsage()
        }
    }
}
