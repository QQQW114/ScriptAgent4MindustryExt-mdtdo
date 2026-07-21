@file:Depends("coreLibrary/extApi/rpcService", "远程调用")
@file:Depends("wayzer/user/accountIpGuard", "风险IP标记")
@file:Depends("wayzer/security/securityGuard", "IP封禁列表")
@file:Depends("coreMindustry/menu", "封禁管理菜单")
@file:Depends("wayzer/user/trustLevel", "3++协管分层权限")

package wayzer.user

import coreMindustry.MenuBuilder
import coreMindustry.PagedMenuBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mindustry.gen.Player
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.security.SecurityGuard
import java.io.Serializable
import java.rmi.Remote
import java.rmi.RemoteException
import java.text.DateFormat
import java.time.Duration
import java.time.Instant
import java.util.*

data class PlayerBan(
    val recordId: Int,
    val ids: Set<String>,
    val reason: String,
    val operator: String?,
    val createTime: Instant,
    val endTime: Instant
) : Serializable

interface PlayerBanStore : Remote {
    @Throws(RemoteException::class)
    fun findNotEnd(id: String): PlayerBan?
    @Throws(RemoteException::class)
    fun findNotEndByShortId(shortId: String): PlayerBan?
    @Throws(RemoteException::class)
    fun listActive(): List<PlayerBan>
    @Throws(RemoteException::class)
    fun create(
        ids: Set<String>,
        duration: Duration,
        reason: String,
        operator: String?
    ): PlayerBan
    @Throws(RemoteException::class)
    fun delete(record: Int): PlayerBan?
}

val rpcService = contextScript<coreLibrary.extApi.RpcService>()
val store get() = rpcService.get<PlayerBanStore>()
val ipGuard = contextScript<AccountIpGuard>()
val securityGuard = contextScript<SecurityGuard>()
val trustLevel = contextScript<TrustLevel>()

private enum class BanMenuKind { PLAYER, IP }

private data class BanMenuEntry(
    val kind: BanMenuKind,
    val key: String,
    val title: String,
    val subtitle: String,
    val reason: String,
    val operator: String,
    val operatorUid: String?,
    val untilMillis: Long,
    val detail: String,
)

private fun formatBanRemaining(untilMillis: Long): String {
    var seconds = ((untilMillis - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(0L)
    if (seconds <= 0L) return "已到期"
    val days = seconds / 86_400L
    seconds %= 86_400L
    val hours = seconds / 3_600L
    seconds %= 3_600L
    val minutes = seconds / 60L
    seconds %= 60L
    return buildList {
        if (days > 0) add("${days}天")
        if (hours > 0 || days > 0) add("${hours}时")
        if (minutes > 0 || hours > 0 || days > 0) add("${minutes}分")
        add("${seconds}秒")
    }.joinToString("")
}

private fun resolveKnownPlayerData(ban: PlayerBan): PlayerData? =
    ban.ids.asSequence().mapNotNull { PlayerData.findByShortId(it) }.firstOrNull()

private fun PlayerBan.toMenuEntry(subjectNames: Map<String, String?>): BanMenuEntry {
    val data = resolveKnownPlayerData(this)
    val name = data?.name
        ?: ids.asSequence().mapNotNull { subjectNames[it] }.firstOrNull { it.isNotBlank() }
        ?: "未知玩家"
    val displayId = data?.shortId ?: "封禁#$recordId"
    return BanMenuEntry(
        kind = BanMenuKind.PLAYER,
        key = recordId.toString(),
        title = "[red]玩家：[white]$name [gray]($displayId)",
        subtitle = "[gray]剩余 ${formatBanRemaining(endTime.toEpochMilli())} | 原因：${reason.replace('\n', ' ').take(42)}",
        reason = reason,
        operator = operator ?: "未知/系统",
        operatorUid = operator,
        untilMillis = endTime.toEpochMilli(),
        detail = "[cyan]关联ID：[white]${ids.filter { it.isNotBlank() }.take(8).joinToString("、").ifBlank { "未知" }}\n" +
            "[cyan]封禁记录：[white]#$recordId\n" +
            "[cyan]开始时间：[white]$createTime\n[cyan]结束时间：[white]$endTime",
    )
}

private suspend fun loadBanMenuEntries(): List<BanMenuEntry> {
    val playerBans = withContext(Dispatchers.IO) {
        val bans = store.listActive()
        val names = MdtStorage.getSubjectNames(bans.flatMap { it.ids })
        bans.map { it.toMenuEntry(names) }
    }
    val ipBans = with(securityGuard) {
        activeIpBanInfos().map { info ->
            val playerText = info.targetName?.takeIf { it.isNotBlank() } ?: "未知玩家"
            BanMenuEntry(
                kind = BanMenuKind.IP,
                key = info.ip,
                title = "[orange]IP：[white]${info.ip} [gray]($playerText)",
                subtitle = "[gray]剩余 ${formatBanRemaining(info.until)} | 原因：${info.reason.replace('\n', ' ').take(42)}",
                reason = info.reason,
                operator = info.operatorUid ?: "未知/系统",
                operatorUid = info.operatorUid,
                untilMillis = info.until,
                detail = "[cyan]关联玩家：[white]$playerText\n" +
                    "[cyan]UUID：[white]${info.targetUuid ?: "未知"}\n[cyan]IP：[white]${info.ip}",
            )
        }
    }
    return (playerBans + ipBans).sortedBy { it.untilMillis }
}

private fun banMenuEntryDetail(entry: BanMenuEntry): String = """
    |${entry.title}
    |[cyan]类型：[white]${if (entry.kind == BanMenuKind.PLAYER) "玩家/账号封禁" else "IP封禁"}
    |[cyan]剩余时间：[white]${formatBanRemaining(entry.untilMillis)}
    |[cyan]操作人：[white]${entry.operator}
    |[cyan]原因：[white]${entry.reason}
    |${entry.detail}
""".trimMargin()

private fun canUnbanEntry(player: Player, entry: BanMenuEntry): Boolean = with(trustLevel) {
    isTrustAdmin(player) || (isPluginAdmin(player) && entry.operatorUid == PlayerData[player].id)
}

private suspend fun openBanDetailMenu(player: Player, entry: BanMenuEntry) {
    MenuBuilder<Unit>(if (entry.kind == BanMenuKind.PLAYER) "玩家封禁详情" else "IP封禁详情") {
        msg = banMenuEntryDetail(entry)
        if (canUnbanEntry(player, entry)) {
            option("[green]立即解除封禁") {
                val success = when (entry.kind) {
                    BanMenuKind.PLAYER -> withContext(Dispatchers.IO) { store.delete(entry.key.toInt()) != null }
                    BanMenuKind.IP -> with(securityGuard) { unbanIpForOperator(entry.key, player) }
                }
                if (success) player.sendMessage("[green]已解除封禁：[white]${entry.title}")
                else player.sendMessage("[yellow]该封禁记录已不存在、已到期或你无权解除。")
                openBanListMenu(player)
            }
        }
        option("返回列表") { openBanListMenu(player) }
        newRow()
        option("关闭") {}
    }.sendTo(player, 60_000)
}

private suspend fun openBanListMenu(player: Player) {
    val entries = runCatching { loadBanMenuEntries() }.getOrElse {
        logger.warning("读取封禁列表失败: ${it.stackTraceToString()}")
        player.sendMessage("[red]读取封禁列表失败：[white]${it.message ?: it.javaClass.simpleName}")
        return
    }
    if (entries.isEmpty()) {
        MenuBuilder<Unit>("封禁管理") {
            msg = "[green]当前没有未到期的玩家或IP封禁。"
            option("刷新") { openBanListMenu(player) }
            option("关闭") {}
        }.sendTo(player, 60_000)
        return
    }
    object : PagedMenuBuilder<BanMenuEntry>(entries, prePage = 6) {
        override suspend fun renderItem(item: BanMenuEntry) {
            option("${item.title}\n${item.subtitle}") { openBanDetailMenu(player, item) }
        }

        override suspend fun build() {
            title = "封禁玩家与IP"
            msg = "[gray]共 ${entries.size} 条未到期封禁；按剩余时间排序。点击查看原因、剩余时长并快速解封。"
            super.build()
            option("刷新") { openBanListMenu(player) }
        }
    }.sendTo(player, 60_000)
}

private suspend fun banListText(): String {
    val entries = loadBanMenuEntries()
    if (entries.isEmpty()) return "[green]当前没有未到期的玩家或IP封禁。"
    return entries.joinToString("\n", prefix = "[cyan]封禁玩家与IP：\n") { entry ->
        "${entry.title} [gray]剩余 ${formatBanRemaining(entry.untilMillis)}，原因：${entry.reason}"
    }
}

fun Player.kick(ban: PlayerBan) {
    fun format(instant: Instant) = DateFormat.getDateTimeInstance().format(Date.from(instant))
    kick(
        """
        [red]你已在该服被禁封[]
        [yellow]名字: ${name()}
        [green]原因: ${ban.reason} (封禁ID#${ban.recordId})
        [green]禁封时间: ${format(ban.createTime)}
        [green]解禁时间: ${format(ban.endTime)}
        [yellow]如有问题,请截图此页咨询管理员
    """.trimIndent(), 0
    )
}

listen<EventType.PlayerConnect> {
    launch(Dispatchers.IO) {
        val ban = store.findNotEnd(PlayerData[it.player].id) ?: return@launch
        withContext(Dispatchers.game) {
            with(ipGuard) { markRiskIpForPlayer(it.player, "禁封账号尝试进入: ${ban.reason}") }
            it.player.kick(ban)
        }
    }
}

suspend fun ban(player: PlayerData, time: Int, reason: String, operate: Player?) {
    val ban = withContext(Dispatchers.IO) {
        store.create(
            player.ids,
            Duration.ofMinutes(time.toLong()), reason,
            operate?.let { PlayerData[it].id }
        )
    }
    val riskReason = if (reason.startsWith("投票踢出")) reason else "账号被禁封: $reason"
    if (reason.startsWith("投票踢出")) {
        // 投票踢出先计入同IP 24小时窗口；达到 accountIpGuard 的阈值后再标记风险IP。
        with(ipGuard) { recordKickForPlayerData(player, riskReason, operate) }
    } else {
        // 管理禁封或其它非投票封禁仍立即触发风险IP，避免被封账号换游客继续进入。
        with(ipGuard) { markRiskIpForPlayerData(player, riskReason, operate) }
    }
    Groups.player.filter { PlayerData[it].id in player.ids }.forEach {
        it.kick(ban)
        broadcast("[red] 管理员禁封了{target.name},原因: [yellow]{reason}".with("target" to it, "reason" to reason))
    }
}

command("banX", "管理指令: 禁封") {
    usage = "<3位id> <时间|分钟> <原因>"
    requirePermission("wayzer.admin.ban")
    body {
        if (arg.size < 3) replyUsage()
        val target = PlayerData.findByShortId(arg[0])
            ?: returnReply("[red]未找到目标, 请输入目标UUID/3位ID.".with())
        val time = arg[1].toIntOrNull()?.takeIf { it > 0 } ?: replyUsage()
        val reason = arg.slice(2 until arg.size).joinToString(" ")
        player?.let { operator ->
            if (!with(trustLevel) { canModerateTrustTarget(operator, target.id, target.player) }) {
                returnReply("[red]你不能封禁同级或更高等级的玩家。".with())
            }
            if (with(trustLevel) { isPluginAdmin(operator) } && time > with(trustLevel) { pluginAdminMaxBanMinutes() }) {
                returnReply("[red]3++协管单次最多封禁 [white]${with(trustLevel) { pluginAdminMaxBanMinutes() }}[red] 分钟。".with())
            }
        }

        ban(target, time, reason, player)
        reply("[green]已禁封{qq}".with("qq" to (target)))
    }
}
command("unbanX", "管理指令: 解禁") {
    usage = "<玩家3位ID/UUID/账号UID|封禁ID>"
    requirePermission("wayzer.admin.unban")
    body {
        if (arg.isEmpty()) replyUsage()
        val input = arg[0].trim()
        if (input.isEmpty()) replyUsage()
        val target = PlayerData.findByShortId(input)
        val candidateIds = linkedSetOf<String>().apply {
            if (target != null) {
                addAll(target.ids)
                add(target.id)
                add(target.uuid)
            }
            add(input)
        }
        val activeBan = withContext(Dispatchers.IO) {
            candidateIds.asSequence().mapNotNull { store.findNotEnd(it) }.firstOrNull()
                ?: input.takeIf { target == null && it.length == 3 }?.let { store.findNotEndByShortId(it) }
        }
        val selectedBan = activeBan ?: input.toIntOrNull()?.let { recordId ->
            withContext(Dispatchers.IO) { store.listActive().firstOrNull { it.recordId == recordId } }
        } ?: returnReply("[red]找不到目标账号的未过期封禁记录，检查玩家3位ID/UUID/账号UID是否正确".with())
        player?.let { operator ->
            if (with(trustLevel) { isPluginAdmin(operator) } && selectedBan.operator != PlayerData[operator].id) {
                returnReply("[red]3++协管只能解除自己施加的账号封禁。".with())
            }
        }
        val ban = withContext(Dispatchers.IO) { store.delete(selectedBan.recordId) }
            ?: returnReply("[yellow]该封禁记录已不存在或已过期。".with())
        logger.info("unban ${ban.ids} ${ban.endTime} ${ban.reason}")
        val targetText = target?.let { "${it.name}(${it.shortId})" } ?: input
        reply("[green]解禁成功: [white]{target}[]，封禁ID#{id}，禁封原因: {reason}".with(
            "target" to targetText,
            "id" to ban.recordId,
            "reason" to ban.reason
        ))
    }
}
command("banlist", "管理指令：查看玩家/IP封禁并快速解封") {
    aliases = listOf("bans", "封禁列表", "封禁管理")
    requirePermission("wayzer.admin.banList")
    body {
        val p = player
        if (p != null) openBanListMenu(p)
        else reply(runCatching { banListText() }.getOrElse { "[red]读取封禁列表失败：[white]${it.message ?: it.javaClass.simpleName}" }.with())
    }
}
PermissionApi.registerDefault("wayzer.admin.ban", "wayzer.admin.unban", "wayzer.admin.banList", group = "@admin")
