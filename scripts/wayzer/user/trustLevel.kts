@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")

package wayzer.user

import coreLibrary.lib.PermissionApi
import coreLibrary.lib.event.RequestPermissionEvent
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.ServerTestMode
import wayzer.lib.TrustLevelChangedEvent
import wayzer.lib.TrustLevelLockChangedEvent

/**
 * MDT 信任等级系统。
 *
 * 当前基础规则：
 * - 0级：未绑定/游客
 * - 1级：已完成绑定的玩家
 * - 2级：有一定MDC与被认可度的玩家
 * - 3级：更高活跃度与认可度的玩家
 * - 3+级：位于 3 与 4 之间的高可信+层级
 * - 3++级：人工任命的插件协管，只获得明确白名单权限
 * - 4级：admin 层级；在线 admin 永远视为 4 级
 */

private fun normalizeLevelCode(level: String): String? = when (level.trim().lowercase()) {
    "0" -> "0"
    "1" -> "1"
    "2" -> "2"
    "3" -> "3"
    "3+", "3p", "3plus" -> "3+"
    "3++", "3pp", "3plusplus" -> "3++"
    "4", "admin", "4+admin", "4admin" -> "4"
    else -> null
}

private val manualLevelCache = mutableMapOf<String, String?>()
private val levelLockCache = mutableMapOf<String, Boolean>()

private fun cachedManualLevelCode(uid: String): String? {
    if (!manualLevelCache.containsKey(uid)) {
        manualLevelCache[uid] = MdtStorage.getManualLevelCode(uid)?.let { normalizeLevelCode(it) }
    }
    return manualLevelCache[uid]
}

fun trustLevelOrder(levelCode: String): Int = when (normalizeLevelCode(levelCode) ?: "0") {
    "0" -> 0
    "1" -> 10
    "2" -> 20
    "3" -> 30
    "3+" -> 35
    "3++" -> 38
    "4" -> 40
    else -> 0
}

/** 用于赞踩额度等旧逻辑：3+/3++ 按 3 级处理，4 级按管理员处理。 */
fun trustQuotaLevel(levelCode: String): Int = when (normalizeLevelCode(levelCode) ?: "0") {
    "0" -> 0
    "1" -> 1
    "2" -> 2
    "3", "3+", "3++" -> 3
    "4" -> 4
    else -> 0
}

fun isSessionAuthed(player: Player): Boolean = PlayerData[player].authed

fun getTrustLevelCode(uid: String, player: Player? = null): String {
    // 关键安全边界：只要传入了在线玩家对象，且当前会话没有完成账号认证，
    // 就一律视为 0 级游客。不要因为数据库里这个 UUID/UID 曾经有等级，
    // 也不要因为原生 admin 标记，就让未登录会话继承脚本侧权限。
    player?.let { p ->
        ServerTestMode.getOrNull()?.takeIf { it.isEnabled() && it.isTestSession(p) }?.let { mode ->
            val formalUid = mode.formalUid(p)
            return if (p.admin || formalUid?.let { cachedManualLevelCode(it) == "4" } == true) "4" else "1"
        }
    }
    if (player != null && !isSessionAuthed(player)) return "0"
    if (player?.admin == true) return "4"
    cachedManualLevelCode(uid)?.let { return it }
    return if (player != null) "1" else "0"
}

fun getTrustLevelCode(player: Player): String = getTrustLevelCode(PlayerData[player].id, player)

fun getTrustLevelDisplayCode(uid: String, player: Player? = null): String =
    if (player?.admin == true && isSessionAuthed(player)) "4+admin" else getTrustLevelCode(uid, player)

fun getTrustLevelDisplayCode(player: Player): String = getTrustLevelDisplayCode(PlayerData[player].id, player)

fun getTrustLevel(uid: String, player: Player? = null): Int = trustQuotaLevel(getTrustLevelCode(uid, player))
fun getTrustLevel(player: Player): Int = getTrustLevel(PlayerData[player].id, player)
fun getTrustLevelOrder(uid: String, player: Player? = null): Int = trustLevelOrder(getTrustLevelCode(uid, player))
fun getTrustLevelOrder(player: Player): Int = getTrustLevelOrder(PlayerData[player].id, player)
fun hasTrustLevel(player: Player, requiredLevelCode: String): Boolean =
    getTrustLevelOrder(player) >= trustLevelOrder(requiredLevelCode)
fun isTrustAdmin(player: Player): Boolean = hasTrustLevel(player, "4")
fun isPluginAdmin(player: Player): Boolean = getTrustLevelCode(player) == "3++"
fun isPluginAdminOrHigher(player: Player): Boolean = hasTrustLevel(player, "3++")

/**
 * 分层玩家管理边界：3+ 只能处理低于 3+，3++ 可处理低于 3++，4 级保留全局管理。
 */
fun canModerateTrustTarget(operator: Player, targetUid: String, target: Player? = null): Boolean {
    val operatorUid = PlayerData[operator].id
    if (operatorUid == targetUid || operator === target) return false
    val operatorOrder = getTrustLevelOrder(operatorUid, operator)
    val targetOrder = getTrustLevelOrder(targetUid, target)
    return when {
        operatorOrder >= trustLevelOrder("4") -> true
        operatorOrder >= trustLevelOrder("3++") -> targetOrder < trustLevelOrder("3++")
        operatorOrder >= trustLevelOrder("3+") -> targetOrder < trustLevelOrder("3+")
        else -> false
    }
}

fun canModerateTrustTarget(operator: Player, target: Player): Boolean =
    canModerateTrustTarget(operator, PlayerData[target].id, target)

/**
 * 直接强制观战/禁建的更严格目标边界：
 * 3+ 只能处理 0/1/2 级，3++ 可处理低于 3++，4 级保留全局管理。
 */
fun canDirectRestrictTrustTarget(operator: Player, targetUid: String, target: Player? = null): Boolean {
    val operatorUid = PlayerData[operator].id
    if (operatorUid == targetUid || operator === target) return false
    val operatorOrder = getTrustLevelOrder(operatorUid, operator)
    val targetOrder = getTrustLevelOrder(targetUid, target)
    return when {
        operatorOrder >= trustLevelOrder("4") -> true
        operatorOrder >= trustLevelOrder("3++") -> targetOrder < trustLevelOrder("3++")
        operatorOrder >= trustLevelOrder("3+") -> targetOrder < trustLevelOrder("3")
        else -> false
    }
}

fun canDirectRestrictTrustTarget(operator: Player, target: Player): Boolean =
    canDirectRestrictTrustTarget(operator, PlayerData[target].id, target)

private val pluginAdminMaxBanMinutesConfig by config.key(7 * 24 * 60, "3++协管单次账号/IP封禁最长时间(分钟)")
fun pluginAdminMaxBanMinutes(): Int = pluginAdminMaxBanMinutesConfig.coerceAtLeast(1)

fun setTrustLevel(uid: String, levelCode: String): String {
    val normalized = normalizeLevelCode(levelCode) ?: error("Invalid trust level: $levelCode")
    MdtStorage.setManualLevelCode(uid, normalized)
    manualLevelCache[uid] = normalized
    return normalized
}

fun setTrustLevel(uid: String, level: Int): String = setTrustLevel(uid, level.toString())

fun emitTrustLevelChanged(uid: String, oldLevel: String, newLevel: String) {
    if (oldLevel == newLevel) return
    launch { TrustLevelChangedEvent(uid, oldLevel, newLevel).emitAsync() }
}

private fun emitTrustLevelDisplayChanged(uid: String, oldLevel: String, newLevel: String) {
    if (oldLevel == newLevel) return
    launch { TrustLevelChangedEvent(uid, oldLevel, newLevel).emitAsync() }
}

fun isTrustLevelLocked(uid: String): Boolean =
    levelLockCache.getOrPut(uid) { MdtStorage.isTrustLevelLocked(uid) }

fun setTrustLevelLocked(uid: String, locked: Boolean): Boolean {
    MdtStorage.setTrustLevelLocked(uid, locked)
    levelLockCache[uid] = locked
    launch { TrustLevelLockChangedEvent(setOf(uid)).emitAsync() }
    return locked
}

fun toggleTrustLevelLocked(uid: String): Boolean = setTrustLevelLocked(uid, !isTrustLevelLocked(uid))

fun trustLevelName(levelCode: String): String = when (normalizeLevelCode(levelCode) ?: "0") {
    "0" -> "游客"
    "1" -> "已绑定"
    "2" -> "可信"
    "3" -> "高可信"
    "3+" -> "高可信+"
    "3++" -> "插件协管"
    "4" -> if (levelCode.trim().lowercase().contains("admin")) "管理员+admin" else "管理员"
    else -> "未知"
}

fun trustLevelName(level: Int): String = trustLevelName(level.toString())

private data class LevelTarget(
    val uid: String,
    val name: String,
    val player: Player?,
)

private fun resolveLevelTarget(text: String): LevelTarget {
    val data = PlayerData.findByShortId(text)
    val uid = data?.id ?: text
    val name = data?.player?.name ?: data?.name ?: text
    return LevelTarget(uid, name, data?.player)
}

private fun canSetLevel(operator: Player?): Boolean {
    if (operator == null) return true // 控制台
    return isTrustAdmin(operator)
}

private fun resolveOnlineLevelTarget(text: String): Player? {
    val fixed = text.trim()
    if (fixed.isEmpty()) return null
    PlayerData.findByShortId(fixed)?.player?.let { return it }
    if (fixed.startsWith("#")) {
        Groups.player.getByID(fixed.substring(1).toIntOrNull() ?: -1)?.let { return it }
    }
    val plain = fixed.replace(" ", "")
    return Groups.player.find {
        it.uuid() == fixed ||
                PlayerData[it].id == fixed ||
                PlayerData[it].shortId.equals(fixed, ignoreCase = true) ||
                it.name.equals(fixed, ignoreCase = true) ||
                it.plainName().equals(fixed, ignoreCase = true) ||
                it.name.replace(" ", "").equals(plain, ignoreCase = true)
    }
}

private fun setNativeAdmin(target: Player, admin: Boolean): Boolean {
    val uid = PlayerData[target].id
    val oldDisplay = getTrustLevelDisplayCode(uid, target)
    val oldNative = target.admin
    val changed = if (admin) {
        netServer.admins.adminPlayer(target.uuid(), target.usid())
    } else {
        netServer.admins.unAdminPlayer(target.uuid())
    }
    target.admin = admin
    netServer.admins.forceSave()
    val newDisplay = getTrustLevelDisplayCode(uid, target)
    emitTrustLevelDisplayChanged(uid, oldDisplay, newDisplay)
    return changed || oldNative != admin
}

// 4级玩家会在权限事件中加入 @admin。这里补齐 @admin 的通配权限，避免部分管理指令
// 只设置了 permission/requirePermission 但没有单独 registerDefault 时，4级仍无法使用。
PermissionApi.registerDefault(
    "scriptAgent.*",
    "coreLibrary.*",
    "coreMindustry.*",
    "wayzer.*",
    "mapScript.*",
    group = "@admin",
)

// 3++ 不进入 @admin，只获得这里明确列出的协管白名单。
PermissionApi.registerDefault(
    "suffix.staffMark",
    "wayzer.admin.skipKick",
    "wayzer.maps.host",
    "wayzer.maps.gameover",
    "wayzer.admin.ban",
    "wayzer.admin.unban",
    "wayzer.admin.banList",
    "wayzer.admin.banIp",
    "wayzer.admin.forceOb",
    "wayzer.admin.recentPlayers",
    "wayzer.admin.forceObClean",
    "wayzer.admin.logicDraw",
    "wayzer.admin.blockBan",
    "wayzer.staff.musicControl",
    group = "@pluginAdmin",
)

listenTo<RequestPermissionEvent>(Event.Priority.After) {
    val p = subject as? Player ?: return@listenTo
    if (!isSessionAuthed(p)) {
        // coreMindustry 的 Player.hasPermission 默认会把 player.uuid() 与原生 @admin 放入权限组。
        // wayzer/module 还会把 PlayerData.ids 中的历史主体 id 加入权限组。
        // 账号系统上线后，未登录会话必须按游客处理，否则只要伪装/沿用相同 UUID 就可能继承脚本权限。
        val blockedGroups = PlayerData[p].ids + p.uuid() + "@admin"
        group = group.filterNot { it in blockedGroups }
        directReturn = null
        return@listenTo
    }
    val levelCode = getTrustLevelCode(p)
    if (levelCode == "3++" && "@pluginAdmin" !in group) {
        group += "@pluginAdmin"
    }
    if (levelCode == "4" && "@admin" !in group) {
        group += "@admin"
    }
    if (levelCode == "4") {
        directReturn = null
    }
}

command("setlevel", "管理指令：设置玩家信任等级") {
    usage = "<uuid/3位id> <0|1|2|3|3+|3++|4>"
    permission = "wayzer.admin.trustLevel"
    body {
        if (!canSetLevel(player)) {
            returnReply("[red]权限不足：只有4级用户或admin可以修改玩家等级".with())
        }
        if (arg.size < 2) replyUsage()

        val target = resolveLevelTarget(arg[0])
        val level = normalizeLevelCode(arg[1]) ?: replyUsage()
        val oldLevel = getTrustLevelCode(target.uid, target.player)
        setTrustLevel(target.uid, level)
        val newLevel = getTrustLevelCode(target.uid, target.player)
        if (oldLevel != newLevel) {
            emitTrustLevelChanged(target.uid, oldLevel, newLevel)
            if (trustLevelOrder(newLevel) > trustLevelOrder(oldLevel)) {
                broadcast("[gold]恭喜[white]${target.name}[gold]升级到了[yellow]${newLevel}级[gold]！".with())
            } else {
                broadcast("[yellow]${target.name} 的等级降为了[orange]${newLevel}级[yellow]！".with())
            }
        }

        reply(
            "[green]已设置 [white]{name}[green] 的信任等级：[yellow]{old}[green] -> [yellow]{new} [gray]({levelName})"
                .with(
                    "name" to target.name,
                    "old" to oldLevel,
                    "new" to newLevel,
                    "levelName" to trustLevelName(newLevel)
                )
        )
    }
}

command("locklevel", "管理指令：锁定/解除玩家信任等级自动调整") {
    usage = "<uuid/3位id> [on|off|toggle]"
    aliases = listOf("levellock", "等级锁", "锁等级")
    permission = "wayzer.admin.trustLevel"
    body {
        if (!canSetLevel(player)) {
            returnReply("[red]权限不足：只有4级用户或admin可以锁定玩家等级".with())
        }
        if (arg.isEmpty()) replyUsage()

        val target = resolveLevelTarget(arg[0])
        val locked = when (arg.getOrNull(1)?.lowercase()) {
            null, "toggle", "切换" -> toggleTrustLevelLocked(target.uid)
            "on", "true", "1", "lock", "locked", "开", "开启", "锁定" -> setTrustLevelLocked(target.uid, true)
            "off", "false", "0", "unlock", "unlocked", "关", "关闭", "解锁" -> setTrustLevelLocked(target.uid, false)
            else -> replyUsage()
        }

        reply(
            if (locked) {
                "[green]已锁定 [white]{name}[green] 的信任等级：[yellow]{level}[green]，晋升系统将不再调整此玩家等级"
            } else {
                "[green]已解除 [white]{name}[green] 的信任等级锁定，晋升系统将在下一次检测时恢复控制"
            }.with("name" to target.name, "level" to getTrustLevelCode(target.uid, target.player))
        )
    }
}

command("setadmin", "管理指令：设置/取消玩家原生管理员") {
    usage = "<在线玩家id/3位id/#id/名字> [on|off|toggle]"
    aliases = listOf("adminplayer", "op", "设管理")
    permission = "wayzer.admin.trustLevel"
    body {
        if (!canSetLevel(player)) {
            returnReply("[red]权限不足：只有4级用户或admin可以设置原生管理员".with())
        }
        if (arg.isEmpty()) replyUsage()

        val target = resolveOnlineLevelTarget(arg[0])
            ?: returnReply("[red]未找到在线玩家；原生admin需要在线玩家的UUID与USID".with())
        val oldNative = target.admin
        val admin = when (arg.getOrNull(1)?.lowercase()) {
            null, "toggle", "切换" -> !oldNative
            "on", "true", "1", "admin", "op", "开", "开启", "设置" -> true
            "off", "false", "0", "unadmin", "deop", "关", "关闭", "取消" -> false
            else -> replyUsage()
        }

        val changed = setNativeAdmin(target, admin)
        val levelText = getTrustLevelDisplayCode(target)
        val message = (if (admin) "[green]已将 [white]{name}[green] 设为原生管理员"
        else "[green]已取消 [white]{name}[green] 的原生管理员") +
                " [gray](等级显示: [yellow]{level}[gray], changed={changed})"
        reply(message.with("name" to target.name, "level" to levelText, "changed" to changed))
        target.sendMessage(
            if (admin) "[green]你已被设置为原生管理员，等级显示为 [yellow]$levelText"
            else "[yellow]你的原生管理员已被取消，当前等级显示为 [yellow]$levelText"
        )
    }
}

registerVarForType<Player>().apply {
    registerChild("trustLevel", "MDT信任等级") { getTrustLevelDisplayCode(it) }
    registerChild("trustLevelCode", "MDT信任等级代码(不含原生admin标记)") { getTrustLevelCode(it) }
    registerChild("trustLevelQuota", "MDT信任等级数值(3+/3++按3级处理)") { getTrustLevel(it) }
    registerChild("trustLevelName", "MDT信任等级名称") { trustLevelName(getTrustLevelDisplayCode(it)) }
    registerChild("trustLevelLocked", "MDT信任等级是否锁定") { isTrustLevelLocked(PlayerData[it].id) }
}

