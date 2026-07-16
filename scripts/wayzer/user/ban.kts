@file:Depends("coreLibrary/extApi/rpcService", "远程调用")
@file:Depends("wayzer/user/accountIpGuard", "风险IP标记")

package wayzer.user

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
        val ban = when {
            activeBan != null -> withContext(Dispatchers.IO) { store.delete(activeBan.recordId) }
            target == null && input.toIntOrNull() != null -> withContext(Dispatchers.IO) { store.delete(input.toInt()) }
            else -> null
        } ?: returnReply("[red]找不到目标账号的未过期封禁记录，检查玩家3位ID/UUID/账号UID是否正确".with())
        logger.info("unban ${ban.ids} ${ban.endTime} ${ban.reason}")
        val targetText = target?.let { "${it.name}(${it.shortId})" } ?: input
        reply("[green]解禁成功: [white]{target}[]，封禁ID#{id}，禁封原因: {reason}".with(
            "target" to targetText,
            "id" to ban.recordId,
            "reason" to ban.reason
        ))
    }
}
PermissionApi.registerDefault("wayzer.admin.ban", "wayzer.admin.unban", group = "@admin")
