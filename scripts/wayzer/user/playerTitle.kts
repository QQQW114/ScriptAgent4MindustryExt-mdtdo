@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("coreMindustry/menu", "称号菜单")
@file:Depends("wayzer/user/nameExt", "玩家名字前后缀")

package wayzer.user

import coreMindustry.MenuBuilder
import wayzer.lib.MdtStorage
import wayzer.lib.PlayerData
import wayzer.lib.PlayerTitleChangedEvent

private val GUEST_TITLE_CODE = "guest"
private val TITLES_PER_PAGE = 5
private val MAX_TITLE_CODE_LENGTH = 64
private val MAX_TITLE_DISPLAY_LENGTH = 80

private data class TitleDefinition(
    val code: String,
    val displayName: String,
    val description: String = "",
)

private data class TitleTarget(
    val uid: String,
    val name: String,
    val player: Player?,
)

private val nameExt = contextScript<NameExt>()
private val titleDefinitions = linkedMapOf(
    GUEST_TITLE_CODE to TitleDefinition(
        GUEST_TITLE_CODE,
        "[green][游客][]",
        "未绑定/游客默认称号"
    )
)
private val missingTitleDefinitions = mutableSetOf<String>()
private val ownedTitlesCache = mutableMapOf<String, Set<String>>()
private val equippedTitleCache = mutableMapOf<String, String?>()

private fun normalizeTitleCode(code: String): String? {
    val normalized = code.trim()
    if (normalized.isEmpty() || normalized.length > MAX_TITLE_CODE_LENGTH) return null
    if (normalized.any { it == '\n' || it == '\r' || it == '|' }) return null
    return normalized
}

private fun normalizeTitleDisplay(displayName: String): String {
    val trimmed = displayName.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_TITLE_DISPLAY_LENGTH)
    if (trimmed.isEmpty()) return ""
    // 不含颜色/格式标记时自动补成标准“[称号]”样式，减少后续称号来源重复处理格式。
    return if ('[' !in trimmed && ']' !in trimmed) "[white][$trimmed][]" else trimmed
}

private fun playerUid(player: Player): String = PlayerData[player].id

private fun isGuest(player: Player?): Boolean =
    player != null && !PlayerData[player].authed

private fun titleDefinition(code: String): TitleDefinition? {
    titleDefinitions[code]?.let { return it }
    if (code in missingTitleDefinitions) return null
    val definition = MdtStorage.getTitleDefinition(code)?.let {
        TitleDefinition(it.code, it.displayName, it.description)
    } ?: run {
        missingTitleDefinitions += code
        return null
    }
    titleDefinitions[code] = definition
    return definition
}

private fun titleDisplay(code: String): String =
    titleDefinition(code)?.displayName ?: normalizeTitleDisplay(code)

private fun titleDescription(code: String): String =
    titleDefinition(code)?.description.orEmpty()

private fun titleDisplayCached(code: String): String =
    titleDefinitions[code]?.displayName ?: normalizeTitleDisplay(code)

private fun emitTitleChanged(uids: Set<String>) {
    launch { PlayerTitleChangedEvent(uids).emitAsync() }
}

private fun refreshOnlineNames(uid: String) {
    Groups.player.forEach { player ->
        val data = PlayerData[player]
        if (data.id == uid || uid in data.ids) {
            with(nameExt) { player.updateName() }
        }
    }
}

private fun upsertCustomTitleDefinition(code: String, displayName: String, description: String = ""): Boolean {
    val normalizedDisplay = normalizeTitleDisplay(displayName)
    val normalizedDescription = description.trim()
    val changed = MdtStorage.upsertTitleDefinition(code, normalizedDisplay, normalizedDescription)
    titleDefinitions[code] = TitleDefinition(code, normalizedDisplay, normalizedDescription)
    missingTitleDefinitions -= code
    return changed
}

private fun refreshOnlineNamesByEquippedTitle(code: String) {
    Groups.player.forEach { player ->
        val uid = playerUid(player)
        if (playerEquippedTitleCode(uid, player) == code) {
            with(nameExt) { player.updateName() }
        }
    }
}

fun registerTitleDefinition(code: String, displayName: String, description: String = ""): Boolean {
    val normalized = normalizeTitleCode(code) ?: return false
    titleDefinitions[normalized] = TitleDefinition(
        normalized,
        normalizeTitleDisplay(displayName),
        description.trim()
    )
    missingTitleDefinitions -= normalized
    return true
}

private fun invalidateTitleCache(uid: String) {
    ownedTitlesCache.remove(uid)
    equippedTitleCache.remove(uid)
}

private fun playerTitlePrefixCached(uid: String, player: Player? = null): String? {
    if (isGuest(player)) return titleDisplayCached(GUEST_TITLE_CODE)
    val selected = if (equippedTitleCache.containsKey(uid)) equippedTitleCache[uid] else return null
    if (selected != null && ownedTitlesCache[uid]?.contains(selected) == true) return titleDisplayCached(selected)
    return null
}

private fun prewarmTitleCache(player: Player) {
    val uid = playerUid(player)
    if (isGuest(player)) return
    launch(Dispatchers.IO) {
        val owned = MdtStorage.playerOwnedTitles(uid)
        val equipped = MdtStorage.getEquippedTitle(uid)?.let { normalizeTitleCode(it) }
        val definitions = buildList {
            equipped?.let { add(it) }
            owned.forEach { add(it) }
        }.distinct()
            .filter { it !in titleDefinitions && it !in missingTitleDefinitions }
            .map { code -> code to MdtStorage.getTitleDefinition(code) }
        withContext(Dispatchers.game) {
            ownedTitlesCache[uid] = owned
            equippedTitleCache[uid] = equipped
            definitions.forEach { (code, definition) ->
                if (definition == null) missingTitleDefinitions += code
                else {
                    titleDefinitions[code] = TitleDefinition(definition.code, definition.displayName, definition.description)
                    missingTitleDefinitions -= code
                }
            }
            if (Groups.player.find { it === player } != null) with(nameExt) { player.updateName() }
        }
    }
}

fun playerOwnedTitleCodes(uid: String): Set<String> =
    ownedTitlesCache.getOrPut(uid) { MdtStorage.playerOwnedTitles(uid) }

fun playerAvailableTitleCodes(uid: String, player: Player? = null): List<String> {
    if (isGuest(player)) return listOf(GUEST_TITLE_CODE)
    val result = linkedSetOf<String>()
    result += playerOwnedTitleCodes(uid)
    return result.toList()
}

fun playerEquippedTitleCode(uid: String, player: Player? = null): String? {
    if (isGuest(player)) return GUEST_TITLE_CODE
    val selected = if (equippedTitleCache.containsKey(uid)) {
        equippedTitleCache[uid]
    } else {
        MdtStorage.getEquippedTitle(uid)?.let { normalizeTitleCode(it) }.also {
            equippedTitleCache[uid] = it
        }
    }
    val available = playerAvailableTitleCodes(uid, player)
    if (selected != null && selected in available) return selected
    if (selected != null) {
        MdtStorage.clearEquippedTitle(uid)
        equippedTitleCache[uid] = null
    }
    return null
}

fun playerTitlePrefix(uid: String, player: Player? = null): String? =
    playerEquippedTitleCode(uid, player)?.let { titleDisplay(it) }

fun playerTitleName(uid: String, player: Player? = null): String? =
    playerEquippedTitleCode(uid, player)?.let { titleDisplay(it) }

fun grantTitle(uid: String, code: String, displayName: String = code, description: String = ""): Boolean {
    val normalized = normalizeTitleCode(code) ?: return false
    if (normalized == GUEST_TITLE_CODE) return false

    val dbDefinition = MdtStorage.getTitleDefinition(normalized)
    val shouldUpdateMetadata = normalized !in titleDefinitions &&
            (dbDefinition == null || displayName.trim() != code || description.isNotBlank())
    val metadataChanged = if (shouldUpdateMetadata) upsertCustomTitleDefinition(normalized, displayName, description) else false

    val changed = MdtStorage.grantTitle(uid, normalized, description.ifBlank { "script" })
    if (changed || metadataChanged) {
        invalidateTitleCache(uid)
        emitTitleChanged(setOf(uid))
        refreshOnlineNames(uid)
    }
    if (metadataChanged) refreshOnlineNamesByEquippedTitle(normalized)
    return changed
}

fun revokeTitle(uid: String, code: String): Boolean {
    val normalized = normalizeTitleCode(code) ?: return false
    if (normalized == GUEST_TITLE_CODE) return false

    val changed = MdtStorage.revokeTitle(uid, normalized)
    if (changed) {
        invalidateTitleCache(uid)
        emitTitleChanged(setOf(uid))
        refreshOnlineNames(uid)
    }
    return changed
}

fun clearEquippedTitle(uid: String, player: Player? = null) {
    MdtStorage.clearEquippedTitle(uid)
    equippedTitleCache[uid] = null
    if (player != null) with(nameExt) { player.updateName() }
    else refreshOnlineNames(uid)
    emitTitleChanged(setOf(uid))
}

fun clearEquippedTitle(player: Player) {
    clearEquippedTitle(playerUid(player), player)
}

fun equipTitle(uid: String, code: String, player: Player? = null): Boolean {
    val normalized = normalizeTitleCode(code) ?: return false
    val available = if (player != null) playerAvailableTitleCodes(uid, player) else playerOwnedTitleCodes(uid).toList()
    if (normalized !in available) return false

    MdtStorage.setEquippedTitle(uid, normalized)
    equippedTitleCache[uid] = normalized
    if (player != null) with(nameExt) { player.updateName() }
    else refreshOnlineNames(uid)
    emitTitleChanged(setOf(uid))
    return true
}

fun equipTitle(player: Player, code: String): Boolean {
    return equipTitle(playerUid(player), code, player)
}

fun defineCustomTitle(code: String, displayName: String, description: String = ""): Boolean {
    val normalized = normalizeTitleCode(code) ?: return false
    if (normalized == GUEST_TITLE_CODE) return false
    val changed = upsertCustomTitleDefinition(normalized, displayName, description)
    if (changed) refreshOnlineNamesByEquippedTitle(normalized)
    return true
}

private fun resolveTitleTarget(text: String): TitleTarget {
    val data = PlayerData.findByShortId(text)
    val uid = data?.id ?: text
    val name = data?.player?.name ?: data?.name ?: text
    return TitleTarget(uid, name, data?.player)
}

private fun optionTitle(code: String, currentCode: String?): String {
    val current = if (code == currentCode) "[green]√[]" else "[gray]○[]"
    val description = titleDescription(code)
    return if (description.isBlank()) {
        "$current ${titleDisplay(code)}"
    } else {
        "$current ${titleDisplay(code)}\n[gray]$description"
    }
}

private suspend fun showTitleMenu(player: Player) {
    val uid = playerUid(player)
    var selectedPage = 1

    object : MenuBuilder<Unit>(true) {
        override suspend fun build() {
            val currentCode = playerEquippedTitleCode(uid, player)
            val items = playerAvailableTitleCodes(uid, player)
            val totalPage = maxOf(1, (items.size + TITLES_PER_PAGE - 1) / TITLES_PER_PAGE)
            selectedPage = selectedPage.coerceIn(1, totalPage)
            val start = (selectedPage - 1) * TITLES_PER_PAGE
            val pageItems = items.drop(start).take(TITLES_PER_PAGE)

            title = "[yellow]称号面板"
            msg = """
                |[acid]点击即可佩戴称号。
                |[gray]正式称号与随机形态头衔相互独立；随机形态会额外显示在称号后方。
                |[cyan]当前称号：[white]${playerTitleName(uid, player) ?: "暂无"}
            """.trimMargin()

            val guest = isGuest(player)
            val noTitleTip = if (guest) "\n[gray]未绑定/游客默认仍会显示 [green][游客][]；绑定后此选项才会完全隐藏头衔" else ""
            option(
                if (currentCode == null) "[green]√[] 不佩戴头衔$noTitleTip"
                else "[gray]○[] 不佩戴头衔${if (guest) "（绑定后生效）" else ""}$noTitleTip"
            ) {
                clearEquippedTitle(player)
                player.sendMessage(
                    if (guest) "[green]已取消手动佩戴称号；未绑定/游客默认仍会显示 [green][游客][]"
                    else "[green]已取消佩戴称号"
                )
                refresh()
            }
            newRow()

            pageItems.forEach { code ->
                option(optionTitle(code, currentCode)) {
                    if (equipTitle(player, code)) {
                        player.sendMessage("[green]已佩戴称号：${titleDisplay(code)}")
                    } else {
                        player.sendMessage("[red]你暂未拥有该称号或该称号不可用")
                    }
                    refresh()
                }
                newRow()
            }
            repeat(TITLES_PER_PAGE - pageItems.size) {
                option("") { refresh() }
                newRow()
            }

            option("<-") {
                selectedPage = (selectedPage - 1).coerceAtLeast(1)
                refresh()
            }
            option("$selectedPage/$totalPage") { refresh() }
            option("->") {
                selectedPage = (selectedPage + 1).coerceAtMost(totalPage)
                refresh()
            }
            newRow()
            option("关闭") {}
        }
    }.sendTo(player, 60_000)
}

registerVarForType<Player>().apply {
    registerChild("prefix.1title", "玩家正式称号") {
        playerTitlePrefixCached(playerUid(it), it)
    }
}

listen<EventType.PlayerJoin> {
    prewarmTitleCache(it.player)
}

command("title", "打开称号面板") {
    aliases = listOf("称号", "titles")
    attr(ClientOnly)
    body {
        showTitleMenu(player!!)
    }
}

command("playertitle", "管理指令：查看/授予/撤销玩家称号") {
    usage = "<玩家id/3位id> [list|grant|revoke|equip|clear] [code] [显示名]"
    permission = "wayzer.admin.playerTitle"
    aliases = listOf("titleadmin", "givetitle", "称号管理")
    body {
        if (arg.isEmpty()) replyUsage()

        val target = resolveTitleTarget(arg[0])
        val op = arg.getOrNull(1)?.lowercase() ?: "list"

        when (op) {
            "list", "查看" -> {
                val owned = playerOwnedTitleCodes(target.uid)
                    .joinToString(", ") { "${it}:${titleDisplay(it)}" }
                    .ifBlank { "无" }
                returnReply(
                    """
                        |[cyan]玩家：[white]${target.name}
                        |[cyan]UID：[white]${target.uid}
                        |[cyan]当前称号：[white]${playerTitleName(target.uid, target.player) ?: "暂无"}
                        |[cyan]拥有称号：[white]$owned
                    """.trimMargin().with()
                )
            }
            "grant", "give", "add", "给予", "授予" -> {
                if (arg.size < 3) replyUsage()
                val code = arg[2]
                val display = arg.drop(3).joinToString(" ").ifBlank { code }
                val changed = grantTitle(target.uid, code, display)
                val status = if (changed) "[green]已授予" else "[yellow]目标已拥有或称号代码无效："
                reply("$status [white]${target.name}[] -> ${titleDisplay(normalizeTitleCode(code) ?: code)}".with())
                if (changed) {
                    target.player?.sendMessage("[green]你获得了称号：${titleDisplay(normalizeTitleCode(code) ?: code)}")
                }
            }
            "revoke", "remove", "del", "撤销", "移除" -> {
                if (arg.size < 3) replyUsage()
                val code = arg[2]
                val changed = revokeTitle(target.uid, code)
                val status = if (changed) "[green]已撤销" else "[yellow]目标没有该称号或称号代码无效："
                reply("$status [white]${target.name}[] -> [white]$code".with())
            }
            "equip", "wear", "佩戴" -> {
                if (arg.size < 3) replyUsage()
                if (!equipTitle(target.uid, arg[2], target.player)) {
                    returnReply("[red]目标暂未拥有该称号或称号不可用".with())
                }
                reply("[green]已让 [white]{name}[green] 佩戴称号：{title}".with(
                    "name" to target.name,
                    "title" to titleDisplay(normalizeTitleCode(arg[2]) ?: arg[2])
                ))
            }
            "clear", "none", "off", "取消", "清除" -> {
                clearEquippedTitle(target.uid, target.player)
                reply("[green]已清除 [white]{name}[green] 的佩戴称号".with("name" to target.name))
            }
            else -> replyUsage()
        }
    }
}

command("titledef", "管理指令：定义/更新一个称号显示名") {
    usage = "<code> <显示名>"
    permission = "wayzer.admin.playerTitle"
    aliases = listOf("deftitle", "定义称号")
    body {
        if (arg.size < 2) replyUsage()
        val code = arg[0]
        val display = arg.drop(1).joinToString(" ")
        if (!defineCustomTitle(code, display)) {
            returnReply("[red]称号代码无效或为系统保留代码".with())
        }
        reply("[green]已定义/更新称号：[white]{code}[] -> {display}".with(
            "code" to code,
            "display" to titleDisplay(normalizeTitleCode(code) ?: code)
        ))
    }
}

PermissionApi.registerDefault("wayzer.admin.playerTitle", group = "@admin")

