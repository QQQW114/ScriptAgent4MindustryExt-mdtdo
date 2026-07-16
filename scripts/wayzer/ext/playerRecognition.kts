@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.ext

import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.PlayerRecognizedEvent
import wayzer.lib.RecognitionChangedEvent
import java.time.LocalDate

private val trustLevel = contextScript<wayzer.user.TrustLevel>()

fun playerReceivedRecognitions(uid: String): Int = MdtStorage.getRecognition(uid).received
fun playerGivenRecognitions(uid: String): Int = MdtStorage.getRecognition(uid).given

private fun emitRecognitionChanged(fromUid: String, targetUid: String) {
    launch {
        PlayerRecognizedEvent(fromUid, targetUid).emitAsync()
        RecognitionChangedEvent(setOf(fromUid, targetUid)).emitAsync()
    }
}

private fun todayKey(): String = LocalDate.now().toString()

private fun pruneOldDailyRecognitions() {
    MdtStorage.pruneRecognitionDaily(todayKey())
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

private fun resolveRecognitionTarget(text: String): Pair<String, String> {
    val data = PlayerData.findByShortId(text)
    val uid = data?.id ?: text
    val name = data?.player?.name ?: data?.name ?: text
    return uid to name
}

fun recognizePlayer(viewer: Player, targetUid: String, targetName: String) {
    pruneOldDailyRecognitions()

    val fromUid = PlayerData[viewer].id
    if (fromUid == targetUid) {
        viewer.sendMessage("[yellow]不能认可自己哦！")
        return
    }

    val level = with(trustLevel) { getTrustLevel(fromUid, viewer) }
    if (level < 2) {
        viewer.sendMessage("[yellow]等级不足，2级玩家才能认可他人哦！")
        return
    }

    if (sameKnownIp(viewer, fromUid, targetUid)) {
        viewer.sendMessage("[yellow]同IP账号之间不能认可，避免刷认可/MDC哦！")
        return
    }

    if (MdtStorage.hasRecognitionPair(fromUid, targetUid)) {
        viewer.sendMessage("[yellow]你已经认可过 [white]$targetName[] 了，每个玩家只能认可同一个人一次")
        return
    }

    val today = todayKey()
    if (MdtStorage.hasDailyRecognition(today, fromUid)) {
        viewer.sendMessage("[yellow]你今天已经认可过别人了，明天再来吧！")
        return
    }

    if (!MdtStorage.recordRecognition(today, fromUid, targetUid)) {
        viewer.sendMessage("[yellow]认可失败：你今天已认可过别人，或已认可过该玩家")
        return
    }

    viewer.sendMessage("[green]你认可了 [white]$targetName[green]！")
    onlinePlayerByUid(targetUid)?.sendMessage("[green]你被 [white]${viewer.name}[green] 认可了！")
    emitRecognitionChanged(fromUid, targetUid)
}


command("recognize", "认可一名玩家") {
    usage = "<玩家id/3位id>"
    aliases = listOf("认可")
    attr(ClientOnly)
    body {
        if (arg.isEmpty()) replyUsage()
        val (targetUid, targetName) = resolveRecognitionTarget(arg[0])
        recognizePlayer(player!!, targetUid, targetName)
    }
}

