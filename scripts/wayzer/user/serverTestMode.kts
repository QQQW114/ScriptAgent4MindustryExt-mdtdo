@file:Depends("coreMindustry/menu", "测试模式管理菜单")
@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.user

import coreLibrary.lib.PermissionApi
import coreMindustry.MenuBuilder
import cf.wayzer.scriptAgent.Event
import mindustry.Vars
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.ServerTestMode
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

name = "[危险]服务器测试模式"

private val TEST_UID_PREFIX = "test:"
private val TEST_REWARD_MULTIPLIER = 10
private val TEST_MODE_ENABLED_KEY = "serverTestMode.enabled"
private val tempFileName by config.key("server-test-mode.tmp.properties", "测试模式临时数据文件名；关闭测试模式时会删除")

private data class TempAccount(
    var name: String = "",
    var currentMdc: Int = 0,
    var totalMdc: Int = 0,
    var playMillis: Long = 0L,
)

private class TestModeService(
    private val file: File,
    private val uidPrefix: String,
    private val rewardMultiplier: Int,
) : ServerTestMode {
    @Volatile
    private var enabled = false

    private val records = ConcurrentHashMap<String, TempAccount>()
    private val originalPrimaryByUuid = ConcurrentHashMap<String, String>()
    private val lock = Any()

    private fun encodeKey(uid: String): String = uid.replace("%", "%25").replace(":", "%3A")
    private fun decodeKey(uid: String): String = uid.replace("%3A", ":").replace("%25", "%")
    private fun saveEnabledState(value: Boolean) {
        MdtStorage.setSetting(TEST_MODE_ENABLED_KEY, value.toString())
    }

    override fun isEnabled(): Boolean = enabled
    override fun testUid(uuid: String): String = "$uidPrefix$uuid"
    override fun ownsUid(uid: String): Boolean = uid.startsWith(uidPrefix)

    override fun isTestSession(player: Player): Boolean =
        enabled && ownsUid(PlayerData[player].id)

    override fun formalUid(player: Player): String? {
        val data = PlayerData[player]
        val mapped = originalPrimaryByUuid[player.uuid()]
        if (!mapped.isNullOrBlank() && mapped != player.uuid()) return mapped
        return data.ids.firstOrNull { it.startsWith("account:") }
    }

    override fun isTestEligible(player: Player): Boolean =
        enabled && formalUid(player)?.startsWith("account:") == true

    private fun ensureRecord(uid: String, name: String? = null): TempAccount =
        records.compute(uid) { _, old ->
            (old ?: TempAccount()).also { record ->
                if (!name.isNullOrBlank()) record.name = name.take(64)
            }
        }!!

    private fun saveLocked() {
        if (!enabled) return
        file.parentFile?.mkdirs()
        val props = Properties()
        val keys = records.keys.sorted()
        props.setProperty("enabled", "true")
        props.setProperty("createdBy", "MDT ServerTestMode")
        props.setProperty("uids", keys.joinToString(",") { encodeKey(it) })
        keys.forEach { uid ->
            val key = encodeKey(uid)
            val record = records[uid] ?: return@forEach
            props.setProperty("name.$key", record.name)
            props.setProperty("current.$key", record.currentMdc.toString())
            props.setProperty("total.$key", record.totalMdc.toString())
            props.setProperty("play.$key", record.playMillis.toString())
        }
        file.outputStream().use { props.store(it, "Temporary data for [DANGER] server test mode. Safe to delete when mode is off.") }
    }

    private fun save() = synchronized(lock) { saveLocked() }

    fun enableFresh() {
        synchronized(lock) {
            records.clear()
            originalPrimaryByUuid.clear()
            enabled = true
            if (file.exists()) file.delete()
            saveEnabledState(true)
            saveLocked()
        }
    }

    fun enableFromDatabase() {
        synchronized(lock) {
            records.clear()
            originalPrimaryByUuid.clear()
            enabled = true
            loadLocked()
            saveLocked()
        }
    }

    fun disableAndClear() {
        enabled = false
        Groups.player.toList().forEach { restoreSession(it, "disable") }
        synchronized(lock) {
            records.clear()
            originalPrimaryByUuid.clear()
            saveEnabledState(false)
            if (file.exists()) file.delete()
        }
    }

    fun shutdownPreserve() {
        enabled = false
        Groups.player.toList().forEach { restoreSession(it, "script-disable") }
        synchronized(lock) {
            records.clear()
            originalPrimaryByUuid.clear()
        }
    }

    private fun loadLocked() {
        if (!file.exists()) return
        runCatching {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.getProperty("uids").orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { encoded ->
                    val uid = decodeKey(encoded)
                    records[uid] = TempAccount(
                        name = props.getProperty("name.$encoded").orEmpty(),
                        currentMdc = props.getProperty("current.$encoded")?.toIntOrNull() ?: 0,
                        totalMdc = props.getProperty("total.$encoded")?.toIntOrNull() ?: 0,
                        playMillis = props.getProperty("play.$encoded")?.toLongOrNull() ?: 0L,
                    )
                }
        }.onFailure {
            logger.warning("[危险]读取测试模式临时数据失败，将以空临时数据继续：${it.message}")
        }
    }

    fun tempRecordCount(): Int = records.size

    override fun applySession(player: Player, reason: String) {
        if (!enabled) return
        val data = PlayerData[player]
        val formal = formalUid(player)
        if (formal == null || !formal.startsWith("account:")) {
            restoreSession(player, "not-authed-$reason")
            return
        }
        val uid = testUid(formal)
        if (data.id != uid) originalPrimaryByUuid[player.uuid()] = formal
        data.addId(uid, asPrimary = true)
        ensureRecord(uid, player.plainName())
        save()
    }

    override fun restoreSession(player: Player, reason: String) {
        val data = PlayerData[player]
        val formal = originalPrimaryByUuid[player.uuid()] ?: data.ids.firstOrNull { it.startsWith("account:") }
        val uid = formal?.let { testUid(it) } ?: data.ids.firstOrNull { ownsUid(it) }
        val oldPrimary = originalPrimaryByUuid.remove(player.uuid()) ?: player.uuid()
        if (uid != null && uid in data.ids) data.removeId(uid)
        data.addId(formal ?: oldPrimary, asPrimary = true)
    }

    override fun getTrustPoints(uid: String): Int =
        if (ownsUid(uid)) records[uid]?.currentMdc ?: 0 else 0

    override fun getTotalTrustPoints(uid: String): Int =
        if (ownsUid(uid)) records[uid]?.totalMdc ?: 0 else 0

    override fun addTrustPoints(uid: String, amount: Int): Int {
        if (!ownsUid(uid)) return 0
        val next = synchronized(lock) {
            val record = ensureRecord(uid)
            record.currentMdc = (record.currentMdc + amount).coerceAtLeast(0)
            if (amount > 0) record.totalMdc += amount
            saveLocked()
            record.currentMdc
        }
        return next
    }

    override fun addCurrentTrustPoints(uid: String, amount: Int): Int {
        if (!ownsUid(uid)) return 0
        val next = synchronized(lock) {
            val record = ensureRecord(uid)
            record.currentMdc = (record.currentMdc + amount).coerceAtLeast(0)
            saveLocked()
            record.currentMdc
        }
        return next
    }

    override fun spendTrustPoints(uid: String, amount: Int): Boolean {
        if (amount <= 0) return true
        if (!ownsUid(uid)) return false
        return synchronized(lock) {
            val record = ensureRecord(uid)
            if (record.currentMdc < amount) return@synchronized false
            record.currentMdc -= amount
            saveLocked()
            true
        }
    }

    override fun transferTrustPoints(fromUid: String, toUid: String, amount: Int): Boolean {
        if (amount <= 0 || fromUid == toUid || !ownsUid(fromUid) || !ownsUid(toUid)) return false
        return synchronized(lock) {
            val from = ensureRecord(fromUid)
            val to = ensureRecord(toUid)
            if (from.currentMdc < amount) return@synchronized false
            from.currentMdc -= amount
            to.currentMdc += amount
            saveLocked()
            true
        }
    }

    override fun setTrustPoints(uid: String, value: Int): Int {
        if (!ownsUid(uid)) return 0
        val fixed = value.coerceAtLeast(0)
        synchronized(lock) {
            ensureRecord(uid).currentMdc = fixed
            saveLocked()
        }
        return fixed
    }

    override fun setTotalTrustPoints(uid: String, value: Int): Int {
        if (!ownsUid(uid)) return 0
        val fixed = value.coerceAtLeast(0)
        synchronized(lock) {
            ensureRecord(uid).totalMdc = fixed
            saveLocked()
        }
        return fixed
    }

    override fun getPlayMillis(uid: String): Long =
        if (ownsUid(uid)) records[uid]?.playMillis ?: 0L else 0L

    override fun addPlayMillis(uid: String, millis: Long): Long {
        if (!ownsUid(uid) || millis <= 0L) return getPlayMillis(uid)
        val next = synchronized(lock) {
            val record = ensureRecord(uid)
            record.playMillis = (record.playMillis + millis).coerceAtLeast(0L)
            saveLocked()
            record.playMillis
        }
        return next
    }

    override fun addPlayMillisBatch(deltas: Map<String, Long>): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        val fixed = deltas.filter { (uid, delta) -> ownsUid(uid) && delta > 0L }
        if (fixed.isEmpty()) return emptyMap()
        synchronized(lock) {
            fixed.forEach { (uid, delta) ->
                val record = ensureRecord(uid)
                record.playMillis = (record.playMillis + delta).coerceAtLeast(0L)
                result[uid] = record.playMillis
            }
            saveLocked()
        }
        return result
    }

    override fun gameContributionRewardMultiplier(): Int = if (enabled) rewardMultiplier else 1

    override fun statusText(): String {
        val fileText = file.absolutePath
        return """
            |[cyan]状态：[white]${if (enabled) "[red]已启用" else "[green]未启用"}
            |[cyan]临时玩家数据：[white]${records.size} 条
            |[cyan]结算倍率：[white]${gameContributionRewardMultiplier()}x
            |[cyan]临时数据文件：[gray]$fileText
            |[gray]启用后：仅已登录账号切到临时测试主体；正式4级玩家仍视为4级，其余已登录玩家视为信任1级、资历3级；MDC写入临时文件；关闭后清理临时数据并恢复原会话主体。
        """.trimMargin()
    }
}

private fun tempFile(): File {
    val dir = File(Vars.dataDirectory.file(), "scripts/.server-test-mode")
    return File(dir, tempFileName)
}

private val testMode = TestModeService(tempFile(), TEST_UID_PREFIX, TEST_REWARD_MULTIPLIER)

private fun enabledMessage(): String =
    "[red][危险]服务器测试模式已启用：[yellow]请先登录账号；登录后使用临时测试MDC，非4级默认信任1/资历3，结算MDC×$TEST_REWARD_MULTIPLIER。"

private fun applyAllOnline(reason: String) {
    Groups.player.toList().forEach { testMode.applySession(it, reason) }
}

private fun enableMode(operatorName: String): Boolean {
    if (testMode.isEnabled()) return false
    testMode.enableFresh()
    applyAllOnline("enable")
    broadcast("$operatorName 启用了 ${enabledMessage()}".with())
    logger.warning("[危险]服务器测试模式已由 $operatorName 启用")
    return true
}

private fun disableMode(operatorName: String): Boolean {
    if (!testMode.isEnabled()) return false
    testMode.disableAndClear()
    broadcast("[green][危险]服务器测试模式已由 [white]$operatorName[green] 关闭，临时账号/MDC数据已清理；如登录态异常请重进或重新登录。".with())
    logger.warning("[危险]服务器测试模式已由 $operatorName 关闭，临时数据已清理")
    return true
}

private suspend fun confirmDanger(player: Player, action: String, detail: String): Boolean {
    var confirmed = false
    MenuBuilder<Unit>("[危险]确认${action}") {
        msg = """
            |[red]你正在${action}服务器测试模式。
            |[yellow]$detail
            |
            |[scarlet]你必须知道其用途才应当继续；此功能仅适用于特殊测试时期。
        """.trimMargin()
        option("[red]确认${action}") { confirmed = true }
        option("[green]取消") {}
    }.sendTo(player, 60_000)
    return confirmed
}

private suspend fun openTestModeMenu(player: Player) {
    if (!player.hasPermission("wayzer.admin.serverTestMode")) {
        player.sendMessage("[red]权限不足：需要服务器测试模式管理权限 wayzer.admin.serverTestMode")
        return
    }
    MenuBuilder<Unit>("[危险]服务器测试模式") {
        msg = """
            |[red]你必须知道其用途才应当启用，仅适用于特殊时期。
            |
            |${testMode.statusText()}
        """.trimMargin()
        if (!testMode.isEnabled()) {
            option("[red]启用测试模式\n[gray]已登录玩家临时MDC/结算×10") {
                if (confirmDanger(player, "启用", "启用后会立即切换所有已登录在线玩家到临时测试主体；未登录玩家仍需登录后才能获得测试MDC与1信任/3资历。")) {
                    enableMode(player.plainName())
                }
            }
        } else {
            option("[green]关闭并清理测试模式\n[gray]恢复原账号主体，删除临时数据文件") {
                if (confirmDanger(player, "关闭", "关闭后会删除临时账号/MDC数据，恢复原账号系统。")) {
                    disableMode(player.plainName())
                }
            }
        }
        newRow()
        option("刷新状态") { openTestModeMenu(player) }
        option("关闭") {}
    }.sendTo(player, 60_000)
}

onEnable {
    ServerTestMode.provide(this, testMode)
    if (MdtStorage.getSetting(TEST_MODE_ENABLED_KEY)?.toBooleanStrictOrNull() == true) {
        testMode.enableFromDatabase()
        applyAllOnline("enable-from-db")
        logger.warning("[危险]服务器测试模式按数据库状态自动启用")
    } else if (tempFile().exists()) {
        logger.warning("[危险]发现遗留测试模式临时文件：${tempFile().absolutePath}；数据库开关未启用，脚本不会自动启用测试模式，可手动启用或删除该文件。")
    }
}

onDisable {
    if (testMode.isEnabled()) {
        testMode.shutdownPreserve()
    }
}

listen<EventType.PlayerJoin> {
    if (!testMode.isEnabled()) return@listen
    testMode.applySession(it.player, "join")
    if (testMode.isTestSession(it.player)) {
        it.player.sendMessage(enabledMessage())
    } else {
        it.player.sendMessage("[yellow][危险]服务器测试模式已启用：请先使用 [gold]/login[] 登录账号，登录后才会获得测试MDC、信任1/资历3与结算×$TEST_REWARD_MULTIPLIER。")
    }
}

command("servertestmode", "[危险]服务器测试模式") {
    aliases = listOf("testmode", "测试模式", "服务器测试模式")
    usage = "[status|on confirm|off confirm|menu]"
    permission = "wayzer.admin.serverTestMode"
    body {
        if (player != null && !player!!.hasPermission("wayzer.admin.serverTestMode")) {
            returnReply("[red]权限不足：需要服务器测试模式管理权限".with())
        }
        val sub = arg.firstOrNull()?.lowercase() ?: "menu"
        when (sub) {
            "menu", "菜单" -> {
                val p = player ?: returnReply(testMode.statusText().with())
                openTestModeMenu(p)
            }
            "status", "状态" -> reply(testMode.statusText().with())
            "on", "enable", "启用", "开启" -> {
                if (arg.getOrNull(1)?.lowercase() != "confirm") {
                    returnReply("[red]危险操作，请使用 /servertestmode on confirm 确认启用，或在游戏内打开菜单二次确认。".with())
                }
                if (enableMode(player?.plainName() ?: "Console")) reply("[green]测试模式已启用".with())
                else reply("[yellow]测试模式已经处于启用状态".with())
            }
            "off", "disable", "关闭", "停用" -> {
                if (arg.getOrNull(1)?.lowercase() != "confirm") {
                    returnReply("[red]危险操作，请使用 /servertestmode off confirm 确认关闭并清理临时数据。".with())
                }
                if (disableMode(player?.plainName() ?: "Console")) reply("[green]测试模式已关闭并清理临时数据".with())
                else reply("[yellow]测试模式当前未启用".with())
            }
            else -> replyUsage()
        }
    }
}

PermissionApi.registerDefault("wayzer.admin.serverTestMode", group = "@admin")
