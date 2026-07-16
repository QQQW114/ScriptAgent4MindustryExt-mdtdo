package wayzer.ext

import arc.Core
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Groups
import java.util.concurrent.atomic.AtomicLong

name = "主线程卡顿诊断"

private val watchdogEnabled by config.key(true, "是否启用主线程卡顿诊断日志")
private val stallWarnMillis by config.key(3_000L, "主线程多久未更新后记录卡顿日志(ms)")
private val checkIntervalMillis by config.key(500L, "后台检测间隔(ms)")
private val dumpCooldownMillis by config.key(30_000L, "线程堆栈转储冷却(ms)")
private val maxThreadsToDump by config.key(12, "最多记录多少个线程堆栈")

private val lastUpdateMillis = AtomicLong(System.currentTimeMillis())
private val lastUpdateFrame = AtomicLong(0L)
private var gameThread: Thread? = null
private var lastDumpMillis = 0L
private var lastRecoveredGap = 0L

private fun nowMillis(): Long = System.currentTimeMillis()

private fun threadLabel(thread: Thread): String =
    "${thread.name} state=${thread.state} id=${thread.id} daemon=${thread.isDaemon}"

private fun stackText(thread: Thread, stack: Array<StackTraceElement>, limit: Int = 24): String =
    buildString {
        appendLine(threadLabel(thread))
        stack.take(limit.coerceAtLeast(1)).forEach { appendLine("  at $it") }
    }

private fun shouldDumpThread(thread: Thread, main: Thread?): Boolean {
    if (thread == main) return true
    val name = thread.name.lowercase()
    return name.contains("server") ||
        name.contains("net") ||
        name.contains("main") ||
        name.contains("path") ||
        name.contains("script") ||
        name.contains("coroutine") ||
        name.contains("defaultdispatcher")
}

private fun dumpThreadStacks(gap: Long) {
    val main = gameThread
    val all = Thread.getAllStackTraces()
    val selected = all.entries
        .asSequence()
        .filter { shouldDumpThread(it.key, main) }
        .sortedWith(
            compareByDescending<Map.Entry<Thread, Array<StackTraceElement>>> { it.key == main }
                .thenBy { it.key.name }
        )
        .take(maxThreadsToDump.coerceAtLeast(1))
        .toList()

    logger.warning(
        buildString {
            appendLine("检测到主线程疑似停顿 ${gap}ms；这通常会表现为聊天正常，但单位/世界不更新，恢复后玩家被拉回。")
            appendLine("状态: playing=${Vars.state.isPlaying} paused=${Vars.state.isPaused} players=${Groups.player.size()} units=${Groups.unit.size()} bullets=${Groups.bullet.size()} sync=${Groups.sync.size()} fps=${Core.graphics.framesPerSecond}")
            appendLine("以下为检测时线程堆栈，用于定位阻塞点：")
            selected.forEach { (thread, stack) ->
                appendLine("---")
                append(stackText(thread, stack))
            }
        }
    )
}

listen(EventType.Trigger.update) {
    if (gameThread == null) gameThread = Thread.currentThread()
    val now = nowMillis()
    val previous = lastUpdateMillis.getAndSet(now)
    lastUpdateFrame.incrementAndGet()
    val gap = now - previous
    if (watchdogEnabled && gap >= stallWarnMillis.coerceAtLeast(1L) && gap != lastRecoveredGap) {
        lastRecoveredGap = gap
        logger.warning("主线程刚从 ${gap}ms 停顿中恢复；若前面没有线程堆栈，说明后台检测未及时抢到卡顿窗口。")
    }
}

onEnable {
    launch(Dispatchers.Default) {
        while (true) {
            delay(checkIntervalMillis.coerceAtLeast(100L))
            if (!watchdogEnabled) continue
            val now = nowMillis()
            val gap = now - lastUpdateMillis.get()
            if (gap < stallWarnMillis.coerceAtLeast(1L)) continue
            if (now - lastDumpMillis < dumpCooldownMillis.coerceAtLeast(1_000L)) continue
            lastDumpMillis = now
            dumpThreadStacks(gap)
        }
    }
}

command("tickwatchdog", "查看主线程卡顿诊断状态") {
    usage = "[status]"
    body {
        val gap = nowMillis() - lastUpdateMillis.get()
        reply(
            """
            |[cyan]主线程卡顿诊断：[white]${if (watchdogEnabled) "启用" else "关闭"}
            |[cyan]距上次游戏更新：[white]${gap}ms
            |[cyan]累计更新帧：[white]${lastUpdateFrame.get()}
            |[cyan]主线程：[white]${gameThread?.name ?: "尚未捕获"}
            |[gray]如果出现“聊天正常但单位/世界卡住后回弹”，日志中应出现“主线程疑似停顿”及线程堆栈。
            """.trimMargin().with()
        )
    }
}
