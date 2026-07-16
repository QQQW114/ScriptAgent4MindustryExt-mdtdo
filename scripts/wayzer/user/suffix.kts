@file:Depends("wayzer/user/nameExt", "玩家名字前后缀")

package wayzer.user

import arc.util.Strings
import cf.wayzer.placehold.DynamicVar
import mindustry.gen.Iconc
import wayzer.lib.PlayerData

private val nameExt = contextScript<NameExt>()

val logVersion by config.key(false, "记录玩家的版本信息")

private val MAX_CUSTOM_SUFFIX_RAW_LENGTH = 64
private val MAX_CUSTOM_SUFFIX_VISIBLE_LENGTH = 16

val cache = mutableMapOf<String, String>()
listen<EventType.PlayerLeave> { cache.remove(it.player.uuid()) }

@Savable
val customSuffixMark = mutableMapOf<String, String>()
customLoad(::customSuffixMark) { customSuffixMark.putAll(it) }

fun Player.getSuffix(): String? {
    cache[uuid()]?.let { return it }
    val data = runCatching { PlayerData[this] }.getOrNull()
    val keys = listOfNotNull(data?.id, uuid()).distinct()
    keys.firstOrNull { customSuffixMark.containsKey(it) }?.let { key ->
        return customSuffixMark[key].orEmpty()
    }
    launch {
        cache[uuid()] = when {
            hasPermission("suffix.admin") -> "${Iconc.admin}"
            hasPermission("suffix.vip") -> "[gold]V[]"
            else -> return@launch
        }
    }
    return null
}

private data class SuffixTarget(
    val uid: String,
    val name: String,
    val player: Player?,
)

private fun resolveSuffixTarget(text: String): SuffixTarget {
    PlayerData.findByShortId(text)?.let {
        return SuffixTarget(it.id, it.player?.name ?: it.name, it.player)
    }
    val plain = text.replace(" ", "")
    Groups.player.find {
        it.uuid() == text ||
                PlayerData[it].id == text ||
                PlayerData[it].shortId.equals(text, ignoreCase = true) ||
                it.name.equals(text, ignoreCase = true) ||
                it.plainName().equals(text, ignoreCase = true) ||
                it.name.replace(" ", "").equals(plain, ignoreCase = true)
    }?.let {
        val data = PlayerData[it]
        return SuffixTarget(data.id, it.name, it)
    }
    return SuffixTarget(text, text, null)
}

private fun validateCustomSuffix(raw: String): String? {
    val fixed = raw.replace('\n', ' ').replace('\r', ' ').trim()
    if (fixed.length > MAX_CUSTOM_SUFFIX_RAW_LENGTH) return null
    val visible = Strings.stripColors(fixed).trim()
    if (visible.length > MAX_CUSTOM_SUFFIX_VISIBLE_LENGTH) return null
    return fixed
}

private fun applyCustomSuffix(target: SuffixTarget, mark: String?, operator: Player?): String {
    if (mark == null) {
        customSuffixMark.remove(target.uid)
    } else {
        customSuffixMark[target.uid] = mark
    }
    target.player?.let {
        cache.remove(it.uuid())
        with(nameExt) { it.updateName() }
    }
    val display = when {
        mark == null -> "默认"
        mark.isEmpty() -> "隐藏"
        else -> mark
    }
    operator?.sendMessage("[green]已设置 [white]${target.name}[green] 的后缀标记：[white]$display")
        ?: logger.info("已设置 ${target.name}(${target.uid}) 的后缀标记：$display")
    return display
}

@Savable
val clientType = mutableMapOf<String, Char>()
customLoad(::clientType) { clientType.putAll(it) }
listen<EventType.PlayerLeave> { clientType.remove(it.player.uuid()) }
onEnable {
    netServer.addPacketHandler("ARC") { p, v ->
        if (logVersion)
            logger.info("ARC ${p.name} $v")
        clientType[p.uuid()] = Iconc.blockArc
    }
    netServer.addPacketHandler("MDTX") { p, v ->
        if (logVersion)
            logger.info("MDTX ${p.name} $v")
        clientType[p.uuid()] = 'X'
    }
    netServer.addPacketHandler("fooCheck") { p, v ->
        if (logVersion)
            logger.info("FOO ${p.name} $v")
        clientType[p.uuid()] = '⒡'
    }
}
onDisable {
    netServer.getPacketHandlers("ARC").clear()
    netServer.getPacketHandlers("MDTX").clear()
    netServer.getPacketHandlers("fooCheck").clear()
}


registerVarForType<Player>().apply {

    registerChild("suffix.s2-clientType", "客户端类型后缀",  { p -> clientType[p.uuid()] })
    registerChild("suffix.s3-computer", "电脑玩家后缀",  { p -> ''.takeIf { !p.con.mobile } })
    registerChild("suffix.s5-group", "权限组后缀",  { it.getSuffix() })
}

PermissionApi.registerDefault("suffix.admin", group = "@admin")
PermissionApi.registerDefault("suffix.vip", group = "@vip")

command("suffixmark", "管理指令：自定义或隐藏名字后缀标记") {
    usage = "[玩家/3位ID] <set <标记>|hide|clear>，或 /suffixmark <标记> 设置自己"
    permission = "suffix.admin"
    aliases = listOf("adminsuffix", "后缀标记", "管理标记")
    body {
        val operator = player
        if (arg.isEmpty()) {
            val p = operator ?: returnReply("[red]控制台请指定玩家：/suffixmark <玩家> <set|hide|clear> [标记]".with())
            val uid = PlayerData[p].id
            val current = when {
                customSuffixMark.containsKey(uid) -> customSuffixMark[uid].orEmpty().ifEmpty { "隐藏" }
                customSuffixMark.containsKey(p.uuid()) -> customSuffixMark[p.uuid()].orEmpty().ifEmpty { "隐藏" }
                else -> "默认"
            }
            returnReply("[cyan]当前后缀标记：[white]$current\n[gray]用法：/suffixmark hide 隐藏管理标；/suffixmark clear 恢复默认；/suffixmark set <标记> 自定义。".with())
        }

        val first = arg[0].lowercase()
        val selfTarget = operator?.let { SuffixTarget(PlayerData[it].id, it.name, it) }
        val selfOps = setOf("set", "hide", "隐藏", "off", "none", "clear", "default", "show", "恢复", "默认", "清除")
        val target: SuffixTarget
        val op: String
        val valueArgs: List<String>
        if (arg.size >= 2 && first !in selfOps) {
            target = resolveSuffixTarget(arg[0])
            op = arg[1].lowercase()
            valueArgs = arg.drop(2)
        } else {
            target = selfTarget ?: returnReply("[red]控制台请指定玩家：/suffixmark <玩家> <set|hide|clear> [标记]".with())
            op = first
            valueArgs = arg.drop(1)
        }

        when (op) {
            "hide", "隐藏", "off", "none" -> {
                applyCustomSuffix(target, "", operator)
            }
            "clear", "default", "show", "恢复", "默认", "清除" -> {
                applyCustomSuffix(target, null, operator)
            }
            "set", "设置" -> {
                val raw = valueArgs.joinToString(" ")
                if (raw.isBlank()) returnReply("[red]请输入标记内容；如需隐藏请用 hide，如需恢复默认请用 clear。".with())
                val mark = validateCustomSuffix(raw)
                    ?: returnReply("[red]标记过长或格式不正确：原始长度最多 ${MAX_CUSTOM_SUFFIX_RAW_LENGTH}，去颜色后最多 ${MAX_CUSTOM_SUFFIX_VISIBLE_LENGTH} 个字符。".with())
                applyCustomSuffix(target, mark, operator)
            }
            else -> {
                // /suffixmark <标记>：快速设置自己的标记，方便处理看不清的图标字符。
                if (target != selfTarget) replyUsage()
                val raw = arg.joinToString(" ")
                val mark = validateCustomSuffix(raw)
                    ?: returnReply("[red]标记过长或格式不正确：原始长度最多 ${MAX_CUSTOM_SUFFIX_RAW_LENGTH}，去颜色后最多 ${MAX_CUSTOM_SUFFIX_VISIBLE_LENGTH} 个字符。".with())
                applyCustomSuffix(target, mark, operator)
            }
        }
    }
}
