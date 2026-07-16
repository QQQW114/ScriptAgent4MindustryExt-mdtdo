package wayzer.lib

/**
 * MDT 菜单用轻量文本格式工具。
 *
 * Mindustry 标准菜单不支持真正 Markdown/图片，这里只把常用 Markdown 子集转换成
 * Mindustry 可显示的彩色纯文本，供 Wiki、帖子等长文本页面复用。
 */
object MdtTextFormat {
    private val imageRegex = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")
    private val linkRegex = Regex("""(?<!!)\[([^\]]+)\]\(([^)]+)\)""")
    private val boldRegex = Regex("""\*\*(.+?)\*\*""")
    private val underlineRegex = Regex("""__(.+?)__""")
    private val codeRegex = Regex("""`([^`]+)`""")
    private val strikeRegex = Regex("""~~(.+?)~~""")
    private val orderedListRegex = Regex("""^(\d+)[.)]\s+(.+)$""")

    val helpText: String = """
        |[cyan]支持的轻量格式：
        |[gold]# 大标题
        |[cyan]## 小标题
        |[gray]- 列表项[] / [gray]* 列表项
        |[gray]> 引用文本
        |[yellow]**重点文本**[] / [gray]`代码`
        |[gray]--- 分割线
        |[gray][文字](链接) 会显示为文字 + 链接；图片不会嵌入，只显示图片链接。
        |
        |[cyan]换行辅助：
        |[white]\n[]、[white]|[] 或全角 [white]｜[] 会被转为换行，适合在输入框中快速分段。
    """.trimMargin()

    fun normalizeMultilineInput(text: String, pipeAsNewline: Boolean = true): String {
        var result = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
        if (pipeAsNewline) {
            result = result.replace('｜', '\n').replace('|', '\n')
        }
        return result
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }

    fun render(rawText: String): String =
        rawText.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .joinToString("\n") { renderLine(it) }
            .trimEnd()

    fun plainPreview(rawText: String, limit: Int = 24): String {
        var oneLine = rawText.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("""#{1,6}\s+"""), "")
            .replace(Regex("""^\s*[-*+]\s+"""), "")
            .replace(Regex("""^\s*>\s+"""), "")
            .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""__(.+?)__"""), "$1")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""~~(.+?)~~"""), "$1")
            .trim()
        if (oneLine.isBlank()) oneLine = "无内容"
        return if (oneLine.length <= limit) oneLine else oneLine.take(limit) + "..."
    }

    private fun renderLine(rawLine: String): String {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") return "[gray]────────────[]"

        return when {
            trimmed.startsWith("### ") -> "[accent]${renderInline(trimmed.drop(4).trim())}[]"
            trimmed.startsWith("## ") -> "[cyan]${renderInline(trimmed.drop(3).trim())}[]"
            trimmed.startsWith("# ") -> "[gold]${renderInline(trimmed.drop(2).trim())}[]"
            trimmed.startsWith("> ") -> "[gray]┃ ${renderInline(trimmed.drop(2).trim())}[]"
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ->
                "[gray]•[] ${renderInline(trimmed.drop(2).trim())}"
            orderedListRegex.matches(trimmed) -> {
                val match = orderedListRegex.matchEntire(trimmed)!!
                "[gray]${match.groupValues[1]}.[] ${renderInline(match.groupValues[2])}"
            }
            else -> renderInline(rawLine)
        }
    }

    private fun renderInline(text: String): String {
        var result = text
        result = imageRegex.replace(result) {
            val alt = it.groupValues[1].ifBlank { "图片" }
            val url = it.groupValues[2]
            "[gray][图片：$alt] $url[]"
        }
        result = linkRegex.replace(result) {
            "${it.groupValues[1]} [gray](${it.groupValues[2]})[]"
        }
        result = boldRegex.replace(result) { "[yellow]${it.groupValues[1]}[]" }
        result = underlineRegex.replace(result) { "[yellow]${it.groupValues[1]}[]" }
        result = codeRegex.replace(result) { "[gray]${it.groupValues[1]}[]" }
        result = strikeRegex.replace(result) { "[darkgray]${it.groupValues[1]}[]" }
        return result
    }
}
