@file:Depends("coreLibrary/db")

package coreLibrary

import cf.wayzer.placehold.PlaceHoldApi.with
import cf.wayzer.scriptAgent.util.DependencyManager
import cf.wayzer.scriptAgent.util.Services
import cf.wayzer.scriptAgent.util.maven.Dependency
import coreLib.db.DBApi
import coreLibrary.lib.PermissionApi
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.ExperimentalKeywordApi
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.system.measureTimeMillis

val driverMaven by config.key("com.h2database:h2:2.0.206", "驱动程序maven包")
val driver by config.key("org.h2.Driver", "驱动程序类名")
val url by config.key("jdbc:h2:H2DB_PATH;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "数据库连接uri", "特殊变量H2DB_PATH 指向data/h2DB.db")
val user by config.key("", "用户名")
val password by config.key("", "密码")
val preserveKeywordCasing by config.key(true, "是否保留关键字大小写, 老用户请设置为false")
var h2KeepAliveMinutes by config.key(5, "H2文件数据库保活间隔(分钟)，<=0关闭。针对云服磁盘/块存储休眠后首次读写卡顿")
var h2DiskWarmupEnabled by config.key(true, "是否启用H2磁盘预热/保活。用于云服磁盘休眠，不是TPS优化器")

//Postgres example
// driverMaven: org.postgresql:postgresql:42.7.5
// driver: org.postgresql.Driver
// url: jdbc:postgresql://db:5432/postgres
// user: postgres
// password: your_password

private data class DatabasePingResult(
    val success: Boolean,
    val costMillis: Long = 0L,
    val message: String,
)

@Volatile private var activeDatabase: Database? = null
@Volatile private var activeDatabaseIsH2 = false
@Volatile private var lastH2PingAt = 0L
@Volatile private var lastH2PingCostMillis = 0L
@Volatile private var lastH2PingError: String? = null
private val h2PingInProgress = AtomicBoolean(false)

private fun normalizeJdbcUrl(raw: String): String {
    val lower = raw.lowercase()
    if (!lower.startsWith("jdbc:h2:")) return raw

    val parts = raw.split(';')
    val optionNames = parts.drop(1)
        .mapNotNull {
            val name = it.substringBefore('=', "").trim()
            if (name.isEmpty()) null else name.uppercase()
        }
        .toSet()
    val extra = mutableListOf<String>()
    if ("DB_CLOSE_DELAY" !in optionNames) extra += "DB_CLOSE_DELAY=-1"
    if ("DB_CLOSE_ON_EXIT" !in optionNames) extra += "DB_CLOSE_ON_EXIT=FALSE"
    if (extra.isEmpty()) return raw
    return raw.trimEnd(';') + ";" + extra.joinToString(";")
}

private fun pingDatabase(db: Database, reason: String): DatabasePingResult {
    if (!h2PingInProgress.compareAndSet(false, true)) {
        return DatabasePingResult(false, message = "已有一次H2预热/保活正在执行")
    }
    return try {
        val cost = measureTimeMillis {
            transaction(db) {
                exec("SELECT 1")
            }
        }
        lastH2PingAt = System.currentTimeMillis()
        lastH2PingCostMillis = cost
        lastH2PingError = null
        if (cost > 1000L) {
            logger.warning("[数据库] H2 $reason 耗时 ${cost}ms；若频繁出现，优先检查VPS磁盘IO/杀毒/网盘同步，或考虑迁移PostgreSQL。")
        }
        DatabasePingResult(true, cost, "$reason 完成")
    } catch (e: Throwable) {
        lastH2PingError = e.message ?: e.javaClass.simpleName
        logger.log(Level.WARNING, "[数据库] H2 $reason 失败", e)
        DatabasePingResult(false, message = "$reason 失败：${lastH2PingError}")
    } finally {
        h2PingInProgress.set(false)
    }
}

private fun diskWarmupStatusText(): String {
    val h2State = when {
        activeDatabase == null -> "[yellow]数据库尚未就绪"
        !activeDatabaseIsH2 -> "[gray]当前不是H2文件数据库，无需磁盘预热"
        else -> "[green]H2已连接"
    }
    val lastText = if (lastH2PingAt <= 0L) {
        "[gray]本次启动尚未执行"
    } else {
        val agoSeconds = (System.currentTimeMillis() - lastH2PingAt).coerceAtLeast(0L) / 1000L
        "[white]${agoSeconds}秒前，耗时 ${lastH2PingCostMillis}ms"
    }
    return """
        |[cyan]H2磁盘预热/保活：${if (h2DiskWarmupEnabled && h2KeepAliveMinutes > 0) "[green]开启" else "[red]关闭"}
        |[cyan]保活间隔：[white]${h2KeepAliveMinutes}分钟
        |[cyan]连接状态：$h2State
        |[cyan]最近执行：$lastText
        |[cyan]最近错误：${lastH2PingError?.let { "[red]$it" } ?: "[green]无"}
        |[gray]该功能仅用 SELECT 1 避免部分云服磁盘/块存储休眠后首次数据库读写卡顿，不是TPS优化器。
    """.trimMargin()
}

private suspend fun runH2KeepAliveLoop(db: Database) {
    if (h2DiskWarmupEnabled && h2KeepAliveMinutes > 0) pingDatabase(db, "启动预热")
    while (true) {
        delay(10_000L)
        if (!h2DiskWarmupEnabled || h2KeepAliveMinutes <= 0) continue
        val intervalMillis = h2KeepAliveMinutes.coerceAtLeast(1).toLong() * 60_000L
        val now = System.currentTimeMillis()
        if (lastH2PingAt <= 0L || now - lastH2PingAt >= intervalMillis) {
            pingDatabase(db, "保活")
        }
    }
}

onEnable {
    try {
        logger.info("[数据库] 开始加载驱动依赖: $driverMaven")
        DependencyManager {
            require(Dependency.parse(driverMaven))
            loadToClassLoader(thisScript.javaClass.classLoader)
        }
        Class.forName(driver)

        val jdbcUrl = normalizeJdbcUrl(url.replace("H2DB_PATH", Config.dataDir.resolve("h2DB.db").absolutePath))
        val isH2 = jdbcUrl.lowercase().startsWith("jdbc:h2:")
        val preserveCase = preserveKeywordCasing
        logger.info("[数据库] 开始连接: ${jdbcUrl.substringBefore(';')}")
        // 驱动只加载到连接器自己的 ClassLoader，不能放入 coreLibrary/db 公共模块。
        // 否则业务脚本同时依赖 H2 数据库驱动和 KVStore 的 h2-mvstore 时，SA 3.4
        // 会让同名 MVMap 来自两个 ScriptClassLoader，最终触发 loader constraint violation。
        val db = Database.connect(
            { DriverManager.getConnection(jdbcUrl, user, password) },
            DatabaseConfig {
                @OptIn(ExperimentalKeywordApi::class)
                preserveKeywordCasing = preserveCase
            }
        )

        logger.info("[数据库] 连接已建立，开始检查表结构")
        DBApi.initDB(db)
        Services.provide(db)
        activeDatabase = db
        activeDatabaseIsH2 = isH2
        logger.info("[数据库] SA 3.4 Database Provider 已注册")

        if (isH2) launch(Dispatchers.IO) { runH2KeepAliveLoop(db) }
    } catch (e: Throwable) {
        logger.log(Level.SEVERE, "[数据库] 初始化失败，停止启用依赖数据库的脚本", e)
        throw e
    }
}

onDisable {
    activeDatabase = null
    activeDatabaseIsH2 = false
}

command("diskwarmup", "管理指令：控制云服H2磁盘预热/保活，减少磁盘休眠后首次读写卡顿") {
    aliases = listOf("h2warmup", "dbwarmup", "diskkeepalive", "磁盘预热", "数据库保活")
    usage = "[status|on|off|now|interval <1~1440分钟>]"
    permission = "coreLibrary.admin.diskWarmup"
    body {
        when (arg.firstOrNull()?.lowercase()) {
            null, "status", "状态" -> reply(diskWarmupStatusText().with())
            "on", "enable", "开", "开启" -> {
                if (h2KeepAliveMinutes <= 0) h2KeepAliveMinutes = 5
                h2DiskWarmupEnabled = true
                val db = activeDatabase
                val result = if (db != null && activeDatabaseIsH2) {
                    withContext(Dispatchers.IO) { pingDatabase(db, "手动启用预热") }
                } else null
                reply(("[green]已开启H2磁盘预热/保活，间隔 ${h2KeepAliveMinutes}分钟。" +
                        (result?.let { "\n${if (it.success) "[green]" else "[yellow]"}${it.message}${if (it.success) "，耗时 ${it.costMillis}ms" else ""}" }
                            ?: "\n[yellow]当前非H2或数据库尚未就绪，开关已保存。")).with())
            }
            "off", "disable", "关", "关闭" -> {
                h2DiskWarmupEnabled = false
                reply("[yellow]已关闭H2磁盘周期预热/保活；数据库连接本身不会关闭。".with())
            }
            "now", "ping", "立即", "预热" -> {
                val db = activeDatabase ?: returnReply("[red]数据库尚未就绪。".with())
                if (!activeDatabaseIsH2) returnReply("[yellow]当前不是H2文件数据库，无需执行磁盘预热。".with())
                val result = withContext(Dispatchers.IO) { pingDatabase(db, "手动预热") }
                reply("${if (result.success) "[green]" else "[red]"}${result.message}${if (result.success) "，耗时 ${result.costMillis}ms" else ""}".with())
            }
            "interval", "minutes", "间隔" -> {
                val minutes = arg.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..1440 }
                    ?: returnReply("[red]用法：/diskwarmup interval <1~1440分钟>".with())
                h2KeepAliveMinutes = minutes
                reply("[green]H2磁盘预热/保活间隔已设为 [white]${minutes}分钟[green]；下一轮动态生效。".with())
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("coreLibrary.admin.diskWarmup", group = "@admin")
