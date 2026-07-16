@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("coreMindustry/menu", "账号菜单")
@file:Depends("coreMindustry/utilTextInput", "账号输入框")
@file:Depends("wayzer/user/accountPassword", "账号密码哈希")
@file:Depends("wayzer/user/accountIpGuard", "账号IP防熊")
@file:Depends("wayzer/user/trustLevel", "信任等级")
@file:Depends("wayzer/user/trustPromotion", "信任晋升检测")

package wayzer.user

import coreMindustry.MenuBuilder
import coreLibrary.lib.event.RequestPermissionEvent
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.ServerTestMode
import wayzer.lib.TrustPointChangedEvent
import kotlin.random.Random

private val passwordTool = contextScript<AccountPassword>()
private val textInput = contextScript<coreMindustry.UtilTextInput>()
private val ipGuard = contextScript<AccountIpGuard>()
private val trustLevel = contextScript<TrustLevel>()
private val trustPromotion = contextScript<TrustPromotion>()

private val QQ_MIN_LENGTH = 5
private val QQ_MAX_LENGTH = 12
private val PASSWORD_MAX_LENGTH = 64
private val LOGIN_FAIL_LIMIT = 5
private val LOGIN_BLOCK_MILLIS = 60_000L
private val CAPTCHA_LENGTH = 4
private val REGISTER_CAPTCHA_MIN_ONLINE_MILLIS = 60L * 60L * 1000L

private val minPasswordLength by config.key(6, "账号密码最小长度")
private val captchaExpireMillis by config.key(5 * 60_000L, "注册验证码有效期(ms)")
private val sessionOnlineCacheMaxEntries by config.key(5_000, "本次启动注册在线时长缓存最大离线UUID数")

private data class LoginFailState(
    var count: Int = 0,
    var blockedUntil: Long = 0,
    var lastFailAt: Long = 0,
)

private data class AccountLookupResult(
    val account: MdtStorage.AccountRecord,
    val input: String,
    val targetName: String?,
    val gameUuid: String?,
    val subjectUid: String,
)

private data class CaptchaState(
    val code: String,
    val expiresAt: Long,
)

private val loginFails = mutableMapOf<String, LoginFailState>()
private val loginCaptchas = mutableMapOf<String, CaptchaState>()
private val sessionOnlineMillis = mutableMapOf<String, Long>()
private val sessionJoinAtMillis = mutableMapOf<String, Long>()
private val sessionLastSeenAtMillis = mutableMapOf<String, Long>()
private val ipv4Regex = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

private fun serverTestModeEnabled(): Boolean = ServerTestMode.getOrNull()?.isEnabled() == true

private suspend fun canManageAccount(operator: Player?): Boolean =
    operator == null || operator.hasPermission("wayzer.admin.account")

private fun denyAccountAdmin(operator: Player?): Boolean {
    val message = "[red]权限不足：需要账号管理权限 wayzer.admin.account"
    operator?.sendMessage(message) ?: logger.warning(message)
    return false
}

private fun testModeAccountMessage(): String =
    "[yellow][危险]服务器测试模式已启用：请先登录/注册账号；登录后使用临时测试MDC，非4级默认信任1/资历3。测试期间改密/注销等破坏性账号操作暂时关闭。"

private val registerWarning = """
    请务必加入QQ群并牢记账号密码。
    账号以 QQ 号作为唯一标识。
    如果没有你的 QQ，后续账号清理、误封申诉、找回密码都可能无法处理。
    请不要使用他人的 QQ 注册。
""".trimIndent()

private fun normalizeQq(input: String): String? {
    val qq = input.trim()
    if (!Regex("\\d{$QQ_MIN_LENGTH,$QQ_MAX_LENGTH}").matches(qq)) return null
    return qq
}

private fun passwordError(password: String): String? = when {
    password.length < minPasswordLength -> "密码至少需要 ${minPasswordLength} 位"
    password.length > PASSWORD_MAX_LENGTH -> "密码不能超过 ${PASSWORD_MAX_LENGTH} 位"
    password.any { it.isWhitespace() } -> "密码暂不允许包含空格或换行"
    else -> null
}

private fun normalizeIp(raw: String?): String =
    raw?.let { ipv4Regex.find(it)?.value ?: it.trim().substringBefore('%') }
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"

private fun playerIp(player: Player): String =
    normalizeIp(player.con?.address ?: player.ip())

private fun loginFailKeys(player: Player): List<String> =
    mutableListOf<String>().apply {
        add("uuid:${player.uuid()}")
        val ip = playerIp(player)
        if (ip != "unknown") add("ip:$ip")
    }

private fun pruneLoginFailures(now: Long = System.currentTimeMillis()) {
    loginFails.entries.removeIf { (_, state) ->
        state.blockedUntil <= now && now - state.lastFailAt > 10 * 60_000L
    }
}

private fun sessionKey(player: Player): String = player.uuid()

private fun currentSessionOnlineMillis(player: Player): Long {
    val key = sessionKey(player)
    val base = sessionOnlineMillis[key] ?: 0L
    val joinedAt = sessionJoinAtMillis[key] ?: return base
    return base + (System.currentTimeMillis() - joinedAt).coerceAtLeast(0L)
}

private fun pruneSessionOnlineCache(now: Long = System.currentTimeMillis()) {
    val limit = sessionOnlineCacheMaxEntries.coerceAtLeast(100)
    if (sessionOnlineMillis.size <= limit) return
    val online = sessionJoinAtMillis.keys
    val removable = sessionOnlineMillis.keys
        .asSequence()
        .filter { it !in online }
        .sortedBy { sessionLastSeenAtMillis[it] ?: 0L }
        .toList()
    var removeCount = sessionOnlineMillis.size - limit
    for (key in removable) {
        if (removeCount <= 0) break
        sessionOnlineMillis.remove(key)
        sessionLastSeenAtMillis.remove(key)
        loginCaptchas.remove(key)
        removeCount--
    }
    if (removeCount > 0) {
        // 理论上只会在在线人数超过限制时出现；不移除在线玩家，避免破坏注册在线时长统计。
        logger.warning("账号注册在线时长缓存超过限制且在线玩家过多，当前=${sessionOnlineMillis.size}, limit=$limit, now=$now")
    }
}

private fun formatSessionOnlineDuration(millis: Long, roundUp: Boolean = false): String {
    val safe = millis.coerceAtLeast(0L)
    val seconds = if (roundUp) ((safe + 999L) / 1000L).coerceAtLeast(1L) else safe / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val sec = seconds % 60L
    val parts = mutableListOf<String>()
    if (hours > 0) parts += "${hours}小时"
    if (minutes > 0) parts += "${minutes}分"
    if (sec > 0 || parts.isEmpty()) parts += "${sec}秒"
    return parts.joinToString("")
}

private fun registerCaptchaOnlineStatus(player: Player, prefix: String = "[yellow]注册验证码获取条件："): String {
    val online = currentSessionOnlineMillis(player).coerceAtLeast(0L)
    val required = REGISTER_CAPTCHA_MIN_ONLINE_MILLIS
    val left = (required - online).coerceAtLeast(0L)
    val onlineText = formatSessionOnlineDuration(online)
    val progress = if (left > 0) {
        val leftText = formatSessionOnlineDuration(left, roundUp = true)
        "[gray]你当前累计在线：[white]$onlineText[gray] / [white]1小时[gray]；还需要继续在线：[gold]$leftText[gray]。"
    } else {
        "[green]你已满足注册验证码在线条件：[white]$onlineText[green] / 1小时；现在可以输入 [gold]/captcha[green] 获取验证码。"
    }
    return "$prefix 本次服务器启动内累计在线满 [gold]1小时[yellow]。\n" +
            "$progress\n" +
            "[gray]该时长只在本次服务端启动内统计；已有账号可直接使用 [gold]/login[] 登录，登录不需要验证码。"
}

private fun checkLoginCooldown(player: Player): Boolean {
    val now = System.currentTimeMillis()
    pruneLoginFailures(now)
    loginFailKeys(player).forEach { key ->
        val state = loginFails[key] ?: return@forEach
        if (state.blockedUntil > now) {
            val left = ((state.blockedUntil - now) / 1000).coerceAtLeast(1)
            player.sendMessage("[red]登录失败次数过多，请 ${left}s 后再试")
            return false
        }
        if (now - state.lastFailAt > 10 * 60_000L) {
            loginFails.remove(key)
        }
    }
    return true
}

private fun recordLoginFailure(player: Player) {
    val now = System.currentTimeMillis()
    pruneLoginFailures(now)
    var blocked = false
    loginFailKeys(player).forEach { key ->
        val state = loginFails.getOrPut(key) { LoginFailState() }
        if (now - state.lastFailAt > 10 * 60_000L) state.count = 0
        state.count += 1
        state.lastFailAt = now
        if (state.count >= LOGIN_FAIL_LIMIT) {
            state.blockedUntil = now + LOGIN_BLOCK_MILLIS
            state.count = 0
            blocked = true
        }
    }
    if (blocked) {
        player.sendMessage("[red]登录失败次数过多，账号登录已临时冷却 60 秒")
    }
}

private fun clearLoginFailure(player: Player) {
    loginFailKeys(player).forEach { loginFails.remove(it) }
}

private fun captchaKey(player: Player): String = player.uuid()

private fun issueCaptcha(player: Player): String {
    val code = Random.nextInt(0, 10_000).toString().padStart(CAPTCHA_LENGTH, '0')
    loginCaptchas[captchaKey(player)] = CaptchaState(
        code = code,
        expiresAt = System.currentTimeMillis() + captchaExpireMillis.coerceAtLeast(60_000L),
    )
    return code
}

private fun registerCaptchaOnlineError(player: Player): String? {
    val online = currentSessionOnlineMillis(player)
    val required = REGISTER_CAPTCHA_MIN_ONLINE_MILLIS
    if (online >= required) return null
    return "[yellow]为防止批量注册，暂不能获取注册验证码。\n" +
            registerCaptchaOnlineStatus(player)
}

private fun captchaLeftSeconds(player: Player): Long {
    val state = loginCaptchas[captchaKey(player)] ?: return 0L
    return ((state.expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
}

private fun hasValidCaptcha(player: Player): Boolean {
    val state = loginCaptchas[captchaKey(player)] ?: return false
    if (state.expiresAt <= System.currentTimeMillis()) {
        loginCaptchas.remove(captchaKey(player))
        return false
    }
    return true
}

private suspend fun verifyCaptchaFlow(player: Player, title: String): Boolean {
    if (!hasValidCaptcha(player)) {
        player.sendMessage(
            "[yellow]请先输入 [gold]/captcha[] 获取4位注册验证码，再继续注册。\n" +
                    registerCaptchaOnlineStatus(player, "[yellow]如果获取失败，请查看还需在线多久：")
        )
        return false
    }
    val state = loginCaptchas[captchaKey(player)] ?: return false
    val leftSeconds = captchaLeftSeconds(player)
    val lengthLimit = CAPTCHA_LENGTH
    val input = with(textInput) {
        textInput(
            player,
            "$title - 验证码",
            "请输入你刚才通过 /captcha 获取的4位验证码。\n验证码剩余：${leftSeconds}秒。",
            lengthLimit = lengthLimit,
            isNumeric = true,
            timeoutMillis = 60_000,
        )
    }?.trim() ?: return false

    return if (input == state.code && state.expiresAt > System.currentTimeMillis()) {
        loginCaptchas.remove(captchaKey(player))
        true
    } else {
        loginCaptchas.remove(captchaKey(player))
        player.sendMessage("[red]验证码错误或已过期，请重新输入 [gold]/captcha[] 获取新注册验证码。")
        false
    }
}

private fun currentAccount(player: Player): MdtStorage.AccountRecord? {
    val uid = ServerTestMode.getOrNull()
        ?.takeIf { it.isEnabled() }
        ?.formalUid(player)
        ?: PlayerData[player].id
    if (uid.startsWith("account:")) {
        uid.substringAfter("account:").toIntOrNull()?.let { id ->
            MdtStorage.findAccountById(id)?.let { return it }
        }
    }
    return MdtStorage.getAccountForGameUuid(player.uuid())
}

private fun resolveAccountRef(input: String): AccountLookupResult? {
    val text = input.trim()
    if (text.isEmpty()) return null

    normalizeQq(text)?.let { qq ->
        MdtStorage.findAccountByQq(qq)?.let { account ->
            return AccountLookupResult(
                account = account,
                input = text,
                targetName = null,
                gameUuid = null,
                subjectUid = MdtStorage.accountSubjectUid(account.id),
            )
        }
    }

    PlayerData.findByShortId(text)?.let { data ->
        val account = if (data.id.startsWith("account:")) {
            data.id.substringAfter("account:").toIntOrNull()?.let { MdtStorage.findAccountById(it) }
        } else null
            ?: MdtStorage.getAccountForGameUuid(data.uuid)
        if (account != null) {
            return AccountLookupResult(
                account = account,
                input = text,
                targetName = data.name,
                gameUuid = data.uuid,
                subjectUid = MdtStorage.accountSubjectUid(account.id),
            )
        }
    }

    if (text.startsWith("account:")) {
        text.substringAfter("account:").toIntOrNull()?.let { id ->
            MdtStorage.findAccountById(id)?.let { account ->
                return AccountLookupResult(account, text, null, null, text)
            }
        }
    }

    MdtStorage.getAccountForGameUuid(text)?.let { account ->
        return AccountLookupResult(
            account = account,
            input = text,
            targetName = null,
            gameUuid = text,
            subjectUid = MdtStorage.accountSubjectUid(account.id),
        )
    }

    MdtStorage.getAccountForSubjectUid(text)?.let { account ->
        return AccountLookupResult(
            account = account,
            input = text,
            targetName = null,
            gameUuid = null,
            subjectUid = text,
        )
    }

    return null
}

private fun accountLookupText(result: AccountLookupResult): String = """
    |[cyan]查询输入：[white]${result.input}
    |[cyan]玩家名：[white]${result.targetName ?: "未知/离线"}
    |[cyan]游戏UUID：[white]${result.gameUuid ?: "未知"}
    |[cyan]主体UID：[white]${result.subjectUid}
    |[cyan]QQ账号：[white]${result.account.qq}
    |[cyan]账号ID：[white]${result.account.id}
    |[cyan]账号状态：[white]${result.account.status}
""".trimMargin()

private fun onlinePlayerBySubject(subjectUid: String, except: Player? = null): Player? =
    Groups.player.find { it !== except && PlayerData[it].id == subjectUid }

private fun ensureAccountCanLoginHere(player: Player, account: MdtStorage.AccountRecord): Boolean {
    if (account.status != "normal") {
        player.sendMessage("[red]此账号状态异常，无法登录，请联系管理")
        return false
    }
    val linked = MdtStorage.getAccountForGameUuid(player.uuid())
    if (linked != null && linked.id != account.id) {
        player.sendMessage("[red]此客户端已绑定到另一个账号，不能直接切换账号；如需处理请联系管理员")
        return false
    }
    val subjectUid = MdtStorage.accountSubjectUid(account.id)
    val online = onlinePlayerBySubject(subjectUid, player)
    if (online != null) {
        player.sendMessage("[red]该账号当前已在线：[white]${online.name}[]，不能重复登录")
        return false
    }
    return true
}

private fun finishLogin(
    player: Player,
    account: MdtStorage.AccountRecord,
    action: String,
    migrateGuestMdcFromUid: String? = null,
): Boolean {
    if (!ensureAccountCanLoginHere(player, account)) return false
    if (!with(ipGuard) { allowAccountLogin(player, account) }) return false

    val data = PlayerData[player]
    val oldLevel = with(trustLevel) { getTrustLevelCode(data.id, player) }
    val subjectUid = MdtStorage.recordAccountLogin(
        player.uuid(),
        account.id,
        player.con.address,
        player.usid(),
        player.plainName(),
    )
    data.addId(subjectUid, asPrimary = true)
    with(ipGuard) { onAccountAuthed(player) }
    ServerTestMode.getOrNull()?.takeIf { it.isEnabled() }?.applySession(player, "login")
    val migratedMdc = migrateGuestMdcFromUid
        ?.takeIf { !serverTestModeEnabled() && it != subjectUid }
        ?.let { guestUid ->
            runCatching { MdtStorage.migrateTrustPoints(guestUid, subjectUid) }
                .onFailure { e -> logger.warning("注册后迁移游客MDC失败: guest=$guestUid account=$subjectUid error=${e.message}") }
                .getOrNull()
        }
    val effectiveUid = PlayerData[player].id
    val newLevel = with(trustLevel) { getTrustLevelCode(effectiveUid, player) }
    if (oldLevel != newLevel) {
        with(trustLevel) { emitTrustLevelChanged(effectiveUid, oldLevel, newLevel) }
    }
    if (!serverTestModeEnabled()) {
        with(trustPromotion) { checkTrustLevel(subjectUid) }
    }
    player.sendMessage(
        if (serverTestModeEnabled()) {
            "[green]${action}成功，已登录 QQ 账号：[white]${account.qq}[]；[yellow]测试模式下已切入临时测试身份。"
        } else {
            "[green]${action}成功，请退出重进改变登录态，已登录 QQ 账号：[white]${account.qq}[]"
        }
    )
    if (migratedMdc?.changed == true) {
        val changedUids = mutableSetOf(subjectUid)
        migrateGuestMdcFromUid?.let { changedUids += it }
        launch { TrustPointChangedEvent(changedUids).emitAsync() }
        player.sendMessage(
            "[green]已保留注册前游客MDC：当前 +[white]${migratedMdc.migratedCurrent}[green]，累计 +[white]${migratedMdc.migratedTotal}[green]。" +
                    " 当前账号余额：[gold]${migratedMdc.targetCurrent} MDC"
        )
    }
    clearLoginFailure(player)
    return true
}

private suspend fun askQq(player: Player, title: String, message: String = "请输入 QQ 号"): String? {
    val qqMaxLength = QQ_MAX_LENGTH
    val input = with(textInput) {
        textInput(player, title, message, lengthLimit = qqMaxLength, isNumeric = true, timeoutMillis = 60_000)
    } ?: return null
    return normalizeQq(input).also {
        if (it == null) player.sendMessage("[red]QQ号格式不正确，需要 ${QQ_MIN_LENGTH}-${QQ_MAX_LENGTH} 位数字")
    }
}

private suspend fun askPassword(player: Player, title: String, message: String): String? {
    val passwordMaxLength = PASSWORD_MAX_LENGTH
    return with(textInput) {
        textInput(player, title, message, lengthLimit = passwordMaxLength, timeoutMillis = 60_000)
    }?.trim()?.takeIf { it.isNotEmpty() }
}

private suspend fun askNewPasswordTwice(player: Player, title: String): String? {
    val password = askPassword(
        player,
        title,
        "请输入新密码。\n要求：至少 ${minPasswordLength} 位，最多 ${PASSWORD_MAX_LENGTH} 位，不含空格。\n不要把密码发到聊天栏。",
    ) ?: return null
    passwordError(password)?.let {
        player.sendMessage("[red]$it")
        return null
    }
    val confirm = askPassword(player, "$title - 确认", "请再次输入新密码。") ?: return null
    if (password != confirm) {
        player.sendMessage("[red]两次输入的密码不一致，已取消")
        return null
    }
    return password
}

suspend fun openAccountMenu(player: Player) {
    val authed = PlayerData[player].authed
    val account = if (authed) currentAccount(player) else null
    val captchaStatus = if (!authed) "\n${registerCaptchaOnlineStatus(player, "[gray]注册验证码状态：")}" else ""
    val testModeText = if (serverTestModeEnabled()) {
        "\n[red][危险]服务器测试模式已启用：[yellow]登录后使用临时测试MDC；非4级默认信任1/资历3；改密/注销暂时关闭。"
    } else ""
    MenuBuilder<Unit>("MDT账号系统") {
        msg = """
            |[cyan]当前状态：[white]${if (authed) "已登录" else "未登录"}
            |[cyan]当前账号：[white]${account?.qq ?: "无"}
            |[gray]同一客户端通常会自动登录；默认未登录玩家仍可游玩。
            |[gray]登录账号不需要验证码；注册前需本次启动累计在线满1小时，并输入 [gold]/captcha[] 获取4位注册验证码。$captchaStatus$testModeText
            |
            |[yellow]注册提示：
            |$registerWarning
        """.trimMargin()
        if (!authed) {
            option("登录账号") { loginFlow(player) }
            option("注册账号") { registerFlow(player) }
        } else {
            option("修改密码") { changePasswordFlow(player) }
            option("注销账号") { deleteOwnAccountFlow(player) }
        }
        newRow()
        option("关闭") { }
    }.sendTo(player, 60_000)
}

suspend fun registerFlow(player: Player) {
    if (PlayerData[player].authed) {
        player.sendMessage("[yellow]你已经登录账号，无需注册")
        return
    }
    val guestUid = PlayerData[player].id
    MdtStorage.getAccountForGameUuid(player.uuid())?.let {
        player.sendMessage("[yellow]此客户端已注册/绑定过 QQ 账号：[white]${it.qq}[]，请使用 /login 登录")
        return
    }
    if (!with(ipGuard) { allowAccountRegister(player) }) return
    if (!verifyCaptchaFlow(player, "注册账号")) return

    val qq = askQq(player, "注册账号", "$registerWarning\n\n请输入要注册的 QQ 号：") ?: return
    if (MdtStorage.findAccountByQq(qq) != null) {
        player.sendMessage("[red]此 QQ 已注册，请使用 /login 登录；如忘记密码请联系管理员")
        return
    }
    val password = askNewPasswordTwice(player, "注册账号") ?: return
    val account = MdtStorage.createAccount(qq, with(passwordTool) { hashPassword(password) })
    if (account == null) {
        player.sendMessage("[red]注册失败：此 QQ 可能已被注册")
        return
    }
    finishLogin(player, account, "注册", migrateGuestMdcFromUid = guestUid)
}

suspend fun loginFlow(player: Player) {
    if (PlayerData[player].authed) {
        player.sendMessage("[yellow]你已经登录账号")
        return
    }
    if (!checkLoginCooldown(player)) return

    val qq = askQq(player, "登录账号") ?: return
    val password = askPassword(player, "登录账号", "请输入账号密码。\n不要把密码或账号发到聊天栏(废话)。") ?: return
    val account = MdtStorage.findAccountByQq(qq)
    if (account == null || !with(passwordTool) { verifyPassword(password, account.passwordHash) }) {
        recordLoginFailure(player)
        player.sendMessage("[red]账号或密码错误")
        return
    }
    finishLogin(player, account, "登录")
}

suspend fun changePasswordFlow(player: Player) {
    if (serverTestModeEnabled()) {
        player.sendMessage(testModeAccountMessage())
        return
    }
    val account = currentAccount(player)
    if (!PlayerData[player].authed || account == null) {
        player.sendMessage("[red]请先登录账号")
        return
    }
    val oldPassword = askPassword(player, "修改密码", "请输入当前密码。") ?: return
    if (!with(passwordTool) { verifyPassword(oldPassword, account.passwordHash) }) {
        player.sendMessage("[red]当前密码错误")
        return
    }
    val newPassword = askNewPasswordTwice(player, "修改密码") ?: return
    MdtStorage.updateAccountPassword(account.id, with(passwordTool) { hashPassword(newPassword) })
    player.sendMessage("[green]密码修改成功，请牢记新密码")
}

private suspend fun confirmAdminPasswordReset(admin: Player, qq: String, passwordLength: Int): Boolean {
    var confirmed = false
    MenuBuilder<Unit>("确认重置密码") {
        msg = """
            |[red]确认要重置此账号密码吗？
            |[cyan]QQ账号：[white]$qq
            |[cyan]新密码长度：[white]$passwordLength
            |
            |[yellow]密码将由脚本内部加密保存。
        """.trimMargin()
        option("确认重置") { confirmed = true }
        option("取消") { }
    }.sendTo(admin, 60_000)
    return confirmed
}

private suspend fun adminResetPassword(admin: Player?, qq: String, newPassword: String): Boolean {
    if (!canManageAccount(admin)) return denyAccountAdmin(admin)
    passwordError(newPassword)?.let {
        admin?.sendMessage("[red]$it") ?: logger.warning(it)
        return false
    }
    val account = MdtStorage.findAccountByQq(qq)
    if (account == null) {
        admin?.sendMessage("[red]账号不存在：$qq") ?: logger.warning("账号不存在：$qq")
        return false
    }
    if (admin != null && !confirmAdminPasswordReset(admin, qq, newPassword.length)) {
        admin.sendMessage("[yellow]已取消重置密码")
        return false
    }
    MdtStorage.updateAccountPassword(account.id, with(passwordTool) { hashPassword(newPassword) })
    admin?.sendMessage("[green]已重置 QQ账号 [white]$qq[green] 的密码") ?: logger.info("已重置 QQ账号 $qq 的密码")
    return true
}

private suspend fun confirmAccountDeletion(operator: Player, qq: String, title: String = "确认删除账号"): Boolean {
    var confirmed = false
    MenuBuilder<Unit>(title) {
        msg = """
            |[red]此操作会删除账号登录记录、解除设备绑定，并清空该账号相关业务数据。
            |[cyan]QQ账号：[white]$qq
            |
            |[yellow]将一并删除该账号主体及已绑定游戏UUID主体下的MDC、称号、赞踩、认可、成就、技能、随机形态、禁言、帖子/评论、红包记录等数据。
        """.trimMargin()
        option("确认删除") { confirmed = true }
        option("取消") { }
    }.sendTo(operator, 60_000)
    return confirmed
}

private fun logoutDeletedAccount(accountId: Int, qq: String, reason: String) {
    val subjectUid = MdtStorage.accountSubjectUid(accountId)
    Groups.player.forEach { online ->
        val data = PlayerData[online]
        if (data.id == subjectUid || subjectUid in data.ids) {
            val oldLevel = with(trustLevel) { getTrustLevelCode(data.id, online) }
            data.removeId(subjectUid)
            val newLevel = with(trustLevel) { getTrustLevelCode(data.id, online) }
            if (oldLevel != newLevel) {
                with(trustLevel) { emitTrustLevelChanged(data.id, oldLevel, newLevel) }
            }
            with(trustPromotion) { checkTrustLevel(data.id) }
            online.sendMessage("[yellow]QQ账号 [white]$qq[yellow] 已被${reason}，当前会话已退出登录")
        }
    }
}

private suspend fun adminDeleteAccount(operator: Player?, target: String, confirmedByConsole: Boolean = false): Boolean {
    if (!canManageAccount(operator)) return denyAccountAdmin(operator)
    val lookup = resolveAccountRef(target)
    if (lookup == null) {
        operator?.sendMessage("[red]未找到账号：$target，可使用 QQ号、玩家3位ID、完整UUID 或 account:<id>") ?: logger.warning("未找到账号：$target")
        return false
    }
    val account = lookup.account
    if (operator != null && !confirmAccountDeletion(operator, account.qq, "确认管理删除账号")) {
        operator.sendMessage("[yellow]已取消删除账号")
        return false
    }
    if (operator == null && !confirmedByConsole) {
        logger.warning("控制台用法：deleteaccount <QQ账号/玩家UUID/account:id> confirm")
        return false
    }
    val deleted = MdtStorage.deleteAccount(account.id)
    if (!deleted) {
        operator?.sendMessage("[red]删除账号失败：${account.qq}") ?: logger.warning("删除账号失败：${account.qq}")
        return false
    }
    logoutDeletedAccount(account.id, account.qq, "删除")
    operator?.sendMessage("[green]已删除 QQ账号：[white]${account.qq}\n[gray]来源：${lookup.input}") ?: logger.info("已删除 QQ账号 ${account.qq} 来源 ${lookup.input}")
    return true
}

suspend fun deleteOwnAccountFlow(player: Player) {
    if (serverTestModeEnabled()) {
        player.sendMessage(testModeAccountMessage())
        return
    }
    val account = currentAccount(player)
    if (!PlayerData[player].authed || account == null) {
        player.sendMessage("[red]请先登录账号")
        return
    }
    val password = askPassword(player, "注销账号", "请输入当前密码以确认身份。\n此操作会删除账号登录记录并退出登录。") ?: return
    if (!with(passwordTool) { verifyPassword(password, account.passwordHash) }) {
        player.sendMessage("[red]当前密码错误，已取消注销")
        return
    }
    if (!confirmAccountDeletion(player, account.qq, "确认注销账号")) {
        player.sendMessage("[yellow]已取消注销账号")
        return
    }
    val deleted = MdtStorage.deleteAccount(account.id)
    if (!deleted) {
        player.sendMessage("[red]注销账号失败，请联系管理员")
        return
    }
    logoutDeletedAccount(account.id, account.qq, "注销")
}

listenTo<ConnectAsyncEvent> {
    val data = PlayerData.forAuth(packet)
    if (data.authed) return@listenTo
    val record = MdtStorage.autoLoginByDevice(packet.uuid, packet.usid) ?: return@listenTo
    if (record.account.status == "normal") {
        data.addId(record.subjectUid, asPrimary = true)
    }
}

listen<EventType.PlayerJoin> {
    val player = it.player
    val now = System.currentTimeMillis()
    val key = sessionKey(player)
    sessionJoinAtMillis[key] = now
    sessionLastSeenAtMillis[key] = now
    pruneSessionOnlineCache(now)
    if (!PlayerData[player].authed) {
        val prefix = if (serverTestModeEnabled()) "[red][危险]服务器测试模式已启用：登录后才可获得测试MDC、信任1/资历3。 " else ""
        player.sendMessage("[yellow]${prefix}你尚未登录账号。已有账号可直接输入 [gold]/login[] 登录；注册需本次启动累计在线满1小时后用 [gold]/captcha[] 获取验证码。")
    }
}

listenTo<RequestPermissionEvent> {
    val p = subject as? Player ?: return@listenTo
    if (PlayerData[p].authed && "@authed" !in group) group += "@authed"
}

listen<EventType.PlayerLeave> {
    val key = sessionKey(it.player)
    val now = System.currentTimeMillis()
    sessionJoinAtMillis.remove(key)?.let { joinedAt ->
        val added = (now - joinedAt).coerceAtLeast(0L)
        sessionOnlineMillis[key] = (sessionOnlineMillis[key] ?: 0L) + added
    }
    sessionLastSeenAtMillis[key] = now
    pruneSessionOnlineCache(now)
    // 不在离线时清空登录失败记录：否则批量尝试密码可通过重连绕过 UUID/IP 冷却。
    pruneLoginFailures()
    loginCaptchas.remove(captchaKey(it.player))
}

listen<EventType.ResetEvent> {
    loginFails.clear()
    loginCaptchas.clear()
}

onEnable {
    val now = System.currentTimeMillis()
    Groups.player.forEach { player ->
        val key = sessionKey(player)
        sessionJoinAtMillis.putIfAbsent(key, now)
        sessionLastSeenAtMillis[key] = now
    }
    pruneSessionOnlineCache(now)
}

command("captcha", "获取注册验证码") {
    aliases = listOf("验证码", "authcode", "code")
    attr(ClientOnly)
    body {
        val p = player!!
        if (PlayerData[p].authed) {
            returnReply("[yellow]你已经登录账号，无需获取验证码。".with())
        }
        registerCaptchaOnlineError(p)?.let { returnReply(it.with()) }
        val code = issueCaptcha(p)
        val onlineText = formatSessionOnlineDuration(currentSessionOnlineMillis(p))
        reply(
            """
            |[cyan]你的注册验证码是：[gold]$code
            |[gray]有效期：${captchaExpireMillis.coerceAtLeast(60_000L) / 1000L}秒。
            |[gray]已满足注册在线条件：本次启动累计在线 [white]$onlineText[gray] / 1小时。
            |[yellow]请继续输入 /register，并在弹窗中填写该验证码。
        """.trimMargin().with()
        )
    }
}

command("account", "打开账号注册/登录页面") {
    aliases = listOf("账号")
    attr(ClientOnly)
    body { openAccountMenu(player!!) }
}

command("register", "注册MDTDO账号") {
    aliases = listOf("注册")
    attr(ClientOnly)
    body { registerFlow(player!!) }
}

command("login", "登录MDTDO账号") {
    aliases = listOf("登录")
    attr(ClientOnly)
    body { loginFlow(player!!) }
}

command("changepassword", "修改当前账号密码") {
    aliases = listOf("passwd", "改密", "修改密码")
    attr(ClientOnly)
    body { changePasswordFlow(player!!) }
}

command("deleteownaccount", "注销当前登录的MDT账号") {
    aliases = listOf("deleteaccountself", "cancelaccount", "注销账号", "账号注销")
    attr(ClientOnly)
    body { deleteOwnAccountFlow(player!!) }
}

command("setpassword", "管理指令：重置玩家账号密码") {
    usage = "<QQ账号> [新密码 confirm(仅控制台)]"
    permission = "wayzer.admin.account"
    aliases = listOf("resetpassword", "账号改密", "重置密码")
    body {
        if (!canManageAccount(player)) returnReply("[red]权限不足：需要账号管理权限".with())
        if (arg.isEmpty()) replyUsage()
        val qq = normalizeQq(arg[0]) ?: returnReply("[red]QQ号格式不正确，需要 ${QQ_MIN_LENGTH}-${QQ_MAX_LENGTH} 位数字".with())
        val operator = player
        val newPassword = when {
            operator != null -> {
                if (arg.size > 1) {
                    operator.sendMessage("[yellow]为避免新密码进入聊天日志，游戏内重置密码请只输入 [gold]/setpassword <QQ账号>[]，随后使用弹窗输入。")
                }
                askNewPasswordTwice(operator, "管理员重置密码") ?: return@body
            }
            arg.size == 3 && operator == null && arg[2].lowercase() == "confirm" -> arg[1]
            operator == null -> returnReply("[red]控制台用法：setpassword <QQ账号> <新密码> confirm".with())
            else -> replyUsage()
        }
        adminResetPassword(operator, qq, newPassword)
    }
}

command("deleteaccount", "管理指令：删除玩家账号") {
    usage = "<QQ账号/玩家3位ID/UUID/account:id> [confirm]"
    permission = "wayzer.admin.account"
    aliases = listOf("delaccount", "账号删除", "删除账号")
    body {
        if (!canManageAccount(player)) returnReply("[red]权限不足：需要账号管理权限".with())
        if (arg.isEmpty()) replyUsage()
        val target = arg[0]
        val operator = player
        val consoleConfirmed = operator == null && arg.getOrNull(1)?.lowercase() == "confirm"
        if (operator == null && !consoleConfirmed) {
            returnReply("[red]控制台用法：deleteaccount <QQ账号/玩家UUID/account:id> confirm".with())
        }
        adminDeleteAccount(operator, target, consoleConfirmed)
    }
}

command("accountqq", "管理指令：查询玩家对应QQ账号") {
    usage = "<玩家3位ID/UUID/account:id/QQ账号>"
    permission = "wayzer.admin.account"
    aliases = listOf("qqof", "查qq", "查询qq", "账号查询", "queryqq")
    body {
        if (!canManageAccount(player)) returnReply("[red]权限不足：需要账号管理权限".with())
        if (arg.isEmpty()) replyUsage()
        val lookup = resolveAccountRef(arg[0])
            ?: returnReply("[red]未找到账号：${arg[0]}，可使用玩家3位ID、完整UUID、account:<id> 或 QQ账号".with())
        reply(accountLookupText(lookup).with())
    }
}

PermissionApi.registerDefault("wayzer.admin.account", group = "@admin")
