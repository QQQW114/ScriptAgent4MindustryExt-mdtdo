package coreMindustry.lib

/**
 * Minecraft 风格命令行词法与选择器解析底座（纯无状态，不依赖具体业务模型）。
 *
 * 同时支撑：
 * - 地图启动参数：`@map[dp=1,dp="亚龙组合包 (1).zip",unlimited=true,pure=false]`（别名 `@opt[...]`）
 * - 目标选择器（单位/玩家）：`@e[team=!1,type=!mono,distance=..20,limit=5,sort=nearest]`、`@a`、`@p`、`@r`、`@s`
 */
class SelectorSyntaxException(message: String) : IllegalArgumentException(message)

/**
 * 选择器方括号内的单个条目。
 *
 * - 键值对：`key=value`（`negated = false`）或 `key=!value`（`negated = true`）
 * - 裸标记：`flag`（`value = null, negated = false`）或 `!flag`（`value = null, negated = true`）
 */
data class SelectorEntry(
    val key: String,
    val value: String?,
    val negated: Boolean = false,
    val quoted: Boolean = false,
) {
    val keyLower: String get() = key.lowercase()
}

data class ParsedSelector(
    val target: String,
    val entries: List<SelectorEntry>,
    val hasBrackets: Boolean,
    val raw: String,
) {
    val targetLower: String get() = target.lowercase()
}

/** Minecraft Java 风格闭区间（`..20`、`5..20`、`10..`、`15`）。 */
data class SelectorRange(
    val min: Float? = null,
    val max: Float? = null,
) {
    fun contains(value: Float): Boolean {
        if (min != null && value < min) return false
        if (max != null && value > max) return false
        return true
    }
}

object SelectorSyntax {
    private val targetRegex = Regex("[A-Za-z][A-Za-z0-9_]*")

    fun isSelectorToken(token: String): Boolean = token.trim().startsWith("@")

    /**
     * 引号与方括号感知分词器：
     * - 方括号 `[...]` 内与引号 `"..."`/`'...'` 内的空白不会把参数拆断；
     * - 仅在词首、空白后或分隔符（`=` / `!` / `,` / `;` / `[`）后才将 `"`/`'` 视为引号开头，
     *   避免误伤玩家名/文件名中正常的撇号（如 `Tom's`）。
     */
    fun tokenize(raw: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var bracketDepth = 0
        var canOpenQuote = true
        var i = 0

        while (i < raw.length) {
            val c = raw[i]
            if (quote != null) {
                if (c == '\\' && i + 1 < raw.length) {
                    current.append(c).append(raw[i + 1])
                    i += 2
                    continue
                }
                current.append(c)
                if (c == quote) {
                    quote = null
                    canOpenQuote = false
                }
                i++
                continue
            }

            when {
                c.isWhitespace() && bracketDepth == 0 -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.setLength(0)
                    }
                    canOpenQuote = true
                    i++
                }
                (c == '"' || c == '\'') && canOpenQuote -> {
                    quote = c
                    current.append(c)
                    canOpenQuote = false
                    i++
                }
                c == '[' -> {
                    if (bracketDepth > 0) {
                        throw SelectorSyntaxException("方括号内不能嵌套 [")
                    }
                    bracketDepth = 1
                    current.append(c)
                    canOpenQuote = true
                    i++
                }
                c == ']' -> {
                    if (bracketDepth == 0) {
                        throw SelectorSyntaxException("存在多余的 ]，方括号未配对")
                    }
                    bracketDepth = 0
                    current.append(c)
                    canOpenQuote = false
                    i++
                }
                else -> {
                    current.append(c)
                    canOpenQuote = bracketDepth > 0 && (c == '=' || c == '!' || c == ',' || c == ';' || c.isWhitespace())
                    i++
                }
            }
        }

        if (quote != null) {
            throw SelectorSyntaxException("引号没有正确闭合")
        }
        if (bracketDepth > 0) {
            throw SelectorSyntaxException("方括号 [ 没有正确闭合")
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }
        return result
    }

    /** 解包可选引号字符串，返回 `(解包后文本, 是否带引号)`。 */
    fun unquote(raw: String): Pair<String, Boolean> {
        val text = raw.trim()
        if (text.isEmpty()) return "" to false
        val first = text.first()
        if (first != '"' && first != '\'') {
            return text to false
        }
        val sb = StringBuilder()
        var closed = false
        var i = 1
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                sb.append(text[i + 1])
                i += 2
                continue
            }
            if (c == first) {
                closed = true
                i++
                break
            }
            sb.append(c)
            i++
        }
        if (!closed) {
            throw SelectorSyntaxException("引号没有正确闭合：$text")
        }
        val trailing = text.substring(i).trim()
        if (trailing.isNotEmpty()) {
            throw SelectorSyntaxException("引号结束后存在多余内容：$trailing")
        }
        return sb.toString() to true
    }

    /** 按顶层分隔符切分（忽略引号内的分隔符）。 */
    fun splitTopLevel(body: String, vararg delimiters: Char): List<String> {
        if (body.isEmpty()) return emptyList()
        val delimSet = delimiters.toSet()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var canOpenQuote = true
        var i = 0

        while (i < body.length) {
            val c = body[i]
            if (quote != null) {
                if (c == '\\' && i + 1 < body.length) {
                    current.append(c).append(body[i + 1])
                    i += 2
                    continue
                }
                current.append(c)
                if (c == quote) {
                    quote = null
                    canOpenQuote = false
                }
                i++
                continue
            }
            when {
                (c == '"' || c == '\'') && canOpenQuote -> {
                    quote = c
                    current.append(c)
                    canOpenQuote = false
                    i++
                }
                c in delimSet -> {
                    result.add(current.toString())
                    current.setLength(0)
                    canOpenQuote = true
                    i++
                }
                else -> {
                    current.append(c)
                    canOpenQuote = c == '=' || c == '!' || c.isWhitespace()
                    i++
                }
            }
        }
        if (quote != null) {
            throw SelectorSyntaxException("引号没有正确闭合")
        }
        result.add(current.toString())
        return result
    }

    private fun indexOfTopLevelEquals(part: String): Int {
        var quote: Char? = null
        var canOpenQuote = true
        var i = 0
        while (i < part.length) {
            val c = part[i]
            if (quote != null) {
                if (c == '\\' && i + 1 < part.length) {
                    i += 2
                    continue
                }
                if (c == quote) {
                    quote = null
                    canOpenQuote = false
                }
                i++
                continue
            }
            if ((c == '"' || c == '\'') && canOpenQuote) {
                quote = c
                canOpenQuote = false
                i++
                continue
            }
            if (c == '=') return i
            canOpenQuote = c == '!' || c.isWhitespace()
            i++
        }
        return -1
    }

    /**
     * 解析单个 Minecraft 风格选择器/参数块：
     * - `@target`
     * - `@target[k=v, k2=!v2, flag, !flag2]`
     */
    fun parseSelector(token: String): ParsedSelector {
        val text = token.trim()
        if (!text.startsWith("@")) {
            throw SelectorSyntaxException("选择器必须以 @ 开头：$text")
        }
        val openIdx = text.indexOf('[')
        if (openIdx < 0) {
            val target = text.substring(1)
            if (!targetRegex.matches(target)) {
                throw SelectorSyntaxException("选择器名称非法：$text")
            }
            return ParsedSelector(target, emptyList(), hasBrackets = false, raw = text)
        }

        val target = text.substring(1, openIdx)
        if (!targetRegex.matches(target)) {
            throw SelectorSyntaxException("选择器名称非法：@$target")
        }
        if (!text.endsWith("]")) {
            throw SelectorSyntaxException("选择器方括号未正确闭合或尾部有多余内容：$text")
        }
        val body = text.substring(openIdx + 1, text.length - 1).trim()
        if (body.isEmpty()) {
            return ParsedSelector(target, emptyList(), hasBrackets = true, raw = text)
        }

        val rawParts = splitTopLevel(body, ',')
        val entries = mutableListOf<SelectorEntry>()
        for (rawPart in rawParts) {
            val part = rawPart.trim()
            if (part.isEmpty()) {
                throw SelectorSyntaxException("方括号内存在空参数项（请检查多余逗号）")
            }
            val eqIdx = indexOfTopLevelEquals(part)
            if (eqIdx < 0) {
                val negated = part.startsWith("!")
                val bodyPart = if (negated) part.substring(1).trim() else part
                val (decoded, quoted) = unquote(bodyPart)
                val key = decoded.trim()
                if (key.isEmpty()) {
                    throw SelectorSyntaxException("方括号内参数名不能为空")
                }
                entries.add(SelectorEntry(key = key, value = null, negated = negated, quoted = quoted))
            } else {
                val rawKey = part.substring(0, eqIdx).trim()
                if (rawKey.isEmpty()) {
                    throw SelectorSyntaxException("参数名不能为空：$part")
                }
                if (rawKey.startsWith("!")) {
                    val clean = rawKey.removePrefix("!").trim()
                    throw SelectorSyntaxException("参数名不能以 ! 开头，请写作 $clean=false 或 !$clean")
                }
                if (rawKey.any { it.isWhitespace() || it == '"' || it == '\'' }) {
                    throw SelectorSyntaxException("参数名格式非法：$rawKey")
                }
                val rawVal = part.substring(eqIdx + 1).trim()
                val negated = rawVal.startsWith("!")
                val valPart = if (negated) rawVal.substring(1).trim() else rawVal
                if (valPart.isEmpty()) {
                    throw SelectorSyntaxException("参数 $rawKey 的值不能为空")
                }
                val (decoded, quoted) = unquote(valPart)
                val value = decoded.trim()
                if (value.isEmpty()) {
                    throw SelectorSyntaxException("参数 $rawKey 的值不能为空")
                }
                entries.add(SelectorEntry(key = rawKey, value = value, negated = negated, quoted = quoted))
            }
        }
        return ParsedSelector(target, entries, hasBrackets = true, raw = text)
    }

    /** 将条目解析为布尔开关；支持 `tag` / `!tag` / `tag=true|false|on|off|1|0` / `tag=!true`。 */
    fun parseBoolean(entry: SelectorEntry): Boolean {
        val raw = entry.value ?: return !entry.negated
        val parsed = when (raw.lowercase()) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> throw SelectorSyntaxException("参数 ${entry.key} 需要布尔值（true/false），收到：$raw")
        }
        return if (entry.negated) !parsed else parsed
    }

    /** 解析 Minecraft 风格数值范围：`..20`、`5..20`、`10..`、`15`。 */
    fun parseFloatRange(raw: String, paramName: String = "distance"): SelectorRange {
        val text = raw.trim()
        if (text.isEmpty()) {
            throw SelectorSyntaxException("参数 $paramName 范围不能为空")
        }
        val sep = text.indexOf("..")
        if (sep < 0) {
            val single = text.toFloatOrNull()?.takeIf { it.isFinite() }
                ?: throw SelectorSyntaxException("参数 $paramName 需要数字或范围（如 ..20、5..20），收到：$text")
            return SelectorRange(single, single)
        }
        if (text.indexOf("..", sep + 2) >= 0) {
            throw SelectorSyntaxException("参数 $paramName 范围格式错误：$text")
        }
        val minText = text.substring(0, sep).trim()
        val maxText = text.substring(sep + 2).trim()
        if (minText.isEmpty() && maxText.isEmpty()) {
            throw SelectorSyntaxException("参数 $paramName 范围不能同时省略上下限：$text")
        }
        val min = if (minText.isEmpty()) null else minText.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: throw SelectorSyntaxException("参数 $paramName 下限非法：$minText")
        val max = if (maxText.isEmpty()) null else maxText.toFloatOrNull()?.takeIf { it.isFinite() }
            ?: throw SelectorSyntaxException("参数 $paramName 上限非法：$maxText")
        if (min != null && max != null && min > max) {
            throw SelectorSyntaxException("参数 $paramName 下限($min)不能大于上限($max)")
        }
        return SelectorRange(min, max)
    }
}
