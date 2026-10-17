@file:Depends("wayzer/maps", "地图加载事件")
@file:Depends("wayzer/vote", "投票服务")

package wayzer.ext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Player
import wayzer.MapLoadFinishedEvent
import wayzer.VoteEvent
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ============================================================================
// 游戏内实时事件流（live events feed）
//
// 目标：把聊天/加入/离开/投票/换图/系统消息以 JSONL 追加写入
//       mdtserver/config/stats/live-events.jsonl，供服务器门户（Web）增量读取。
//
// 数据契约（Web 端按此读取，不要单方面修改）：
//   {"ts":<epochMillis>,"type":"chat|join|leave|vote_start|vote_pass|vote_fail|map_change|system",
//    "text":"<Mindustry 原始颜色标记文本>","player":"<显示名>","playerId":"<可选 uuid>"}
//   - ts 严格递增（同一毫秒内多条事件自动 +1ms），Web 端 afterTs 为严格大于语义；
//   - text 保留 [red]/[#RRGGBB]/[] 等颜色标记原文，脚本侧只做 JSON 转义、不剥离/不 HTML 转义；
//   - 每行一个完整 JSON 对象，内部换行以 \n 转义，不产生真实换行。
//
// 线程模型（不占用游戏主线程）：
//   - 游戏事件监听器只做「提取字段 + 内存入队」，无文件/数据库/网络操作；
//   - 文件写入全部在 launch(Dispatchers.IO) 的单一写者协程中：定时 drain 内存队列，
//     一次拼接整批 JSON 行，再一次性 append 到文件（每批最多 200 条）；
//   - 投票没有现成事件回调，用 Dispatchers.game 上的轻量轮询观察 VoteEvent.current()
//     状态变化（只在内存读 AtomicReference，不做 IO）；不需要改 vote.lib.kt；
//   - 单文件超过阈值（默认约 2MB）先 rename 成 live-events.1.jsonl 再新建主文件，
//     只保留 1 份旧文件；启动时若文件已超限也会先轮转；
//   - 写文件/轮转异常只记录限频日志并重试，绝不影响游戏主线程。
// ============================================================================

name = "实时事件流"

// ---- 配置键（均可通过 SA 脚本配置修改） ----

private val feedDefaultEnabled by config.key(true, "实时事件流是否启用")
private val outputPath by config.key("stats/live-events.jsonl", "输出路径（相对 config 目录）")
private val flushIntervalMillis by config.key(400L, "批量写入间隔(ms)，约 2-4 次/秒")
private val maxQueueSize by config.key(4096, "内存队列上限，超出时丢弃最旧事件")
private val maxTextChars by config.key(500, "text 最大字符数，超出截断")
private val maxPlayerChars by config.key(64, "player 显示名最大字符数")
private val rotateBytes by config.key(2L * 1024L * 1024L, "单文件轮转阈值(字节)")
private val votePollMillis by config.key(250L, "投票状态轮询间隔(ms)")

private val EVENT_TYPES = setOf(
    "chat", "join", "leave", "vote_start", "vote_pass", "vote_fail", "map_change", "system"
)
private val MAX_BATCH_PER_WRITE = 200
// 轮转阈值下限，只防呆（误配成几字节会频繁轮转），不改变默认 2MB 行为。
private val MIN_ROTATE_BYTES = 4096L
private val ERROR_LOG_INTERVAL_MILLIS = 60_000L
private val DROP_LOG_INTERVAL_MILLIS = 60_000L

// ---- 事件模型与内存队列 ----

private data class LiveEvent(
    val time: Long,
    val type: String,
    val text: String,
    val player: String,
    val playerId: String?,
)

private val eventQueue = ConcurrentLinkedQueue<LiveEvent>()
private val eventQueueSize = AtomicInteger(0)
private val droppedEvents = AtomicLong(0)
private val writtenEvents = AtomicLong(0)
private val writtenBatches = AtomicLong(0)
private val rotationCount = AtomicLong(0)
private val currentFileBytes = AtomicLong(0)
private val lastWrittenTs = AtomicLong(0)

@Volatile private var writerReady = false
@Volatile private var lastErrorText: String? = null
@Volatile private var lastDropWarnAt = 0L
@Volatile private var lastErrorLogAt = 0L

// ---- 通用小工具 ----

// 按字符截断，避免把代理对（emoji 等）切成半个字符。
private fun truncateChars(value: String, limit: Int): String {
    val max = limit.coerceAtLeast(16)
    if (value.length <= max) return value
    var end = max
    if (end > 0 && Character.isHighSurrogate(value[end - 1])) end -= 1
    return value.substring(0, end)
}

private fun playerDisplayName(player: Player?): String =
    runCatching { player?.name }.getOrNull() ?: ""

// 门户契约的 playerId 是 Mindustry UUID；本地假玩家/未连接时 uuid() 返回 "[LOCAL]"，不写入。
private fun playerUuid(player: Player?): String? = runCatching { player?.uuid() }
    .getOrNull()
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it != "[LOCAL]" }

private fun enqueue(type: String, text: String, player: String = "", playerId: String? = null) {
    if (!feedDefaultEnabled) return
    if (type !in EVENT_TYPES) return
    val event = LiveEvent(
        time = System.currentTimeMillis(),
        type = type,
        text = truncateChars(text, maxTextChars),
        player = truncateChars(player, maxPlayerChars),
        playerId = playerId?.takeIf { it.isNotBlank() },
    )
    eventQueue.offer(event)
    var size = eventQueueSize.incrementAndGet()
    while (size > maxQueueSize.coerceAtLeast(16)) {
        if (eventQueue.poll() == null) break
        size = eventQueueSize.decrementAndGet()
        droppedEvents.incrementAndGet()
    }
    if (size >= maxQueueSize.coerceAtLeast(16)) warnQueueDrop()
}

private fun warnQueueDrop() {
    val now = System.currentTimeMillis()
    if (now - lastDropWarnAt > DROP_LOG_INTERVAL_MILLIS) {
        lastDropWarnAt = now
        logger.warning("实时事件流内存队列已满（上限 $maxQueueSize），已丢弃最旧事件，门户可能缺少部分事件")
    }
}

private fun recordError(source: String, error: Throwable) {
    val message = "$source: ${error.message ?: error.javaClass.simpleName}"
    lastErrorText = message
    val now = System.currentTimeMillis()
    if (now - lastErrorLogAt > ERROR_LOG_INTERVAL_MILLIS) {
        lastErrorLogAt = now
        logger.warning("实时事件流写入异常（已自动降级并重试，不影响游戏）：$message")
    }
}

// ---- 游戏事件监听（游戏线程：只提取字段并入队，不做 IO） ----

listen<EventType.PlayerJoin> {
    if (!feedDefaultEnabled) return@listen
    val player = it.player
    enqueue("join", "加入了服务器", playerDisplayName(player), playerUuid(player))
}

listen<EventType.PlayerLeave> {
    if (!feedDefaultEnabled) return@listen
    val player = it.player
    enqueue("leave", "离开了服务器", playerDisplayName(player), playerUuid(player))
}

listen<EventType.PlayerChatEvent> {
    if (!feedDefaultEnabled) return@listen
    enqueue("chat", it.message ?: "", playerDisplayName(it.player), playerUuid(it.player))
}

// 换图事件：wayzer/maps 在每次 MapManager.loadMapSync 成功后广播，含地图名/作者/模式/ID。
// 注意：MapLoadFinishedEvent 是 SA 自定义事件，必须用 listenTo 注册（listen 只挂 Mindustry arc 事件）。
listenTo<MapLoadFinishedEvent> {
    if (!feedDefaultEnabled) return@listenTo
    val info = this.info
    val mapName = runCatching { info.name }.getOrNull().orEmpty()
    // Gamemode.toString() 走 bundle 本地化（生存/沙盒/进攻/PVP/编辑器），门户直接展示更友好。
    val mode = runCatching { info.mode.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { info.mode.name }.getOrNull().orEmpty()
    val author = runCatching { info.author }.getOrNull().orEmpty()
    val mapId = runCatching { info.id }.getOrNull() ?: -1
    val text = buildString {
        append("[accent]加载地图：[white]").append(mapName)
        append(" [gray]模式：[white]").append(mode)
        if (author.isNotBlank() && !author.equals("unknown", ignoreCase = true)) {
            append(" [gray]作者：[white]").append(author)
        }
        if (mapId > 0) append(" [gray]ID：[white]").append(mapId)
    }
    enqueue("map_change", text, "", null)
}

// ---- 投票观察：VoteEvent 没有事件回调，只能轮询 current() ----

private var observedVote: VoteEvent? = null

private fun voteDescText(event: VoteEvent): String =
    runCatching { event.voteDesc.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { event.voteDesc.text }.getOrNull().orEmpty()

private fun emitVoteStart(event: VoteEvent) {
    if (!feedDefaultEnabled) return
    val starter = event.starter
    enqueue(
        "vote_start",
        "[gold]投票开始：[white]${playerDisplayName(starter)}[yellow] 发起 ${voteDescText(event)}",
        playerDisplayName(starter),
        playerUuid(starter),
    )
}

private fun emitVoteResult(event: VoteEvent) {
    if (!feedDefaultEnabled) return
    val starter = event.starter
    val succeed = runCatching { event.succeed }.getOrDefault(false)
    val type = if (succeed) "vote_pass" else "vote_fail"
    val prefix = if (succeed) "[green]投票通过：[white]" else "[red]投票失败：[white]"
    enqueue(
        type,
        "$prefix${playerDisplayName(starter)}[yellow] 发起的 ${voteDescText(event)}",
        playerDisplayName(starter),
        playerUuid(starter),
    )
}

private fun startVoteObserver() {
    launch(Dispatchers.game) {
        while (true) {
            delay(votePollMillis.coerceIn(100L, 2000L))
            val current = runCatching { VoteEvent.current() }.getOrNull()
            if (current === observedVote) continue
            val previous = observedVote
            observedVote = current
            if (previous != null) {
                runCatching { emitVoteResult(previous) }
                    .onFailure { recordError("voteResult", it) }
            }
            if (current != null) {
                runCatching { emitVoteStart(current) }
                    .onFailure { recordError("voteStart", it) }
            }
        }
    }
}

// ---- JSONL 写者（Dispatchers.IO 单写者） ----

private val writeLock = Any()
private var writerFile: File? = null

private fun resolveOutputFile(): File = File(Vars.dataDirectory.file(), outputPath)

// live-events.jsonl 的轮转目标是 live-events.1.jsonl（只保留 1 份旧文件）。
private fun rotatedFileOf(file: File): File {
    val name = file.name
    val dot = name.lastIndexOf('.')
    val oldName = if (dot > 0) name.substring(0, dot) + ".1" + name.substring(dot) else "$name.1"
    return File(file.parentFile, oldName)
}

// 先 rename 旧文件为 .1，主文件由后续 append 新建；读取端只看主文件，避免读到半行。
private fun rotateFile(file: File): Boolean {
    if (!file.exists() || file.length() == 0L) return false
    val rotated = rotatedFileOf(file)
    val moved = runCatching {
        runCatching {
            Files.move(
                file.toPath(), rotated.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(file.toPath(), rotated.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.onFailure { recordError("rotate", it) }.getOrDefault(false)
    if (moved) {
        rotationCount.incrementAndGet()
        currentFileBytes.set(0L)
        logger.info("实时事件流已轮转：${file.name} -> ${rotated.name}")
    }
    return moved
}

// 启动时从文件尾部恢复最后一条 ts，保证脚本热重载后 ts 仍然严格递增（Web 端 afterTs 增量拉取不丢事件）。
private fun readLastTimestamp(file: File): Long {
    if (!file.exists() || file.length() <= 0L) return 0L
    return runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val readLength = minOf(length, 16384L).toInt()
            if (readLength <= 0) return@use 0L
            raf.seek(length - readLength)
            val bytes = ByteArray(readLength)
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            val index = text.lastIndexOf("\"ts\":")
            if (index < 0) return@use 0L
            var end = index + 5
            while (end < text.length && text[end] != ',' && text[end] != '}') end++
            text.substring(index + 5, end).trim().toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)
}

private fun initWriter() {
    synchronized(writeLock) {
        lastWrittenTs.set(0L)
        val file = resolveOutputFile()
        file.parentFile?.mkdirs()
        if (file.exists() && file.length() > rotateBytes.coerceAtLeast(MIN_ROTATE_BYTES)) {
            rotateFile(file)
        }
        writerFile = file
        currentFileBytes.set(if (file.exists()) file.length() else 0L)
        lastWrittenTs.set(readLastTimestamp(file).coerceAtLeast(0L))
        writerReady = true
    }
}

private fun nextTimestamp(eventTime: Long): Long {
    while (true) {
        val previous = lastWrittenTs.get()
        val base = maxOf(eventTime, System.currentTimeMillis())
        val next = if (base > previous) base else previous + 1
        if (lastWrittenTs.compareAndSet(previous, next)) return next
    }
}

private fun appendJsonString(builder: StringBuilder, value: String) {
    builder.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> builder.append("\\\"")
            '\\' -> builder.append("\\\\")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            else -> if (ch < ' ') {
                builder.append("\\u")
                builder.append(ch.code.toString(16).padStart(4, '0'))
            } else {
                builder.append(ch)
            }
        }
    }
    builder.append('"')
}

private fun renderJsonLine(event: LiveEvent): String {
    val builder = StringBuilder(96 + event.text.length)
    builder.append("{\"ts\":").append(nextTimestamp(event.time))
    builder.append(",\"type\":")
    appendJsonString(builder, event.type)
    builder.append(",\"text\":")
    appendJsonString(builder, event.text)
    builder.append(",\"player\":")
    appendJsonString(builder, event.player)
    event.playerId?.let {
        builder.append(",\"playerId\":")
        appendJsonString(builder, it)
    }
    builder.append('}').append('\n')
    return builder.toString()
}

private fun flushQueue() {
    synchronized(writeLock) {
        val file = writerFile ?: resolveOutputFile().also { writerFile = it }
        while (true) {
            val batch = ArrayList<LiveEvent>(minOf(MAX_BATCH_PER_WRITE, maxOf(eventQueueSize.get(), 1)))
            while (batch.size < MAX_BATCH_PER_WRITE) {
                val event = eventQueue.poll() ?: break
                eventQueueSize.decrementAndGet()
                batch.add(event)
            }
            if (batch.isEmpty()) return
            val payload = buildString { batch.forEach { append(renderJsonLine(it)) } }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            var size = currentFileBytes.get()
            if (size > 0L && size + bytes.size > rotateBytes.coerceAtLeast(MIN_ROTATE_BYTES)) {
                if (rotateFile(file)) size = 0L
            }
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }
            } catch (e: Exception) {
                recordError("write", e)
                // 写入失败：事件放回队列等下一轮重试，内存队列继续有界。
                batch.forEach {
                    eventQueue.offer(it)
                    eventQueueSize.incrementAndGet()
                }
                return
            }
            currentFileBytes.set(size + bytes.size)
            writtenEvents.addAndGet(batch.size.toLong())
            writtenBatches.incrementAndGet()
            lastErrorText = null
        }
    }
}

private fun startWriter() {
    launch(Dispatchers.IO) {
        runCatching { initWriter() }.onFailure { recordError("init", it) }
        enqueue("system", "[gray]实时事件流已启动")
        while (true) {
            runCatching { flushQueue() }.onFailure { recordError("flush", it) }
            delay(flushIntervalMillis.coerceIn(100L, 5000L))
        }
    }
}

onEnable {
    writerReady = false
    lastErrorText = null
    startWriter()
    startVoteObserver()
    val target = runCatching { resolveOutputFile().path }.getOrDefault(outputPath)
    logger.info("实时事件流已启用：输出=$target 批量间隔=${flushIntervalMillis}ms 轮转阈值=${rotateBytes}B")
}

onDisable {
    runCatching {
        // 停用前补投一条系统事件并同步落盘，避免最后一批事件丢失。
        enqueue("system", "[gray]实时事件流已停止")
        runBlocking(Dispatchers.IO) {
            runCatching { flushQueue() }.onFailure { recordError("disableFlush", it) }
        }
    }.onFailure { logger.warning("实时事件流停用前落盘失败: $it") }
}

command("livefeed", "管理指令：实时事件流状态 / 手动轮转") {
    aliases = listOf("事件流", "实时事件")
    usage = "[status|rotate]"
    requirePermission("wayzer.livefeed.admin")
    body {
        when (arg.getOrNull(0)?.lowercase()) {
            "rotate", "轮转" -> {
                val rotated = withContext(Dispatchers.IO) {
                    synchronized(writeLock) {
                        val file = writerFile ?: resolveOutputFile()
                        if (rotateFile(file)) {
                            currentFileBytes.set(0L)
                            true
                        } else {
                            false
                        }
                    }
                }
                if (rotated) reply("[green]已轮转实时事件流，旧内容保留在 live-events.1.jsonl".with())
                else reply("[yellow]没有可轮转的内容（文件不存在或为空）".with())
            }

            else -> {
                val file = runCatching { resolveOutputFile() }.getOrNull()
                reply("[cyan]实时事件流".with())
                reply("[gray]输出文件：[white]${file?.path ?: outputPath}".with())
                reply(
                    (
                        "[gray]状态：[white]${if (writerReady) "运行中" else "初始化中"}" +
                            "[gray]  文件大小：[white]${currentFileBytes.get()} B" +
                            "[gray]  轮转阈值：[white]${rotateBytes} B"
                        ).with()
                )
                reply(
                    (
                        "[gray]已写事件：[white]${writtenEvents.get()}" +
                            "[gray]  批次：[white]${writtenBatches.get()}" +
                            "[gray]  轮转：[white]${rotationCount.get()}" +
                            "[gray]  队列：[white]${eventQueueSize.get()}"
                        ).with()
                )
                reply(
                    (
                        "[gray]最后 ts：[white]${lastWrittenTs.get()}" +
                            "[gray]  丢弃：[white]${droppedEvents.get()}" +
                            "[gray]  最近错误：[white]${lastErrorText ?: "无"}"
                        ).with()
                )
            }
        }
    }
}
PermissionApi.registerDefault("wayzer.livefeed.admin", group = "@admin")
