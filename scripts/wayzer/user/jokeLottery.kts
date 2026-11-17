@file:Depends("coreMindustry/menu", "抽奖与背包 MenuV3 页面")
@file:Depends("coreLibrary/db", "抽奖记录持久化")
@file:Depends("wayzer/mdtDatabase", "MDT 玩家数据")
@file:Depends("wayzer/user/trustPoint", "MDC 扣除/发放")
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
import org.jetbrains.exposed.sql.transactions.transaction as exposedTransaction
import wayzer.lib.PlayerData
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

name = "MDT 抽奖系统"

private val trustPoint = contextScript<wayzer.user.TrustPoint>()
private val skillsCore = contextScript<wayzer.user.ext.Skills>()
private val soundEffects = contextScript<wayzer.ext.SoundEffectMenu>()

private val poolFile by config.key("config/lottery/pool.json", "抽奖奖池 JSON")
private val jokeDir by config.key("config/lottery/jokes", "冷笑话题库目录")
private val singleCost by config.key(33, "单抽价格（MDC）")
private val tenCost by config.key(264, "十连价格（MDC）")
private val voucherDays by config.key(28, "奖券有效天数")

private val MAX_VOUCHERS = 100
private val PITY_LIMIT = 100

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
)

private data class Pool(val version: String, val prizes: List<Prize>) {
    val totalWeight: Int get() = prizes.sumOf { it.weight }
    val hasGrand: Boolean get() = prizes.any { it.rarity == "grand" }
}

private data class Profile(val uid: String, val pity: Int, val draws: Int)
private data class PreparedDraw(
    val id: Int,
    val prize: Prize,
    val jokeId: String?,
    val jokeText: String?,
    val effectJson: String?,
    val status: String,
)
private data class Voucher(val id: Int, val name: String, val effect: String, val duration: Int, val expiresAt: Long)
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
    val pityCounter = integer("pityCounter").default(0)
    val totalDraws = integer("totalDraws").default(0)
    val updatedAt = long("updatedAt").default(0L)
}

private object LotteryRounds : IntIdTable("LotteryRounds") {
    val uid = text("uid")
    val roundNo = integer("roundNo")
    val libraryVersion = text("libraryVersion")
    val offset = integer("offset")
    val stride = integer("stride")
    val cursor = integer("cursor").default(0)
    val total = integer("total")
    val status = text("status").default("active")
    val createdAt = long("createdAt").default(0L)
}

private object LotteryDraws : IntIdTable("LotteryDraws") {
    val uid = text("uid")
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
    val status = text("status").default("applied")
    val createdAt = long("createdAt").default(0L)
    val usedAt = long("usedAt").nullable()
    val expiresAt = long("expiresAt").nullable()
}

DBApi.registerTable(LotteryProfiles)
DBApi.registerTable(LotteryRounds)
DBApi.registerTable(LotteryDraws)

private var loadedPool: Pool? = null
private var loadedLibraries: Map<String, JokeLibrary> = emptyMap()
private var currentJokeVersion: String? = null

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

    {"id":"sound-omg","name":"omg 服务器音效","type":"sound","rarity":"epic","weight":2,"sound":"omg"},

    {"id":"grand-mdc-1000","name":"头奖：1000 MDC","type":"mdc","rarity":"grand","weight":1,"amount":1000}
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
        return Profile(uid, row[LotteryProfiles.pityCounter], row[LotteryProfiles.totalDraws])
    }
    LotteryProfiles.insert {
        it[LotteryProfiles.uid] = uid
        it[pityCounter] = 0
        it[totalDraws] = 0
        it[updatedAt] = now()
    }
    return Profile(uid, 0, 0)
}

private fun loadProfile(uid: String): Profile = dbResult { ensureProfileTx(uid) }

private fun expireOldVouchersTx(uid: String) {
    val now = now()
    LotteryDraws.selectAll().where {
        (LotteryDraws.uid eq uid) and (LotteryDraws.status eq "unused")
    }.toList().forEach { row ->
        val expires = row[LotteryDraws.expiresAt]
        if (expires != null && expires <= now) {
            LotteryDraws.update({ LotteryDraws.id eq row[LotteryDraws.id].value }) {
                it[status] = "expired"
            }
        }
    }
}

private fun countUnusedVouchers(uid: String): Int = dbResult {
    expireOldVouchersTx(uid)
    LotteryDraws.selectAll().where {
        (LotteryDraws.uid eq uid) and (LotteryDraws.status eq "unused")
    }.count().toInt()
}

private fun unusedVouchers(uid: String): List<Voucher> = dbResult {
    expireOldVouchersTx(uid)
    LotteryDraws.selectAll().where {
        (LotteryDraws.uid eq uid) and (LotteryDraws.status eq "unused")
    }.orderBy(LotteryDraws.expiresAt to SortOrder.ASC).mapNotNull { row ->
        val parsed = parseEffectJson(row[LotteryDraws.effectJson]) ?: return@mapNotNull null
        Voucher(
            id = row[LotteryDraws.id].value,
            name = row[LotteryDraws.prizeName],
            effect = parsed.first,
            duration = parsed.second,
            expiresAt = row[LotteryDraws.expiresAt] ?: 0L,
        )
    }
}

private fun historyRows(uid: String): List<HistoryRow> = dbResult {
    LotteryDraws.selectAll().where { LotteryDraws.uid eq uid }
        .orderBy(LotteryDraws.id to SortOrder.DESC)
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
    val last = LotteryRounds.selectAll().where { LotteryRounds.uid eq uid }
        .orderBy(LotteryRounds.roundNo to SortOrder.DESC)
        .limit(1)
        .firstOrNull()

    var roundId: Int
    var roundNo: Int
    var roundVersion: String
    var offset: Int
    var stride: Int
    var cursor: Int
    var total: Int

    val active = last?.takeIf {
        it[LotteryRounds.status] == "active" && it[LotteryRounds.cursor] < it[LotteryRounds.total]
    }
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
    val index = Math.floorMod((offset.toLong() + cursor.toLong() * stride).toInt(), total)
    val joke = library.jokes.getOrNull(index) ?: error("题库索引越界")
    val newCursor = cursor + 1
    LotteryRounds.update({ LotteryRounds.id eq roundId }) {
        it[LotteryRounds.cursor] = newCursor
        it[LotteryRounds.status] = if (newCursor >= total) "completed" else "active"
    }
    return joke
}

private fun choosePrize(forceGrand: Boolean): Prize {
    val pool = loadedPool ?: error("奖池未加载")
    val candidates = if (forceGrand) pool.prizes.filter { it.rarity == "grand" } else pool.prizes
    if (candidates.isEmpty()) error("没有可抽取的奖品")
    var roll = Random.nextDouble() * candidates.sumOf { it.weight }
    for (prize in candidates) {
        roll -= prize.weight
        if (roll <= 0.0) return prize
    }
    return candidates.last()
}

private fun effectJsonFor(prize: Prize): String =
    """{"effect":"${jsonEscape(prize.effect)}","duration":${prize.duration},"name":"${jsonEscape(prize.name)}"}"""

private fun prepareDraw(uid: String, batchId: String, index: Int, count: Int, totalCost: Int): PreparedDraw =
    dbResult {
        val profile = ensureProfileTx(uid)
        val forceGrand = profile.pity >= PITY_LIMIT - 1
        val prize = choosePrize(forceGrand)
        val joke = if (prize.type == "joke") nextJokeTx(uid) else null
        val isHead = prize.rarity == "grand"
        val effectJson = if (prize.type == "buff" || prize.type == "debuff") effectJsonFor(prize) else null
        val status = if (prize.type == "buff") "unused" else "applied"
        val expiresAt = if (status == "unused") now() + voucherDays.toLong() * 86_400_000L else null
        val drawCost = if (count > 1 && index > 0) 0 else totalCost

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
            it[LotteryDraws.isHead] = isHead
            it[LotteryDraws.status] = status
            it[LotteryDraws.createdAt] = now()
            it[LotteryDraws.expiresAt] = expiresAt
        }.value

        LotteryProfiles.update({ LotteryProfiles.uid eq uid }) {
            it[pityCounter] = if (isHead) 0 else profile.pity + 1
            it[totalDraws] = profile.draws + 1
            it[updatedAt] = now()
        }

        PreparedDraw(id, prize, joke?.id, joke?.text, effectJson, status)
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
    else -> status
}

private fun debuffTarget(player: Player): Unit? {
    player.unit()?.takeIf { it.isValid && !it.dead }?.let { return it }
    if (player.dead()) return null
    runCatching { player.checkSpawn() }.onFailure {
        Log.warn("[lottery] core spawn failed uid=${player.uuid()}: ${it.message}")
    }
    return player.unit()?.takeIf { it.isValid && !it.dead }
}

private fun applyStatusPrize(player: Player, effectName: String, duration: Int): String {
    val unit = debuffTarget(player) ?: return "[yellow]当前没有可施加状态的核心机或单位"
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
    if (player.unit() != null) {
        skillsCore.spawnAround(type, player, count)
    } else {
        repeat(count) {
            val unit = type.create(player.team())
            unit.set(player.x, player.y)
            unit.add()
        }
    }
    return "[green]已生成 [cyan]$count[green] 个 [white]${type.localizedName}"
}

private fun applyPrize(player: Player, prepared: PreparedDraw): String {
    val uid = PlayerData[player].id
    val prize = prepared.prize
    return when (prize.type) {
        "joke" -> "[cyan]冷笑话：${prepared.jokeText ?: "（题库为空）"}"
        "mdc" -> {
            val newBalance = trustPoint.addTrustPoints(uid, prize.amount, "Lottery:${prize.id}")
            val sign = if (prize.amount >= 0) "+" else ""
            val color = if (prize.amount >= 0) "[green]" else "[red]"
            "$color${sign}${prize.amount} MDC[gray]，当前 [white]$newBalance"
        }
        "unit" -> spawnPrizeUnits(player, prize)
        "sound" -> {
            val result = soundEffects.playFixedSoundEffect(prize.sound, player.name, ignoreInterval = true)
            if (result.startsWith("[green]")) "[green]服务器音效：[white]${prize.name}" else result
        }
        "buff" -> "[green]已存入背包：[cyan]${prize.name}[gray]（${durationLabel(prize.duration)}，28 天内使用）"
        "debuff" -> applyStatusPrize(player, prize.effect, -1)
        else -> "[yellow]未知奖品类型：${prize.type}"
    }
}

private fun formatTime(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))

private fun drawAndShow(player: Player, count: Int) {
    val uid = PlayerData[player].id
    val pool = loadedPool
    if (pool == null || pool.prizes.isEmpty()) {
        player.sendMessage("[yellow]奖池未配置，暂不能抽奖。".with())
        return
    }
    if (!pool.hasGrand) {
        player.sendMessage("[yellow]奖池没有配置头奖（rarity=grand），暂不能抽奖。".with())
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
    val unused = countUnusedVouchers(uid)
    if (unused + count > MAX_VOUCHERS) {
        player.sendMessage("[yellow]未使用奖券已有 $unused 张，超过 $MAX_VOUCHERS 张上限，请先使用背包中的奖券。".with())
        return
    }
    if (trustPoint.getTrustPoints(uid) < cost) {
        player.sendMessage("[red]MDC 不足：本次需要 $cost MDC。".with())
        return
    }
    if (!trustPoint.spendTrustPoints(uid, cost, if (count <= 1) "Lottery:single" else "Lottery:ten")) {
        player.sendMessage("[red]MDC 扣除失败，请稍后再试。".with())
        return
    }

    val batchId = UUID.randomUUID().toString()
    val lines = mutableListOf<String>()
    var completed = 0
    try {
        repeat(count) { index ->
            val prepared = prepareDraw(uid, batchId, index, count, cost)
            completed++
            lines += applyPrize(player, prepared)
            if (prepared.prize.rarity == "grand") {
                broadcast("[gold][抽奖] [white]${player.plainName()}[gold] 抽中了头奖：[white]${prepared.prize.name}".with())
            }
        }
    } catch (t: Throwable) {
        Log.warn("[lottery] 抽奖执行失败 uid=$uid count=$count completed=$completed", t)
        if (completed == 0) {
            trustPoint.addCurrentTrustPoints(uid, cost, "Lottery:refund")
            player.sendMessage("[red]抽奖执行失败，已退回 $cost MDC。".with())
        } else {
            player.sendMessage("[red]抽奖中途失败，已完成 $completed 抽；请联系管理员检查日志。".with())
        }
        return
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
        msg = """
            |[cyan]这里只存放抽奖获得的正面 buff 奖券（非实物，不进入物品栏）。
            |[gray]使用后作用于当前控制单位；没有单位时会保留奖券。
            |[gray]每张奖券 28 天过期，最多同时持有 $MAX_VOUCHERS 张。
            |[gray]当前未使用：[white]${vouchers.size}/$MAX_VOUCHERS[gray]。
            |[gray]不显示当前单位状态，只显示奖券本身。
        """.trimMargin()

        if (vouchers.isEmpty()) {
            option("（背包为空）") { }
        } else {
            renderPaged(vouchers, prePage = 6, key = "lottery_bag") { voucher ->
                option(
                    "[green]${voucher.name}[gray] · ${durationLabel(voucher.duration)}" +
                            "[gray] · ${formatTime(voucher.expiresAt)} 过期"
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

private fun useVoucher(player: Player, drawId: Int): String {
    val uid = PlayerData[player].id
    val row = dbResult {
        LotteryDraws.selectAll().where {
            (LotteryDraws.id eq drawId) and (LotteryDraws.uid eq uid)
        }.firstOrNull()
    } ?: return "[red]奖券不存在。"
    if (row[LotteryDraws.status] != "unused") return "[yellow]该奖券状态为：${statusLabel(row[LotteryDraws.status])}"
    val expiresAt = row[LotteryDraws.expiresAt] ?: 0L
    if (expiresAt <= now()) {
        db {
            LotteryDraws.update({ LotteryDraws.id eq drawId }) { it[status] = "expired" }
        }
        return "[red]该奖券已过期。"
    }
    val parsed = parseEffectJson(row[LotteryDraws.effectJson])
        ?: return "[red]奖券效果数据损坏。"
    val unit = player.unit()?.takeIf { it.isValid && !it.dead && !it.spawnedByCore }
        ?: return "[yellow]当前未附身单位，奖券已保留。"
    val effect = findStatusEffect(parsed.first) ?: return "[red]状态 ${parsed.first} 不存在，奖券已保留。"
    val seconds = if (parsed.second < 0) Float.POSITIVE_INFINITY else parsed.second.toFloat()
    val applied = skillsCore.applyStackedStatus(unit, effect, seconds, 1)
    if (applied <= 0) return "[yellow]当前单位免疫 ${effect.localizedName}，奖券已保留。"

    db {
        LotteryDraws.update({ LotteryDraws.id eq drawId }) {
            it[status] = "used"
            it[usedAt] = now()
        }
    }
    return "[green]已使用 [cyan]${row[LotteryDraws.prizeName]}[green]，作用于当前单位。"
}

private suspend fun openHistory(player: Player) {
    val uid = PlayerData[player].id
    MenuV3(player) {
        title = "抽奖记录"
        fillScreen = false
        wrapInPane = false
        rootWidth = 680f
        val rows = historyRows(uid)
        msg = "[gray]共 ${rows.size} 条记录；十连只会在第一条显示批次总消耗。"
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
            |[gold]保底
            |[white]每名玩家独立计数，第 $PITY_LIMIT 抽必为头奖；自然抽到头奖会清零计数。
            |[gray]十连按 10 次顺序结算，保底可能落在其中任意一抽。
            |
            |[gold]奖券
            |[white]正面 buff 会存为奖券，28 天内使用；使用时作用于当前控制单位。
            |[white]debuff 不进入背包，抽到后立即永久生效，不询问。
            |[white]未使用正面奖券最多 $MAX_VOUCHERS 张。
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
    val uid = PlayerData[player].id
    MenuV3(player) {
        title = "MDT 抽奖"
        fillScreen = false
        wrapInPane = false
        rootWidth = 620f
        val profile = loadProfile(uid)
        val unused = countUnusedVouchers(uid)
        val round = activeRound(uid)
        val pool = loadedPool
        val library = currentJokeVersion?.let { loadedLibraries[it] }
        val balance = trustPoint.getTrustPoints(uid)

        msg = """
            |[gold]余额：[white]$balance MDC
            |[gold]保底进度：[white]${profile.pity}/$PITY_LIMIT[gray]（第 $PITY_LIMIT 抽必出头奖）
            |[gold]背包：[white]$unused/$MAX_VOUCHERS[gray] 张未使用奖券
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
        option("[cyan]背包[gray] · $unused/$MAX_VOUCHERS 张未使用奖券") {
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
                            "\n[green]单抽 $singleCost MDC，十连 $tenCost MDC，保底 $PITY_LIMIT 抽").with()
                )
            }
            else -> returnReply("[yellow]用法：/lotteryadmin <reload|status>".with())
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.lottery", group = "@admin")

onEnable {
    Log.info("[lottery] ${reloadContent()}")
}

onDisable {
    loadedPool = null
    loadedLibraries = emptyMap()
    currentJokeVersion = null
}
