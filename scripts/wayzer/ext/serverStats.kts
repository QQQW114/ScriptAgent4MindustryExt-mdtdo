@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.ext

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 服务器状态统计（可分离设计）：
 * - 游戏线程事件只把事件投递到队列（绝不阻塞主线程，也不查库）；
 * - 统计状态、数据库读写、JSON 文件写入全部在 Dispatchers.IO 的独立协程中完成；
 * - 计数全部增量维护（MdtSettings 键值 + 主键/日期键登记），不做全表 COUNT；
 * - 履历明细分两级且行数有界：每天 1 行汇总 + 每天最多 24 行小时明细；
 * - 社区互动（帖子/评论/点赞/认可）不重复存一份，直接按日期区间聚合现有业务表；
 * - 开关独立（MdtSettings serverStats.enabled），关闭时仅保留最终文件；
 * - Web 侧独立：只读取本脚本输出的 JSON 文件，与统计开关互不依赖。
 */
name = "服务器状态统计"

private val trustLevel = contextScript<TrustLevel>()

private val enabledDefault by config.key(true, "服务器状态统计是否启用(默认开启)")
private val updateIntervalMillis by config.key(60_000L, "统计刷新/持久化/写文件间隔(ms)")
private val snapshotIntervalMillis by config.key(5_000L, "服务器状态快照刷新间隔(ms)")
private val communityIntervalMillis by config.key(300_000L, "社区互动(帖子/点赞/认可)聚合刷新间隔(ms)")
private val dailySeriesDays by config.key(14, "近N天人数曲线天数")
private val hourlySeriesHours by config.key(24, "一天内小时曲线桶数(24)")
private val dailyKeepDays by config.key(400L, "每日汇总保留天数(超出按日期裁剪)")
private val statsOutputPath by config.key("stats/server-status.json", "统计JSON输出路径(相对config目录)")

private val ENABLED_KEY = MdtStorage.SERVER_STATS_ENABLED_KEY
private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
private val DAY_LABEL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
private val ZONE: ZoneId = ZoneId.systemDefault()

@Volatile private var statsEnabled: Boolean = false
@Volatile private var serverStartMillis: Long = System.currentTimeMillis()
@Volatile private var latestStatusText: String = "服务器状态统计加载中..."
@Volatile private var latestSnapshot = ServerInfoSnapshot()

private val statsEventQueue = ConcurrentLinkedQueue<StatsEvent>()
/** 统计已启用时记录每位在线玩家的进入时刻（只由游戏线程读写）。 */
private val sessionStartMillis = ConcurrentHashMap<String, Long>()

private sealed class StatsEvent
private data class StatsJoinEvent(val uid: String, val date: String, val hour: Int, val levelCode: String) : StatsEvent()
private data class StatsLeaveEvent(val uid: String, val date: String, val playMillis: Long) : StatsEvent()
private data class StatsChatEvent(val date: String) : StatsEvent()
private data class StatsLevelEvent(val uid: String, val newLevelCode: String) : StatsEvent()

/**
 * 只由统计 IO 协程单写者访问的内存状态（避免游戏线程读写竞争）。
 *
 * 逐日字段用「日期 → 值」保存而不是单一 today 字段：跨天瞬间（00:00 前后）仍在队列里的
 * 事件会带着各自的日期到达，这样昨天的尾量不会被错误累加到今天。
 */
private class StatsState {
    var date: String = ""
    var totalPlayers: Long = 0L
    var totalFlow: Long = 0L
    var todayPlayers: Long = 0L
    var rank0: Long = 0L
    var rank1: Long = 0L
    var rank2: Long = 0L
    var rank3: Long = 0L
    var rank3plus: Long = 0L

    /** 当天已登记过“活跃”的主体（用于今日游玩人数去重，重启后从库回填）。 */
    val activeToday = HashSet<String>()
    val dailyJoins = HashMap<String, Long>()
    val dailyChat = HashMap<String, Long>()
    val dailyPlay = HashMap<String, Long>()
    val hourlyJoins = HashMap<Pair<String, Int>, Long>()
    var pendingPeakOnline: Int = 0

    var totalChat: Long = 0L
    var totalPlayMillis: Long = 0L
    var totalForumPosts: Long = 0L
    var totalForumComments: Long = 0L
    var totalLikes: Long = 0L
    var totalDislikes: Long = 0L
    var totalRecognitions: Long = 0L
    var peakOnline: Int = 0

    /** 社区互动按日聚合的缓存（来自业务表，不是增量计数）。 */
    var communityDaily: Map<String, MdtStorage.StatsCommunityDay> = emptyMap()

    /** 最早有记录的日期（用于 Web 声明“统计自该日期起”）。 */
    var firstRecordedDate: String = ""
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

/** 近 N 天曲线上的一个点。 */
private data class DaySeriesPoint(
    val label: String,
    val players: Long,
    val joins: Long,
    val chat: Long,
    val playMillis: Long,
)

private val stats = StatsState()

private fun statsOutputFile(): File = File(Vars.dataDirectory.file(), statsOutputPath)

/** “今天”一律以实时系统日期为准（持久化的 serverStats.date 只作历史记录，不作为日期权威）。 */
private fun todayDateString(): String = LocalDate.now().toString()

private fun dateAt(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate().toString()

private fun hourAt(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(ZONE).hour

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

/** 跨天滚动：只重置“今日人数”内存值（逐日明细本来就按日期键存放，不受影响）。 */
private fun rolloverTo(date: String) {
    if (stats.date == date) return
    stats.date = date
    stats.todayPlayers = 0L
    stats.pendingPeakOnline = 0
    if (stats.activeToday.isNotEmpty()) stats.activeToday.clear()
    // 新的一天：把“今日人数”按当天已落库的活跃登记重建（防止重启后当日重复计数）。
    runCatching {
        stats.activeToday.addAll(MdtStorage.loadStatsActiveUids(date))
        stats.todayPlayers = stats.activeToday.size.toLong()
    }.onFailure { logger.warning("服务器状态统计跨天重建活跃登记失败: $it") }
}

private fun StatsState.toRecord() = MdtStorage.ServerStatsRecord(
    date = date,
    totalPlayers = totalPlayers,
    totalFlow = totalFlow,
    todayPlayers = todayPlayers,
    rank0 = rank0,
    rank1 = rank1,
    rank2 = rank2,
    rank3 = rank3,
    rank3plus = rank3plus,
)

private fun StatsState.toTotalsRecord() = MdtStorage.StatsTotalsRecord(
    chatMessages = totalChat,
    playMillis = totalPlayMillis,
    forumPosts = totalForumPosts,
    forumComments = totalForumComments,
    likes = totalLikes,
    dislikes = totalDislikes,
    recognitions = totalRecognitions,
    peakOnline = peakOnline,
)

/** 处理一个事件（只在 IO 协程内调用，返回后由调用方决定是否落盘）。 */
private fun processStatsEvent(event: StatsEvent) {
    when (event) {
        is StatsJoinEvent -> {
            rolloverTo(event.date)
            stats.totalFlow++
            stats.dailyJoins[event.date] = (stats.dailyJoins[event.date] ?: 0L) + 1L
            val hourKey = event.date to event.hour
            stats.hourlyJoins[hourKey] = (stats.hourlyJoins[hourKey] ?: 0L) + 1L

            val mark = MdtStorage.markStatsPlayerSeen(event.uid, event.date, event.levelCode)
            if (mark.isNew) {
                stats.totalPlayers++
                stats.bumpRank(rankBucket(event.levelCode))
            } else {
                mark.prevLevel?.let { prev -> stats.moveRank(rankBucket(prev), rankBucket(event.levelCode)) }
            }
            // 每日活跃主体登记（复合主键，同一天只插一次），据此维护“今日游玩人数”。
            if (MdtStorage.markStatsPlayerActive(event.date, event.uid, event.hour)) {
                if (stats.activeToday.add(event.uid)) stats.todayPlayers++
            }
        }
        is StatsLeaveEvent -> {
            if (event.playMillis > 0L) {
                stats.dailyPlay[event.date] = (stats.dailyPlay[event.date] ?: 0L) + event.playMillis
                stats.totalPlayMillis += event.playMillis
            }
        }
        is StatsChatEvent -> {
            stats.dailyChat[event.date] = (stats.dailyChat[event.date] ?: 0L) + 1L
            stats.totalChat++
        }
        is StatsLevelEvent -> {
            val moved = MdtStorage.moveStatsPlayerLevel(event.uid, event.newLevelCode)
            if (moved != null) stats.moveRank(rankBucket(moved.first), rankBucket(event.newLevelCode))
        }
    }
}

/** 把内存里的逐日增量写库，写完清空（每个日期一次 UPDATE，条数与“跨天的日期数”同阶）。 */
private fun flushDailyDeltas() {
    val dates = stats.dailyJoins.keys + stats.dailyChat.keys + stats.dailyPlay.keys
    dates.forEach { date ->
        val delta = MdtStorage.StatsDailyDelta(
            joins = stats.dailyJoins[date] ?: 0L,
            chatMessages = stats.dailyChat[date] ?: 0L,
            playMillis = stats.dailyPlay[date] ?: 0L,
        )
        MdtStorage.addStatsDailyValues(date, delta)
    }
    stats.dailyJoins.clear()
    stats.dailyChat.clear()
    stats.dailyPlay.clear()

    val hourly = stats.hourlyJoins
    hourly.forEach { (key, joins) ->
        MdtStorage.addStatsHourlyJoins(key.first, key.second, joins)
    }
    hourly.clear()
}

// ---------- 事件钩子（游戏线程：只投递，不做事） ----------

listen<EventType.PlayerJoin> {
    if (!statsEnabled) return@listen
    val data = PlayerData[it.player]
    val level = with(trustLevel) { getTrustLevelCode(data.id, it.player) }
    val now = System.currentTimeMillis()
    sessionStartMillis[data.id] = now
    statsEventQueue.offer(StatsJoinEvent(data.id, dateAt(now), hourAt(now), normalizeStatsLevel(level)))
}

listen<EventType.PlayerLeave> {
    if (!statsEnabled) return@listen
    val data = PlayerData[it.player]
    val startedAt = sessionStartMillis.remove(data.id) ?: return@listen
    val now = System.currentTimeMillis()
    val playMillis = (now - startedAt).coerceAtLeast(0L)
    if (playMillis <= 0L) return@listen
    statsEventQueue.offer(StatsLeaveEvent(data.id, dateAt(now), playMillis))
}

/** 聊天量：以已提交到服务端的玩家聊天事件为准（被限速/拦截的消息不会走到这里）。 */
listen<EventType.PlayerChatEvent> {
    if (!statsEnabled) return@listen
    statsEventQueue.offer(StatsChatEvent(dateAt(System.currentTimeMillis())))
}

listen<TrustLevelChangedEvent> {
    if (!statsEnabled) return@listen
    statsEventQueue.offer(StatsLevelEvent(it.uid, normalizeStatsLevel(it.newLevel)))
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

/** 近 N 天合并视图：库里的历史行 + 内存里尚未落盘的今日数据。 */
private fun mergedDaily(days: Int): Map<String, MdtStorage.StatsDailyRecord> {
    val merged = linkedMapOf<String, MdtStorage.StatsDailyRecord>()
    runCatching { MdtStorage.loadStatsDaily(days.toLong()) }
        .onFailure { logger.warning("服务器状态统计读取每日明细失败: $it") }
        .getOrDefault(emptyList())
        .forEach { merged[it.date] = it }
    val today = todayDateString()
    val base = merged[today] ?: MdtStorage.StatsDailyRecord(date = today)
    merged[today] = base.copy(
        joins = base.joins + (stats.dailyJoins[today] ?: 0L),
        chatMessages = base.chatMessages + (stats.dailyChat[today] ?: 0L),
        playMillis = base.playMillis + (stats.dailyPlay[today] ?: 0L),
        peakOnline = maxOf(base.peakOnline, stats.pendingPeakOnline),
    )
    return merged
}

private fun dailyActiveCount(date: String): Long {
    if (date == todayDateString()) return stats.todayPlayers
    return runCatching { MdtStorage.countStatsActivePlayers(date) }.getOrDefault(0L)
}

private fun buildStatsJson(): String {
    val snap = latestSnapshot
    val days = dailySeriesDays.coerceIn(2, 90)
    val hours = hourlySeriesHours.coerceIn(1, 24)
    val today = todayDateString()
    val merged = mergedDaily(days)

    // 近 N 天曲线：固定补齐缺失日期（没有数据的日子显示 0，而不是断线）。
    val todayDate = runCatching { LocalDate.parse(today) }.getOrNull() ?: LocalDate.now()

    val series = (days - 1 downTo 0).map { offset ->
        val date = todayDate.minusDays(offset.toLong())
        val key = date.toString()
        val row = merged[key]
        DaySeriesPoint(
            label = date.format(DAY_LABEL_FORMATTER),
            players = dailyActiveCount(key),
            joins = row?.joins ?: 0L,
            chat = row?.chatMessages ?: 0L,
            playMillis = row?.playMillis ?: 0L,
        )
    }

    val communityDaily = stats.communityDaily
    val windowCutoff = todayDate.minusDays((days - 1).toLong()).toString()
    var windowPosts = 0L
    var windowComments = 0L
    var windowLikes = 0L
    var windowDislikes = 0L
    var windowRecognitions = 0L
    var windowPlayMillis = 0L
    merged.filterKeys { it >= windowCutoff }.forEach { (_, row) ->
        windowPlayMillis += row.playMillis
    }
    communityDaily.filterKeys { it >= windowCutoff }.forEach { (_, row) ->
        windowPosts += row.posts
        windowComments += row.comments
        windowLikes += row.likes
        windowDislikes += row.dislikes
        windowRecognitions += row.recognitions
    }
    val windowChat = series.sumOf { it.chat }
    val playedDays = series.count { it.players > 0 }
    val avgPlayers = if (series.isEmpty()) 0.0 else series.sumOf { it.players }.toDouble() / series.size

    // 一天内 24 小时曲线（今天）。
    val hourlyRows = runCatching { MdtStorage.loadStatsHourly(today) }.getOrDefault(emptyMap())

    return buildString {
        appendLine("{")
        appendLine("  \"schema\": 2,")
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
        appendLine("    \"date\": ${jsonString(today)},")
        appendLine("    \"players\": ${stats.todayPlayers},")
        appendLine("    \"flow\": ${merged[today]?.joins ?: 0L},")
        appendLine("    \"chatMessages\": ${merged[today]?.chatMessages ?: 0L},")
        appendLine("    \"playMillis\": ${merged[today]?.playMillis ?: 0L},")
        appendLine("    \"online\": ${snap.onlinePlayers},")
        appendLine("    \"peakOnline\": ${merged[today]?.peakOnline ?: 0},")
        appendLine("    \"peakOnlineHistory\": ${stats.peakOnline}")
        appendLine("  },")
        appendLine("  \"total\": {")
        appendLine("    \"players\": ${stats.totalPlayers},")
        appendLine("    \"flow\": ${stats.totalFlow},")
        appendLine("    \"chatMessages\": ${stats.totalChat},")
        appendLine("    \"playMillis\": ${stats.totalPlayMillis},")
        appendLine("    \"forumPosts\": ${stats.totalForumPosts},")
        appendLine("    \"forumComments\": ${stats.totalForumComments},")
        appendLine("    \"likes\": ${stats.totalLikes},")
        appendLine("    \"dislikes\": ${stats.totalDislikes},")
        appendLine("    \"recognitions\": ${stats.totalRecognitions},")
        appendLine("    \"peakOnline\": ${stats.peakOnline}")
        appendLine("  },")
        appendLine("  \"last${days}Days\": {")
        appendLine("    \"days\": $days,")
        appendLine("    \"startDate\": ${jsonString(todayDate.minusDays((days - 1).toLong()).toString())},")
        appendLine("    \"endDate\": ${jsonString(today)},")
        appendLine("    \"activeDays\": $playedDays,")
        appendLine("    \"players\": ${series.sumOf { it.players }},")
        appendLine("    \"dailyAvgPlayers\": ${"%.1f".format(avgPlayers)},")
        appendLine("    \"flow\": ${series.sumOf { it.joins }},")
        appendLine("    \"chatMessages\": $windowChat,")
        appendLine("    \"playMillis\": $windowPlayMillis,")
        appendLine("    \"forumPosts\": $windowPosts,")
        appendLine("    \"forumComments\": $windowComments,")
        appendLine("    \"likes\": $windowLikes,")
        appendLine("    \"dislikes\": $windowDislikes,")
        appendLine("    \"recognitions\": $windowRecognitions")
        appendLine("  },")
        appendLine("  \"daily\": [")
        series.forEachIndexed { index, item ->
            val comma = if (index == series.lastIndex) "" else ","
            appendLine(
                "    {\"date\": ${jsonString(item.label)}, \"players\": ${item.players}, " +
                    "\"flow\": ${item.joins}, \"chatMessages\": ${item.chat}, \"playMillis\": ${item.playMillis}}$comma"
            )
        }
        appendLine("  ],")
        appendLine("  \"hourly\": [")
        val currentHour = hourAt(System.currentTimeMillis())
        (0 until hours).forEach { h ->
            val key = today to h
            val joins = (hourlyRows[h]?.joins ?: 0L) + (stats.hourlyJoins[key] ?: 0L)
            val peak = maxOf(hourlyRows[h]?.peakOnline ?: 0, if (h == currentHour) stats.pendingPeakOnline else 0)
            val comma = if (h == hours - 1) "" else ","
            appendLine("    {\"hour\": $h, \"flow\": $joins, \"peakOnline\": $peak}$comma")
        }
        appendLine("  ],")
        appendLine("  \"totalByRank\": ${rankMapJson(stats.rank0, stats.rank1, stats.rank2, stats.rank3, stats.rank3plus)},")
        appendLine("  \"notes\": {")
        appendLine("    \"dailySeriesDays\": $days,")
        appendLine("    \"hourlySeriesHours\": $hours,")
        appendLine("    \"dailyKeepDays\": ${dailyKeepDays.coerceAtLeast(2L)},")
        appendLine("    \"hourlyKeepDays\": ${MdtStorage.STATS_HOURLY_KEEP_DAYS},")
        appendLine("    \"communityWindowDays\": $days,")
        appendLine("    \"peakOnline\": ${stats.peakOnline},")
        appendLine("    \"playMillisTotal\": ${stats.totalPlayMillis},")
        appendLine("    \"communityTotalsAreLifetime\": true,")
        appendLine("    \"dailyHistorySince\": ${jsonString(stats.firstRecordedDate)}")
        appendLine("  }")
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
        |[cyan]统计日期：[white]${stats.date}[gray]（每日明细起始 ${stats.firstRecordedDate.ifEmpty { "尚未写入" }}）
        |[cyan]今日：[white]${stats.todayPlayers}[cyan] 人游玩，人流量 [white]${mergedDaily(2)[stats.date]?.joins ?: 0L}[cyan]，聊天 [white]${mergedDaily(2)[stats.date]?.chatMessages ?: 0L}[cyan] 条，峰值在线 [white]${stats.pendingPeakOnline}
        |[cyan]累计：[white]${stats.totalPlayers}[cyan] 人游玩，人流量 [white]${stats.totalFlow}[cyan]，聊天 [white]${stats.totalChat}[cyan] 条，在线时长 [white]${stats.totalPlayMillis / 3_600_000L}[cyan] 小时
        |[cyan]社区累计：[white]帖子 ${stats.totalForumPosts}[gray]|[white]评论 ${stats.totalForumComments}[gray]|[white]赞 ${stats.totalLikes}[gray]|[white]踩 ${stats.totalDislikes}[gray]|[white]认可 ${stats.totalRecognitions}
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
        // 日期权威是实时系统日期：持久化里的 serverStats.date 是上一次运行留下的历史值，
        // 若直接沿用会让 14 天/24 小时窗口整体错位（例如上一轮停在 8-22、今天已是 9-11）。
        val today = todayDateString()
        val sameDay = loaded.date == today
        stats.date = today
        stats.totalPlayers = loaded.totalPlayers
        stats.totalFlow = loaded.totalFlow
        stats.todayPlayers = if (sameDay) loaded.todayPlayers else 0L
        stats.rank0 = loaded.rank0
        stats.rank1 = loaded.rank1
        stats.rank2 = loaded.rank2
        stats.rank3 = loaded.rank3
        stats.rank3plus = loaded.rank3plus

        // 重启后当天已登记过的活跃主体要回填，避免同一玩家当天被重复计入。
        if (sameDay) {
            runCatching { MdtStorage.loadStatsActiveUids(today) }
                .onSuccess {
                    stats.activeToday.clear()
                    stats.activeToday.addAll(it)
                    stats.todayPlayers = it.size.toLong()
                }
                .onFailure { logger.warning("服务器状态统计读取当日活跃登记失败: $it") }
        }

        // 跨重启累计总量：首次启用时做一次性历史回填（帖子/评论/点赞/认可/在线时长）；
        // 老库若存在早期版本写下的回填标记但社区总量仍为 0，也会在此自愈重做一次。
        runCatching {
            if (MdtStorage.needsStatsLifetimeBackfill()) {
                MdtStorage.backfillStatsLifetime().also {
                    logger.info("服务器状态统计已回填历史总量: 帖子=${it.forumPosts} 评论=${it.forumComments} 赞=${it.likes} 认可=${it.recognitions} 在线时长=${it.playMillis / 3_600_000L}小时")
                }
            } else {
                MdtStorage.loadStatsTotals()
            }
        }.onSuccess { totals ->
            stats.totalChat = totals.chatMessages
            stats.totalPlayMillis = totals.playMillis
            stats.totalForumPosts = totals.forumPosts
            stats.totalForumComments = totals.forumComments
            stats.totalLikes = totals.likes
            stats.totalDislikes = totals.dislikes
            stats.totalRecognitions = totals.recognitions
            stats.peakOnline = totals.peakOnline
        }.onFailure { logger.warning("服务器状态统计读取累计总量失败: $it") }

        // 一次性裁剪（之后只在跨天滚动时再裁一次），保证明细表行数有界。
        runCatching { MdtStorage.pruneServerStats(dailyKeepDays, LocalDate.now().toString()) }
            .onFailure { logger.warning("服务器状态统计裁剪历史明细失败: $it") }

        runCatching { stats.firstRecordedDate = MdtStorage.loadStatsFirstDate() ?: "" }
            .onFailure { logger.warning("服务器状态统计读取起始日期失败: $it") }

        runCatching { stats.communityDaily = MdtStorage.loadStatsCommunityDaily(dailySeriesDays.toLong()) }
            .onFailure { logger.warning("服务器状态统计读取社区互动聚合失败: $it") }
        if (stats.firstRecordedDate.isEmpty()) stats.firstRecordedDate = LocalDate.now().toString()

        latestStatusText = buildStatusText()
        writeStatsFile()
        var dirty = false
        var lastWrittenEnabled = statsEnabled
        var lastCommunityRefresh = System.currentTimeMillis()

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
            val now = System.currentTimeMillis()
            val today = dateAt(now)
            rolloverTo(today)

            while (true) {
                val event = statsEventQueue.poll() ?: break
                runCatching { processStatsEvent(event) }
                    .onFailure { logger.warning("服务器状态统计事件处理失败: $it") }
                dirty = true
            }

            // 峰值在线：以 5 秒快照为准，每个刷新周期检查一次（只在更高时写库）。
            val online = latestSnapshot.onlinePlayers
            if (online > 0 && online > stats.pendingPeakOnline) {
                stats.pendingPeakOnline = online
                dirty = true
                runCatching {
                    stats.peakOnline = MdtStorage.raiseStatsDailyPeak(today, online, stats.peakOnline)
                    MdtStorage.raiseStatsHourlyPeak(today, hourAt(now), online)
                }.onFailure { logger.warning("服务器状态统计写入峰值在线失败: $it") }
            }

            if (dirty) {
                runCatching {
                    flushDailyDeltas()
                    MdtStorage.saveServerStats(stats.toRecord())
                    MdtStorage.saveStatsTotals(stats.toTotalsRecord())
                }.onFailure { logger.warning("服务器状态统计持久化失败: $it") }
                dirty = false
            }

            // 社区互动聚合按更长间隔刷新（默认 5 分钟），不在每个周期都查库。
            if (now - lastCommunityRefresh >= communityIntervalMillis.coerceAtLeast(60_000L)) {
                lastCommunityRefresh = now
                runCatching { MdtStorage.loadStatsCommunityDaily(dailySeriesDays.toLong()) }
                    .onSuccess { stats.communityDaily = it }
                    .onFailure { logger.warning("服务器状态统计刷新社区互动聚合失败: $it") }
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

/**
 * 脚本停用/服务器关闭时做最后一次落盘：平时是“每分钟有变化才写”，
 * 这里补一次收尾，避免退出前最后不到一个周期的增量丢失。
 * 数据库/脚本加载器可能已在关闭流程中，失败只记日志、不影响停服。
 */
onDisable {
    runCatching {
        if (statsEnabled) {
            flushDailyDeltas()
            MdtStorage.saveServerStats(stats.toRecord())
            MdtStorage.saveStatsTotals(stats.toTotalsRecord())
        }
        writeStatsFile()
    }.onFailure { logger.warning("服务器状态统计退出前落盘失败: $it") }
}
