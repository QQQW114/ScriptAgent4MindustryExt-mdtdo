@file:Depends("wayzer/user/ban")
@file:Depends("coreLibrary/DBApi", "数据库储存")

package wayzer.user

import arc.util.serialization.Base64Coder
import coreLibrary.DBApi.DB.registerTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction as exposedTransaction
import java.security.MessageDigest
import java.rmi.server.UnicastRemoteObject
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger

object Table : IntIdTable("PlayerBanV2") {
    val ids = text("ids", eagerLoading = true)
    val reason = text("reason", eagerLoading = true)
    val operator = text("operator").nullable()
    val createTime = timestamp("createTime").defaultExpression(CurrentTimestamp)
    val endTime = timestamp("endTime").defaultExpression(CurrentTimestamp)
}
registerTable(Table)

object ServiceImpl : UnicastRemoteObject(), Ban.PlayerBanStore {
    private val slowDbWarnMs = 200L
    private val verySlowDbWarnMs = 1000L
    private val dbLogger = Logger.getLogger("wayzer.user.banStore")
    private val md5Digest = MessageDigest.getInstance("md5")!!

    private fun readResolve(): Any = ServiceImpl

    private fun slowDbCaller(): String =
        Thread.currentThread().stackTrace.firstOrNull {
            it.className == ServiceImpl::class.java.name && it.methodName !in setOf("transaction", "slowDbCaller")
        }?.methodName ?: "unknown"

    private fun <T> transaction(block: Transaction.() -> T): T {
        val start = System.nanoTime()
        var error: Throwable? = null
        try {
            return exposedTransaction { block() }
        } catch (t: Throwable) {
            error = t
            throw t
        } finally {
            val costMs = (System.nanoTime() - start) / 1_000_000L
            if (costMs >= slowDbWarnMs) {
                val level = if (costMs >= verySlowDbWarnMs) "严重慢事务" else "慢事务"
                val failed = error?.let { " failed=${it.javaClass.simpleName}: ${it.message}" } ?: ""
                dbLogger.warning("[数据库] $level: ${slowDbCaller()} cost=${costMs}ms thread=${Thread.currentThread().name}$failed")
            }
        }
    }

    private fun ResultRow.toBan() = Ban.PlayerBan(
        get(Table.id).value,
        ids = get(Table.ids).split("$").toSet(),
        reason = get(Table.reason),
        operator = get(Table.operator),
        createTime = get(Table.createTime),
        endTime = get(Table.endTime)
    )

    private fun shortStrForLookup(str: String): String {
        fun md5Md5(bs: ByteArray) = synchronized(md5Digest) {
            md5Digest.update(md5Digest.digest(bs))
            md5Digest.digest(bs)
        }

        val bs = md5Md5(str.toByteArray())
        return Base64Coder.encode(bs).sliceArray(0..2).map {
            when (it) {
                'k' -> 'K'
                'S' -> 's'
                'l' -> 'L'
                '+' -> 'A'
                '/' -> 'B'
                else -> it
            }
        }.joinToString("")
    }

    private fun getById(id: Int) = transaction {
        Table.selectAll().where { Table.id eq id }.firstOrNull()?.toBan()
    }

    override fun create(ids: Set<String>, duration: Duration, reason: String, operator: String?): Ban.PlayerBan =
        transaction {
            Table.insertAndGetId {
                it[Table.ids] = ids.joinToString("$", "$", "$")
                it[Table.endTime] = Instant.now() + duration
                it[Table.operator] = operator
                it[Table.reason] = reason
            }.let {
                getById(it.value)!!
            }
        }

    override fun findNotEnd(id: String): Ban.PlayerBan? = transaction {
        Table.selectAll().where { (Table.ids like "%$${id}$%") and Table.endTime.greater(CurrentTimestamp) }
            .firstOrNull()?.toBan()
    }

    override fun findNotEndByShortId(shortId: String): Ban.PlayerBan? = transaction {
        val fixed = shortId.trim()
        if (fixed.isEmpty()) return@transaction null
        Table.selectAll().where { Table.endTime.greater(CurrentTimestamp) }
            .asSequence()
            .map { it.toBan() }
            .firstOrNull { ban -> ban.ids.any { it.isNotBlank() && (it == fixed || shortStrForLookup(it) == fixed) } }
    }

    override fun delete(record: Int): Ban.PlayerBan? = transaction {
        getById(record)?.also {
            Table.deleteWhere { id eq record }
        }
    }
}

val rpcService = contextScript<coreLibrary.extApi.RpcService>()

onEnable {
    rpcService.register<Ban.PlayerBanStore> { ServiceImpl }
}
