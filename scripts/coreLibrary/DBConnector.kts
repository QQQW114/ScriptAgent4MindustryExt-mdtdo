@file:Depends("coreLibrary/DBApi")

package coreLibrary

import cf.wayzer.scriptAgent.util.DependencyManager
import cf.wayzer.scriptAgent.util.maven.Dependency
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.ExperimentalKeywordApi
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.logging.Level
import kotlin.system.measureTimeMillis

val driverMaven by config.key("com.h2database:h2:2.0.206", "驱动程序maven包")
val driver by config.key("org.h2.Driver", "驱动程序类名")
val url by config.key("jdbc:h2:H2DB_PATH;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "数据库连接uri", "特殊变量H2DB_PATH 指向data/h2DB.db")
val user by config.key("", "用户名")
val password by config.key("", "密码")
val preserveKeywordCasing by config.key(true, "是否保留关键字大小写, 老用户请设置为false")
val h2KeepAliveMinutes by config.key(5, "H2文件数据库保活间隔(分钟)，<=0关闭。用于避免部分VPS磁盘空闲后首次访问卡顿")

//Postgres example
// driverMaven: org.postgresql:postgresql:42.7.5
// driver: org.postgresql.Driver
// url: jdbc:postgresql://db:5432/postgres
// user: postgres
// password: your_password

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

private fun pingDatabase(db: Database, reason: String) {
    try {
        val cost = measureTimeMillis {
            transaction(db) {
                exec("SELECT 1")
            }
        }
        if (cost > 1000L) {
            logger.warning("[数据库] H2 $reason 耗时 ${cost}ms；若频繁出现，优先检查VPS磁盘IO/杀毒/网盘同步，或考虑迁移PostgreSQL。")
        }
    } catch (e: Throwable) {
        logger.log(Level.WARNING, "[数据库] H2 $reason 失败", e)
    }
}

onEnable {
    DependencyManager {
        require(Dependency.parse(driverMaven))
        loadToClassLoader(thisScript.javaClass.classLoader)
    }
    Class.forName(driver)

    val url = normalizeJdbcUrl(url.replace("H2DB_PATH", Config.dataDir.resolve("h2DB.db").absolutePath))
    val isH2 = url.lowercase().startsWith("jdbc:h2:")
    val db = Database.connect({
        DriverManager.getConnection(url, user, password)
    }, DatabaseConfig {
        @OptIn(ExperimentalKeywordApi::class)
        preserveKeywordCasing = thisScript.preserveKeywordCasing
    })
    DBApi.DB.provide(this, db)

    if (isH2) {
        launch(Dispatchers.IO) {
            pingDatabase(db, "启动预热")
            val interval = h2KeepAliveMinutes.coerceAtLeast(0)
            if (interval > 0) {
                while (true) {
                    delay(interval * 60_000L)
                    pingDatabase(db, "保活")
                }
            }
        }
    }
}
