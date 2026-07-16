@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.user

import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.ServerTestMode
import wayzer.lib.TrustPointChangedEvent
import java.time.Duration
import java.time.Instant

private val CREDIT_SHORT_NAME = "MDC"
private val CREDIT_FULL_NAME = "MDT DO Credit"
private val RED_PACKET_EXPIRE_MILLIS = 10 * 60_000L
private val MAX_RED_PACKET_SHARES = 50
private val MAX_RED_PACKET_TOTAL = 500
private val notifyMdcChange by config.key(true, "MDC变化时是否给在线玩家发送简短提示")

private fun credit(amount: Int): String = "$amount $CREDIT_SHORT_NAME"
private fun creditName(): String = "$CREDIT_SHORT_NAME（$CREDIT_FULL_NAME）"

private val currentPointCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

private fun testModeFor(uid: String): ServerTestMode? =
    ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.ownsUid(uid) }

private fun isServerTestModeEnabled(): Boolean = ServerTestMode.getOrNull()?.isEnabled() == true
private fun activeTestMode(): ServerTestMode? = ServerTestMode.getOrNull()?.takeIf { it.isEnabled() }
private fun blockNormalMdcInTestMode(uid: String): Boolean =
    activeTestMode()?.let { !it.ownsUid(uid) } == true

fun getTrustPoints(uid: String): Int = when {
    testModeFor(uid) != null -> testModeFor(uid)!!.getTrustPoints(uid)
    blockNormalMdcInTestMode(uid) -> 0
    else -> MdtStorage.getTrustPoints(uid)
}

fun getTotalTrustPoints(uid: String): Int = when {
    testModeFor(uid) != null -> testModeFor(uid)!!.getTotalTrustPoints(uid)
    blockNormalMdcInTestMode(uid) -> 0
    else -> MdtStorage.getTotalTrustPoints(uid)
}

fun getCachedTrustPoints(uid: String): Int =
    testModeFor(uid)?.getTrustPoints(uid)
        ?: if (blockNormalMdcInTestMode(uid)) 0 else currentPointCache.getOrPut(uid) { MdtStorage.getTrustPoints(uid) }

private fun emitTrustPointChanged(uid: String) {
    launch { TrustPointChangedEvent(setOf(uid)).emitAsync() }
}

private fun emitTrustPointChanged(uids: Set<String>) {
    if (uids.isNotEmpty()) launch { TrustPointChangedEvent(uids).emitAsync() }
}

private fun onlinePlayerByUid(uid: String): Player? =
    Groups.player.find { PlayerData[it].id == uid }

private fun mdcChangeReason(desc: String, amount: Int): String {
    val fixed = desc.trim()
    return when {
        fixed.isBlank() -> if (amount >= 0) "MDC获得" else "MDC消耗"
        fixed == "admin" -> "管理员调整"
        fixed == "transfer" -> "转账"
        fixed == "GameContribution" -> "本局贡献"
        fixed.startsWith("RewardBatch") -> "奖励"
        fixed.startsWith("Achievement:") -> "成就奖励"
        fixed.startsWith("Skill:") -> if (amount >= 0) "技能奖励" else "技能消耗"
        fixed.startsWith("Shop:") -> "商店购买"
        fixed.startsWith("RedPacket:send") -> "红包发送"
        fixed.startsWith("RedPacket:grab") -> "红包领取"
        fixed.startsWith("RedPacket:return") -> "红包退回"
        else -> fixed.take(24)
    }
}

private fun notifyMdcChanged(uid: String, amount: Int, desc: String, newPoints: Int? = null) {
    if (!notifyMdcChange || amount == 0) return
    // 本局贡献结算已有更完整的个人结算提示与全服排行，避免重复刷屏。
    if (desc == "GameContribution") return
    val sign = if (amount > 0) "+" else ""
    val color = if (amount > 0) "[green]" else "[red]"
    val balance = newPoints?.let { "[gray] 当前：[white]$it" } ?: ""
    val message = "[gold]MDC$color$sign$amount[gray]（${mdcChangeReason(desc, amount)}）$balance"
    launch(Dispatchers.game) {
        onlinePlayerByUid(uid)?.sendMessage(message)
    }
}

private fun setCachedTrustPoints(uid: String, value: Int) {
    if (testModeFor(uid) != null) return
    currentPointCache[uid] = value
}

fun addTrustPoints(uid: String, amount: Int, desc: String = ""): Int {
    if (amount == 0) return getTrustPoints(uid)
    if (blockNormalMdcInTestMode(uid)) return 0
    val oldPoints = getCachedTrustPoints(uid)
    val newPoints = testModeFor(uid)?.addTrustPoints(uid, amount) ?: MdtStorage.addTrustPoints(uid, amount)
    setCachedTrustPoints(uid, newPoints)
    notifyMdcChanged(uid, newPoints - oldPoints, desc, newPoints)
    emitTrustPointChanged(uid)
    return newPoints
}

fun addTrustPointsBatch(rewards: Map<String, Int>, desc: String = ""): Map<String, Int> {
    val fixed = rewards.filterValues { it > 0 }
    if (fixed.isEmpty()) return emptyMap()
    val testMode = ServerTestMode.getOrNull()?.takeIf { it.isEnabled() }
    val testRewards = if (testMode != null) fixed.filterKeys { testMode.ownsUid(it) } else emptyMap()
    val normalRewards = if (testMode != null) emptyMap() else fixed
    val newPoints = linkedMapOf<String, Int>()
    if (normalRewards.isNotEmpty()) newPoints += MdtStorage.addTrustPointsBatch(normalRewards)
    if (testMode != null && testRewards.isNotEmpty()) {
        testRewards.forEach { (uid, amount) ->
            newPoints[uid] = testMode.addTrustPoints(uid, amount)
        }
    }
    newPoints.forEach { (uid, newValue) ->
        setCachedTrustPoints(uid, newValue)
        notifyMdcChanged(uid, fixed[uid] ?: 0, desc, newValue)
    }
    emitTrustPointChanged(newPoints.keys)
    return newPoints
}

fun addCurrentTrustPoints(uid: String, amount: Int, desc: String = ""): Int {
    if (amount == 0) return getTrustPoints(uid)
    if (blockNormalMdcInTestMode(uid)) return 0
    val oldPoints = getCachedTrustPoints(uid)
    val newPoints = testModeFor(uid)?.addCurrentTrustPoints(uid, amount) ?: MdtStorage.addCurrentTrustPoints(uid, amount)
    setCachedTrustPoints(uid, newPoints)
    notifyMdcChanged(uid, newPoints - oldPoints, desc, newPoints)
    emitTrustPointChanged(uid)
    return newPoints
}

fun spendTrustPoints(uid: String, amount: Int, desc: String = ""): Boolean {
    if (amount <= 0) return true
    if (blockNormalMdcInTestMode(uid)) return false
    val oldPoints = getCachedTrustPoints(uid)
    val success = testModeFor(uid)?.spendTrustPoints(uid, amount) ?: MdtStorage.spendTrustPoints(uid, amount)
    if (success) {
        val newPoints = (oldPoints - amount).coerceAtLeast(0)
        setCachedTrustPoints(uid, newPoints)
        notifyMdcChanged(uid, -amount, desc, newPoints)
        emitTrustPointChanged(uid)
    }
    return success
}

fun transferTrustPoints(fromUid: String, toUid: String, amount: Int, desc: String = ""): Boolean {
    if (amount <= 0 || fromUid == toUid) return false
    if (blockNormalMdcInTestMode(fromUid) || blockNormalMdcInTestMode(toUid)) return false
    val oldFrom = getCachedTrustPoints(fromUid)
    val oldTo = getCachedTrustPoints(toUid)
    val fromTest = testModeFor(fromUid)
    val toTest = testModeFor(toUid)
    val success = when {
        fromTest != null && toTest != null && fromTest === toTest -> fromTest.transferTrustPoints(fromUid, toUid, amount)
        fromTest != null || toTest != null -> false
        else -> MdtStorage.transferTrustPoints(fromUid, toUid, amount)
    }
    if (success) {
        val newFrom = (oldFrom - amount).coerceAtLeast(0)
        val newTo = oldTo + amount
        setCachedTrustPoints(fromUid, newFrom)
        setCachedTrustPoints(toUid, newTo)
        notifyMdcChanged(fromUid, -amount, desc, newFrom)
        notifyMdcChanged(toUid, amount, desc, newTo)
        emitTrustPointChanged(fromUid)
        emitTrustPointChanged(toUid)
    }
    return success
}

fun setTrustPoints(uid: String, value: Int): Int {
    if (blockNormalMdcInTestMode(uid)) return 0
    val oldPoints = getCachedTrustPoints(uid)
    val newValue = testModeFor(uid)?.setTrustPoints(uid, value) ?: MdtStorage.setTrustPoints(uid, value)
    setCachedTrustPoints(uid, newValue)
    notifyMdcChanged(uid, newValue - oldPoints, "admin", newValue)
    emitTrustPointChanged(uid)
    return newValue
}

fun setTotalTrustPoints(uid: String, value: Int): Int {
    if (blockNormalMdcInTestMode(uid)) return 0
    val newValue = testModeFor(uid)?.setTotalTrustPoints(uid, value) ?: MdtStorage.setTotalTrustPoints(uid, value)
    emitTrustPointChanged(uid)
    return newValue
}

private fun resolvePointTarget(text: String): Pair<String, String> {
    val data = PlayerData.findByShortId(text)
    val uid = data?.id ?: text
    val name = data?.player?.name ?: data?.name ?: text
    return uid to name
}

private fun resolveKnownPlayerData(text: String): PlayerData? = PlayerData.findByShortId(text.trim())

private fun displayName(data: PlayerData): String = data.player?.name ?: data.name

private fun positiveIntOrNull(text: String): Int? = text.toIntOrNull()?.takeIf { it > 0 }

private fun expireRedPacketsAndNotify() {
    if (isServerTestModeEnabled()) return
    val expired = MdtStorage.expireRedPackets()
    expired.forEach { packet ->
        if (packet.remainingAmount > 0) {
            val newPoints = getTrustPoints(packet.senderUid)
            setCachedTrustPoints(packet.senderUid, newPoints)
            notifyMdcChanged(packet.senderUid, packet.remainingAmount, "RedPacket:return", newPoints)
            emitTrustPointChanged(packet.senderUid)
            onlinePlayerByUid(packet.senderUid)?.sendMessage(
                "[yellow]红包 #[white]${packet.id}[yellow] 已过期，退回 [white]${credit(packet.remainingAmount)}"
            )
        }
    }
}

private fun redPacketBrief(packet: MdtStorage.RedPacketRecord): String =
    "#${packet.id} ${packet.senderName} ${packet.remainingShares}/${packet.totalShares}份 ${credit(packet.remainingAmount)}/${credit(packet.totalAmount)} ${packet.status}"

private fun redPacketSettlementMessage(packetId: Int, claims: List<MdtStorage.RedPacketClaimRecord>): String {
    if (claims.isEmpty()) return "[gold]红包 #[white]$packetId[gold] 已结算，但没有领取记录。"
    val claimList = claims.sortedBy { it.claimedAt }
        .joinToString("，") { "[white]${it.claimerName}[gray](${credit(it.amount)})" }
        .take(240)
    val ranking = claims.sortedWith(
        compareByDescending<MdtStorage.RedPacketClaimRecord> { it.amount }
            .thenBy { it.claimedAt }
    )
    val king = ranking.first()
    val rankText = ranking.take(5).mapIndexed { index, claim ->
        "[white]${index + 1}.[gold]${claim.claimerName}[gray](${credit(claim.amount)})"
    }.joinToString("  ")
    return """
        |[gold]红包 #[white]$packetId[gold] 已被抢完！
        |[cyan]领奖者：[gray]$claimList
        |[orange]手气王：[white]${king.claimerName}[orange] 抢到 [gold]${credit(king.amount)}
        |[gray]手气榜：$rankText
    """.trimMargin()
}

onEnable {
    launch {
        while (true) {
            delay(60_000)
            expireRedPacketsAndNotify()
        }
    }
}

command("points", "查看自己的MDC余额") {
    aliases = listOf("积分", "mdc", "credit")
    attr(ClientOnly)
    body {
        val uid = PlayerData[player!!].id
        val redPacketText = if (isServerTestModeEnabled()) {
            "[gray]服务器测试模式下红包功能暂时关闭，避免写入正式红包数据库。"
        } else {
            "[gray]红包：/redpacket <总MDC> <份数> [留言]；/grab <红包ID>"
        }
        reply(
            """
                |[cyan]货币：[white]${creditName()}
                |[cyan]当前MDC：[white]${getTrustPoints(uid)}
                |[cyan]累计MDC：[white]${getTotalTrustPoints(uid)}
                |[gray]转账：/pay <玩家3位ID> <数量> [留言]
                |$redPacketText
            """.trimMargin().with()
        )
    }
}

command("pay", "向其他玩家转账MDC") {
    usage = "<玩家id/3位id> <数量> [留言]"
    aliases = listOf("transfer", "转账", "付款", "mdcpay")
    attr(ClientOnly)
    body {
        if (arg.size < 2) replyUsage()
        val sender = player!!
        val senderData = PlayerData[sender]
        val targetData = resolveKnownPlayerData(arg[0])
            ?: returnReply("[red]未找到目标玩家，请使用在线/最近玩家的3位ID或UUID".with())
        val amount = positiveIntOrNull(arg[1]) ?: returnReply("[red]数量必须是正整数".with())
        if (senderData.id == targetData.id) returnReply("[red]不能给自己转账".with())
        if (getTrustPoints(senderData.id) < amount) {
            returnReply("[red]MDC不足：需要 ${credit(amount)}，当前 ${credit(getTrustPoints(senderData.id))}".with())
        }
        if (!transferTrustPoints(senderData.id, targetData.id, amount, "transfer")) {
            returnReply("[red]转账失败，请确认余额和目标玩家".with())
        }
        val note = arg.drop(2).joinToString(" ").trim()
        sender.sendMessage("[green]已向 [white]${displayName(targetData)}[green] 转账 [yellow]${credit(amount)}")
        targetData.player?.sendMessage(
            "[green]你收到了 [white]${sender.name}[green] 的转账：[yellow]${credit(amount)}" +
                    if (note.isBlank()) "" else "\n[gray]留言：$note"
        )
    }
}

command("redpacket", "发MDC红包") {
    usage = "<总MDC> <份数> [留言]"
    aliases = listOf("hongbao", "hb", "红包", "发红包")
    attr(ClientOnly)
    body {
        if (isServerTestModeEnabled()) {
            returnReply("[yellow]服务器测试模式下红包功能暂时关闭，避免临时MDC写入正式红包数据库。".with())
        }
        if (arg.size < 2) replyUsage()
        val sender = player!!
        val senderUid = PlayerData[sender].id
        val total = positiveIntOrNull(arg[0]) ?: returnReply("[red]总MDC必须是正整数".with())
        val shares = positiveIntOrNull(arg[1]) ?: returnReply("[red]份数必须是正整数".with())
        if (total > MAX_RED_PACKET_TOTAL) returnReply("[red]单个红包最多 ${credit(MAX_RED_PACKET_TOTAL)}".with())
        if (shares > MAX_RED_PACKET_SHARES) returnReply("[red]单个红包最多 ${MAX_RED_PACKET_SHARES} 份".with())
        if (total < shares) returnReply("[red]总MDC不能小于份数，否则无法保证每份至少 1 MDC".with())
        if (getTrustPoints(senderUid) < total) {
            returnReply("[red]MDC不足：需要 ${credit(total)}，当前 ${credit(getTrustPoints(senderUid))}".with())
        }
        val message = arg.drop(2).joinToString(" ").trim().ifBlank { "恭喜发财，大吉大利" }
        val expireAt = Instant.now().plusMillis(RED_PACKET_EXPIRE_MILLIS)
        val packet = MdtStorage.createRedPacket(senderUid, sender.plainName(), total, shares, message, expireAt)
            ?: returnReply("[red]红包创建失败，请确认MDC余额、数量和份数".with())
        val newPoints = getTrustPoints(senderUid)
        setCachedTrustPoints(senderUid, newPoints)
        notifyMdcChanged(senderUid, -total, "RedPacket:send", newPoints)
        emitTrustPointChanged(senderUid)
        broadcast(
            """
                |[gold]${sender.name}[yellow] 发了一个 MDC 红包！
                |[cyan]编号：[white]#${packet.id}  [cyan]总额：[white]${credit(total)}  [cyan]份数：[white]$shares
                |[gray]留言：${packet.message}
                |[green]输入 [white]/grab ${packet.id}[green] 抢红包，10分钟后未抢完自动退回。
            """.trimMargin().with()
        )
    }
}

command("grab", "抢MDC红包") {
    usage = "<红包ID>"
    aliases = listOf("抢红包", "开红包", "抢", "grabredpacket")
    attr(ClientOnly)
    body {
        if (isServerTestModeEnabled()) {
            returnReply("[yellow]服务器测试模式下红包功能暂时关闭。".with())
        }
        expireRedPacketsAndNotify()
        if (arg.isEmpty()) {
            val packets = MdtStorage.listRedPackets(8, includeClosed = false)
            if (packets.isEmpty()) returnReply("[yellow]当前没有可抢的红包".with())
            returnReply(
                packets.joinToString("\n", prefix = "[cyan]当前可抢红包：\n") {
                    "[white]${redPacketBrief(it)} [gray]/grab ${it.id}"
                }.with()
            )
        }
        val id = arg[0].removePrefix("#").toIntOrNull() ?: returnReply("[red]红包ID必须是数字".with())
        val p = player!!
        val data = PlayerData[p]
        if (!data.authed) {
            returnReply("[yellow]游客不能抢红包，请先使用 [gold]/login[] 登录或 [gold]/register[] 注册。".with())
        }
        val result = MdtStorage.claimRedPacket(id, data.id, p.plainName())
        val claim = result.claim
        val packet = result.packet
        when {
            result.success && claim != null && packet != null -> {
                val newPoints = getTrustPoints(data.id)
                setCachedTrustPoints(data.id, newPoints)
                notifyMdcChanged(data.id, claim.amount, "RedPacket:grab", newPoints)
                emitTrustPointChanged(data.id)
                p.sendMessage("[green]你抢到了红包 #[white]$id[green]：[yellow]${credit(claim.amount)}")
                if (packet.status == "finished") {
                    val claims = MdtStorage.listRedPacketClaims(id)
                    broadcast(redPacketSettlementMessage(id, claims).with())
                }
            }
            result.reason == "self" -> p.sendMessage("[yellow]不能抢自己发的红包哦")
            result.reason == "claimed" -> p.sendMessage("[yellow]你已经抢过这个红包了")
            result.reason == "not_found" -> p.sendMessage("[red]红包不存在：#$id")
            result.reason == "expired" -> p.sendMessage("[yellow]红包已过期")
            result.reason == "finished" || result.reason == "empty" -> p.sendMessage("[yellow]红包已经被抢完了")
            else -> p.sendMessage("[red]抢红包失败：${result.reason}")
        }
    }
}

command("redpackets", "查看MDC红包列表") {
    aliases = listOf("红包列表", "hblist")
    body {
        if (isServerTestModeEnabled()) {
            returnReply("[yellow]服务器测试模式下红包功能暂时关闭。".with())
        }
        expireRedPacketsAndNotify()
        val includeClosed = arg.firstOrNull()?.lowercase() in setOf("all", "全部")
        val packets = MdtStorage.listRedPackets(12, includeClosed = includeClosed)
        if (packets.isEmpty()) returnReply("[yellow]当前没有红包记录".with())
        reply(
            packets.joinToString("\n", prefix = "[cyan]红包列表：\n") {
                "[white]${redPacketBrief(it)}"
            }.with()
        )
    }
}

command("trustpoint", "管理指令：查看/修改玩家MDC") {
    usage = "<玩家id/3位id> [set|add|spend|totalSet|totalAdd] [数量]"
    permission = "wayzer.admin.trustPoint"
    aliases = listOf("tpoint", "改积分", "改mdc")
    body {
        if (player != null && !player!!.hasPermission("wayzer.admin.trustPoint")) {
            returnReply("[red]权限不足：需要MDC管理权限".with())
        }
        if (arg.isEmpty()) replyUsage()
        val (targetUid, targetName) = resolvePointTarget(arg[0])
        if (arg.size == 1) {
            returnReply(
                """
                    |[cyan]玩家：[white]$targetName
                    |[cyan]UID：[white]$targetUid
                    |[cyan]当前MDC：[white]${getTrustPoints(targetUid)}
                    |[cyan]累计MDC：[white]${getTotalTrustPoints(targetUid)}
                """.trimMargin().with()
            )
        }
        if (arg.size < 3) replyUsage()
        val op = arg[1].lowercase()
        val value = arg[2].toIntOrNull() ?: replyUsage()
        val oldCurrent = getTrustPoints(targetUid)
        val oldTotal = getTotalTrustPoints(targetUid)
        when (op) {
            "set", "设置" -> setTrustPoints(targetUid, value)
            "add", "增加", "加" -> addTrustPoints(targetUid, value, "admin")
            "spend", "消费", "扣" -> addTrustPoints(targetUid, -value, "admin")
            "totalset", "settotal", "累计设置" -> setTotalTrustPoints(targetUid, value)
            "totaladd", "addtotal", "累计增加" -> setTotalTrustPoints(targetUid, oldTotal + value)
            else -> returnReply("[red]操作错误，请使用 set/add/spend/totalSet/totalAdd".with())
        }
        reply(
            """
                |[green]已修改 [white]$targetName[green] 的MDC
                |[cyan]当前MDC：[yellow]$oldCurrent[green] -> [yellow]${getTrustPoints(targetUid)}
                |[cyan]累计MDC：[yellow]$oldTotal[green] -> [yellow]${getTotalTrustPoints(targetUid)}
            """.trimMargin().with()
        )
    }
}

PermissionApi.registerDefault("wayzer.admin.trustPoint", group = "@admin")

registerVarForType<Player>().apply {
    registerChild("mdc", "当前MDC") { getCachedTrustPoints(PlayerData[it].id) }
    registerChild("mdcTotal", "累计MDC") { getTotalTrustPoints(PlayerData[it].id) }
}

listen<EventType.PlayerLeave> {
    currentPointCache.remove(it.player.uuid())
    currentPointCache.remove(PlayerData[it.player].id)
}
