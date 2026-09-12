@file:Depends("coreMindustry/menu")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer

import cf.wayzer.placehold.DynamicVar
import cf.wayzer.scriptAgent.events.ScriptDisableEvent
import mindustry.gen.Groups
import mindustry.gen.Player
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.user.TrustLevel
import java.time.Duration
import java.time.Instant

name = "投票服务"

private val trustLevel = contextScript<TrustLevel>()

listen<EventType.PlayerJoin> {
    (VoteEvent.active.get() ?: return@listen)
        .vote(it.player, VoteEvent.Action.Join)
}

listen<EventType.PlayerLeave> {
    VoteEvent.lastAction = System.currentTimeMillis()
    (VoteEvent.active.get() ?: return@listen)
        .vote(it.player, VoteEvent.Action.Quit)
}

listen<EventType.PlayerChatEvent> {
    val action = when (it.message.lowercase()) {
        "赞成", "y", "1" -> VoteEvent.Action.Agree
        "反对", "n", "0" -> VoteEvent.Action.Disagree
        "中立", "." -> VoteEvent.Action.Ignore
        else -> return@listen
    }
    val active = VoteEvent.active.get()
    if (active == null) {
        // 点歌等外部投票会复用 1/./0 快捷表态；此时不要误提示“投票已结束”。
        if (VoteEvent.startBlockReason(it.player) != null) return@listen
        return@listen it.player.sendMessage("[red]投票已结束")
    }
    active.vote(it.player, action)
}

listen<EventType.ResetEvent> { VoteEvent.coolDowns.clear() }

registerVar("scoreboard.ext.vote", "投票状态显示", DynamicVar {
    VoteEvent.active.get()?.run {
        "{cK}投票{cV}{desc}:\n    {status} {cV}\uE867{left 秒}".with(
            "desc" to voteDesc,
            "status" to status(),
            "left" to Duration.between(Instant.now(), endTime)
        )
    }
})

command("vote", "发起投票") {
    type = CommandType.Client
    aliases = listOf("投票")
    attr {
        if (VoteEvent.active.get() != null)
            returnReply("[red]投票进行中".with())
        player?.let { VoteEvent.startBlockReason(it)?.let { reason -> returnReply(reason.with()) } }
    }
    body(VoteEvent.VoteCommands)
}
listenTo<ScriptDisableEvent> {
    VoteEvent.VoteCommands.removeAll(script)
}
PermissionApi.registerDefault("wayzer.vote.*")
PermissionApi.registerDefault(VoteEvent.unlimitedVotePermission, group = "@admin")

/**
 * 管理指令：禁止/解除"某玩家发起投票"。
 * 权限沿用 3++/4 级的既有分层口径（与玩家信息菜单按钮一致），不新增权限节点：
 * - 控制台（player == null）总是允许；
 * - 玩家需要 3++ 级及以上（与菜单按钮的门槛一致）。
 */
private fun canManageVoteStartBan(operator: Player?): Boolean =
    operator == null || with(trustLevel) { hasTrustLevel(operator, "3++") }

/**
 * 按"玩家id / 三位id / UUID / 主体uid（如 account:12）"解析目标，返回 (主体uid, 显示名)。
 *
 * 解析顺序：在线玩家（名字/UUID）→ `PlayerData.findByShortId`（含 1 天内历史）→
 * 直接当作主体 uid 查库（`MdtStorage.getSubjectName`）。
 * 最后一条是为"玩家已离线较久、只剩主体 uid"的场景兜底，否则管理员无法对离线玩家施加限制
 * （2026-09-12 自测时发现：只按在线/历史缓存解析会直接报"找不到目标玩家"）。
 */
private fun resolveVoteBanTarget(input: String): Pair<String, String>? {
    Groups.player.firstOrNull { it.name.equals(input, ignoreCase = true) || it.uuid() == input }
        ?.let { return PlayerData[it].id to it.plainName() }
    PlayerData.findByShortId(input)?.let { return it.id to it.name }

    val uid = input.trim()
    if (uid.isEmpty()) return null
    val name = runCatching { MdtStorage.getSubjectName(uid) }.getOrNull()
    if (!name.isNullOrBlank()) return uid to name
    // 库里没有名字时，只要看起来是主体 uid / 游戏 UUID，也允许按 uid 施加。
    return if (uid.startsWith("account:") || uid.length >= 16) uid to uid else null
}

private fun onlinePlayerByUid(uid: String): Player? =
    Groups.player.firstOrNull { PlayerData[it].id == uid }

command("votestartban", "管理指令：禁止指定玩家发起投票") {
    usage = "<玩家id|三位id|主体uid> [理由]"
    aliases = listOf("禁投票", "禁止投票", "voteban")
    attr {
        if (!canManageVoteStartBan(player)) returnReply("[red]权限不足：需要 3++ 级及以上协管。".with())
    }
    body {
        val input = arg.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: replyUsage()
        val (uid, name) = resolveVoteBanTarget(input)
            ?: returnReply("[red]找不到目标玩家：$input（可用玩家名/三位ID/UUID）".with())
        val reason = arg.drop(1).joinToString(" ").trim().ifBlank { "管理员操作" }
        val existed = VoteEvent.isStartBanned(uid)
        VoteEvent.banVoteStarter(uid, reason)
        if (existed) returnReply("[yellow]$name 原本已被禁止发起投票，已更新理由：[white]$reason".with())
        reply("[green]已禁止 [white]$name[green] 发起投票：[white]$reason".with())
        onlinePlayerByUid(uid)?.sendMessage("[yellow]你已被管理员禁止发起投票：[white]$reason".with())
    }
}

command("voteunban", "管理指令：解除禁止发起投票") {
    usage = "<玩家id|三位id>"
    aliases = listOf("解禁投票", "解除禁止投票", "voteunbanstart")
    attr {
        if (!canManageVoteStartBan(player)) returnReply("[red]权限不足：需要 3++ 级及以上协管。".with())
    }
    body {
        val input = arg.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: replyUsage()
        val (uid, name) = resolveVoteBanTarget(input)
            ?: returnReply("[red]找不到目标玩家：$input（可用玩家名/三位ID/UUID）".with())
        if (!VoteEvent.unbanVoteStarter(uid)) {
            returnReply("[yellow]$name 当前没有被禁止发起投票。".with())
        }
        reply("[green]已解除 [white]$name[green] 的禁止发起投票。".with())
        onlinePlayerByUid(uid)?.sendMessage("[green]管理员已解除你的禁止发起投票状态。".with())
    }
}

command("votelist", "管理指令：查看禁止发起投票名单") {
    aliases = listOf("禁投票名单", "votebanlist")
    attr {
        if (!canManageVoteStartBan(player)) returnReply("[red]权限不足：需要 3++ 级及以上协管。".with())
    }
    body {
        val all = VoteEvent.allStartBans()
        if (all.isEmpty()) returnReply("[green]当前没有玩家被禁止发起投票。".with())
        reply("[cyan]禁止发起投票名单（${all.size}）：".with())
        all.forEach { (uid, reason) ->
            val name = onlinePlayerByUid(uid)?.plainName()
                ?: PlayerData.history.asMap().values.firstOrNull { it.id == uid }?.name
                ?: uid
            reply("  [white]$name[gray]：[white]${reason.ifBlank { "（无理由）" }}".with())
        }
    }
}
