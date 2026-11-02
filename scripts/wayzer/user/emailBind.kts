@file:Depends("wayzer/mdtDatabase", "MDT数据库")
@file:Depends("wayzer/user/trustLevel", "信任等级")
@file:Depends("wayzer/user/seniorityLevel", "资历等级（仅清理旧版锁定快照）")
@file:Depends("wayzer/user/trustPoint", "MDC")
@file:Depends("coreMindustry/utilTextInput", "邮箱/验证码输入")

package wayzer.user

import arc.util.Log
import cf.wayzer.placehold.PlaceHoldApi.with
import coreMindustry.UtilTextInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mindustry.game.EventType
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val trustLevel = contextScript<wayzer.user.TrustLevel>()
private val seniorityLevel = contextScript<wayzer.user.SeniorityLevel>()
private val trustPoint = contextScript<wayzer.user.TrustPoint>()
private val textInput = contextScript<UtilTextInput>()

private val emailServiceUrl by config.key("http://127.0.0.1:40150/send", "邮箱验证码服务地址")
private val emailServiceToken by config.key("", "邮箱验证码服务 internalToken")
private val emailCodeTtlMinutes by config.key(10, "验证码有效期（分钟）")
private val emailInputTimeoutSeconds by config.key(180, "邮箱/验证码输入超时（秒）")
private val ipDailyRegisterLimit by config.key(3, "每 IP 每天成功注册上限")
private val bindRewardOld by config.key(1000, "老账号补绑奖励 MDC")
private val bindRewardNew by config.key(300, "新账号绑定奖励 MDC")
private val codeSendCooldownSeconds by config.key(60, "验证码发送冷却（秒，按账号/IP）")
private val codeSendDailyLimit by config.key(10, "每身份每日验证码发送上限")

private data class PendingCode(
    val email: String,
    val codeHash: String,
    val expiresAt: Long,
    var attempts: Int = 0,
)

private val pending = ConcurrentHashMap<String, PendingCode>()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun normalizeEmail(email: String): String = email.trim().lowercase()

private fun isQqEmail(email: String): Boolean =
    Regex("^[1-9][0-9]{4,11}@qq\\.com$").matches(email)

private fun playerIp(player: Player): String =
    (player.con?.address ?: player.ip()).substringBefore(':')

private fun accountIdOf(player: Player): Int? =
    PlayerData[player].id.substringAfter("account:", "").toIntOrNull()

private fun isAdminTier(uid: String): Boolean =
    with(trustLevel) { trustLevelOrder(getTrustLevelCode(uid)) >= trustLevelOrder("3++") }

private fun rewardOnce(accountId: Int, amount: Int, label: String) {
    val key = "email.reward.$accountId"
    if (MdtStorage.getSetting(key) == "done") return
    with(trustPoint) { addTrustPoints("account:$accountId", amount, label) }
    MdtStorage.setSetting(key, "done")
}

/** 未绑定邮箱：只把信任等级封顶到 2 级并锁定；资历等级完全不限制（用户 2026-11-02 口径）。 */
private fun lockForUnbound(accountId: Int, uid: String) {
    if (MdtStorage.getSetting("email.bind.$accountId") != null) return
    if (MdtStorage.getSetting("email.lock.$accountId") != null) return
    if (isAdminTier(uid)) return
    val trust = with(trustLevel) { getTrustLevelCode(uid) }
    val trustCapped = with(trustLevel) {
        if (trustLevelOrder(trust) > trustLevelOrder("2")) "2" else trust
    }
    // 兼容旧快照格式 "信任|资历"：新逻辑只保存信任等级，解绑时忽略旧资历部分。
    MdtStorage.setSetting("email.lock.$accountId", trust)
    with(trustLevel) {
        setTrustLevel(uid, trustCapped)
        setTrustLevelLocked(uid, true)
    }
    Log.info("[emailBind] 未绑定邮箱，信任等级封顶为 $trustCapped（原 $trust）uid=$uid")
}

private fun unlockAfterBind(accountId: Int, uid: String) {
    val snapshot = MdtStorage.getSetting("email.lock.$accountId") ?: return
    // 旧版本快照可能是 "trust|seniority"（协作者早期版本会锁资历）；资历等级已不参与限制，
    // 这里只在发现旧快照时顺手恢复/解锁一次，新快照只保存信任等级。
    val parts = snapshot.split('|', limit = 2)
    val trustCode = parts[0].ifBlank { "2" }
    with(trustLevel) {
        setTrustLevel(uid, trustCode)
        setTrustLevelLocked(uid, false)
    }
    if (parts.size == 2) {
        with(seniorityLevel) {
            setSeniorityLevel(uid, parts[1].ifBlank { "2" })
            setSeniorityLevelLocked(uid, false)
        }
    }
    MdtStorage.setSetting("email.lock.$accountId", null)
}

fun isEmailBound(accountId: Int): Boolean =
    MdtStorage.getSetting("email.bind.$accountId") != null

/** 是否 3++/4 级工作人员（未绑定也不降信任等级，入服弹窗也不打扰）。 */
fun isStaffUid(uid: String): Boolean = isAdminTier(uid)

fun onAccountLogin(accountId: Int, uid: String) {
    runCatching {
        if (!isEmailBound(accountId)) lockForUnbound(accountId, uid)
        else unlockAfterBind(accountId, uid)
    }.onFailure {
        Log.err("[emailBind] 登录时同步邮箱绑定/信任状态失败 account=$accountId uid=$uid: ${it.message}")
    }
}

private fun canRegisterFromIp(ip: String): Boolean {
    val key = "email.ip.${sha256(ip)}"
    val today = java.time.LocalDate.now().toString()
    val raw = MdtStorage.getSetting(key) ?: return true
    val parts = raw.split('|', limit = 2)
    if (parts.size != 2 || parts[0] != today) return true
    val count = parts[1].toIntOrNull() ?: 0
    return count < ipDailyRegisterLimit
}

private fun markRegisterFromIp(ip: String) {
    val key = "email.ip.${sha256(ip)}"
    val today = java.time.LocalDate.now().toString()
    val raw = MdtStorage.getSetting(key)
    val parts = raw?.split('|', limit = 2).orEmpty()
    val count = if (parts.size == 2 && parts[0] == today) (parts[1].toIntOrNull() ?: 0) else 0
    MdtStorage.setSetting(key, "$today|${count + 1}")
}

private fun mapEmailServiceError(status: Int, text: String): String {
    if (status == 0) return "邮件服务暂不可用，请稍后再试"
    val error = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    return when (error) {
        "SERVICE_DAILY_LIMIT", "QUOTA_EXCEEDED" -> "注册邮件已满，请隔天再尝试"
        "EMAIL_DAILY_LIMIT" -> "该邮箱今日验证码次数已达上限"
        "EMAIL_COOLDOWN" -> "发送太频繁，请稍后再试"
        "DOMAIN_NOT_ALLOWED", "INVALID_EMAIL" -> "只支持 QQ 邮箱（@qq.com）"
        "INVALID_CODE" -> "验证码格式错误"
        "EMAIL_SERVICE_CONFIG" -> "邮件服务配置异常，请联系管理员"
        else -> if (status == 429) "发送太频繁，请稍后再试" else "邮件发送失败，请稍后再试"
    }
}

private suspend fun sendCode(email: String, code: String): String? {
    if (emailServiceToken.isBlank()) return "邮件服务 token 未配置"
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(emailServiceUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Internal-Token", emailServiceToken)
            val body = """{"email":"$email","code":"$code","expireMinutes":$emailCodeTtlMinutes,"purpose":"bind"}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status == 200) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            status to text
        }.getOrElse { 0 to (it.message ?: "") }
    }
    return if (result.first == 200) null else mapEmailServiceError(result.first, result.second)
}

private suspend fun askEmail(player: Player, title: String): String? {
    val raw = with(textInput) {
        textInput(
            player, title, "请输入 QQ 邮箱（例如 123456@qq.com）。只允许数字@qq.com。",
            lengthLimit = 64, timeoutMillis = emailInputTimeoutSeconds * 1000
        )
    } ?: run {
        player.sendMessage("[yellow]邮箱输入已取消或超时；可重新输入 /bindemail 或 /register 重试".with())
        return null
    }
    val email = normalizeEmail(raw)
    if (!isQqEmail(email)) {
        player.sendMessage("[red]只允许 数字@qq.com，例如 123456@qq.com".with())
        return null
    }
    return email
}

private suspend fun askCode(player: Player): String? {
    val raw = with(textInput) {
        textInput(
            player, "输入验证码", "请输入邮箱收到的 6 位验证码（可等待邮件到达，最多 ${emailInputTimeoutSeconds} 秒）。",
            lengthLimit = 6, isNumeric = true, timeoutMillis = emailInputTimeoutSeconds * 1000
        )
    } ?: run {
        player.sendMessage("[yellow]验证码输入已取消或超时；可重新发送验证码后重试".with())
        return null
    }
    return raw.trim().takeIf { Regex("^\\d{6}$").matches(it) } ?: run {
        player.sendMessage("[red]验证码必须是 6 位数字".with())
        null
    }
}

private fun sendRateKeys(player: Player): List<String> {
    val keys = mutableListOf<String>()
    accountIdOf(player)?.let { keys += "account:$it" }
    keys += "ip:${sha256(playerIp(player))}"
    return keys
}

/** 发送验证码前的账号/IP 限流（邮件服务只按收件邮箱限流，防止换邮箱刷爆额度）。 */
private fun checkSendRateLimit(keys: List<String>): String? {
    val now = System.currentTimeMillis()
    val today = java.time.LocalDate.now().toString()
    for (key in keys) {
        val raw = MdtStorage.getSetting("email.send.$key") ?: continue
        val parts = raw.split('|', limit = 3)
        if (parts.getOrNull(0) != today) continue
        val count = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val last = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        if (now - last < codeSendCooldownSeconds * 1000L) return "发送太频繁，请稍后再试"
        if (count >= codeSendDailyLimit) return "今日验证码发送次数已达上限"
    }
    return null
}

private fun markSendAttempt(keys: List<String>) {
    val now = System.currentTimeMillis()
    val today = java.time.LocalDate.now().toString()
    for (key in keys) {
        val raw = MdtStorage.getSetting("email.send.$key")
        val parts = raw?.split('|', limit = 3).orEmpty()
        val count = if (parts.getOrNull(0) == today) (parts.getOrNull(1)?.toIntOrNull() ?: 0) else 0
        MdtStorage.setSetting("email.send.$key", "$today|${count + 1}|$now")
    }
}

private suspend fun verifyEmailCode(player: Player, email: String, purpose: String): Boolean {
    val key = player.uuid()
    val rateKeys = sendRateKeys(player)
    checkSendRateLimit(rateKeys)?.let {
        player.sendMessage("[red]$it".with())
        return false
    }
    val code = (100000..999999).random().toString()
    val error = sendCode(email, code)
    if (error != null) {
        player.sendMessage("[red]$error".with())
        return false
    }
    markSendAttempt(rateKeys)
    val expiresAt = System.currentTimeMillis() + emailCodeTtlMinutes * 60_000L
    pending[key] = PendingCode(email, sha256("$email|$code"), expiresAt)
    player.sendMessage("[green]验证码已发送到 $email，请在 ${emailCodeTtlMinutes} 分钟内输入。".with())
    val input = askCode(player) ?: run {
        pending.remove(key)
        player.sendMessage("[yellow]已取消邮箱验证".with())
        return false
    }
    val rec = pending[key] ?: return false
    if (System.currentTimeMillis() > rec.expiresAt) {
        pending.remove(key)
        player.sendMessage("[red]验证码已过期，请重新发送".with())
        return false
    }
    if (rec.attempts >= 5) {
        pending.remove(key)
        player.sendMessage("[red]尝试次数过多，请重新发送".with())
        return false
    }
    if (rec.codeHash != sha256("$email|$input")) {
        rec.attempts++
        player.sendMessage("[red]验证码错误".with())
        return false
    }
    pending.remove(key)
    return true
}

/** 绑定邮箱；返回 null 表示成功，否则错误文案。 */
fun bindEmail(accountId: Int, emailRaw: String, qqRaw: String, isNewAccount: Boolean): String? {
    val email = normalizeEmail(emailRaw)
    val qq = qqRaw.trim()
    if (!isQqEmail(email)) return "只允许 数字@qq.com"
    if (email.substringBefore('@') != qq) return "邮箱前缀必须与 QQ 号一致"
    val emailHash = sha256(email)
    val emailOwner = MdtStorage.getSetting("email.index.$emailHash")?.toIntOrNull()
    if (emailOwner != null && emailOwner != accountId) return "该 QQ 邮箱已绑定其他账号"
    val qqOwner = MdtStorage.getSetting("qq.index.$qq")?.toIntOrNull()
    if (qqOwner != null && qqOwner != accountId) return "该 QQ 号已绑定其他账号"

    MdtStorage.setSettings(
        mapOf(
            "email.bind.$accountId" to email,
            "email.index.$emailHash" to accountId.toString(),
            "qq.index.$qq" to accountId.toString(),
        )
    )
    val uid = "account:$accountId"
    unlockAfterBind(accountId, uid)
    // 奖励失败不能影响绑定结果（兜底：只记日志，玩家稍后可找管理员补）。
    runCatching {
        rewardOnce(
            accountId,
            if (isNewAccount) bindRewardNew else bindRewardOld,
            if (isNewAccount) "新玩家邮箱绑定奖励" else "老玩家补绑奖励"
        )
    }.onFailure { Log.err("[emailBind] 发放绑定奖励失败 account=$accountId: ${it.message}") }
    return null
}

/** 注册流程：验证邮箱并返回 (QQ, 邮箱)。 */
suspend fun verifyEmailForRegistration(player: Player): Pair<String, String>? {
    if (!canRegisterFromIp(playerIp(player))) {
        player.sendMessage("[red]该 IP 今日注册次数已达上限".with())
        return null
    }
    val email = askEmail(player, "注册账号") ?: return null
    val qq = email.substringBefore('@')
    if (MdtStorage.findAccountByQq(qq) != null) {
        player.sendMessage("[red]该 QQ 已注册，请使用 /login 登录".with())
        return null
    }
    if (MdtStorage.getSetting("email.index.${sha256(email)}") != null) {
        player.sendMessage("[red]该邮箱已绑定其他账号".with())
        return null
    }
    if (!verifyEmailCode(player, email, "register")) return null
    markRegisterFromIp(playerIp(player))
    return qq to email
}

private suspend fun bindExisting(player: Player) {
    val accountId = accountIdOf(player) ?: run {
        player.sendMessage("[red]请先登录账号".with())
        return
    }
    if (MdtStorage.getSetting("email.bind.$accountId") != null) {
        player.sendMessage("[yellow]该账号已经绑定邮箱".with())
        return
    }
    val account = MdtStorage.findAccountById(accountId)
    val currentQq = account?.qq?.takeIf { Regex("^\\d{5,12}$").matches(it) }.orEmpty()
    val email = askEmail(player, "绑定 QQ 邮箱") ?: return
    val qq = email.substringBefore('@')
    if (currentQq.isNotEmpty() && currentQq != qq) {
        player.sendMessage("[red]邮箱前缀必须与当前账号 QQ 号一致；历史账号迁移请联系管理员".with())
        return
    }
    if (!verifyEmailCode(player, email, "bind")) return
    val error = bindEmail(accountId, email, qq, isNewAccount = false)
    player.sendMessage((if (error == null) "[green]QQ 邮箱绑定成功" else "[red]$error").with())
}

/** 供入服弹窗“绑定邮箱”按钮/其它脚本复用的公开入口。 */
suspend fun bindEmailFlow(player: Player) = bindExisting(player)

command("bindemail", "绑定/补绑 QQ 邮箱（验证码，绑定后信任等级恢复并奖励 1000 MDC）") {
    aliases = listOf("绑定邮箱", "emailbindme")
    usage = "[无需参数，按提示输入 数字@qq.com 与验证码]"
    body {
        val p = player ?: returnReply("[red]仅限游戏内使用".with())
        bindExisting(p)
    }
}

command("emailbind", "管理指令：查询/清除玩家邮箱绑定") {
    usage = "<accountId> [reset]"
    permission = "wayzer.admin.account"
    body {
        val id = arg.getOrNull(0)?.toIntOrNull() ?: returnReply("[red]用法：/emailbind <accountId> [reset]".with())
        val email = MdtStorage.getSetting("email.bind.$id")
        if (email == null) returnReply("[yellow]account:$id 未绑定邮箱".with())
        if (arg.getOrNull(1) == "reset") {
            MdtStorage.setSettings(
                mapOf(
                    "email.bind.$id" to null,
                    "email.index.${sha256(email)}" to null,
                )
            )
            returnReply("[green]已清除 account:$id 的邮箱绑定".with())
        }
        returnReply("[cyan]account:$id 绑定邮箱：[white]$email".with())
    }
}

listen<EventType.PlayerJoin> { event ->
    val p = event.player
    launch(Dispatchers.game) {
        delay(3_000L)
        if (!Groups.player.contains(p) || !PlayerData[p].authed) return@launch
        runCatching {
            accountIdOf(p)?.let { lockForUnbound(it, PlayerData[p].id) }
        }.onFailure { Log.err("[emailBind] 离线/入服同步信任等级失败: ${it.message}") }
    }
}
