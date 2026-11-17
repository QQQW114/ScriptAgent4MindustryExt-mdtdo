@file:Depends("coreMindustry/menu", "抽奖与背包 MenuV3 页面")
@file:Depends("coreLibrary/db", "抽奖记录持久化")
@file:Depends("wayzer/mdtDatabase", "MDT 玩家数据")
@file:Depends("wayzer/user/trustPoint", "MDC 扣除/发放")
@file:Depends("wayzer/user/trustLevel", "抽奖信任等级门槛")
@file:Depends("wayzer/user/ext/skills", "单位生成与状态效果")
@file:Depends("wayzer/ext/soundEffectMenu", "服务器小音效")

package wayzer.user

import arc.util.Log
import arc.util.serialization.Jval
import coreLib.db.DBApi
import coreLibrary.lib.PermissionApi
import coreMindustry.MenuV3
import coreMindustry.lib.broadcast
import coreMindustry.renderPaged
import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.Vars
import mindustry.content.StatusEffects
import mindustry.ctype.ContentType
import mindustry.gen.Player
import mindustry.gen.Unit
import mindustry.type.StatusEffect
import mindustry.type.UnitType
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction as exposedTransaction
import wayzer.lib.DatabaseFeature
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.isDatabaseFeatureEnabled
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

name = "MDT 抽奖系统"

private val trustPoint = contextScript<wayzer.user.TrustPoint>()
private val trustLevel = contextScript<wayzer.user.TrustLevel>()
private val skillsCore = contextScript<wayzer.user.ext.Skills>()
private val soundEffects = contextScript<wayzer.ext.SoundEffectMenu>()

private val poolFile by config.key("config/lottery/pool.json", "抽奖奖池 JSON")
private val jokeDir by config.key("config/lottery/jokes", "冷笑话题库目录")
private val singleCost by config.key(33, "单抽价格（MDC）")
private val tenCost by config.key(264, "十连价格（MDC）")

private val MAX_VOUCHERS = 100
private val HEAD_TICKET_STACK_LIMIT = 64
private val PITY_LIMIT = 100
private val HISTORY_LIMIT = 200
private val VOUCHER_MDC_3M = 11
private val VOUCHER_MDC_8M = 22
private val VOUCHER_MDC_PERMANENT = 66
private val HEAD_TICKET_CHANCE = 0.005
private val SHIELD_BUFF_AMOUNT = 500_000f
private val ALL_BUFF_EFFECTS = listOf("fast", "overdrive", "shielded")

private data class Joke(val id: String, val text: String)
private data class JokeLibrary(val version: String, val jokes: List<Joke>)

private data class Prize(
    val id: String,
    val name: String,
    val type: String,
    val rarity: String,
    val weight: Int,
    val amount: Int = 0,
    val unit: String = "",
    val count: Int = 1,
    val sound: String = "",
    val effect: String = "",
    val duration: Int = -1,
    val flying: Boolean = false,
)

private data class Pool(val version: String, val prizes: List<Prize>) {
    val totalWeight: Int get() = prizes.sumOf { it.weight }
}

private data class Profile(val uid: String, val draws: Int, val pity: Int)
private data class VoucherSlots(val headTickets: Int, val otherVouchers: Int)

private val HEAD_TICKET = Prize(
    id = "headticket",
    name = "头奖抽奖券",
    type = "headticket",
    rarity = "special",
    weight = 1,
)
private val HEAD_PRIZES = listOf(
    Prize("head-reign", "王座", "unit", "head", 1, unit = "reign"),
    Prize("head-corvus", "死星", "unit", "head", 1, unit = "corvus"),
    Prize("head-toxopid", "天蝎", "unit", "head", 1, unit = "toxopid"),
    Prize("head-conquer", "领主", "unit", "head", 1, unit = "conquer"),
    Prize("head-collaris", "天帝", "unit", "head", 1, unit = "collaris"),
    Prize("head-eclipse", "日蚀", "unit", "head", 1, unit = "eclipse"),
    Prize("head-oct", "要塞", "unit", "head", 1, unit = "oct"),
    Prize("head-disrupt", "disrupt（悲怆）", "unit", "head", 1, unit = "disrupt"),
    Prize("head-emanate", "发散", "unit", "head", 1, unit = "emanate"),
    Prize("head-omura", "起飞的海神", "unit", "head", 1, unit = "omura", flying = true),
    Prize("head-navanax", "起飞的龙王", "unit", "head", 1, unit = "navanax", flying = true),
    Prize("head-mdc-1000", "1000 MDC", "mdc", "head", 1, amount = 1000),
    Prize("head-buff-all", "无限时长全增益 buff 兑换券", "buff_all", "head", 1),
)

private data class PreparedDraw(
    val id: Int,
    val prize: Prize,
    val jokeId: String?,
    val jokeText: String?,
    val effectJson: String?,
    val status: String,
    val balanceAfter: Int? = null,
)
private sealed class BatchOutcome {
    data class Success(val draws: List<PreparedDraw>) : BatchOutcome()
    data class Rejected(val userMessage: String) : BatchOutcome()
}
private data class Voucher(
    val ids: List<Int>,
    val name: String,
    val kind: String,
    val effect: String,
    val duration: Int,
) {
    val id: Int get() = ids.first()
    val count: Int get() = ids.size
}
private data class HistoryRow(
    val id: Int,
    val prizeName: String,
    val prizeType: String,
    val isHead: Boolean,
    val status: String,
    val jokeText: String?,
    val cost: Int,
    val batchId: String,
    val createdAt: Long,
)
private data class RoundInfo(val remaining: Int, val total: Int, val version: String)

private object LotteryProfiles : IntIdTable("LotteryProfiles") {
    val uid = varchar("uid", 64).uniqueIndex()
    val totalDraws = integer("totalDraws").default(0)
    val pityCounter = integer("pityCounter").default(0)
    val updatedAt = long("updatedAt").default(0L)
}

private object LotteryRounds : IntIdTable("LotteryRounds") {
    val uid = varchar("uid", 64)
    val roundNo = integer("roundNo")
    val libraryVersion = text("libraryVersion")
    val offset = integer("offset")
    val stride = integer("stride")
    val cursor = integer("cursor").default(0)
    val total = integer("total")
    val status = text("status").default("active")
    val createdAt = long("createdAt").default(0L)

    init {
        index(false, uid)
        uniqueIndex(uid, roundNo)
    }
}

private object LotteryDraws : IntIdTable("LotteryDraws") {
    val uid = varchar("uid", 64)
    val batchId = text("batchId")
    val prizeId = text("prizeId")
    val prizeName = text("prizeName")
    val prizeType = text("prizeType")
    val rarity = text("rarity")
    val effectJson = text("effectJson").nullable()
    val jokeId = text("jokeId").nullable()
    val jokeText = text("jokeText").nullable()
    val cost = integer("cost").default(0)
    val isHead = bool("isHead").default(false)
    val status = varchar("status", 32).default("applied")
    val createdAt = long("createdAt").default(0L)
    val usedAt = long("usedAt").nullable()

    init {
        index(false, uid)
        index(false, uid, status)
    }
}

DBApi.registerTable(LotteryProfiles)
DBApi.registerTable(LotteryRounds)
DBApi.registerTable(LotteryDraws)

private var loadedPool: Pool? = null
private var loadedLibraries: Map<String, JokeLibrary> = emptyMap()
private var currentJokeVersion: String? = null
private var cleanupHook: ((String) -> kotlin.Unit)? = null

private fun db(block: Transaction.() -> Unit) {
    exposedTransaction(DBApi.db.get()) { block() }
}

private fun <T> dbResult(block: Transaction.() -> T): T =
    exposedTransaction(DBApi.db.get()) { block() }

private fun now(): Long = System.currentTimeMillis()

private fun jsonEscape(text: String): String = buildString {
    for (c in text) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

private val defaultJokes = (1..10).map { Joke("test-$it", "测试$it") }

private fun writeDefaultJokes(file: File) {
    file.parentFile?.mkdirs()
    file.writeText(
        defaultJokes.joinToString("\n") {
            """{"id":"${jsonEscape(it.id)}","text":"${jsonEscape(it.text)}"}"""
        } + "\n",
        Charsets.UTF_8,
    )
}

private fun versionNumber(name: String): Int =
    Regex("""v(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

private fun loadJokeLibraries(): Pair<Map<String, JokeLibrary>, String> {
    val dir = File(jokeDir)
    dir.mkdirs()
    var files = dir.listFiles { f -> f.isFile && f.name.matches(Regex("""v\d+\.jsonl""")) }
        ?.sortedBy { versionNumber(it.name) }
        .orEmpty()
    if (files.isEmpty()) {
        val seed = File(dir, "v1.jsonl")
        writeDefaultJokes(seed)
        Log.warn("[lottery] 未发现题库，已生成测试题库 v1（测试1-10）；上线前请替换为正式内容")
        files = listOf(seed)
    }

    val libraries = linkedMapOf<String, JokeLibrary>()
    for (file in files) {
        val version = file.nameWithoutExtension
        val jokes = file.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching {
                val json = Jval.read(line)
                val id = json.getString("id", "").trim()
                val text = json.getString("text", "").trim()
                if (id.isBlank() || text.isBlank()) null else Joke(id, text)
            }.getOrNull()
        }
        if (jokes.isEmpty()) {
            Log.warn("[lottery] 题库文件无有效条目: ${file.absolutePath}")
            continue
        }
        libraries[version] = JokeLibrary(version, jokes)
    }
    if (libraries.isEmpty()) error("题库目录中没有有效题库: ${dir.absolutePath}")
    val current = libraries.keys.maxByOrNull { versionNumber(it) }!!
    return libraries to current
}

private val defaultPoolJson = """
{
  "version": 1,
  "prizes": [
    {"id":"joke-normal","name":"冷笑话","type":"joke","rarity":"common","weight":40},

    {"id":"mdc-20","name":"20 MDC","type":"mdc","rarity":"common","weight":20,"amount":20},
    {"id":"mdc-50","name":"50 MDC","type":"mdc","rarity":"rare","weight":12,"amount":50},
    {"id":"mdc-100","name":"100 MDC","type":"mdc","rarity":"epic","weight":5,"amount":100},
    {"id":"mdc-minus-10","name":"-10 MDC","type":"mdc","rarity":"common","weight":8,"amount":-10},

    {"id":"unit-dagger","name":"3 个 dagger","type":"unit","rarity":"rare","weight":8,"unit":"dagger","count":3},
    {"id":"unit-mace","name":"1 个 mace","type":"unit","rarity":"epic","weight":4,"unit":"mace","count":1},
    {"id":"unit-poly","name":"1 个 poly","type":"unit","rarity":"epic","weight":3,"unit":"poly","count":1},

    {"id":"buff-fast-3","name":"快速 3 分钟","type":"buff","rarity":"common","weight":10,"effect":"fast","duration":180},
    {"id":"buff-fast-8","name":"快速 8 分钟","type":"buff","rarity":"rare","weight":6,"effect":"fast","duration":480},
    {"id":"buff-fast-perm","name":"快速 永久","type":"buff","rarity":"epic","weight":2,"effect":"fast","duration":-1},
    {"id":"buff-overdrive-3","name":"超速 3 分钟","type":"buff","rarity":"common","weight":10,"effect":"overdrive","duration":180},
    {"id":"buff-overdrive-8","name":"超速 8 分钟","type":"buff","rarity":"rare","weight":6,"effect":"overdrive","duration":480},
    {"id":"buff-overdrive-perm","name":"超速 永久","type":"buff","rarity":"epic","weight":2,"effect":"overdrive","duration":-1},
    {"id":"buff-shielded-3","name":"护盾 3 分钟","type":"buff","rarity":"common","weight":10,"effect":"shielded","duration":180},
    {"id":"buff-shielded-8","name":"护盾 8 分钟","type":"buff","rarity":"rare","weight":6,"effect":"shielded","duration":480},
    {"id":"buff-shielded-perm","name":"护盾 永久","type":"buff","rarity":"epic","weight":2,"effect":"shielded","duration":-1},

    {"id":"debuff-slow","name":"减速（永久）","type":"debuff","rarity":"rare","weight":5,"effect":"slow","duration":-1},
    {"id":"debuff-wet","name":"潮湿（永久）","type":"debuff","rarity":"rare","weight":5,"effect":"wet","duration":-1},
    {"id":"debuff-sapped","name":"虚弱（永久）","type":"debuff","rarity":"rare","weight":4,"effect":"sapped","duration":-1},
    {"id":"debuff-tarred","name":"焦油（永久）","type":"debuff","rarity":"rare","weight":4,"effect":"tarred","duration":-1},
    {"id":"debuff-corroded","name":"腐蚀（永久）","type":"debuff","rarity":"epic","weight":3,"effect":"corroded","duration":-1},

    {"id":"sound-omg","name":"omg 服务器音效","type":"sound","rarity":"epic","weight":2,"sound":"omg"}
  ]
}
""".trimIndent()

private fun loadPool(): Pool {
    val file = File(poolFile)
    if (!file.exists()) {
        file.parentFile?.mkdirs()
        file.writeText(defaultPoolJson + "\n", Charsets.UTF_8)
        Log.info("[lottery] 已生成测试奖池: ${file.absolutePath}")
    }

    val root = Jval.read(file.readText(Charsets.UTF_8))
    val array = root.get("prizes")?.asArray() ?: error("奖池缺少 prizes 数组")
    val prizes = array.mapNotNull { node ->
        val id = node.getString("id", "").trim()
        val name = node.getString("name", "").trim()
        val type = node.getString("type", "").trim().lowercase()
        val rarity = node.getString("rarity", "common").trim().lowercase()
        val weight = node.getInt("weight", 1)
        if (id.isBlank() || name.isBlank() || weight <= 0) return@mapNotNull null
        if (rarity == "grand") {
            Log.info("[lottery] 已忽略旧头奖配置 $id；头奖改由头奖抽奖券抽取")
            return@mapNotNull null
        }
        if (type !in setOf("joke", "mdc", "unit", "sound", "buff", "debuff")) {
            Log.warn("[lottery] 未知奖品类型 $type，已跳过: $id")
            return@mapNotNull null
        }
        val amount = node.getInt("amount", 0)
        val unit = node.getString("unit", "").trim()
        val count = node.getInt("count", 1).coerceIn(1, 20)
        val sound = node.getString("sound", "").trim()
        val effect = node.getString("effect", "").trim()
        val duration = node.getInt("duration", -1)

        when (type) {
            "mdc" -> if (amount == 0) return@mapNotNull null
            "unit" -> if (unit.isBlank() || skillsCore.unitTypeByName(unit) == null) {
                Log.warn("[lottery] 未知单位 $unit，已跳过: $id")
                return@mapNotNull null
            }
            "sound" -> if (sound.isBlank()) return@mapNotNull null
            "buff", "debuff" -> {
                val status = findStatusEffect(effect)
                if (status == null) {
                    Log.warn("[lottery] 未知状态 $effect，已跳过: $id")
                    return@mapNotNull null
                }
                if (type == "buff" && duration != -1 && duration <= 0) return@mapNotNull null
            }
        }
        Prize(id, name, type, rarity, weight, amount, unit, count, sound, effect, duration)
    }
    if (prizes.isEmpty()) error("奖池没有有效奖品: ${file.absolutePath}")
    val version = root.getString("version", "1")
    return Pool(version, prizes)
}

private fun validateHeadPrizes() {
    var missing = 0
    for (prize in HEAD_PRIZES) {
        when (prize.type) {
            "unit" -> if (skillsCore.unitTypeByName(prize.unit) == null) {
                missing++
                Log.warn("[lottery] 头奖单位不存在: ${prize.name} (${prize.unit})")
            }
            "mdc" -> Unit
            "buff_all" -> for (effect in ALL_BUFF_EFFECTS) {
                if (findStatusEffect(effect) == null) {
                    missing++
                    Log.warn("[lottery] 全增益状态不存在: $effect")
                }
            }
            else -> {
                missing++
                Log.warn("[lottery] 未知头奖类型: ${prize.type} / ${prize.name}")
            }
        }
    }
    if (missing == 0) Log.info("[lottery] 头奖池 ${HEAD_PRIZES.size} 项校验通过（含起飞单位/全增益券）")
}

private fun isDefaultJokeLibrary(library: JokeLibrary?): Boolean =
    library != null &&
            library.jokes.size == defaultJokes.size &&
            library.jokes.map { it.id } == defaultJokes.map { it.id } &&
            library.jokes.map { it.text } == defaultJokes.map { it.text }

private fun reloadContent(): String {
    val libraries = runCatching { loadJokeLibraries() }.getOrElse {
        return "[red]题库加载失败：${it.message ?: it.javaClass.simpleName}"
    }
    val newPool = runCatching { loadPool() }.getOrElse {
        return "[red]奖池加载失败：${it.message ?: it.javaClass.simpleName}"
    }
    loadedLibraries = libraries.first
    currentJokeVersion = libraries.second
    loadedPool = newPool
    validateHeadPrizes()
    if (isDefaultJokeLibrary(libraries.first[currentJokeVersion])) {
        Log.warn(
            "[lottery] 当前题库仍是自动生成的测试题库 ${currentJokeVersion}（测试1-10）；" +
                    "上线前请替换为正式题库，避免带着测试文案上线"
        )
    }
    return "[green]已加载奖池 v${newPool.version}（${newPool.prizes.size} 项），" +
            "题库 ${currentJokeVersion}（${libraries.first[currentJokeVersion]?.jokes?.size ?: 0} 条）"
}

private fun findStatusEffect(name: String): StatusEffect? {
    if (name.isBlank()) return null
    return Vars.content.getByName<StatusEffect>(ContentType.status, name)
        ?: Vars.content.statusEffects().firstOrNull {
            it.name.equals(name, ignoreCase = true) || it.localizedName.equals(name, ignoreCase = true)
        }
}

private fun ensureProfileTx(uid: String): Profile {
    val row = LotteryProfiles.selectAll().where { LotteryProfiles.uid eq uid }.firstOrNull()
    if (row != null) {
        return Profile(uid, row[LotteryProfiles.totalDraws], row[LotteryProfiles.pityCounter])
    }
    LotteryProfiles.insert {
        it[LotteryProfiles.uid] = uid
        it[totalDraws] = 0
        it[pityCounter] = 0
        it[updatedAt] = now()
    }
    return Profile(uid, 0, 0)
}

private fun loadProfile(uid: String): Profile = dbResult { ensureProfileTx(uid) }

private fun voucherSlotsTx(uid: String): VoucherSlots {
    var headTickets = 0
    var otherVouchers = 0
    LotteryDraws.selectAll().where {
        (LotteryDraws.uid eq uid) and (LotteryDraws.status eq "unused")
    }.forEach { row ->
        if (row[LotteryDraws.prizeType] == "headticket") headTickets++ else otherVouchers++
    }
    return VoucherSlots(headTickets, otherVouchers)
}

private fun unusedVouchers(uid: String): List<Voucher> = dbResult {
    LotteryDraws.selectAll().where {
        (LotteryDraws.uid eq uid) and (LotteryDraws.status eq "unused")
    }.orderBy(LotteryDraws.id to SortOrder.DESC).mapNotNull { row ->
        val id = row[LotteryDraws.id].value
        val name = row[LotteryDraws.prizeName]
        when (row[LotteryDraws.prizeType]) {
            "headticket" -> Voucher(listOf(id), name, "headticket", "", -1)
            "buff_all" -> Voucher(listOf(id), name, "buff_all", "", -1)
            "buff" -> {
                val parsed = parseEffectJson(row[LotteryDraws.effectJson]) ?: return@mapNotNull null
                Voucher(listOf(id), name, "buff", parsed.first, parsed.second)
            }
            else -> null
        }
    }.groupBy { Triple(it.kind, it.effect, it.duration) }
        .values
        .map { group -> group.first().copy(ids = group.flatMap { it.ids }) }
}

private fun historyRows(uid: String): List<HistoryRow> = dbResult {
    LotteryDraws.selectAll().where { LotteryDraws.uid eq uid }
        .orderBy(LotteryDraws.id to SortOrder.DESC)
        .limit(HISTORY_LIMIT)
        .map {
            HistoryRow(
                id = it[LotteryDraws.id].value,
                prizeName = it[LotteryDraws.prizeName],
                prizeType = it[LotteryDraws.prizeType],
                isHead = it[LotteryDraws.isHead],
                status = it[LotteryDraws.status],
                jokeText = it[LotteryDraws.jokeText],
                cost = it[LotteryDraws.cost],
                batchId = it[LotteryDraws.batchId],
                createdAt = it[LotteryDraws.createdAt],
            )
        }
}

private fun activeRound(uid: String): RoundInfo? = dbResult {
    val row = LotteryRounds.selectAll().where { LotteryRounds.uid eq uid }
        .orderBy(LotteryRounds.roundNo to SortOrder.DESC)
        .limit(1)
        .firstOrNull() ?: return@dbResult null
    if (row[LotteryRounds.status] != "active") return@dbResult null
    RoundInfo(
        remaining = (row[LotteryRounds.total] - row[LotteryRounds.cursor]).coerceAtLeast(0),
        total = row[LotteryRounds.total],
        version = row[LotteryRounds.libraryVersion],
    )
}

private fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = x % y
        x = y
        y = t
    }
    return x
}

private fun chooseStride(total: Int): Int {
    if (total <= 1) return 1
    while (true) {
        val s = Random.nextInt(1, total)
        if (gcd(s, total) == 1) return s
    }
}

private fun nextJokeTx(uid: String): Joke {
    val version = currentJokeVersion ?: error("题库未加载")
    val libraries = loadedLibraries

    repeat(5) {
        val last = LotteryRounds.selectAll().where { LotteryRounds.uid eq uid }
            .orderBy(LotteryRounds.roundNo to SortOrder.DESC)
            .limit(1)
            .firstOrNull()

        val active = last?.takeIf {
            it[LotteryRounds.status] == "active" && it[LotteryRounds.cursor] < it[LotteryRounds.total]
        }
        val roundId: Int
        val roundNo: Int
        val roundVersion: String
        val offset: Int
        val stride: Int
        val cursor: Int
        val total: Int

        if (active != null) {
            roundId = active[LotteryRounds.id].value
            roundNo = active[LotteryRounds.roundNo]
            roundVersion = active[LotteryRounds.libraryVersion]
            offset = active[LotteryRounds.offset]
            stride = active[LotteryRounds.stride]
            cursor = active[LotteryRounds.cursor]
            total = active[LotteryRounds.total]
        } else {
            val library = libraries[version] ?: error("题库版本 $version 不存在")
            roundNo = (last?.get(LotteryRounds.roundNo) ?: 0) + 1
            roundVersion = version
            total = library.jokes.size
            offset = Random.nextInt(total)
            stride = chooseStride(total)
            cursor = 0
            roundId = LotteryRounds.insertAndGetId {
                it[LotteryRounds.uid] = uid
                it[LotteryRounds.roundNo] = roundNo
                it[LotteryRounds.libraryVersion] = roundVersion
                it[LotteryRounds.offset] = offset
                it[LotteryRounds.stride] = stride
                it[LotteryRounds.cursor] = 0
                it[LotteryRounds.total] = total
                it[LotteryRounds.status] = "active"
                it[LotteryRounds.createdAt] = now()
            }.value
        }

        if (total <= 0) error("题库为空")
        val library = libraries[roundVersion] ?: error("活跃轮次题库版本 $roundVersion 已丢失")
        val index = Math.floorMod(
            offset.toLong() + cursor.toLong() * stride.toLong(),
            total.toLong(),
        ).toInt()
        val joke = library.jokes.getOrNull(index) ?: error("题库索引越界")
        val newCursor = cursor + 1
        // 只有 cursor 仍等于读到的值时才推进；并发抽奖时另一事务已经推进则重试。
        val updated = LotteryRounds.update({
            (LotteryRounds.id eq roundId) and (LotteryRounds.cursor eq cursor)
        }) {
            it[LotteryRounds.cursor] = newCursor
            it[LotteryRounds.status] = if (newCursor >= total) "completed" else "active"
        }
        if (updated > 0) return joke
    }
    error("题库轮次并发冲突，请重试")
}

private fun choosePrize(headTickets: Int, forceHeadTicket: Boolean): Prize {
    if (headTickets < HEAD_TICKET_STACK_LIMIT && (forceHeadTicket || Random.nextDouble() < HEAD_TICKET_CHANCE)) {
        return HEAD_TICKET
    }
    val pool = loadedPool ?: error("奖池未加载")
    if (pool.prizes.isEmpty()) error("没有可抽取的奖品")
    var roll = Random.nextDouble() * pool.totalWeight
    for (prize in pool.prizes) {
        roll -= prize.weight
        if (roll <= 0.0) return prize
    }
    return pool.prizes.last()
}

private fun effectJsonFor(prize: Prize): String =
    """{"effect":"${jsonEscape(prize.effect)}","duration":${prize.duration},"name":"${jsonEscape(prize.name)}"}"""

private fun voucherCompensation(duration: Int): Int = when {
    duration < 0 -> VOUCHER_MDC_PERMANENT
    duration <= 180 -> VOUCHER_MDC_3M
    else -> VOUCHER_MDC_8M
}

private fun prepareDrawTx(
    uid: String,
    batchId: String,
    index: Int,
    count: Int,
    totalCost: Int,
    headTickets: Int,
    otherVouchers: Int,
): PreparedDraw {
    val profile = ensureProfileTx(uid)
    val forceHeadTicket = profile.pity >= PITY_LIMIT - 1 &&
            headTickets < HEAD_TICKET_STACK_LIMIT
    var prize = choosePrize(headTickets, forceHeadTicket)
    if (prize.type == "buff" && otherVouchers >= MAX_VOUCHERS) {
        val compensation = voucherCompensation(prize.duration)
        prize = prize.copy(
            name = "${prize.name}（奖券已满，折算 +$compensation MDC）",
            type = "mdc",
            amount = compensation,
            effect = "",
            duration = -1,
        )
    }
    val joke = if (prize.type == "joke") nextJokeTx(uid) else null
    val isHeadTicket = prize.type == "headticket"
    val effectJson = if (prize.type == "buff" || prize.type == "debuff") effectJsonFor(prize) else null
    val status = when (prize.type) {
        "buff", "headticket", "buff_all" -> "unused"
        else -> "applied"
    }
    val drawCost = if (count > 1 && index > 0) 0 else totalCost
    val balanceAfter = if (prize.type == "mdc") {
        MdtStorage.addTrustPointsInTx(uid, prize.amount)
    } else {
        null
    }

    val id = LotteryDraws.insertAndGetId {
        it[LotteryDraws.uid] = uid
        it[LotteryDraws.batchId] = batchId
        it[LotteryDraws.prizeId] = prize.id
        it[LotteryDraws.prizeName] = prize.name
        it[LotteryDraws.prizeType] = prize.type
        it[LotteryDraws.rarity] = prize.rarity
        it[LotteryDraws.effectJson] = effectJson
        it[LotteryDraws.jokeId] = joke?.id
        it[LotteryDraws.jokeText] = joke?.text
        it[LotteryDraws.cost] = drawCost
        it[LotteryDraws.isHead] = isHeadTicket
        it[LotteryDraws.status] = status
        it[LotteryDraws.createdAt] = now()
    }.value

    val nextPity = when {
        isHeadTicket -> 0
        profile.pity >= PITY_LIMIT - 1 -> PITY_LIMIT - 1
        else -> profile.pity + 1
    }
    LotteryProfiles.update({ LotteryProfiles.uid eq uid }) {
        it[totalDraws] = profile.draws + 1
        it[pityCounter] = nextPity
        it[updatedAt] = now()
    }

    return PreparedDraw(id, prize, joke?.id, joke?.text, effectJson, status, balanceAfter)
}

// 扣费、全部抽奖记录、保底与题库游标必须在同一个事务内提交；任何一步失败都整体回滚。
private fun prepareBatch(uid: String, count: Int, cost: Int, batchId: String): BatchOutcome = dbResult {
    if (!MdtStorage.spendTrustPointsInTx(uid, cost)) {
        return@dbResult BatchOutcome.Rejected("[red]MDC 不足：本次需要 $cost MDC。")
    }
    var slots = voucherSlotsTx(uid)
    val draws = List(count) { index ->
        val draw = prepareDrawTx(
            uid, batchId, index, count, cost,
            slots.headTickets, slots.otherVouchers,
        )
        when (draw.prize.type) {
            "headticket" -> slots = slots.copy(headTickets = slots.headTickets + 1)
            "buff", "buff_all" -> slots = slots.copy(otherVouchers = slots.otherVouchers + 1)
        }
        draw
    }
    BatchOutcome.Success(draws)
}

private fun prepareHeadDrawTx(uid: String, ticketId: Int): PreparedDraw? = dbResult {
    val claimed = LotteryDraws.update({
        (LotteryDraws.id eq ticketId) and
                (LotteryDraws.uid eq uid) and
                (LotteryDraws.status eq "unused") and
                (LotteryDraws.prizeType eq "headticket")
    }) {
        it[status] = "used"
        it[usedAt] = now()
    }
    if (claimed == 0) return@dbResult null

    val slots = voucherSlotsTx(uid)
    var prize = HEAD_PRIZES.random()
    if (prize.type == "buff_all" && slots.otherVouchers >= MAX_VOUCHERS) {
        prize = HEAD_PRIZES.filter { it.type != "buff_all" }.random()
    }
    val status = if (prize.type == "buff_all") "unused" else "applied"
    val balanceAfter = if (prize.type == "mdc") {
        MdtStorage.addTrustPointsInTx(uid, prize.amount)
    } else {
        null
    }
    val id = LotteryDraws.insertAndGetId {
        it[LotteryDraws.uid] = uid
        it[LotteryDraws.batchId] = "head:$ticketId"
        it[LotteryDraws.prizeId] = prize.id
        it[LotteryDraws.prizeName] = prize.name
        it[LotteryDraws.prizeType] = prize.type
        it[LotteryDraws.rarity] = prize.rarity
        it[LotteryDraws.effectJson] = null
        it[LotteryDraws.jokeId] = null
        it[LotteryDraws.jokeText] = null
        it[LotteryDraws.cost] = 0
        it[LotteryDraws.isHead] = true
        it[LotteryDraws.status] = status
        it[LotteryDraws.createdAt] = now()
    }.value
    PreparedDraw(id, prize, null, null, null, status, balanceAfter)
}

private fun drawHeadPrize(player: Player, ticketId: Int): String {
    val uid = PlayerData[player].id
    val prepared = try {
        prepareHeadDrawTx(uid, ticketId)
    } catch (t: Throwable) {
        Log.warn("[lottery] 头奖抽取失败 uid=$uid ticket=$ticketId", t)
        return "[red]头奖抽取失败，奖券仍保留，请稍后再试。"
    } ?: return "[yellow]该头奖抽奖券已被使用或状态已变化。"

    if (prepared.prize.type == "mdc") {
        runCatching { trustPoint.refreshTrustPoints(uid) }
            .onFailure { Log.warn("[lottery] 刷新 MDC 缓存失败 uid=$uid", it) }
        trustPoint.notifyMdcGranted(uid, prepared.prize.amount, "Lottery:${prepared.prize.id}")
    }

    if (prepared.prize.type == "buff_all") {
        broadcast("[gold][抽奖] [white]${player.plainName()}[gold] 抽中了头奖：[white]${prepared.prize.name}".with())
        return "[gold]头奖：[white]${prepared.prize.name}[green] 已存入背包，可在背包中对普通单位使用。"
    }

    val line = try {
        applyPrize(player, prepared)
    } catch (t: Throwable) {
        Log.warn("[lottery] 头奖发放异常 uid=$uid draw=${prepared.id}", t)
        markDeliveryFailed(prepared.id)
        "[red]头奖 ${prepared.prize.name} 发放异常，记录已保留，请联系管理员"
    }
    val failed = prepared.prize.type in setOf("unit", "mdc") && !line.startsWith("[green]")
    if (failed) markDeliveryFailed(prepared.id)
    broadcast("[gold][抽奖] [white]${player.plainName()}[gold] 抽中了头奖：[white]${prepared.prize.name}".with())
    return line
}

private fun parseEffectJson(raw: String?): Pair<String, Int>? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val json = Jval.read(raw)
        val effect = json.getString("effect", "").trim()
        val duration = json.getInt("duration", -1)
        if (effect.isBlank()) null else effect to duration
    }.getOrNull()
}

private fun voucherSubtitle(voucher: Voucher): String = when {
    voucher.kind == "headticket" -> "使用后抽取头奖"
    voucher.kind == "buff_all" -> "快速/超速永久增益 + 500,000 护盾"
    voucher.effect == "shielded" -> "使用后获得 500,000 护盾"
    else -> durationLabel(voucher.duration)
}

private fun durationLabel(duration: Int): String = when {
    duration < 0 -> "永久"
    duration % 60 == 0 -> "${duration / 60} 分钟"
    else -> "${duration} 秒"
}

private fun statusLabel(status: String): String = when (status) {
    "unused" -> "未使用"
    "used" -> "已使用"
    "expired" -> "已过期"
    "applied" -> "已生效"
    "delivery_failed" -> "发放失败"
    else -> status
}

private fun markDeliveryFailed(drawId: Int) {
    runCatching {
        dbResult {
            LotteryDraws.update({ LotteryDraws.id eq drawId }) {
                it[status] = "delivery_failed"
            }
        }
    }.onFailure { Log.warn("[lottery] 标记奖品发放失败 uid=draw:$drawId", it) }
}

// 正常情况下 player.unit() 已经是核心机；不要在这里另开 core 重生路径。
private fun debuffTarget(player: Player): Unit? =
    player.unit()?.takeIf { it.isValid && !it.dead }

private fun applyStatusPrize(player: Player, effectName: String, duration: Int): String {
    val unit = debuffTarget(player) ?: return "[yellow]当前没有可施加状态的单位"
    val effect = findStatusEffect(effectName) ?: return "[red]状态 ${effectName} 不存在"
    val seconds = if (duration < 0) Float.POSITIVE_INFINITY else duration.toFloat()
    val applied = skillsCore.applyStackedStatus(unit, effect, seconds, 1)
    return if (applied > 0) {
        "[green]已对当前单位施加 [cyan]${effect.localizedName}[green]：${durationLabel(duration)}"
    } else {
        "[yellow]当前单位免疫 ${effect.localizedName}，本次状态未生效"
    }
}

private fun spawnPrizeUnits(player: Player, prize: Prize): String {
    val type: UnitType = skillsCore.unitTypeByName(prize.unit)
        ?: return "[red]单位 ${prize.unit} 不存在"
    val count = prize.count.coerceIn(1, 20)
    fun applyFlight(unit: Unit) {
        if (prize.flying) {
            unit.elevation = 1f
            unit.update()
        }
    }
    if (player.unit() != null) {
        skillsCore.spawnAround(type, player, count) { applyFlight(it) }
    } else {
        repeat(count) {
            val unit = type.create(player.team())
            unit.set(player.x, player.y)
            unit.add()
            applyFlight(unit)
        }
    }
    val flight = if (prize.flying) "（已起飞）" else ""
    return "[green]已生成 [cyan]$count[green] 个 [white]${type.localizedName}$flight"
}

private fun applyPrize(player: Player, prepared: PreparedDraw): String {
    val uid = PlayerData[player].id
    val prize = prepared.prize
    return when (prize.type) {
        "joke" -> "[cyan]冷笑话：${prepared.jokeText ?: "（题库为空）"}"
        "mdc" -> {
            val newBalance = prepared.balanceAfter ?: trustPoint.getTrustPoints(uid)
            val sign = if (prize.amount >= 0) "+" else ""
            val color = if (prize.amount >= 0) "[green]" else "[red]"
            "$color${sign}${prize.amount} MDC[gray]，当前 [white]$newBalance"
        }
        "unit" -> spawnPrizeUnits(player, prize)
        "sound" -> {
            val result = soundEffects.playFixedSoundEffect(prize.sound, player.name, ignoreInterval = true)
            if (result.startsWith("[green]")) "[green]服务器音效：[white]${prize.name}" else result
        }
        "buff" -> "[green]已存入背包：[cyan]${prize.name}[gray]（${durationLabel(prize.duration)}，不设过期时间）"
        "headticket" -> "[green]已存入背包：[cyan]头奖抽奖券[gray]（在背包中使用后抽取头奖）"
        "buff_all" -> "[green]已存入背包：[cyan]无限时长全增益 buff 兑换券"
        "debuff" -> applyStatusPrize(player, prize.effect, -1)
        else -> "[yellow]未知奖品类型：${prize.type}"
    }
}

private fun formatTime(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))

private fun lotteryAccessMessage(player: Player): String? = when {
    !isDatabaseFeatureEnabled(DatabaseFeature.Lottery) ->
        "[yellow]抽奖系统已被管理员关闭，请联系管理员。"
    !trustLevel.hasTrustLevel(player, "3") ->
        "[red]抽奖仅对信任等级 3 级及以上玩家开放。"
    else -> null
}

private fun drawAndShow(player: Player, count: Int) {
    lotteryAccessMessage(player)?.let {
        player.sendMessage(it.with())
        return
    }
    val uid = PlayerData[player].id
    val pool = loadedPool
    if (pool == null || pool.prizes.isEmpty()) {
        player.sendMessage("[yellow]奖池未配置，暂不能抽奖。".with())
        return
    }
    val needJoke = pool.prizes.any { it.type == "joke" }
    if (needJoke && (currentJokeVersion == null || loadedLibraries[currentJokeVersion]?.jokes.isNullOrEmpty())) {
        player.sendMessage("[yellow]冷话题库为空，暂不能抽奖。".with())
        return
    }
    if (Vars.state.rules.pvp) {
        player.sendMessage("[yellow]PvP 模式下抽奖已关闭。".with())
        return
    }

    val cost = if (count <= 1) singleCost else tenCost
    val batchId = UUID.randomUUID().toString()
    val outcome = try {
        prepareBatch(uid, count, cost, batchId)
    } catch (t: Throwable) {
        Log.warn("[lottery] 抽奖事务失败，未扣除 MDC uid=$uid count=$count", t)
        player.sendMessage("[red]抽奖执行失败，MDC 未扣除，请稍后再试。".with())
        return
    }
    val prepared = when (outcome) {
        is BatchOutcome.Rejected -> {
            player.sendMessage(outcome.userMessage.with())
            return
        }
        is BatchOutcome.Success -> outcome.draws
    }

    // 事务提交后再同步 MDC 缓存；抽奖结果已经落库，不能再回滚。
    val newBalance = runCatching { trustPoint.refreshTrustPoints(uid) }
        .onFailure { Log.warn("[lottery] 刷新 MDC 缓存失败 uid=$uid", it) }
        .getOrNull()
    if (newBalance != null && cost > 0) {
        trustPoint.notifyMdcSpent(uid, cost, "Lottery:${batchId.take(8)}", newBalance)
    }
    prepared.filter { it.prize.type == "mdc" && it.prize.amount > 0 }.forEach {
        trustPoint.notifyMdcGranted(uid, it.prize.amount, "Lottery:${it.prize.id}")
    }

    val lines = mutableListOf<String>()
    for (draw in prepared) {
        val line = try {
            applyPrize(player, draw)
        } catch (t: Throwable) {
            Log.warn("[lottery] 奖品发放异常 uid=$uid draw=${draw.id} prize=${draw.prize.id}", t)
            markDeliveryFailed(draw.id)
            "[red]奖品 ${draw.prize.name} 发放异常，抽奖记录已保留，请联系管理员"
        }
        val failed = draw.prize.type in setOf("unit", "sound", "debuff") && !line.startsWith("[green]")
        if (failed) markDeliveryFailed(draw.id)
        lines += line
    }

    val title = if (count <= 1) "单抽" else "十连"
    player.sendMessage(
        (listOf("[green]${title}完成，消耗 [white]$cost MDC[green]：") + lines.map { "[gray]· $it" })
            .joinToString("\n").with()
    )
}

private fun voteStatusText(status: String): String = when (status) {
    "unused" -> "[green]未使用"
    "used" -> "[gray]已使用"
    "expired" -> "[red]已过期"
    "applied" -> "[gray]已生效"
    "delivery_failed" -> "[red]发放失败"
    else -> "[gray]$status"
}

private suspend fun openBackpack(player: Player) {
    val uid = PlayerData[player].id
    MenuV3(player) {
        title = "抽奖背包"
        fillScreen = false
        wrapInPane = false
        rootWidth = 620f
        val vouchers = unusedVouchers(uid)
        val totalCount = vouchers.sumOf { it.count }
        val buffCount = vouchers.filter { it.kind == "buff" }.sumOf { it.count }
        val allBuffCount = vouchers.filter { it.kind == "buff_all" }.sumOf { it.count }
        val ticketCount = vouchers.filter { it.kind == "headticket" }.sumOf { it.count }
        val otherCount = buffCount + allBuffCount
        msg = """
            |[cyan]这里存放正面 buff 奖券、头奖抽奖券和全增益兑换券（非实物，不进入物品栏）。
            |[gray]相同奖券已堆叠，只显示不同项；点击使用 1 张。buff/全增益券需附身普通单位，核心机不适用；头奖券无单位限制。
            |[gray]未附身时奖券会保留，不设过期时间。
            |[gray]当前未使用：[white]$totalCount[gray] 张（buff/全增益 [white]$otherCount/$MAX_VOUCHERS[gray]，头奖券 [white]$ticketCount/$HEAD_TICKET_STACK_LIMIT[gray]，共 [white]${vouchers.size}[gray] 种）。
            |[gray]不显示当前单位状态，只显示奖券本身。
        """.trimMargin()

        if (vouchers.isEmpty()) {
            option("（背包为空）") { }
        } else {
            renderPaged(vouchers, prePage = 6, key = "lottery_bag") { voucher ->
                val stack = if (voucher.count > 1) "[white] ×${voucher.count}" else ""
                option(
                    "[green]${voucher.name}$stack[gray] · ${voucherSubtitle(voucher)}"
                ) {
                    val result = useVoucher(player, voucher.id)
                    player.sendMessage(result.with())
                    refresh()
                }
            }
        }
        option("返回抽奖页") {
            close()
            openLotteryMenu(player)
        }
    }.send().awaitWithTimeout(120.seconds)
}

private fun claimVoucher(uid: String, drawId: Int): Boolean = dbResult {
    LotteryDraws.update({
        (LotteryDraws.id eq drawId) and (LotteryDraws.uid eq uid) and (LotteryDraws.status eq "unused")
    }) {
        it[status] = "used"
        it[usedAt] = now()
    } > 0
}

private fun releaseVoucher(uid: String, drawId: Int) {
    dbResult {
        LotteryDraws.update({
            (LotteryDraws.id eq drawId) and (LotteryDraws.uid eq uid) and (LotteryDraws.status eq "used")
        }) {
            it[status] = "unused"
            it[usedAt] = null
        }
    }
}

private fun useAllBuffVoucher(player: Player, drawId: Int, row: ResultRow): String {
    val uid = PlayerData[player].id
    val unit = player.unit()?.takeIf { it.isValid && !it.dead }
        ?: return "[yellow]当前没有可控单位，奖券已保留。"
    if (unit.spawnedByCore) return "[yellow]核心机不适用，请附身普通单位后再使用（奖券已保留）。"
    val statusEffects = ALL_BUFF_EFFECTS.filter { it != "shielded" }.map { name ->
        findStatusEffect(name) ?: return "[red]状态 $name 不存在，奖券已保留。"
    }
    if (!claimVoucher(uid, drawId)) return "[yellow]奖券状态已变化，未生效。"

    var applied = 0
    try {
        for (effect in statusEffects) {
            applied += skillsCore.applyStackedStatus(unit, effect, Float.POSITIVE_INFINITY, 1)
        }
        unit.shield(SHIELD_BUFF_AMOUNT)
        applied++
    } catch (t: Throwable) {
        releaseVoucher(uid, drawId)
        Log.warn("[lottery] 应用全增益奖券失败 uid=$uid draw=$drawId", t)
        return "[red]奖券效果应用失败，奖券已保留，请稍后再试。"
    }
    if (applied <= 0) {
        releaseVoucher(uid, drawId)
        return "[yellow]当前单位免疫全部增益，奖券已保留。"
    }
    val totalEffects = statusEffects.size + 1
    val partial = if (applied < totalEffects) "（部分增益免疫）" else ""
    return "[green]已使用 [cyan]${row[LotteryDraws.prizeName]}[green]，对当前普通单位施加全增益与 500,000 护盾$partial。"
}

private fun useVoucher(player: Player, drawId: Int): String {
    lotteryAccessMessage(player)?.let { return it }
    val uid = PlayerData[player].id
    val row = dbResult {
        LotteryDraws.selectAll().where {
            (LotteryDraws.id eq drawId) and (LotteryDraws.uid eq uid)
        }.firstOrNull()
    } ?: return "[red]奖券不存在。"
    if (row[LotteryDraws.status] != "unused") return "[yellow]该奖券状态为：${statusLabel(row[LotteryDraws.status])}"

    when (row[LotteryDraws.prizeType]) {
        "headticket" -> return drawHeadPrize(player, drawId)
        "buff_all" -> return useAllBuffVoucher(player, drawId, row)
        "buff" -> Unit
        else -> return "[red]未知奖券类型：${row[LotteryDraws.prizeType]}"
    }

    val parsed = parseEffectJson(row[LotteryDraws.effectJson])
        ?: return "[red]奖券效果数据损坏。"
    val unit = player.unit()?.takeIf { it.isValid && !it.dead }
        ?: return "[yellow]当前没有可控单位，奖券已保留。"
    if (unit.spawnedByCore) return "[yellow]核心机不适用，请附身普通单位后再使用（奖券已保留）。"
    if (parsed.first == "shielded") {
        if (!claimVoucher(uid, drawId)) return "[yellow]奖券状态已变化，未生效。"
        try {
            unit.shield(SHIELD_BUFF_AMOUNT)
        } catch (t: Throwable) {
            releaseVoucher(uid, drawId)
            Log.warn("[lottery] 应用护盾奖券失败 uid=$uid draw=$drawId", t)
            return "[red]奖券效果应用失败，奖券已保留，请稍后再试。"
        }
        return "[green]已使用 [cyan]${row[LotteryDraws.prizeName]}[green]，当前普通单位获得 500,000 护盾。"
    }

    val effect = findStatusEffect(parsed.first) ?: return "[red]状态 ${parsed.first} 不存在，奖券已保留。"
    if (!claimVoucher(uid, drawId)) return "[yellow]奖券状态已变化，未生效。"

    val seconds = if (parsed.second < 0) Float.POSITIVE_INFINITY else parsed.second.toFloat()
    val applied = try {
        skillsCore.applyStackedStatus(unit, effect, seconds, 1)
    } catch (t: Throwable) {
        releaseVoucher(uid, drawId)
        Log.warn("[lottery] 应用奖券效果失败 uid=$uid draw=$drawId", t)
        return "[red]奖券效果应用失败，奖券已保留，请稍后再试。"
    }
    if (applied <= 0) {
        releaseVoucher(uid, drawId)
        return "[yellow]当前单位免疫 ${effect.localizedName}，奖券已保留。"
    }
    return "[green]已使用 [cyan]${row[LotteryDraws.prizeName]}[green]，作用于当前普通单位。"
}

private suspend fun openHistory(player: Player) {
    val uid = PlayerData[player].id
    MenuV3(player) {
        title = "抽奖记录"
        fillScreen = false
        wrapInPane = false
        rootWidth = 680f
        val rows = historyRows(uid)
        msg = "[gray]显示最近 ${rows.size} 条记录（最多 $HISTORY_LIMIT 条）；十连只会在第一条显示批次总消耗。"
        if (rows.isEmpty()) {
            option("（暂无记录）") { }
        } else {
            renderPaged(rows, prePage = 8, key = "lottery_history") { row ->
                val head = if (row.isHead) "[gold]★ " else ""
                val batch = row.batchId.take(8)
                val cost = if (row.cost > 0) "${row.cost} MDC" else "十连内"
                option(
                    "$head[white]${row.prizeName}[gray] · ${row.prizeType} · $cost\n" +
                            "[gray]${formatTime(row.createdAt)} · ${voteStatusText(row.status)} · 批次 $batch"
                ) { }
            }
        }
        option("返回抽奖页") {
            close()
            openLotteryMenu(player)
        }
    }.send().awaitWithTimeout(120.seconds)
}

private suspend fun openPoolPreview(player: Player) {
    val pool = loadedPool
    MenuV3(player) {
        title = "奖池预览"
        fillScreen = false
        wrapInPane = false
        rootWidth = 650f
        val prizes = pool?.prizes.orEmpty()
        msg = if (pool == null) {
            "[yellow]奖池尚未加载。"
        } else {
            "[gray]当前奖池 v${pool.version}，共 ${prizes.size} 项；权重越高越常见。"
        }
        if (prizes.isEmpty()) {
            option("（奖池为空）") { }
        } else {
            renderPaged(prizes, prePage = 8, key = "lottery_pool") { prize ->
                option(
                    "[white]${prize.name}[gray] · ${prize.type} · ${prize.rarity} · 权重 ${prize.weight}"
                ) { }
            }
        }
        option("返回抽奖页") {
            close()
            openLotteryMenu(player)
        }
    }.send().awaitWithTimeout(120.seconds)
}

private suspend fun openRules(player: Player) {
    MenuV3(player) {
        title = "抽奖规则"
        fillScreen = false
        wrapInPane = true
        rootWidth = 620f
        label(
            """
            |[gold]价格
            |[white]单抽 $singleCost MDC；十连 $tenCost MDC（原价 ${singleCost * 10}，八折）。
            |
            |[gold]头奖
            |[white]普通抽奖不再直接出现头奖。每次抽奖有 0.5% 概率获得“头奖抽奖券”，在背包中使用后从 13 项头奖中随机抽取，所有头奖概率相同。
            |[white]每 $PITY_LIMIT 抽保底一张头奖抽奖券；抽到头奖券后保底计数清零。头奖券独立堆叠，最多 $HEAD_TICKET_STACK_LIMIT 张，不占其他奖券的 $MAX_VOUCHERS 张上限。
            |
            |[gold]奖券
            |[white]正面 buff 会存为奖券（不设过期时间）。只在附身普通单位时可用；核心机或无单位状态点击会被拒绝，奖券保留。
            |[white]“护盾”buff 改为给当前普通单位增加 500,000 护盾；全增益兑换券使用后对当前普通单位施加快速、超速永久增益，并增加 500,000 护盾。
            |[white]debuff 不进入背包，抽到后立即永久生效，不询问。
            |[white]buff/全增益券合计最多 $MAX_VOUCHERS 张，重复奖券会堆叠显示；满额后抽到的 buff 会折算为 MDC（3 分钟 +$VOUCHER_MDC_3M，8 分钟 +$VOUCHER_MDC_8M，永久 +$VOUCHER_MDC_PERMANENT）。
            |[white]MDC 增减、单位、服务器音效、冷笑话立即生效。
            |
            |[gold]冷笑话
            |[white]每名玩家独立轮次；同一轮内不重复，当前题库抽完后自动开启下一轮。
            |[white]题库和奖池由管理员通过文件更新，执行 /lotteryadmin reload 生效。
            |
            |[gold]限制
            |[white]PvP 模式默认关闭抽奖；余额不足时不扣费、不发奖。
            |[gray]本页面不显示当前单位状态；背包只显示奖券。
            """.trimMargin(),
            align = "left",
            wrap = true,
        )
        option("返回抽奖页") {
            close()
            openLotteryMenu(player)
        }
    }.send().awaitWithTimeout(120.seconds)
}

private suspend fun openLotteryMenu(player: Player) {
    lotteryAccessMessage(player)?.let {
        player.sendMessage(it.with())
        return
    }
    val uid = PlayerData[player].id
    MenuV3(player) {
        title = "MDT 抽奖"
        fillScreen = false
        wrapInPane = false
        rootWidth = 620f
        val vouchers = unusedVouchers(uid)
        val totalVouchers = vouchers.sumOf { it.count }
        val buffCount = vouchers.filter { it.kind == "buff" }.sumOf { it.count }
        val allBuffCount = vouchers.filter { it.kind == "buff_all" }.sumOf { it.count }
        val ticketCount = vouchers.filter { it.kind == "headticket" }.sumOf { it.count }
        val otherCount = buffCount + allBuffCount
        val profile = loadProfile(uid)
        val round = activeRound(uid)
        val pool = loadedPool
        val library = currentJokeVersion?.let { loadedLibraries[it] }
        val balance = trustPoint.getTrustPoints(uid)

        msg = """
            |[gold]余额：[white]$balance MDC
            |[gold]保底：[white]${profile.pity}/$PITY_LIMIT[gray]（第 $PITY_LIMIT 抽必得头奖券）
            |[gold]背包：[gray]buff/全增益 [white]$otherCount/$MAX_VOUCHERS[gray] · 头奖券 [white]$ticketCount/$HEAD_TICKET_STACK_LIMIT
            |[gold]冷话题库：[white]${library?.version ?: "未加载"}[gray] / ${library?.jokes?.size ?: 0} 条
            |[gold]当前轮次：[white]${round?.let { "${it.remaining}/${it.total} 条剩余" } ?: "尚未开始"}
            |[gold]奖池：[white]${pool?.let { "v${it.version} / ${it.prizes.size} 项" } ?: "未加载"}
        """.trimMargin()

        option("[green]单抽[gray] · $singleCost MDC") {
            drawAndShow(player, 1)
            refresh()
        }
        option("[green]十连[gray] · $tenCost MDC[gray]（八折）") {
            drawAndShow(player, 10)
            refresh()
        }
        option("[cyan]背包[gray] · ${vouchers.size} 种 / $totalVouchers 张（buff $buffCount，头奖券 $ticketCount）") {
            close()
            openBackpack(player)
        }
        option("抽奖记录") {
            close()
            openHistory(player)
        }
        option("奖池预览") {
            close()
            openPoolPreview(player)
        }
        option("规则") {
            close()
            openRules(player)
        }
    }.send().awaitWithTimeout(120.seconds)
}

command("lottery", "打开 MDT 抽奖页面") {
    aliases = listOf("抽奖", "jokelottery", "lc")
    body {
        val p = player ?: returnReply("[red]该指令需要在游戏内使用。".with())
        openLotteryMenu(p)
    }
}

// 维护入口：故意不受 DatabaseFeature.Lottery 限制；关闭玩家抽奖后仍可 reload/status。
command("lotteryadmin", "抽奖管理：重新加载奖池/题库") {
    usage = "<reload|status>"
    requirePermission("wayzer.admin.lottery")
    aliases = listOf("抽奖管理")
    body {
        when (arg.firstOrNull()?.lowercase()) {
            "reload", "重载" -> returnReply(reloadContent().with())
            "status", "状态" -> {
                val pool = loadedPool
                val library = currentJokeVersion?.let { loadedLibraries[it] }
                returnReply(
                    ("[green]奖池：${pool?.let { "v${it.version} / ${it.prizes.size} 项" } ?: "未加载"}" +
                            "\n[green]题库：${library?.let { "${it.version} / ${it.jokes.size} 条" } ?: "未加载"}" +
                            "\n[green]单抽 $singleCost MDC，十连 $tenCost MDC" +
                            "\n[green]头奖抽奖券概率 0.5%，每 $PITY_LIMIT 抽保底一张；头奖池 ${HEAD_PRIZES.size} 项等概率" +
                            "\n[green]buff/全增益券上限 $MAX_VOUCHERS，头奖券独立上限 $HEAD_TICKET_STACK_LIMIT").with()
                )
            }
            else -> returnReply("[yellow]用法：/lotteryadmin <reload|status>".with())
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.lottery", group = "@admin")

onEnable {
    val hook: (String) -> kotlin.Unit = { uid ->
        LotteryDraws.deleteWhere { LotteryDraws.uid eq uid }
        LotteryRounds.deleteWhere { LotteryRounds.uid eq uid }
        LotteryProfiles.deleteWhere { LotteryProfiles.uid eq uid }
        kotlin.Unit
    }
    cleanupHook = hook
    MdtStorage.registerSubjectCleanupHook(hook)
    Log.info("[lottery] ${reloadContent()}")
}

onDisable {
    cleanupHook?.let { MdtStorage.unregisterSubjectCleanupHook(it) }
    cleanupHook = null
    loadedPool = null
    loadedLibraries = emptyMap()
    currentJokeVersion = null
}
