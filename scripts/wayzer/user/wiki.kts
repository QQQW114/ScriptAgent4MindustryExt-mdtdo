@file:Depends("wayzer/mdtDatabase", "MDT数据库持久化")
@file:Depends("coreMindustry/menu", "Wiki菜单")
@file:Depends("coreMindustry/utilTextInput", "Wiki文本输入")
@file:Depends("wayzer/user/trustLevel", "MDT信任等级")

package wayzer.user

import coreMindustry.MenuBuilder
import cf.wayzer.placehold.PlaceHoldApi.with
import mindustry.gen.Groups
import wayzer.lib.MdtStorage
import wayzer.lib.MdtTextFormat
import wayzer.lib.PlayerData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

name = "MDT Wiki系统"

private val trustLevel = contextScript<TrustLevel>()
private val textInput = contextScript<coreMindustry.UtilTextInput>()

private val WIKI_INDEX_KEY = "wiki.index"
private val WIKI_TRASH_INDEX_KEY = "wiki.trash.index"
private val WIKI_PROTECTED_IDS_KEY = "wiki.protectedIds"
private val WIKI_SEEDED_KEY = "wiki.seeded.v1"
private val WIKI_PAGE_PREFIX = "wiki.page."
private val WIKI_LIST_PAGE_SIZE = 6
private val WIKI_TEXT_PAGE_CHARS = 850
private val WIKI_MAX_ID_LENGTH = 32
private val WIKI_MAX_TITLE_LENGTH = 60
private val WIKI_MAX_BODY_LENGTH = 6000
private val WIKI_MENU_TIMEOUT_MILLIS = 30 * 60_000
private val WIKI_EDIT_INPUT_TIMEOUT_MILLIS = 30 * 60_000
private val WIKI_HISTORY_LIMIT = 10
private val WIKI_DELETE_DAILY_LIMIT = 2
private val WIKI_DELETE_SHORT_LIMIT = 1
private val WIKI_DELETE_SHORT_WINDOW_MILLIS = 30 * 60_000L
private val WIKI_CACHE_TTL_MILLIS = 60_000L
private val WIKI_SUMMARY_FIELDS = listOf("title", "updatedBy", "preview", "bodyLength", "body")
private val WIKI_HISTORY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private data class WikiPage(
    val id: String,
    val title: String,
    val body: String,
    val updatedBy: String = "",
)

private data class WikiPageSummary(
    val id: String,
    val title: String,
    val preview: String,
    val bodyLength: Int,
    val updatedBy: String = "",
)

private data class WikiSummaryPage(
    val items: List<WikiPageSummary>,
    val page: Int,
    val totalPage: Int,
    val total: Int,
)

private data class WikiTimedCache<T>(
    val value: T,
    val loadedAt: Long,
)

private val wikiSummaryPageCache = mutableMapOf<String, WikiTimedCache<WikiSummaryPage>>()
private val wikiPageCache = mutableMapOf<String, WikiTimedCache<WikiPage?>>()
private val wikiHistoryTextCache = mutableMapOf<String, WikiTimedCache<String>>()

private suspend fun <T> db(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun <T> cacheValue(entry: WikiTimedCache<T>?): T? {
    if (entry == null) return null
    return if (System.currentTimeMillis() - entry.loadedAt <= WIKI_CACHE_TTL_MILLIS) entry.value else null
}

private fun clearWikiCache(id: String? = null) {
    if (id == null) {
        wikiSummaryPageCache.clear()
        wikiPageCache.clear()
        wikiHistoryTextCache.clear()
        return
    }
    val normalized = normalizeWikiId(id) ?: return
    wikiSummaryPageCache.clear()
    wikiPageCache.remove(normalized)
    wikiHistoryTextCache.remove(normalized)
}

private data class WikiEditRecord(
    val timeMillis: Long,
    val editorName: String,
    val action: String,
)

private val initialWikiId = "linuxdo-guidelines"
private val initialWikiTitle = "linux do社区准则节选"
private val initialWikiBody = """
以真诚待人为荣，以虚伪欺人为耻；
以友善热心为荣，以傲慢冷漠为耻；
以团结协作为荣，以孤立对抗为耻；
以专业敬业为荣，以敷衍了事为耻。
总体原则
不可以傲慢。这是本论坛的氛围基调，哪怕你技术再牛，我们也不欢迎傲慢。
不可以搞破坏。这毫无疑问，任何可能导致论坛故障或死亡的行为，都不受欢迎。
不可以直接使用AI生成、润色内容。为维护中文互联网环境，此内容只接受截图发出！
具体细则
禁止内容
政治相关。我们不讨论、评价国家政策及国家领导人。若你在其他平台有此类负面发言，这里也不欢迎你。
血腥、暴力、赌博、毒品、涉黑相关。若你在其他平台有此类负面发言，这里也不欢迎你。
色情相关，包括擦边内容。
恶意引战内容。包括易对立话题。
诋毁、侮辱他人的内容。包括挂人带节奏。
谣言、欺诈、黑产类内容。
未明确标注的病毒、恶意软件及网页等。
恶意刷帖、垃圾信息攻击。包括自动发帖、内容无文字意义、滥用彩色文字、隐藏字符等。
为躲避机器审核进行链接特殊处理，包括但不限于：编码、转换/短链、加密、添/替字符、提供脚本/工具。
使用AI生成、润色的文字内容。如果要发，请截图发出。
违规的推广、引流内容。具体见下方细则。
主动私聊发送骚扰、广告相关内容。
社区聊天频道发送打卡类内容。
发布以上内容，发布者自行承担法律责任、道德风险，且最高可永久封禁社区账号。
总的来说，本着我为人人、人人为我的原则，真诚、友善地对待他人，不以一己私利为重，就不会有问题。希望你在这里玩得愉快！
——节选自L站社区准则，未修改内容
https://linux.do/guidelines
""".trimIndent()

private fun wikiKey(id: String, field: String): String = "$WIKI_PAGE_PREFIX$id.$field"

private fun wikiSettings(id: String, fields: Iterable<String>): Map<String, String?> {
    val normalized = normalizeWikiId(id) ?: return emptyMap()
    return MdtStorage.getSettings(fields.map { wikiKey(normalized, it) })
}

private fun normalizeWikiId(input: String): String? {
    val id = input.trim().lowercase()
    if (id.isEmpty() || id.length > WIKI_MAX_ID_LENGTH) return null
    if (!Regex("[a-z0-9_-]+").matches(id)) return null
    return id
}

private fun compactLine(text: String, limit: Int = 16): String {
    return MdtTextFormat.plainPreview(text, limit)
}

private fun sanitizeHistoryField(text: String, limit: Int = 80): String =
    text.replace('\n', ' ')
        .replace('\r', ' ')
        .replace('|', ' ')
        .trim()
        .take(limit)
        .ifBlank { "未知" }

private fun readWikiHistory(id: String): List<WikiEditRecord> {
    val normalized = normalizeWikiId(id) ?: return emptyList()
    return wikiSettings(normalized, listOf("history"))[wikiKey(normalized, "history")]
        .orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val timeMillis = parts[0].toLongOrNull() ?: return@mapNotNull null
            WikiEditRecord(timeMillis, parts[1], parts[2])
        }
        .take(WIKI_HISTORY_LIMIT)
        .toList()
}

private fun saveWikiHistory(id: String, records: List<WikiEditRecord>) {
    val normalized = normalizeWikiId(id) ?: return
    val text = records.take(WIKI_HISTORY_LIMIT).joinToString("\n") {
        "${it.timeMillis}|${sanitizeHistoryField(it.editorName)}|${sanitizeHistoryField(it.action, 24)}"
    }
    MdtStorage.setSetting(wikiKey(normalized, "history"), text)
}

private fun appendWikiHistory(id: String, editorName: String?, action: String) {
    val normalized = normalizeWikiId(id) ?: return
    val record = WikiEditRecord(
        System.currentTimeMillis(),
        sanitizeHistoryField(editorName ?: "控制台"),
        sanitizeHistoryField(action, 24),
    )
    saveWikiHistory(normalized, listOf(record) + readWikiHistory(normalized))
}

private fun wikiHistoryText(id: String): String {
    val records = readWikiHistory(id)
    if (records.isEmpty()) return "[gray]暂无修改记录"
    return records.mapIndexed { index, record ->
        val time = WIKI_HISTORY_TIME_FORMATTER.format(Instant.ofEpochMilli(record.timeMillis))
        "[gray]${index + 1}.[$time] [white]${record.editorName}[]：[cyan]${record.action}"
    }.joinToString("\n")
}

private fun wikiHistoryTextCached(id: String): String {
    val normalized = normalizeWikiId(id) ?: return "[gray]暂无修改记录"
    cacheValue(wikiHistoryTextCache[normalized])?.let { return it }
    val text = wikiHistoryText(normalized)
    wikiHistoryTextCache[normalized] = WikiTimedCache(text, System.currentTimeMillis())
    return text
}

private fun wikiIds(): List<String> = MdtStorage.getSetting(WIKI_INDEX_KEY)
    .orEmpty()
    .split('|')
    .mapNotNull { normalizeWikiId(it) }
    .distinct()

private fun saveWikiIds(ids: List<String>) {
    MdtStorage.setSetting(WIKI_INDEX_KEY, ids.mapNotNull { normalizeWikiId(it) }.distinct().joinToString("|"))
}

private fun wikiTrashIds(): List<String> = MdtStorage.getSetting(WIKI_TRASH_INDEX_KEY)
    .orEmpty()
    .split('|')
    .mapNotNull { normalizeWikiId(it) }
    .distinct()

private fun saveWikiTrashIds(ids: List<String>) {
    MdtStorage.setSetting(WIKI_TRASH_INDEX_KEY, ids.mapNotNull { normalizeWikiId(it) }.distinct().joinToString("|"))
}

private fun protectedWikiIds(): Set<String> = MdtStorage.getSetting(WIKI_PROTECTED_IDS_KEY)
    .orEmpty()
    .split('|')
    .mapNotNull { normalizeWikiId(it) }
    .toSet()

private fun setWikiProtected(id: String, protected: Boolean): Boolean {
    val normalized = normalizeWikiId(id) ?: return false
    if (normalized !in wikiIds()) return false
    val ids = protectedWikiIds().toMutableSet()
    if (protected) ids += normalized else ids -= normalized
    MdtStorage.setSetting(WIKI_PROTECTED_IDS_KEY, ids.sorted().joinToString("|"))
    clearWikiCache(normalized)
    return true
}

private fun isWikiProtected(id: String): Boolean =
    normalizeWikiId(id)?.let { it in protectedWikiIds() } == true

private fun saveWikiDeletedMeta(id: String, actorName: String, reason: String) {
    val normalized = normalizeWikiId(id) ?: return
    MdtStorage.setSetting(wikiKey(normalized, "deletedBy"), sanitizeHistoryField(actorName, 48))
    MdtStorage.setSetting(wikiKey(normalized, "deleteReason"), sanitizeHistoryField(reason, 160))
    MdtStorage.setSetting(wikiKey(normalized, "deletedAt"), System.currentTimeMillis().toString())
}

private fun clearWikiDeletedMeta(id: String) {
    val normalized = normalizeWikiId(id) ?: return
    MdtStorage.setSetting(wikiKey(normalized, "deletedBy"), null)
    MdtStorage.setSetting(wikiKey(normalized, "deleteReason"), null)
    MdtStorage.setSetting(wikiKey(normalized, "deletedAt"), null)
}

private fun wikiDeletedMetaText(id: String): String {
    val normalized = normalizeWikiId(id) ?: return "[gray]删除信息未知"
    val settings = wikiSettings(normalized, listOf("deletedBy", "deleteReason", "deletedAt"))
    val by = settings[wikiKey(normalized, "deletedBy")] ?: "未知"
    val reason = settings[wikiKey(normalized, "deleteReason")] ?: "未填写"
    val at = settings[wikiKey(normalized, "deletedAt")]?.toLongOrNull()
        ?.let { WIKI_HISTORY_TIME_FORMATTER.format(Instant.ofEpochMilli(it)) }
        ?: "未知时间"
    return "[gray]删除者:[white]$by[] / 时间:$at / 原因:$reason"
}

private fun safeSettingSuffix(text: String): String =
    java.lang.Integer.toUnsignedString(text.hashCode(), 36)

private fun wikiDeleteLimitKey(player: Player, scope: String): String =
    "wiki.delete.$scope.${safeSettingSuffix(PlayerData[player].id)}"

private fun canDeleteWikiNow(player: Player): String? {
    if (canAdminWiki(player)) return null
    val dailyKey = wikiDeleteLimitKey(player, "daily.${java.time.LocalDate.now()}")
    val bucket = System.currentTimeMillis() / WIKI_DELETE_SHORT_WINDOW_MILLIS
    val shortKey = wikiDeleteLimitKey(player, "short.$bucket")
    val daily = MdtStorage.getSetting(dailyKey)?.toIntOrNull() ?: 0
    val short = MdtStorage.getSetting(shortKey)?.toIntOrNull() ?: 0
    return when {
        daily >= WIKI_DELETE_DAILY_LIMIT -> "[red]今日删除Wiki次数已达上限：$WIKI_DELETE_DAILY_LIMIT。请联系4级/admin处理。"
        short >= WIKI_DELETE_SHORT_LIMIT -> "[red]短时间删除Wiki过多，请稍后再试或联系4级/admin处理。"
        else -> null
    }
}

private fun recordWikiDelete(player: Player) {
    if (canAdminWiki(player)) return
    val dailyKey = wikiDeleteLimitKey(player, "daily.${java.time.LocalDate.now()}")
    val bucket = System.currentTimeMillis() / WIKI_DELETE_SHORT_WINDOW_MILLIS
    val shortKey = wikiDeleteLimitKey(player, "short.$bucket")
    MdtStorage.setSetting(dailyKey, ((MdtStorage.getSetting(dailyKey)?.toIntOrNull() ?: 0) + 1).toString())
    MdtStorage.setSetting(shortKey, ((MdtStorage.getSetting(shortKey)?.toIntOrNull() ?: 0) + 1).toString())
}

private fun getWikiPage(id: String): WikiPage? {
    val normalized = normalizeWikiId(id) ?: return null
    val settings = wikiSettings(normalized, listOf("title", "body", "updatedBy"))
    val title = settings[wikiKey(normalized, "title")]?.takeIf { it.isNotBlank() } ?: return null
    val body = settings[wikiKey(normalized, "body")]?.takeIf { it.isNotBlank() } ?: return null
    val updatedBy = settings[wikiKey(normalized, "updatedBy")].orEmpty()
    return WikiPage(normalized, title, body, updatedBy)
}

private fun getWikiPageCached(id: String, forceRefresh: Boolean = false): WikiPage? {
    val normalized = normalizeWikiId(id) ?: return null
    if (!forceRefresh) cacheValue(wikiPageCache[normalized])?.let { return it }
    val page = getWikiPage(normalized)
    wikiPageCache[normalized] = WikiTimedCache(page, System.currentTimeMillis())
    return page
}

private fun getWikiSummary(id: String, cachedSettings: Map<String, String?>? = null): WikiPageSummary? {
    val normalized = normalizeWikiId(id) ?: return null
    val settings = cachedSettings ?: wikiSettings(normalized, WIKI_SUMMARY_FIELDS)
    val title = settings[wikiKey(normalized, "title")]?.takeIf { it.isNotBlank() } ?: return null
    val updatedBy = settings[wikiKey(normalized, "updatedBy")].orEmpty()
    val cachedPreview = settings[wikiKey(normalized, "preview")]?.takeIf { it.isNotBlank() }
    val cachedLength = settings[wikiKey(normalized, "bodyLength")]?.toIntOrNull()
    if (cachedPreview != null && cachedLength != null) {
        return WikiPageSummary(normalized, title, cachedPreview, cachedLength, updatedBy)
    }

    // 兼容旧数据：第一次列表读取时才回退读正文，并补齐轻量摘要字段，后续列表不再读正文。
    val body = settings[wikiKey(normalized, "body")]?.takeIf { it.isNotBlank() } ?: return null
    val preview = compactLine(body, 16)
    MdtStorage.setSetting(wikiKey(normalized, "preview"), preview)
    MdtStorage.setSetting(wikiKey(normalized, "bodyLength"), body.length.toString())
    return WikiPageSummary(normalized, title, preview, body.length, updatedBy)
}

private fun listWikiSummariesPaged(requestPage: Int, pageSize: Int = WIKI_LIST_PAGE_SIZE): WikiSummaryPage {
    val ids = wikiIds()
    val total = ids.size
    val totalPage = maxOf(1, (total + pageSize - 1) / pageSize)
    val page = requestPage.coerceIn(1, totalPage)
    val start = (page - 1) * pageSize
    val pageIds = ids.drop(start).take(pageSize)
    val settings = MdtStorage.getSettings(pageIds.flatMap { id -> WIKI_SUMMARY_FIELDS.map { wikiKey(id, it) } })
    val items = pageIds.mapNotNull { getWikiSummary(it, settings) }
    return WikiSummaryPage(items, page, totalPage, total)
}

private fun listWikiTrashSummariesPaged(requestPage: Int, pageSize: Int = WIKI_LIST_PAGE_SIZE): WikiSummaryPage {
    val ids = wikiTrashIds()
    val total = ids.size
    val totalPage = maxOf(1, (total + pageSize - 1) / pageSize)
    val page = requestPage.coerceIn(1, totalPage)
    val start = (page - 1) * pageSize
    val pageIds = ids.drop(start).take(pageSize)
    val settings = MdtStorage.getSettings(pageIds.flatMap { id -> WIKI_SUMMARY_FIELDS.map { wikiKey(id, it) } })
    val items = pageIds.mapNotNull { getWikiSummary(it, settings) }
    return WikiSummaryPage(items, page, totalPage, total)
}

private fun listWikiSummariesPagedCached(requestPage: Int, pageSize: Int = WIKI_LIST_PAGE_SIZE): WikiSummaryPage {
    val key = "$requestPage:$pageSize"
    cacheValue(wikiSummaryPageCache[key])?.let { return it }
    val page = listWikiSummariesPaged(requestPage, pageSize)
    val entry = WikiTimedCache(page, System.currentTimeMillis())
    wikiSummaryPageCache[key] = entry
    wikiSummaryPageCache["${page.page}:$pageSize"] = entry
    return page
}

private fun saveWikiPage(id: String, title: String, body: String, editorName: String?, action: String = "修改"): WikiPage? {
    val normalized = normalizeWikiId(id) ?: return null
    val normalizedTitle = title.replace('\n', ' ').replace('\r', ' ').trim().take(WIKI_MAX_TITLE_LENGTH)
    val normalizedBody = MdtTextFormat.normalizeMultilineInput(body).take(WIKI_MAX_BODY_LENGTH)
    val normalizedEditor = editorName?.takeIf { it.isNotBlank() } ?: "控制台"
    if (normalizedTitle.isBlank() || normalizedBody.isBlank()) return null

    val ids = wikiIds().toMutableList()
    val isNew = normalized !in ids
    if (isNew) {
        ids += normalized
        saveWikiIds(ids)
    }
    MdtStorage.setSetting(wikiKey(normalized, "title"), normalizedTitle)
    MdtStorage.setSetting(wikiKey(normalized, "body"), normalizedBody)
    MdtStorage.setSetting(wikiKey(normalized, "preview"), MdtTextFormat.plainPreview(normalizedBody, 16))
    MdtStorage.setSetting(wikiKey(normalized, "bodyLength"), normalizedBody.length.toString())
    MdtStorage.setSetting(wikiKey(normalized, "updatedBy"), normalizedEditor)
    appendWikiHistory(normalized, normalizedEditor, if (isNew) "新增" else action)
    clearWikiCache(normalized)
    return WikiPage(normalized, normalizedTitle, normalizedBody, normalizedEditor)
}

private fun deleteWikiPage(id: String): Boolean {
    val normalized = normalizeWikiId(id) ?: return false
    if (normalized !in wikiIds()) return false
    saveWikiIds(wikiIds().filterNot { it == normalized })
    saveWikiTrashIds((wikiTrashIds() + normalized).distinct())
    clearWikiCache(normalized)
    return true
}

private fun restoreWikiPage(id: String): Boolean {
    val normalized = normalizeWikiId(id) ?: return false
    if (normalized !in wikiTrashIds()) return false
    val page = getWikiPage(normalized) ?: return false
    saveWikiTrashIds(wikiTrashIds().filterNot { it == normalized })
    saveWikiIds((wikiIds() + page.id).distinct())
    clearWikiDeletedMeta(normalized)
    appendWikiHistory(normalized, "系统", "恢复")
    clearWikiCache(normalized)
    return true
}

private fun purgeWikiPage(id: String): Boolean {
    val normalized = normalizeWikiId(id) ?: return false
    if (normalized !in wikiTrashIds() && normalized !in wikiIds()) return false
    saveWikiIds(wikiIds().filterNot { it == normalized })
    saveWikiTrashIds(wikiTrashIds().filterNot { it == normalized })
    val protected = protectedWikiIds().filterNot { it == normalized }
    MdtStorage.setSetting(WIKI_PROTECTED_IDS_KEY, protected.sorted().joinToString("|"))
    listOf(
        "title", "body", "preview", "bodyLength", "updatedBy", "history",
        "deletedBy", "deleteReason", "deletedAt"
    ).forEach { field ->
        MdtStorage.setSetting(wikiKey(normalized, field), null)
    }
    clearWikiCache(normalized)
    return true
}

private fun splitWikiBody(body: String): List<String> {
    val pages = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        val text = current.toString().trimEnd()
        if (text.isNotBlank()) pages += text
        current.clear()
    }
    body.lines().forEach { line ->
        if (line.length > WIKI_TEXT_PAGE_CHARS) {
            if (current.isNotEmpty()) flush()
            line.chunked(WIKI_TEXT_PAGE_CHARS).forEach { pages += it }
            return@forEach
        }
        if (current.length + line.length + 1 > WIKI_TEXT_PAGE_CHARS && current.isNotEmpty()) flush()
        current.append(line).append('\n')
    }
    flush()
    return pages.ifEmpty { listOf("[gray]暂无内容") }
}

private fun canManageWiki(player: Player): Boolean {
    return with(trustLevel) { hasTrustLevel(player, "3+") }
}

private fun canAdminWiki(player: Player): Boolean {
    return with(trustLevel) { isTrustAdmin(player) }
}

private suspend fun askWikiText(
    player: Player,
    title: String,
    message: String,
    default: String = "",
    limit: Int = WIKI_MAX_BODY_LENGTH,
): String? {
    // 避免在 with(textInput) 的隐式接收者里直接读取脚本级变量，ScriptAgent/Kotlin 脚本会报 implicit receiver 错误。
    val timeoutMillis = WIKI_EDIT_INPUT_TIMEOUT_MILLIS
    return with(textInput) {
        textInput(player, title, message, default = default, lengthLimit = limit, timeoutMillis = timeoutMillis)
    }?.trim()?.takeIf { it.isNotEmpty() }
}

private suspend fun openWikiIndex(player: Player, initialPage: Int = 1) {
    val manager = canManageWiki(player)
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            val pageData = db { listWikiSummariesPagedCached(selectedPage, WIKI_LIST_PAGE_SIZE) }
            selectedPage = pageData.page
            val totalPage = pageData.totalPage
            val pageItems = pageData.items

            title = "Wiki列表"
            msg = if (pageData.total == 0) {
                "[yellow]当前暂无Wiki页面。"
            } else {
                "[cyan]点击条目查看内容。\n[gray]列表仅加载当前页摘要，不预读正文。"
            }

            pageItems.forEach { item ->
                option("[gold]${item.title}\n[gray]${item.preview}") {
                    openWikiPage(player, item.id)
                }
                newRow()
            }
            repeat(WIKI_LIST_PAGE_SIZE - pageItems.size) {
                option("") { refresh() }
                newRow()
            }

            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/$totalPage") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(totalPage); refresh() }
            newRow()
            if (manager) option("管理Wiki") { openWikiManageMenu(player) }
            option("关闭") {}
        }
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private suspend fun openWikiPage(player: Player, id: String, initialPage: Int = 1) {
    val page = db { getWikiPageCached(id) } ?: run {
        player.sendMessage("[yellow]未找到Wiki页面：$id")
        openWikiIndex(player)
        return
    }
    val manager = canManageWiki(player)
    val adminWiki = canAdminWiki(player)
    val protected = db { isWikiProtected(page.id) }
    val canEdit = manager && (!protected || adminWiki)
    val bodyPages = splitWikiBody(MdtTextFormat.render(page.body))
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            selectedPage = selectedPage.coerceIn(1, bodyPages.size)
            title = page.title
            msg = """
                |[gray]Wiki ID: [white]${page.id}[]  [gray]页数: [white]$selectedPage/${bodyPages.size}
                |${if (protected) "[red]保护锁：[white]已开启，仅4级/admin可编辑或删除" else "[gray]保护锁：未开启"}
                |
                |${bodyPages[selectedPage - 1]}
            """.trimMargin()
            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/${bodyPages.size}") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(bodyPages.size); refresh() }
            newRow()
            option("返回列表") { openWikiIndex(player) }
            option("最近修改") { openWikiHistoryMenu(player, page.id) }
            option("分享到聊天") { shareWikiPageToChat(player, page.id) }
            newRow()
            if (canEdit) option("编辑此页") { openWikiEditMenu(player, page.id) }
            if (adminWiki) option(if (protected) "解除保护锁（4）" else "设置保护锁（4）") {
                if (db { setWikiProtected(page.id, !protected) }) {
                    player.sendMessage(if (protected) "[green]已解除Wiki保护锁" else "[green]已设置Wiki保护锁，4级以下不可编辑/删除")
                } else {
                    player.sendMessage("[yellow]保护锁操作失败，页面可能不存在")
                }
                openWikiPage(player, page.id, selectedPage)
            }
            newRow()
            option("关闭") {}
        }
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private suspend fun shareWikiPageToChat(player: Player, id: String) {
    val page = db { getWikiPageCached(id) } ?: run {
        player.sendMessage("[yellow]未找到Wiki页面：$id")
        openWikiIndex(player)
        return
    }
    val message = "[cyan][Wiki分享] [white]{player.name}[cyan] 分享了 Wiki [gold]{title}[cyan]（ID: [white]{id}[cyan]）。[gray]输入 [gold]/wiki {id}[] 快速打开。".with(
        "player" to player,
        "title" to compactLine(page.title, 36),
        "id" to page.id,
    )
    Groups.player.forEach { it.sendMessage(message) }
    player.sendMessage("[green]已分享到聊天栏：/wiki ${page.id}")
    openWikiPage(player, page.id)
}

private suspend fun openWikiFormatHelp(player: Player, backId: String? = null) {
    MenuBuilder<Unit>("Wiki格式帮助") {
        msg = MdtTextFormat.helpText
        if (backId != null) option("返回编辑") { openWikiEditMenu(player, backId) }
        option("返回Wiki列表") { openWikiIndex(player) }
        option("关闭") {}
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private suspend fun openWikiHistoryMenu(player: Player, id: String) {
    val data = db { getWikiPageCached(id)?.let { it to wikiHistoryTextCached(it.id) } } ?: run {
        player.sendMessage("[yellow]未找到Wiki页面：$id")
        openWikiIndex(player)
        return
    }
    val page = data.first
    val historyText = data.second
    MenuBuilder<Unit>("Wiki最近修改：${page.title}") {
        msg = """
            |[cyan]仅记录最近${WIKI_HISTORY_LIMIT}次新增/编辑。
            |
            |$historyText
        """.trimMargin()
        option("返回此页") { openWikiPage(player, page.id) }
        if (canManageWiki(player) && (!db { isWikiProtected(page.id) } || canAdminWiki(player))) option("编辑此页") { openWikiEditMenu(player, page.id) }
        option("关闭") {}
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private suspend fun createWikiFlow(player: Player) {
    if (!canManageWiki(player)) {
        player.sendMessage("[red]权限不足：只有3+级与管理员可以编辑Wiki")
        return
    }
    val id = askWikiText(player, "新增Wiki - ID", "请输入Wiki ID，仅允许英文小写、数字、_、-，最长${WIKI_MAX_ID_LENGTH}字符。", limit = WIKI_MAX_ID_LENGTH)
        ?.let { normalizeWikiId(it) }
    if (id == null) {
        player.sendMessage("[yellow]已取消或ID格式错误")
        openWikiManageMenu(player)
        return
    }
    if (db { getWikiSummary(id) != null }) {
        player.sendMessage("[yellow]该ID已存在：$id")
        openWikiManageMenu(player)
        return
    }
    val title = askWikiText(player, "新增Wiki - 标题", "请输入Wiki标题。", limit = WIKI_MAX_TITLE_LENGTH)
    if (title == null) {
        player.sendMessage("[yellow]已取消输入标题")
        openWikiManageMenu(player)
        return
    }
    val body = askWikiText(
        player,
        "新增Wiki - 内容",
        "请输入Wiki正文，可粘贴多行文本。\n支持 #/## 标题、- 列表、> 引用、**重点**、--- 分割线；可用 \\n 或 | 快速换行。",
        limit = WIKI_MAX_BODY_LENGTH,
    )
    if (body == null) {
        player.sendMessage("[yellow]已取消输入内容")
        openWikiManageMenu(player)
        return
    }
    val editorName = player.plainName()
    val saved = db { saveWikiPage(id, title, body, editorName, "新增") }
    if (saved == null) {
        player.sendMessage("[red]保存失败：标题或正文为空，或ID不合法")
        openWikiManageMenu(player)
        return
    }
    player.sendMessage("[green]已新增Wiki：[white]${saved.title}")
    openWikiPage(player, saved.id)
}

private suspend fun openWikiEditMenu(player: Player, id: String) {
    if (!canManageWiki(player)) {
        player.sendMessage("[red]权限不足：只有3+级与管理员可以编辑Wiki")
        return
    }
    val data = db { getWikiPageCached(id)?.let { it to wikiHistoryTextCached(it.id) } } ?: run {
        player.sendMessage("[yellow]未找到Wiki页面：$id")
        openWikiManageMenu(player)
        return
    }
    val page = data.first
    val historyText = data.second
    val protected = db { isWikiProtected(page.id) }
    val adminWiki = canAdminWiki(player)
    if (protected && !adminWiki) {
        player.sendMessage("[red]此Wiki已被4级/admin设置保护锁，只有4级/admin可以修改或删除。")
        openWikiPage(player, page.id)
        return
    }
    MenuBuilder<Unit>("Wiki管理：${page.title}") {
        msg = """
            |[cyan]ID：[white]${page.id}
            |[cyan]标题：[white]${page.title}
            |[cyan]正文长度：[white]${page.body.length}/${WIKI_MAX_BODY_LENGTH}
            |[cyan]最后编辑：[white]${page.updatedBy.ifBlank { "未知" }}
            |${if (protected) "[red]保护锁：[white]已开启，仅4级/admin可编辑或删除" else "[gray]保护锁：未开启"}
            |
            |[cyan]最近修改：
            |$historyText
        """.trimMargin()
        option("修改标题") {
            val newTitle = askWikiText(player, "修改Wiki标题", "当前标题：${page.title}", default = page.title, limit = WIKI_MAX_TITLE_LENGTH)
            val editorName = player.plainName()
            if (newTitle != null && db { saveWikiPage(page.id, newTitle, page.body, editorName, "修改标题") } != null)
                player.sendMessage("[green]已修改标题")
            openWikiEditMenu(player, page.id)
        }
        option("修改正文") {
            val newBody = askWikiText(
                player,
                "修改Wiki正文",
                "可粘贴多行文本。\n支持 #/## 标题、- 列表、> 引用、**重点**、--- 分割线；可用 \\n 或 | 快速换行。",
                default = page.body,
                limit = WIKI_MAX_BODY_LENGTH,
            )
            val editorName = player.plainName()
            if (newBody != null && db { saveWikiPage(page.id, page.title, newBody, editorName, "修改正文") } != null)
                player.sendMessage("[green]已修改正文")
            openWikiEditMenu(player, page.id)
        }
        newRow()
        option("格式帮助") { openWikiFormatHelp(player, page.id) }
        newRow()
        if (adminWiki) option(if (protected) "解除保护锁（4）" else "设置保护锁（4）") {
            if (db { setWikiProtected(page.id, !protected) }) {
                player.sendMessage(if (protected) "[green]已解除Wiki保护锁" else "[green]已设置Wiki保护锁，4级以下不可编辑/删除")
            } else {
                player.sendMessage("[yellow]保护锁操作失败，页面可能不存在")
            }
            openWikiEditMenu(player, page.id)
        }
        option("删除此页") { confirmDeleteWiki(player, page.id) }
        option("查看此页") { openWikiPage(player, page.id) }
        newRow()
        option("返回管理") { openWikiManageMenu(player) }
        option("关闭") {}
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private suspend fun confirmDeleteWiki(player: Player, id: String) {
    if (!canManageWiki(player)) {
        player.sendMessage("[red]权限不足：只有3+级与管理员可以删除Wiki")
        return
    }
    val page = db { getWikiPageCached(id) } ?: run {
        player.sendMessage("[yellow]未找到Wiki页面：$id")
        openWikiManageMenu(player)
        return
    }
    val protected = db { isWikiProtected(page.id) }
    if (protected && !canAdminWiki(player)) {
        player.sendMessage("[red]此Wiki已被4级/admin设置保护锁，只有4级/admin可以删除。")
        openWikiPage(player, page.id)
        return
    }
    db { canDeleteWikiNow(player) }?.let {
        player.sendMessage(it)
        openWikiEditMenu(player, page.id)
        return
    }
    MenuBuilder<Unit>("确认删除Wiki") {
        msg = "[red]确认删除Wiki：[white]${page.title}[]？\n[gray]页面会进入回收站，4级/admin可恢复或彻底删除。"
        option("确认移入回收站") {
            val reason = askWikiText(player, "删除Wiki理由", "请输入删除理由，最多80字。", limit = 80)
                ?: run {
                    player.sendMessage("[yellow]已取消删除")
                    openWikiEditMenu(player, page.id)
                    return@option
                }
            val actorName = player.plainName()
            if (db { deleteWikiPage(page.id) }) {
                db {
                    saveWikiDeletedMeta(page.id, actorName, reason)
                    appendWikiHistory(page.id, actorName, "删除")
                    recordWikiDelete(player)
                }
                clearWikiCache(page.id)
                player.sendMessage("[green]已将Wiki移入回收站：[white]${page.title}")
            } else player.sendMessage("[yellow]删除失败或页面不存在")
            openWikiManageMenu(player)
        }
        option("取消") { openWikiEditMenu(player, page.id) }
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private suspend fun openWikiTrashMenu(player: Player, initialPage: Int = 1) {
    if (!canAdminWiki(player)) {
        player.sendMessage("[red]权限不足：只有4级/admin可以查看Wiki回收站。")
        return
    }
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            val pageData = db { listWikiTrashSummariesPaged(selectedPage, WIKI_LIST_PAGE_SIZE) }
            selectedPage = pageData.page
            val totalPage = pageData.totalPage
            val pageItems = pageData.items

            title = "Wiki回收站"
            msg = "[cyan]仅4级/admin可恢复或彻底删除。当前页 ${pageItems.size} 条 / 共 ${pageData.total} 条"
            pageItems.forEach { item ->
                val meta = db { wikiDeletedMetaText(item.id) }
                option("[yellow]${item.title}\n[gray]ID: ${item.id} / ${item.bodyLength}字\n$meta") {
                    openWikiTrashPageMenu(player, item.id, selectedPage)
                }
                newRow()
            }
            repeat(WIKI_LIST_PAGE_SIZE - pageItems.size) {
                option("") { refresh() }
                newRow()
            }
            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/$totalPage") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(totalPage); refresh() }
            newRow()
            option("返回管理") { openWikiManageMenu(player) }
            option("关闭") {}
        }
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private suspend fun openWikiTrashPageMenu(player: Player, id: String, trashPage: Int = 1) {
    if (!canAdminWiki(player)) {
        player.sendMessage("[red]权限不足：只有4级/admin可以管理Wiki回收站。")
        return
    }
    val page = db { getWikiPage(id) } ?: run {
        player.sendMessage("[yellow]未找到Wiki页面：$id")
        openWikiTrashMenu(player, trashPage)
        return
    }
    if (page.id !in db { wikiTrashIds() }) {
        player.sendMessage("[yellow]该Wiki不在回收站：${page.id}")
        openWikiPage(player, page.id)
        return
    }
    val meta = db { wikiDeletedMetaText(page.id) }
    MenuBuilder<Unit>("回收站Wiki：${page.title}") {
        msg = """
            |[cyan]ID：[white]${page.id}
            |[cyan]标题：[white]${page.title}
            |[cyan]正文长度：[white]${page.body.length}/${WIKI_MAX_BODY_LENGTH}
            |$meta
            |
            |[gray]正文预览：${compactLine(page.body, 80)}
        """.trimMargin()
        option("恢复Wiki") {
            if (db { restoreWikiPage(page.id) }) {
                clearWikiCache(page.id)
                player.sendMessage("[green]已恢复Wiki：[white]${page.title}")
                openWikiPage(player, page.id)
            } else {
                player.sendMessage("[yellow]恢复失败")
                openWikiTrashMenu(player, trashPage)
            }
        }
        option("彻底删除") { confirmPurgeWikiPage(player, page.id, trashPage) }
        newRow()
        option("返回回收站") { openWikiTrashMenu(player, trashPage) }
        option("关闭") {}
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private suspend fun confirmPurgeWikiPage(player: Player, id: String, trashPage: Int = 1) {
    if (!canAdminWiki(player)) {
        player.sendMessage("[red]权限不足：只有4级/admin可以彻底删除Wiki。")
        return
    }
    val page = db { getWikiPage(id) } ?: run {
        player.sendMessage("[yellow]未找到Wiki页面：$id")
        openWikiTrashMenu(player, trashPage)
        return
    }
    if (page.id !in db { wikiTrashIds() }) {
        player.sendMessage("[yellow]只能彻底删除回收站中的Wiki，请先普通删除到回收站。")
        openWikiPage(player, page.id)
        return
    }
    MenuBuilder<Unit>("彻底删除Wiki") {
        msg = "[red]确认彻底删除Wiki：[white]${page.title}[]？\n[gray]此操作会删除正文、摘要、历史和删除记录，无法从回收站恢复。"
        option("确认彻底删除") {
            if (db { purgeWikiPage(page.id) }) {
                clearWikiCache(page.id)
                player.sendMessage("[green]已彻底删除Wiki：[white]${page.title}")
            } else {
                player.sendMessage("[yellow]彻底删除失败")
            }
            openWikiTrashMenu(player, trashPage)
        }
        option("取消") { openWikiTrashPageMenu(player, page.id, trashPage) }
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private suspend fun openWikiManageMenu(player: Player, initialPage: Int = 1) {
    if (!canManageWiki(player)) {
        player.sendMessage("[red]权限不足：只有3+级与管理员可以编辑Wiki")
        return
    }
    var selectedPage = initialPage
    object : MenuBuilder<Unit>(false) {
        override suspend fun build() {
            val pageData = db { listWikiSummariesPagedCached(selectedPage, WIKI_LIST_PAGE_SIZE) }
            selectedPage = pageData.page
            val totalPage = pageData.totalPage
            val pageItems = pageData.items

            title = "Wiki管理"
            msg = "[cyan]3+级与管理员可在此新增、编辑或删除Wiki。\n[gray]管理列表仅加载当前页摘要。"
            pageItems.forEach { item ->
                option("[gold]${item.title}\n[gray]ID: ${item.id} / ${item.bodyLength}字") { openWikiEditMenu(player, item.id) }
                newRow()
            }
            repeat(WIKI_LIST_PAGE_SIZE - pageItems.size) {
                option("") { refresh() }
                newRow()
            }
            option("<-") { selectedPage = (selectedPage - 1).coerceAtLeast(1); refresh() }
            option("$selectedPage/$totalPage") { refresh() }
            option("->") { selectedPage = (selectedPage + 1).coerceAtMost(totalPage); refresh() }
            newRow()
            option("新增Wiki") { createWikiFlow(player) }
            if (canAdminWiki(player)) option("回收站") { openWikiTrashMenu(player) }
            option("格式帮助") { openWikiFormatHelp(player) }
            option("返回Wiki列表") { openWikiIndex(player) }
            option("关闭") {}
        }
    }.sendTo(player, WIKI_MENU_TIMEOUT_MILLIS)
}

private fun seedInitialWikiIfNeeded() {
    if (MdtStorage.getSetting(WIKI_SEEDED_KEY) == "true") return
    if (getWikiPage(initialWikiId) == null) {
        saveWikiPage(initialWikiId, initialWikiTitle, initialWikiBody, null)
    }
    MdtStorage.setSetting(WIKI_SEEDED_KEY, "true")
}

onEnable {
    launch(Dispatchers.IO) {
        seedInitialWikiIfNeeded()
    }
}

command("wiki", "打开Wiki列表") {
    aliases = listOf("百科", "规则", "wiki列表")
    usage = "[Wiki ID|share <Wiki ID>|admin]"
    attr(ClientOnly)
    body {
        val player = player!!
        when (val sub = arg.firstOrNull()) {
            null -> openWikiIndex(player)
            "share", "分享" -> {
                val id = arg.getOrNull(1) ?: replyUsage()
                shareWikiPageToChat(player, id)
            }
            "admin", "管理" -> openWikiManageMenu(player)
            else -> openWikiPage(player, sub)
        }
    }
}

command("wikiadmin", "Wiki管理：新增/编辑/保护/回收Wiki页面") {
    aliases = listOf("wikimgr", "wiki管理")
    usage = "[add|edit <id>|trash|protect <id>|unprotect <id>|restore <id>|purge <id>]"
    attr(ClientOnly)
    body {
        val player = player!!
        if (!canManageWiki(player)) {
            returnReply("[red]权限不足：只有3+级与管理员可以编辑Wiki".with())
        }
        when (arg.getOrNull(0)?.lowercase()) {
            null -> openWikiManageMenu(player)
            "add", "新增", "添加" -> createWikiFlow(player)
            "trash", "回收站" -> openWikiTrashMenu(player)
            "edit", "编辑" -> {
                val id = arg.getOrNull(1) ?: replyUsage()
                openWikiEditMenu(player, id)
            }
            "protect", "保护", "保护锁" -> {
                if (!canAdminWiki(player)) returnReply("[red]权限不足：只有4级/admin可以设置Wiki保护锁。".with())
                val id = arg.getOrNull(1) ?: replyUsage()
                if (db { setWikiProtected(id, true) }) {
                    reply("[green]已保护Wiki [white]$id[green]，4级以下不可编辑/删除。".with())
                } else {
                    reply("[yellow]未找到可保护的Wiki：[white]$id".with())
                }
            }
            "unprotect", "解除保护", "取消保护" -> {
                if (!canAdminWiki(player)) returnReply("[red]权限不足：只有4级/admin可以解除Wiki保护锁。".with())
                val id = arg.getOrNull(1) ?: replyUsage()
                if (db { setWikiProtected(id, false) }) {
                    reply("[green]已解除Wiki [white]$id[green] 的保护锁。".with())
                } else {
                    reply("[yellow]未找到可解除保护的Wiki：[white]$id".with())
                }
            }
            "restore", "恢复" -> {
                if (!canAdminWiki(player)) returnReply("[red]权限不足：只有4级/admin可以恢复Wiki。".with())
                val id = arg.getOrNull(1) ?: replyUsage()
                if (db { restoreWikiPage(id) }) {
                    reply("[green]已恢复Wiki：[white]$id".with())
                } else {
                    reply("[yellow]恢复失败，Wiki不存在或不在回收站。".with())
                }
            }
            "purge", "彻底删除" -> {
                if (!canAdminWiki(player)) returnReply("[red]权限不足：只有4级/admin可以彻底删除Wiki。".with())
                val id = arg.getOrNull(1) ?: replyUsage()
                confirmPurgeWikiPage(player, id)
            }
            else -> replyUsage()
        }
    }
}
