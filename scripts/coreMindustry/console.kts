@file:Import("org.jline:jline-terminal-jansi:3.21.0", mavenDependsSingle = true)
@file:Import("org.jline:jline-terminal:3.21.0", mavenDependsSingle = true)
@file:Import("org.fusesource.jansi:jansi:2.4.0", mavenDependsSingle = true)
@file:Import("org.jline:jline-reader:3.21.0", mavenDependsSingle = true)

package coreMindustry

import org.jline.reader.*
import org.jline.utils.AttributedString
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.PrintStream
import java.util.logging.Level
import kotlin.system.exitProcess

class MyPrintStream(private val block: (String) -> Unit) : PrintStream(ByteArrayOutputStream()) {
    private val bufOut = out as ByteArrayOutputStream

    var last = -1
    override fun write(b: Int) {
        if (last == 13 && b == 10) {// \r\n
            last = -1
            return
        }
        last = b
        if (b == 13 || b == 10) flush()
        else super.write(b)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        if (len < 0) throw ArrayIndexOutOfBoundsException(len)
        for (i in 0 until len)
            write(buf[off + i].toInt())
    }

    @Synchronized
    override fun flush() {
        val str = try {
            bufOut.toString()
        } finally {
            bufOut.reset()
        }
        block(str)
    }
}

object MyCompleter : Completer {
    override fun complete(reader: LineReader, line: ParsedLine, candidates: MutableList<Candidate>) {
        val cmd = line.line().substring(0, line.cursor()).split(' ')
        val res = runBlocking(Dispatchers.game) {
            Commands.Root.tabComplete {
                arg = cmd
            }
        }
        candidates += res.map {
            Candidate(it)
        }
    }
}

@OptIn(LoaderApi::class)
suspend fun handleInput(reader: LineReader) {
    var last = 0
    while (isActive) {
        val line = try {
            runInterruptible {
                reader.readLine("> ").let(RootCommands::trimInput)
            }
        } catch (e: InterruptedIOException) {
            return
        } catch (e: UserInterruptException) {
            if (!enabled) break//script disable
            if (last != 1) {
                reader.printAbove("Interrupt again to force exit application")
                last = 1
                continue
            }
            reader.printAbove("force exit")
            exitProcess(255)
        } catch (e: EndOfFileException) {
            if (last != 2) {
                reader.printAbove("Catch EndOfFile, again to exit application")
                last = 2
                continue
            }
            reader.printAbove("exit")
            ScriptManager.disableAll()
            exitProcess(1)
        }
        last = 0
        if (line.isEmpty()) continue
        launch(Job()) {//ignore cancel
            try {
                RootCommands.handleInput(line, null)
            } catch (e: Throwable) {
                logger.log(Level.SEVERE, "error when handle input", e)
            }
        }.join()
    }
}

var started = false
lateinit var reader: LineReader
fun start() {
    if (started) return
    started = true
    launch(Dispatchers.IO + CoroutineName("Console Reader")) {
        reader = withContextClassloader {
            LineReaderBuilder.builder()
                .completer(MyCompleter)
                .variable(LineReader.HISTORY_FILE, Config.cacheDir.resolve("console.history"))
                .build()
        }
        val bakOut = System.out
        // JLine 的 printAbove/flush 可能被慢磁盘、宿主面板或管道输出阻塞数十秒。
        // 绝不能让写日志的游戏主线程直接执行它；使用有界队列转移到 IO 线程，
        // 队列拥塞时只丢弃旧的终端展示行，文件日志仍由独立日志 Handler 保存。
        val outputQueue = Channel<String>(
            capacity = 1024,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val outputJob = launch(Dispatchers.IO + CoroutineName("Console Output")) {
            for (line in outputQueue) {
                reader.printAbove(AttributedString.fromAnsi(line))
            }
        }
        System.setOut(MyPrintStream {
            if (it.isNotEmpty()) outputQueue.trySend(it)
        })
        try {
            handleInput(reader)
        } finally {
            System.setOut(bakOut)
            outputQueue.close()
            outputJob.cancelAndJoin()
            reader.terminal.close()
        }
    }
}

onEnable {
    Core.app.listeners.find { it.javaClass.simpleName == "ServerControl" }?.apply {
        javaClass.getDeclaredField("serverInput")
            .set(this, Runnable {
                logger.info("Overwrite ServerControl.serverInput")
                start()
            })
    }
    start()
}
