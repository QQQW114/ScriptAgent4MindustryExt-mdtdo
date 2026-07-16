package wayzer.user

import arc.util.Strings
import cf.wayzer.placehold.PlaceHoldApi
import cf.wayzer.placehold.TypeBinder
import wayzer.lib.PlayerData

@Savable(serializable = false)
val realName = mutableMapOf<String, String>()
customLoad(::realName) { realName.putAll(it) }

val TypeBinder<*>.tree: Map<String, Any> by reflectDelegate()

registerVarForType<Player>().apply {
    registerChild("prefix", "名字前缀,可通过prefix.xxx变量注册") { p ->
        PlaceHoldApi.typeBinder<Player>().run {
            val keys = tree.keys.filter { it.startsWith("prefix.") }.sorted()
            keys.joinToString("") { k ->
                resolve(this@registerChild, p, k)?.let { isolateNamePart(resolveVarForString(it)) }.orEmpty()
            }
        }
    }
    registerChild("suffix", "名字后缀,可通过suffix.xxx变量注册") { p ->
        PlaceHoldApi.typeBinder<Player>().run {
            val keys = tree.keys.filter { it.startsWith("suffix.") }.sorted()
            keys.joinToString("") { k ->
                resolve(this@registerChild, p, k)?.let { isolateNamePart(resolveVarForString(it)) }.orEmpty()
            }
        }
    }
}

private val generatedTitlePrefixRegex = Regex("""^\s*(?:\[[^\[\]\r\n]{1,24}\]\s*)+""")
private val brokenColorTagRegex = Regex("""\[[A-Za-z0-9_#.+\-]{1,32}$""")

private fun isolateNamePart(raw: String?): String {
    val fixed = raw
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        .orEmpty()
    if (fixed.isBlank()) return ""
    // 各前/后缀来源可能包含 [green]/[white] 但没有显式恢复默认色。
    // 用 [white] 隔离每一个片段，避免上一段颜色污染下一段或玩家名字。
    return "$fixed[white]"
}

private fun sanitizeRealName(raw: String?): String {
    var fixed = (raw ?: "NotInit")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
    // 保留玩家自己名字里的合法颜色标记；只清掉末尾残缺颜色标签，避免聊天栏后续内容被染色。
    fixed = brokenColorTagRegex.replace(fixed, "").trim()
    return fixed.ifBlank { "NotInit" }
}

private fun sanitizeFallbackName(raw: String?): String {
    val fixed = sanitizeRealName(raw)
    // 只有在取不到连接包/PlayerData 原名时才使用当前 Player.name 兜底；
    // 当前名字可能已经被本脚本拼过头衔，因此用无颜色文本去掉开头的 [游客] 等生成前缀。
    return generatedTitlePrefixRegex.replace(Strings.stripColors(fixed).trim(), "").trim()
        .ifBlank { fixed.ifBlank { "NotInit" } }
}

private fun refreshRealName(player: Player, force: Boolean = false) {
    val key = player.uuid()
    val fromAuth = runCatching { PlayerData[player].name }.getOrNull()
    val current = realName[key]?.let(::sanitizeRealName)
    val sanitizedAuth = fromAuth?.let(::sanitizeRealName)
    realName[key] = when {
        // PlayerData.name 来自连接包原名，优先使用它，可保留玩家手动写入的颜色。
        sanitizedAuth != null && sanitizedAuth != "NotInit" -> sanitizedAuth
        force -> sanitizeFallbackName(player.name)
        current == null -> sanitizeFallbackName(player.name)
        current != realName[key] -> current
        else -> current
    }
}

fun Player.updateName() {
    refreshRealName(this)
    name = "{player.prefix}[white]{name}[white]{player.suffix}[white]".with(
        "player" to this,
        "name" to sanitizeRealName(realName[uuid()])
    ).toString()
}

listen<EventType.PlayerConnect> {
    val p = it.player
    refreshRealName(p, force = true)
    p.updateName()
}
onEnable {
    loop(Dispatchers.game) {
        Groups.player.forEach {
            refreshRealName(it)
        }
        delay(5000)
        Groups.player.forEach { it.updateName() }
    }
}
