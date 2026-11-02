@file:Depends("coreLibrary/db", "数据库事务")
@file:Depends("wayzer/mdtDatabase", "MDT数据库")

package wayzer.user

import arc.util.Log
import arc.util.serialization.Jval
import cf.wayzer.placehold.PlaceHoldApi.with
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import wayzer.lib.MdtStorage
import java.io.File
import java.util.UUID

private val requestFile by config.key("config/stats/account-migrate-requests.jsonl", "迁移请求队列")
private val resultFile by config.key("config/stats/account-migrate-results.jsonl", "迁移结果队列")

private data class MigrateResult(val ok: Boolean, val message: String, val accountId: Int, val oldQq: String, val newQq: String)

private fun accountIdOf(player: mindustry.gen.Player): Int? =
    wayzer.lib.PlayerData[player].id.substringAfter("account:", "").toIntOrNull()

private fun resultLine(id: String, r: MigrateResult): String {
    val msg = r.message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    return """{"id":"$id","ok":${r.ok},"message":"$msg","accountId":${r.accountId},"oldQq":"${r.oldQq}","newQq":"${r.newQq}"}"""
}

private fun appendResult(id: String, r: MigrateResult) {
    File(resultFile).parentFile?.mkdirs()
    synchronized(this) {
        File(resultFile).appendText(resultLine(id, r) + "\n", Charsets.UTF_8)
    }
}

/**
 * 在运行中的游戏服事务里改用户名与 QQ binding；不直接打开 H2 文件。
 * 只改 username/binding，account:<id>、MDC、称号、资历、游戏主体全部保留。
 */
private fun migrate(accountId: Int, newQqRaw: String, clearEmail: Boolean): MigrateResult {
    val newQq = newQqRaw.trim()
    if (!Regex("^[1-9][0-9]{4,11}$").matches(newQq)) {
        return MigrateResult(false, "新 QQ 必须是 5-12 位数字", accountId, "", newQq)
    }
    val account = MdtStorage.findAccountById(accountId)
        ?: return MigrateResult(false, "account:$accountId 不存在", accountId, "", newQq)
    val oldQq = account.qq
    if (oldQq == newQq) return MigrateResult(true, "用户名已经是该 QQ，无需迁移", accountId, oldQq, newQq)
    if (MdtStorage.findAccountByQq(newQq) != null) {
        return MigrateResult(false, "目标 QQ 已存在账号，未做合并", accountId, oldQq, newQq)
    }

    val boundEmail = MdtStorage.getSetting("email.bind.$accountId")
    if (!clearEmail && boundEmail != null && boundEmail.substringBefore('@') != newQq) {
        return MigrateResult(false, "账号已绑定邮箱 $boundEmail，与 $newQq 不一致；先 /emailbind $accountId reset 或工具加 --clear-email", accountId, oldQq, newQq)
    }

    var error: String? = null
    transaction {
        try {
            val eid = EntityID(accountId, MdtStorage.Accounts)
            if (MdtStorage.Accounts.selectAll().where { MdtStorage.Accounts.username eq newQq }.any()) {
                error = "目标 QQ 已存在账号"
                return@transaction
            }
            MdtStorage.Accounts.update({ MdtStorage.Accounts.id eq eid }) {
                it[MdtStorage.Accounts.username] = newQq
            }
            MdtStorage.AccountBindings.update({
                (MdtStorage.AccountBindings.account eq eid) and (MdtStorage.AccountBindings.type eq "QQ")
            }) {
                it[MdtStorage.AccountBindings.identifier] = newQq
            }
        } catch (e: Throwable) {
            error = "数据库更新失败：${e.message}"
        }
    }
    if (error != null) return MigrateResult(false, error!!, accountId, oldQq, newQq)

    MdtStorage.setSetting("qq.index.$oldQq", null)
    MdtStorage.setSetting("qq.index.$newQq", accountId.toString())
    if (clearEmail && boundEmail != null) {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(boundEmail.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        MdtStorage.setSetting("email.bind.$accountId", null)
        MdtStorage.setSetting("email.index.$hash", null)
    }
    Log.info("[accountMigrate] account:$accountId $oldQq -> $newQq clearEmail=$clearEmail")
    return MigrateResult(true, "迁移成功：$oldQq -> $newQq；请让玩家用新 QQ 登录并 /bindemail", accountId, oldQq, newQq)
}

private fun processRequests() {
    val file = File(requestFile)
    if (!file.exists()) return
    val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return
    for (line in lines) {
        if (line.isBlank()) continue
        val id = runCatching { Jval.read(line).getString("id", "") }.getOrNull().orEmpty()
        if (id.isBlank()) continue
        // 结果已存在则跳过
        val result = File(resultFile)
        if (result.exists() && result.readLines(Charsets.UTF_8).any { it.contains("\"id\":\"$id\"") }) continue

        val json = runCatching { Jval.read(line) }.getOrNull() ?: continue
        val action = json.getString("action", "rename")
        val accountId = json.getString("accountId", "").toIntOrNull() ?: continue
        val newQq = json.getString("newQq", "")
        val clearEmail = json.getBool("clearEmail", false)
        val r = when (action) {
            "check" -> {
                val acc = MdtStorage.findAccountById(accountId)
                if (acc == null) MigrateResult(false, "account:$accountId 不存在", accountId, "", newQq)
                else MigrateResult(true, "account:$accountId 当前用户名=${acc.qq}", accountId, acc.qq, newQq)
            }
            else -> migrate(accountId, newQq, clearEmail)
        }
        appendResult(id, r)
    }
}

onEnable {
    command("migrateaccount", "迁移账号用户名（仅改 username/QQ binding）") {
        usage = "<accountId> <newQq> [--clear-email]"
        permission = "wayzer.admin.account"
        body {
            val id = arg.getOrNull(0)?.toIntOrNull() ?: returnReply("[red]用法：/migrateaccount <accountId> <newQq> [--clear-email]".with())
            val newQq = arg.getOrNull(1) ?: returnReply("[red]缺少新 QQ".with())
            val clear = arg.getOrNull(2) == "--clear-email"
            val r = migrate(id, newQq, clear)
            returnReply(((if (r.ok) "[green]" else "[red]") + r.message).with())
        }
    }
    launch(Dispatchers.IO) {
        while (true) {
            runCatching { processRequests() }.onFailure { Log.err("[accountMigrate] ${it.message}") }
            delay(1000L)
        }
    }
}
