@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")
@file:Depends("wayzer/user/playerTitle", "玩家正式称号")

package wayzer.ext

import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.PlayerLikedEvent
import wayzer.lib.ReputationChangedEvent
import java.time.LocalDate

private data class VoteLimits(
    val perTargetPerDay: Int?,
    val totalPerDay: Int?,
)

private enum class VoteType(
    val actionText: String,
    val selfMessage: String,
    val storageCode: String,
) {
    Like("赞", "不能给自己点赞哦！", "like"),
    Dislike("踩", "不能给自己点踩哦！", "dislike"),
}

private val trustLevel = contextScript<wayzer.user.TrustLevel>()
private val playerTitle = contextScript<wayzer.user.PlayerTitle>()

fun playerReputationUid(player: Player): String = PlayerData[player].id

fun playerReputationLevel(uid: String, player: Player?): Int = with(trustLevel) { getTrustLevel(uid, player) }
fun playerReputationLevelText(uid: String, player: Player?): String = with(trustLevel) { getTrustLevelDisplayCode(uid, player) }

// 正式称号接口。随机形态头衔独立显示在名字前缀中，不再混入这里。
fun playerReputationTitle(uid: String, player: Player?): String =
    with(playerTitle) { playerTitleName(uid, player) } ?: "暂无"

fun playerLikes(uid: String): Int = MdtStorage.getReputation(uid).receivedLikes
fun playerDislikes(uid: String): Int = MdtStorage.getReputation(uid).receivedDislikes
fun playerGivenLikes(uid: String): Int = MdtStorage.getReputation(uid).givenLikes
fun playerGivenDislikes(uid: String): Int = MdtStorage.getReputation(uid).givenDislikes

private fun emitReputationChanged(uids: Set<String>) {
    launch { ReputationChangedEvent(uids).emitAsync() }
}

private fun emitPlayerLiked(fromUid: String, targetUid: String) {
    launch { PlayerLikedEvent(fromUid, targetUid).emitAsync() }
}

private fun todayKey(): String = LocalDate.now().toString()
private var prunedDailyDate: String? = null

private fun likeLimits(level: Int): VoteLimits = when {
    level >= 4 -> VoteLimits(null, null)
    level >= 3 -> VoteLimits(perTargetPerDay = 8, totalPerDay = 50)
    level >= 2 -> VoteLimits(perTargetPerDay = 5, totalPerDay = 25)
    else -> VoteLimits(perTargetPerDay = 3, totalPerDay = 10)
}

private fun dislikeLimits(level: Int): VoteLimits = when {
    level >= 4 -> VoteLimits(null, null)
    level >= 3 -> VoteLimits(perTargetPerDay = 5, totalPerDay = 12)
    level >= 2 -> VoteLimits(perTargetPerDay = 3, totalPerDay = 8)
    else -> VoteLimits(perTargetPerDay = 2, totalPerDay = 5)
}

private fun limitsFor(type: VoteType, level: Int): VoteLimits = when (type) {
    VoteType.Like -> likeLimits(level)
    VoteType.Dislike -> dislikeLimits(level)
}

private fun onlinePlayerByUid(uid: String): Player? =
    Groups.player.find { PlayerData[it].id == uid }

private val ipv4Regex = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

private fun normalizeIp(raw: String?): String =
    raw?.let { ipv4Regex.find(it)?.value ?: it.trim().substringBefore('%') }
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"

private fun playerIp(player: Player): String =
    normalizeIp(player.con?.address ?: player.ip())

private fun subjectIp(uid: String, online: Player? = onlinePlayerByUid(uid)): String? =
    online?.let { playerIp(it) }?.takeIf { it != "unknown" }
        ?: MdtStorage.getSubjectLastIp(uid)?.let { normalizeIp(it) }?.takeIf { it != "unknown" }

private fun sameKnownIp(viewer: Player, viewerUid: String, targetUid: String): Boolean {
    val fromIp = subjectIp(viewerUid, viewer) ?: return false
    val targetIp = subjectIp(targetUid) ?: return false
    return fromIp == targetIp
}

private fun pruneOldDailyCounters(date: String) {
    if (prunedDailyDate == date) return
    MdtStorage.pruneReputationDaily(date)
    prunedDailyDate = date
}

private fun performVote(viewer: Player, targetUid: String, targetName: String, type: VoteType): Boolean {
    val fromUid = playerReputationUid(viewer)
    if (fromUid == targetUid) {
        viewer.sendMessage("[yellow]${type.selfMessage}")
        return false
    }

    val level = playerReputationLevel(fromUid, viewer)
    if (level <= 0) {
        viewer.sendMessage("[yellow]等级不足，0级玩家不能点赞/点踩哦！")
        return false
    }

    if (type == VoteType.Like && sameKnownIp(viewer, fromUid, targetUid)) {
        viewer.sendMessage("[yellow]同IP账号之间不能点赞，避免刷赞/MDC哦！")
        return false
    }

    val limits = limitsFor(type, level)
    val date = todayKey()
    pruneOldDailyCounters(date)

    val result = MdtStorage.recordReputationVoteChecked(
        date = date,
        fromUid = fromUid,
        targetUid = targetUid,
        type = type.storageCode,
        totalLimit = limits.totalPerDay,
        targetLimit = limits.perTargetPerDay,
    )
    if (!result.accepted) {
        when (result.rejectedBy) {
            "total" -> viewer.sendMessage("[yellow]你今天的${type.actionText}次数已用完啦！")
            "target" -> viewer.sendMessage("[yellow]你今天对 [white]$targetName[] 的${type.actionText}次数已达上限啦！")
            else -> viewer.sendMessage("[yellow]本次${type.actionText}未成功，请稍后再试")
        }
        return false
    }
    val newTargetCount = result.targetCount

    viewer.sendMessage("[green]你${type.actionText}了 [white]$targetName[green]（第${newTargetCount}次）")

    if (type == VoteType.Like) {
        onlinePlayerByUid(targetUid)?.sendMessage(
            if (newTargetCount <= 1)
                "[green]你被 [white]${viewer.name}[green] 赞了！"
            else
                "[green]你被 [white]${viewer.name}[green] 赞了！（第${newTargetCount}次）"
        )
        emitPlayerLiked(fromUid, targetUid)
    }
    emitReputationChanged(setOf(fromUid, targetUid))
    return true
}

fun likePlayer(viewer: Player, targetUid: String, targetName: String): Boolean =
    performVote(viewer, targetUid, targetName, VoteType.Like)

fun dislikePlayer(viewer: Player, targetUid: String, targetName: String): Boolean =
    performVote(viewer, targetUid, targetName, VoteType.Dislike)

private fun parseVoteType(text: String): VoteType? = when (text.lowercase()) {
    "like", "likes", "liked", "赞", "点赞" -> VoteType.Like
    "dislike", "dislikes", "disliked", "踩", "点踩" -> VoteType.Dislike
    else -> null
}

private fun resolveVoteTarget(text: String): Pair<String, String> {
    val data = PlayerData.findByShortId(text)
    val uid = data?.id ?: text
    val name = data?.player?.name ?: data?.name ?: text
    return uid to name
}

private fun receivedVote(type: VoteType, uid: String): Int =
    MdtStorage.getReceivedReputation(uid, type.storageCode)

private fun setReceivedVote(type: VoteType, uid: String, value: Int): Int =
    MdtStorage.setReceivedReputation(uid, type.storageCode, value)

private fun addReceivedVote(type: VoteType, uid: String, delta: Int): Int =
    MdtStorage.addReceivedReputation(uid, type.storageCode, delta)


command("reputation", "管理指令：查看/修改玩家口碑赞踩数") {
    usage = "<玩家id/3位id> [赞|踩] [set|add] [数量]"
    permission = "wayzer.admin.reputation"
    aliases = listOf("rep", "口碑", "赞踩")
    body {
        if (arg.isEmpty()) replyUsage()

        val (targetUid, targetName) = resolveVoteTarget(arg[0])
        if (arg.size == 1) {
            returnReply(
                """
                    |[cyan]玩家：[white]$targetName
                    |[cyan]UID：[white]$targetUid
                    |[cyan]被赞数：[white]${playerLikes(targetUid)}
                    |[cyan]被踩数：[white]${playerDislikes(targetUid)}
                    |[cyan]送出赞数：[white]${playerGivenLikes(targetUid)}
                    |[cyan]送出踩数：[white]${playerGivenDislikes(targetUid)}
                """.trimMargin().with()
            )
        }

        if (arg.size < 4) replyUsage()
        val type = parseVoteType(arg[1])
            ?: returnReply("[red]类型错误，请使用 赞/踩 或 like/dislike".with())
        val op = arg[2].lowercase()
        val value = arg[3].toIntOrNull() ?: replyUsage()

        val oldValue = receivedVote(type, targetUid)
        val newValue = when (op) {
            "set", "设置" -> setReceivedVote(type, targetUid, value)
            "add", "增加", "加" -> addReceivedVote(type, targetUid, value)
            else -> returnReply("[red]操作错误，请使用 set/add".with())
        }

        reply(
            "[green]已修改 [white]{name}[green] 的被{type}数：[yellow]{old}[green] -> [yellow]{new}"
                .with("name" to targetName, "type" to type.actionText, "old" to oldValue, "new" to newValue)
        )
        emitReputationChanged(setOf(targetUid))
    }
}

PermissionApi.registerDefault("wayzer.admin.reputation", group = "@admin")

