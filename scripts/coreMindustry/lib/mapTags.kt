package coreMindustry.lib

import arc.struct.StringMap
import cf.wayzer.scriptAgent.ScriptRegistry
import mindustry.game.Rules
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 中立地图标签定义与统一注册表。
 *
 * 位于 `coreMindustry/lib`，供 `mapScript/lib/TagSupport`、`wayzer/maps`、`wayzer/cmds/voteMap`
 * 与 `wayzer/map/funRuleModes` 共同读取，避免重复维护别名表，且防止常驻命令脚本反向依赖 `mapScript`。
 */
data class MapTagDefinition(
    val canonicalTag: String,
    val scriptId: String? = null,
    val aliases: Set<String> = emptySet(),
    val description: String = "",
    val votable: Boolean = true,
    val allowDisable: Boolean = true,
) {
    val shortName: String get() = canonicalTag.removePrefix("@")

    val allTagKeys: List<String>
        get() = (listOf(canonicalTag) + aliases).distinctBy { it.lowercase() }

    fun matches(input: String): Boolean {
        val normalized = normalizeMapTagKey(input) ?: return false
        return allTagKeys.any { it.equals(normalized, ignoreCase = true) }
    }
}

fun normalizeMapTagKey(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val body = trimmed.removePrefix("@")
    if (body.isEmpty() || !body.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
    return "@$body"
}

/**
 * 外部 DP 解析桥接（由 `wayzer/map/externalCpHotReload` 在启用时注册）。
 * 让 `wayzer/maps`（`/host`）与 `wayzer/cmds/voteMap` 共用同一份外部 DP 解析规则而不引入跨层依赖。
 */
object MapDpResolverBridge {
    @Volatile
    private var resolver: ((String) -> String?)? = null

    fun register(fn: (String) -> String?) {
        resolver = fn
    }

    fun clear() {
        resolver = null
    }

    fun isAvailable(): Boolean = resolver != null

    fun resolveName(input: String): String? = resolver?.invoke(input)
}

object MapTagRegistry {
    private val reservedNonTagKeys = setOf("@dp", "@cp", "@nodp", "@dpnone", "@mapscript")
    private val descriptionTagRegex = Regex("\\[(@[a-zA-Z0-9]+)(=[^=\\]]+)?]")

    private val builtInDefinitions: List<MapTagDefinition> = listOf(
        MapTagDefinition(
            canonicalTag = "@pure",
            scriptId = "mapScript/tags/pure",
            description = "纯净模式（禁用普通技能与3级技能）",
        ),
        MapTagDefinition(
            canonicalTag = "@Unlimited",
            scriptId = "mapScript/tags/unlimited",
            description = "无限资源模式（本局无限资源，换图恢复）",
        ),
        MapTagDefinition(
            canonicalTag = "@flood",
            scriptId = "mapScript/tags/flood",
            aliases = setOf("@floodV2"),
            description = "洪水模式",
        ),
        MapTagDefinition(
            canonicalTag = "@hybrid",
            scriptId = "mapScript/tags/hybrid",
            description = "地图特色杂交模式",
        ),
        MapTagDefinition(
            canonicalTag = "@noSkills",
            scriptId = null,
            aliases = setOf("@noSkill"),
            description = "禁用普通/2级/商店技能（保留3级与管理技能）",
        ),
    )

    private val dynamicDefinitions = CopyOnWriteArrayList<MapTagDefinition>()

    fun registerDynamic(tag: String, scriptId: String, description: String = "") {
        val normalized = normalizeMapTagKey(tag) ?: return
        if (normalized.lowercase() in reservedNonTagKeys) return
        if (builtInDefinitions.any { it.matches(normalized) }) return
        dynamicDefinitions.removeIf { it.canonicalTag.equals(normalized, ignoreCase = true) && it.scriptId == scriptId }
        dynamicDefinitions.add(MapTagDefinition(canonicalTag = normalized, scriptId = scriptId, description = description))
    }

    fun unregisterDynamic(tag: String, scriptId: String) {
        val normalized = normalizeMapTagKey(tag) ?: return
        dynamicDefinitions.removeIf { it.canonicalTag.equals(normalized, ignoreCase = true) && it.scriptId == scriptId }
    }

    private fun isDefinitionScriptAvailable(def: MapTagDefinition): Boolean {
        val scriptId = def.scriptId ?: return true
        return ScriptRegistry.getScriptInfo(scriptId) != null
    }

    private fun discoverTagScript(shortName: String): String? {
        val exactId = "mapScript/tags/$shortName"
        if (ScriptRegistry.getScriptInfo(exactId) != null) return exactId
        return ScriptRegistry.allScripts { it.id.startsWith("mapScript/tags/") }
            .firstOrNull { it.id.removePrefix("mapScript/tags/").equals(shortName, ignoreCase = true) }
            ?.id
    }

    /** 解析地图标签定义（大小写不敏感，支持省略 `@` 前缀与别名如 `floodV2`、`unlimited`）。 */
    fun resolve(input: String): MapTagDefinition? {
        val normalized = normalizeMapTagKey(input) ?: return null
        if (normalized.lowercase() in reservedNonTagKeys) return null

        builtInDefinitions.firstOrNull { it.matches(normalized) }?.let { def ->
            return def.takeIf(::isDefinitionScriptAvailable)
        }
        dynamicDefinitions.firstOrNull { it.matches(normalized) }?.let { def ->
            return def.takeIf(::isDefinitionScriptAvailable)
        }
        val discoveredId = discoverTagScript(normalized.removePrefix("@")) ?: return null
        val scriptLeaf = discoveredId.removePrefix("mapScript/tags/")
        return MapTagDefinition(canonicalTag = "@$scriptLeaf", scriptId = discoveredId)
    }

    /** 查找标签关联的 `mapScript/tags/...` 脚本 ID（大小写不敏感）。 */
    fun findScriptId(tag: String): String? {
        val normalized = normalizeMapTagKey(tag) ?: return null
        builtInDefinitions.firstOrNull { it.matches(normalized) }?.scriptId?.let { id ->
            if (ScriptRegistry.getScriptInfo(id) != null) return id
        }
        dynamicDefinitions.firstOrNull { it.matches(normalized) }?.scriptId?.let { id ->
            if (ScriptRegistry.getScriptInfo(id) != null) return id
        }
        return discoverTagScript(normalized.removePrefix("@"))
    }

    /** 列出当前服务端全部可用地图标签（用于报错提示与帮助文案）。 */
    fun availableTags(): List<MapTagDefinition> {
        val result = linkedMapOf<String, MapTagDefinition>()
        builtInDefinitions.filter(::isDefinitionScriptAvailable).forEach { def ->
            result[def.canonicalTag.lowercase()] = def
        }
        dynamicDefinitions.filter(::isDefinitionScriptAvailable).forEach { def ->
            result.putIfAbsent(def.canonicalTag.lowercase(), def)
        }
        ScriptRegistry.allScripts { it.id.startsWith("mapScript/tags/") }
            .sortedBy { it.id.lowercase() }
            .forEach { info ->
                val alreadyMapped = result.values.any { it.scriptId.equals(info.id, ignoreCase = true) }
                if (!alreadyMapped) {
                    val leaf = info.id.removePrefix("mapScript/tags/")
                    result.putIfAbsent("@${leaf.lowercase()}", MapTagDefinition("@$leaf", scriptId = info.id))
                }
            }
        return result.values.toList()
    }

    fun hasTagIgnoreCase(tags: StringMap, vararg names: String): Boolean {
        val targets = names.map { it.lowercase() }.toSet()
        for (key in tags.keys()) {
            if (key.lowercase() in targets) return true
        }
        return false
    }

    fun getTagIgnoreCase(tags: StringMap, name: String): String? {
        for (entry in tags.entries()) {
            if (entry.key.equals(name, ignoreCase = true)) return entry.value
        }
        return null
    }

    fun removeKeysIgnoreCase(tags: StringMap, keys: Iterable<String>): Int {
        val targets = keys.map { it.lowercase() }.toSet()
        if (targets.isEmpty()) return 0
        val toRemove = mutableListOf<String>()
        for (key in tags.keys()) {
            if (key.lowercase() in targets) toRemove.add(key)
        }
        toRemove.forEach { tags.remove(it) }
        return toRemove.size
    }

    fun putTagIgnoreCase(
        tags: StringMap,
        canonicalKey: String,
        value: String,
        extraKeysToClear: Iterable<String> = emptyList(),
    ) {
        removeKeysIgnoreCase(tags, listOf(canonicalKey) + extraKeysToClear)
        tags.put(canonicalKey, value)
    }

    /** 判断规则标签是否禁止外部 DP（口径与 `wayzer/map/mapDpTags` 完全一致）。 */
    fun rulesForbidDp(tags: StringMap): Boolean {
        if (hasTagIgnoreCase(tags, "@nodp", "@dpnone")) return true
        val dpVal = getTagIgnoreCase(tags, "@dp")?.trim()?.lowercase().orEmpty()
        return dpVal in setOf("off", "none", "false", "0")
    }

    /** 判断地图简介文本中的标签是否禁止外部 DP。 */
    fun descriptionForbidsDp(description: String): Boolean {
        if (description.isBlank()) return false
        val temp = StringMap()
        descriptionTagRegex.findAll(description).forEach { match ->
            val value = match.groupValues[2].takeIf(String::isNotEmpty)?.removePrefix("=") ?: "true"
            temp.put(match.groupValues[1], value)
        }
        return rulesForbidDp(temp)
    }
}

/**
 * 单次换图的启动参数（由 Minecraft 风格 `@map[...]` / `@opt[...]` 解析生成）。
 *
 * - `dpTargets`：本局附加的外部 DP 规范文件名列表（为空表示不覆盖地图自身 DP）；
 * - `forbidDp`：显式禁掉本局 DP（`nodp=true` / `dp=none` / `dp=off`）；
 * - `tagChanges`：逻辑标签开关表（`MapTagDefinition -> Boolean`，`true` 启用，`false` 移除）；
 */
data class MapLaunchOptions(
    val dpTargets: List<String> = emptyList(),
    val forbidDp: Boolean = false,
    val tagChanges: Map<MapTagDefinition, Boolean> = emptyMap(),
) {
    val isEmpty: Boolean get() = dpTargets.isEmpty() && !forbidDp && tagChanges.isEmpty()

    /** 将启动参数写入/移除目标 `Rules.tags`（幂等，大小写不敏感）。 */
    fun applyTo(rules: Rules) {
        if (forbidDp) {
            MapTagRegistry.removeKeysIgnoreCase(rules.tags, listOf("@dp"))
            MapTagRegistry.putTagIgnoreCase(rules.tags, "@nodp", "true", listOf("@dpnone"))
        } else if (dpTargets.isNotEmpty()) {
            MapTagRegistry.putTagIgnoreCase(rules.tags, "@dp", dpTargets.joinToString(","))
        }
        tagChanges.forEach { (def, enabled) ->
            if (enabled) {
                MapTagRegistry.putTagIgnoreCase(rules.tags, def.canonicalTag, "true", def.aliases)
            } else {
                MapTagRegistry.removeKeysIgnoreCase(rules.tags, def.allTagKeys)
            }
        }
    }

    /** 生成用于投票面板/公告/日志的人类可读摘要。 */
    fun describeLines(): String = buildString {
        if (forbidDp) {
            append("\n[white]本次 DP: [lightgrey]禁用 (@nodp)[][]")
        } else if (dpTargets.isNotEmpty()) {
            append("\n[white]本次 DP: [lightgrey]${dpTargets.joinToString(", ")}[][]")
        }
        if (tagChanges.isNotEmpty()) {
            append(
                "\n[white]本次标签: [lightgrey]" +
                    tagChanges.entries.joinToString(", ") { (def, enabled) ->
                        "${if (enabled) "+" else "-"}${def.canonicalTag}"
                    } +
                    "[][]"
            )
        }
    }

    fun compactSummary(): String {
        val parts = mutableListOf<String>()
        if (forbidDp) parts += "nodp=true"
        else if (dpTargets.isNotEmpty()) parts += "dp=${dpTargets.joinToString(",")}"
        tagChanges.forEach { (def, enabled) ->
            parts += "${def.shortName}=$enabled"
        }
        return parts.joinToString(", ")
    }

    companion object {
        val EMPTY = MapLaunchOptions()
        private val dpDisableWords = setOf("none", "off", "false", "0")
        private val forbiddenDpChars = setOf('[', ']', '=', ',', ';', '|')

        fun usageHint(): String =
            "@map[dp=编号或文件名,unlimited=true,pure=false]（可用多次 dp= 或 ; 分隔多包；支持 !标签 取反与别名 @opt[...]）"

        /**
         * 解析换图命令中地图 ID 之后的参数文本。
         * - 空白文本 → [EMPTY]
         * - 支持 `@map[...]` 与别名 `@opt[...]`（最多一个参数块，块外不能有多余内容）。
         */
        fun parse(raw: String, requireVotable: Boolean = false): MapLaunchOptions {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return EMPTY

            val tokens = SelectorSyntax.tokenize(trimmed)
            if (tokens.isEmpty()) return EMPTY
            if (tokens.size > 1) {
                throw SelectorSyntaxException("换图参数只能包含一个 @map[...] 选择器块，多余内容：${tokens.drop(1).joinToString(" ")}")
            }
            val token = tokens[0]
            if (!SelectorSyntax.isSelectorToken(token)) {
                throw SelectorSyntaxException("换图参数格式错误：请使用 ${usageHint()}")
            }
            val parsed = SelectorSyntax.parseSelector(token)
            if (parsed.targetLower != "map" && parsed.targetLower != "opt") {
                throw SelectorSyntaxException("不支持的换图参数选择器 @${parsed.target}，请使用 @map[...] 或 @opt[...]")
            }
            if (!parsed.hasBrackets || parsed.entries.isEmpty()) {
                return EMPTY
            }

            val dpTargets = mutableListOf<String>()
            var forbidDp = false
            var sawExplicitNodp = false
            val tagChanges = linkedMapOf<MapTagDefinition, Boolean>()

            for (entry in parsed.entries) {
                val cleanKey = entry.keyLower.removePrefix("@")
                when (cleanKey) {
                    "cp" -> throw SelectorSyntaxException("不支持 cp 参数，请改用 dp=编号或文件名（例如 @map[dp=1]）")
                    "nodp", "dpnone" -> {
                        val enabled = SelectorSyntax.parseBoolean(entry)
                        if (!enabled) {
                            throw SelectorSyntaxException("不支持关闭地图自带的 @nodp 限制")
                        }
                        if (dpTargets.isNotEmpty()) {
                            throw SelectorSyntaxException("不能同时指定 dp=... 与 nodp=true")
                        }
                        if (sawExplicitNodp) {
                            throw SelectorSyntaxException("参数 nodp 重复")
                        }
                        forbidDp = true
                        sawExplicitNodp = true
                    }
                    "dp" -> {
                        if (entry.value == null) {
                            throw SelectorSyntaxException("dp 参数必须指定值，例如 dp=1 或 dp=\"包名.zip\"")
                        }
                        if (entry.negated) {
                            throw SelectorSyntaxException("dp 参数不支持 ! 取反，如需禁用 DP 请使用 nodp=true 或 dp=none")
                        }
                        val items = SelectorSyntax.splitTopLevel(entry.value, ',', ';', '|')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        if (items.isEmpty()) {
                            throw SelectorSyntaxException("dp 文件名或编号不能为空")
                        }
                        if (items.size == 1 && items[0].lowercase() in dpDisableWords) {
                            if (dpTargets.isNotEmpty()) {
                                throw SelectorSyntaxException("不能同时指定 DP 文件与 dp=${items[0]}")
                            }
                            forbidDp = true
                            sawExplicitNodp = true
                            continue
                        }
                        if (forbidDp) {
                            throw SelectorSyntaxException("不能同时指定 nodp/dp=none 与具体 DP 文件")
                        }
                        if (!MapDpResolverBridge.isAvailable()) {
                            throw SelectorSyntaxException("外部 DP 热重载模块当前未启用，无法校验或加载 DP")
                        }
                        for (item in items) {
                            if (item.lowercase() in dpDisableWords) {
                                throw SelectorSyntaxException("不能在 DP 列表中混用 $item 与具体文件名")
                            }
                            val canonical = MapDpResolverBridge.resolveName(item)
                                ?: throw SelectorSyntaxException("服务器外部 DP 不存在：$item（可用 /externalcp list 查看编号与文件名）")
                            if (canonical.any { it in forbiddenDpChars }) {
                                throw SelectorSyntaxException("DP 文件名包含地图标签不支持的字符（[]=,;|）：$canonical")
                            }
                            if (dpTargets.any { it.equals(canonical, ignoreCase = true) }) {
                                throw SelectorSyntaxException("DP 文件重复：$canonical")
                            }
                            dpTargets.add(canonical)
                        }
                    }
                    else -> {
                        val def = MapTagRegistry.resolve(entry.key) ?: run {
                            val available = MapTagRegistry.availableTags().joinToString("、") { it.shortName }
                            throw SelectorSyntaxException("未知地图标签：${entry.key}（当前可用：$available）")
                        }
                        if (requireVotable && !def.votable) {
                            throw SelectorSyntaxException("地图标签 ${def.canonicalTag} 不允许通过投票开关")
                        }
                        val enabled = SelectorSyntax.parseBoolean(entry)
                        if (!enabled && !def.allowDisable) {
                            throw SelectorSyntaxException("地图标签 ${def.canonicalTag} 不允许关闭")
                        }
                        if (tagChanges.keys.any { it.canonicalTag.equals(def.canonicalTag, ignoreCase = true) }) {
                            throw SelectorSyntaxException("地图标签重复：${def.canonicalTag}")
                        }
                        tagChanges[def] = enabled
                    }
                }
            }
            return MapLaunchOptions(dpTargets = dpTargets, forbidDp = forbidDp, tagChanges = tagChanges)
        }
    }
}
