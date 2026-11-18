@file:Depends("wayzer/maps", "地图管理")
@file:Depends("wayzer/vote", "投票实现")
@file:Depends("wayzer/map/mapFilter", "地图筛选系统")

package wayzer.cmds

import arc.util.Strings.stripColors
import java.io.File
import arc.util.Strings.truncate
import cf.wayzer.scriptAgent.ScriptRegistry
import mindustry.Vars
import mindustry.game.Rules
import wayzer.*
import wayzer.map.MapFilter

private val mapFilter = contextScript<MapFilter>()

private fun mapVoteRequireNum(all: Int): Int =
    ((all.coerceAtLeast(1) * 51) + 99) / 100

private fun ensureMapAllowedForVote(player: Player, map: MapInfo): Boolean {
    val reason = with(mapFilter) { blockReason(map) }
    if (reason != null) {
        player.sendMessage("[red]无法发起换图投票：该地图被地图筛选系统阻止，原因：$reason".with())
        return false
    }
    return true
}

data class MapVoteOptions(
    val dpTargets: List<String> = emptyList(),
    val tagChanges: Map<String, Boolean> = emptyMap(),
) {
    val isEmpty: Boolean get() = dpTargets.isEmpty() && tagChanges.isEmpty()
}

private data class PendingMapOptions(
    val mapId: Int,
    val options: MapVoteOptions,
)

private class MapVoteArgumentException(message: String) : Exception(message)

private val mapTagScriptAliases = mapOf(
    "@floodV2" to "mapScript/tags/flood",
    "@Unlimited" to "mapScript/tags/unlimited",
)

private fun isKnownMapTag(tag: String): Boolean {
    val normalized = if (tag.startsWith("@")) tag else "@$tag"
    val scriptId = mapTagScriptAliases[normalized] ?: "mapScript/tags/${normalized.removePrefix("@")}"
    return ScriptRegistry.getScriptInfo(scriptId) != null
}

private var pendingMapOptions: PendingMapOptions? = null

private fun parseBraceGroups(raw: String): List<String> {
    val groups = mutableListOf<String>()
    var index = 0
    while (index < raw.length) {
        while (index < raw.length && raw[index].isWhitespace()) index++
        if (index >= raw.length) break
        if (raw[index] != '{') {
            throw MapVoteArgumentException("参数格式错误：大括号外不能有多余内容")
        }
        index++
        val content = StringBuilder()
        var quote: Char? = null
        var closed = false
        while (index < raw.length) {
            val c = raw[index]
            if (quote != null) {
                if (c == '\\' && index + 1 < raw.length) {
                    content.append(c).append(raw[index + 1])
                    index += 2
                    continue
                }
                content.append(c)
                if (c == quote) quote = null
                index++
                continue
            }
            when (c) {
                '"', '\'' -> {
                    quote = c
                    content.append(c)
                    index++
                }
                '{' -> throw MapVoteArgumentException("大括号内不能嵌套 {")
                '}' -> {
                    index++
                    closed = true
                    break
                }
                else -> {
                    content.append(c)
                    index++
                }
            }
        }
        if (!closed || quote != null) {
            throw MapVoteArgumentException("大括号没有正确闭合")
        }
        groups.add(content.toString())
        if (groups.size > 2) {
            throw MapVoteArgumentException("最多只能提供两个大括号参数")
        }
    }
    return groups
}

private fun splitQuoted(value: String, delimiter: Char): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var index = 0
    while (index < value.length) {
        val c = value[index]
        if (quote != null) {
            if (c == '\\' && index + 1 < value.length) {
                current.append(c).append(value[index + 1])
                index += 2
                continue
            }
            current.append(c)
            if (c == quote) quote = null
            index++
            continue
        }
        when (c) {
            '"', '\'' -> {
                quote = c
                current.append(c)
                index++
            }
            delimiter -> {
                result.add(current.toString())
                current.setLength(0)
                index++
            }
            else -> {
                current.append(c)
                index++
            }
        }
    }
    if (quote != null) throw MapVoteArgumentException("文件名引号没有闭合")
    result.add(current.toString())
    return result
}

private fun decodeToken(token: String): String {
    val value = token.trim()
    if (value.isEmpty()) throw MapVoteArgumentException("文件名不能为空")
    if (value.first() == '"' || value.first() == '\'') {
        val quote = value.first()
        if (value.length < 2 || value.last() != quote) {
            throw MapVoteArgumentException("文件名引号没有闭合：$value")
        }
        val body = value.substring(1, value.length - 1)
        val result = StringBuilder()
        var index = 0
        while (index < body.length) {
            val c = body[index]
            if (c == '\\' && index + 1 < body.length) {
                result.append(body[index + 1])
                index += 2
            } else {
                result.append(c)
                index++
            }
        }
        return result.toString().trim().also {
            if (it.isEmpty()) throw MapVoteArgumentException("文件名不能为空")
        }
    }
    if (value.any { it == '"' || it == '\'' }) {
        throw MapVoteArgumentException("文件名引号位置错误：$value")
    }
    return value
}

private fun resolveServerCpName(input: String): String? {
    val text = input.trim().takeIf { it.isNotBlank() } ?: return null
    val dir = File(Vars.dataDirectory.file(), "scripts/external-cp")
    val supported = setOf("json", "hjson", "json5", "zip")
    val files = dir.listFiles { file -> file.isFile && file.extension.lowercase() in supported }
        ?.toList()
        .orEmpty()
    val candidates = buildList {
        add(text)
        if (File(text).extension.isBlank()) {
            supported.forEach { add("$text.$it") }
        }
    }
    return files.firstOrNull { file ->
        candidates.any { it.equals(file.name, ignoreCase = true) }
    }?.name
}

private fun parseDpTargets(content: String): List<String> {
    val text = content.trim()
    if (text.isEmpty()) return emptyList()
    if (text.contains("[@cp", ignoreCase = true)) {
        throw MapVoteArgumentException("不支持外部 CP 标签；第一块只能使用 [@dp=文件名]")
    }
    if (!text.startsWith("[@dp=") || !text.endsWith("]")) {
        throw MapVoteArgumentException("第一块只支持 [@dp=文件名1,文件名2]")
    }
    val rawValue = text.removePrefix("[@dp=").removeSuffix("]")
    val tokens = splitQuoted(rawValue, ',').map(::decodeToken)
    if (tokens.isEmpty()) throw MapVoteArgumentException("DP 文件名不能为空")

    val result = mutableListOf<String>()
    tokens.forEach { token ->
        if (token.any { it == '[' || it == ']' || it == '=' || it == ',' || it == ';' }) {
            throw MapVoteArgumentException("DP 文件名含不支持的字符（[]=,;）：$token")
        }
        val canonical = resolveServerCpName(token)
            ?: throw MapVoteArgumentException("服务器 CP 不存在：$token")
        if (result.any { it.equals(canonical, ignoreCase = true) }) {
            throw MapVoteArgumentException("DP 文件名重复：$canonical")
        }
        result.add(canonical)
    }
    return result
}

private fun parseTagChanges(content: String): Map<String, Boolean> {
    val text = content.trim()
    if (text.isEmpty()) return emptyMap()
    val changes = linkedMapOf<String, Boolean>()
    val regex = Regex("\\[@([A-Za-z0-9]+)=(true|false)]")
    var consumed = 0
    regex.findAll(text).forEach { match ->
        if (text.substring(consumed, match.range.first).isNotBlank()) {
            throw MapVoteArgumentException("标签块存在多余内容：${text.substring(consumed, match.range.first).trim()}")
        }
        val tag = "@${match.groupValues[1]}"
        if (tag.equals("@dp", true) || tag.equals("@cp", true) || tag.equals("@nodp", true)) {
            throw MapVoteArgumentException("第二块不能设置 CP 标签：$tag")
        }
        if (!isKnownMapTag(tag)) {
            throw MapVoteArgumentException("未知地图标签：$tag")
        }
        if (changes.containsKey(tag)) {
            throw MapVoteArgumentException("地图标签重复：$tag")
        }
        changes[tag] = match.groupValues[2].toBoolean()
        consumed = match.range.last + 1
    }
    if (text.substring(consumed).isNotBlank()) {
        throw MapVoteArgumentException("标签块存在多余内容：${text.substring(consumed).trim()}")
    }
    if (changes.isEmpty()) {
        throw MapVoteArgumentException("标签块格式错误，应为 [@标签=true] 或 [@标签=false]")
    }
    return changes
}

private fun parseMapVoteOptions(raw: String): MapVoteOptions {
    val groups = parseBraceGroups(raw)
    val dpTargets = parseDpTargets(groups.getOrNull(0).orEmpty())
    val tagChanges = parseTagChanges(groups.getOrNull(1).orEmpty())
    return MapVoteOptions(dpTargets, tagChanges)
}

private fun applyMapVoteOptions(rules: Rules, options: MapVoteOptions) {
    if (options.dpTargets.isNotEmpty()) {
        rules.tags.put("@dp", options.dpTargets.joinToString(","))
    }
    options.tagChanges.forEach { (tag, enabled) ->
        if (enabled) rules.tags.put(tag, "true") else rules.tags.remove(tag)
    }
}

listenTo<MapChangeEvent>(Event.Priority.Before) {
    val pending = pendingMapOptions ?: return@listenTo
    if (pending.mapId != info.id) return@listenTo
    pendingMapOptions = null
    try {
        applyMapVoteOptions(rules, pending.options)
        logger.info(
            "投票换图参数已应用: map=${info.id}, dp=${pending.options.dpTargets.joinToString(",")}, " +
                "tags=${pending.options.tagChanges.entries.joinToString(",") { "${it.key}=${it.value}" }}"
        )
    } catch (e: Throwable) {
        logger.warning("投票换图参数应用失败: ${e.message}")
        cancelled = true
    }
}


private fun optionsDescription(options: MapVoteOptions): String = buildString {
    if (options.dpTargets.isNotEmpty()) {
        append("\n[white]本次 DP: [lightgrey]${options.dpTargets.joinToString(", ")}[][]")
    }
    if (options.tagChanges.isNotEmpty()) {
        append(
            "\n[white]本次标签: [lightgrey]" +
                options.tagChanges.entries.joinToString(", ") { "${if (it.value) "+" else "-"}${it.key}" } +
                "[][]"
        )
    }
}

fun voteMap(player: Player, map: MapInfo) = voteMap(player, map, MapVoteOptions())

fun voteMap(player: Player, map: MapInfo, options: MapVoteOptions) {
    launch(Dispatchers.game) {
        if (!ensureMapAllowedForVote(player, map)) return@launch
        val event = VoteEvent(
            this, player,
            "换图([green]{nextMap.id}[]: [green]{nextMap.name}[yellow]|[green]{nextMap.mode}[])".with("nextMap" to map),
            extDesc = "[white]地图作者: [lightgrey]${stripColors(map.author)}[][]\n" +
                    "[white]地图简介: [lightgrey]${truncate(stripColors(map.description), 100, "...")}[][]" +
                    optionsDescription(options),
            supportSingle = true,
            requireNum = ::mapVoteRequireNum,
        )
        if (!event.awaitResult()) return@launch
        if (!options.isEmpty) pendingMapOptions = PendingMapOptions(map.id, options)
        try {
            broadcast("[yellow]异步加载地图中，请耐心等待".with())
            if (MapManager.loadMapSync(map))
                broadcast("[green]换图成功,当前地图[yellow]{map.name}[green](id: {map.id})".with())
        } finally {
            pendingMapOptions = null
        }
    }
}

fun voteNextAutoMap(player: Player, map: MapInfo) {
    launch(Dispatchers.game) {
        if (!ensureMapAllowedForVote(player, map)) return@launch
        if (map == MapManager.current) {
            player.sendMessage("[yellow]目标地图已经是当前地图，不建议设置为下次自动轮换地图。".with())
            return@launch
        }
        val pending = MapManager.getPendingAutoRotateMap()
        val event = VoteEvent(
            this, player,
            "下次自动轮换地图([green]{nextMap.id}[]: [green]{nextMap.name}[yellow]|[green]{nextMap.mode}[])".with("nextMap" to map),
            extDesc = buildString {
                append("[white]注：投票通过后不会立刻换图，而是在下一次自动轮换地图时切换到该地图。[][]\n")
                append("[gray]如果期间被管理员或其他机制手动换图，本计划仍会保留，直到下一次自动轮换时生效。[][]\n")
                append("[white]地图作者: [lightgrey]${stripColors(map.author)}[][]\n")
                append("[white]地图简介: [lightgrey]${truncate(stripColors(map.description), 100, "...")}[][]")
                if (pending != null) {
                    append("\n[yellow]当前已有计划：${pending.target.id} ${stripColors(pending.target.name)}，通过后会被本次投票覆盖。")
                }
            },
            supportSingle = true,
            requireNum = ::mapVoteRequireNum,
        )
        if (!event.awaitResult()) return@launch
        MapManager.setPendingAutoRotateMap(map, player.plainName())
        broadcast(
            "[green]投票通过：下次自动轮换地图将换到 [yellow]{nextMap.name}[green](id: {nextMap.id})，发起人：[white]{player.name}".with(
                "nextMap" to map,
                "player" to player,
            )
        )
    }
}

fun VoteService.register() {
    addSubVote("立即投票换图", "<地图ID> {[@dp=文件名1,文件名2]} {[@标签=true|false]}", "map", "换图") {
        if (arg.isEmpty())
            returnReply("[red]请输入地图序号".with())
        val map = arg[0].toIntOrNull()?.let { MapRegistry.findById(it, reply) }
            ?: returnReply("[red]地图序号错误,可以通过/maps查询".with())
        val options = try {
            parseMapVoteOptions(arg.drop(1).joinToString(" "))
        } catch (e: MapVoteArgumentException) {
            returnReply("[red]${e.message}".with())
        }
        voteMap(player!!, map, options)
    }
    addSubVote("指定下次轮换地图", "<地图ID>", "nextmap", "endmap", "aftermap", "下局换图", "结束后换图") {
        if (arg.isEmpty())
            returnReply("[red]请输入地图序号".with())
        val map = arg[0].toIntOrNull()?.let { MapRegistry.findById(it, reply) }
            ?: returnReply("[red]地图序号错误,可以通过/maps查询".with())
        voteNextAutoMap(player!!, map)
    }
    addSubVote("回滚存档（/slots查看）", "<存档ID>", "rollback", "load", "回档") {
        val save = arg.firstOrNull()?.toIntOrNull()
            ?: returnReply("[red]请输入正确的存档编号".with())
        val map = MapManager.getSlot(save)
            ?: returnReply("[red]存档不存在或存档损坏".with())
        start(player!!, "回档({save})".with("save" to save), supportSingle = true) {
            MapManager.loadSave(map)
            broadcast("[green]回档成功".with(), quite = true)
        }
    }
}

onEnable {
    VoteService.register()
}
