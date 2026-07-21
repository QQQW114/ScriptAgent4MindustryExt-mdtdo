package coreLib.db

import cf.wayzer.scriptAgent.ScriptRegistry
import cf.wayzer.scriptAgent.define.Script
import cf.wayzer.scriptAgent.util.DSLBuilder
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.lib.getOrNull
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SchemaUtils.withDataBaseLock
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import kotlin.system.measureTimeMillis

/**
 * SA 3.4 数据库服务入口。
 *
 * 必须放在模块库中，不能继续把 ServiceRegistry 对象定义在 kts 内；否则不同脚本
 * 类加载器会持有不同的注册表实例，出现“连接已建立但业务侧 No Provider”。
 */
object DBApi {
    val db = Services.get<Database>()

    private var Script.registeredTable: List<Table>? by DSLBuilder.dataKey()

    object TableVersion : IdTable<String>("TableVersion") {
        override val id: Column<EntityID<String>> = varchar("table", 64).entityId()
        override val primaryKey = PrimaryKey(id)

        val version = integer("version")
        val updateDate = timestamp("time")

        fun get(table: Table): Int {
            val identity = TransactionManager.current().identity(table)
            return select(version).where { id eq identity }.firstOrNull()?.get(version) ?: 0
        }

        fun update(table: Table, versionV: Int) {
            val identity = TransactionManager.current().identity(table)
            if (get(table) == 0) {
                insert {
                    it[id] = identity
                    it[version] = versionV
                    it[updateDate] = Instant.now()
                }
            } else {
                update({ id eq identity }) {
                    it[version] = versionV
                    it[updateDate] = Instant.now()
                }
            }
        }

        fun check(table: Table) {
            val expectedVersion = (table as? WithUpgrade)?.version ?: 1
            val currentVersion = get(table)
            if (currentVersion >= expectedVersion) return

            exposedLogger.info("Do Database upgrade for $table: $currentVersion -> $expectedVersion")
            try {
                if (table is WithUpgrade) table.onUpgrade(currentVersion)
                else SchemaUtils.createMissingTablesAndColumns(table)
                update(table, expectedVersion)
            } catch (e: Throwable) {
                exposedLogger.error("Fail to do Database upgrade for $table: $currentVersion -> $expectedVersion", e)
            }
        }
    }

    interface WithUpgrade {
        val version: Int

        fun onUpgrade(oldVersion: Int) {
            SchemaUtils.createMissingTablesAndColumns(this as Table)
        }
    }

    context(script: Script)
    fun registerTable(vararg tables: Table) {
        script.registeredTable = script.registeredTable.orEmpty() + tables
        db.getOrNull()?.let { database ->
            transaction(database) {
                withDataBaseLock { initTable(tables.asIterable()) }
            }
        }
    }

    @Synchronized
    fun initDB(database: Database) {
        TransactionManager.defaultDatabase = database
        val allTables = ScriptRegistry.allScripts { it.inst != null }
            .flatMapTo(mutableSetOf()) { it.inst?.registeredTable.orEmpty() }

        transaction(database) {
            withDataBaseLock {
                SchemaUtils.create(TableVersion)
                initTable(allTables)
            }
        }
    }

    private fun initTable(source: Iterable<Table>) {
        val tables = SchemaUtils.sortTablesByReferences(source)
        val time = measureTimeMillis { tables.forEach { TableVersion.check(it) } }
        exposedLogger.info("Finish check upgrade for ${tables.size} tables, costs $time ms")
    }
}
